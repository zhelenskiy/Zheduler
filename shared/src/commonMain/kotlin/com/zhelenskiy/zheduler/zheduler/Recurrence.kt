@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Parse time from compact format (e.g., "2y3mo1w5d12h30m45s") to RecurrencePeriod
 * Supports: y (years), mo (months), w (weeks), d (days), h (hours), m (minutes), s (seconds)
 * Spaces between parts are optional
 * Returns null if format is invalid
 *
 * Normalizes: 7d→1w, 12mo→1y, 60s→1m, 60m→1h, 24h→1d
 * Does NOT convert: days to months (30d stays 30d), weeks to months
 */
fun parseCompactTimeToPeriod(input: String): RecurrencePeriod? {
    if (input.isBlank()) return null

    val normalized = input.trim().replace(" ", "").lowercase()
    if (normalized.isEmpty()) return null

    // Pattern: number followed by unit (y, mo, w, d, h, m, s)
    val pattern = Regex("""(\d+)(y|mo|w|d|h|m|s)""")
    val matches = pattern.findAll(normalized).toList()

    // Check if the entire string was matched (no invalid characters)
    val matchedString = matches.joinToString("") { it.value }
    if (matchedString != normalized) return null

    if (matches.isEmpty()) return null

    var years = 0
    var months = 0
    var weeks = 0
    var days = 0
    var hours = 0
    var minutes = 0
    var seconds = 0
    val seenUnits = mutableSetOf<String>()

    for (match in matches) {
        val value = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]

        // Check for duplicate units
        if (unit in seenUnits) return null
        seenUnits.add(unit)

        when (unit) {
            "y" -> years = value
            "mo" -> months = value
            "w" -> weeks = value
            "d" -> days = value
            "h" -> hours = value
            "m" -> minutes = value
            "s" -> seconds = value
            else -> return null
        }
    }

    // Normalize: convert smaller units to larger where appropriate
    // seconds -> minutes
    if (seconds >= 60) {
        minutes += seconds / 60
        seconds %= 60
    }

    // minutes -> hours
    if (minutes >= 60) {
        hours += minutes / 60
        minutes %= 60
    }

    // hours -> days
    if (hours >= 24) {
        days += hours / 24
        hours %= 24
    }

    // days -> weeks (7 days = 1 week)
    if (days >= 7) {
        weeks += days / 7
        days %= 7
    }

    // months -> years (12 months = 1 year)
    if (months >= 12) {
        years += months / 12
        months %= 12
    }

    return try {
        RecurrencePeriod(
            years = years,
            months = months,
            weeks = weeks,
            days = days,
            hours = hours,
            minutes = minutes,
            seconds = seconds
        )
    } catch (e: IllegalArgumentException) {
        null
    }
}

/**
 * Parse time from compact format (e.g., "2y3mo1w5d12h30m45s") to seconds
 * Supports: y (years), mo (months), w (weeks), d (days), h (hours), m (minutes), s (seconds)
 * Spaces between parts are optional
 * Returns null if format is invalid
 *
 * Note: This function uses parseCompactTimeToPeriod for normalization, then converts to seconds.
 * Use this for estimated time and other duration fields that need seconds representation.
 */
fun parseCompactTime(input: String): Long? {
    val period = parseCompactTimeToPeriod(input) ?: return null

    // Convert to approximate seconds (using 365 days per year, 30 days per month)
    return period.years * 365L * 24L * 60L * 60L +
           period.months * 30L * 24L * 60L * 60L +
           period.weeks * 7L * 24L * 60L * 60L +
           period.days * 24L * 60L * 60L +
           period.hours * 60L * 60L +
           period.minutes * 60L +
           period.seconds
}

/**
 * Timezone specification for recurrence calculations
 */
@Serializable
sealed class RecurrenceTimeZone {
    /**
     * Use the system's default timezone
     */
    @Serializable
    data object SystemDefault : RecurrenceTimeZone()
    
    /**
     * Use a specific timezone
     */
    @Serializable
    data class Specific(val zoneId: String) : RecurrenceTimeZone() {
        init {
            // Validate timezone ID
            require(runCatching { TimeZone.of(zoneId) }.isSuccess) {
                "Invalid timezone ID: $zoneId"
            }
        }
    }
    
    fun toTimeZone(): TimeZone = when (this) {
        is SystemDefault -> TimeZone.currentSystemDefault()
        is Specific -> TimeZone.of(zoneId)
    }
}

/**
 * Day of week for fixed point recurrence
 */
@Serializable
enum class RecurrenceDayOfWeek {
    MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY;
    
    fun toKotlinxDayOfWeek(): DayOfWeek = when (this) {
        MONDAY -> DayOfWeek.MONDAY
        TUESDAY -> DayOfWeek.TUESDAY
        WEDNESDAY -> DayOfWeek.WEDNESDAY
        THURSDAY -> DayOfWeek.THURSDAY
        FRIDAY -> DayOfWeek.FRIDAY
        SATURDAY -> DayOfWeek.SATURDAY
        SUNDAY -> DayOfWeek.SUNDAY
    }
    
    companion object {
        fun fromKotlinxDayOfWeek(dow: DayOfWeek): RecurrenceDayOfWeek = when (dow) {
            DayOfWeek.MONDAY -> MONDAY
            DayOfWeek.TUESDAY -> TUESDAY
            DayOfWeek.WEDNESDAY -> WEDNESDAY
            DayOfWeek.THURSDAY -> THURSDAY
            DayOfWeek.FRIDAY -> FRIDAY
            DayOfWeek.SATURDAY -> SATURDAY
            DayOfWeek.SUNDAY -> SUNDAY
        }
    }
}

