@file:OptIn(ExperimentalTestApi::class, ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.zhelenskiy.zheduler.zheduler.Task
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.ExperimentalTime

/**
 * Configuring a recurrence rule is a long piece of work that reaches the task only on Save.
 *
 * An activity recreation part way through — a rotation, a theme switch, the system reclaiming
 * memory behind a picker — used to close the dialog and take the schedule with it, without so much
 * as a prompt.
 */
class RecurrenceDialogRestoreTest {

    @Test
    fun theScheduleBeingConfiguredSurvivesRecreation() = runComposeUiTest {
        var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
        // Composed at the same position both times: saved state is keyed on where it sits.
        var onScreen by mutableStateOf(true)

        setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                if (onScreen) {
                    val noTasks = flowOf(PagingData.empty<Task>()).collectAsLazyPagingItems()
                    SingleRecurrenceRuleDialog(
                        currentRule = null,
                        filteredTasks = noTasks,
                        loadedTasks = emptyMap(),
                        onFilterTasks = {},
                        onLoadTask = {},
                        onDismiss = {},
                        onRecurrenceSelected = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithText("After timeout").performClick()
        waitForIdle()
        onNodeWithText("Interval (e.g., 1w 2d 3h)").performTextInput("3d")
        waitForIdle()

        val saved = registry.performSave()
        onScreen = false
        waitForIdle()
        registry = SaveableStateRegistry(restoredValues = saved) { true }
        onScreen = true
        waitForIdle()

        onNodeWithText("Interval (e.g., 1w 2d 3h)").assertExists()
        onNodeWithText("3d").assertExists()
    }
}
