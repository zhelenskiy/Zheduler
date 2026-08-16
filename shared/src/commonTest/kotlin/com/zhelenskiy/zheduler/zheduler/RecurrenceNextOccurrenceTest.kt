@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Whatever a rule stores as its next occurrence has to be in the future.
 *
 * A next occurrence in the past is not merely a wrong date: the gate in [RecurrenceCalculator
 * .shouldTrigger] only blocks while the current time is *before* it, so a past one stops gating
 * anything and the rule fires again on the very next event, spending its remaining occurrences.
 */
class RecurrenceNextOccurrenceTest {

    private val newYork = TimeZone.of("America/New_York")

    private fun ny(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
        LocalDateTime(year, month, day, hour, minute).toInstant(newYork)

    private fun rule(trigger: RecurrenceTrigger.TimeRecurrenceTrigger) = RecurrenceRule(
        timeRecurrenceTrigger = trigger,
        statusChangeTrigger = RecurrenceTrigger.StatusChange(persistentSetOf(TaskStatus.Done)),
        resetToStatus = TaskStatus.Open,
    )

    // ---- AfterTimeout ----

    @Test
    fun `the first fire of an AfterTimeout rule schedules the next one ahead`() {
        val firstOccurrence = ny(2026, 8, 1, 0)
        val rule = rule(
            RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod(days = 1),
                firstOccurrence = firstOccurrence,
                timezone = RecurrenceTimeZone.Specific("America/New_York"),
            )
        )
        val completedAt = ny(2026, 8, 1, 9)

        val (advanced, _) = assertNotNull(
            RecurrenceService.processRecurrence(
                persistentListOf(rule to RecurrenceState()),
                RecurrenceTriggerEvent(TaskStatus.Done, completedAt),
            )
        )

        val next = assertNotNull(advanced.single().second.nextOccurrenceDate)
        assertTrue(next > completedAt, "next occurrence $next is not after the fire at $completedAt")
        assertEquals(ny(2026, 8, 2, 9), next)
    }

    @Test
    fun `an AfterTimeout rule cannot fire twice for one period`() {
        val rule = rule(
            RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod(days = 1),
                firstOccurrence = ny(2026, 8, 1, 0),
                timezone = RecurrenceTimeZone.Specific("America/New_York"),
            )
        )

        val (afterFirst, _) = assertNotNull(
            RecurrenceService.processRecurrence(
                persistentListOf(rule to RecurrenceState()),
                RecurrenceTriggerEvent(TaskStatus.Done, ny(2026, 8, 1, 9)),
            )
        )