/**
 * Month for fixed point recurrence
 */
@Serializable
enum class RecurrenceMonth {
    JANUARY, FEBRUARY, MARCH, APRIL, MAY, JUNE,
    JULY, AUGUST, SEPTEMBER, OCTOBER, NOVEMBER, DECEMBER;
    
    fun toKotlinxMonth(): Month = when (this) {
        JANUARY -> Month.JANUARY
        FEBRUARY -> Month.FEBRUARY
        MARCH -> Month.MARCH
        APRIL -> Month.APRIL
        MAY -> Month.MAY
        JUNE -> Month.JUNE
        JULY -> Month.JULY
        AUGUST -> Month.AUGUST
        SEPTEMBER -> Month.SEPTEMBER
        OCTOBER -> Month.OCTOBER
        NOVEMBER -> Month.NOVEMBER
        DECEMBER -> Month.DECEMBER
    }
    
    companion object {
        fun fromKotlinxMonth(month: Month): RecurrenceMonth = when (month) {
            Month.JANUARY -> JANUARY
            Month.FEBRUARY -> FEBRUARY
            Month.MARCH -> MARCH
            Month.APRIL -> APRIL
            Month.MAY -> MAY
            Month.JUNE -> JUNE
            Month.JULY -> JULY
            Month.AUGUST -> AUGUST
            Month.SEPTEMBER -> SEPTEMBER
            Month.OCTOBER -> OCTOBER
            Month.NOVEMBER -> NOVEMBER
            Month.DECEMBER -> DECEMBER
        }
    }
}

/**
 * Ordinal for "nth day of week in month" patterns (e.g., "first Monday", "last Friday")
 */
@Serializable
enum class WeekOrdinal {
    FIRST, SECOND, THIRD, FOURTH, FIFTH, LAST
}

/**
 * Time of day for recurrence (hour, minute, second)
 */
@Serializable
data class TimeOfDay(
    val hour: Int = 0,
    val minute: Int = 0,
    val second: Int = 0
) {
    init {
        require(hour in 0..23) { "Hour must be between 0 and 23" }
        require(minute in 0..59) { "Minute must be between 0 and 59" }
        require(second in 0..59) { "Second must be between 0 and 59" }
    }
    
    companion object {
        val MIDNIGHT = TimeOfDay(0, 0, 0)
        val NOON = TimeOfDay(12, 0, 0)
    }
}

/**
 * Single termination condition for recurrence
 */
@Serializable
sealed class RecurrenceTerminationCondition {
    /**
     * Terminates after a specific number of occurrences
     */
    @Serializable
    data class AfterOccurrences(val count: Int) : RecurrenceTerminationCondition() {
        init {
            require(count > 0) { "Occurrence count must be positive" }
        }
    }

    /**
     * Terminates after a specific date/time
     */
    @Serializable
    data class OnDate(
        @Serializable(with = InstantSerializer::class)
        val endDate: Instant
    ) : RecurrenceTerminationCondition()
}

/**
 * Termination conditions for recurrence.
 * Can contain 0, 1, or 2 conditions. Recurrence terminates when ANY condition is met.
 * Empty list means never terminates.
 */
@Serializable
data class RecurrenceTermination(
    val afterOccurrences: RecurrenceTerminationCondition.AfterOccurrences? = null,
    val onDate: RecurrenceTerminationCondition.OnDate? = null,
): Presentable {
    val maxOccurrences: Int? get() = afterOccurrences?.count

    val endDate: Instant? get() = onDate?.endDate
    override fun toBriefString(): String = toFullString()
    override fun toFullString(): String {
        if (afterOccurrences == null && onDate == null) return "Repeats forever"
        return listOfNotNull(
            afterOccurrences?.count?.let { "after $it occurrence${if (it > 1) "s" else ""}" },
            onDate?.endDate?.let { "on ${formatDate(it)}" }
        ).joinToString(" or ", prefix = "Stops ")
    }

    companion object {
        val Never = RecurrenceTermination()
        fun afterOccurrences(count: Int) = RecurrenceTermination(afterOccurrences = RecurrenceTerminationCondition.AfterOccurrences(count))
        fun onDate(endDate: Instant) = RecurrenceTermination(onDate = RecurrenceTerminationCondition.OnDate(endDate))
    }
}

/**
 * Period-based interval for recurrence (e.g., every 2 weeks, every 3 months)
 */
