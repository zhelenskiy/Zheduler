package com.zhelenskiy.zheduler.zheduler

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

/**
 * Filters with only one end set — "at least an hour", "due before Friday" — leave the other end
 * open, and the query states that as a bound rather than omitting it so the planner can still seek.
 *
 * The value chosen for the open end has to survive being bound to SQLite on every platform. On
 * Kotlin/JS a Long arrives as a JavaScript number, so anything past 2^53 is rounded and the
 * comparison it lands in stops matching: with Long.MAX_VALUE these filters returned nothing at all
 * on the web build. Running on JS is what catches that, so these live in commonTest.
 */
@OptIn(ExperimentalTime::class)
class OpenEndedRangeComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    private suspend fun TaskRepository.fixture(): String {
        val space = createSpace("Test", "TEST")!!
        addTask(space.id, title = "quick", estimatedTime = RecurrencePeriod(minutes = 10))
        addTask(space.id, title = "long", estimatedTime = RecurrencePeriod(hours = 5))
        addTask(space.id, title = "soon", dueDate = Clock.System.now() + 1.hours)
        addTask(space.id, title = "later", dueDate = Clock.System.now() + 30.days)
        addTask(space.id, title = "important", priority = Priority(90))
        return space.id
    }

    private suspend fun TaskRepository.titlesFor(spaceId: String, criteria: TaskFilterCriteria): Set<String> =
        getAllWithTotalsFiltered(spaceId, criteria).mapTo(mutableSetOf()) { it.task.title }

    @Test
    fun `an estimate with only a lower bound still matches`() = runTest {
        for (repository in repositories()) {
            val space = repository.fixture()
            assertEquals(
                setOf("long"),
                repository.titlesFor(
                    space,
                    TaskFilterCriteria(
                        estimatedTimeFilter = EstimatedTimeFilter.Custom,
                        customEstimatedTimeMin = "1h",
                    ),
                ),
                "$repository",
            )
        }
    }

    @Test
    fun `an estimate with only an upper bound still matches`() = runTest {
        for (repository in repositories()) {
            val space = repository.fixture()
            assertEquals(
                setOf("quick"),
                repository.titlesFor(
                    space,
                    TaskFilterCriteria(
                        estimatedTimeFilter = EstimatedTimeFilter.Custom,
                        customEstimatedTimeMax = "1h",
                    ),
                ),
                "$repository",
            )
        }
    }

    @Test
    fun `a due date with only a lower bound still matches`() = runTest {
        for (repository in repositories()) {
            val space = repository.fixture()
            assertEquals(
                setOf("later"),
                repository.titlesFor(
                    space,
                    TaskFilterCriteria(
                        dueDateFilter = DueDateFilter.Custom,
                        customDueDateAfter = Clock.System.now() + 7.days,
                    ),
                ),
                "$repository",
            )
        }
    }

    @Test
    fun `a high priority band whose upper end is open still matches`() = runTest {
        for (repository in repositories()) {
            val space = repository.fixture()
            assertEquals(
                setOf("important"),
                repository.titlesFor(space, TaskFilterCriteria(priorityFilter = PriorityFilter.High)),
                "$repository",
            )
        }
    }
}
