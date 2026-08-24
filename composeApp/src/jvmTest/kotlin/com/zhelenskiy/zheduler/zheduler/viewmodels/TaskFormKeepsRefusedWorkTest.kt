@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.lifecycle.SavedStateHandle
import com.zhelenskiy.zheduler.zheduler.components.form.PersistedFormState
import com.zhelenskiy.zheduler.zheduler.events.ChosenSound
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.sync.CloudSpaceStatus
import com.zhelenskiy.zheduler.zheduler.sync.CloudSpaces
import com.zhelenskiy.zheduler.zheduler.sync.CommitOutcome
import com.zhelenskiy.zheduler.zheduler.sync.FakeRemoteSpaceGateway
import com.zhelenskiy.zheduler.zheduler.sync.FetchedSpace
import com.zhelenskiy.zheduler.zheduler.sync.Outcome
import com.zhelenskiy.zheduler.zheduler.sync.RemoteError
import com.zhelenskiy.zheduler.zheduler.sync.SignedInAccount
import com.zhelenskiy.zheduler.zheduler.sync.SpaceSyncService
import com.zhelenskiy.zheduler.zheduler.sync.StoredCredentials
import com.zhelenskiy.zheduler.zheduler.sync.SyncSettings
import com.zhelenskiy.zheduler.zheduler.sync.Uploaded
import com.zhelenskiy.zheduler.zheduler.sync.inMemoryStore
import com.zhelenskiy.zheduler.zheduler.sync.testAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.subscribe
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * A form that is refused by the server keeps what the user wrote.
 *
 * The reported bug: the connection dropped mid-edit, the space went read-only, and the edit was
 * gone. Two things had to be true for that, and only one of them was right. Taking the change back
 * out is right — nothing may stay here that the server has not agreed to. Announcing "saved",
 * clearing the draft and navigating away *before* finding out is not.
 *
 * So the form now asks, and the answer it gets is what these check. A refused save reports itself
 * as refused, and — this is the part that matters — leaves the draft alone, because the draft is
 * the only remaining copy of the work.
 */
class TaskFormKeepsRefusedWorkTest {

    private val gateway = FakeRemoteSpaceGateway()
    private val repository = InMemoryTaskRepository()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val sync = SpaceSyncService(
        gateway,
        repository,
        inMemoryStore(SyncSettings()),
        inMemoryStore(StoredCredentials()),
        revocations = scope,
    )

    private val cloud = CloudSpaces(
        sync = sync,
        repository = repository,
        scope = scope,
        settle = 0.milliseconds,
    )

    private val containers = mutableListOf<ScopedContainer>()

    @AfterTest
    fun stop() {
        containers.forEach { it.close() }
        scope.cancel()
    }

    private fun liveSpace(): String = runBlocking {
        val account = assertIs<Outcome.Success<SignedInAccount>>(
            sync.signUp(testAddress(), "ada", "a long enough password")
        ).value
        val spaceId = assertNotNull(repository.createSpace("Work", "WRK")).id
        assertIs<Outcome.Success<Uploaded>>(cloud.putOnServer(spaceId, account, "remote-1"))
        gateway.onFetch = { _, _, _ -> Outcome.Success(FetchedSpace.Unchanged(1)) }
        cloud.refresh(spaceId)
        assertIs<CloudSpaceStatus.Live>(cloud.statusOf(spaceId))
        spaceId
    }

    private fun theServerGoesAway() {
        gateway.onUpdate = { _, _, _, _ -> Outcome.Failure(RemoteError.Unreachable("no route")) }
    }

    /** Collects what a store announces, the way a screen's `subscribe` block does. */
    private fun <S : MVIState, I : MVIIntent, A : MVIAction> watch(
        store: Store<S, I, A>,
        into: MutableList<A>,
    ) {
        CoroutineScope(Dispatchers.Unconfined).subscribe(
            store,
            consume = { into += it },
            render = {},
        )
    }

    // ------------------------------------------------------------------ editing

