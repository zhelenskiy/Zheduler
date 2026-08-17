@file:OptIn(ExperimentalTestApi::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.GroupableField
import com.zhelenskiy.zheduler.zheduler.components.common.ReorderControls
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The order of these lists is not decoration — it decides how groups nest and which sort rule wins
 * — and dragging was the only way to change it. Dragging needs a pointer, so a keyboard-only user,
 * or anyone working through a screen reader, could build a view mode and never arrange it.
 */
class ReorderWithoutDraggingTest {

    @Test
    fun aLevelCanBeMovedWithoutAPointer() = runComposeUiTest {
        val state = ViewModeEditorState(spaceId = "space-1")
        state.groupingLevels.add(GroupingLevelState())
        state.groupingLevels.add(GroupingLevelState().apply {
            field = GroupableField.Tags
            initializeDefaultGroups()
        })

        setContent {
            MaterialTheme {
                ReorderControls(
                    what = "level 2",
                    canMoveUp = true,
                    canMoveDown = false,
                    onMoveUp = { state.moveGroupingLevel(1, 0) },
                    onMoveDown = {},
                )
            }
        }
        waitForIdle()

        onNodeWithContentDescription("Move level 2 up").performClick()
        waitForIdle()

        assertEquals(
            listOf(GroupableField.Tags, GroupableField.Status),
            state.groupingLevels.map { it.field },
            "the level did not move",
        )
    }

    @Test
    fun theEndsOfTheListCannotBeMovedPastIt() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ReorderControls(
                    what = "rule 1",
                    canMoveUp = false,
                    canMoveDown = true,
                    onMoveUp = {},
                    onMoveDown = {},
                )
            }
        }
        waitForIdle()

        onNodeWithContentDescription("Move rule 1 up").assertIsNotEnabled()
        onNodeWithContentDescription("Move rule 1 down").assertIsEnabled()
    }
}
