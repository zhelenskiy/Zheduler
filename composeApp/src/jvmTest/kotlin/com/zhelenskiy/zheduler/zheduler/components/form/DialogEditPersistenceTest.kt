package com.zhelenskiy.zheduler.zheduler.components.form

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

    @Test
    fun aStatusSetInItsDialogSurvivesTheFormBeingRebuilt() = runComposeUiTest {
        val handle = SavedStateHandle()
        val edited = emptyForm()
        setContent { edited.persistedIn(FormStatePersistence(handle)) }
        waitForIdle()

        edited.status = TaskStatus.Blocked(persistentSetOf("TEST-1"), "waiting")
        edited.connections = persistentSetOf(connection)
        edited.autoUpdateStatusFromSubtasks = true
        edited.setRecurrenceRule(0, rule to RecurrenceState())
        waitForIdle()

        val restored = emptyForm()
        FormStatePersistence(handle).read()!!.applyTo(restored)

        assertEquals(TaskStatus.Blocked(persistentSetOf("TEST-1"), "waiting"), restored.status)
        assertEquals(persistentSetOf(connection), restored.connections)
        assertEquals(true, restored.autoUpdateStatusFromSubtasks)
        assertEquals(persistentListOf(rule to RecurrenceState()), restored.recurrenceRules)
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
