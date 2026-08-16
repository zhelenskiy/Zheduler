package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.GroupDefinition
import com.zhelenskiy.zheduler.zheduler.GroupFilter
import com.zhelenskiy.zheduler.zheduler.GroupableField
import com.zhelenskiy.zheduler.zheduler.GroupingLevel
import com.zhelenskiy.zheduler.zheduler.NullPosition
import com.zhelenskiy.zheduler.zheduler.OrderDirection
import com.zhelenskiy.zheduler.zheduler.OrderableField
import com.zhelenskiy.zheduler.zheduler.OrderingRule
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import com.zhelenskiy.zheduler.zheduler.TaskGroupInfo
import com.zhelenskiy.zheduler.zheduler.TaskWithTotals
import com.zhelenskiy.zheduler.zheduler.ViewMode
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A group can carry its own ordering rules, which the editor offers and the management screen
 * lists. The list screen has to ask for a leaf's tasks with those rules rather than the view
 * mode's defaults, or configuring them changes nothing that the user can see.
 */
class LeafGroupOrderingTest {

    private val byTitle = persistentListOf(
        OrderingRule(OrderableField.Title, OrderDirection.Ascending, NullPosition.Last)
    )
    private val byPriority = persistentListOf(
        OrderingRule(OrderableField.Priority, OrderDirection.Descending, NullPosition.Last)
    )

    private fun viewModeWith(groupRules: PersistentList<OrderingRule>) = ViewMode(
        id = "vm",
        name = "Grouped",
        spaceId = "space",
        groupingLevels = persistentListOf(
            GroupingLevel(
                field = GroupableField.Status,
                groups = persistentListOf(
                    GroupDefinition(
                        label = "Open",
                        values = persistentSetOf("Open"),
                        orderingRules = groupRules,
                    )
                ),
            )
        ),
        defaultOrderingRules = byPriority,
    )

    /** Renders the list and returns the ordering rules it asked the leaf group's pages for. */
    @OptIn(ExperimentalTestApi::class)
    private fun rulesRequestedFor(
        viewMode: ViewMode,
        groupDefinition: GroupDefinition,
    ): PersistentList<OrderingRule> {
        var requested: PersistentList<OrderingRule>? = null
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    DynamicTaskList(
                        viewMode = viewMode,
                        filterCriteria = TaskFilterCriteria(),
                        dataVersion = 0L,
                        shouldAnimate = false,
                        onGetTaskGroups = { _, _, _, _ ->
                            listOf(
                                TaskGroupInfo(
                                    label = groupDefinition.label,
                                    taskCount = 1,
                                    groupDefinition = groupDefinition,
                                    filter = GroupFilter.Values(GroupableField.Status, persistentSetOf("Open")),
                                )
                            )
                        },
                        onCountTasks = { _, _ -> 1 },
                        onGetTaskPages = { _, rules, _ ->
                            requested = rules
                            emptyPages()
                        },
                        onMatchingCountChange = {},
                        onTaskClick = {},
                        onDelete = {},
                        onCopy = {},
                    )
                }
            }
            waitForIdle()
        }
        return requested ?: error("the leaf group's pages were never requested")
    }

    private fun emptyPages(): Flow<PagingData<TaskWithTotals>> = flowOf(PagingData.empty())

    @Test
    fun aGroupWithItsOwnRulesIsOrderedByThem() {
        assertEquals(byTitle, rulesRequestedFor(viewModeWith(byTitle), groupDefinitionOf(byTitle)))
    }

    @Test
    fun aGroupWithoutRulesFallsBackToTheViewModeDefaults() {
        val empty = persistentListOf<OrderingRule>()
        assertEquals(byPriority, rulesRequestedFor(viewModeWith(empty), groupDefinitionOf(empty)))
    }

    private fun groupDefinitionOf(rules: PersistentList<OrderingRule>) = GroupDefinition(
        label = "Open",
        values = persistentSetOf("Open"),
        orderingRules = rules,
    )
}
