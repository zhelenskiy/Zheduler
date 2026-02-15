@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.util

import com.zhelenskiy.zheduler.zheduler.RecurrencePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

fun formatDueDateRest(epochMillis: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')
    return "${dateTime.month.name.lowercase().replaceFirstChar(Char::uppercaseChar)} ${dateTime.day}, ${dateTime.year} at $hour:$minute"
}

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
        else -> formatDueDateRest(instant.toEpochMilliseconds())
    }
}

/**
 * Formats a RecurrencePeriod to a human-readable string.
 * Example: "1y 2mo 3w 4d 5h 6m 7s"
 */
fun formatPeriod(period: RecurrencePeriod): String = buildString {
    if (period.years > 0) append("${period.years}y ")
    if (period.months > 0) append("${period.months}mo ")
    if (period.weeks > 0) append("${period.weeks}w ")
    if (period.days > 0) append("${period.days}d ")
    if (period.hours > 0) append("${period.hours}h ")
    if (period.minutes > 0) append("${period.minutes}m ")
    if (period.seconds > 0) append("${period.seconds}s")
}.trim()

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