@Serializable
data class RecurrencePeriod(
    val years: Int = 0,
    val months: Int = 0,
    val weeks: Int = 0,
    val days: Int = 0,
    val hours: Int = 0,
    val minutes: Int = 0,
    val seconds: Int = 0
): Presentable {
    init {
        require(years >= 0 && months >= 0 && weeks >= 0 && days >= 0 && hours >= 0 && minutes >= 0 && seconds >= 0) {
            "All period components must be non-negative"
        }
        require(years > 0 || months > 0 || weeks > 0 || days > 0 || hours > 0 || minutes > 0 || seconds > 0) {
            "At least one period component must be positive"
        }
    }
    
    /**
     * Convert this period to approximate total seconds (for comparisons)
     * Note: Uses approximate values for years (365 days) and months (30 days)
     */
    fun toApproximateSeconds(): Long {
        return years * 365L * 24L * 60L * 60L +
               months * 30L * 24L * 60L * 60L +
               weeks * 7L * 24L * 60L * 60L +
               days * 24L * 60L * 60L +
               hours * 60L * 60L +
               minutes * 60L +
               seconds
    }

    /**
     * Add this period to a LocalDateTime
     */
    fun addTo(dateTime: LocalDateTime): LocalDateTime {
        val date = dateTime.date
        val time = dateTime.time

        // Add date-based components
        val newDate = date
            .plus(years, DateTimeUnit.YEAR)
            .plus(months, DateTimeUnit.MONTH)
            .plus(weeks * 7 + days, DateTimeUnit.DAY)

        // Add time-based components by converting to instant and back
        val tempDateTime = LocalDateTime(newDate, time)
        val tempInstant = tempDateTime.toInstant(TimeZone.UTC)
        val newInstant = tempInstant
            .plus(hours.toLong() * 60 * 60, DateTimeUnit.SECOND, TimeZone.UTC)
            .plus(minutes.toLong() * 60, DateTimeUnit.SECOND, TimeZone.UTC)
            .plus(seconds.toLong(), DateTimeUnit.SECOND, TimeZone.UTC)

        return newInstant.toLocalDateTime(TimeZone.UTC)
    }
    override fun toFullString(): String = buildString {
        val skipNumber = listOf(years, months, weeks, days, hours, minutes, seconds).filter { it != 0 } == listOf(1)
        fun Int.asStringWithSpace() = if (this == 1 && skipNumber) "" else "$this "
        if (years > 0) append("${years.asStringWithSpace()}year${if (years > 1) "s" else ""} ")
        if (months > 0) append("${months.asStringWithSpace()}month${if (months > 1) "s" else ""} ")
        if (weeks > 0) append("${weeks.asStringWithSpace()}week${if (weeks > 1) "s" else ""} ")
        if (days > 0) append("${days.asStringWithSpace()}day${if (days > 1) "s" else ""} ")
        if (hours > 0) append("${hours.asStringWithSpace()}hour${if (hours > 1) "s" else ""} ")
        if (minutes > 0) append("${minutes.asStringWithSpace()}minute${if (minutes > 1) "s" else ""} ")
        if (seconds > 0) append("${seconds.asStringWithSpace()}second${if (seconds > 1) "s" else ""}")
    }.trim().ifEmpty { "0 seconds" }

    override fun toBriefString(): String = buildString {
        if (years > 0) append("${years}y ")
        if (months > 0) append("${months}mo ")
        if (weeks > 0) append("${weeks}w ")
        if (days > 0) append("${days}d ")
        if (hours > 0) append("${hours}h ")
        if (minutes > 0) append("${minutes}m ")
        if (seconds > 0) append("${seconds}s")
    }.trim().ifEmpty { "0s" }

    
    companion object {
        fun ofDays(days: Int) = RecurrencePeriod(days = days)
        fun ofWeeks(weeks: Int) = RecurrencePeriod(weeks = weeks)
        fun ofMonths(months: Int) = RecurrencePeriod(months = months)
        fun ofYears(years: Int) = RecurrencePeriod(years = years)
        fun ofHours(hours: Int) = RecurrencePeriod(hours = hours)
    }
}

/**
 * Fixed point specification for calendar-based recurrence patterns
 */
@Serializable
sealed class FixedPointPattern : Presentable {
    abstract val timeOfDay: TimeOfDay
    /**
     * Specific days of the week (e.g., every Tuesday and Thursday)
     */
    @Serializable
    data class DaysOfWeek(
        val days: Set<RecurrenceDayOfWeek>,
        override val timeOfDay: TimeOfDay = TimeOfDay.MIDNIGHT
    ) : FixedPointPattern() {
        init {
            require(days.isNotEmpty()) { "At least one day must be specified" }
        }

        override fun toFullString(): String {
            val timeStr = " at ${timeOfDay.hour.toString().padStart(2, '0')}:${timeOfDay.minute.toString().padStart(2, '0')}"
            val dayNames = days.map { it.name.lowercase().replaceFirstChar(Char::uppercaseChar) }
            val daysText = when (dayNames.size) {
                1 -> "Every ${dayNames.first()}"
                else -> "Every ${dayNames.dropLast(1).joinToString(", ")} and ${dayNames.last()}"
            }
            return daysText + timeStr
        }

        override fun toBriefString(): String {
            val dayAbbrevs = days.map { it.name.take(3) }
            return if (dayAbbrevs.size <= 3) dayAbbrevs.joinToString(", ") else "Weekly"
        }
    }
    
    /**
     * Specific day of the month (e.g., every 15th)
     * If the day doesn't exist in a month (e.g., 31st in February), uses last day of month
     */
    @Serializable
    data class DayOfMonth(
        val dayOfMonth: Int,
        override val timeOfDay: TimeOfDay = TimeOfDay.MIDNIGHT
    ) : FixedPointPattern() {
        init {
            require(dayOfMonth in 1..31) { "Day of month must be between 1 and 31" }
        }

        override fun toFullString(): String {
            val timeStr = " at ${timeOfDay.hour.toString().padStart(2, '0')}:${timeOfDay.minute.toString().padStart(2, '0')}"
            val suffix = getOrdinalSuffix(dayOfMonth)
            return "Every ${dayOfMonth}$suffix of the month" + timeStr
        }

        override fun toBriefString(): String = "${dayOfMonth}${getOrdinalSuffix(dayOfMonth)} every month"
    }
    protected fun getOrdinalSuffix(ordinal: Int): String = when {
        ordinal in 11..13 -> "th"
        ordinal % 10 == 1 -> "st"
        ordinal % 10 == 2 -> "nd"
        ordinal % 10 == 3 -> "rd"
        else -> "th"
    }


