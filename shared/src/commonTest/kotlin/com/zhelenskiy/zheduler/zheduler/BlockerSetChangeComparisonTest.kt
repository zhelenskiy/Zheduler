package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Rewriting which tasks a Blocked task is waiting on is a status change like any other, even
 * though the kind of status has not changed. The status dialog offers exactly that edit, and
 * everything downstream — a parent deriving its status, tasks waiting on this one — has to be told.
 */
@OptIn(ExperimentalTime::class)
class BlockerSetChangeComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    @Test
    fun `a parent follows its subtask onto a new blocker`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val first = repository.addTask(space.id, title = "first blocker")!!
            val second = repository.addTask(space.id, title = "second blocker")!!
            val subtask = repository.addTask(space.id, title = "subtask")!!
            val parent = repository.addTask(
                space.id,
                title = "parent",
                autoUpdateStatusFromSubtasks = true,
            )!!
            repository.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

            repository.updateTask(
                repository.getTaskById(subtask.id)!!
                    .copy(status = TaskStatus.Blocked(persistentSetOf(first.id), "waiting"))
            )
            // Same kind of status, different blocker — the change the cascade used to ignore.
            repository.updateTask(
                repository.getTaskById(subtask.id)!!
                    .copy(status = TaskStatus.Blocked(persistentSetOf(second.id), "waiting"))
            )

            val parentStatus = repository.getTaskById(parent.id)!!.status
            assertIs<TaskStatus.Blocked>(parentStatus, "$repository")
            assertEquals(
                persistentSetOf(second.id),
                parentStatus.blockerTaskIds,
                "$repository: the parent still lists a blocker its subtask no longer waits on",
            )
        }
    }

    @Test
    fun `completing an abandoned blocker does not unblock the parent`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val abandoned = repository.addTask(space.id, title = "abandoned blocker")!!
            val actual = repository.addTask(space.id, title = "actual blocker")!!
            val subtask = repository.addTask(space.id, title = "subtask")!!
            val parent = repository.addTask(
                space.id,
                title = "parent",
                autoUpdateStatusFromSubtasks = true,
            )!!
            repository.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

            repository.updateTask(
                repository.getTaskById(subtask.id)!!
                    .copy(status = TaskStatus.Blocked(persistentSetOf(abandoned.id), "waiting"))
            )
            repository.updateTask(
                repository.getTaskById(subtask.id)!!
                    .copy(status = TaskStatus.Blocked(persistentSetOf(actual.id), "waiting"))
            )

            // Finishing the blocker nobody waits on any more must not release anything.
            repository.updateTask(repository.getTaskById(abandoned.id)!!.copy(status = TaskStatus.Done))

            assertIs<TaskStatus.Blocked>(
                repository.getTaskById(subtask.id)!!.status,
                "$repository: the subtask still waits on the other blocker",
            )
            assertIs<TaskStatus.Blocked>(
                repository.getTaskById(parent.id)!!.status,
                "$repository: so the parent cannot be InProgress either",
            )
        }
    }
}
