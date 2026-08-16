package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A status-change trigger names a *kind* of status.
 *
 * The rule editor offers one chip per kind and records a bare instance to stand for it: Blocked
 * with no blockers, Declined with no reason. No real task is ever in either of those exact states —
 * a blocked task lists what it waits on, a declined one carries its reason — so matching on the
 * whole value made those two chips unfireable.
 */
@OptIn(ExperimentalTime::class)
class StatusChangeTriggerMatchTest {

    private val now = Instant.parse("2026-03-01T10:00:00Z")

    private fun ruleFor(vararg statuses: TaskStatus) = RecurrenceRule(
        timeRecurrenceTrigger = null,
        statusChangeTrigger = RecurrenceTrigger.StatusChange(persistentSetOf(*statuses)),
        resetToStatus = TaskStatus.Open,
    )

    private fun triggers(rule: RecurrenceRule, status: TaskStatus) =
        RecurrenceCalculator.shouldTrigger(
            rule = rule,
            event = RecurrenceTriggerEvent(currentStatus = status, currentTime = now),
            recurrenceState = RecurrenceState(),
        )

    @Test
    fun `a blocked trigger fires for a task blocked on something`() {
        // What the editor stores for the "Blocked" chip.
        val rule = ruleFor(TaskStatus.Blocked(persistentSetOf()))

        assertTrue(triggers(rule, TaskStatus.Blocked(persistentSetOf("TEST-1"), "waiting")))
    }

    @Test
    fun `a declined trigger fires whatever the reason given`() {
        val rule = ruleFor(TaskStatus.Declined(""))

        assertTrue(triggers(rule, TaskStatus.Declined("not this quarter")))
    }

    @Test
    fun `a trigger for one kind does not fire for another`() {
        val rule = ruleFor(TaskStatus.Done)

        assertFalse(triggers(rule, TaskStatus.InProgress))
        assertFalse(triggers(rule, TaskStatus.Blocked(persistentSetOf("TEST-1"), "waiting")))
    }
}
