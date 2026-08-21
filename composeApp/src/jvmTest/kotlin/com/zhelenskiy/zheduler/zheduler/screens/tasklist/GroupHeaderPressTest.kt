package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.GroupDefinition
import com.zhelenskiy.zheduler.zheduler.GroupFilter
import com.zhelenskiy.zheduler.zheduler.GroupableField
import com.zhelenskiy.zheduler.zheduler.GroupingLevel
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import com.zhelenskiy.zheduler.zheduler.TaskGroupInfo
import com.zhelenskiy.zheduler.zheduler.ViewMode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * The whole header opens its group, and the arrow is the only part that shows the press.
 *
 * Sharing one press between the two is easy to take apart by accident, and what would say so is a
 * ripple missing from a 24dp arrow — too little to notice by eye, and invisible to a test, since
 * no indication reaches the semantics tree. What can be pinned down is pinned down here instead:
 * that the header still opens its group, and that it is one target rather than two.
 */
class GroupHeaderPressTest {

    private val nested = ViewMode(
        id = "vm",
        name = "Nested",
        spaceId = "space",
        groupingLevels = persistentListOf(
            GroupingLevel(
                field = GroupableField.Status,
                groups = persistentListOf(
                    GroupDefinition(label = "Work", values = persistentSetOf("Open")),
                ),
            ),
            GroupingLevel(
                field = GroupableField.IsRecurring,
                groups = persistentListOf(
                    GroupDefinition(label = "Repeating", values = persistentSetOf("true")),
                ),
            ),
        ),
    )

    private val parent = TaskGroupInfo(
        label = "Work",
        taskCount = 1,
        groupDefinition = GroupDefinition(label = "Work", values = persistentSetOf("Open")),
        filter = GroupFilter.Values(GroupableField.Status, persistentSetOf("Open")),
    )

    private val child = TaskGroupInfo(
        label = "Repeating",
        taskCount = 1,
        groupDefinition = GroupDefinition(label = "Repeating", values = persistentSetOf("true")),
        filter = GroupFilter.Values(GroupableField.IsRecurring, persistentSetOf("true")),
    )

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeContent() = @androidx.compose.runtime.Composable {
        MaterialTheme {
            DynamicTaskList(
                viewMode = nested,
                filterCriteria = TaskFilterCriteria(),
                dataVersion = 0L,
                shouldAnimate = false,
                onGetTaskGroups = { _, levelIndex, _, _ ->
                    if (levelIndex == 0) listOf(parent) else listOf(child)
                },
                onCountTasks = { _, _ -> 1 },
                onGetTaskPages = { _, _, _, _ -> flowOf(PagingData.empty()) },
                onMatchingCountChange = {},
                onTaskClick = {},
                onDelete = {},
                onCopy = {},
            )
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the header itself closes and opens its group`() = runComposeUiTest {
        setContent(ComposeContent())
        waitForIdle()
        onNodeWithText("Repeating (1)").assertExists("the group starts open")

        // The label, not the arrow: it is the far end of the row from the only thing that shows a
        // press, and it is where a finger lands.
        onNodeWithText("Work (1)").performClick()
        waitForIdle()
        onNodeWithText("Repeating (1)").assertDoesNotExist()

        onNodeWithText("Work (1)").performClick()
        waitForIdle()
        onNodeWithText("Repeating (1)").assertExists("pressing the header again did not reopen it")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the header is one thing to press rather than two`() = runComposeUiTest {
        setContent(ComposeContent())
        waitForIdle()

        // No click target inside another one: the arrow would be one if its semantics were ever
        // left in place, and a keyboard user would walk through every header twice, the second
        // time onto something that announces nothing. Counted this way rather than by totalling
        // the screen's targets, which says nothing about headers once anything else is on it.
        onAllNodes(
            hasClickAction() and hasAnyAncestor(hasClickAction()),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }
}