    /**
     * Nth day of week in a month (e.g., first Monday, last Friday)
     */
    @Serializable
    data class NthDayOfWeekInMonth(
        val ordinal: WeekOrdinal,
        val dayOfWeek: RecurrenceDayOfWeek,
        override val timeOfDay: TimeOfDay = TimeOfDay.MIDNIGHT
    ) : FixedPointPattern() {
        override fun toFullString(): String {
            val timeStr = " at ${timeOfDay.hour.toString().padStart(2, '0')}:${timeOfDay.minute.toString().padStart(2, '0')}"
            val ordinalName = ordinal.name.lowercase()
            val dayName = dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercaseChar)
            return "Every $ordinalName $dayName of the month$timeStr"
        }

        override fun toBriefString(): String {
            val ordinalAbbrev = ordinal.name.lowercase().replaceFirstChar(Char::uppercaseChar)
            val dayAbbrev = dayOfWeek.name.take(3).uppercase()
            return "$ordinalAbbrev $dayAbbrev"
        }
    }
    
    /**
     * Specific months and day (e.g., every January 1st, every March 15th)
     */
    @Serializable
    data class YearlyOnDate(
        val months: Set<RecurrenceMonth>,
        val dayOfMonth: Int,
        override val timeOfDay: TimeOfDay = TimeOfDay.MIDNIGHT
    ) : FixedPointPattern() {
        init {
            require(dayOfMonth in 1..31) { "Day of month must be between 1 and 31" }
        }

        override fun toFullString(): String {
            val timeStr =
                "at ${timeOfDay.hour.toString().padStart(2, '0')}:${timeOfDay.minute.toString().padStart(2, '0')}"
            val monthName = months.joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercaseChar) }
            return "Every $dayOfMonth${getOrdinalSuffix(dayOfMonth)} of $monthName $timeStr"
        }

        override fun toBriefString(): String {
            val monthAbbrev = months.joinToString(", ") { it.name.take(3) }
            return "$dayOfMonth${getOrdinalSuffix(dayOfMonth)} of $monthAbbrev"
        }
    }
    /**
     * Nth day of week in specific months (e.g., first Monday of January and July)
     */
    @Serializable
    data class NthDayOfWeekInMonths(
        val ordinal: WeekOrdinal,
        val dayOfWeek: RecurrenceDayOfWeek,
        val months: Set<RecurrenceMonth>,
        override val timeOfDay: TimeOfDay = TimeOfDay.MIDNIGHT
    ) : FixedPointPattern() {
        init {
            require(months.isNotEmpty()) { "At least one month must be specified" }
        }

        override fun toFullString(): String {
            val timeStr = " at ${timeOfDay.hour.toString().padStart(2, '0')}:${timeOfDay.minute.toString().padStart(2, '0')}"
            val ordinalName = ordinal.name.lowercase()
            val dayName = dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercaseChar)
            val monthNames = months.map { it.name.lowercase().replaceFirstChar(Char::uppercaseChar) }
            return "Every $ordinalName $dayName in ${monthNames.joinToString(", ")}" + timeStr
        }

        override fun toBriefString(): String {
            val ordinalAbbrev = ordinal.name.lowercase()
            val dayAbbrev = dayOfWeek.name.take(3)
            return "Yearly $ordinalAbbrev $dayAbbrev"
        }
    }
}

/**
 * Trigger event that causes recurrence to advance
 */
@Serializable
sealed class RecurrenceTrigger {
    @Serializable
    sealed class TimeRecurrenceTrigger : RecurrenceTrigger() {
        abstract val timezone: RecurrenceTimeZone
    }

    /**
     * Repeats at fixed intervals from the first occurrence
     * Example: Every 2 weeks starting from Jan 1
     */
    @Serializable
    data class AfterTimeout(
        val period: RecurrencePeriod?,
        @Serializable(with = InstantSerializer::class)
        val firstOccurrence: Instant,
        override val timezone: RecurrenceTimeZone = RecurrenceTimeZone.SystemDefault,
    ) : TimeRecurrenceTrigger()

    /**
     * Repeats at fixed calendar points
     * Example: Every Tuesday and Thursday, Every 1st of month
     */
    @Serializable
    data class AtFixedPoints(
        val pattern: FixedPointPattern,
        @Serializable(with = InstantSerializer::class)
        val startFrom: Instant,  // Don't generate occurrences before this
        override val timezone: RecurrenceTimeZone = RecurrenceTimeZone.SystemDefault,
    ) : TimeRecurrenceTrigger()

    /**
     * Triggered when task reaches one of the specified statuses
     */
    @Serializable
    data class StatusChange(val requiredStatuses: Set<TaskStatus>) : RecurrenceTrigger()
}

interface Presentable {
    fun toBriefString(): String
    fun toFullString(): String
}

