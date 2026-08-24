@file:OptIn(ExperimentalTime::class, ExperimentalTestApi::class)

package com.zhelenskiy.zheduler.zheduler.sync

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.ServerConfig
import com.zhelenskiy.zheduler.zheduler.StorageConfig
import com.zhelenskiy.zheduler.zheduler.components.dialogs.NewSpaceDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.PutOnServerTags
import com.zhelenskiy.zheduler.zheduler.components.dialogs.PutSpaceOnServerDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.RemoteSetupTags
import com.zhelenskiy.zheduler.zheduler.store.InMemorySyncStore
import com.zhelenskiy.zheduler.zheduler.syncModule
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListIntent
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import pro.respawn.flowmvi.dsl.subscribe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * The whole feature, used: a real Netty server, the real HTTP gateway, the real view model, and
 * the real dialog clicked through the way a person would.
 *
 * Everything below this is tested against a stand-in for something — the dialog against a state
 * machine, the gateway against a mock engine, the store against a fake gateway. This is the one
 * that would notice if those pieces disagreed: a test tag that moved, a button that stays disabled
 * because the state it waits on never arrives, an address the client builds and the server does
 * not route.
 */
class SpaceCreationAgainstARealServerTest {

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var syncClient: HttpClient
    private lateinit var repository: InMemoryTaskRepository
    private lateinit var sync: SpaceSyncService
    private lateinit var container: SpaceListContainer
    private var serverPort = 0
    private var mintedRemoteIds = 0
    private val extraClients = mutableListOf<HttpClient>()
    private var secondServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var secondServerPort = 0
    private val secondServerStore = InMemorySyncStore()
    private val secondServerUrl get() = "http://127.0.0.1:$secondServerPort"
    private val serverStore = InMemorySyncStore()

    private val seen = MutableStateFlow(SpaceListState())
    private var screenScope: CoroutineScope? = null

    private val serverUrl get() = "http://127.0.0.1:$serverPort"

    // Not inside a coroutine: EmbeddedServer.start blocks on coroutines of its own and deadlocks
    // when called from one.
    @Before
    fun startEverything() {
        val config = ServerConfig(
            port = 0,
            host = "127.0.0.1",
            storage = StorageConfig.InMemory,
            tokenLifetime = 30.days,
            allowedOrigins = emptyList(),
            trustForwardedHeaders = false,
            strictTransportSecurity = false,
        )
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            syncModule(config, serverStore, Clock.System)
        }
        server.start(wait = false)
        serverPort = runBlocking { server.engine.resolvedConnectors().first().port }

        syncClient = HttpClient(CIO) { installSyncClientDefaults() }
        repository = InMemoryTaskRepository()
        sync = SpaceSyncService(
            gateway = KtorRemoteSpaceGateway(syncClient),
            repository = repository,
            links = inMemoryStore(SyncSettings()),
            credentials = inMemoryStore(StoredCredentials()),
        )
        container = SpaceListContainer(
            repository = repository,
            sync = sync,
            // Distinct per call, and named so the first one still reads as it always did. A
            // constant here would hide the very thing the double-press test looks for: two
            // uploads racing would collide on one id and the server would end up with one space
            // whether or not the claim that prevents them exists.
            newRemoteId = { "remote-under-test" + "-${mintedRemoteIds++}".takeIf { mintedRemoteIds > 1 }.orEmpty() },
            dispatcher = Dispatchers.Unconfined,
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        screenScope = scope
        scope.subscribe(container.store) { state: SpaceListState -> seen.value = state }
    }

    @After
    fun stopEverything() {
        screenScope?.cancel()
        container.close()
        syncClient.close()
        extraClients.forEach { it.close() }
        secondServer?.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        secondServerStore.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        serverStore.close()
    }



