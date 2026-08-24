package com.zhelenskiy.zheduler.zheduler.sync

import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client's half of sync, against a scripted gateway and a real repository.
 *
 * The repository is the genuine [InMemoryTaskRepository] rather than a stub, because what is being
 * checked includes that the thing uploaded is the space's real export and that a download really
 * lands as a new space.
 */
class SpaceSyncServiceTest {

    private val gateway = FakeRemoteSpaceGateway()
    private val repository: TaskRepository = InMemoryTaskRepository()
    private val links = inMemoryStore(SyncSettings())
    private val credentials = inMemoryStore(StoredCredentials())
    private val address = testAddress()

    /**
     * Unconfined, so a revocation the service fires and forgets runs inline on this thread.
     *
     * With the production scope it lands on a background dispatcher that `runTest` does not join:
     * an assertion about revocations would then be counting whichever ones happened to win a race,
     * and `gateway.calls` would be appended to from two threads at once.
     */
    private val service = SpaceSyncService(
        gateway,
        repository,
        links,
        credentials,
        revocations = CoroutineScope(Dispatchers.Unconfined),
    )

    private suspend fun signIn(username: String = "ada"): SignedInAccount =
        assertIs<Outcome.Success<SignedInAccount>>(
            service.signUp(address, username, "a long enough password")
        ).value

    private suspend fun localSpace(name: String = "Work", prefix: String = "WRK"): String =
        assertNotNull(repository.createSpace(name, prefix)).id

    // ------------------------------------------------------------------ signing in

    @Test
    fun `signing up files the token under the name the server gave back`() = runTest {
        // The server lower-cases and trims; filing it under what was typed would put the token
        // where the next sign-in never looks.
        gateway.onRegister = { _, _ -> Outcome.Success(AuthResponse("tkn", "u1", "ada", 0)) }
        val account = assertIs<Outcome.Success<SignedInAccount>>(
            service.signUp(address, "  Ada  ", "a long enough password")
        ).value

        assertEquals("ada", account.key.username)
        assertTrue(service.isSignedIn(AccountKey(address.value, "ada")))
        assertFalse(service.isSignedIn(AccountKey(address.value, "  Ada  ")))
    }

    @Test
    fun `a refused sign-in stores nothing`() = runTest {
        gateway.onLogIn = { _, _ ->
            Outcome.Failure(RemoteError.Rejected(ApiErrorCode.InvalidCredentials, "no"))
        }
        assertIs<Outcome.Failure>(service.signIn(address, "ada", "wrong password here"))
        assertFalse(service.isSignedIn(AccountKey(address.value, "ada")))
    }

    @Test
    fun `signing out forgets the token even when the server cannot be reached`() = runTest {
        // A user who asks to sign out must end up signed out; a token thrown away here cannot be
        // used again whatever the server thinks.
        val account = signIn()
        gateway.onLogOut = { Outcome.Failure(RemoteError.Unreachable("down")) }

        assertIs<Outcome.Failure>(service.signOut(account.key))
        assertFalse(service.isSignedIn(account.key))
    }

    @Test
    fun `signing out when there was no token does not call the server`() = runTest {
        assertIs<Outcome.Success<Unit>>(service.signOut(AccountKey(address.value, "nobody")))
        assertFalse("logOut" in gateway.calls)
    }

    @Test
    fun `two accounts on the same server hold separate tokens`() = runTest {
        signIn("ada")
        signIn("bob")
        assertTrue(service.isSignedIn(AccountKey(address.value, "ada")))
        assertTrue(service.isSignedIn(AccountKey(address.value, "bob")))
        service.signOut(AccountKey(address.value, "ada"))
        assertFalse(service.isSignedIn(AccountKey(address.value, "ada")))
        assertTrue(service.isSignedIn(AccountKey(address.value, "bob")))
    }

    @Test
    fun `the same username on two servers is two accounts`() = runTest {
        val other = assertIs<Outcome.Success<ServerAddress>>(
            ServerAddress.parse("https://elsewhere.example.com")
        ).value
        signIn("ada")
        service.signUp(other, "ada", "a long enough password")

        service.signOut(AccountKey(address.value, "ada"))
        assertTrue(service.isSignedIn(AccountKey(other.value, "ada")))
    }

