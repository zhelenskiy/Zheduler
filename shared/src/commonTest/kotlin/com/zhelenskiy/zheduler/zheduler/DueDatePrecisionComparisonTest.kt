package com.zhelenskiy.zheduler.zheduler

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A due date is kept to the millisecond, because that is what the column holds.
 *
 * Callers hand over whatever precision they have — a clock reading on the JVM carries microseconds
 * — so a task returned unrounded did not match the one the next read produced, and the two
 * repositories, one rounding and one not, stored different instants for the same input.
 */
@OptIn(ExperimentalTime::class)
class DueDatePrecisionComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    /** A due date with sub-millisecond precision, as a clock reading would have. */
    private val fractional = Instant.fromEpochSeconds(1_800_000_000, 123_456_789)

    @Test
    fun `a created task matches what is read back`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val created = repository.addTask(space.id, title = "due soon", dueDate = fractional)!!

            assertEquals(created.dueDate, repository.getTaskById(created.id)!!.dueDate, "$repository")
        }
    }

    @Test
    fun `an updated task matches what is read back`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val task = repository.addTask(space.id, title = "due soon")!!

            val updated = repository.updateTask(task.copy(dueDate = fractional))!!

            assertEquals(updated.dueDate, repository.getTaskById(task.id)!!.dueDate, "$repository")
        }
    }

    @Test
    fun `both repositories store the same instant`() = runTest {
        val stored = repositories().map { repository ->
            val space = repository.createSpace("Test", "TEST")!!
            repository.addTask(space.id, title = "due soon", dueDate = fractional)!!.dueDate
        }

        assertEquals(Instant.fromEpochMilliseconds(fractional.toEpochMilliseconds()), stored[0])
        assertEquals(stored[0], stored[1])
    }
}
