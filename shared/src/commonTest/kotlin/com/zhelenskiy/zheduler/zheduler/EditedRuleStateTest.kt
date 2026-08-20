@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * What the rule editor keeps when a rule is changed.
 *
 * A rule that has already come round records how far it has got, and a rule with a single
 * occurrence records that it is finished by having no next one. Carrying that across a change of
 * date is how re-dating a spent rule produced something that looked active in the editor and could
 * never fire again.
 */
class EditedRuleStateTest {

    private fun at(day: Int, hour: Int = 9): Instant =
        LocalDateTime(2026, 6, day, hour, 0).toInstant(TimeZone.UTC)

    private fun oneShot(on: Instant) = RecurrenceRule(
        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
            period = null,
            firstOccurrence = on,
            timezone = RecurrenceTimeZone.SystemDefault,
        ),
        statusChangeTrigger = null,
        resetToStatus = TaskStatus.Open,
    )

    @Test
    fun `re-dating a rule that has already been used starts it again`() {
        val spent = oneShot(at(1)) to RecurrenceState(
            occurrenceCount = 1,
            lastOccurrenceDate = at(1),
            nextOccurrenceDate = null,
        )

        val (rule, state) = RecurrenceService.stateForEditedRule(spent, oneShot(at(30)))

        assertEquals(at(30), state.nextOccurrenceDate, "the new date is when it happens")
        assertEquals(0, state.occurrenceCount, "and it has not happened yet")
        assertNotNull(rule.timeRecurrenceTrigger)
    }

    @Test
    fun `changing anything but the timing leaves the progress alone`() {
        val rule = oneShot(at(1))
        val used = rule to RecurrenceState(
            occurrenceCount = 2,
            lastOccurrenceDate = at(1),
            nextOccurrenceDate = null,
        )

        val (_, state) = RecurrenceService.stateForEditedRule(
            used,
            rule.copy(resetToStatus = TaskStatus.InProgress),
        )

        assertEquals(2, state.occurrenceCount, "a rule that has come round twice still has")
        assertNull(state.nextOccurrenceDate, "and a spent one-shot is still spent")
    }

    @Test
    fun `editing something else leaves a rule that is already waiting for its moment armed`() {
        // A weekly rule that also names a status keeps an occurrence in the past while it waits
        // for that status. Working its date out again from today would push it to next week.
        val everyMonday = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AtFixedPoints(
                pattern = FixedPointPattern.DaysOfWeek(
                    days = persistentSetOf(RecurrenceDayOfWeek.MONDAY),
                    timeOfDay = TimeOfDay(9, 0),
                ),
                startFrom = at(1),
                timezone = RecurrenceTimeZone.SystemDefault,
            ),
            statusChangeTrigger = RecurrenceTrigger.StatusChange(persistentSetOf(TaskStatus.Done)),
            resetToStatus = TaskStatus.Open,
        )
        val armed = everyMonday to RecurrenceState(nextOccurrenceDate = at(1))

        val (_, state) = RecurrenceService.stateForEditedRule(
            armed,
            everyMonday.copy(termination = RecurrenceTermination.afterOccurrences(4)),
        )

        assertEquals(at(1), state.nextOccurrenceDate, "still waiting for the moment it was waiting for")
    }

    @Test
    fun `a rule with no history is simply scheduled`() {
        val (_, state) = RecurrenceService.stateForEditedRule(previous = null, edited = oneShot(at(30)))

        assertEquals(at(30), state.nextOccurrenceDate)
        assertEquals(0, state.occurrenceCount)
    }
}
