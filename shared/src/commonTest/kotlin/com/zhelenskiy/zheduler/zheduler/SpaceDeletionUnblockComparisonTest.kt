package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Deleting a space removes tasks that other spaces may be blocked on. Those blocked tasks have to
 * end up where deleting the same blockers one at a time would have left them — including the
 * timeline entry and the parent cascade, which are not optional extras: a task nobody is waiting
 * on any more must not stay Blocked, because nothing will ever look at it again.
 */
@OptIn(ExperimentalTime::class)
class SpaceDeletionUnblockComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    @Test
    fun `a task whose remaining blockers are all resolved is unblocked`() = runTest {
        for (repository in repositories()) {
            val doomed = repository.createSpace("Doomed", "AAA")!!
            val keep = repository.createSpace("Keep", "BBB")!!

            val crossSpaceBlocker = repository.addTask(doomed.id, title = "blocker in doomed space")!!
            val resolvedBlocker = repository.addTask(keep.id, title = "already done", status = TaskStatus.Done)!!
            val blocked = repository.addTask(
                keep.id,
                title = "blocked",
                status = TaskStatus.Blocked(
                    persistentSetOf(crossSpaceBlocker.id, resolvedBlocker.id),
                    "waiting",
                ),
            )!!

            repository.deleteSpace(doomed.id)

            val after = repository.getTaskById(blocked.id)!!
            assertEquals(
                TaskStatus.InProgress,
                after.status,
                "$repository: the only blocker left is Done, and nothing will revisit this task",
            )
        }
    }

    @Test
    fun `an unresolved remaining blocker keeps the task blocked`() = runTest {
        for (repository in repositories()) {
            val doomed = repository.createSpace("Doomed", "AAA")!!
            val keep = repository.createSpace("Keep", "BBB")!!

            val crossSpaceBlocker = repository.addTask(doomed.id, title = "blocker in doomed space")!!
            val openBlocker = repository.addTask(keep.id, title = "still open")!!
            val blocked = repository.addTask(
                keep.id,
                title = "blocked",
                status = TaskStatus.Blocked(persistentSetOf(crossSpaceBlocker.id, openBlocker.id), "waiting"),
            )!!

            repository.deleteSpace(doomed.id)

            val status = repository.getTaskById(blocked.id)!!.status
            assertTrue(status is TaskStatus.Blocked, "$repository: one blocker is still open")
            assertEquals(persistentSetOf(openBlocker.id), status.blockerTaskIds, "$repository")
        }
    }

    @Test
    fun `the automatic unblock is recorded on the timeline`() = runTest {
        for (repository in repositories()) {
            val doomed = repository.createSpace("Doomed", "AAA")!!
            val keep = repository.createSpace("Keep", "BBB")!!

            val blocker = repository.addTask(doomed.id, title = "blocker")!!
            val blocked = repository.addTask(
                keep.id,
                title = "blocked",
                status = TaskStatus.Blocked(persistentSetOf(blocker.id), "waiting"),
            )!!

            repository.deleteSpace(doomed.id)

            val timeline = repository.getStatusTimeline(blocked.id)
            assertTrue(
                timeline.any { it.automaticChangeReason == AutomaticChangeReason.Unblocked },
                "$repository: every other automatic unblock is recorded; got $timeline",
            )
        }
    }
}
