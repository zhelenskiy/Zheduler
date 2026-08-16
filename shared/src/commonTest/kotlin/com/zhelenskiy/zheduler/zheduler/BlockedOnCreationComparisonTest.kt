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
    fun `importing settles blocked statuses the same way whichever order the file is in`() = runTest {
        for (repository in repositories()) {
            val source = repository.createSpace("Source", "SRC")!!
            // The blocker is created *after* the task that waits on it, so during the import it
            // does not exist yet when that task is written. Row order in an export is not
            // specified, and judging each task as it lands made the outcome depend on it.
            val waiting = repository.addTask(source.id, title = "waiting")!!
            val blocker = repository.addTask(source.id, title = "blocker")!!
            repository.updateTask(
                repository.getTaskById(waiting.id)!!
                    .copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id), "waiting"))
            )

            // Marked Done in the file rather than through the repository. Saving the task blocked
            // on an already-Done blocker would be normalised on the way in, so the export would
            // carry InProgress and this test would prove nothing — which is exactly what an
            // earlier version of it did.
            val json = repository.exportSpaceToJson(source.id, prettyPrint = false)!!
                .replace(
                    """{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"}""",
                    """{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Done"}""",
                )
            val imported = repository.importSpaceFromJson(json)!!

            val importedWaiting = repository.getAllTasks(imported.id).single { it.title == "waiting" }
            assertEquals(
                TaskStatus.InProgress,
                importedWaiting.status,
                "$repository: its blocker is Done, so nothing will ever revisit it",
            )
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
