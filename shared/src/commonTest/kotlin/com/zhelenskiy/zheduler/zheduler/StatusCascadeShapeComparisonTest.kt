package com.zhelenskiy.zheduler.zheduler

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A task can be reached from below by more than one chain of subtasks. The cascade that restates
 * derived statuses has to cope with that: it must not loop on a cycle, and it must not decline to
 * re-derive a task simply because an earlier branch had already looked at it and found nothing to
 * do — the later branch is the one that changes the answer.
 */
@OptIn(ExperimentalTime::class)
class StatusCascadeShapeComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    private suspend fun TaskRepository.auto(spaceId: String, title: String) =
        addTask(spaceId, title = title, autoUpdateStatusFromSubtasks = true)!!

    @Test
    fun `a shared ancestor is re-derived by whichever branch finishes last`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!

            // leaf -> mid -> {left, right} -> top. Both left and right reach top, so the cascade
            // arrives there twice; only the second visit can see every child resolved.
            val top = repository.auto(space.id, "top")
            val left = repository.auto(space.id, "left")
            val right = repository.auto(space.id, "right")
            val mid = repository.auto(space.id, "mid")
            val leaf = repository.addTask(space.id, title = "leaf")!!

            repository.addConnection(left.id, top.id, ConnectionType.SubtaskOf)
            repository.addConnection(right.id, top.id, ConnectionType.SubtaskOf)
            repository.addConnection(mid.id, left.id, ConnectionType.SubtaskOf)
            repository.addConnection(mid.id, right.id, ConnectionType.SubtaskOf)
            repository.addConnection(leaf.id, mid.id, ConnectionType.SubtaskOf)

            repository.updateTask(repository.getTaskById(leaf.id)!!.copy(status = TaskStatus.Done))

            assertEquals(
                TaskStatus.Done,
                repository.getTaskById(top.id)!!.status,
                "$repository: every chain below top ends in a Done leaf",
            )
        }
    }

    @Test
    fun `an ordinary chain still settles`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val grandparent = repository.auto(space.id, "grandparent")
            val parent = repository.auto(space.id, "parent")
            val child = repository.addTask(space.id, title = "child")!!

            repository.addConnection(parent.id, grandparent.id, ConnectionType.SubtaskOf)
            repository.addConnection(child.id, parent.id, ConnectionType.SubtaskOf)

            repository.updateTask(repository.getTaskById(child.id)!!.copy(status = TaskStatus.Done))

            assertEquals(TaskStatus.Done, repository.getTaskById(parent.id)!!.status, "$repository")
            assertEquals(TaskStatus.Done, repository.getTaskById(grandparent.id)!!.status, "$repository")
        }
    }
}
