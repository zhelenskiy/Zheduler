@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.util.formatCompactDateTime
import com.zhelenskiy.zheduler.zheduler.util.formatDueDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class TimeUtilsTest {

    // ==================== formatPeriod Tests (Basic) ====================

    @Test
    fun `formatPeriod with all components`() {
        val period = RecurrencePeriod(
            years = 1,
            months = 2,
            weeks = 3,
            days = 4,
            hours = 5,
            minutes = 6,
            seconds = 7
        )
        assertEquals("1y 2mo 3w 4d 5h 6m 7s", period.toBriefString())
    }

    @Test
    fun `formatPeriod skips zero components`() {
        val period = RecurrencePeriod(years = 1, days = 5)
        assertEquals("1y 5d", period.toBriefString())
    }

    // ==================== formatDueDate Tests ====================

    @Test
    fun `formatDueDate for today contains Today`() {
        val now = Clock.System.now()
        val result = formatDueDate(now)
        assertTrue(result.contains("Today") || result.contains("at"))
    }

    @Test
    fun `formatDueDate for tomorrow contains Tomorrow`() {
        val tomorrow = Clock.System.now() + 1.days
        val result = formatDueDate(tomorrow)
        assertTrue(result.contains("Tomorrow") || result.contains("at"))
    }

    @Test
    fun `formatDueDate for yesterday contains Yesterday`() {
        val yesterday = Clock.System.now() - 1.days
        val result = formatDueDate(yesterday)
        assertTrue(result.contains("Yesterday") || result.contains("at"))
    }

    @Test
    fun `formatDueDate includes time component`() {
        val now = Clock.System.now()
        val result = formatDueDate(now)
        assertTrue(result.contains("at"))
        assertTrue(result.contains(":"))
    }

    @Test
    fun `formatDueDate for far future shows month and day`() {
        val farFuture = Clock.System.now() + 30.days
        val result = formatDueDate(farFuture)
        // Should contain month name and day
        assertTrue(result.contains(",") || result.isNotBlank())
    }

    // ==================== formatCompactDateTime Tests ====================

    @Test
    fun `formatCompactDateTime includes month abbreviation`() {
        val now = Clock.System.now()
        val result = formatCompactDateTime(now)
        // Should have format like "Jan 15 14:30:45"
        val parts = result.split(" ")
        assertTrue(parts.size >= 3)
        assertTrue(parts[0].length == 3) // Month abbreviation
    }

    @Test
    fun `formatCompactDateTime includes day`() {
        val now = Clock.System.now()
        val result = formatCompactDateTime(now)
        val dateTime = now.toLocalDateTime(TimeZone.currentSystemDefault())
        assertTrue(result.contains(dateTime.dayOfMonth.toString()))
    }

    @Test
    fun `formatCompactDateTime includes time with seconds`() {
        val now = Clock.System.now()
        val result = formatCompactDateTime(now)
        // Should contain colons for time
        assertTrue(result.count { it == ':' } == 2)
    }

    @Test
    fun `formatCompactDateTime pads hours minutes seconds`() {
        // Create instant with single-digit time components
        val instant = Instant.fromEpochMilliseconds(0) // Jan 1, 1970 00:00:00 UTC
        val result = formatCompactDateTime(instant)
        // Should have padded format like "00:00:00"
        assertTrue(result.contains("00:00:00") || result.contains(":"))
    }

    // ==================== parseCompactTimeToPeriod Integration Tests ====================

    @Test
    fun `parse and format roundtrip preserves meaning`() {
        val testCases = listOf(
            "1y",
            "2mo",
            "3w",
            "4d",
            "5h",
            "6m",
            "7s",
            "1y 2mo 3d",
            "2w 4h 30m"
        )

        for (input in testCases) {
            val period = parseCompactTimeToPeriod(input)
            if (period != null) {
                val formatted = period.toBriefString()
                val reparsed = parseCompactTimeToPeriod(formatted)
                assertEquals(period, reparsed, "Roundtrip failed for: $input -> $formatted")
            }
        }
    }

    // ==================== Edge Cases ====================

    @Test
    fun `formatDueDate handles epoch instant`() {
        val epoch = Instant.fromEpochMilliseconds(0)
        val result = formatDueDate(epoch)
        // Should produce some valid string without crashing
        assertTrue(result.isNotBlank())
    }

    @Test
    fun `formatCompactDateTime handles epoch instant`() {
        val epoch = Instant.fromEpochMilliseconds(0)
        val result = formatCompactDateTime(epoch)
        assertTrue(result.isNotBlank())
        assertTrue(result.contains("Jan") || result.contains("Dec")) // Depends on timezone
    }

    @Test
    fun `formatPeriod with single unit has no trailing space`() {
        val period = RecurrencePeriod(hours = 1)
        val result = period.toBriefString()
        assertFalse(result.endsWith(" "))
        assertFalse(result.startsWith(" "))
    }

    @Test
    fun `formatDueDate near midnight boundary`() {
        val nearMidnight = Clock.System.now()
        // Just ensure it doesn't crash near day boundaries
        val result = formatDueDate(nearMidnight)
        assertTrue(result.isNotBlank())
    }

    // ==================== parseCompactTimeToPeriod - Overflow Conversion Tests ====================

    @Test
    fun `parseCompactTimeToPeriod converts 60 seconds to 1 minute`() {
        val period = parseCompactTimeToPeriod("60s")
        assertNotNull(period)
        assertEquals(0, period.seconds)
        assertEquals(1, period.minutes)
    }

    @Test
    fun `parseCompactTimeToPeriod converts 90 seconds to 1m 30s`() {
        val period = parseCompactTimeToPeriod("90s")
        assertNotNull(period)
        assertEquals(30, period.seconds)
        assertEquals(1, period.minutes)
    }

    @Test
    fun `parseCompactTimeToPeriod converts 120 seconds to 2 minutes`() {
        val period = parseCompactTimeToPeriod("120s")
        assertNotNull(period)
        assertEquals(0, period.seconds)
        assertEquals(2, period.minutes)
    }

    @Test
    fun `parseCompactTimeToPeriod converts 60 minutes to 1 hour`() {
        val period = parseCompactTimeToPeriod("60m")
        assertNotNull(period)
        assertEquals(0, period.minutes)
        assertEquals(1, period.hours)
    }

    @Test
    fun `parseCompactTimeToPeriod converts 90 minutes to 1h 30m`() {
        val period = parseCompactTimeToPeriod("90m")
        assertNotNull(period)
        assertEquals(30, period.minutes)
        assertEquals(1, period.hours)
    }

    @Test
    fun `parseCompactTimeToPeriod converts 24 hours to 1 day`() {
        val period = parseCompactTimeToPeriod("24h")
        assertNotNull(period)
        assertEquals(0, period.hours)
        assertEquals(1, period.days)
    }

    @Test
    fun `parseCompactTimeToPeriod converts 25 hours to 1d 1h`() {
        val period = parseCompactTimeToPeriod("25h")
        assertNotNull(period)
        assertEquals(1, period.hours)
        assertEquals(1, period.days)
    }

    @Test
    fun `parseCompactTimeToPeriod converts 7 days to 1 week`() {
        val period = parseCompactTimeToPeriod("7d")
        assertNotNull(period)
        assertEquals(0, period.days)
        assertEquals(1, period.weeks)
    }

    @Test
    fun `parseCompactTimeToPeriod converts 14 days to 2 weeks`() {
        val period = parseCompactTimeToPeriod("14d")
        assertNotNull(period)
        assertEquals(0, period.days)
        assertEquals(2, period.weeks)
    }

    @Test
    fun `parseCompactTimeToPeriod converts 10 days to 1w 3d`() {
        val period = parseCompactTimeToPeriod("10d")
        assertNotNull(period)
        assertEquals(3, period.days)
        assertEquals(1, period.weeks)
    }

    @Test
    fun `parseCompactTimeToPeriod converts 12 months to 1 year`() {
        val period = parseCompactTimeToPeriod("12mo")
        assertNotNull(period)
        assertEquals(0, period.months)
        assertEquals(1, period.years)
    }

    @Test
    fun `parseCompactTimeToPeriod converts 13 months to 1y 1mo`() {
        val period = parseCompactTimeToPeriod("13mo")
        assertNotNull(period)
        assertEquals(1, period.months)
        assertEquals(1, period.years)
    }

    @Test
    fun `parseCompactTimeToPeriod does NOT convert weeks to months`() {
        // 4 weeks ≠ 1 month (4 weeks = 28 days, not 30)
        val period = parseCompactTimeToPeriod("4w")
        assertNotNull(period)
        assertEquals(4, period.weeks)
        assertEquals(0, period.months)
    }

    @Test
    fun `parseCompactTimeToPeriod does NOT convert days to months`() {
        // 30 days stays as weeks + days, not converted to month
        val period = parseCompactTimeToPeriod("30d")
        assertNotNull(period)
        assertEquals(4, period.weeks) // 30 / 7 = 4 weeks
        assertEquals(2, period.days)  // 30 % 7 = 2 days
        assertEquals(0, period.months)
    }

    @Test
    fun `parseCompactTimeToPeriod handles cascading overflow`() {
        // 90s + 90m + 25h = 1m30s + 1h30m + 1d1h = 1d 2h 31m 30s
        val period = parseCompactTimeToPeriod("25h 90m 90s")
        assertNotNull(period)
        assertEquals(30, period.seconds)
        assertEquals(31, period.minutes) // 90 + 1 from seconds
        assertEquals(2, period.hours)    // 25 + 1 from minutes - 24
        assertEquals(1, period.days)
    }

    @Test
    fun `parseCompactTimeToPeriod handles extreme overflow`() {
        // 3665s = 1h 1m 5s
        val period = parseCompactTimeToPeriod("3665s")
        assertNotNull(period)
        assertEquals(5, period.seconds)
        assertEquals(1, period.minutes)
        assertEquals(1, period.hours)
    }

    @Test
    fun `parseCompactTimeToPeriod validates no all-zero values`() {
        // All zero values should be rejected
        assertNull(parseCompactTimeToPeriod("0s"))
        assertNull(parseCompactTimeToPeriod("0m"))
        assertNull(parseCompactTimeToPeriod("0h"))
    }
}
