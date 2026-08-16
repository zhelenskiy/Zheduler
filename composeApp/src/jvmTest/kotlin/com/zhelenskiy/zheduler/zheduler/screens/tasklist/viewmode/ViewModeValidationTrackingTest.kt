package com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.GroupDefinition
import com.zhelenskiy.zheduler.zheduler.GroupableField
import com.zhelenskiy.zheduler.zheduler.GroupingLevel
import com.zhelenskiy.zheduler.zheduler.GroupingValidationResult
import com.zhelenskiy.zheduler.zheduler.ViewMode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The editor screen opens before the mode it is editing has been read from the database, so the
 * first [ViewModeEditorState] it builds is an empty placeholder that validates clean. Everything
 * downstream of validation — the Save button above all — has to follow the state that replaces it.
 */
class ViewModeValidationTrackingTest {

    private val invalidMode = ViewMode(
        id = "mode-1",
        name = "Loaded",
        spaceId = "space-1",
        groupingLevels = persistentListOf(
            GroupingLevel(
                field = GroupableField.Status,
                // A blank label is one of the errors the level editor reports.
                groups = persistentListOf(GroupDefinition(label = "  ", values = persistentSetOf("Open"))),
            )
        ),
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun validationFollowsTheEditorStateThatReplacesThePlaceholder() = runComposeUiTest {
        var loaded by mutableStateOf<ViewMode?>(null)
        lateinit var result: GroupingValidationResult

        setContent {
            val editorState = rememberViewModeEditorState(loaded, spaceId = "space-1")
            result = rememberViewModeValidation(editorState).value
        }

        waitForIdle()
        assertIs<GroupingValidationResult.Valid>(result, "an empty editor has nothing to report")

        loaded = invalidMode
        waitForIdle()

        val invalid = assertIs<GroupingValidationResult.Invalid>(
            result,
            "the blank group label of the loaded mode has to disable Save",
        )
        assertEquals(1, invalid.errors.size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun validationStillTracksEditsMadeAfterLoading() = runComposeUiTest {
        var loaded by mutableStateOf<ViewMode?>(null)
        lateinit var editorState: ViewModeEditorState
        lateinit var result: GroupingValidationResult

        setContent {
            editorState = rememberViewModeEditorState(loaded, spaceId = "space-1")
            result = rememberViewModeValidation(editorState).value
        }

        loaded = invalidMode
        waitForIdle()
        assertIs<GroupingValidationResult.Invalid>(result)

        editorState.groupingLevels[0].groups[0].label = "Unresolved"
        waitForIdle()
        assertIs<GroupingValidationResult.Valid>(result, "fixing the label has to re-enable Save")
    }
}
