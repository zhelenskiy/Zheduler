package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.sync.ApiErrorCode
import com.zhelenskiy.zheduler.zheduler.sync.AuthResponse
import com.zhelenskiy.zheduler.zheduler.sync.FakeRemoteSpaceGateway
import com.zhelenskiy.zheduler.zheduler.sync.Outcome
import com.zhelenskiy.zheduler.zheduler.sync.RemoteError
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetup
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupStage
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupState
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSpaceLinks
import com.zhelenskiy.zheduler.zheduler.sync.SignedInAccount
import com.zhelenskiy.zheduler.zheduler.sync.SpacePushResponse
import com.zhelenskiy.zheduler.zheduler.sync.SpaceSyncService
import com.zhelenskiy.zheduler.zheduler.sync.StoredCredentials
import com.zhelenskiy.zheduler.zheduler.sync.inMemoryStore
import com.zhelenskiy.zheduler.zheduler.sync.testAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import pro.respawn.flowmvi.dsl.subscribe
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The space list's side of sync: what an intent does to the state a screen renders.
 *
 * Intents are handled on the store's own coroutine, so every assertion waits for the state it is
 * about rather than reading straight after sending — the shape that otherwise passes on a fast
 * machine and fails on a loaded one.
 */
class SpaceListSyncTest {

    private val gateway = FakeRemoteSpaceGateway()
    private val repository = InMemoryTaskRepository()
    private val links = inMemoryStore(RemoteSpaceLinks())
    private val credentials = inMemoryStore(StoredCredentials())
    private val sync = SpaceSyncService(gateway, repository, links, credentials)
    private val address = testAddress()

    private val container = SpaceListContainer(
        repository = repository,
        sync = sync,
        newRemoteId = { "remote-fixed" },
        dispatcher = Dispatchers.Unconfined,
    )

    private val seen = MutableStateFlow(SpaceListState())

    @AfterTest
    fun tearDown() = container.close()

