package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A range group with neither bound set still says something: with nulls excluded it admits exactly
 * the tasks that have a value. The editor lets such a group be built, and the two repositories have
 * to agree on what it means — SQL read it as no restriction at all and let the valueless tasks in,
 * while the shared predicate sent them to Uncategorized.
 */
@OptIn(ExperimentalTime::class)
class UnboundedGroupRangeComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private val group = GroupFilter.PriorityRange(min = null, max = null, includeNull = false)

    /** Titles admitted by [group], in a space holding one task with a priority and one without. */
    private suspend fun TaskRepository.titlesInGroup(): List<String> {
        val space = createSpace("Test", "TEST")!!
        addTask(space.id, title = "has a priority", priority = Priority(30))
        addTask(space.id, title = "has none")

        return getTasksForGroup(space.id, persistentListOf(group), persistentListOf(), TaskFilterCriteria())
            .map { it.task.title }
            .sorted()
    }

    @Test
    fun `an unbounded priority group admits only tasks that have one`() = runTest {
        val expected = InMemoryTaskRepository(Clock.System).titlesInGroup()

        assertEquals(listOf("has a priority"), expected)
        assertEquals(expected, createDatabaseRepository(Clock.System).titlesInGroup())
    }

    @Test
    fun `a custom priority bound too large to be a priority is no bound`() = runTest {
        // The field takes free text. A number past Int.MAX_VALUE was a real bound to the SQL and
        // no bound at all to the shared predicate, so the same filter gave two different lists.
        val criteria = TaskFilterCriteria(
            priorityFilter = PriorityFilter.Custom,
            customPriorityMin = "3000000000",
        )

        val titles = listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System)).map { repository ->
            val space = repository.createSpace("Test", "TEST")!!
            repository.addTask(space.id, title = "has a priority", priority = Priority(30))
            repository.getTasksForGroup(space.id, persistentListOf(), persistentListOf(), criteria)
                .map { it.task.title }
        }

        assertEquals(listOf("has a priority"), titles[0])
        assertEquals(titles[0], titles[1])
    }

    @Test
    fun `the group count agrees with the tasks in it`() = runTest {
        for (repository in listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))) {
            val space = repository.createSpace("Test", "TEST")!!
            repository.addTask(space.id, title = "has a priority", priority = Priority(30))
            repository.addTask(space.id, title = "has none")

            val filters = persistentListOf(group)
            assertEquals(
                repository.getTasksForGroup(space.id, filters, persistentListOf(), TaskFilterCriteria()).size,
                repository.countTasksForGroup(space.id, filters, TaskFilterCriteria()),
                "$repository: the header counts tasks the group does not show",
            )
        }
    }
}
