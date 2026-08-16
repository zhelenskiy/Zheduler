@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import com.zhelenskiy.zheduler.zheduler.RecurrencePeriod
import com.zhelenskiy.zheduler.zheduler.RecurrenceRule
import com.zhelenskiy.zheduler.zheduler.RecurrenceState
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The notification and recurrence lists are keyed by these ids. An id has to stay with its entry
 * as neighbours come and go — keyed by position instead, deleting from the middle animates as
 * though the last row went.
 */
class TaskFormEntryIdsTest {

    private fun state() = TaskFormState(
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

    private fun rule(days: Int) = RecurrenceRule(
        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod.ofDays(days),
            firstOccurrence = Instant.fromEpochMilliseconds(0),
        ),
        statusChangeTrigger = null,
        resetToStatus = TaskStatus.Open,
    ) to RecurrenceState()

    @Test
    fun `a notification keeps its id when an earlier one is removed`() {
        val form = state()
        repeat(3) { form.addNotification() }
        form.updateNotification(0, "1d")
        form.updateNotification(1, "2d")
        form.updateNotification(2, "3d")
        val idOfThird = form.notificationIds[2]

        form.removeNotification(0)

        assertEquals(listOf("2d", "3d"), form.notifications)
        assertEquals(idOfThird, form.notificationIds[1], "the surviving entry should keep its identity")
        assertEquals(form.notifications.size, form.notificationIds.size)
    }

    @Test
    fun `identical notifications are still told apart`() {
        val form = state()
        repeat(2) { form.addNotification() }
        form.updateNotification(0, "1d")
        form.updateNotification(1, "1d")

        assertTrue(form.notificationIds[0] != form.notificationIds[1], "duplicates need distinct ids")
    }

    @Test
    fun `a recurrence rule keeps its id when an earlier one is removed`() {
        val form = state()
        form.setRecurrenceRule(0, rule(1))
        form.setRecurrenceRule(1, rule(2))
        val idOfSecond = form.recurrenceRuleIds[1]

        form.removeRecurrenceRule(0)

        assertEquals(1, form.recurrenceRules.size)
        assertEquals(idOfSecond, form.recurrenceRuleIds.single())
    }

    @Test
    fun `replacing a rule in place leaves its id alone`() {
        val form = state()
        form.setRecurrenceRule(0, rule(1))
        val id = form.recurrenceRuleIds.single()

        form.setRecurrenceRule(0, rule(5))

        assertEquals(1, form.recurrenceRules.size)
        assertEquals(id, form.recurrenceRuleIds.single(), "editing an entry is not replacing it")
    }
}