    /**
     * Runs [block] with the store subscribed, which is what makes its `whileSubscribed` work — the
     * collector that keeps [SpaceListState.remoteLinks] current — actually run.
     */
    private fun onScreen(block: suspend () -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.subscribe(container.store) { state: SpaceListState -> seen.value = state }
        try {
            block()
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    /** The first state that satisfies [predicate], or an assertion failure naming what was wanted. */
    private suspend fun until(reason: String, predicate: (SpaceListState) -> Boolean): SpaceListState =
        try {
            withTimeout(TIMEOUT_MILLIS) { seen.first(predicate) }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $reason; the state was ${seen.value}")
        }

    private fun send(intent: SpaceListIntent) = container.store.intent(intent)

    private suspend fun signedIn(): SignedInAccount =
        assertIs<Outcome.Success<SignedInAccount>>(
            sync.signUp(address, "ada", "a long enough password")
        ).value

    /**
     * Sends the intent and waits for the space to appear, returning its id.
     *
     * Polled rather than awaited on state: the space's existence is the repository's news, not the
     * store's, and `remoteLinks` only follows for a space that was uploaded.
     */
    private suspend fun addSpace(name: String, prefix: String, account: SignedInAccount?): String {
        val before = repository.getAllSpaces().map { it.id }.toSet()
        send(SpaceListIntent.AddSpace(name, prefix, account))
        try {
            return withTimeout(TIMEOUT_MILLIS) {
                while (true) {
                    repository.getAllSpaces().firstOrNull { it.id !in before }?.let { return@withTimeout it.id }
                    delay(POLL_MILLIS)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("the space \"$name\" was never created")
        }
    }

    // ------------------------------------------------------------ setting a server up

    @Test
    fun `checking a server that answers moves on to the credentials`() = onScreen {
        send(SpaceListIntent.UpdateRemoteSetup(RemoteSetup.turnedOn(RemoteSetupState(addressText = address.value))))
        send(SpaceListIntent.CheckRemoteServer)

        until("the credentials to be asked for") {
            it.remoteSetup?.stage is RemoteSetupStage.Authenticating
        }
    }

    @Test
    fun `a server that does not answer leaves the error under the address`() = onScreen {
        gateway.onServerInfo = { Outcome.Failure(RemoteError.Unreachable("refused")) }
        send(SpaceListIntent.UpdateRemoteSetup(RemoteSetup.turnedOn(RemoteSetupState(addressText = address.value))))
        send(SpaceListIntent.CheckRemoteServer)

        val state = until("the failure to be shown") {
            (it.remoteSetup?.stage as? RemoteSetupStage.Addressing)?.error != null
        }
        assertEquals(
            RemoteError.Unreachable("refused"),
            (state.remoteSetup?.stage as RemoteSetupStage.Addressing).error,
        )
    }

    @Test
    fun `an address this app will not use is refused without contacting anything`() = onScreen {
        send(
            SpaceListIntent.UpdateRemoteSetup(
                RemoteSetup.turnedOn(RemoteSetupState(addressText = "http://sync.example.com"))
            )
        )
        send(SpaceListIntent.CheckRemoteServer)

        until("the address to be refused") {
            (it.remoteSetup?.stage as? RemoteSetupStage.Addressing)?.error != null
        }
        assertTrue(gateway.calls.isEmpty(), "the password's server was contacted anyway: ${gateway.calls}")
    }

    @Test
    fun `a rejected password is reported without losing the address`() = onScreen {
        gateway.onLogIn = { _, _ ->
            Outcome.Failure(RemoteError.Rejected(ApiErrorCode.InvalidCredentials, "no"))
        }
        send(SpaceListIntent.UpdateRemoteSetup(RemoteSetup.turnedOn(RemoteSetupState(addressText = address.value))))
        send(SpaceListIntent.CheckRemoteServer)
        val answered = until("the credentials to be asked for") {
            it.remoteSetup?.stage is RemoteSetupStage.Authenticating
        }

        send(
            SpaceListIntent.UpdateRemoteSetup(
                assertNotNull(answered.remoteSetup).copy(username = "ada", password = "wrong password")
            )
        )
        send(SpaceListIntent.AuthenticateRemote)

        val rejected = until("the password to be rejected") {
            (it.remoteSetup?.stage as? RemoteSetupStage.Authenticating)?.error != null
        }
        val stage = rejected.remoteSetup?.stage as RemoteSetupStage.Authenticating
        assertEquals(address, stage.address, "the server was forgotten along with the password")
    }

    @Test
    fun `signing in reaches Ready and offers the account`() = onScreen {
        gateway.onLogIn = { _, _ -> Outcome.Success(AuthResponse("tkn", "u1", "ada", 0)) }
        send(SpaceListIntent.UpdateRemoteSetup(RemoteSetup.turnedOn(RemoteSetupState(addressText = address.value))))
        send(SpaceListIntent.CheckRemoteServer)
        val answered = until("the credentials to be asked for") {
            it.remoteSetup?.stage is RemoteSetupStage.Authenticating
        }

        send(
            SpaceListIntent.UpdateRemoteSetup(
                assertNotNull(answered.remoteSetup).copy(username = "ada", password = "a long enough password")
            )
        )
        send(SpaceListIntent.AuthenticateRemote)

        val ready = until("the sign-in to finish") { it.remoteSetup?.stage is RemoteSetupStage.Ready }
        assertEquals("ada", assertNotNull(ready.remoteSetup?.readyAccount).key.username)
    }

    @Test
    fun `two taps on sign in mint only one token`() = onScreen {
        // The second token would never be filed — only the first is — and would stay live on the
        // server until it expired, with nothing on this device able to revoke it.
        val held = CompletableDeferred<Unit>()
        var attempts = 0
        gateway.onLogIn = { _, _ ->
            attempts++
            held.await()
            Outcome.Success(AuthResponse("tkn", "u1", "ada", 0))
        }

        send(SpaceListIntent.UpdateRemoteSetup(RemoteSetup.turnedOn(RemoteSetupState(addressText = address.value))))
        send(SpaceListIntent.CheckRemoteServer)
        val answered = until("the credentials to be asked for") {
            it.remoteSetup?.stage is RemoteSetupStage.Authenticating
        }
        send(
            SpaceListIntent.UpdateRemoteSetup(
                assertNotNull(answered.remoteSetup).copy(username = "ada", password = "a long enough password")
            )
        )

        send(SpaceListIntent.AuthenticateRemote)
        until("the first attempt to be in flight") {
            (it.remoteSetup?.stage as? RemoteSetupStage.Authenticating)?.busy == true
        }
        send(SpaceListIntent.AuthenticateRemote)

        held.complete(Unit)
        until("the sign-in to finish") { it.remoteSetup?.stage is RemoteSetupStage.Ready }
        assertEquals(1, attempts, "the second tap started a second sign-in")
    }

    // --------------------------------------------------------------- creating a space

    @Test
    fun `a new space with an account is created locally and uploaded`() = onScreen {
        val account = signedIn()
        val spaceId = addSpace("Work", "WRK", account)

        // Waited on `isUploaded`, not on the link existing: the link is written before the upload
        // is attempted, so its mere presence says only that a server was chosen.
        val state = until("the upload to be acknowledged") { it.remoteLinks[spaceId]?.isUploaded == true }
        val link = assertNotNull(state.remoteLinks[spaceId])
        assertEquals("remote-fixed", link.remoteSpaceId)
        assertEquals(1L, link.lastSyncedRevision)
        assertTrue(state.syncFailures.isEmpty())
    }

    @Test
    fun `a new space whose upload fails still exists locally, with the failure shown`() = onScreen {
        // Refusing to create it would throw away everything the user typed over a network blip.
        val account = signedIn()
        gateway.onCreate = { _, _, _ -> Outcome.Failure(RemoteError.Unreachable("down")) }
        val spaceId = addSpace("Work", "WRK", account)

        val state = until("the failure to be shown") { it.syncFailures.containsKey(spaceId) }
        assertEquals(RemoteError.Unreachable("down"), state.syncFailures[spaceId])
        // Linked, so the row offers a retry that can work — but not uploaded, so it does not
        // claim to be backed up.
        assertEquals(false, assertNotNull(state.remoteLinks[spaceId]).isUploaded)
        assertTrue(state.uploading.isEmpty())
        assertNotNull(repository.getSpaceById(spaceId))
    }

    @Test
    fun `a new space with no account is not uploaded at all`() = onScreen {
        addSpace("Work", "WRK", account = null)
        assertTrue(gateway.calls.isEmpty(), "made ${gateway.calls}")
    }

    // ----------------------------------------------------------------------- uploading

    @Test
    fun `uploading advances the recorded revision and clears the last failure`() = onScreen {
        val account = signedIn()
        val spaceId = addSpace("Work", "WRK", account)
        until("the first upload") { it.remoteLinks[spaceId]?.isUploaded == true }

        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.ServerFault(503)) }
        send(SpaceListIntent.UploadSpace(spaceId))
        until("the failure to be shown") { it.syncFailures.containsKey(spaceId) }

        gateway.onUpdate = { _, remoteId, revision, _ ->
            Outcome.Success(SpacePushResponse(remoteId, revision + 1, 900))
        }
        send(SpaceListIntent.UploadSpace(spaceId))

        val state = until("the revision to advance") {
            it.remoteLinks[spaceId]?.lastSyncedRevision == 2L
        }
        assertNull(state.syncFailures[spaceId], "the old failure was left on screen")
    }

    @Test
    fun `a conflict is kept against the space it happened to`() = onScreen {
        val account = signedIn()
        val work = addSpace("Work", "WRK", account)
        val home = addSpace("Home", "HOM", account)
        until("both uploads") { it.remoteLinks[work]?.isUploaded == true && it.remoteLinks[home]?.isUploaded == true }

        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Conflict(4L)) }
        send(SpaceListIntent.UploadSpace(work))

        val state = until("the conflict") { it.syncFailures.containsKey(work) }
        assertEquals(RemoteError.Conflict(4L), state.syncFailures[work])
        assertNull(state.syncFailures[home], "one space's failure was shown against another's row")
    }

