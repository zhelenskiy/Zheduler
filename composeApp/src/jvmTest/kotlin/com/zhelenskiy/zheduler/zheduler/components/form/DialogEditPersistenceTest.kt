package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.SavedStateHandle
import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.RecurrencePeriod
import com.zhelenskiy.zheduler.zheduler.RecurrenceRule
import com.zhelenskiy.zheduler.zheduler.RecurrenceState
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * What a dialog puts into the form is an edit like any other.
 *
 * Leaving the edit screen for a moment — to create a connected task, or because the process died —
 * disposes the form and rebuilds it from the task. Whatever the persistence does not carry is
 * rolled back on the way in, and a status, a connection or a recurrence rule the user had just set
 * went with it while the typed fields survived.
 */
@OptIn(ExperimentalTime::class, ExperimentalTestApi::class)
class DialogEditPersistenceTest {

    private val connection = TaskConnection("TEST-7", ConnectionType.DependsOn)
    private val rule = RecurrenceRule(
        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod(days = 3),
            firstOccurrence = Instant.parse("2026-04-01T09:00:00Z"),
        ),
        statusChangeTrigger = null,
        resetToStatus = TaskStatus.Open,
    )

    private fun emptyForm() = TaskFormState(
        initialTitle = "",
        initialDescription = "",
        initialPriority = "",
        initialEstimatedTime = "",
        initialTags = persistentSetOf(),
        initialDueDate = null,
        initialStatus = TaskStatus.Open,
        initialConnections = persistentSetOf(),
        initialNotifications = persistentListOf(),
        initialRecurrenceRules = persistentListOf(),
        initialAutoUpdateStatusFromSubtasks = false,
    )

    /**
     * Edits [edit] into a form, then answers with a fresh form restored from what was written.
     *
     * One field per test on purpose. The write effect only re-runs when one of its keys changes,
     * so a test that moves four fields at once passes even if three of those keys are missing —
     * the fourth triggers the write that happens to carry them all.
     */
    private fun ComposeUiTest.restoredAfter(edit: TaskFormState.() -> Unit): TaskFormState {
        val handle = SavedStateHandle()
        val edited = emptyForm()
        setContent { edited.persistedIn(FormStatePersistence(handle)) }
        waitForIdle()

        edited.edit()
        waitForIdle()

        return emptyForm().also { restored ->
            assertNotNull(FormStatePersistence(handle).read(), "nothing was written").applyTo(restored)
        }
    }

    @Test
    fun aStatusSetInItsDialogSurvivesTheFormBeingRebuilt() {
        val blocked = TaskStatus.Blocked(persistentSetOf("TEST-1"), "waiting")
        runComposeUiTest {
            assertEquals(blocked, restoredAfter { status = blocked }.status)
        }
    }

    @Test
    fun aConnectionAddedInItsDialogSurvivesTheFormBeingRebuilt() = runComposeUiTest {
        assertEquals(
            persistentSetOf(connection),
            restoredAfter { connections = persistentSetOf(connection) }.connections,
        )
    }

    @Test
    fun theAutoUpdateFlagSurvivesTheFormBeingRebuilt() = runComposeUiTest {
        assertEquals(true, restoredAfter { autoUpdateStatusFromSubtasks = true }.autoUpdateStatusFromSubtasks)
    }

    @Test
    fun aRecurrenceRuleSurvivesTheFormBeingRebuilt() = runComposeUiTest {
        assertEquals(
            persistentListOf(rule to RecurrenceState()),
            restoredAfter { setRecurrenceRule(0, rule to RecurrenceState()) }.recurrenceRules,
        )
    }

    @Test
    fun theRecordRemembersTheConnectionsTheFormWasBuiltWith() = runComposeUiTest {
        val handle = SavedStateHandle()
        val existing = TaskConnection("TEST-9", ConnectionType.RelatesTo)
        val form = TaskFormState(
            initialTitle = "",
            initialDescription = "",
            initialPriority = "",
            initialEstimatedTime = "",
            initialTags = persistentSetOf(),
            initialDueDate = null,
            initialStatus = TaskStatus.Open,
            initialConnections = persistentSetOf(existing),
            initialNotifications = persistentListOf(),
            initialRecurrenceRules = persistentListOf(),
            initialAutoUpdateStatusFromSubtasks = false,
        )
        setContent { form.persistedIn(FormStatePersistence(handle)) }
        waitForIdle()

        form.connections = persistentSetOf(existing, connection)
        waitForIdle()

        // The mark the edit screen measures the database's changes against has to be where the
        // form started, not where it has got to: taken from the current set, a connection made
        // while the screen was away reads as no change, and saving the form deletes it.
        val record = assertNotNull(FormStatePersistence(handle).read(), "nothing was written")
        assertEquals(persistentSetOf(existing), record.connectionsBase)
        assertEquals(persistentSetOf(existing, connection), record.connections)
    }

    @Test
    fun aNotificationTypedInSurvivesTheFormBeingRebuilt() = runComposeUiTest {
        val handle = SavedStateHandle()
        val edited = emptyForm()
        setContent { edited.persistedIn(FormStatePersistence(handle)) }
        waitForIdle()

        edited.addNotification()
        edited.updateNotification(0, "2h")
        waitForIdle()

        val restored = emptyForm()
        FormStatePersistence(handle).read()!!.applyTo(restored)

        assertEquals(persistentListOf("2h"), restored.notifications)
        assertEquals(1, restored.notificationIds.size, "a row needs an identity to be drawn with")
    }
}