@Serializable
data class RecurrenceRule(
    val timeRecurrenceTrigger: RecurrenceTrigger.TimeRecurrenceTrigger?,
    val statusChangeTrigger: RecurrenceTrigger.StatusChange?,
    val resetToStatus: TaskStatus,
    val termination: RecurrenceTermination = RecurrenceTermination.Never,
) : Presentable {
    init {
        require(timeRecurrenceTrigger != null || statusChangeTrigger != null) {
            "At least one trigger must be specified"
        }
    }
    override fun toBriefString(): String {
        val timeRecurrenceTriggerString = timeRecurrenceTrigger?.let {
            when (it) {
                is RecurrenceTrigger.AfterTimeout if it.period != null -> "Every ${it.period.toBriefString()}"
                is RecurrenceTrigger.AfterTimeout -> "At ${formatDate(it.firstOccurrence)}"
                is RecurrenceTrigger.AtFixedPoints -> it.pattern.toBriefString()
            }
        }
        val statusChangePrefix = if (timeRecurrenceTriggerString == null) "On " else "$timeRecurrenceTriggerString on "
        return statusChangeTrigger?.requiredStatuses
            ?.joinToString(prefix = statusChangePrefix) { it.toBriefString() }
            ?: timeRecurrenceTriggerString
            ?: error("No recurrence trigger found")
    }

    override fun toFullString(): String {
        val timeRecurrenceTriggerString = timeRecurrenceTrigger?.let {
            when (it) {
                is RecurrenceTrigger.AfterTimeout if it.period != null -> "Every ${it.period.toFullString()} since ${formatDate(it.firstOccurrence)}"
                is RecurrenceTrigger.AfterTimeout -> "At ${formatDate(it.firstOccurrence)}"
                is RecurrenceTrigger.AtFixedPoints -> it.pattern.toFullString() + it.timezoneSuffix()
            }
        }
        val statusChangePrefix =
            if (timeRecurrenceTriggerString == null) "On status " else "$timeRecurrenceTriggerString on status "
        val triggersString = statusChangeTrigger?.requiredStatuses
            ?.joinToString(prefix = statusChangePrefix) { it.displayName }
            ?: timeRecurrenceTriggerString
            ?: error("No recurrence trigger found")
        val resetString = "Reset to status ${resetToStatus.toFullString()}"
        val termination = termination.toFullString()
        return "$triggersString\n$resetString\n$termination"
    }
}

fun RecurrenceRule?.toFullString(): String = this?.toFullString() ?: "Never"

/**
 * State tracking for recurrence
 */
@Serializable
data class RecurrenceState(
    val occurrenceCount: Int = 0,
    @Serializable(with = InstantSerializer::class)
    val lastOccurrenceDate: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    val nextOccurrenceDate: Instant? = null
)

/**
 * Service for calculating next occurrence dates and managing recurrence
 */
object RecurrenceCalculator {
    
    /**
     * Calculate the next occurrence date based on recurrence rule
     * @param rule The recurrence rule
     * @param currentState Current recurrence state
     * @param triggerTime The time when the trigger event occurred
     * @return The next occurrence instant, or null if recurrence has terminated
     */
    fun calculateNextOccurrence(
        rule: RecurrenceRule?,
        currentState: RecurrenceState,
        triggerTime: Instant = kotlin.time.Clock.System.now()
    ): Instant? {
        // Check termination - either condition can terminate the recurrence
        if (rule == null) return null
        val termination = rule.termination
        val maxOccurrences = termination.maxOccurrences
        val endDate = termination.endDate
        if (maxOccurrences != null && currentState.occurrenceCount >= maxOccurrences) {
            return null
        }
        if (endDate != null && triggerTime > endDate) {
            return null
        }

        return when (val trigger = rule.timeRecurrenceTrigger) {
            is RecurrenceTrigger.AfterTimeout -> calculateAfterTimeout(trigger, currentState)
            is RecurrenceTrigger.AtFixedPoints -> calculateAtFixedPoints(trigger, currentState, triggerTime)
            null -> null
        }
    }
    
    private fun calculateAfterTimeout(
        trigger: RecurrenceTrigger.AfterTimeout,
        currentState: RecurrenceState
    ): Instant? {
        val tz = trigger.timezone.toTimeZone()
        val baseDateTime = if (currentState.occurrenceCount == 0) {
            trigger.firstOccurrence.toLocalDateTime(tz)
        } else {
            currentState.lastOccurrenceDate?.toLocalDateTime(tz) 
                ?: trigger.firstOccurrence.toLocalDateTime(tz)
        }
        
        return if (currentState.occurrenceCount == 0) {
            trigger.firstOccurrence
        } else {
            trigger.period?.addTo(baseDateTime)?.toInstant(tz)
        }
    }
    
    private fun calculateAtFixedPoints(
        trigger: RecurrenceTrigger.AtFixedPoints,
        currentState: RecurrenceState,
        fromTime: Instant
    ): Instant {
        val tz = trigger.timezone.toTimeZone()
        // Find the maximum of the three instants
        val searchFrom = listOf(
            trigger.startFrom,
            currentState.lastOccurrenceDate ?: trigger.startFrom,
            fromTime
        ).maxByOrNull { it.toEpochMilliseconds() } ?: fromTime
        
        return when (val pattern = trigger.pattern) {
            is FixedPointPattern.DaysOfWeek -> findNextDayOfWeek(pattern, searchFrom, tz)
            is FixedPointPattern.DayOfMonth -> findNextDayOfMonth(pattern, searchFrom, tz)
            is FixedPointPattern.NthDayOfWeekInMonth -> findNextNthDayOfWeek(pattern, searchFrom, tz)
            is FixedPointPattern.YearlyOnDate -> findNextYearlyDate(pattern, searchFrom, tz)
            is FixedPointPattern.NthDayOfWeekInMonths -> findNextNthDayOfWeekInMonths(pattern, searchFrom, tz)
        }
    }
    