        // The user completes it again five minutes later; the daily rule is not due yet.
        val secondAttempt = RecurrenceService.processRecurrence(
            afterFirst,
            RecurrenceTriggerEvent(TaskStatus.Done, ny(2026, 8, 1, 9) + 5.minutes),
        )
        assertEquals(null, secondAttempt, "the rule is not due again until tomorrow")
    }

    @Test
    fun `an hourly period is an hour of real time across a fall-back`() {
        // 2026-11-01: America/New_York goes 02:00 EDT -> 01:00 EST, so 01:30 happens twice.
        val period = RecurrencePeriod(hours = 1)
        val before = ny(2026, 11, 1, 0, 30)

        assertEquals(
            before + 1.hours,
            period.addTo(before, newYork),
            "adding an hour to the wall clock instead skipped the repeated hour",
        )
    }

    @Test
    fun `a monthly period keeps the time of day`() {
        val period = RecurrencePeriod(months = 1)
        assertEquals(ny(2026, 9, 15, 9), period.addTo(ny(2026, 8, 15, 9), newYork))
    }

    @Test
    fun `a very large interval does not wrap into the past`() {
        val period = RecurrencePeriod(weeks = 306_783_379)
        val from = ny(2026, 8, 1, 0)
        assertTrue(
            period.addTo(from, newYork) > from,
            "weeks * 7 overflowed Int and landed millions of years before the start",
        )
    }

    // ---- Fixed points across a daylight-saving fall-back ----

    @Test
    fun `a weekly fixed point is never scheduled before the moment it is asked about`() {
        val rule = rule(
            RecurrenceTrigger.AtFixedPoints(
                pattern = FixedPointPattern.DaysOfWeek(
                    days = persistentSetOf(RecurrenceDayOfWeek.SUNDAY),
                    timeOfDay = TimeOfDay(1, 30),
                ),
                startFrom = ny(2026, 10, 1, 0),
                timezone = RecurrenceTimeZone.Specific("America/New_York"),
            )
        )

        // 01:15 in the *second* pass of the repeated hour, i.e. 01:15 EST.
        val duringRepeatedHour = ny(2026, 11, 1, 1, 30) + 45.minutes

        val next = assertNotNull(
            RecurrenceCalculator.calculateNextOccurrence(rule, RecurrenceState(), duringRepeatedHour)
        )
        assertTrue(
            next > duringRepeatedHour,
            "next occurrence $next is before the time it was asked about, $duringRepeatedHour",
        )
    }

    @Test
    fun `a monthly fixed point is never scheduled before the moment it is asked about`() {
        val rule = rule(
            RecurrenceTrigger.AtFixedPoints(
                pattern = FixedPointPattern.DayOfMonth(
                    dayOfMonth = 1,
                    timeOfDay = TimeOfDay(1, 30),
                ),
                startFrom = ny(2026, 10, 1, 0),
                timezone = RecurrenceTimeZone.Specific("America/New_York"),
            )
        )
        val duringRepeatedHour = ny(2026, 11, 1, 1, 30) + 45.minutes

        val next = assertNotNull(
            RecurrenceCalculator.calculateNextOccurrence(rule, RecurrenceState(), duringRepeatedHour)
        )
        assertTrue(next > duringRepeatedHour, "next occurrence $next is before $duringRepeatedHour")
    }

    @Test
    fun `an ordinary weekly rule still lands on the next matching day`() {
        val rule = rule(
            RecurrenceTrigger.AtFixedPoints(
                pattern = FixedPointPattern.DaysOfWeek(
                    days = persistentSetOf(RecurrenceDayOfWeek.WEDNESDAY),
                    timeOfDay = TimeOfDay(9, 0),
                ),
                startFrom = ny(2026, 8, 1, 0),
                timezone = RecurrenceTimeZone.Specific("America/New_York"),
            )
        )
        // Monday 2026-08-03; the next Wednesday is the 5th.
        val next = RecurrenceCalculator.calculateNextOccurrence(rule, RecurrenceState(), ny(2026, 8, 3, 12))
        assertEquals(ny(2026, 8, 5, 9), next)
    }

    @Test
    fun `a rule finished by its end date does not fire again`() {
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AtFixedPoints(
                pattern = FixedPointPattern.DaysOfWeek(
                    days = persistentSetOf(RecurrenceDayOfWeek.MONDAY),
                    timeOfDay = TimeOfDay(9, 0),
                ),
                startFrom = ny(2026, 8, 1, 0),
                timezone = RecurrenceTimeZone.Specific("America/New_York"),
            ),
            statusChangeTrigger = RecurrenceTrigger.StatusChange(persistentSetOf(TaskStatus.Done)),
            resetToStatus = TaskStatus.Open,
            // Wednesday, so the Monday after the first fire falls outside it.
            termination = RecurrenceTermination.onDate(ny(2026, 8, 19, 0)),
        )

        val (afterFirst, _) = assertNotNull(
            RecurrenceService.processRecurrence(
                persistentListOf(rule to RecurrenceState(nextOccurrenceDate = ny(2026, 8, 17, 9))),
                RecurrenceTriggerEvent(TaskStatus.Done, ny(2026, 8, 17, 9)),
            )
        )
        assertEquals(
            null,
            afterFirst.single().second.nextOccurrenceDate,
            "the next Monday is past the end date, so there is no next occurrence",
        )

        // With no next occurrence every date gate had nothing to compare against, so the finished
        // rule fired again on the next event, and the one after that, indefinitely.
        assertEquals(
            null,
            RecurrenceService.processRecurrence(
                afterFirst,
                RecurrenceTriggerEvent(TaskStatus.Done, ny(2026, 8, 26, 9)),
            ),
            "a rule with nothing left to schedule is finished",
        )
    }

    @Test
    fun `a yearly rule naming no months never occurs, rather than throwing`() {
        // Such a rule cannot be built in the dialog but can be stored or imported. A require in
        // the constructor would make the row undecodable and take the whole space down with it.
        val pattern = FixedPointPattern.YearlyOnDate(months = persistentSetOf(), dayOfMonth = 1)
        val rule = rule(
            RecurrenceTrigger.AtFixedPoints(
                pattern = pattern,
                startFrom = ny(2026, 8, 1, 0),
                timezone = RecurrenceTimeZone.Specific("America/New_York"),
            )
        )
        assertEquals(null, RecurrenceCalculator.calculateNextOccurrence(rule, RecurrenceState(), ny(2026, 8, 1, 0)))
    }

    @Test
    fun `a daily fixed point on the same day still fires later that day`() {
        val rule = rule(
            RecurrenceTrigger.AtFixedPoints(
                pattern = FixedPointPattern.DayOfMonth(
                    dayOfMonth = 15,
                    timeOfDay = TimeOfDay(17, 0),
                ),
                startFrom = ny(2026, 8, 1, 0),
                timezone = RecurrenceTimeZone.Specific("America/New_York"),
            )
        )
        val next = RecurrenceCalculator.calculateNextOccurrence(rule, RecurrenceState(), ny(2026, 8, 15, 9))
        assertEquals(ny(2026, 8, 15, 17), next, "the 15th at 17:00 is still ahead at 09:00")
    }

    @Test
    fun `a monthly fixed point rolls to next month once the day has passed`() {
        val rule = rule(
            RecurrenceTrigger.AtFixedPoints(
                pattern = FixedPointPattern.DayOfMonth(
                    dayOfMonth = 15,
                    timeOfDay = TimeOfDay(9, 0),
                ),
                startFrom = ny(2026, 8, 1, 0),
                timezone = RecurrenceTimeZone.Specific("America/New_York"),
            )
        )
        val next = RecurrenceCalculator.calculateNextOccurrence(rule, RecurrenceState(), ny(2026, 8, 20, 9))
        assertEquals(ny(2026, 9, 15, 9), next)
    }
}
