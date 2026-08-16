@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.dialogs

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

/**
 * The date picker holds a day as midnight UTC. A task's due date is an instant, and the day it
 * falls on locally is not the day it falls on in UTC — for anything late in the evening west of
 * Greenwich, or early in the morning east of it, the two differ.
 */
class DatePickerRoundTripTest {

    private val zones = listOf(
        "UTC",
        "Europe/Moscow",      // +03, so 01:00 local is the previous day in UTC
        "Pacific/Kiritimati", // +14, the largest offset there is
        "America/Los_Angeles",// -08, so 20:00 local is the next day in UTC
        "Pacific/Niue",       // -11
    ).map(TimeZone::of)

    private val localTimes = listOf(
        LocalDateTime(2024, 3, 15, 0, 30),
        LocalDateTime(2024, 3, 15, 12, 0),
        LocalDateTime(2024, 3, 15, 23, 45),
        LocalDateTime(2024, 12, 31, 23, 59),
        LocalDateTime(2024, 1, 1, 0, 0),
    )

    @Test
    fun `the picker opens on the day the instant falls on locally`() {
        for (zone in zones) {
            for (local in localTimes) {
                val instant = local.toInstant(zone)
                assertEquals(
                    local.date,
                    datePickerDateOf(datePickerMillisFor(instant, zone)),
                    "$local in $zone",
                )
            }
        }
    }

    @Test
    fun `passing the instant straight through is what picked the wrong day`() {
        val moscow = TimeZone.of("Europe/Moscow")
        val justAfterMidnight = LocalDateTime(2024, 3, 15, 1, 0).toInstant(moscow)

        // What the dialog used to hand the picker.
        assertEquals(
            LocalDate(2024, 3, 14),
            datePickerDateOf(justAfterMidnight.toEpochMilliseconds()),
            "the raw instant lands on the previous UTC day",
        )
        assertEquals(LocalDate(2024, 3, 15), datePickerDateOf(datePickerMillisFor(justAfterMidnight, moscow)))
    }

    @Test
    fun `a day handed back is the day that was shown`() {
        val date = LocalDate(2024, 6, 1)
        val millis = datePickerMillisFor(date.atStartOfDayIn(TimeZone.UTC), TimeZone.UTC)
        assertEquals(date, datePickerDateOf(millis))
    }
}

private fun LocalDate.atStartOfDayIn(zone: TimeZone) =
    kotlinx.datetime.LocalDateTime(year, month, day, 0, 0).toInstant(zone)
