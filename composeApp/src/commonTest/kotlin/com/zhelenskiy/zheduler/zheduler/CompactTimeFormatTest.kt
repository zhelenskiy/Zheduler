package com.zhelenskiy.zheduler.zheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import com.zhelenskiy.zheduler.zheduler.parseCompactTime

class CompactTimeFormatTest {

    @Test
    fun testParseCompactTime_validFormats() {
        // Basic units (in seconds)
        assertEquals(1L, parseCompactTime("1s"))
        assertEquals(60L, parseCompactTime("1m"))
        assertEquals(60L * 60L, parseCompactTime("1h"))
        assertEquals(24L * 60L * 60L, parseCompactTime("1d"))
        assertEquals(7L * 24L * 60L * 60L, parseCompactTime("1w"))
        assertEquals(30L * 24L * 60L * 60L, parseCompactTime("1mo"))
        assertEquals(365L * 24L * 60L * 60L, parseCompactTime("1y"))

        // Seconds
        assertEquals(59L, parseCompactTime("59s"))
        assertEquals(60L, parseCompactTime("60s"))
        assertEquals(61L, parseCompactTime("61s"))
        assertEquals(119L, parseCompactTime("119s"))

        // Combined formats
        assertEquals(2L * 60L * 60L + 30L * 60L, parseCompactTime("2h30m"))
        assertEquals(60L * 60L + 30L * 60L, parseCompactTime("1h30m"))
        assertEquals(24L * 60L * 60L + 2L * 60L * 60L, parseCompactTime("1d2h"))
        assertEquals(60L * 60L + 30L * 60L + 45L, parseCompactTime("1h30m45s"))

        // With spaces
        assertEquals(2L * 60L * 60L + 30L * 60L, parseCompactTime("2h 30m"))
        assertEquals(60L * 60L + 30L * 60L, parseCompactTime("1h 30m"))
        assertEquals(24L * 60L * 60L + 2L * 60L * 60L, parseCompactTime("1d 2h"))

        // Complex combinations
        assertEquals(
            365L * 24L * 60L * 60L + 30L * 24L * 60L * 60L + 7L * 24L * 60L * 60L + 24L * 60L * 60L + 60L * 60L + 30L * 60L,
            parseCompactTime("1y1mo1w1d1h30m")
        )

        // Case insensitive
        assertEquals(60L * 60L, parseCompactTime("1H"))
        assertEquals(24L * 60L * 60L, parseCompactTime("1D"))
        assertEquals(30L * 24L * 60L * 60L, parseCompactTime("1MO"))
        assertEquals(2L * 60L * 60L + 30L * 60L, parseCompactTime("2H30M"))

        // All units
        assertEquals(
            365L * 24L * 60L * 60L + 30L * 24L * 60L * 60L + 7L * 24L * 60L * 60L + 24L * 60L * 60L + 60L * 60L + 30L * 60L + 45L,
            parseCompactTime("1y1mo1w1d1h30m45s")
        )
    }

    @Test
    fun testParseCompactTime_invalidFormats() {
        // Empty or blank
        assertNull(parseCompactTime(""))
        assertNull(parseCompactTime("   "))

        // Invalid characters
        assertNull(parseCompactTime("abc"))
        assertNull(parseCompactTime("2x"))
        assertNull(parseCompactTime("1h2x3m"))

        // Duplicate units
        assertNull(parseCompactTime("2h3h"))
        assertNull(parseCompactTime("1d2d"))
        assertNull(parseCompactTime("5m10m"))
        assertNull(parseCompactTime("5s10s"))

        // Missing numbers
        assertNull(parseCompactTime("h"))
        assertNull(parseCompactTime("hm"))

        // Zero values result in null
        assertNull(parseCompactTime("0m"))
        assertNull(parseCompactTime("0h"))
        assertNull(parseCompactTime("0s"))

        // Invalid unit order doesn't matter but invalid units do
        assertNull(parseCompactTime("1mm"))
        assertNull(parseCompactTime("1hh"))
    }