    // --------------------------------------------------------------------- uploads

    @Test
    fun `a first upload sends the space's export and records the link`() = runTest {
        val account = signIn()
        val spaceId = localSpace(name = "Work", prefix = "WRK")

        assertIs<Outcome.Success<Uploaded>>(service.linkAndUpload(spaceId, account, "remote-1"))

        val pushed = gateway.pushes.single()
        assertEquals("Work", pushed.name)
        assertEquals("WRK", pushed.idPrefix)
        assertTrue("\"space\"" in pushed.payload, "the payload was not the space's export: ${pushed.payload}")

        val link = assertNotNull(service.linkFor(spaceId))
        assertEquals("remote-1", link.remoteSpaceId)
        assertEquals(1L, link.lastSyncedRevision)
        assertEquals(account.key, link.account)
    }

    @Test
    fun `a first upload that fails leaves a link that has not been uploaded`() = runTest {
        // The link is what a retry acts on. Without one the space is stranded: the only button it
        // is offered can answer nothing but "this space is not connected to a server".
        val account = signIn()
        val spaceId = localSpace()
        gateway.onCreate = { _, _, _ -> Outcome.Failure(RemoteError.Unreachable("down")) }

        assertIs<Outcome.Failure>(service.linkAndUpload(spaceId, account, "remote-1"))

        val link = assertNotNull(service.linkFor(spaceId))
        assertFalse(link.isUploaded, "a space the server never accepted must not read as backed up")
        assertEquals(RemoteSpaceLink.NOT_UPLOADED, link.lastSyncedRevision)
        assertEquals("remote-1", link.remoteSpaceId)
    }

    @Test
    fun `retrying a failed first upload creates it under the same remote id`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        gateway.onCreate = { _, _, _ -> Outcome.Failure(RemoteError.Unreachable("down")) }
        service.linkAndUpload(spaceId, account, "remote-1")

        // The same id again, so a create whose reply was lost is refused as a conflict rather
        // than leaving a second copy on the server.
        var createdAs: String? = null
        gateway.onCreate = { _, remoteId, _ ->
            createdAs = remoteId
            Outcome.Success(SpacePushResponse(remoteId, 1, 100))
        }

