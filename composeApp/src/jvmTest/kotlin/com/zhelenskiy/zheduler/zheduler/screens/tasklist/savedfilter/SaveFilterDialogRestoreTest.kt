@file:OptIn(ExperimentalTestApi::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist.savedfilter

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.SavedFilter
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import kotlin.test.Test

/**
 * Two saved filters, edited through the same dialog.
 *
 * What `rememberSaveable` keeps belongs to a position in the composition, and this dialog occupies
 * the same position whichever filter is open in it. A name typed while editing one filter, left
 * behind by an activity recreation that closed the dialog, was handed to the next filter opened —
 * and saving wrote it onto that one instead.
 */
class SaveFilterDialogRestoreTest {

    private val first = SavedFilter(id = "f-1", name = "First filter", spaceId = "space-1", criteria = TaskFilterCriteria())
    private val second = SavedFilter(id = "f-2", name = "Second filter", spaceId = "space-1", criteria = TaskFilterCriteria())

    @Test
    fun aNameTypedForOneFilterDoesNotFollowIntoAnother() = runComposeUiTest {
        var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
        var editing by mutableStateOf<SavedFilter?>(first)

        setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                editing?.let { filter ->
                    SaveFilterDialog(
                        existingFilter = filter,
                        criteria = filter.criteria,
                        viewModes = emptyList(),
                        currentActiveViewModeId = null,
                        spaceId = "space-1",
                        allTags = emptySet(),
                        spaceIdPrefix = "TEST",
                        generateId = { "generated" },
                        onSave = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithText("First filter").performTextClearance()
        onNodeWithText("Filter Name").performTextInput("Renamed but abandoned")
        waitForIdle()

        // The recreation: the dialog's state is saved, the dialog goes away with the activity, and
        // the user comes back and opens a different filter for editing.
        val saved = registry.performSave()
        editing = null
        waitForIdle()
        registry = SaveableStateRegistry(restoredValues = saved) { true }
        editing = second
        waitForIdle()

        onNodeWithText("Second filter").assertExists()
    }

    @Test
    fun aNameTypedForOneFilterComesBackToThatFilter() = runComposeUiTest {
        var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
        var onScreen by mutableStateOf(true)

        setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                if (onScreen) {
                    SaveFilterDialog(
                        existingFilter = first,
                        criteria = first.criteria,
                        viewModes = emptyList(),
                        currentActiveViewModeId = null,
                        spaceId = "space-1",
                        allTags = emptySet(),
                        spaceIdPrefix = "TEST",
                        generateId = { "generated" },
                        onSave = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithText("First filter").performTextClearance()
        onNodeWithText("Filter Name").performTextInput("Renamed and kept")
        waitForIdle()

        val saved = registry.performSave()
        onScreen = false
        waitForIdle()
        registry = SaveableStateRegistry(restoredValues = saved) { true }
        onScreen = true
        waitForIdle()

        onNodeWithText("Renamed and kept").assertExists()
    }
}
