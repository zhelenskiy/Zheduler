@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.sync

import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * What happens to a change made at the moment the server goes away.
 *
 * Written for a report: the connection dropped while a task was being edited, the space went
 * read-only, and the edit was gone — undone by the rule that nothing may stay here which the
 * server has not agreed to. That rule is right, but on its own it is only half an answer. The
 * other half is that whoever made the change still has to have it, and that means the screen
 * holding it must find out *before* it throws its draft away and leaves.
 *
 * [CloudSpaces.commit] is what makes that possible. The change is made *inside* it, so that
 * nothing can land between the writing and the sending, and it reports what actually became of
 * that change rather than leaving the caller to guess from the state of the space afterwards —
 * which cannot tell "the server took it" from "it was taken back out", those two being identical
 * by construction.
 */
class NoEditIsLostWhenTheServerGoesTest {

    private val gateway = FakeRemoteSpaceGateway()
    private val repository = InMemoryTaskRepository()
    private val links = inMemoryStore(SyncSettings())
    private val credentials = inMemoryStore(StoredCredentials())
    private val address = testAddress()

    private val sync = SpaceSyncService(
        gateway,
        repository,
        links,
        credentials,
        revocations = CoroutineScope(Dispatchers.Unconfined),
    )

    private val cloud = CloudSpaces(
        sync = sync,
        repository = repository,
        scope = CoroutineScope(Dispatchers.Unconfined),
        settle = 0.milliseconds,
    )

    private suspend fun liveSpace(): String {
        val account = assertIs<Outcome.Success<SignedInAccount>>(
            sync.signUp(address, "ada", "a long enough password")
        ).value
        val spaceId = assertNotNull(repository.createSpace("Work", "WRK")).id
        assertIs<Outcome.Success<Uploaded>>(cloud.putOnServer(spaceId, account, "remote-1"))
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
        return spaceId
    }

    private suspend fun titles(spaceId: String) = repository.getAllTasks(spaceId).map { it.title }

    /** A save, made the way the forms make one: the write happens inside the commit. */
    private suspend fun saveAdding(spaceId: String, title: String): CommitOutcome =
        cloud.commit(spaceId) { repository.addTask(spaceId = spaceId, title = title) != null }

