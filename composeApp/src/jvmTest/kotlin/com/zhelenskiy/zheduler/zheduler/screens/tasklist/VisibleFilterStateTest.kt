@file:OptIn(ExperimentalTestApi::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * A filter in force has to be visible somewhere the user can reach it.
 *
 * The connection-id boxes filter on their own, ticked chip or not, but each used to be drawn only
 * while its type chip was ticked: clearing the chip hid text that went on narrowing the list, with
 * nothing on screen to explain the missing tasks and no way to clear it short of resetting every
 * filter. The same held for the summary chip above the list.
 */
class VisibleFilterStateTest {

    @Test
    fun anIdFilterLeftByAnUntickedChipIsStillOnScreen() = runComposeUiTest {
        val filterState = TaskFilterState().apply { dependsOnTaskIds = "TEST-5" }

        setContent {
            TaskFilterPanel(filterState = filterState, allTags = emptySet(), spaceIdPrefix = "TEST")
        }
        waitForIdle()

        // No connection type is ticked, so the category has to be opened to look.
        onNodeWithText("Connections").performClick()
        waitForIdle()

        onNodeWithText("Depends on (Task IDs)").assertExists()
        onNodeWithText("TEST-5").assertExists()
    }

    @Test
    fun aTagFilterOutlivingItsVocabularyIsStillOnScreen() = runComposeUiTest {
        // The space's tags were deleted; the tasks carrying them, and this filter, remain.
        val filterState = TaskFilterState().apply { selectedTags = selectedTags.add("work") }

        setContent {
            TaskFilterPanel(filterState = filterState, allTags = emptySet(), spaceIdPrefix = "TEST")
        }
        waitForIdle()

        onNodeWithText("Tags").performClick()
        waitForIdle()

        onNodeWithText("work").assertExists()
    }
}
