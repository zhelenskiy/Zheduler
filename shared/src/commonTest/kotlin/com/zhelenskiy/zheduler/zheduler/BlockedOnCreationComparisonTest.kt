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
 * Nothing re-examines a blocked task except one of its blockers changing status. A task created
 * blocked on work that is already finished would therefore wait for an event that has already
 * happened and can never happen again, so the check has to run when it is created — not only when
 * it is edited, which is the only place it used to run.
 */
@OptIn(ExperimentalTime::class)
class BlockedOnCreationComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    @Test
    fun `a task created blocked on finished work is not blocked`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val done = repository.addTask(space.id, title = "already done", status = TaskStatus.Done)!!

            val created = repository.addTask(
                space.id,
                title = "waiting on nothing",
                status = TaskStatus.Blocked(persistentSetOf(done.id), "waiting"),
            )!!

            assertEquals(
                TaskStatus.InProgress,
                repository.getTaskById(created.id)!!.status,
                "$repository: its only blocker is Done and nothing will revisit this task",
            )
        }
    }

    @Test
    fun `a task created blocked on outstanding work stays blocked`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val open = repository.addTask(space.id, title = "still open")!!

            val created = repository.addTask(
                space.id,
                title = "waiting",
                status = TaskStatus.Blocked(persistentSetOf(open.id), "waiting"),
            )!!

            val status = repository.getTaskById(created.id)!!.status
            assertIs<TaskStatus.Blocked>(status, "$repository")
            assertEquals(persistentSetOf(open.id), status.blockerTaskIds, "$repository")
        }
    }

    @Test
    fun `blocked without naming a blocker is left alone`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val created = repository.addTask(
                space.id,
                title = "blocked on something unstated",
                status = TaskStatus.Blocked(persistentSetOf(), "waiting on a person"),
            )!!

            assertIs<TaskStatus.Blocked>(
                repository.getTaskById(created.id)!!.status,
                "$repository: an empty blocker set means blocked without saying by what",
            )
        }
    }
}
