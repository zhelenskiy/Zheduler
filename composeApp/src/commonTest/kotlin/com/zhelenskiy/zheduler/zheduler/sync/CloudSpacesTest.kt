package com.zhelenskiy.zheduler.zheduler.sync

import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The rules that make the server the space rather than a copy of it.
 *
 * Each of these is a sentence the user was promised: opening a space shows what the server has,
 * a change that the server did not take did not happen, and a space whose server is out of reach
 * cannot be typed into.
 */
class CloudSpacesTest {

    private val gateway = FakeRemoteSpaceGateway()
    private val repository: TaskRepository = InMemoryTaskRepository()
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

    /**
     * No settle at all, and an unconfined scope.
     *
     * The debounce is there so one gesture is one upload; a test that waited it out would be
     * asserting on a timer rather than on what the class does with a refusal.
     */
    private val cloud = CloudSpaces(
        sync = sync,
        repository = repository,
        scope = CoroutineScope(Dispatchers.Unconfined),
        settle = 0.milliseconds,
    )

    private suspend fun linkedSpace(name: String = "Work", prefix: String = "WRK"): String {
        val account = assertIs<Outcome.Success<SignedInAccount>>(
            sync.signUp(address, "ada", "a long enough password")
        ).value
        val spaceId = assertNotNull(repository.createSpace(name, prefix)).id
        assertIs<Outcome.Success<Uploaded>>(sync.linkAndUpload(spaceId, account, "remote-$spaceId"))
        return spaceId
    }

    private suspend fun taskCount(spaceId: String): Int = repository.getAllTasks(spaceId).size

    private suspend fun addTask(spaceId: String, title: String) {
        assertNotNull(repository.addTask(spaceId = spaceId, title = title))
    }

    private suspend fun clearTasks(spaceId: String) {
        repository.getAllTasks(spaceId).forEach { task ->
            assertTrue(repository.deleteTask(task.id))
        }
    }

    // ------------------------------------------------------------------ opening a space

