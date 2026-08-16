package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.GroupDefinition
import com.zhelenskiy.zheduler.zheduler.GroupFilter
import com.zhelenskiy.zheduler.zheduler.GroupableField
import com.zhelenskiy.zheduler.zheduler.GroupingLevel
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import com.zhelenskiy.zheduler.zheduler.TaskGroupInfo
import com.zhelenskiy.zheduler.zheduler.TaskWithTotals
import com.zhelenskiy.zheduler.zheduler.ViewMode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * A group's label is user text: two groups may share one, and one may be called "Uncategorized"
 * like the bucket the list adds itself. Nothing forbids either, so the list's identities for
 * groups cannot be built out of labels — a LazyColumn item key that repeats is a crash, not a
 * cosmetic problem.
 */
class DuplicateGroupLabelTest {

    private fun viewModeWithTwoGroupsCalled(label: String) = ViewMode(
        id = "vm",
        name = "Grouped",
        spaceId = "space",
        groupingLevels = persistentListOf(
            GroupingLevel(
                field = GroupableField.Status,
                groups = persistentListOf(
                    GroupDefinition(label = label, values = persistentSetOf("Open")),
                    GroupDefinition(label = label, values = persistentSetOf("Done")),
                ),
            )
        ),
    )

    @OptIn(ExperimentalTestApi::class)
    private fun render(viewMode: ViewMode, groups: List<TaskGroupInfo>) = runComposeUiTest {
        setContent {
            MaterialTheme {
                DynamicTaskList(
                    viewMode = viewMode,
                    filterCriteria = TaskFilterCriteria(),
                    dataVersion = 0L,
                    shouldAnimate = false,
                    onGetTaskGroups = { _, _, _, _ -> groups },
                    onCountTasks = { _, _ -> 1 },
                    onGetTaskPages = { _, _, _ -> flowOf(PagingData.empty<TaskWithTotals>()) },
                    onMatchingCountChange = {},
                    onTaskClick = {},
                    onDelete = {},
                    onCopy = {},
                )
            }
        }
        waitForIdle()
    }

    private fun groupInfo(label: String, status: String) = TaskGroupInfo(
        label = label,
        taskCount = 1,
        groupDefinition = GroupDefinition(label = label, values = persistentSetOf(status)),
        filter = GroupFilter.Values(GroupableField.Status, persistentSetOf(status)),
    )

    @Test
    fun `two groups sharing a label render without colliding`() {
        render(
            viewModeWithTwoGroupsCalled("Work"),
            listOf(groupInfo("Work", "Open"), groupInfo("Work", "Done")),
        )
    }

    @Test
    fun `a group named Uncategorized does not collide with the automatic bucket`() {
        render(
            viewModeWithTwoGroupsCalled("Uncategorized"),
            listOf(
                groupInfo("Uncategorized", "Open"),
                TaskGroupInfo(label = "", taskCount = 1, isUncategorized = true),
            ),
        )
    }

    @Test
    fun `labels containing the key separator do not alias across levels`() {
        // "A" then "B.C" nested, versus "A.B" then "C", used to produce the same leaf key.
        render(
            viewModeWithTwoGroupsCalled("A.B"),
            listOf(groupInfo("A.B", "Open"), groupInfo("A", "Done")),
        )
    }
}