    private fun findNextDayOfWeek(
        pattern: FixedPointPattern.DaysOfWeek,
        from: Instant,
        tz: TimeZone
    ): Instant {
        var currentDate = from.toLocalDateTime(tz).date
        val targetTime = LocalTime(pattern.timeOfDay.hour, pattern.timeOfDay.minute, pattern.timeOfDay.second)
        
        // Check if today qualifies (if current time is before target time)
        val fromDateTime = from.toLocalDateTime(tz)
        val currentDow = RecurrenceDayOfWeek.fromKotlinxDayOfWeek(currentDate.dayOfWeek)
        if (currentDow in pattern.days && isTimeBefore(fromDateTime.time, targetTime)) {
            return LocalDateTime(currentDate, targetTime).toInstant(tz)
        }
        
        // Search forward up to 7 days
        for (i in 1..7) {
            currentDate = currentDate.plus(1, DateTimeUnit.DAY)
            val dow = RecurrenceDayOfWeek.fromKotlinxDayOfWeek(currentDate.dayOfWeek)
            if (dow in pattern.days) {
                return LocalDateTime(currentDate, targetTime).toInstant(tz)
            }
        }
        
        // Should never reach here if pattern.days is not empty
        return LocalDateTime(currentDate, targetTime).toInstant(tz)
    }
    
    private fun isTimeBefore(time1: LocalTime, time2: LocalTime): Boolean {
        if (time1.hour != time2.hour) return time1.hour < time2.hour
        if (time1.minute != time2.minute) return time1.minute < time2.minute
        return time1.second < time2.second
    }
    
    private fun findNextDayOfMonth(
        pattern: FixedPointPattern.DayOfMonth,
        from: Instant,
        tz: TimeZone
    ): Instant {
        val fromDateTime = from.toLocalDateTime(tz)
        var currentDate = fromDateTime.date
        val targetTime = LocalTime(pattern.timeOfDay.hour, pattern.timeOfDay.minute, pattern.timeOfDay.second)
        
        // Try current month
        val targetDayThisMonth = minOf(pattern.dayOfMonth, currentDate.month.length(isLeapYear(currentDate.year)))
        if (currentDate.dayOfMonth < targetDayThisMonth || 
            (currentDate.dayOfMonth == targetDayThisMonth && isTimeBefore(fromDateTime.time, targetTime))) {
            val targetDate = LocalDate(currentDate.year, currentDate.month, targetDayThisMonth)
            return LocalDateTime(targetDate, targetTime).toInstant(tz)
        }
        
        // Move to next month
        val nextMonth = currentDate.plus(1, DateTimeUnit.MONTH)
        val nextMonthStart = LocalDate(nextMonth.year, nextMonth.month, 1)
        val targetDayNextMonth = minOf(pattern.dayOfMonth, nextMonthStart.month.length(isLeapYear(nextMonthStart.year)))
        val targetDate = LocalDate(nextMonthStart.year, nextMonthStart.month, targetDayNextMonth)
        return LocalDateTime(targetDate, targetTime).toInstant(tz)
    }
    
    private fun findNextNthDayOfWeek(
        pattern: FixedPointPattern.NthDayOfWeekInMonth,
        from: Instant,
        tz: TimeZone
    ): Instant {
        val fromDateTime = from.toLocalDateTime(tz)
        var currentMonth = LocalDate(fromDateTime.date.year, fromDateTime.date.month, 1)
        val targetTime = LocalTime(pattern.timeOfDay.hour, pattern.timeOfDay.minute, pattern.timeOfDay.second)
        
        // Check current month
        val targetDate = findNthDayOfWeekInMonth(
            currentMonth.year, currentMonth.month,
            pattern.ordinal, pattern.dayOfWeek
        )
        
        if (targetDate != null) {
            val targetDateTime = LocalDateTime(targetDate, targetTime)
            if (targetDateTime.toInstant(tz) > from) {
                return targetDateTime.toInstant(tz)
            }
        }
        
        // Move to next month
        currentMonth = currentMonth.plus(1, DateTimeUnit.MONTH)
        val nextTargetDate = findNthDayOfWeekInMonth(
            currentMonth.year, currentMonth.month,
            pattern.ordinal, pattern.dayOfWeek
        ) ?: error("Could not find nth day of week in month")

        return LocalDateTime(nextTargetDate, targetTime).toInstant(tz)
    }
    
    private fun findNextYearlyDate(
        pattern: FixedPointPattern.YearlyOnDate,
        from: Instant,
        tz: TimeZone
    ): Instant {
        val fromDateTime = from.toLocalDateTime(tz)
        val targetTime = LocalTime(pattern.timeOfDay.hour, pattern.timeOfDay.minute, pattern.timeOfDay.second)
        val targetMonths = pattern.months.map { it.toKotlinxMonth() }

        return targetMonths.minOf { targetMonth ->
            // Try current year
            val daysInMonth = targetMonth.length(isLeapYear(fromDateTime.year))
            val targetDay = minOf(pattern.dayOfMonth, daysInMonth)
            var targetDate = LocalDate(fromDateTime.year, targetMonth, targetDay)
            var targetDateTime = LocalDateTime(targetDate, targetTime)

            if (targetDateTime.toInstant(tz) > from) {
                return@minOf targetDateTime.toInstant(tz)
            }

            // Move to next year
            val nextYear = fromDateTime.year + 1
            val nextDaysInMonth = targetMonth.length(isLeapYear(nextYear))
            val nextTargetDay = minOf(pattern.dayOfMonth, nextDaysInMonth)
            targetDate = LocalDate(nextYear, targetMonth, nextTargetDay)
            targetDateTime = LocalDateTime(targetDate, targetTime)

            return@minOf targetDateTime.toInstant(tz)
        }
    }
    
