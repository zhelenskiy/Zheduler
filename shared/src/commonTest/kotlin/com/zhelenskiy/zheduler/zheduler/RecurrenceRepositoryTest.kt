@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

import com.zhelenskiy.zheduler.zheduler.TaskStatus.Done
import com.zhelenskiy.zheduler.zheduler.TaskStatus.Open
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class InMemoryRecurrenceRepositoryTest: RecurrenceRepositoryTest(), InMemoryRepositoryTest
class DatabaseRecurrenceRepositoryTest: RecurrenceRepositoryTest(), DatabaseRepositoryTest

abstract class RecurrenceRepositoryTest: AbstractRepositoryTest {

    private fun instant(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant {
        val localDateTime = LocalDateTime(year, month, day, hour, minute)
        return localDateTime.toInstant(TimeZone.UTC)
    }

    // ==================== RecurrencePeriod Tests ====================

    @Test
    fun testRecurrencePeriodAddDays() {
        val period = RecurrencePeriod.ofDays(5)
        val start = LocalDateTime(2024, 1, 15, 10, 30)
        val result = period.addTo(start)
        assertEquals(LocalDateTime(2024, 1, 20, 10, 30), result)
    }

    @Test
    fun testRecurrencePeriodAddWeeks() {
        val period = RecurrencePeriod.ofWeeks(2)
        val start = LocalDateTime(2024, 1, 15, 10, 30)
        val result = period.addTo(start)
        assertEquals(LocalDateTime(2024, 1, 29, 10, 30), result)
    }

    @Test
    fun testRecurrencePeriodAddMonths() {
        val period = RecurrencePeriod.ofMonths(3)
        val start = LocalDateTime(2024, 1, 15, 10, 30)
        val result = period.addTo(start)
        assertEquals(LocalDateTime(2024, 4, 15, 10, 30), result)
    }

    @Test
    fun testRecurrencePeriodAddYears() {
        val period = RecurrencePeriod.ofYears(1)
        val start = LocalDateTime(2024, 1, 15, 10, 30)
        val result = period.addTo(start)
        assertEquals(LocalDateTime(2025, 1, 15, 10, 30), result)
    }

    @Test
    fun testRecurrencePeriodComplex() {
        val period = RecurrencePeriod(months = 1, weeks = 2, days = 3, hours = 4)
        val start = LocalDateTime(2024, 1, 1, 0, 0)
        val result = period.addTo(start)
        // 1 month + 2 weeks + 3 days + 4 hours = Feb 1 + 14 days + 3 days + 4 hours = Feb 18, 4:00
        assertEquals(LocalDateTime(2024, 2, 18, 4, 0), result)
    }

    // ==================== AfterTimeout Recurrence Tests ====================

    private fun RecurrenceTrigger.TimeRecurrenceTrigger.toRule(): RecurrenceRule =
        RecurrenceRule(timeRecurrenceTrigger = this, statusChangeTrigger = null, resetToStatus = Open)

    @Test
    fun testEveryPeriodFirstOccurrence() {
        val firstOccurrence = instant(2024, 1, 15, 9, 0)
        val rule = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod.ofWeeks(1),
            firstOccurrence = firstOccurrence
        ).toRule()
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state)
        assertEquals(firstOccurrence, next)
    }

    @Test
    fun testEveryPeriodSubsequentOccurrences() {
        val firstOccurrence = instant(2024, 1, 15, 9, 0)
        val rule = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod.ofWeeks(1),
            firstOccurrence = firstOccurrence
        ).toRule()
        val state = RecurrenceState(
            occurrenceCount = 1,
            lastOccurrenceDate = firstOccurrence
        )

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state)
        assertEquals(instant(2024, 1, 22, 9, 0), next)
    }

    @Test
    fun testEveryPeriodTerminationAfterOccurrences() {
        val firstOccurrence = instant(2024, 1, 15, 9, 0)
        val rule = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod.ofDays(1),
            firstOccurrence = firstOccurrence,
        ).toRule().copy(termination = RecurrenceTermination.afterOccurrences(3))
        val state = RecurrenceState(occurrenceCount = 3)

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state)
        assertNull(next)
    }

    @Test
    fun testEveryPeriodTerminationOnDate() {
        val firstOccurrence = instant(2024, 1, 15, 9, 0)
        val endDate = instant(2024, 1, 20, 0, 0)
        val rule = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod.ofDays(1),
            firstOccurrence = firstOccurrence,
        ).toRule().copy(termination = RecurrenceTermination.onDate(endDate))
        val state = RecurrenceState(occurrenceCount = 1, lastOccurrenceDate = firstOccurrence)

        // Trigger time is after end date
        val next = RecurrenceCalculator.calculateNextOccurrence(
            rule, state,
            triggerTime = instant(2024, 1, 21, 0, 0)
        )
        assertNull(next)
    }

    // ==================== AtFixedPoints - DaysOfWeek Tests ====================

    @Test
    fun testDaysOfWeekFindNextSameDay() {
        val startFrom = instant(2024, 1, 15, 0, 0) // Monday at midnight
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.DaysOfWeek(
                days = persistentSetOf(RecurrenceDayOfWeek.MONDAY),
                timeOfDay = TimeOfDay(10, 0)
            ),
            startFrom = startFrom,
            timezone = RecurrenceTimeZone.Specific("UTC")  // Use explicit UTC timezone
        ).toRule()
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)
        assertEquals(DayOfWeek.MONDAY, nextDateTime.dayOfWeek)
        assertEquals(10, nextDateTime.hour)
        // Should return the same Monday (Jan 15) at 10:00 since we're before that time
        assertEquals(15, nextDateTime.date.day)
    }

    @Test
    fun testDaysOfWeekMultipleDays() {
        val startFrom = instant(2024, 1, 15, 12, 0) // Monday noon
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.DaysOfWeek(
                days = persistentSetOf(RecurrenceDayOfWeek.TUESDAY, RecurrenceDayOfWeek.THURSDAY),
                timeOfDay = TimeOfDay(9, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)
        assertEquals(DayOfWeek.TUESDAY, nextDateTime.dayOfWeek)
        assertEquals(16, nextDateTime.date.day) // Jan 16, 2024
    }

    // ==================== AtFixedPoints - DayOfMonth Tests ====================

    @Test
    fun testDayOfMonthSameMonth() {
        val startFrom = instant(2024, 1, 10, 0, 0)
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.DayOfMonth(
                dayOfMonth = 15,
                timeOfDay = TimeOfDay(9, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)
        assertEquals(15, nextDateTime.date.day)
        assertEquals(1, nextDateTime.month.number)
    }

    @Test
    fun testDayOfMonthNextMonth() {
        val startFrom = instant(2024, 1, 20, 0, 0)
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.DayOfMonth(
                dayOfMonth = 15,
                timeOfDay = TimeOfDay(9, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)
        assertEquals(15, nextDateTime.date.day)
        assertEquals(2, nextDateTime.month.number) // February
    }

    @Test
    fun testDayOfMonthFebruaryClamp() {
        // Feb doesn't have 31 days, should clamp to last day
        val startFrom = instant(2024, 1, 31, 12, 0)
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.DayOfMonth(
                dayOfMonth = 31,
                timeOfDay = TimeOfDay(9, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState(lastOccurrenceDate = startFrom)

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)
        assertEquals(2, nextDateTime.month.number) // February
        assertEquals(29, nextDateTime.date.day) // 2024 is leap year
    }

    // ==================== AtFixedPoints - NthDayOfWeekInMonth Tests ====================

    @Test
    fun testFirstMondayOfMonth() {
        val startFrom = instant(2024, 1, 1, 0, 0)
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.NthDayOfWeekInMonth(
                ordinal = WeekOrdinal.FIRST,
                dayOfWeek = RecurrenceDayOfWeek.MONDAY,
                timeOfDay = TimeOfDay(9, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)
        assertEquals(DayOfWeek.MONDAY, nextDateTime.dayOfWeek)
        // First Monday of January 2024 is January 1st
        assertEquals(1, nextDateTime.date.day)
    }

    @Test
    fun testLastFridayOfMonth() {
        val startFrom = instant(2024, 1, 1, 0, 0)
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.NthDayOfWeekInMonth(
                ordinal = WeekOrdinal.LAST,
                dayOfWeek = RecurrenceDayOfWeek.FRIDAY,
                timeOfDay = TimeOfDay(17, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)
        assertEquals(DayOfWeek.FRIDAY, nextDateTime.dayOfWeek)
        // Last Friday of January 2024 is January 26th
        assertEquals(26, nextDateTime.date.day)
    }

    // ==================== AtFixedPoints - YearlyOnDate Tests ====================

    @Test
    fun testYearlyOnDateSameYear() {
        val startFrom = instant(2024, 1, 1, 0, 0)
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.YearlyOnDate(
                months = persistentSetOf(RecurrenceMonth.MARCH),
                dayOfMonth = 15,
                timeOfDay = TimeOfDay(12, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)
        assertEquals(Month.MARCH, nextDateTime.month)
        assertEquals(15, nextDateTime.date.day)
        assertEquals(2024, nextDateTime.year)
    }

    @Test
    fun testYearlyOnDateNextYear() {
        val startFrom = instant(2024, 6, 1, 0, 0)
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.YearlyOnDate(
                months = persistentSetOf(RecurrenceMonth.MARCH),
                dayOfMonth = 15,
                timeOfDay = TimeOfDay(12, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)
        assertEquals(Month.MARCH, nextDateTime.month)
        assertEquals(15, nextDateTime.date.day)
        assertEquals(2025, nextDateTime.year)
    }

    // ==================== RecurrenceService Tests ====================

    @Test
    fun testProcessRecurrenceTermination() {
        val firstOccurrence = instant(2024, 1, 15, 9, 0)
        val rule = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod.ofDays(1),
            firstOccurrence = firstOccurrence,
        ).toRule().copy(termination = RecurrenceTermination.afterOccurrences(2))
        
        val currentState = RecurrenceState(
            occurrenceCount = 2,
            lastOccurrenceDate = instant(2024, 1, 16, 9, 0)
        )
        
        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(rule to currentState),
            triggerEvent = RecurrenceTriggerEvent(Open, instant(2024, 1, 17, 9, 0)),
        )

        assertNull(result)
    }

    // ==================== Display String Tests ====================

    @Test
    fun testDisplayStringEveryPeriod() {
        val rule = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod.ofWeeks(2),
            firstOccurrence = instant(2024, 1, 1, 0, 0)
        ).toRule()
        assertEquals("Every 2w", rule.toBriefString())
    }

    @Test
    fun testDisplayStringDaysOfWeek() {
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.DaysOfWeek(
                days = persistentSetOf(RecurrenceDayOfWeek.TUESDAY, RecurrenceDayOfWeek.THURSDAY)
            ),
            startFrom = instant(2024, 1, 1, 0, 0)
        ).toRule()
        assertTrue(rule.toBriefString().contains("TUE"))
        assertTrue(rule.toBriefString().contains("THU"))
    }

    // ==================== Repository Integration Tests ====================

    @Test
    fun `processRecurrenceTrigger does not change dueDate but resets status`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val originalDueDate = instant(2024, 1, 15, 9, 0)
        val task = repo.addTask(
            spaceId = spaceId,
            title = "Recurring task",
            status = Done,
            dueDate = originalDueDate,
            recurrenceRules = persistentListOf(RecurrenceRule(
                timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                    period = RecurrencePeriod.ofWeeks(1),
                    firstOccurrence = originalDueDate,
                ),
                statusChangeTrigger = RecurrenceTrigger.StatusChange(requiredStatuses = persistentSetOf(Done)),
                resetToStatus = Open,
            ).to(RecurrenceState()))
        )!!

        val updated = repo.processRecurrenceTrigger(
            task.id,
            RecurrenceTriggerEvent(Done, instant(2024, 1, 17, 9, 0))
        )

        assertNotNull(updated)
        assertEquals(originalDueDate, updated.dueDate)
        assertEquals(Open, updated.status)
    }

    @Test
    fun `processRecurrenceTrigger does not change dueDate but resets status when recurrence ends`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val originalDueDate = instant(2024, 1, 15, 9, 0)
        val rules = RecurrenceRule(
            RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofWeeks(1),
                firstOccurrence = originalDueDate,
            ),
            statusChangeTrigger = RecurrenceTrigger.StatusChange(requiredStatuses = persistentSetOf(Done)),
            resetToStatus = Open,
            termination = RecurrenceTermination.afterOccurrences(1)
        ).to(RecurrenceState())
        var task = repo.addTask(
            spaceId = spaceId,
            title = "Recurring task",
            status = Done,
            dueDate = originalDueDate,
            recurrenceRules = persistentListOf(rules)
        )!!
        // Manually set occurrence count to simulate that the task has already occurred once
        task = repo.updateTask(task.copy(recurrenceRules = rules.let { persistentListOf(it) }))!!

        val updated = repo.processRecurrenceTrigger(
            task.id,
            RecurrenceTriggerEvent(Done, originalDueDate)
        )

        assertNotNull(updated)
        assertEquals(originalDueDate, updated.dueDate)
        assertEquals(Open, updated.status) // Status resets even on last occurrence
        // Exhausted rules are kept with count 0, not removed
        assertEquals(1, updated.recurrenceRules.size)
        assertEquals(0, updated.recurrenceRules[0].first.termination.afterOccurrences?.count)
    }

    // ==================== Task Integration Tests ====================

    @Test
    fun testTaskIsRecurring() {
        val nonRecurringTask = Task(
            id = "TASK-1",
            title = "Non-recurring",
            spaceId = "space-1"
        )
        assertFalse(nonRecurringTask.isRecurring)

        val recurringTask = Task(
            id = "TASK-2",
            title = "Recurring",
            spaceId = "space-1",
            recurrenceRules = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ).toRule().to(RecurrenceState()).let { persistentListOf(it) }
        )
        assertTrue(recurringTask.isRecurring)
    }
}