        assertEquals(1L, assertIs<Outcome.Success<Uploaded>>(service.upload(spaceId)).value.revision)
        assertEquals("remote-1", createdAs)
        assertTrue(assertNotNull(service.linkFor(spaceId)).isUploaded)
    }

    @Test
    fun `a space that has been uploaded is replaced not created again`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")
        gateway.calls.clear()

        service.upload(spaceId)

        assertTrue("updateSpace" in gateway.calls, "made ${gateway.calls}")
        assertFalse("createSpace" in gateway.calls)
    }

    @Test
    fun `a later upload is guarded by the revision this device last saw`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")

        var guardedBy: Long? = null
        gateway.onUpdate = { _, remoteId, revision, _ ->
            guardedBy = revision
            Outcome.Success(SpacePushResponse(remoteId, revision + 1, 300))
        }

        assertEquals(2L, assertIs<Outcome.Success<Uploaded>>(service.upload(spaceId)).value.revision)
        assertEquals(1L, guardedBy)
        assertEquals(2L, service.linkFor(spaceId)?.lastSyncedRevision)
    }

    @Test
    fun `a conflict leaves the recorded revision alone`() = runTest {
        // The link must keep pointing at what this device actually has; moving it forward on a
        // refusal would make the next upload guard on a revision it never downloaded.
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")
        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Conflict(7L)) }

        val failure = assertIs<Outcome.Failure>(service.upload(spaceId))
        assertEquals(RemoteError.Conflict(7L), failure.error)
        assertEquals(1L, service.linkFor(spaceId)?.lastSyncedRevision)
    }

    @Test
    fun `an overwrite reads the server's revision first and still guards on it`() = runTest {
        // Not an unconditional write: between the read and the write a third device could arrive,
        // and it should lose rather than be silently overwritten.
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")

        gateway.onFetch = { _, remoteId, _ ->
            Outcome.Success(
                FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 9L, 400, "{}"))
            )
        }
        var guardedBy: Long? = null
        gateway.onUpdate = { _, remoteId, revision, _ ->
            guardedBy = revision
            Outcome.Success(SpacePushResponse(remoteId, revision + 1, 500))
        }

        assertEquals(10L, assertIs<Outcome.Success<Uploaded>>(service.uploadOverwriting(spaceId)).value.revision)
        assertEquals(9L, guardedBy)
    }

    @Test
    fun `uploading a space that was never linked fails rather than inventing a link`() = runTest {
        signIn()
        val spaceId = localSpace()
        assertIs<Outcome.Failure>(service.upload(spaceId))
        assertIs<Outcome.Failure>(service.uploadOverwriting(spaceId))
        assertTrue(gateway.pushes.isEmpty())
    }

    @Test
    fun `an upload with no token asks for a sign-in without contacting the server`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")
        service.signOut(account.key)
        gateway.calls.clear()

        val failure = assertIs<Outcome.Failure>(service.upload(spaceId))
        assertIs<RemoteError.AuthenticationRequired>(failure.error)
        assertTrue(gateway.calls.isEmpty(), "made ${gateway.calls}")
    }

    @Test
    fun `a token the server has stopped honouring is thrown away`() = runTest {
        // Otherwise the app keeps a dead token and keeps sending it, so "sign in again" appears to
        // do nothing.
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")
        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.AuthenticationRequired()) }

        assertIs<Outcome.Failure>(service.upload(spaceId))
        assertFalse(service.isSignedIn(account.key))
    }

    @Test
    fun `an ordinary failure does not throw the token away`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")
        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.ServerFault(503)) }

        assertIs<Outcome.Failure>(service.upload(spaceId))
        assertTrue(service.isSignedIn(account.key))
    }

    @Test
    fun `uploading a space that has been deleted locally fails without sending anything`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")
        repository.deleteSpace(spaceId)
        gateway.pushes.clear()

        assertIs<Outcome.Failure>(service.upload(spaceId))
        assertTrue(gateway.pushes.isEmpty())
    }

    // ------------------------------------------------------------------- downloads

    @Test
    fun `a download lands as a new local space and is linked`() = runTest {
        val account = signIn()
        val original = localSpace(name = "Work", prefix = "WRK")
        service.linkAndUpload(original, account, "remote-1")
        val exported = assertNotNull(repository.exportSpaceToJson(original, prettyPrint = false))

        gateway.onFetch = { _, remoteId, _ ->
            Outcome.Success(FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 4L, 600, exported)))
        }

        val downloaded = assertIs<Outcome.Success<Downloaded>>(
            service.download(account.key, "remote-2")
        ).value

        // A new space, not the one already here: a download must never overwrite local work.
        assertTrue(downloaded.space.id != original)
        assertEquals(4L, downloaded.link.lastSyncedRevision)
        assertEquals(downloaded.space.id, assertNotNull(service.linkFor(downloaded.space.id)).spaceId)
        assertEquals(2, repository.getAllSpaces().size)
    }

    @Test
    fun `a payload this build cannot read is refused rather than half-imported`() = runTest {
        val account = signIn()
        gateway.onFetch = { _, remoteId, _ ->
            Outcome.Success(
                FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 1L, 700, "not a space at all"))
            )
        }

        val failure = assertIs<Outcome.Failure>(service.download(account.key, "remote-1"))
        assertIs<RemoteError.Malformed>(failure.error)
        assertTrue(repository.getAllSpaces().isEmpty())
    }

    @Test
    fun `an unconditional download that comes back unchanged is a malformed answer`() = runTest {
        // Nothing was offered to compare against, so there is nothing "unchanged" could mean.
        val account = signIn()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(3L)) }
        assertIs<RemoteError.Malformed>(
            assertIs<Outcome.Failure>(service.download(account.key, "remote-1")).error
        )
    }

    @Test
    fun `checking for changes offers the revision this device holds`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")

        var offered: Long? = null
        gateway.onFetch = { _, _, known ->
            offered = known
            Outcome.Success(FetchedSpace.Unchanged(1L))
        }
        assertEquals(false, assertIs<Outcome.Success<Boolean>>(service.checkForChanges(spaceId)).value)
        assertEquals(1L, offered)

        gateway.onFetch = { _, remoteId, _ ->
            Outcome.Success(FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 2L, 800, "{}")))
        }
        assertEquals(true, assertIs<Outcome.Success<Boolean>>(service.checkForChanges(spaceId)).value)
    }

    // ------------------------------------------------------------------- unlinking

    @Test
    fun `unlinking forgets the link and leaves the server alone`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")

        service.unlink(spaceId)
        assertNull(service.linkFor(spaceId))
        assertFalse("deleteSpace" in gateway.calls)
    }

    @Test
    fun `deleting the remote copy is guarded by the revision and then unlinks`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")

        var guardedBy: Long? = null
        gateway.onDelete = { _, _, revision ->
            guardedBy = revision
            Outcome.Success(Unit)
        }
        assertIs<Outcome.Success<Unit>>(service.deleteRemote(spaceId))
        assertEquals(1L, guardedBy)
        assertNull(service.linkFor(spaceId))
    }

    @Test
    fun `a refused remote deletion keeps the link`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")
        gateway.onDelete = { _, _, _ -> Outcome.Failure(RemoteError.Conflict(5L)) }

        assertIs<Outcome.Failure>(service.deleteRemote(spaceId))
        assertNotNull(service.linkFor(spaceId))
    }

    @Test
    fun `forgetting everything throws away every link and every token`() = runTest {
        // What "erase all data" has to mean: a token left in the file is still a working
        // credential for whoever can read it.
        val account = signIn("ada")
        val other = signIn("bob")
        val first = localSpace(name = "Work", prefix = "WRK")
        val second = localSpace(name = "Home", prefix = "HOM")
        service.linkAndUpload(first, account, "remote-1")
        service.linkAndUpload(second, other, "remote-2")

        service.forgetEverything()

        assertNull(service.linkFor(first))
        assertNull(service.linkFor(second))
        assertFalse(service.isSignedIn(account.key))
        assertFalse(service.isSignedIn(other.key))
        assertEquals(2, gateway.calls.count { it == "logOut" }, "each token should be revoked too")
    }

    @Test
    fun `forgetting everything still clears this device when the server cannot be reached`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")
        gateway.onLogOut = { Outcome.Failure(RemoteError.Unreachable("down")) }

        service.forgetEverything()

        assertFalse(service.isSignedIn(account.key))
        assertNull(service.linkFor(spaceId))
    }

    @Test
    fun `an account key survives a round trip through its stored form`() = runTest {
        // The credentials file is keyed by this string, and `forgetEverything` reads it back to
        // find which server to revoke each token on.
        listOf(
            AccountKey("https://sync.example.com", "ada"),
            AccountKey("https://sync.example.com:8443", "a.b-c_d"),
            AccountKey("http://127.0.0.1:8080", "ada"),
        ).forEach { key ->
            assertEquals(key, AccountKey.fromStorageKey(key.storageKey), "round trip failed for $key")
        }
        assertNull(AccountKey.fromStorageKey("no separator"))
        assertNull(AccountKey.fromStorageKey("|leading"))
        assertNull(AccountKey.fromStorageKey("trailing|"))
    }

    // ------------------------------------- replies that land after the thing they are about

    @Test
    fun `an upload that lands after everything was erased does not bring the link back`() = runTest {
        // A remote call takes up to thirty seconds and the user can erase everything during one.
        // A link written afterwards names a space that no longer exists — and local space ids are
        // reused, so the next space to take that id would inherit it and its first upload would
        // overwrite a stranger's backup.
        val account = signIn()
        val spaceId = localSpace()
        gateway.onCreate = { _, remoteId, _ ->
            service.forgetEverything()
            Outcome.Success(SpacePushResponse(remoteId, 1, 100))
        }

        service.linkAndUpload(spaceId, account, "remote-1")

        assertNull(service.linkFor(spaceId), "the erased link came back when the reply landed")
    }

    @Test
    fun `an upload that lands after the space was unlinked does not resurrect it`() = runTest {
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")

        gateway.onUpdate = { _, remoteId, revision, _ ->
            // What deleting the space does, arriving while this upload is in flight.
            service.unlink(spaceId)
            Outcome.Success(SpacePushResponse(remoteId, revision + 1, 200))
        }
        service.upload(spaceId)

        assertNull(service.linkFor(spaceId), "the unlinked space came back when the reply landed")
    }

    @Test
    fun `an upload that lands after the id was reused does not steal the new space's link`() = runTest {
        // Local space ids come back around: `space-<count>-<prefix>`. Delete a space mid-upload,
        // make another with the same prefix, and it takes the same id. A guard that only asked
        // "is something linked under this id?" would let the old reply re-point the new space at
        // the old space's server copy — and the revision it wrote would match, so the next upload
        // would overwrite that copy without ever showing a conflict.
        val account = signIn()
        val spaceId = localSpace(name = "Work", prefix = "WRK")
        service.linkAndUpload(spaceId, account, "remote-OLD")

        gateway.onUpdate = { _, remoteId, revision, _ ->
            // While this upload is in flight the space is deleted and a new one takes its id,
            // linked to a different remote.
            service.unlink(spaceId)
            service.linkAndUpload(spaceId, account, "remote-NEW")
            Outcome.Success(SpacePushResponse(remoteId, revision + 1, 300))
        }
        service.upload(spaceId)

        val link = assertNotNull(service.linkFor(spaceId))
        assertEquals("remote-NEW", link.remoteSpaceId, "the old upload re-pointed the new space")
    }

    @Test
    fun `a sign-in that lands after everything was erased stores no token`() = runTest {
        gateway.onRegister = { username, _ ->
            service.forgetEverything()
            Outcome.Success(AuthResponse("tkn", "u1", username, 0))
        }

        val account = assertIs<Outcome.Success<SignedInAccount>>(
            service.signUp(address, "ada", "a long enough password")
        ).value

        assertFalse(service.isSignedIn(account.key), "a token outlived the erase that removed it")
    }

    @Test
    fun `a download that lands after everything was erased records no link`() = runTest {
        val account = signIn()
        val original = localSpace()
        val exported = assertNotNull(repository.exportSpaceToJson(original, prettyPrint = false))
        gateway.onFetch = { _, remoteId, _ ->
            service.forgetEverything()
            Outcome.Success(FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 1L, 100, exported)))
        }

        val downloaded = assertIs<Outcome.Success<Downloaded>>(
            service.download(account.key, "remote-1")
        ).value

        assertNull(service.linkFor(downloaded.space.id))
    }

    @Test
    fun `an ordinary upload after an erase is unaffected by the guard`() = runTest {
        // The guard must only drop writes from *before* the erase, not everything after it.
        val account = signIn()
        val spaceId = localSpace()
        service.linkAndUpload(spaceId, account, "remote-1")
        service.forgetEverything()

        val again = signIn()
        val second = localSpace(name = "Home", prefix = "HOM")
        assertIs<Outcome.Success<Uploaded>>(service.linkAndUpload(second, again, "remote-2"))
        assertNotNull(service.linkFor(second))
    }

    @Test
    fun `links for different spaces do not overwrite one another`() = runTest {
        val account = signIn()
        val first = localSpace(name = "Work", prefix = "WRK")
        val second = localSpace(name = "Home", prefix = "HOM")

        service.linkAndUpload(first, account, "remote-1")
        service.linkAndUpload(second, account, "remote-2")

        assertEquals("remote-1", service.linkFor(first)?.remoteSpaceId)
        assertEquals("remote-2", service.linkFor(second)?.remoteSpaceId)
    }
}