    private fun findNextNthDayOfWeekInMonths(
        pattern: FixedPointPattern.NthDayOfWeekInMonths,
        from: Instant,
        tz: TimeZone
    ): Instant {
        val fromDateTime = from.toLocalDateTime(tz)
        val targetTime = LocalTime(pattern.timeOfDay.hour, pattern.timeOfDay.minute, pattern.timeOfDay.second)
        
        // Find the next matching month
        var year = fromDateTime.year
        var month = fromDateTime.month
        
        // Search up to 24 months ahead
        for (i in 0 until 24) {
            val recMonth = RecurrenceMonth.fromKotlinxMonth(month)
            if (recMonth in pattern.months) {
                val targetDate = findNthDayOfWeekInMonth(year, month, pattern.ordinal, pattern.dayOfWeek)
                if (targetDate != null) {
                    val targetDateTime = LocalDateTime(targetDate, targetTime)
                    if (targetDateTime.toInstant(tz) > from) {
                        return targetDateTime.toInstant(tz)
                    }
                }
            }
            
            // Move to next month
            if (month == Month.DECEMBER) {
                month = Month.JANUARY
                year++
            } else {
                month = Month.entries[month.ordinal + 1]
            }
        }
        
        // Fallback - shouldn't reach here normally
        return from
    }
    
    private fun findNthDayOfWeekInMonth(
        year: Int,
        month: Month,
        ordinal: WeekOrdinal,
        dayOfWeek: RecurrenceDayOfWeek
    ): LocalDate? {
        val firstOfMonth = LocalDate(year, month, 1)
        val daysInMonth = month.length(isLeapYear(year))
        val targetDow = dayOfWeek.toKotlinxDayOfWeek()
        
        if (ordinal == WeekOrdinal.LAST) {
            // Find last occurrence
            var lastDate = LocalDate(year, month, daysInMonth)
            while (lastDate.dayOfWeek != targetDow) {
                lastDate = lastDate.minus(1, DateTimeUnit.DAY)
            }
            return lastDate
        }
        
        // Find first occurrence
        var date = firstOfMonth
        while (date.dayOfWeek != targetDow) {
            date = date.plus(1, DateTimeUnit.DAY)
        }
        
        // Add weeks for ordinal
        val weeksToAdd = when (ordinal) {
            WeekOrdinal.FIRST -> 0
            WeekOrdinal.SECOND -> 1
            WeekOrdinal.THIRD -> 2
            WeekOrdinal.FOURTH -> 3
            WeekOrdinal.FIFTH -> 4
            WeekOrdinal.LAST -> 0 // Handled above
        }
        
        date = date.plus(weeksToAdd * 7, DateTimeUnit.DAY)
        
        // Check if still in same month
        return if (date.month == month) date else null
    }
    
    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }
    
    private fun Month.length(isLeapYear: Boolean): Int = when (this) {
        Month.JANUARY -> 31
        Month.FEBRUARY -> if (isLeapYear) 29 else 28
        Month.MARCH -> 31
        Month.APRIL -> 30
        Month.MAY -> 31
        Month.JUNE -> 30
        Month.JULY -> 31
        Month.AUGUST -> 31
        Month.SEPTEMBER -> 30
        Month.OCTOBER -> 31
        Month.NOVEMBER -> 30
        Month.DECEMBER -> 31
    }
    
    /**
     * Check if recurrence should trigger based on an event
     */
    fun shouldTrigger(rule: RecurrenceRule, event: RecurrenceTriggerEvent): Boolean {
        val isStatusOk = when (rule.statusChangeTrigger) {
            is RecurrenceTrigger.StatusChange -> when (event) {
                is RecurrenceTriggerEvent.DateTimeReached -> event.currentStatus in rule.statusChangeTrigger.requiredStatuses
                is RecurrenceTriggerEvent.StatusChanged -> event.newStatus in rule.statusChangeTrigger.requiredStatuses
            }
            null -> true
        }
        val isTimeOk = rule.timeRecurrenceTrigger == null || event is RecurrenceTriggerEvent.DateTimeReached
        return isStatusOk && isTimeOk
    }
}

/**
 * Events that can trigger recurrence advancement
 */
sealed class RecurrenceTriggerEvent {
    data class DateTimeReached(val currentStatus: TaskStatus) : RecurrenceTriggerEvent()
    data class StatusChanged(val newStatus: TaskStatus) : RecurrenceTriggerEvent()
}

/**
 * Result of processing a recurring task
 */
data class RecurrenceResult(
    val updatedRecurrenceState: RecurrenceState,
    val nextOccurrenceDate: Instant?
)

/**
 * Service for managing recurring tasks
 */
object RecurrenceService {
    
    /**
     * Process a recurrence trigger and calculate the next state
     * @param rule The recurrence rule
     * @param currentState The current recurrence state
     * @param triggerEvent The event that triggered this
     * @param triggerTime The time when the trigger occurred
     * @return RecurrenceResult with updated state and next due date
     */
    fun processRecurrence(
        rule: RecurrenceRule?,
        currentState: RecurrenceState,
        triggerEvent: RecurrenceTriggerEvent,
        triggerTime: Instant = kotlin.time.Clock.System.now()
    ): RecurrenceResult {
        if (rule == null) {
            return RecurrenceResult(
                updatedRecurrenceState = currentState,
                nextOccurrenceDate = null
            )
        }

        // Check if this event should trigger recurrence
        if (!RecurrenceCalculator.shouldTrigger(rule, triggerEvent)) {
            return RecurrenceResult(
                updatedRecurrenceState = currentState,
                nextOccurrenceDate = currentState.nextOccurrenceDate
            )
        }

        val newOccurrenceCount = currentState.occurrenceCount + 1
        val newState = currentState.copy(
            occurrenceCount = newOccurrenceCount,
            lastOccurrenceDate = triggerTime
        )

        val nextOccurrence = RecurrenceCalculator.calculateNextOccurrence(
            rule = rule,
            currentState = newState,
            triggerTime = triggerTime
        )

        return RecurrenceResult(
            updatedRecurrenceState = newState.copy(nextOccurrenceDate = nextOccurrence),
            nextOccurrenceDate = nextOccurrence
        )
    }
    