    // ------------------------------------------------------------------ signing in again

    @Test
    fun `signing in again uploads the space that was stuck`() = onScreen {
        val account = signedIn()
        val spaceId = addSpace("Work", "WRK", account)
        until("the first upload") { it.remoteLinks[spaceId]?.isUploaded == true }

        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.AuthenticationRequired()) }
        send(SpaceListIntent.UploadSpace(spaceId))
        until("the session to be reported as over") {
            it.syncFailures[spaceId] is RemoteError.AuthenticationRequired
        }

        send(SpaceListIntent.BeginReauth(spaceId))
        val opened = until("the sign-in dialog") { it.reauthSpaceId == spaceId }
        val setup = assertNotNull(opened.remoteSetup)
        // The username is filled in and the address is fixed: this is about the password, and
        // letting either be retyped here would move the space to another account without saying so.
        assertEquals("ada", setup.username)
        assertEquals(address, assertIs<RemoteSetupStage.Authenticating>(setup.stage).address)

        gateway.onUpdate = { _, remoteId, revision, _ ->
            Outcome.Success(SpacePushResponse(remoteId, revision + 1, 950))
        }
        send(SpaceListIntent.UpdateRemoteSetup(setup.copy(password = "a long enough password")))
        send(SpaceListIntent.AuthenticateRemote)

        val recovered = until("the stuck space to go up") {
            it.reauthSpaceId == null && it.remoteLinks[spaceId]?.lastSyncedRevision == 2L
        }
        assertNull(recovered.syncFailures[spaceId])
    }

    @Test
    fun `signing in again for a space that is not linked opens nothing`() = onScreen {
        val spaceId = addSpace("Work", "WRK", account = null)
        send(SpaceListIntent.BeginReauth(spaceId))
        send(SpaceListIntent.ClearRemoteSetup)

        // Nothing to sign in to, so the dialog never opens; the reset that follows is what proves
        // the intent was handled rather than still queued.
        val state = until("the reset to be handled") { it.remoteSetup == RemoteSetupState() }
        assertNull(state.reauthSpaceId)
    }

    // ------------------------------------------------------------------------ deleting

    @Test
    fun `deleting a space forgets its link`() = onScreen {
        // Space ids come back around after a deletion; a link left behind would reattach itself
        // to whichever space next takes the id.
        val account = signedIn()
        val spaceId = addSpace("Work", "WRK", account)
        until("the upload") { it.remoteLinks[spaceId]?.isUploaded == true }

        send(SpaceListIntent.DeleteSpace(spaceId))

        until("the link to be forgotten") { !it.remoteLinks.containsKey(spaceId) }
        assertNull(sync.linkFor(spaceId))
        assertTrue("deleteSpace" !in gateway.calls, "the server's copy should be left alone")
    }

    @Test
    fun `erasing everything forgets every link`() = onScreen {
        val account = signedIn()
        val work = addSpace("Work", "WRK", account)
        val home = addSpace("Home", "HOM", account)
        until("both uploads") { it.remoteLinks.values.count { link -> link.isUploaded } == 2 }

        send(SpaceListIntent.ClearAllData)

        val state = until("every link to be forgotten") { it.remoteLinks.isEmpty() }
        assertTrue(state.syncFailures.isEmpty())
        assertNull(sync.linkFor(work))
        assertNull(sync.linkFor(home))
    }

    @Test
    fun `forgetting a link leaves the space alone`() = onScreen {
        val account = signedIn()
        val spaceId = addSpace("Work", "WRK", account)
        until("the upload") { it.remoteLinks[spaceId]?.isUploaded == true }

        send(SpaceListIntent.ForgetRemoteLink(spaceId))

        until("the link to be forgotten") { !it.remoteLinks.containsKey(spaceId) }
        assertNotNull(repository.getSpaceById(spaceId))
    }

    @Test
    fun `the server section resets between dialogs`() = onScreen {
        // Otherwise the next new-space dialog opens already signed in to a server the user had
        // walked away from.
        send(SpaceListIntent.UpdateRemoteSetup(RemoteSetup.turnedOn(RemoteSetupState(addressText = address.value))))
        until("the address to be held") { it.remoteSetup?.addressText == address.value }

        send(SpaceListIntent.ClearRemoteSetup)
        until("the reset") { it.remoteSetup == RemoteSetupState() }
    }

    @Test
    fun `a space list with no sync at all has no server section`() = runBlocking {
        val plain = SpaceListContainer(repository, sync = null, dispatcher = Dispatchers.Unconfined)
        try {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val states = MutableStateFlow(SpaceListState())
            val job = scope.subscribe(plain.store) { state: SpaceListState -> states.value = state }
            try {
                assertNull(withTimeout(TIMEOUT_MILLIS) { states.first() }.remoteSetup)
            } finally {
                job.cancel()
                scope.cancel()
            }
        } finally {
            plain.close()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 5L
    }
}