    private fun theServerGoesAway() {
        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Unreachable("no route")) }
    }

    private fun theServerComesBack() {
        gateway.onUpdate = { _, remoteId, revision, _ ->
            Outcome.Success(SpacePushResponse(remoteId, revision + 1, 900))
        }
    }

    // ------------------------------------------------------------------ what a screen is told

    @Test
    fun `a save the server takes is reported as taken`() = runTest {
        val spaceId = liveSpace()

        assertEquals(CommitOutcome.Accepted, saveAdding(spaceId, "kept"))
        assertEquals(listOf("kept"), titles(spaceId))
    }

    @Test
    fun `a save the server will not take is reported as not taken`() = runTest {
        val spaceId = liveSpace()
        theServerGoesAway()

        // This is the answer the form needs, and the whole point of asking synchronously: without
        // it the screen has already cleared its draft and navigated away by the time the refusal
        // arrives, and the rollback below removes the work with nothing left holding a copy.
        assertEquals(CommitOutcome.Undone, saveAdding(spaceId, "typed as the network died"))
        assertTrue(titles(spaceId).isEmpty(), "the space kept something the server refused")
        assertTrue(!cloud.statusOf(spaceId).isEditable)
    }

    @Test
    fun `a change that could not be made is reported apart from one the server refused`() = runTest {
        val spaceId = liveSpace()

        // The repository declined — the thing being changed is gone. Nothing to do with the
        // server, and a different sentence for the user.
        assertEquals(CommitOutcome.NotWritten, cloud.commit(spaceId) { false })
    }

    @Test
    fun `a space of this device's own needs nobody's permission`() = runTest {
        val spaceId = assertNotNull(repository.createSpace("Personal", "PSN")).id

        assertEquals(CommitOutcome.Accepted, saveAdding(spaceId, "mine alone"))
        assertEquals(listOf("mine alone"), titles(spaceId))
    }

    // ------------------------------------------------------------------ and nothing is lost

    /**
     * The reported case, end to end: the edit comes back when the server does.
     *
     * The form is not in this test — what stands in for it is the caller keeping hold of what it
     * wrote, which is what the screens now do for any answer other than [CommitOutcome.Accepted].
     */
    @Test
    fun `a refused edit can be made again once the server is back`() = runTest {
        val spaceId = liveSpace()
        assertEquals(CommitOutcome.Accepted, saveAdding(spaceId, "before"))
        val existing = repository.getAllTasks(spaceId).single()

        theServerGoesAway()
        val edited = existing.copy(title = "after")
        assertEquals(
            CommitOutcome.Undone,
            cloud.commit(spaceId) { repository.updateTask(edited) != null },
        )
        // Taken back out, as the rule requires — and the caller still holds `edited`.
        assertEquals(listOf("before"), titles(spaceId))

        theServerComesBack()
        cloud.refresh(spaceId)
        assertTrue(cloud.statusOf(spaceId).isEditable, "the space never became writable again")

        assertEquals(
            CommitOutcome.Accepted,
            cloud.commit(spaceId) { repository.updateTask(edited) != null },
            "the same edit was refused a second time",
        )
        assertEquals(listOf("after"), titles(spaceId))
    }

    /** And the same for a task that never existed anywhere else. */
    @Test
    fun `a refused new task can be made again once the server is back`() = runTest {
        val spaceId = liveSpace()
        theServerGoesAway()
        assertEquals(CommitOutcome.Undone, saveAdding(spaceId, "written while it was down"))
        assertTrue(titles(spaceId).isEmpty())

        theServerComesBack()
        cloud.refresh(spaceId)

        assertEquals(CommitOutcome.Accepted, saveAdding(spaceId, "written while it was down"))
        assertEquals(listOf("written while it was down"), titles(spaceId))
    }

    /**
     * A save made while an earlier upload is still on the wire waits for it, and then goes.
     *
     * Without this the answer describes somebody else's request: an upload already in flight makes
     * `uploadNow` note the change and return at once, and a form told "not taken" about a change
     * that was neither taken nor undone would offer to make it a second time.
     */
    @Test
    fun `a save behind an upload already in flight waits for its own answer`() = runTest {
        val spaceId = liveSpace()
        val onTheWire = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        gateway.onUpdate = { _, remoteId, revision, _ ->
            if (onTheWire.complete(Unit)) held.await()
            Outcome.Success(SpacePushResponse(remoteId, revision + 1, 900))
        }

        // Something else's upload, started first and stuck.
        assertNotNull(repository.addTask(spaceId = spaceId, title = "somebody else's"))
        val earlier = launch(Dispatchers.Unconfined) { cloud.uploadNow(spaceId) }
        onTheWire.await()

        // And now the form's save, which must not be answered by the request above.
        val committing = async(Dispatchers.Unconfined) { saveAdding(spaceId, "the form's") }
        held.complete(Unit)
        earlier.join()

        assertEquals(CommitOutcome.Accepted, committing.await())
        assertEquals(
            listOf("somebody else's", "the form's"),
            titles(spaceId).sorted(),
            "the save that waited did not end up on the server",
        )
    }

    /**
     * A check queued ahead of a save cannot swallow it.
     *
     * The dangerous shape, and the reason the change is written *inside* the commit: a check that
     * finds the server ahead replaces the whole space and leaves it perfectly healthy. Were the
     * write made before the lock, that replacement would land on top of it and the send that
     * followed would find the space already matching the server — nothing to do — and report the
     * save as safely up there, with the work gone from the space and the form told it could let go.
     */
    @Test
    fun `a check queued ahead cannot swallow the save that follows it`() = runTest {
        val spaceId = liveSpace()
        val theirs = theirCopyWith("somebody else's")

        val onTheWire = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        gateway.onFetch = { _, remoteId, _ ->
            if (onTheWire.complete(Unit)) held.await()
            Outcome.Success(FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 9, 900, theirs)))
        }
        theServerComesBack()

        // Somebody else's copy, on its way down and about to replace this space wholesale.
        val checking = launch(Dispatchers.Unconfined) { cloud.refresh(spaceId) }
        onTheWire.await()

        val committing = async(Dispatchers.Unconfined) { saveAdding(spaceId, "the form's") }
        held.complete(Unit)
        checking.join()

        // The save was made after the replacement, so it survived it and went up.
        assertEquals(CommitOutcome.Accepted, committing.await())
        assertTrue("the form's" in titles(spaceId), "the save was swallowed by the check")
    }

    /** A space with [title] in it, as another device would have left it on the server. */
    private suspend fun theirCopyWith(title: String): String {
        val scratch = assertNotNull(repository.createSpace("Scratch", "SCR")).id
        assertNotNull(repository.addTask(spaceId = scratch, title = title))
        val payload = assertNotNull(repository.exportSpaceToJson(scratch, prettyPrint = false))
        assertTrue(repository.deleteSpace(scratch))
        return payload
    }

    /**
     * A conflict is not an undoing, and must not be reported as one.
     *
     * Both copies survive a conflict on purpose — the user is about to be asked which wins — so
     * the work is still here. It is not safe to walk away from, though: until the question is
     * answered that copy can be adopted over, so the form is told to keep what it has.
     */
    @Test
    fun `a conflict is reported as a question rather than as work undone`() = runTest {
        val spaceId = liveSpace()
        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Conflict(remoteRevision = 9)) }

        assertEquals(CommitOutcome.AwaitingYourChoice, saveAdding(spaceId, "mine"))
        assertEquals(listOf("mine"), titles(spaceId), "a conflict threw this side's work away")
    }

    @Test
    fun `committing nothing new sends nothing`() = runTest {
        val spaceId = liveSpace()
        assertEquals(CommitOutcome.Accepted, saveAdding(spaceId, "once"))
        val sent = gateway.pushes.size

        assertEquals(CommitOutcome.Accepted, cloud.commit(spaceId) { true })

        assertEquals(sent, gateway.pushes.size, "the server was given the same thing twice")
    }
}
