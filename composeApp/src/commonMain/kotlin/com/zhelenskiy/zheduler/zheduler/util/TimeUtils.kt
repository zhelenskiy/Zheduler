@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.util

import com.zhelenskiy.zheduler.zheduler.formatDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

fun formatDueDate(instant: Instant, clock: Clock = Clock.System): String {
    val tz = TimeZone.currentSystemDefault()
    val dateTime = instant.toLocalDateTime(tz)
    val nowDateTime = clock.now().toLocalDateTime(tz)

    // Compare calendar dates, not raw time differences
    val dueDate = dateTime.date
    val todayDate = nowDateTime.date

    val daysDiff = dueDate.toEpochDays() - todayDate.toEpochDays()

    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')
    val timeStr = " at $hour:$minute"

    return when (daysDiff) {
        0L -> "Today$timeStr"
        1L -> "Tomorrow$timeStr"
        -1L -> "Yesterday$timeStr"
        else -> formatDate(Instant.fromEpochMilliseconds(instant.toEpochMilliseconds()))
    }
}

/**
 * Formats a timestamp to a compact date-time string for timeline display.
 * Example: "Jan 15 14:30:45"
 */
fun formatCompactDateTime(instant: Instant): String {
    val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val monthAbbr = dateTime.month.name.take(3).lowercase().replaceFirstChar(Char::uppercaseChar)
    val day = dateTime.day
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')
    val second = dateTime.second.toString().padStart(2, '0')
    return "$monthAbbr $day $hour:$minute:$second"
}