    /**
     * A second client, with nothing of this device's, signed in to the same account.
     *
     * For reading back what the server actually holds. A local export proves only what this
     * device thinks; the point of an upload is what somebody else can fetch.
     */
    private suspend fun freshClientSignedIn(): Triple<SignedInAccount, SpaceSyncService, InMemoryTaskRepository> {
        val theirRepository = InMemoryTaskRepository()
        val theirClient = HttpClient(CIO) { installSyncClientDefaults() }
        extraClients += theirClient
        val theirSync = SpaceSyncService(
            gateway = KtorRemoteSpaceGateway(theirClient),
            repository = theirRepository,
            links = inMemoryStore(SyncSettings()),
            credentials = inMemoryStore(StoredCredentials()),
            revocations = CoroutineScope(Dispatchers.Unconfined),
        )
        val address = assertNotNull(ServerAddress.parse(serverUrl).getOrNull())
        val account = assertNotNull(
            theirSync.signIn(address, "ada", "a long enough password").getOrNull()
        )
        return Triple(account, theirSync, theirRepository)
    }


    /** A second, independent server, for the case where the user changes their mind. */
    private fun startSecondServer() {
        val config = ServerConfig(
            port = 0,
            host = "127.0.0.1",
            storage = StorageConfig.InMemory,
            tokenLifetime = 30.days,
            allowedOrigins = emptyList(),
            trustForwardedHeaders = false,
            strictTransportSecurity = false,
        )
        val other = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            syncModule(config, secondServerStore, Clock.System)
        }
        other.start(wait = false)
        secondServer = other
        secondServerPort = runBlocking { other.engine.resolvedConnectors().first().port }
    }

    /** Brings the server back where it was, so a client's stored address still reaches it. */
    private fun restartServerOn(port: Int) {
        val config = ServerConfig(
            port = port,
            host = "127.0.0.1",
            storage = StorageConfig.InMemory,
            tokenLifetime = 30.days,
            allowedOrigins = emptyList(),
            trustForwardedHeaders = false,
            strictTransportSecurity = false,
        )
        server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            syncModule(config, serverStore, Clock.System)
        }
        server.start(wait = false)
    }

    private fun until(reason: String, predicate: (SpaceListState) -> Boolean): SpaceListState =
        runBlocking {
            try {
                withTimeout(15_000) { seen.first(predicate) }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("timed out waiting for $reason; the state was ${seen.value}")
            }
        }

    @Test
    fun aPersonCanPointANewSpaceAtAServer_signUp_andHaveItUploaded() = runComposeUiTest {
        var created = false
        setContent {
            val state by seen.collectAsState()
            NewSpaceDialog(
                onDismiss = {},
                onSpaceCreated = { name, prefix, account ->
                    created = true
                    container.store.intent(SpaceListIntent.AddSpace(name, prefix, account))
                },
                remoteSetup = state.remoteSetup,
                onRemoteSetupChange = { edit -> container.store.intent(SpaceListIntent.EditRemoteSetup(edit)) },
                onCheckServer = { typed -> container.store.intent(SpaceListIntent.CheckRemoteServer(typed)) },
                onAuthenticate = { user, secret ->
                    container.store.intent(SpaceListIntent.AuthenticateRemote(user, secret))
                },
            )
        }
        waitForIdle()

        // 1. Name the space.
        onNodeWithText("Space Name").performTextInput("Work")
        onNodeWithText("ID Prefix (e.g., WORK, HOME)").performTextInput("WRK")
        waitForIdle()

        // 2. Ask for it to be kept on a server, and give the address.
        onNodeWithTag(RemoteSetupTags.TOGGLE).performClick()
        waitForIdle()
        onNodeWithText("Create").assertIsNotEnabled()

        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput(serverUrl)
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.CONNECT).assertIsEnabled().performClick()

        // 3. The server answers, and the credentials appear.
        until("the server to answer") { it.remoteSetup?.stage is RemoteSetupStage.Authenticating }
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.USERNAME).assertIsDisplayed()

        // 4. Make an account and sign in.
        onNodeWithText("Create an account").performClick()
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.USERNAME).performTextInput("ada")
        onNodeWithTag(RemoteSetupTags.PASSWORD).performTextInput("a long enough password")
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.SUBMIT).assertIsEnabled().performClick()

        until("the sign-up to finish") { it.remoteSetup?.stage is RemoteSetupStage.Ready }
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.SIGNED_IN).assertIsDisplayed()

        // 5. Create the space, which uploads it.
        onNodeWithText("Create").assertIsEnabled().performClick()
        waitForIdle()
        assertTrue(created)

        val spaceId = runBlocking { repository.getAllSpaces().single().id }
        val state = until("the upload to be recorded") { it.remoteLinks.containsKey(spaceId) }
        val link = assertNotNull(state.remoteLinks[spaceId])
        assertEquals(1L, link.lastSyncedRevision)
        assertEquals("ada", link.account.username)
        assertEquals(serverUrl, link.account.serverUrl)

        // 6. And the server really has it: fetched back over HTTP, it is this space's export.
        val fetched = runBlocking {
            val summaries = assertNotNull(sync.listRemoteSpaces(link.account).getOrNull())
            assertEquals(listOf("remote-under-test"), summaries.map { it.remoteId })
            assertEquals("Work", summaries.single().name)
            assertNotNull(repository.exportSpaceToJson(spaceId, prettyPrint = false))
        }
        assertTrue(fetched.contains("\"WRK\""), "the uploaded space should carry its own prefix")
    }


    /**
     * A space that already exists, sent to a server afterwards.
     *
     * The other route into the cloud, and the one a person actually takes: spaces are made long
     * before anybody has a server to put them on. What matters is that it ends up in the same
     * state as a space created on one — same link, same revision, same contents up there — because
     * from that moment nothing else in the app distinguishes the two.
     */
    @Test
    fun anExistingSpaceCanBePutOnAServerAndReallyArrives() = runComposeUiTest {
        val space = runBlocking {
            val made = assertNotNull(repository.createSpace("Notes", "NTS"))
            assertNotNull(repository.addTask(made.id, title = "written long before any server"))
            made
        }

        container.store.intent(SpaceListIntent.BeginPutOnServer(space.id, space.name))
        until("the page to open") { it.putOnServer?.spaceId == space.id }

        setContent {
            val state by seen.collectAsState()
            val prompt = state.putOnServer
            val setup = state.remoteSetup
            if (prompt != null && setup != null) {
                PutSpaceOnServerDialog(
                    spaceName = prompt.spaceName,
                    state = setup,
                    onEdit = { edit -> container.store.intent(SpaceListIntent.EditRemoteSetup(edit)) },
                    onCheckServer = { typed ->
                        container.store.intent(SpaceListIntent.CheckRemoteServer(typed))
                    },
                    onAuthenticate = { user, secret ->
                        container.store.intent(SpaceListIntent.AuthenticateRemote(user, secret))
                    },
                    onConfirm = { account ->
                        container.store.intent(SpaceListIntent.PutOnServer(prompt.spaceId, account))
                    },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()

        // Nothing to upload to yet.
        onNodeWithTag(PutOnServerTags.CONFIRM).assertIsNotEnabled()

        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput(serverUrl)
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.CONNECT).assertIsEnabled().performClick()
        until("the server to answer") { it.remoteSetup?.stage is RemoteSetupStage.Authenticating }
        waitForIdle()

        onNodeWithText("Create an account").performClick()
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.USERNAME).performTextInput("ada")
        onNodeWithTag(RemoteSetupTags.PASSWORD).performTextInput("a long enough password")
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.SUBMIT).assertIsEnabled().performClick()
        until("the sign-up to finish") { it.remoteSetup?.stage is RemoteSetupStage.Ready }
        waitForIdle()

        onNodeWithTag(PutOnServerTags.CONFIRM).assertIsEnabled().performClick()

        // Waited on `isUploaded`, not on the link existing: a link is written before the upload
        // is attempted, so its presence alone would pass whether anything reached the server.
        val linked = until("the upload to land") { it.remoteLinks[space.id]?.isUploaded == true }
        val link = assertNotNull(linked.remoteLinks[space.id])
        assertEquals(1L, link.lastSyncedRevision)
        assertEquals("ada", link.account.username)
        // And the page closes itself, which is how the user knows it worked.
        until("the page to close") { it.putOnServer == null }

        // The server really has it, contents and all — read back over the wire by a client that
        // has never seen this device's database. Asserting on the local export would have passed
        // whatever the server received, including nothing at all.
        runBlocking {
            val summaries = assertNotNull(sync.listRemoteSpaces(link.account).getOrNull())
            assertEquals(listOf("Notes"), summaries.map { it.name })

            val elsewhere = freshClientSignedIn()
            val pulled = assertNotNull(
                elsewhere.second.download(elsewhere.first.key, link.remoteSpaceId).getOrNull()
            )
            assertEquals(
                listOf("written long before any server"),
                elsewhere.third.getAllTasks(pulled.space.id).map { it.title },
            )
        }
    }

    /**
     * Signing out of a space whose session has already ended.
     *
     * The complaint this was written for: it left two "Sign in again" buttons under the space —
     * one from where the space now stands, one from the sign-out that had just failed. What the
     * action did is said in passing; what the space needs is said once, on its row.
     */
    @Test
    fun signingOutOfASpaceWithNoAccessSaysSoWithoutASecondButton() = runComposeUiTest {
        val spaceId = runBlocking {
            val account = assertNotNull(
                sync.signUp(
                    assertNotNull(ServerAddress.parse(serverUrl).getOrNull()),
                    "ada",
                    "a long enough password",
                ).getOrNull()
            )
            val made = assertNotNull(repository.createSpace("Work", "WRK"))
            assertNotNull(sync.linkAndUpload(made.id, account, "remote-signout").getOrNull())
            made.id
        }
        // The session ends — which on this device means the token is gone.
        runBlocking { assertNotNull(sync.signOut(AccountKey(serverUrl, "ada")).getOrNull()) }

        // Now anything the user tries files a failure against the space, and that failure carries
        // a "Sign in again" button of its own.
        container.store.intent(SpaceListIntent.UploadSpace(spaceId))
        val stuck = until("a failure to be filed") { it.syncFailures.containsKey(spaceId) }
        assertEquals(
            RemoteRemedy.SignIn,
            assertNotNull(stuck.syncFailures[spaceId]).remedy,
            "this test is only meaningful while the filed failure is the sign-in kind",
        )

        container.store.intent(SpaceListIntent.SignOutOfSpace(spaceId))
        until("the sign-out to clear it") { !it.syncFailures.containsKey(spaceId) }

        // The token really is gone — signing out did happen — and nothing was filed against the
        // space to say so. What the space needs said is said once, by its own standing.
        assertTrue(runBlocking { !sync.isSignedIn(AccountKey(serverUrl, "ada")) })
        assertEquals(null, seen.value.syncFailures[spaceId])
    }


    /**
     * An upload that does not land leaves the page where it is.
     *
     * Closing on failure would be the worst of both: the space is still this device's own, and the
     * one screen that could say so — and be used to try again — has just disappeared.
     */
    @Test
    fun aSpaceThatCouldNotBeUploadedLeavesThePageOpen() = runComposeUiTest {
        val space = runBlocking { assertNotNull(repository.createSpace("Notes", "NTS")) }

        container.store.intent(SpaceListIntent.BeginPutOnServer(space.id, space.name))
        until("the page to open") { it.putOnServer?.spaceId == space.id }

        setContent {
            val state by seen.collectAsState()
            val prompt = state.putOnServer
            val setup = state.remoteSetup
            if (prompt != null && setup != null) {
                PutSpaceOnServerDialog(
                    spaceName = prompt.spaceName,
                    state = setup,
                    onEdit = { edit -> container.store.intent(SpaceListIntent.EditRemoteSetup(edit)) },
                    onCheckServer = { typed ->
                        container.store.intent(SpaceListIntent.CheckRemoteServer(typed))
                    },
                    onAuthenticate = { user, secret ->
                        container.store.intent(SpaceListIntent.AuthenticateRemote(user, secret))
                    },
                    onConfirm = { account ->
                        container.store.intent(SpaceListIntent.PutOnServer(prompt.spaceId, account))
                    },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput(serverUrl)
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.CONNECT).performClick()
        until("the server to answer") { it.remoteSetup?.stage is RemoteSetupStage.Authenticating }
        waitForIdle()
        onNodeWithText("Create an account").performClick()
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.USERNAME).performTextInput("ada")
        onNodeWithTag(RemoteSetupTags.PASSWORD).performTextInput("a long enough password")
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.SUBMIT).performClick()
        until("the sign-up to finish") { it.remoteSetup?.stage is RemoteSetupStage.Ready }
        waitForIdle()

        // The server goes away between signing in and uploading — the ordinary way an upload
        // fails, and the reason the page cannot treat "signed in" as "done".
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)

        onNodeWithTag(PutOnServerTags.CONFIRM).performClick()
        val stuck = until("the failure to be reported") { it.syncFailures.containsKey(space.id) }

        assertNotNull(stuck.putOnServer, "the page closed on a space that never went up")
        assertEquals(space.id, stuck.putOnServer?.spaceId)
        assertTrue(assertNotNull(stuck.remoteLinks[space.id]).isUploaded.not())
    }


    /**
     * Pressing Upload twice puts one copy on the server, not two.
     *
     * The page stays open while the upload runs, so a second press is a thing a person does. Each
     * one mints its own remote id, so without a claim the server ends up holding two copies of the
     * space — the first orphaned, and this device attached to the second.
     */
    @Test
    fun pressingUploadTwiceDoesNotPutTwoCopiesOnTheServer() = runComposeUiTest {
        val space = runBlocking { assertNotNull(repository.createSpace("Notes", "NTS")) }
        val account = runBlocking {
            assertNotNull(
                sync.signUp(
                    assertNotNull(ServerAddress.parse(serverUrl).getOrNull()),
                    "ada",
                    "a long enough password",
                ).getOrNull()
            )
        }

        container.store.intent(SpaceListIntent.PutOnServer(space.id, account))
        container.store.intent(SpaceListIntent.PutOnServer(space.id, account))
        until("the upload to land") { it.remoteLinks[space.id]?.isUploaded == true }
        until("both presses to finish") { space.id !in it.uploading }

        val onTheServer = runBlocking { assertNotNull(sync.listRemoteSpaces(account.key).getOrNull()) }
        assertEquals(1, onTheServer.size, "the server was given the same space twice")
    }

    /**
     * A space that is already on a server is not sent to one again.
     *
     * The button that opens the page is drawn from a status map that starts empty, so for the
     * first frames after launch a cloud space looks local. Acting on that would overwrite the
     * link and orphan the copy the user's other devices share.
     */
    @Test
    fun aSpaceAlreadyOnAServerIsNotUploadedAgain() = runComposeUiTest {
        val account = runBlocking {
            assertNotNull(
                sync.signUp(
                    assertNotNull(ServerAddress.parse(serverUrl).getOrNull()),
                    "ada",
                    "a long enough password",
                ).getOrNull()
            )
        }
        val space = runBlocking {
            val made = assertNotNull(repository.createSpace("Work", "WRK"))
            assertNotNull(sync.linkAndUpload(made.id, account, "remote-already-there").getOrNull())
            made
        }
        val before = assertNotNull(runBlocking { sync.linkFor(space.id) })

        container.store.intent(SpaceListIntent.BeginPutOnServer(space.id, space.name))
        container.store.intent(SpaceListIntent.PutOnServer(space.id, account))
        until("the page to be refused") { it.putOnServer == null }

        val after = assertNotNull(runBlocking { sync.linkFor(space.id) })
        assertEquals(before.remoteSpaceId, after.remoteSpaceId, "the space was relinked elsewhere")
        // Nothing was sent at all: a space already on a server is refused, not re-uploaded, and a
        // revision that moved is how an upload would give itself away.
        assertEquals(
            before.lastSyncedRevision,
            after.lastSyncedRevision,
            "a space already on a server was uploaded again",
        )
        val onTheServer = runBlocking { assertNotNull(sync.listRemoteSpaces(account.key).getOrNull()) }
        assertEquals(listOf("remote-already-there"), onTheServer.map { it.remoteId })
        assertEquals(listOf(before.lastSyncedRevision), onTheServer.map { it.revision })
    }


    /**
     * Trying again after an upload that did not land.
     *
     * The page stays open on failure so that this is possible, which only means anything if the
     * second press actually uploads. A link is written before the first attempt and kept when it
     * fails, so anything that treats "has a link" as "already on a server" answers Try again with
     * a refusal about a space that is not up there.
     */
    @Test
    fun anUploadThatFailedCanBeTriedAgainFromTheSamePage() = runComposeUiTest {
        val space = runBlocking { assertNotNull(repository.createSpace("Notes", "NTS")) }
        val account = runBlocking {
            assertNotNull(
                sync.signUp(
                    assertNotNull(ServerAddress.parse(serverUrl).getOrNull()),
                    "ada",
                    "a long enough password",
                ).getOrNull()
            )
        }
        container.store.intent(SpaceListIntent.BeginPutOnServer(space.id, space.name))
        until("the page to open") { it.putOnServer?.spaceId == space.id }

        // The server is not there for the first attempt.
        val port = serverPort
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        container.store.intent(SpaceListIntent.PutOnServer(space.id, account))
        until("the failure") { it.syncFailures.containsKey(space.id) }
        assertNotNull(seen.value.putOnServer, "the page closed on a space that never went up")
        // The id the first attempt pinned. A retry has to send this one again: if that attempt had
        // in fact reached the server and only its reply was lost, a fresh id would leave the copy
        // it made behind, unreachable and paid for.
        val pinned = assertNotNull(runBlocking { sync.linkFor(space.id) }).remoteSpaceId

        // It comes back, and the user presses Try again — the same intent the failure box sends.
        restartServerOn(port)
        container.store.intent(SpaceListIntent.PutOnServer(space.id, account))

        until("the retry to land") { it.remoteLinks[space.id]?.isUploaded == true }
        until("the page to close") { it.putOnServer == null }

        val landed = assertNotNull(runBlocking { sync.linkFor(space.id) })
        assertEquals(pinned, landed.remoteSpaceId, "trying again claimed a different remote space")
        val onTheServer = runBlocking { assertNotNull(sync.listRemoteSpaces(account.key).getOrNull()) }
        assertEquals(listOf(pinned), onTheServer.map { it.remoteId })
    }


    /**
     * Changing your mind about the server after an upload failed.
     *
     * The page stays open so the trouble can be answered, and one honest answer to "that server is
     * not there" is to use a different one — the address field is still on screen for exactly
     * that. Retrying against the address the failed attempt pinned would upload to a server the
     * user had just navigated away from, and tell them it went to the one they are looking at.
     *
     * Nothing was ever accepted for the first link, so there is nothing on the old server to leave
     * behind by starting again.
     */
    @Test
    fun signingInSomewhereElseAfterAFailureUploadsThere() = runComposeUiTest {
        val space = runBlocking { assertNotNull(repository.createSpace("Notes", "NTS")) }

        // A server that is not there, signed in to while it still was.
        val absent = runBlocking {
            val account = assertNotNull(
                sync.signUp(
                    assertNotNull(ServerAddress.parse(serverUrl).getOrNull()),
                    "ada",
                    "a long enough password",
                ).getOrNull()
            )
            account
        }
        container.store.intent(SpaceListIntent.BeginPutOnServer(space.id, space.name))
        until("the page to open") { it.putOnServer?.spaceId == space.id }

        server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        container.store.intent(SpaceListIntent.PutOnServer(space.id, absent))
        until("the failure") { it.syncFailures.containsKey(space.id) }
        val pinnedToTheDeadOne = assertNotNull(runBlocking { sync.linkFor(space.id) })
        assertEquals(absent.key, pinnedToTheDeadOne.account)

        // Somewhere else entirely, signed in to from the same still-open page.
        startSecondServer()
        val elsewhere = runBlocking {
            assertNotNull(
                sync.signUp(
                    assertNotNull(ServerAddress.parse(secondServerUrl).getOrNull()),
                    "ada",
                    "a long enough password",
                ).getOrNull()
            )
        }
        container.store.intent(SpaceListIntent.PutOnServer(space.id, elsewhere))

        until("the upload to land") { it.remoteLinks[space.id]?.isUploaded == true }
        val landed = assertNotNull(runBlocking { sync.linkFor(space.id) })
        assertEquals(
            elsewhere.key,
            landed.account,
            "the retry uploaded to the server the user had walked away from",
        )
        val there = runBlocking { assertNotNull(sync.listRemoteSpaces(elsewhere.key).getOrNull()) }
        assertEquals(listOf("Notes"), there.map { it.name })
    }

    @Test
    fun aWrongPasswordIsShownAndCreationStaysBlocked() = runComposeUiTest {
        // The account exists with one password; the dialog is given another.
        runBlocking {
            assertNotNull(
                sync.signUp(
                    ServerAddress.parse(serverUrl).getOrNull()!!,
                    "ada",
                    "the real password here",
                ).getOrNull()
            )
        }

        setContent {
            val state by seen.collectAsState()
            NewSpaceDialog(
                onDismiss = {},
                onSpaceCreated = { _, _, _ -> },
                remoteSetup = state.remoteSetup,
                onRemoteSetupChange = { edit -> container.store.intent(SpaceListIntent.EditRemoteSetup(edit)) },
                onCheckServer = { typed -> container.store.intent(SpaceListIntent.CheckRemoteServer(typed)) },
                onAuthenticate = { user, secret ->
                    container.store.intent(SpaceListIntent.AuthenticateRemote(user, secret))
                },
            )
        }
        waitForIdle()

        onNodeWithText("Space Name").performTextInput("Work")
        onNodeWithText("ID Prefix (e.g., WORK, HOME)").performTextInput("WRK")
        onNodeWithTag(RemoteSetupTags.TOGGLE).performClick()
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput(serverUrl)
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.CONNECT).performClick()
        until("the server to answer") { it.remoteSetup?.stage is RemoteSetupStage.Authenticating }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.USERNAME).performTextInput("ada")
        onNodeWithTag(RemoteSetupTags.PASSWORD).performTextInput("not the real password")
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.SUBMIT).performClick()

        until("the password to be rejected") {
            (it.remoteSetup?.stage as? RemoteSetupStage.Authenticating)?.error != null
        }
        waitForIdle()

        onNodeWithText("That username and password do not match.").assertIsDisplayed()
        // Not "Try again": repeating a password the server has already refused cannot work.
        onNodeWithText("Try again").assertDoesNotExist()
        onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun aServerThatIsNotThereIsReportedWithARetryThatWorksOnceItIs() = runComposeUiTest {
        setContent {
            val state by seen.collectAsState()
            NewSpaceDialog(
                onDismiss = {},
                onSpaceCreated = { _, _, _ -> },
                remoteSetup = state.remoteSetup,
                onRemoteSetupChange = { edit -> container.store.intent(SpaceListIntent.EditRemoteSetup(edit)) },
                onCheckServer = { typed -> container.store.intent(SpaceListIntent.CheckRemoteServer(typed)) },
                onAuthenticate = { user, secret ->
                    container.store.intent(SpaceListIntent.AuthenticateRemote(user, secret))
                },
            )
        }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.TOGGLE).performClick()
        waitForIdle()
        // Nothing listens on port 1.
        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput("http://127.0.0.1:1")
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.CONNECT).performClick()

        until("the failure to be shown") {
            (it.remoteSetup?.stage as? RemoteSetupStage.Addressing)?.error != null
        }
        waitForIdle()
        onNodeWithText("Try again").assertIsDisplayed()

        // Point it at the server that is actually running, and the same button gets through.
        // Typed into the box rather than pushed through the store on purpose: the box owns what
        // is in it, and Connect sends what the box holds — which is the whole of the fix for a
        // caret that used to jump backwards under a fast typist.
        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextClearance()
        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput(serverUrl)
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.CONNECT).performClick()
        until("the server to answer") { it.remoteSetup?.stage is RemoteSetupStage.Authenticating }
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.USERNAME).assertIsDisplayed()
    }
}