    /**
     * Initialize recurrence state for a new recurring task
     */
    fun initializeRecurrence(rule: RecurrenceRule?): RecurrenceState {

        val firstOccurrence = when (val trigger = rule?.timeRecurrenceTrigger) {
            null -> return RecurrenceState()
            is RecurrenceTrigger.AfterTimeout -> trigger.firstOccurrence
            is RecurrenceTrigger.AtFixedPoints -> RecurrenceCalculator.calculateNextOccurrence(
                rule = rule,
                currentState = RecurrenceState(),
                triggerTime = trigger.startFrom
            )
        }

        return RecurrenceState(
            occurrenceCount = 0,
            lastOccurrenceDate = null,
            nextOccurrenceDate = firstOccurrence
        )
    }
    
    /**
     * Create a copy of a task for the next occurrence
     * Resets status to the initial state while preserving other fields
     */
    fun createNextOccurrence(
        task: Task,
        recurrenceRule: RecurrenceRule,
        newDueDate: Instant?,
        resetToStatus: TaskStatus = TaskStatus.Open
    ): Task {
        return task.copy(
            status = resetToStatus,
            dueDate = newDueDate
        )
    }
}

///**
// * Brief human-readable description of a recurrence rule for task cards
// */
//fun RecurrenceRule?.toBriefString(): String {
//    if (this == null) return ""
//    return triggers.joinToString(" & ") {
//        when (it) {
//            is RecurrenceTrigger.AfterTimeout if it.period != null -> "Every ${it.period.toBriefString()}"
//            is RecurrenceTrigger.AfterTimeout -> "Once"
//            is RecurrenceTrigger.AtFixedPoints -> it.pattern.toBriefString()
//            is RecurrenceTrigger.StatusChange -> "On ${it.requiredStatuses.joinToString()}"
//        }
//    }
//}
//
///**
// * Human-readable description of a recurrence rule
// */
//fun RecurrenceRule?.toDisplayString(): String {
//    if (this == null) return "Does not repeat"
//    return triggers.joinToString("&") { trigger ->
//        when (trigger) {
//            is RecurrenceTrigger.AfterTimeout if trigger.period != null -> {
//                val period = trigger.period
//                val periodStr = buildString {
//                    if (period.years > 0) append("${period.years} year${if (period.years > 1) "s" else ""} ")
//                    if (period.months > 0) append("${period.months} month${if (period.months > 1) "s" else ""} ")
//                    if (period.weeks > 0) append("${period.weeks} week${if (period.weeks > 1) "s" else ""} ")
//                    if (period.days > 0) append("${period.days} day${if (period.days > 1) "s" else ""} ")
//                    if (period.hours > 0) append("${period.hours} hour${if (period.hours > 1) "s" else ""} ")
//                    if (period.minutes > 0) append("${period.minutes} minute${if (period.minutes > 1) "s" else ""} ")
//                    if (period.seconds > 0) append("${period.seconds} second${if (period.seconds > 1) "s" else ""}")
//                }.trim()
//                val triggerInfo = triggerSuffix()
//                "Every $periodStr$triggerInfo\nReset to ${resetToStatus.displayName}${terminationSuffix()}"
//            }
//            is RecurrenceTrigger.AfterTimeout -> listOf("Once", triggerSuffix().takeIf { it.isNotEmpty() })
//                .joinToString(" ", postfix = "\nReset to ${resetToStatus.displayName}${terminationSuffix()}")
//
//            is RecurrenceTrigger.AtFixedPoints -> {
//                val triggerInfo = triggerSuffix()
//                trigger.pattern.toFullString() + "${trigger.timezoneSuffix()}$triggerInfo\nReset to ${resetToStatus.displayName}${terminationSuffix()}"
//            }
//            is RecurrenceTrigger.StatusChange -> "On status ${trigger.requiredStatuses.joinToString()}"
//        }
//    }
//}
//
//private fun RecurrenceRule.triggerSuffix(): String = triggers.filterIsInstance<RecurrenceTrigger.StatusChange>().joinToString {
////    when (it) {
////        is RecurrenceTrigger.StatusChange -> {
//            "\non status ${it.requiredStatuses.joinToString("/") { it.displayName }}"
////        }
////    }
//}

private fun RecurrenceTrigger.AtFixedPoints.timezoneSuffix(): String = when (val tz = timezone) {
    is RecurrenceTimeZone.SystemDefault -> ""
    is RecurrenceTimeZone.Specific -> " (${tz.zoneId})"
}

//private fun RecurrenceRule.terminationSuffix(): String {
//    val parts = mutableListOf<String>()
//    termination.maxOccurrences?.let { parts.add(if (it == 1) "$it time" else "$it times") }
//    termination.endDate?.let { parts.add("${if (parts.isEmpty()) "Until" else "until"} ${formatDate(it)}") }
//    return if (parts.isEmpty()) "" else parts.joinToString(", ", prefix = "\n")
//}

private fun formatDate(instant: Instant): String {
    val dt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = dt.hour.toString().padStart(2, '0')
    val minute = dt.minute.toString().padStart(2, '0')
    return "${dt.month.name.lowercase().replaceFirstChar(Char::uppercaseChar)} ${dt.day}, ${dt.year} at $hour:$minute"
}
