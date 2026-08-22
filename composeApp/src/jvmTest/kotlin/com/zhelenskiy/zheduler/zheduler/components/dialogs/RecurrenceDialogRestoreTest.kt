@file:OptIn(ExperimentalTestApi::class, ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.zhelenskiy.zheduler.zheduler.RecurrenceRule
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.StatusChange
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun anOldRuleWatchingBothAtOnceIsNotNarrowedByOpeningIt() = runComposeUiTest {
        // A build ago there was one condition covering wifi and bluetooth together, and it fired
        // when *either* turned up. There are two now, and two have to both hold. Read as two, a
        // rule like this one would come back from Save meaning something stricter than it was
        // written to mean — here, a rule that only fires when the user is at the office *and* in
        // the car — with nothing on screen having changed to say so.
        val bothAtOnce = RecurrenceTrigger.NearbyChange(
            signals = persistentSetOf(
                NearbySignal.Wifi("Office"),
                NearbySignal.Bluetooth("AA:BB:CC:DD:EE:FF", "Car audio"),
            ),
        )
        val old = RecurrenceRule(
            timeRecurrenceTrigger = null,
            statusChangeTrigger = null,
            resetToStatus = TaskStatus.Open,
            nearbyTrigger = bothAtOnce,
        )
        var saved: RecurrenceRule? = null

        setContent {
            val noTasks = flowOf(PagingData.empty<Task>()).collectAsLazyPagingItems()
            SingleRecurrenceRuleDialog(
                currentRule = old,
                filteredTasks = noTasks,
                loadedTasks = emptyMap(),
                onFilterTasks = {},
                onLoadTask = {},
                onDismiss = {},
                onRecurrenceSelected = { saved = it },
            )
        }
        waitForIdle()

        onNodeWithText("Save").performClick()
        waitForIdle()

        assertEquals(old, saved, "opening and saving an old rule must not change what it watches")
    }

    @Test
    fun theOldConditionCanBeRemovedAndThenItIsGone() = runComposeUiTest {
        // The two chips are read *alongside* the old condition, not instead of it, so there has to
        // be a way to say "that one goes". Without it the old condition is a third thing every
        // firing must also satisfy, and a rule carrying one could only ever be deleted whole.
        val old = mixedRule()
        var saved: RecurrenceRule? = old

        setContent { Dialog(old) { saved = it } }
        waitForIdle()

        onNodeWithText("Remove").performClick()
        waitForIdle()
        // Nothing else fires this rule, so there is no longer a rule to save — which is the delete
        // button's job, not this dialog's.
        onNodeWithText("Save").assertIsNotEnabled()

        onNodeWithText("Wifi", substring = true).assertExists("the rest of the editor is still there")
        assertEquals(old, saved, "nothing is written until Save")
    }

    @Test
    fun removingTheOldConditionLeavesTheRestOfTheRuleAlone() = runComposeUiTest {
        val old = mixedRule().copy(statusChangeTrigger = StatusChange(requiredStatuses = persistentSetOf(TaskStatus.Done)))
        var saved: RecurrenceRule? = null

        setContent { Dialog(old) { saved = it } }
        waitForIdle()

        onNodeWithText("Remove").performClick()
        waitForIdle()
        onNodeWithText("Save").performClick()
        waitForIdle()

        assertNull(saved?.nearbyTrigger, "removed means removed")
        assertEquals(old.statusChangeTrigger, saved?.statusChangeTrigger)
    }

    @Test
    fun aRuleHeldUpOnlyByTheOldConditionStillShowsWhatItResetsTo() = runComposeUiTest {
        // It is a rule like any other: it fires, and what it does when it fires is editable. The
        // section used to appear only for the triggers the current build writes, leaving the reset
        // status and the end date of exactly these rules unreachable.
        setContent { Dialog(mixedRule()) {} }
        waitForIdle()

        onNodeWithText("Reset to status").assertExists()
    }

    private fun mixedRule() = RecurrenceRule(
        timeRecurrenceTrigger = null,
        statusChangeTrigger = null,
        resetToStatus = TaskStatus.Open,
        nearbyTrigger = RecurrenceTrigger.NearbyChange(
            signals = persistentSetOf(
                NearbySignal.Wifi("Office"),
                NearbySignal.Bluetooth("AA:BB:CC:DD:EE:FF", "Car audio"),
            ),
        ),
    )

    @Composable
    private fun Dialog(rule: RecurrenceRule, onSelected: (RecurrenceRule?) -> Unit) {
        val noTasks = flowOf(PagingData.empty<Task>()).collectAsLazyPagingItems()
        SingleRecurrenceRuleDialog(
            currentRule = rule,
            filteredTasks = noTasks,
            loadedTasks = emptyMap(),
            onFilterTasks = {},
            onLoadTask = {},
            onDismiss = {},
            onRecurrenceSelected = onSelected,
        )
    }
}