    @Test
    fun `a space the server has moved on from is replaced by the server's copy`() = runTest {
        val spaceId = linkedSpace()
        addTask(spaceId, "written here")
        val theirs = assertNotNull(repository.exportSpaceToJson(spaceId, prettyPrint = false))
        // Put the local copy back to empty, so the server's copy is demonstrably the one adopted.
        clearTasks(spaceId)
        assertEquals(0, taskCount(spaceId))

        gateway.onFetch = { _, remoteId, _ ->
            Outcome.Success(FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 7, 900, theirs)))
        }
        cloud.refresh(spaceId)

        assertEquals(1, taskCount(spaceId), "the server's copy should have replaced this one")
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
        assertEquals(7L, assertNotNull(sync.linkFor(spaceId)).lastSyncedRevision)
    }

    @Test
    fun `a space the server agrees with is left alone`() = runTest {
        val spaceId = linkedSpace()
        addTask(spaceId, "written here")
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }

        cloud.refresh(spaceId)

        assertEquals(1, taskCount(spaceId))
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
    }

    @Test
    fun `a server that cannot be reached leaves the space readable but not editable`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Failure(RemoteError.Unreachable("no route")) }

        cloud.refresh(spaceId)

        val status = assertIs<CloudSpaceStatus.Offline>(cloud.statusOf(spaceId))
        assertFalse(status.isEditable)
        assertNotNull(status.asOf, "the banner has to be able to say how old the copy is")
    }

    @Test
    fun `a server that refuses blocks the space rather than calling it offline`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ ->
            Outcome.Failure(RemoteError.Rejected(ApiErrorCode.NotFound, "gone"))
        }

        cloud.refresh(spaceId)

        val status = assertIs<CloudSpaceStatus.Blocked>(cloud.statusOf(spaceId))
        assertFalse(status.isEditable)
    }

    @Test
    fun `a local space is never anything but editable`() = runTest {
        val spaceId = assertNotNull(repository.createSpace("Personal", "PSN")).id

        cloud.refresh(spaceId)

        assertEquals(CloudSpaceStatus.OnThisDevice, cloud.statusOf(spaceId))
        assertTrue(cloud.statusOf(spaceId).isEditable)
    }

    @Test
    fun `a space already in step stays editable while it is asked again`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))

        // Eight screens inside a space each ask on entry. If every one of those took the buttons
        // away for the length of a request, a healthy space would flicker read-only all day.
        val server = HeldServer()
        gateway.onFetch = server.answer()
        val asking = launch(Dispatchers.Unconfined) { cloud.refresh(spaceId) }
        // Waited for, not assumed: the status is only worth reading once the request is genuinely
        // in flight, and asserting before that would pass whatever the production code did.
        server.reached.await()

        assertTrue(cloud.statusOf(spaceId).isEditable, "a space in step should not go read-only")

        server.release()
        asking.join()
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
    }

    @Test
    fun `a space in trouble says it is being checked when it is asked again`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Failure(RemoteError.Unreachable("no route")) }
        cloud.refresh(spaceId)
        assertIs<CloudSpaceStatus.Offline>(cloud.statusOf(spaceId))

        val server = HeldServer()
        gateway.onFetch = server.answer()
        val asking = launch(Dispatchers.Unconfined) { cloud.refresh(spaceId) }
        server.reached.await()

        assertIs<CloudSpaceStatus.Checking>(cloud.statusOf(spaceId))

        server.release()
        asking.join()
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
    }

    // ------------------------------------------------------------------ changing a space

    @Test
    fun `a change the server takes stays`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)

        addTask(spaceId, "kept")
        cloud.uploadNow(spaceId)

        assertEquals(1, taskCount(spaceId))
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
    }

    @Test
    fun `a change the server will not take is taken back off this device`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)
        assertEquals(0, taskCount(spaceId))

        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Unreachable("no route")) }
        addTask(spaceId, "typed while the server was away")
        cloud.uploadNow(spaceId)

        // The whole of "every update is made on the server": the write never became part of the
        // space, because the server never agreed to it.
        assertEquals(0, taskCount(spaceId), "a refused change should not survive on this device")
        assertFalse(cloud.statusOf(spaceId).isEditable)
    }

    @Test
    fun `a change made before the server was ever asked is not sent`() = runTest {
        val spaceId = linkedSpace()
        val before = gateway.pushes.size

        // No refresh: the space's standing is unknown, and unknown must not read as fine.
        addTask(spaceId, "written before anything was checked")
        cloud.uploadNow(spaceId)

        assertEquals(before, gateway.pushes.size, "nothing should go up from an unchecked space")
    }

    @Test
    fun `an unchanged space is not uploaded again`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)

        addTask(spaceId, "one edit")
        cloud.uploadNow(spaceId)
        val afterFirst = gateway.pushes.size

        cloud.uploadNow(spaceId)

        assertEquals(afterFirst, gateway.pushes.size, "the server already has exactly this")
    }


    /**
     * A server that answers only when told to, and says when it was reached.
     *
     * Both halves matter. Holding the answer is what makes "in flight" a state a test can look at;
     * [reached] is what stops the looking from happening before the request has even begun, which
     * is how an assertion about a status ends up passing against any implementation at all.
     */
    private class HeldServer {
        val reached = CompletableDeferred<Unit>()
        private val answered = CompletableDeferred<Unit>()

        fun answer(): suspend (AuthToken, String, Long?) -> Outcome<FetchedSpace> = { _, _, _ ->
            // Only the first ask is held. A space this device has not spoken to the server about
            // is asked a second time, for the whole copy, and holding that one too would deadlock
            // a test that is about the status during the first.
            if (reached.complete(Unit)) answered.await()
            Outcome.Success(FetchedSpace.Unchanged(1))
        }

        fun release() {
            if (!answered.complete(Unit)) error("the server answered twice")
        }
    }


    /**
     * The hole a matching revision number cannot see.
     *
     * A recurrence rule firing writes to the database with no screen open, so there is no
     * affordance to have hidden and nothing to have stopped it. If "the server's revision has not
     * moved" were taken to mean "this device is in step", that write would be blessed as the
     * agreed state and the space would quietly diverge from the server for good.
     */
    @Test
    fun `a change that never reached the server is sent when the server is next reachable`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)

        // Written while nothing could be sent, the way the scheduler writes.
        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Unreachable("no route")) }
        addTask(spaceId, "fired while the network was down")
        cloud.uploadNow(spaceId)
        assertFalse(cloud.statusOf(spaceId).isEditable)
        // The rollback took it back, so put it there again the way the engine would, with no
        // upload attempt at all — the space is not editable, so nothing is scheduled.
        addTask(spaceId, "fired again while the network was down")
        assertEquals(1, taskCount(spaceId))

        gateway.onUpdate = { _, remoteId, revision, _ ->
            Outcome.Success(SpacePushResponse(remoteId, revision + 1, 1_000))
        }
        val before = gateway.pushes.size
        cloud.refresh(spaceId)

        assertTrue(gateway.pushes.size > before, "the change should have been sent, not blessed")
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
        assertEquals(1, taskCount(spaceId), "and it should still be here, now that it is agreed")
    }

    /**
     * The same hole across a restart, where there is nothing at all to compare against.
     *
     * Nothing is remembered about what the server took in a previous run, so a matching revision
     * proves even less. The whole copy is asked for rather than assumed.
     */
    @Test
    fun `a first check with nothing remembered asks for the whole copy`() = runTest {
        val spaceId = linkedSpace()
        val asked = mutableListOf<Long?>()
        gateway.onFetch = { _, _, knownRevision ->
            asked += knownRevision
            if (knownRevision == null) {
                Outcome.Success(
                    FetchedSpace.Fresh(SpaceSnapshot("remote-$spaceId", "Work", "WRK", 1, 900, theirCopy()))
                )
            } else {
                Outcome.Success(FetchedSpace.Unchanged(1))
            }
        }

        cloud.refresh(spaceId)

        assertEquals(listOf(1L, null), asked, "a conditional ask, then the whole thing")
        assertEquals(1, taskCount(spaceId), "the server's copy is what the space now is")
    }

    /** A space with one task in it, as the server would hand it back. */
    private suspend fun theirCopy(): String {
        val scratch = assertNotNull(repository.createSpace("Scratch", "SCR")).id
        addTask(scratch, "the server's task")
        val payload = assertNotNull(repository.exportSpaceToJson(scratch, prettyPrint = false))
        assertTrue(repository.deleteSpace(scratch))
        return payload
    }


    /**
     * The one refusal that must not roll anything back.
     *
     * A conflict means both copies are real work. The user is about to be asked which one wins,
     * and answering "keep mine" has to have a "mine" left to keep — so the change stays put and
     * the space simply stops being editable until the question is settled.
     */
    @Test
    fun `a conflict leaves this device's version alone for the user to choose from`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)

        gateway.onUpdate = { _, _, _, _ ->
            Outcome.Failure(RemoteError.Conflict(remoteRevision = 9))
        }
        addTask(spaceId, "written here while somebody else was writing there")
        cloud.uploadNow(spaceId)

        assertEquals(1, taskCount(spaceId), "a conflict must not throw this side's work away")
        assertIs<CloudSpaceStatus.Blocked>(cloud.statusOf(spaceId))
    }

    /**
     * A space whose very first upload failed is not wedged.
     *
     * There is nothing on the server to fetch, so asking about revisions is meaningless; what the
     * space needs is the upload attempted again. Without this every button it has leads back to
     * the same refusal and the space is read-only for good.
     */
    @Test
    fun `a space whose first upload never landed is uploaded when it is next checked`() = runTest {
        val account = assertIs<Outcome.Success<SignedInAccount>>(
            sync.signUp(address, "ada", "a long enough password")
        ).value
        val spaceId = assertNotNull(repository.createSpace("Work", "WRK")).id
        gateway.onCreate = { _, _, _ -> Outcome.Failure(RemoteError.Unreachable("no route")) }
        assertIs<Outcome.Failure>(sync.linkAndUpload(spaceId, account, "remote-1"))

        cloud.refresh(spaceId)
        assertFalse(cloud.statusOf(spaceId).isEditable, "nothing is up there to be in step with")

        gateway.onCreate = { _, remoteId, _ -> Outcome.Success(SpacePushResponse(remoteId, 1, 100)) }
        cloud.refresh(spaceId)

        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
        assertEquals(1L, assertNotNull(sync.linkFor(spaceId)).lastSyncedRevision)
    }


    /**
     * The conflict has to still be there when the user arrives to answer it.
     *
     * After a conflict the server is by definition ahead, so an ordinary check would fetch and
     * adopt — and the one screen where the question can be answered checks every space when it
     * opens. Going to resolve a conflict would therefore destroy the copy being chosen between.
     */
    @Test
    fun `checking a space does not answer a conflict on the user's behalf`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)

        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Conflict(remoteRevision = 9)) }
        addTask(spaceId, "mine")
        cloud.uploadNow(spaceId)
        assertIs<CloudSpaceStatus.Blocked>(cloud.statusOf(spaceId))

        // What the space list does the moment it opens.
        gateway.onFetch = { _, remoteId, _ ->
            Outcome.Success(
                FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 9, 900, theirCopy()))
            )
        }
        cloud.refreshAll()

        assertEquals(1, taskCount(spaceId), "the copy the user is choosing between was destroyed")
        assertIs<CloudSpaceStatus.Blocked>(cloud.statusOf(spaceId))
    }

    /** And once they have answered, the space is checked again like any other. */
    @Test
    fun `resolving a conflict lets the space be checked again`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)
        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Conflict(remoteRevision = 9)) }
        addTask(spaceId, "mine")
        cloud.uploadNow(spaceId)
        assertIs<CloudSpaceStatus.Blocked>(cloud.statusOf(spaceId))

        gateway.onUpdate = { _, remoteId, revision, _ ->
            Outcome.Success(SpacePushResponse(remoteId, revision + 1, 900))
        }
        cloud.conflictResolved(spaceId)

        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
        assertEquals(1, taskCount(spaceId), "keeping mine should have kept mine")
    }

    /** A rollback is announced, so a screen can say the change was undone rather than lost. */
    @Test
    fun `an undone change is reported`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)

        val announced = mutableListOf<String>()
        val listening = launch(Dispatchers.Unconfined) { cloud.rolledBack.collect { announced += it } }

        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Unreachable("no route")) }
        addTask(spaceId, "typed while the server was away")
        cloud.uploadNow(spaceId)

        assertEquals(listOf(spaceId), announced, "the user has to be told their change was undone")
        listening.cancel()
    }


    /**
     * A space stays editable while it is checked, so an adopted copy can bury work typed since.
     *
     * Adopting is still right — the server is the space — but it is the user's change being
     * dropped, and this class does not drop one without saying so.
     */
    @Test
    fun `a change buried by the server's copy is reported`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)
        assertTrue(cloud.rolledBack.value.isEmpty())

        // Typed here, and meanwhile another device had moved the server on.
        addTask(spaceId, "typed while the check was in flight")
        gateway.onFetch = { _, remoteId, _ ->
            Outcome.Success(
                FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 9, 900, theirCopy()))
            )
        }
        cloud.refresh(spaceId)

        assertTrue(spaceId in cloud.rolledBack.value, "the buried change was not reported")
    }

    /** The notice stays until the user says they have read it, not until the space comes right. */
    @Test
    fun `the notice outlives the trouble that caused it`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)

        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Unreachable("no route")) }
        addTask(spaceId, "typed while the server was away")
        cloud.uploadNow(spaceId)
        assertTrue(spaceId in cloud.rolledBack.value)

        // The server comes back and the space is in step again — which is not an answer to
        // "where did my task go".
        gateway.onUpdate = { _, remoteId, revision, _ ->
            Outcome.Success(SpacePushResponse(remoteId, revision + 1, 900))
        }
        cloud.refresh(spaceId)
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
        assertTrue(spaceId in cloud.rolledBack.value, "the notice vanished before it was read")

        cloud.noticeSeen(spaceId)
        assertFalse(spaceId in cloud.rolledBack.value)
    }

    /** Forgetting a space takes its notice with it, so a reused id does not inherit one. */
    @Test
    fun `forgetting a space clears its notice`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)
        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Unreachable("no route")) }
        addTask(spaceId, "gone")
        cloud.uploadNow(spaceId)
        assertTrue(spaceId in cloud.rolledBack.value)

        cloud.forget(spaceId)

        assertFalse(spaceId in cloud.rolledBack.value)
    }


    /**
     * A check outlives the screen that asked for it.
     *
     * Every caller is a screen, and screens go away. If the work ran in the caller's coroutine,
     * backing out of a space mid-request would cancel something the server may already have acted
     * on — the reply would never land, the link would keep a revision the server had moved past,
     * and the next attempt would be refused as a conflict with nobody.
     */
    @Test
    fun `a check finishes even when whoever asked for it walks away`() = runTest {
        val spaceId = linkedSpace()
        val server = HeldServer()
        gateway.onFetch = server.answer()

        val screen = CoroutineScope(Dispatchers.Unconfined)
        screen.launch { cloud.refresh(spaceId) }
        server.reached.await()
        screen.cancel()

        server.release()
        // The work carries on, so the space still arrives at a settled state.
        withTimeout(5_000) { cloud.all.first { it[spaceId] is CloudSpaceStatus.Live } }
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
    }

    /**
     * The first check of a space has no baseline, so the revision has to answer instead.
     *
     * A server still at the revision this device last synced has not moved; anything the copy that
     * comes back disagrees with was written here and never sent. Saying nothing would make a cold
     * start after a lost upload the quietest way to lose work there is.
     */
    @Test
    fun `work that never went up is reported when the first check buries it`() = runTest {
        val spaceId = linkedSpace()
        // Written with nothing remembered about this space, exactly as after a restart.
        addTask(spaceId, "written before this run knew anything")
        gateway.onFetch = { _, remoteId, knownRevision ->
            if (knownRevision == null) {
                Outcome.Success(
                    FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 1, 900, theirCopy()))
                )
            } else {
                Outcome.Success(FetchedSpace.Unchanged(1))
            }
        }

        cloud.refresh(spaceId)

        assertTrue(spaceId in cloud.rolledBack.value, "a change that never went up was buried in silence")
    }

    /** And an ordinary catch-up, where nothing local was pending, says nothing. */
    @Test
    fun `catching up with a server that moved on is not reported as a loss`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, remoteId, _ ->
            // A revision beyond the one this device holds: somebody else did this, not us.
            Outcome.Success(
                FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 9, 900, theirCopy()))
            )
        }

        cloud.refresh(spaceId)

        assertTrue(cloud.rolledBack.value.isEmpty(), "an ordinary catch-up must not cry wolf")
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
    }


    /**
     * The case a revision number cannot answer.
     *
     * The app is killed with an edit still waiting to go up, and meanwhile another device moves the
     * server forward. On the next run the local copy differs from the server's for two reasons at
     * once, and "the server is ahead" is true either way — so the revision says nothing. What the
     * link remembers about the contents it last had accepted does say something.
     */
    @Test
    fun `work lost to a restart is reported even when the server moved on too`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)

        // Written, never sent, and then the process ends: a new CloudSpaces with nothing in memory.
        addTask(spaceId, "typed just before the app was killed")
        val afterRestart = CloudSpaces(
            sync = sync,
            repository = repository,
            scope = CoroutineScope(Dispatchers.Unconfined),
            settle = 0.milliseconds,
        )

        // Somebody else has been working in the meantime.
        gateway.onFetch = { _, remoteId, _ ->
            Outcome.Success(
                FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 9, 900, theirCopy()))
            )
        }
        afterRestart.refresh(spaceId)

        assertTrue(
            spaceId in afterRestart.rolledBack.value,
            "a change lost across a restart was buried in silence",
        )
    }

    /** And a restart with nothing pending still says nothing, however far the server has moved. */
    @Test
    fun `a restart with nothing pending is not reported as a loss`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)

        val afterRestart = CloudSpaces(
            sync = sync,
            repository = repository,
            scope = CoroutineScope(Dispatchers.Unconfined),
            settle = 0.milliseconds,
        )
        gateway.onFetch = { _, remoteId, _ ->
            Outcome.Success(
                FetchedSpace.Fresh(SpaceSnapshot(remoteId, "Work", "WRK", 9, 900, theirCopy()))
            )
        }
        afterRestart.refresh(spaceId)

        assertTrue(afterRestart.rolledBack.value.isEmpty(), "an ordinary restart must not cry wolf")
    }

    // ------------------------------------------------------------------ forgetting

    @Test
    fun `forgetting a space leaves nothing behind to report on it`() = runTest {
        val spaceId = linkedSpace()
        gateway.onFetch = { _, _, _ -> Outcome.Failure(RemoteError.Unreachable("no route")) }
        cloud.refresh(spaceId)
        assertIs<CloudSpaceStatus.Offline>(cloud.statusOf(spaceId))

        cloud.forget(spaceId)

        assertEquals(
            CloudSpaceStatus.OnThisDevice,
            cloud.statusOf(spaceId),
            "an id that comes round again must not inherit the last space's trouble",
        )
    }
}
