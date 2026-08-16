package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A task's totals come from the whole dependency graph, not from whichever tasks happen to share
 * its group. Filtering a dependent out of view does not make it stop depending.
 */
@OptIn(ExperimentalTime::class)
class GroupTotalsScopeComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    /**
     * A space where a low-priority task carries a high-priority dependent that the filter hides,
     * answering with the visible task's total priority.
     */
    private suspend fun TaskRepository.hiddenDependentTotal(): Int? {
        val space = createSpace("Test", "TEST")!!
        val visible = addTask(space.id, title = "visible", priority = Priority(10))!!
        val hidden = addTask(space.id, title = "hidden", priority = Priority(100))!!
        addConnection(visible.id, hidden.id, ConnectionType.IsDependencyOf)
        updateTask(getTaskById(hidden.id)!!.copy(status = TaskStatus.InProgress))

        // Only tasks not yet started: the dependent is out of view, but still depends, and is
        // still unresolved — a finished one would not count towards a total anyway.
        val criteria = TaskFilterCriteria(statusFilters = persistentSetOf(TaskStatus.Open))
        return getTasksForGroup(space.id, persistentListOf(), persistentListOf(), criteria)
            .single { it.task.title == "visible" }
            .totalPriority
            ?.value
    }

    @Test
    fun `a filtered-out dependent still counts towards the total`() = runTest {
        val expected = InMemoryTaskRepository(Clock.System).hiddenDependentTotal()

        assertEquals(100, expected, "the dependent's priority is what makes the total")
        assertEquals(expected, createDatabaseRepository(Clock.System).hiddenDependentTotal())
    }
}