    @Test
    fun testParseCompactTime_edgeCases() {
        // Large numbers
        assertEquals(999L * 60L * 60L, parseCompactTime("999h"))
        assertEquals(999L * 60L, parseCompactTime("999m"))
        assertEquals(999L, parseCompactTime("999s"))

        // Single unit variations
        assertEquals(60L, parseCompactTime("1M"))
        assertEquals(60L * 60L, parseCompactTime("1h"))
        assertEquals(60L, parseCompactTime("1 m"))

        // Multiple spaces
        assertEquals(2L * 60L * 60L + 30L * 60L, parseCompactTime("2h  30m"))
        assertEquals(60L * 60L + 30L * 60L, parseCompactTime("  1h   30m  "))
    }


    @Test
    fun testParseCompactTimeToPeriod_normalization() {
        // Test that normalization happens correctly

        // 60s -> 1m
        parseCompactTimeToPeriod("60s")?.let { period ->
            assertEquals(1, period.minutes)
            assertEquals(0, period.seconds)
        } ?: error("Failed to parse 60s")

        // 90s -> 1m 30s
        parseCompactTimeToPeriod("90s")?.let { period ->
            assertEquals(1, period.minutes)
            assertEquals(30, period.seconds)
        } ?: error("Failed to parse 90s")

        // 60m -> 1h
        parseCompactTimeToPeriod("60m")?.let { period ->
            assertEquals(1, period.hours)
            assertEquals(0, period.minutes)
        } ?: error("Failed to parse 60m")

        // 24h -> 1d
        parseCompactTimeToPeriod("24h")?.let { period ->
            assertEquals(1, period.days)
            assertEquals(0, period.hours)
        } ?: error("Failed to parse 24h")

        // 7d -> 1w (THIS IS THE KEY TEST)
        parseCompactTimeToPeriod("7d")?.let { period ->
            assertEquals(1, period.weeks)
            assertEquals(0, period.days)
        } ?: error("Failed to parse 7d")

        // 14d -> 2w
        parseCompactTimeToPeriod("14d")?.let { period ->
            assertEquals(2, period.weeks)
            assertEquals(0, period.days)
        } ?: error("Failed to parse 14d")

        // 31d -> 4w 3d (NOT 1mo)
        parseCompactTimeToPeriod("31d")?.let { period ->
            assertEquals(0, period.months)
            assertEquals(4, period.weeks)
            assertEquals(3, period.days)
        } ?: error("Failed to parse 31d")

        // 30d -> 4w 2d (NOT 1mo)
        parseCompactTimeToPeriod("30d")?.let { period ->
            assertEquals(0, period.months)
            assertEquals(4, period.weeks)
            assertEquals(2, period.days)
        } ?: error("Failed to parse 30d")

        // 12mo -> 1y
        parseCompactTimeToPeriod("12mo")?.let { period ->
            assertEquals(1, period.years)
            assertEquals(0, period.months)
        } ?: error("Failed to parse 12mo")

        // 13mo -> 1y 1mo
        parseCompactTimeToPeriod("13mo")?.let { period ->
            assertEquals(1, period.years)
            assertEquals(1, period.months)
        } ?: error("Failed to parse 13mo")

        // Complex: 25h 90m 120s -> 1d 2h 32m
        parseCompactTimeToPeriod("25h90m120s")?.let { period ->
            assertEquals(1, period.days)
            assertEquals(2, period.hours)
            assertEquals(32, period.minutes)
            assertEquals(0, period.seconds)
        } ?: error("Failed to parse 25h90m120s")
    }

    @Test
    fun testParseCompactTimeToPeriod_preservesMonthsAndYears() {
        // 1mo stays as 1mo (not converted to days)
        parseCompactTimeToPeriod("1mo")?.let { period ->
            assertEquals(0, period.years)
            assertEquals(1, period.months)
            assertEquals(0, period.weeks)
            assertEquals(0, period.days)
        } ?: error("Failed to parse 1mo")

        // 2mo stays as 2mo
        parseCompactTimeToPeriod("2mo")?.let { period ->
            assertEquals(0, period.years)
            assertEquals(2, period.months)
        } ?: error("Failed to parse 2mo")

        // 1y stays as 1y
        parseCompactTimeToPeriod("1y")?.let { period ->
            assertEquals(1, period.years)
            assertEquals(0, period.months)
        } ?: error("Failed to parse 1y")

        // 1y 2mo stays as 1y 2mo
        parseCompactTimeToPeriod("1y2mo")?.let { period ->
            assertEquals(1, period.years)
            assertEquals(2, period.months)
        } ?: error("Failed to parse 1y2mo")
    }
}
