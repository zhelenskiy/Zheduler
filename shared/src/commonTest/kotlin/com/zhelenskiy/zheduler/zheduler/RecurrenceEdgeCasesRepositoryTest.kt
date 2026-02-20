@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.datetime.*
import kotlin.test.*
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
        assertEquals(2, result.monthNumber)
    }

    @Test
    fun `RecurrencePeriod addTo handles year transition`() {
        val period = RecurrencePeriod.ofMonths(2)
        val nov15 = LocalDateTime(2024, 11, 15, 10, 0)
        val result = period.addTo(nov15)
        assertEquals(2025, result.year)
        assertEquals(1, result.monthNumber)
        assertEquals(15, result.date.dayOfMonth)
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
    fun `AfterOccurrences requires positive count`() {
        assertFailsWith<IllegalArgumentException> {
            RecurrenceTerminationCondition.AfterOccurrences(0)
        }
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
        val rule = RecurrenceRule.AtFixedPoints(
            pattern = FixedPointPattern.YearlyOnDate(
                months = RecurrenceMonth.FEBRUARY,
                dayOfMonth = 29,
                timeOfDay = TimeOfDay(9, 0)
            ),
            startFrom = startFrom
        )
        val state = RecurrenceState(lastOccurrenceDate = startFrom)

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)

        // 2025 is not a leap year, should clamp to Feb 28
        assertEquals(2025, nextDateTime.year)
        assertEquals(2, nextDateTime.monthNumber)
        assertEquals(28, nextDateTime.date.dayOfMonth)
    }

    @Test
    fun `Monthly recurrence on 31st handles short months`() {
        // Starting from March 31
        val startFrom = instant(2024, 3, 31, 12, 0)
        val rule = RecurrenceRule.AtFixedPoints(
            pattern = FixedPointPattern.DayOfMonth(
                dayOfMonth = 31,
                timeOfDay = TimeOfDay(12, 0)
            ),
            startFrom = startFrom
        )
        val state = RecurrenceState(lastOccurrenceDate = startFrom)

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)

        // April has 30 days, should clamp to 30
        assertEquals(4, nextDateTime.monthNumber)
        assertEquals(30, nextDateTime.date.dayOfMonth)
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
        val rule = RecurrenceRule.AtFixedPoints(
            pattern = FixedPointPattern.NthDayOfWeekInMonth(
                ordinal = WeekOrdinal.FIFTH,
                dayOfWeek = RecurrenceDayOfWeek.MONDAY,
                timeOfDay = TimeOfDay(9, 0)
            ),
            startFrom = startFrom
        )
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)

        assertEquals(29, nextDateTime.date.dayOfMonth)
        assertEquals(1, nextDateTime.monthNumber)
    }

    @Test
    fun `Fifth Monday skips months without fifth Monday`() {
        // March 2024 has 5 Mondays (4, 11, 18, 25, but starts late)
        // April 2024 has only 4 Mondays
        // Look for July 2024 which has 5 Mondays (1, 8, 15, 22, 29)
        val startFrom = instant(2024, 6, 15, 0, 0) // Start mid-June
        val rule = RecurrenceRule.AtFixedPoints(
            pattern = FixedPointPattern.NthDayOfWeekInMonth(
                ordinal = WeekOrdinal.FIFTH,
                dayOfWeek = RecurrenceDayOfWeek.MONDAY,
                timeOfDay = TimeOfDay(9, 0)
            ),
            startFrom = startFrom
        )
        val state = RecurrenceState()

        val next = RecurrenceCalculator.calculateNextOccurrence(rule, state, triggerTime = startFrom)
        assertNotNull(next)
        val nextDateTime = next.toLocalDateTime(TimeZone.UTC)

        // July 2024 has 5 Mondays, the 5th is July 29
        assertEquals(7, nextDateTime.monthNumber) // July
        assertEquals(29, nextDateTime.date.dayOfMonth)
    }

    // ==================== FixedPointPattern Validation Tests ====================

    @Test
    fun `DaysOfWeek requires at least one day`() {
        assertFailsWith<IllegalArgumentException> {
            FixedPointPattern.DaysOfWeek(emptySet())
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
                months = RecurrenceMonth.JANUARY,
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
                months = emptySet()
            )
        }
    }

    // ==================== RecurrenceService Tests ====================

    @Test
    fun `processRecurrence for None rule returns terminated`() {
        val result = RecurrenceService.processRecurrence(
            rule = RecurrenceRule.None,
            currentState = RecurrenceState(),
            triggerEvent = RecurrenceTriggerEvent.DateTimeReached(TaskStatus.Open)
        )

        assertTrue(result.nextOccurrenceDate == null)
        assertNull(result.nextOccurrenceDate)
    }

    @Test
    fun `processRecurrence ignores non-matching trigger`() {
        val rule = RecurrenceRule.AfterTimeout(
            period = RecurrencePeriod.ofDays(1),
            firstOccurrence = instant(2024, 1, 1, 0, 0),
            trigger = RecurrenceTrigger.DateTime()
        )

        val result = RecurrenceService.processRecurrence(
            rule = rule,
            currentState = RecurrenceState(nextOccurrenceDate = instant(2024, 1, 2, 0, 0)),
            triggerEvent = RecurrenceTriggerEvent.StatusChanged(TaskStatus.Done)
        )

        // Should not advance because trigger doesn't match
        assertFalse(result.nextOccurrenceDate == null)
        assertEquals(0, result.updatedRecurrenceState.occurrenceCount)
    }

    @Test
    fun `processRecurrence with status trigger advances on matching status`() {
        val rule = RecurrenceRule.AfterTimeout(
            period = RecurrencePeriod.ofDays(1),
            firstOccurrence = instant(2024, 1, 1, 0, 0),
            trigger = RecurrenceTrigger.StatusChange(requiredStatuses = setOf(TaskStatus.Done))
        )

        val result = RecurrenceService.processRecurrence(
            rule = rule,
            currentState = RecurrenceState(),
            triggerEvent = RecurrenceTriggerEvent.StatusChanged(TaskStatus.Done),
            triggerTime = instant(2024, 1, 1, 12, 0)
        )

        assertEquals(1, result.updatedRecurrenceState.occurrenceCount)
        assertNotNull(result.nextOccurrenceDate)
    }

    @Test
    fun `createNextOccurrence resets status`() {
        val task = Task(
            id = "TEST-1",
            title = "Test",
            spaceId = "space-1",
            status = TaskStatus.Done
        )

        val nextTask = RecurrenceService.createNextOccurrence(
            task = task,
            recurrenceRule = RecurrenceRule.None,
            newDueDate = instant(2024, 1, 15, 0, 0),
            resetToStatus = TaskStatus.Open
        )

        assertEquals(TaskStatus.Open, nextTask.status)
        assertEquals(instant(2024, 1, 15, 0, 0), nextTask.dueDate)
    }

    // ==================== Display String Edge Cases ====================

    @Test
    fun `displayString for all days of week`() {
        val rule = RecurrenceRule.AtFixedPoints(
            pattern = FixedPointPattern.DaysOfWeek(
                days = RecurrenceDayOfWeek.entries.toSet()
            ),
            startFrom = instant(2024, 1, 1, 0, 0)
        )
        val display = rule.toDisplayString()
        // Should mention it's daily or list all days
        assertTrue(display.isNotBlank())
    }

    @Test
    fun `displayString for single day of week`() {
        val rule = RecurrenceRule.AtFixedPoints(
            pattern = FixedPointPattern.DaysOfWeek(
                days = setOf(RecurrenceDayOfWeek.WEDNESDAY)
            ),
            startFrom = instant(2024, 1, 1, 0, 0)
        )
        val display = rule.toDisplayString()
        assertTrue(display.contains("Wednesday"))
    }

    @Test
    fun `displayString for NthDayOfWeekInMonth`() {
        val rule = RecurrenceRule.AtFixedPoints(
            pattern = FixedPointPattern.NthDayOfWeekInMonth(
                ordinal = WeekOrdinal.SECOND,
                dayOfWeek = RecurrenceDayOfWeek.TUESDAY
            ),
            startFrom = instant(2024, 1, 1, 0, 0)
        )
        val display = rule.toDisplayString()
        assertTrue(display.contains("second") || display.contains("Second") || display.contains("2nd"))
        assertTrue(display.contains("Tuesday"))
    }

    @Test
    fun `displayString for YearlyOnDate`() {
        val rule = RecurrenceRule.AtFixedPoints(
            pattern = FixedPointPattern.YearlyOnDate(
                months = RecurrenceMonth.DECEMBER,
                dayOfMonth = 25
            ),
            startFrom = instant(2024, 1, 1, 0, 0)
        )
        val display = rule.toDisplayString()
        assertTrue(display.contains("December") || display.contains("25"))
    }

    @Test
    fun `displayString for complex period`() {
        val rule = RecurrenceRule.AfterTimeout(
            period = RecurrencePeriod(years = 1, months = 6),
            firstOccurrence = instant(2024, 1, 1, 0, 0)
        )
        val display = rule.toDisplayString()
        assertTrue(display.isNotBlank())
    }

    // ==================== Multiple Occurrences Sequence Test ====================

    @Test
    fun `weekly recurrence produces correct sequence`() {
        val firstOccurrence = instant(2024, 1, 1, 9, 0) // Monday
        val rule = RecurrenceRule.AfterTimeout(
            period = RecurrencePeriod.ofWeeks(1),
            firstOccurrence = firstOccurrence
        )

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
        val rule = RecurrenceRule.AfterTimeout(
            period = RecurrencePeriod.ofDays(1),
            firstOccurrence = firstOccurrence,
            termination = RecurrenceTermination.afterOccurrences(3)
        )

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

        // Should terminate after 3
        next = RecurrenceCalculator.calculateNextOccurrence(rule, state)
        assertNull(next)
    }
}
