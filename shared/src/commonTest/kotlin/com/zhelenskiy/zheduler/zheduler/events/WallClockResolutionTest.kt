@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.events

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

/**
 * A wall-clock reading is not one instant. On the two days a year a zone changes its offset it is
 * either none or two, and a scheduler that assumes one either drops a reminder or delivers it
 * twice.
 *
 * New York, 2026: clocks jump 02:00 → 03:00 on March 8, and 02:00 → 01:00 on November 1.
 */
class WallClockResolutionTest {

    private val ny = TimeZone.of("America/New_York")

    @Test
    fun `an ordinary reading happens exactly once`() {
        val noon = LocalDateTime(2026, 6, 15, 12, 0)
        val occurrences = noon.occurrencesIn(ny)

        assertEquals(1, occurrences.size, "a reading outside a transition has one instant")
        assertEquals(noon.toInstant(ny), occurrences.single())
    }

    @Test
    fun `a reading inside the lost hour happens never`() {
        val lost = LocalDateTime(2026, 3, 8, 2, 30)

        assertEquals(
            emptyList(),
            lost.occurrencesIn(ny),
            "02:30 does not exist on the day New York jumps 02:00 to 03:00",
        )
    }

    @Test
    fun `a reading inside the repeated hour happens twice an hour apart`() {
        val repeated = LocalDateTime(2026, 11, 1, 1, 30)
        val occurrences = repeated.occurrencesIn(ny)

        assertEquals(2, occurrences.size, "01:30 comes round twice when New York falls back")
        assertEquals(1.hours, occurrences.last() - occurrences.first())
        assertTrue(
            occurrences.all { it.toLocalDateTime(ny) == repeated },
            "both instants must read back as the same wall clock",
        )
    }

    @Test
    fun `the ambiguous policy chooses between the two passes`() {
        val repeated = LocalDateTime(2026, 11, 1, 1, 30)
        val both = repeated.occurrencesIn(ny)

        assertEquals(both.first(), repeated.resolveIn(ny, ambiguous = AmbiguousTimePolicy.Earlier))
        assertEquals(both.last(), repeated.resolveIn(ny, ambiguous = AmbiguousTimePolicy.Later))
    }

    @Test
    fun `a lost reading either shifts past the jump or reports nothing`() {
        val lost = LocalDateTime(2026, 3, 8, 2, 30)

        assertEquals(
            LocalDateTime(2026, 3, 8, 3, 30).toInstant(ny),
            lost.resolveIn(ny),
            "shifting forward moves on by the length of the jump",
        )
        assertNull(lost.resolveInOrNull(ny), "the nullable form reports the day as having no such time")
    }

    @Test
    fun `the defaults agree with the platform conversion`() {
        // Anything else would make the new resolution a second, quietly different answer to a
        // question the rest of the codebase already asks with toInstant.
        listOf(
            LocalDateTime(2026, 6, 15, 12, 0),
            LocalDateTime(2026, 3, 8, 2, 30),
            LocalDateTime(2026, 11, 1, 1, 30),
        ).forEach { reading ->
            assertEquals(reading.toInstant(ny), reading.resolveIn(ny), "$reading")
        }
    }
}
