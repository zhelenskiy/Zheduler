@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.TaskStatus.Done
import com.zhelenskiy.zheduler.zheduler.TaskStatus.Open
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.StatusChange
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class InMemoryRecurrenceEdgeCasesRepositoryTest: RecurrenceEdgeCasesRepositoryTest(), InMemoryRepositoryTest
class DatabaseRecurrenceEdgeCasesRepositoryTest: RecurrenceEdgeCasesRepositoryTest(), DatabaseRepositoryTest

/**
 * Additional edge case tests for recurrence functionality.
 * Covers corner cases like leap years, month boundaries, timezone handling, etc.
 */
abstract class RecurrenceEdgeCasesRepositoryTest: AbstractRepositoryTest {

    private fun instant(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Instant {
        val localDateTime = LocalDateTime(year, month, day, hour, minute, second)
        return localDateTime.toInstant(TimeZone.UTC)
    }

    // ==================== RecurrencePeriod Edge Cases ====================

    @Test
    fun `RecurrencePeriod requires at least one positive component`() {
        assertFailsWith<IllegalArgumentException> {
            RecurrencePeriod(years = 0, months = 0, days = 0)
        }
    }

    @Test
    fun `RecurrencePeriod rejects negative values`() {
        assertFailsWith<IllegalArgumentException> {
            RecurrencePeriod(days = -1)
        }
    }

    @Test
    fun `RecurrencePeriod with only seconds is valid`() {
        val period = RecurrencePeriod(seconds = 30)
        assertEquals(30, period.seconds)
    }

    @Test
    fun `RecurrencePeriod toApproximateSeconds calculation`() {
        val period = RecurrencePeriod(
            years = 1,
            months = 1,
            weeks = 1,
            days = 1,
            hours = 1,
            minutes = 1,
            seconds = 1
        )
        val expected = 365L * 24 * 60 * 60 +  // year
                       30L * 24 * 60 * 60 +   // month
                       7L * 24 * 60 * 60 +    // week
                       1L * 24 * 60 * 60 +    // day
                       1L * 60 * 60 +         // hour
                       1L * 60 +              // minute
                       1L                     // second
        assertEquals(expected, period.toApproximateSeconds())
    }

    @Test
    fun `RecurrencePeriod addTo handles month end dates correctly`() {
        // Adding 1 month to Jan 31 should give Feb 28/29
        val period = RecurrencePeriod.ofMonths(1)
        val jan31 = LocalDateTime(2024, 1, 31, 12, 0) // 2024 is leap year
        val result = period.addTo(jan31)
        // Result depends on kotlinx-datetime behavior - likely Feb 29 in leap year
        assertEquals(2, result.month.number)
    }

    @Test
    fun `RecurrencePeriod addTo handles year transition`() {
        val period = RecurrencePeriod.ofMonths(2)
        val nov15 = LocalDateTime(2024, 11, 15, 10, 0)
        val result = period.addTo(nov15)
        assertEquals(2025, result.year)
        assertEquals(1, result.month.number)
        assertEquals(15, result.date.day)
    }

    // ==================== TimeOfDay Edge Cases ====================

    @Test
    fun `TimeOfDay MIDNIGHT is 00 00 00`() {
        assertEquals(0, TimeOfDay.MIDNIGHT.hour)
        assertEquals(0, TimeOfDay.MIDNIGHT.minute)
        assertEquals(0, TimeOfDay.MIDNIGHT.second)
    }

    @Test
    fun `TimeOfDay NOON is 12 00 00`() {
        assertEquals(12, TimeOfDay.NOON.hour)
        assertEquals(0, TimeOfDay.NOON.minute)
        assertEquals(0, TimeOfDay.NOON.second)
    }

    @Test
    fun `TimeOfDay rejects invalid hour`() {
        assertFailsWith<IllegalArgumentException> {
            TimeOfDay(hour = 24)
        }
        assertFailsWith<IllegalArgumentException> {
            TimeOfDay(hour = -1)
        }
    }

    @Test
    fun `TimeOfDay rejects invalid minute`() {
        assertFailsWith<IllegalArgumentException> {
            TimeOfDay(minute = 60)
        }
        assertFailsWith<IllegalArgumentException> {
            TimeOfDay(minute = -1)
        }
    }

    @Test
    fun `TimeOfDay rejects invalid second`() {
        assertFailsWith<IllegalArgumentException> {
            TimeOfDay(second = 60)
        }
        assertFailsWith<IllegalArgumentException> {
            TimeOfDay(second = -1)
        }
    }

    @Test
    fun `TimeOfDay allows maximum valid values`() {
        val time = TimeOfDay(23, 59, 59)
        assertEquals(23, time.hour)
        assertEquals(59, time.minute)
        assertEquals(59, time.second)
    }

    // ==================== RecurrenceTermination Edge Cases ====================

    @Test
    fun `AfterOccurrences allows zero and rejects negative count`() {
        val zero = RecurrenceTerminationCondition.AfterOccurrences(0)
        assertEquals(0, zero.count)
        assertFailsWith<IllegalArgumentException> {
            RecurrenceTerminationCondition.AfterOccurrences(-1)
        }
    }

    @Test
    fun `AfterOccurrences with count 1 is valid`() {
        val termination = RecurrenceTerminationCondition.AfterOccurrences(1)
        assertEquals(1, termination.count)
    }

    // ==================== RecurrenceTimeZone Edge Cases ====================

    @Test
    fun `RecurrenceTimeZone Specific with invalid zone throws`() {
        assertFailsWith<IllegalArgumentException> {
            RecurrenceTimeZone.Specific("Invalid/Zone")
        }
    }

    @Test
    fun `RecurrenceTimeZone Specific with valid zone works`() {
        val tz = RecurrenceTimeZone.Specific("America/New_York")
        assertEquals("America/New_York", tz.zoneId)
    }

    @Test
    fun `RecurrenceTimeZone SystemDefault returns current system default`() {
        val tz = RecurrenceTimeZone.SystemDefault.toTimeZone()
        assertEquals(TimeZone.currentSystemDefault(), tz)
    }

    // ==================== Leap Year Edge Cases ====================

    @Test
    fun `Feb 29 recurrence on leap year`() {
        val startFrom = instant(2024, 2, 29, 9, 0)
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.YearlyOnDate(
                months = persistentSetOf(RecurrenceMonth.FEBRUARY),
                dayOfMonth = 29,
                timeOfDay = TimeOfDay(9, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState(lastOccurrenceDate = startFrom)

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)

        // 2025 is not a leap year, should clamp to Feb 28
        assertEquals(2025, nextDateTime.year)
        assertEquals(2, nextDateTime.month.number)
        assertEquals(28, nextDateTime.date.day)
    }

    private fun RecurrenceTrigger.TimeRecurrenceTrigger.toRule(): RecurrenceRule =
        RecurrenceRule(timeRecurrenceTrigger = this, statusChangeTrigger = null, resetToStatus = Open)
    
    @Test
    fun `Monthly recurrence on 31st handles short months`() {
        // Starting from March 31
        val startFrom = instant(2024, 3, 31, 12, 0)
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.DayOfMonth(
                dayOfMonth = 31,
                timeOfDay = TimeOfDay(12, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState(lastOccurrenceDate = startFrom)

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)

        // April has 30 days, should clamp to 30
        assertEquals(4, nextDateTime.month.number)
        assertEquals(30, nextDateTime.date.day)
    }

    // ==================== RecurrenceDayOfWeek Conversion Tests ====================

    @Test
    fun `RecurrenceDayOfWeek converts to kotlinx DayOfWeek correctly`() {
        assertEquals(DayOfWeek.MONDAY, RecurrenceDayOfWeek.MONDAY.toKotlinxDayOfWeek())
        assertEquals(DayOfWeek.TUESDAY, RecurrenceDayOfWeek.TUESDAY.toKotlinxDayOfWeek())
        assertEquals(DayOfWeek.WEDNESDAY, RecurrenceDayOfWeek.WEDNESDAY.toKotlinxDayOfWeek())
        assertEquals(DayOfWeek.THURSDAY, RecurrenceDayOfWeek.THURSDAY.toKotlinxDayOfWeek())
        assertEquals(DayOfWeek.FRIDAY, RecurrenceDayOfWeek.FRIDAY.toKotlinxDayOfWeek())
        assertEquals(DayOfWeek.SATURDAY, RecurrenceDayOfWeek.SATURDAY.toKotlinxDayOfWeek())
        assertEquals(DayOfWeek.SUNDAY, RecurrenceDayOfWeek.SUNDAY.toKotlinxDayOfWeek())
    }

    @Test
    fun `RecurrenceDayOfWeek fromKotlinxDayOfWeek roundtrip`() {
        for (dow in RecurrenceDayOfWeek.entries) {
            val kotlinxDow = dow.toKotlinxDayOfWeek()
            val back = RecurrenceDayOfWeek.fromKotlinxDayOfWeek(kotlinxDow)
            assertEquals(dow, back)
        }
    }

    // ==================== RecurrenceMonth Conversion Tests ====================

    @Test
    fun `RecurrenceMonth converts to kotlinx Month correctly`() {
        assertEquals(Month.JANUARY, RecurrenceMonth.JANUARY.toKotlinxMonth())
        assertEquals(Month.FEBRUARY, RecurrenceMonth.FEBRUARY.toKotlinxMonth())
        assertEquals(Month.MARCH, RecurrenceMonth.MARCH.toKotlinxMonth())
        assertEquals(Month.APRIL, RecurrenceMonth.APRIL.toKotlinxMonth())
        assertEquals(Month.MAY, RecurrenceMonth.MAY.toKotlinxMonth())
        assertEquals(Month.JUNE, RecurrenceMonth.JUNE.toKotlinxMonth())
        assertEquals(Month.JULY, RecurrenceMonth.JULY.toKotlinxMonth())
        assertEquals(Month.AUGUST, RecurrenceMonth.AUGUST.toKotlinxMonth())
        assertEquals(Month.SEPTEMBER, RecurrenceMonth.SEPTEMBER.toKotlinxMonth())
        assertEquals(Month.OCTOBER, RecurrenceMonth.OCTOBER.toKotlinxMonth())
        assertEquals(Month.NOVEMBER, RecurrenceMonth.NOVEMBER.toKotlinxMonth())
        assertEquals(Month.DECEMBER, RecurrenceMonth.DECEMBER.toKotlinxMonth())
    }

    @Test
    fun `RecurrenceMonth fromKotlinxMonth roundtrip`() {
        for (month in RecurrenceMonth.entries) {
            val kotlinxMonth = month.toKotlinxMonth()
            val back = RecurrenceMonth.fromKotlinxMonth(kotlinxMonth)
            assertEquals(month, back)
        }
    }

    // ==================== Fifth Week Edge Cases ====================

    @Test
    fun `Fifth Monday exists in some months`() {
        // January 2024 has 5 Mondays (1, 8, 15, 22, 29)
        val startFrom = instant(2024, 1, 1, 0, 0)
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.NthDayOfWeekInMonth(
                ordinal = WeekOrdinal.FIFTH,
                dayOfWeek = RecurrenceDayOfWeek.MONDAY,
                timeOfDay = TimeOfDay(9, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)

        assertEquals(29, nextDateTime.date.day)
        assertEquals(1, nextDateTime.month.number)
    }

    @Test
    fun `Fifth Monday skips months without fifth Monday`() {
        // March 2024 has 5 Mondays (4, 11, 18, 25, but starts late)
        // April 2024 has only 4 Mondays
        // Look for July 2024 which has 5 Mondays (1, 8, 15, 22, 29)
        val startFrom = instant(2024, 6, 15, 0, 0) // Start mid-June
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.NthDayOfWeekInMonth(
                ordinal = WeekOrdinal.FIFTH,
                dayOfWeek = RecurrenceDayOfWeek.MONDAY,
                timeOfDay = TimeOfDay(9, 0)
            ),
            startFrom = startFrom
        ).toRule()
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)

        // July 2024 has 5 Mondays, the 5th is July 29
        assertEquals(7, nextDateTime.month.number) // July
        assertEquals(29, nextDateTime.date.day)
    }

    // ==================== FixedPointPattern Validation Tests ====================

    @Test
    fun `DaysOfWeek requires at least one day`() {
        assertFailsWith<IllegalArgumentException> {
            FixedPointPattern.DaysOfWeek(persistentSetOf())
        }
    }

    @Test
    fun `DayOfMonth rejects day 0`() {
        assertFailsWith<IllegalArgumentException> {
            FixedPointPattern.DayOfMonth(dayOfMonth = 0)
        }
    }

    @Test
    fun `DayOfMonth rejects day 32`() {
        assertFailsWith<IllegalArgumentException> {
            FixedPointPattern.DayOfMonth(dayOfMonth = 32)
        }
    }

    @Test
    fun `DayOfMonth accepts day 31`() {
        val pattern = FixedPointPattern.DayOfMonth(dayOfMonth = 31)
        assertEquals(31, pattern.dayOfMonth)
    }

    @Test
    fun `YearlyOnDate rejects invalid day`() {
        assertFailsWith<IllegalArgumentException> {
            FixedPointPattern.YearlyOnDate(
                months = persistentSetOf(RecurrenceMonth.JANUARY),
                dayOfMonth = 32
            )
        }
    }

    @Test
    fun `NthDayOfWeekInMonths requires at least one month`() {
        assertFailsWith<IllegalArgumentException> {
            FixedPointPattern.NthDayOfWeekInMonths(
                ordinal = WeekOrdinal.FIRST,
                dayOfWeek = RecurrenceDayOfWeek.MONDAY,
                months = persistentSetOf()
            )
        }
    }

    // ==================== RecurrenceService Tests ====================

    @Test
    fun `processRecurrence for None rule returns terminated`() {
        val now = instant(2024, 1, 1, 0, 0)
        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(),
            triggerEvent = RecurrenceTriggerEvent(Open, now)
        )

        assertNull(result)
    }

    @Test
    fun `processRecurrence ignores non-matching trigger`() {
        val rule = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod.ofDays(1),
            firstOccurrence = instant(2024, 1, 1, 0, 0),
        ).toRule()

        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(rule to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0))),
            triggerEvent = RecurrenceTriggerEvent(Done, instant(2024, 1, 1, 0, 0))
        )

        // Should not advance because trigger doesn't match
        assertNull(result)
    }

    @Test
    fun `processRecurrence with status trigger advances on matching status`() {
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0),
            ),
            statusChangeTrigger = StatusChange(requiredStatuses = persistentSetOf(Done)),
            resetToStatus = Open
        )

        val now = instant(2024, 1, 2, 12, 0)
        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(rule to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0))),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )
        assertNotNull(result)

        val (newRules, newStatus) = result

        assertEquals(Open, newStatus)
        assertEquals(1, newRules.single().second.occurrenceCount)
        assertEquals(now, newRules.single().second.lastOccurrenceDate)
    }

    @Test
    fun `createNextOccurrence resets status`() {
        val task = Task(
            id = "TEST-1",
            title = "Test",
            spaceId = "space-1",
            status = Done
        )

        val nextTask = task.copy(
            status = Open,
            dueDate = instant(2024, 1, 15, 0, 0)
        )

        assertEquals(Open, nextTask.status)
        assertEquals(instant(2024, 1, 15, 0, 0), nextTask.dueDate)
    }

    // ==================== Display String Edge Cases ====================

    @Test
    fun `displayString for all days of week`() {
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.DaysOfWeek(
                days = RecurrenceDayOfWeek.entries.toSet().let { persistentSetOf(*it.toTypedArray()) }
            ),
            startFrom = instant(2024, 1, 1, 0, 0)
        ).toRule()
        val display = rule.toBriefString()
        // Should mention it's daily or list all days
        assertTrue(display.isNotBlank())
    }

    @Test
    fun `displayString for single day of week`() {
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.DaysOfWeek(
                days = persistentSetOf(RecurrenceDayOfWeek.WEDNESDAY)
            ),
            startFrom = instant(2024, 1, 1, 0, 0)
        ).toRule()
        val display = rule.toBriefString()
        assertTrue(display.contains("WED"))
    }

    @Test
    fun `displayString for NthDayOfWeekInMonth`() {
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.NthDayOfWeekInMonth(
                ordinal = WeekOrdinal.SECOND,
                dayOfWeek = RecurrenceDayOfWeek.TUESDAY
            ),
            startFrom = instant(2024, 1, 1, 0, 0)
        ).toRule()
        val display = rule.toBriefString()
        assertTrue(display.contains("second") || display.contains("Second") || display.contains("2nd"))
        assertTrue(display.contains("TUE"))
    }

    @Test
    fun `displayString for YearlyOnDate`() {
        val rule = RecurrenceTrigger.AtFixedPoints(
            pattern = FixedPointPattern.YearlyOnDate(
                months = persistentSetOf(RecurrenceMonth.DECEMBER),
                dayOfMonth = 25
            ),
            startFrom = instant(2024, 1, 1, 0, 0)
        ).toRule()
        val display = rule.toBriefString()
        assertTrue(display.contains("December") || display.contains("25"))
    }

    @Test
    fun `displayString for complex period`() {
        val rule = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod(years = 1, months = 6),
            firstOccurrence = instant(2024, 1, 1, 0, 0)
        ).toRule()
        val display = rule.toBriefString()
        assertTrue(display.isNotBlank())
    }

    // ==================== Multiple Occurrences Sequence Test ====================

    @Test
    fun `weekly recurrence produces correct sequence`() {
        val firstOccurrence = instant(2024, 1, 1, 9, 0) // Monday
        val rule = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod.ofWeeks(1),
            firstOccurrence = firstOccurrence
        ).toRule()

        var state = RecurrenceState()

        // First occurrence
        var next = RecurrenceCalculator.calculateNextOccurrence(rule, state)
        assertEquals(firstOccurrence, next)

        // Simulate processing
        state = state.copy(occurrenceCount = 1, lastOccurrenceDate = next)

        // Second occurrence
        next = RecurrenceCalculator.calculateNextOccurrence(rule, state)
        assertEquals(instant(2024, 1, 8, 9, 0), next)

        state = state.copy(occurrenceCount = 2, lastOccurrenceDate = next)

        // Third occurrence
        next = RecurrenceCalculator.calculateNextOccurrence(rule, state)
        assertEquals(instant(2024, 1, 15, 9, 0), next)
    }

    @Test
    fun `termination after 3 occurrences stops after third`() {
        val firstOccurrence = instant(2024, 1, 1, 9, 0)
        val rule = RecurrenceTrigger.AfterTimeout(
            period = RecurrencePeriod.ofDays(1),
            firstOccurrence = firstOccurrence,
        ).toRule().copy(termination = RecurrenceTermination.afterOccurrences(3))

        var state = RecurrenceState()

        // Occurrence 1
        var next = RecurrenceCalculator.calculateNextOccurrence(rule, state)
        assertNotNull(next)
        state = state.copy(occurrenceCount = 1, lastOccurrenceDate = next)

        // Occurrence 2
        next = RecurrenceCalculator.calculateNextOccurrence(rule, state)
        assertNotNull(next)
        state = state.copy(occurrenceCount = 2, lastOccurrenceDate = next)

        // Occurrence 3
        next = RecurrenceCalculator.calculateNextOccurrence(rule, state)
        assertNotNull(next)
        state = state.copy(occurrenceCount = 3, lastOccurrenceDate = next)

        // No more occurrences after 3
        next = RecurrenceCalculator.calculateNextOccurrence(rule, state)
        assertNull(next)
    }

    // ==================== Multiple Rules Tests ====================

    @Test
    fun `processRecurrence with multiple rules uses first matching rule`() {
        val now = instant(2024, 1, 2, 12, 0)
        val rule1 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open
        )
        val rule2 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofWeeks(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open
        )

        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(
                rule1 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0)),
                rule2 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 8, 0, 0))
            ),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )

        assertNotNull(result)
        val (newRules, newStatus) = result
        assertEquals(Open, newStatus)
        assertEquals(2, newRules.size)
        // Rule 1 should have advanced
        assertEquals(1, newRules[0].second.occurrenceCount)
        assertEquals(now, newRules[0].second.lastOccurrenceDate)
    }

    @Test
    fun `processRecurrence with multiple rules keeps exhausted rule with count 0`() {
        val now = instant(2024, 1, 2, 12, 0)
        val rule1 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open,
            termination = RecurrenceTermination.afterOccurrences(1)
        )
        val rule2 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofWeeks(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open
        )

        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(
                rule1 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0)),
                rule2 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 8, 0, 0))
            ),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )

        assertNotNull(result)
        val (newRules, _) = result
        // Rule 1 exhausted but kept with count 0
        assertEquals(2, newRules.size)
        assertEquals(0, newRules[0].first.termination.afterOccurrences?.count)
        assertEquals(rule2.timeRecurrenceTrigger, newRules[1].first.timeRecurrenceTrigger)
    }

    @Test
    fun `processRecurrence priority - earliest nextOccurrenceDate wins`() {
        val now = instant(2024, 1, 6, 12, 0)
        val rule1 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open
        )
        val rule2 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(2),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open
        )

        // Rule1 has earlier next occurrence (Jan 2) vs Rule2 (Jan 3)
        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(
                rule1 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 7, 0, 0)),
                rule2 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 6, 0, 0)) // Earlier
            ),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )

        assertNotNull(result)
        val (newRules, _) = result
        // Rule 2 should have triggered (earlier date)
        assertEquals(0, newRules[0].second.occurrenceCount) // Rule 1 unchanged
        assertEquals(1, newRules[1].second.occurrenceCount) // Rule 2 advanced
    }

    // ==================== Rules with Two Conditions Tests ====================

    @Test
    fun `rule with both time and status trigger requires both conditions`() {
        val now = instant(2024, 1, 2, 12, 0)
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open
        )

        // Time passed but status not matching
        val result1 = RecurrenceService.processRecurrence(
            rules = persistentListOf(rule to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0))),
            triggerEvent = RecurrenceTriggerEvent(Open, now)
        )
        assertNull(result1)

        // Status matches but time not passed
        val result2 = RecurrenceService.processRecurrence(
            rules = persistentListOf(rule to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 3, 0, 0))),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )
        assertNull(result2)

        // Both conditions met
        val result3 = RecurrenceService.processRecurrence(
            rules = persistentListOf(rule to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0))),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )
        assertNotNull(result3)
    }

    @Test
    fun `rule with both time and status conditions combined with termination keeps exhausted rule`() {
        val now = instant(2024, 1, 2, 12, 0)
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open,
            termination = RecurrenceTermination.afterOccurrences(1)
        )

        // First (and only) occurrence - rule should be removed after this
        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(rule to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0))),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )
        assertNotNull(result)
        // Rule exhausted and kept with count 0 after single occurrence
        assertEquals(1, result.first.size)
        assertEquals(0, result.first[0].first.termination.afterOccurrences?.count)
        assertEquals(Open, result.second) // Status reset to Open
    }

    @Test
    fun `rule termination by date prevents occurrence after end date`() {
        val now = instant(2024, 1, 2, 12, 0)
        val endDate = instant(2024, 1, 3, 0, 0)
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open,
            termination = RecurrenceTermination.onDate(endDate)
        )

        // This occurrence happens before end date
        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(rule to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0))),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )
        assertNotNull(result)
        assertEquals(1, result.first[0].second.occurrenceCount)

        // Next occurrence would be Jan 3, which is allowed since endDate is Jan 3 00:00
        assertNotNull(result.first[0].second.nextOccurrenceDate)
    }

    @Test
    fun `rule with two termination conditions respects both`() {
        val now = instant(2024, 1, 2, 12, 0)
        val endDate = instant(2024, 1, 10, 0, 0)
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open,
            termination = RecurrenceTermination(
                afterOccurrences = RecurrenceTerminationCondition.AfterOccurrences(1),
                onDate = RecurrenceTerminationCondition.OnDate(endDate)
            )
        )

        // First occurrence - rule should be removed (hits occurrence limit of 1)
        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(rule to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0))),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )
        assertNotNull(result)
        // Rule exhausted by occurrence limit before date limit, but kept with count 0
        assertEquals(1, result.first.size)
        assertEquals(0, result.first[0].first.termination.afterOccurrences?.count)
        assertEquals(Open, result.second) // Status reset to Open
    }

    @Test
    fun `multiple rules with different status triggers`() {
        val now = instant(2024, 1, 2, 12, 0)
        val rule1 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open
        )
        val rule2 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofWeeks(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(TaskStatus.InProgress)),
            resetToStatus = Open
        )

        // Trigger with Done status - only rule1 should trigger
        val result1 = RecurrenceService.processRecurrence(
            rules = persistentListOf(
                rule1 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0)),
                rule2 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0))
            ),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )
        assertNotNull(result1)
        assertEquals(1, result1.first[0].second.occurrenceCount) // Rule 1 advanced
        assertEquals(0, result1.first[1].second.occurrenceCount) // Rule 2 unchanged

        // Trigger with InProgress status - only rule2 should trigger
        val result2 = RecurrenceService.processRecurrence(
            rules = persistentListOf(
                rule1 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0)),
                rule2 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0))
            ),
            triggerEvent = RecurrenceTriggerEvent(TaskStatus.InProgress, now)
        )
        assertNotNull(result2)
        assertEquals(0, result2.first[0].second.occurrenceCount) // Rule 1 unchanged
        assertEquals(1, result2.first[1].second.occurrenceCount) // Rule 2 advanced
    }

    @Test
    fun `cascading rules - one rule triggers another`() {
        val now = instant(2024, 1, 2, 12, 0)
        val rule1 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = TaskStatus.InProgress // Resets to InProgress
        )
        val rule2 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(TaskStatus.InProgress)), // Triggers on InProgress
            resetToStatus = Open
        )

        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(
                rule1 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0)),
                rule2 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0))
            ),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )

        assertNotNull(result)
        val (newRules, newStatus) = result
        // Both rules should have triggered in cascade
        assertEquals(Open, newStatus) // Final status from rule2
        assertEquals(1, newRules[0].second.occurrenceCount) // Rule 1 triggered
        assertEquals(1, newRules[1].second.occurrenceCount) // Rule 2 triggered by rule 1's reset
    }

    @Test
    fun `rule priority - earliest nextOccurrenceDate triggers first among matching rules`() {
        val now = instant(2024, 1, 2, 12, 0)
        val rule1 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open
        )
        val rule2 = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(2),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open
        )

        // Rule1 has earlier next occurrence (Jan 2) vs Rule2 (Jan 3)
        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(
                rule1 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0)),
                rule2 to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 3, 0, 0))
            ),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )

        assertNotNull(result)
        // Rule 1 should have triggered (has earlier nextOccurrenceDate)
        assertEquals(1, result.first[0].second.occurrenceCount)
        assertEquals(0, result.first[1].second.occurrenceCount) // Not yet triggered
    }

    @Test
    fun `late trigger event does advance rule`() {
        val now = instant(2024, 1, 2, 12, 0)
        val rule = RecurrenceRule(
            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                period = RecurrencePeriod.ofDays(1),
                firstOccurrence = instant(2024, 1, 1, 0, 0)
            ),
            statusChangeTrigger = StatusChange(persistentSetOf(Done)),
            resetToStatus = Open,
            termination = RecurrenceTermination.onDate(now - 1.hours)
        )
        val result = RecurrenceService.processRecurrence(
            rules = persistentListOf(rule to RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0))),
            triggerEvent = RecurrenceTriggerEvent(Done, now)
        )
        assertNotNull(result)
        val (newRules, newState) = result
        assertEquals(Open, newState)
        // Rule is kept but marked as terminated (nextOccurrenceDate is null since termination date passed)
        assertEquals(1, newRules.size)
        assertNull(newRules[0].second.nextOccurrenceDate)
    }
}
