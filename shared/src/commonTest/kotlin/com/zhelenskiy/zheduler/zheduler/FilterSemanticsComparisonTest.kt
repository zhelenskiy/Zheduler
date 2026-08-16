package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The filter panel's criteria are matched in Kotlin by one repository and in SQL by the other.
 * These pin the places the two had drifted apart, each of which the user reaches with one tap or
 * one typed character.
 */
@OptIn(ExperimentalTime::class)
class FilterSemanticsComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private class Both(val inMemory: TaskRepository, val db: TaskRepository, val space: String)

    private suspend fun both(setup: suspend TaskRepository.(String) -> Unit): List<Both> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System)).map {
            val space = it.createSpace("Test", "TEST")!!
            it.setup(space.id)
            Both(it, it, space.id)
        }

    /** Titles both repositories return, asserted equal, under [criteria] and any [filters]. */
    private suspend fun List<Both>.titlesFor(
        criteria: TaskFilterCriteria = TaskFilterCriteria(),
        filters: PersistentList<GroupFilter> = persistentListOf(),
    ): Set<String> {
        val results = map { context ->
            context.db.getTasksForGroup(context.space, filters, persistentListOf(), criteria)
                .map { it.task.title }
                .toSet()
        }
        assertEquals(results[0], results[1], "the two repositories disagree")
        return results[0]
    }

    // ---- Search box ----

    private suspend fun searchFixture() = both { spaceId ->
        addTask(spaceId, title = "fix the login bug")
        addTask(spaceId, title = "buy milk", description = "urgent")
        addTask(spaceId, title = "50% off sale")
        addTask(spaceId, title = "a_b naming")
        addTask(spaceId, title = "axb naming")
        addTask(spaceId, title = "Задача")
    }

    private fun search(query: String) = TaskFilterCriteria(
        searchQuery = query,
        textSearchFields = persistentSetOf(TaskTextSearchField.Title, TaskTextSearchField.Description),
    )

    @Test
    fun `terms may appear in any order`() = runTest {
        assertEquals(setOf("fix the login bug"), searchFixture().titlesFor(search("bug fix")))
    }

    @Test
    fun `terms may be spread across the searched fields`() = runTest {
        assertEquals(setOf("buy milk"), searchFixture().titlesFor(search("milk urgent")))
    }

    @Test
    fun `a percent sign is a character, not a wildcard`() = runTest {
        assertEquals(setOf("50% off sale"), searchFixture().titlesFor(search("50%")))
        // As a wildcard this matches everything; as text, nothing has it.
        assertEquals(emptySet(), searchFixture().titlesFor(search("%o%")))
    }

    @Test
    fun `an underscore is a character, not a wildcard`() = runTest {
        // "axb naming" is there too: as a wildcard, a_b matches it.
        assertEquals(setOf("a_b naming"), searchFixture().titlesFor(search("a_b")))
    }

    @Test
    fun `case is ignored beyond ASCII`() = runTest {
        assertEquals(setOf("Задача"), searchFixture().titlesFor(search("задача")))
    }

    @Test
    fun `a plain single-word search still works`() = runTest {
        assertEquals(setOf("buy milk"), searchFixture().titlesFor(search("milk")))
    }

    // ---- Connection type chips ----

    @Test
    fun `ticking two connection types matches tasks having either`() = runTest {
        val contexts = both { spaceId ->
            val target = addTask(spaceId, title = "target")!!
            addTask(spaceId, title = "depends", connections = persistentSetOf(TaskConnection(target.id, ConnectionType.DependsOn)))
            addTask(spaceId, title = "relates", connections = persistentSetOf(TaskConnection(target.id, ConnectionType.RelatesTo)))
            addTask(spaceId, title = "unconnected")
        }

        val titles = contexts.titlesFor(
            TaskFilterCriteria(
                connectionTypeFilters = persistentSetOf(
                    ConnectionTypeOption.DependsOn,
                    ConnectionTypeOption.RelatesTo,
                )
            )
        )
        // "target" is in too, and rightly: connections are symmetric, so it holds the RelatesTo
        // side of the third task's edge. What matters is that this is a union — under "all of the
        // ticked kinds" no task has both DependsOn and RelatesTo, and the board would be empty.
        assertEquals(setOf("target", "depends", "relates"), titles)
    }

    @Test
    fun `subtask-of together with not-a-subtask matches everything, not nothing`() = runTest {
        val contexts = both { spaceId ->
            val parent = addTask(spaceId, title = "parent")!!
            addTask(spaceId, title = "child", connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf)))
        }

        val titles = contexts.titlesFor(
            TaskFilterCriteria(
                connectionTypeFilters = persistentSetOf(
                    ConnectionTypeOption.SubtaskOf,
                    ConnectionTypeOption.NotSubtask,
                )
            )
        )
        assertEquals(setOf("parent", "child"), titles, "the two chips together cover every task")
    }

    // ---- Nested groups of the same field ----

    private suspend fun priorityFixture() = both { spaceId ->
        addTask(spaceId, title = "no priority", priority = null)
        addTask(spaceId, title = "low", priority = Priority(10))
        addTask(spaceId, title = "high", priority = Priority(90))
    }

    @Test
    fun `a range nested inside no-priority admits nothing`() = runTest {
        val titles = priorityFixture().titlesFor(
            filters = persistentListOf(
                GroupFilter.PriorityRange(includeNull = true),
                GroupFilter.PriorityRange(min = 1, max = 25),
            )
        )
        assertEquals(emptySet(), titles, "having no priority and being between 1 and 25 is impossible")
    }

    @Test
    fun `a range nested inside a wider range keeps only the overlap`() = runTest {
        val titles = priorityFixture().titlesFor(
            filters = persistentListOf(
                GroupFilter.PriorityRange(min = 1, max = 100),
                GroupFilter.PriorityRange(min = 1, max = 25),
            )
        )
        assertEquals(setOf("low"), titles)
    }

    @Test
    fun `no-priority nested inside no-priority-or-range keeps the nulls only`() = runTest {
        val titles = priorityFixture().titlesFor(
            filters = persistentListOf(
                GroupFilter.PriorityRange(min = 1, max = 100, includeNull = true),
                GroupFilter.PriorityRange(includeNull = true),
            )
        )
        assertEquals(setOf("no priority"), titles)
    }

    // ---- Boolean groups ----

    @Test
    fun `a boolean group admitting both values matches every task`() = runTest {
        val contexts = both { spaceId ->
            addTask(spaceId, title = "plain")
            addTask(spaceId, title = "also plain")
        }

        val titles = contexts.titlesFor(
            filters = persistentListOf(
                GroupFilter.Values(GroupableField.IsRecurring, persistentSetOf("true", "false")),
            )
        )
        assertEquals(setOf("plain", "also plain"), titles, "both values are admitted, so nothing is excluded")
    }

    @Test
    fun `a boolean group admitting one value still filters`() = runTest {
        val contexts = both { spaceId ->
            addTask(spaceId, title = "plain")
        }

        assertEquals(
            setOf("plain"),
            contexts.titlesFor(
                filters = persistentListOf(GroupFilter.Values(GroupableField.IsRecurring, persistentSetOf("false")))
            ),
        )
        assertEquals(
            emptySet(),
            contexts.titlesFor(
                filters = persistentListOf(GroupFilter.Values(GroupableField.IsRecurring, persistentSetOf("true")))
            ),
        )
    }

    // ---- Status class matching ----

    @Test
    fun `a status name inside a declined reason is not a status`() = runTest {
        val contexts = both { spaceId ->
            addTask(
                spaceId,
                title = "declined",
                status = TaskStatus.Declined("superseded, was TaskStatus.Done already"),
            )
            addTask(spaceId, title = "really done", status = TaskStatus.Done)
        }

        val titles = contexts.titlesFor(
            TaskFilterCriteria(statusFilters = persistentSetOf(TaskStatus.Done))
        )
        assertEquals(setOf("really done"), titles)
    }

    @Test
    fun `a status name inside a blocked comment is not a status`() = runTest {
        val contexts = both { spaceId ->
            val blocker = addTask(spaceId, title = "blocker")!!
            addTask(
                spaceId,
                title = "blocked",
                status = TaskStatus.Blocked(persistentSetOf(blocker.id), "waiting on TaskStatus.InProgress work"),
            )
        }

        val titles = contexts.titlesFor(
            TaskFilterCriteria(statusFilters = persistentSetOf(TaskStatus.InProgress))
        )
        assertEquals(emptySet(), titles)
    }
}