    @Test
    fun anEditTheServerRefusesIsReportedAndTheDraftIsKept() {
        val spaceId = liveSpace()
        val task = runBlocking {
            assertNotNull(repository.addTask(spaceId = spaceId, title = "before"))
        }
        runBlocking { assertEquals(CommitOutcome.Accepted, cloud.commit(spaceId) { true }) }

        val container = TaskEditContainer(repository, cloud, spaceId, task.id, SavedStateHandle())
            .also { containers += it }
        val announced = mutableListOf<TaskEditAction>()
        watch(container.store, announced)
        // Something half-written, kept for process death. It is the only copy of the work once the
        // change itself has been taken back out.
        container.formPersistence.write(draftSaying("the user's unfinished sentence"))

        theServerGoesAway()
        container.store.intent(TaskEditIntent.SaveTask(task.copy(title = "after")))
        runBlocking { waitFor { announced.isNotEmpty() } }

        assertIs<TaskEditAction.TaskSaveNotAccepted>(announced.single())
        assertEquals(
            "the user's unfinished sentence",
            container.formPersistence.read()?.title,
            "the draft was thrown away over a save that did not happen",
        )
        assertEquals(
            listOf("before"),
            runBlocking { repository.getAllTasks(spaceId).map { it.title } },
            "the space kept a change the server refused",
        )
    }

    @Test
    fun anEditTheServerTakesIsReportedAndTheDraftIsCleared() {
        val spaceId = liveSpace()
        val task = runBlocking {
            assertNotNull(repository.addTask(spaceId = spaceId, title = "before"))
        }
        runBlocking { assertEquals(CommitOutcome.Accepted, cloud.commit(spaceId) { true }) }

        val container = TaskEditContainer(repository, cloud, spaceId, task.id, SavedStateHandle())
            .also { containers += it }
        val announced = mutableListOf<TaskEditAction>()
        watch(container.store, announced)
        container.formPersistence.write(draftSaying("no longer needed"))

        container.store.intent(TaskEditIntent.SaveTask(task.copy(title = "after")))
        runBlocking { waitFor { announced.isNotEmpty() } }

        assertIs<TaskEditAction.TaskSaved>(announced.single())
        assertEquals(null, container.formPersistence.read())
    }

    // ------------------------------------------------------------------ creating

    @Test
    fun aNewTaskTheServerRefusesIsReportedAndTheDraftIsKept() {
        val spaceId = liveSpace()
        val container = NewTaskContainer(repository, cloud, spaceId, null, null, SavedStateHandle())
            .also { containers += it }
        val announced = mutableListOf<NewTaskAction>()
        watch(container.store, announced)
        container.formPersistence.write(draftSaying("everything typed into the form"))

        theServerGoesAway()
        container.store.intent(
            NewTaskIntent.CreateTask(
                title = "written as it died",
                description = "",
                status = TaskStatus.Open,
                dueDate = null,
                priority = null,
                estimatedTime = null,
                tags = persistentSetOf(),
                connections = persistentSetOf(),
                notifications = persistentListOf(),
                recurrenceRules = persistentListOf(),
                autoUpdateStatusFromSubtasks = false,
                dueSound = ChosenSound.Deferred,
            )
        )
        runBlocking { waitFor { announced.isNotEmpty() } }

        assertIs<NewTaskAction.TaskNotAccepted>(announced.single())
        assertEquals("everything typed into the form", container.formPersistence.read()?.title)
        assertTrue(runBlocking { repository.getAllTasks(spaceId) }.isEmpty())
    }

    /** A stored form with [title] in it, standing in for whatever the user had typed. */
    private fun draftSaying(title: String) = PersistedFormState(
        title = title,
        description = null,
        priority = null,
        estimatedTime = null,
        tags = persistentSetOf(),
        dueDate = null,
        status = null,
        connections = null,
        connectionsBase = null,
        notifications = null,
        notificationSounds = null,
        recurrenceRules = null,
        autoUpdateStatusFromSubtasks = null,
        dueSound = null,
    )

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            kotlinx.coroutines.delay(10)
        }
        throw AssertionError("the container never answered")
    }
}
