@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.parseCompactTimeToPeriod
import com.zhelenskiy.zheduler.zheduler.util.formatDueDate
import com.zhelenskiy.zheduler.zheduler.util.formatPeriod
import kotlinx.datetime.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Type of recurrence pattern
 */
enum class RecurrenceType {
    NONE,
    ONCE,
    AFTER_INTERVAL,
    FIXED_DAYS_OF_WEEK,
    FIXED_DAY_OF_MONTH,
    NTH_DAY_OF_WEEK,
    YEARLY_ON_DATE,
    YEARLY_ON_NTH_DAY_OF_WEEK
}

/**
 * Dialog for configuring task recurrence
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurrenceDialog(
    currentRule: RecurrenceRule,
    currentDueDate: Instant?,
    onDismiss: () -> Unit,
    onRecurrenceSelected: (RecurrenceRule) -> Unit
) {
    var selectedType by remember {
        mutableStateOf(
            when (currentRule) {
                is RecurrenceRule.None -> RecurrenceType.NONE
                is RecurrenceRule.Once -> RecurrenceType.ONCE
                is RecurrenceRule.AfterInterval -> RecurrenceType.AFTER_INTERVAL
                is RecurrenceRule.AtFixedPoints -> when (currentRule.pattern) {
                    is FixedPointPattern.DaysOfWeek -> RecurrenceType.FIXED_DAYS_OF_WEEK
                    is FixedPointPattern.DayOfMonth -> RecurrenceType.FIXED_DAY_OF_MONTH
                    is FixedPointPattern.NthDayOfWeekInMonth -> RecurrenceType.NTH_DAY_OF_WEEK
                    is FixedPointPattern.YearlyOnDate -> RecurrenceType.YEARLY_ON_DATE
                    is FixedPointPattern.NthDayOfWeekInMonths -> RecurrenceType.YEARLY_ON_NTH_DAY_OF_WEEK
                }
            }
        )
    }

    // Helper to extract TimeOfDay from pattern
    val initialTimeOfDay = remember(currentRule) {
        when (currentRule) {
            is RecurrenceRule.AtFixedPoints -> when (val p = currentRule.pattern) {
                is FixedPointPattern.DaysOfWeek -> p.timeOfDay
                is FixedPointPattern.DayOfMonth -> p.timeOfDay
                is FixedPointPattern.NthDayOfWeekInMonth -> p.timeOfDay
                is FixedPointPattern.YearlyOnDate -> p.timeOfDay
                is FixedPointPattern.NthDayOfWeekInMonths -> p.timeOfDay
            }
            else -> TimeOfDay(9, 0)
        }
    }

    // Period state (compact format)
    var periodText by remember {
        mutableStateOf(
            when (currentRule) {
                is RecurrenceRule.AfterInterval -> formatPeriod(currentRule.period)
                else -> ""
            }
        )
    }

    // Once scheduled time - default to 9:00 AM next day
    var onceScheduledTime by remember {
        mutableStateOf(
            when (currentRule) {
                is RecurrenceRule.Once -> currentRule.scheduledTime
                else -> {
                    val now = Clock.System.now()
                    val nowLocal = now.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                    val tomorrow = nowLocal.date.plus(1, DateTimeUnit.DAY)
                    val tomorrowAt9am = LocalDateTime(tomorrow, LocalTime(9, 0))
                    tomorrowAt9am.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault())
                }
            }
        )
    }
    var showOnceDatePicker by remember { mutableStateOf(false) }

    // Once reset status
    var onceResetStatus by remember {
        mutableStateOf<TaskStatus>(
            when (currentRule) {
                is RecurrenceRule.Once -> currentRule.resetToStatus
                else -> TaskStatus.Open
            }
        )
    }

    // Once reset status properties for complex statuses
    var onceResetBlockedTaskIds by remember {
        mutableStateOf(
            when (currentRule) {
                is RecurrenceRule.Once -> when (val status = currentRule.resetToStatus) {
                    is TaskStatus.Blocked -> status.blockerTaskIds.joinToString(", ")
                    else -> ""
                }
                else -> ""
            }
        )
    }
    var onceResetBlockedComment by remember {
        mutableStateOf(
            when (currentRule) {
                is RecurrenceRule.Once -> when (val status = currentRule.resetToStatus) {
                    is TaskStatus.Blocked -> status.comment
                    else -> ""
                }
                else -> ""
            }
        )
    }
    var onceResetDeclinedReason by remember {
        mutableStateOf(
            when (currentRule) {
                is RecurrenceRule.Once -> when (val status = currentRule.resetToStatus) {
                    is TaskStatus.Declined -> status.reason
                    else -> ""
                }
                else -> ""
            }
        )
    }

    // Trigger statuses (for tasks, the statuses that trigger recurrence)
    var triggerStatuses by remember {
        mutableStateOf<Set<TaskStatus>>(setOf(TaskStatus.Done))
    }

    // Reset status (what status to set when recurrence happens)
    var resetStatus by remember {
        mutableStateOf<TaskStatus>(TaskStatus.Open)
    }

    // Reset status properties for complex statuses
    var resetBlockedTaskIds by remember { mutableStateOf("") }
    var resetBlockedComment by remember { mutableStateOf("") }
    var resetDeclinedReason by remember { mutableStateOf("") }

    // Days of week state
    var selectedDays by remember {
        mutableStateOf(
            if (currentRule is RecurrenceRule.AtFixedPoints && currentRule.pattern is FixedPointPattern.DaysOfWeek) {
                (currentRule.pattern as FixedPointPattern.DaysOfWeek).days
            } else {
                emptySet()
            }
        )
    }

    // Day of month state
    var dayOfMonth by remember {
        mutableStateOf(
            if (currentRule is RecurrenceRule.AtFixedPoints && currentRule.pattern is FixedPointPattern.DayOfMonth) {
                (currentRule.pattern as FixedPointPattern.DayOfMonth).dayOfMonth.toString()
            } else {
                "1"
            }
        )
    }

    // Nth day of week state (also used for YEARLY_ON_NTH_DAY_OF_WEEK)
    var selectedOrdinal by remember {
        mutableStateOf(
            when {
                currentRule is RecurrenceRule.AtFixedPoints && currentRule.pattern is FixedPointPattern.NthDayOfWeekInMonth ->
                    (currentRule.pattern as FixedPointPattern.NthDayOfWeekInMonth).ordinal
                currentRule is RecurrenceRule.AtFixedPoints && currentRule.pattern is FixedPointPattern.NthDayOfWeekInMonths ->
                    (currentRule.pattern as FixedPointPattern.NthDayOfWeekInMonths).ordinal
                else -> WeekOrdinal.FIRST
            }
        )
    }
    var selectedDayOfWeek by remember {
        mutableStateOf(
            when {
                currentRule is RecurrenceRule.AtFixedPoints && currentRule.pattern is FixedPointPattern.NthDayOfWeekInMonth ->
                    (currentRule.pattern as FixedPointPattern.NthDayOfWeekInMonth).dayOfWeek
                currentRule is RecurrenceRule.AtFixedPoints && currentRule.pattern is FixedPointPattern.NthDayOfWeekInMonths ->
                    (currentRule.pattern as FixedPointPattern.NthDayOfWeekInMonths).dayOfWeek
                else -> RecurrenceDayOfWeek.MONDAY
            }
        )
    }

    // Yearly date state - multi-select months
    var selectedMonths by remember {
        mutableStateOf(
            when {
                currentRule is RecurrenceRule.AtFixedPoints && currentRule.pattern is FixedPointPattern.YearlyOnDate ->
                    setOf((currentRule.pattern as FixedPointPattern.YearlyOnDate).month)
                currentRule is RecurrenceRule.AtFixedPoints && currentRule.pattern is FixedPointPattern.NthDayOfWeekInMonths ->
                    (currentRule.pattern as FixedPointPattern.NthDayOfWeekInMonths).months
                else -> setOf(RecurrenceMonth.JANUARY)
            }
        )
    }
    var yearlyDayOfMonth by remember {
        mutableStateOf(
            if (currentRule is RecurrenceRule.AtFixedPoints && currentRule.pattern is FixedPointPattern.YearlyOnDate) {
                (currentRule.pattern as FixedPointPattern.YearlyOnDate).dayOfMonth.toString()
            } else {
                "1"
            }
        )
    }

    // Time of day
    var timeHour by remember { mutableStateOf(initialTimeOfDay.hour) }
    var timeMinute by remember { mutableStateOf(initialTimeOfDay.minute) }
    var showTimePicker by remember { mutableStateOf(false) }

    // Termination state
    var terminationType by remember {
        mutableStateOf(
            when (currentRule.termination) {
                is RecurrenceTermination.Never -> "never"
                is RecurrenceTermination.AfterOccurrences -> "count"
                is RecurrenceTermination.OnDate -> "date"
            }
        )
    }
    var terminationCount by remember {
        mutableStateOf(
            when (val term = currentRule.termination) {
                is RecurrenceTermination.AfterOccurrences -> term.count.toString()
                else -> ""
            }
        )
    }
    var terminationDate by remember {
        mutableStateOf(
            when (val term = currentRule.termination) {
                is RecurrenceTermination.OnDate -> term.endDate
                else -> null
            }
        )
    }
    var showTerminationDatePicker by remember { mutableStateOf(false) }

    // Timezone selection
    var useSystemTimezone by remember {
        mutableStateOf(currentRule.timezone is RecurrenceTimeZone.SystemDefault)
    }
    var selectedTimezone by remember {
        mutableStateOf(
            when (val tz = currentRule.timezone) {
                is RecurrenceTimeZone.SystemDefault -> TimeZone.currentSystemDefault().id
                is RecurrenceTimeZone.Specific -> tz.zoneId
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Recurrence") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Recurrence type selection
                Text("Repeat", style = MaterialTheme.typography.labelLarge)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedType == RecurrenceType.NONE,
                        onClick = { selectedType = RecurrenceType.NONE },
                        label = { Text("None", style = MaterialTheme.typography.labelSmall) }
                    )

                    FilterChip(
                        selected = selectedType == RecurrenceType.ONCE,
                        onClick = { selectedType = RecurrenceType.ONCE },
                        label = { Text("Once", style = MaterialTheme.typography.labelSmall) }
                    )

                    FilterChip(
                        selected = selectedType == RecurrenceType.AFTER_INTERVAL,
                        onClick = { selectedType = RecurrenceType.AFTER_INTERVAL },
                        label = { Text("After interval", style = MaterialTheme.typography.labelSmall) }
                    )

                    FilterChip(
                        selected = selectedType == RecurrenceType.FIXED_DAYS_OF_WEEK,
                        onClick = { selectedType = RecurrenceType.FIXED_DAYS_OF_WEEK },
                        label = { Text("Weekly", style = MaterialTheme.typography.labelSmall) }
                    )

                    FilterChip(
                        selected = selectedType == RecurrenceType.FIXED_DAY_OF_MONTH,
                        onClick = { selectedType = RecurrenceType.FIXED_DAY_OF_MONTH },
                        label = { Text("Monthly", style = MaterialTheme.typography.labelSmall) }
                    )

                    FilterChip(
                        selected = selectedType == RecurrenceType.NTH_DAY_OF_WEEK,
                        onClick = { selectedType = RecurrenceType.NTH_DAY_OF_WEEK },
                        label = { Text("Monthly (weekday)", style = MaterialTheme.typography.labelSmall) }
                    )

                    FilterChip(
                        selected = selectedType == RecurrenceType.YEARLY_ON_DATE,
                        onClick = { selectedType = RecurrenceType.YEARLY_ON_DATE },
                        label = { Text("Yearly", style = MaterialTheme.typography.labelSmall) }
                    )

                    FilterChip(
                        selected = selectedType == RecurrenceType.YEARLY_ON_NTH_DAY_OF_WEEK,
                        onClick = { selectedType = RecurrenceType.YEARLY_ON_NTH_DAY_OF_WEEK },
                        label = { Text("Yearly (weekday)", style = MaterialTheme.typography.labelSmall) }
                    )
                }

                AnimatedVisibility(visible = selectedType != RecurrenceType.NONE) {
                    HorizontalDivider()
                }

                // Type-specific configuration
                AnimatedContent(targetState = selectedType) { type ->
                    when (type) {
                        RecurrenceType.NONE -> {
                            // No additional configuration needed
                        }

                        RecurrenceType.ONCE -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Scheduled datetime picker
                                Text("Scheduled Time:", style = MaterialTheme.typography.labelMedium)
                                OutlinedButton(
                                    onClick = { showOnceDatePicker = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(formatDueDate(onceScheduledTime))
                                }

                                // Reset status selection
                                Text("Reset to status:", style = MaterialTheme.typography.labelMedium)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        TaskStatus.Open,
                                        TaskStatus.InProgress,
                                        TaskStatus.Blocked(emptySet()),
                                        TaskStatus.Done,
                                        TaskStatus.Declined("")
                                    ).forEach { status ->
                                        FilterChip(
                                            selected = onceResetStatus::class == status::class,
                                            onClick = { onceResetStatus = status },
                                            label = { Text(status.displayName, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }
                                Text(
                                    "Status to set when scheduled time is reached",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Complex status properties
                                AnimatedVisibility(visible = onceResetStatus is TaskStatus.Blocked) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = onceResetBlockedTaskIds,
                                            onValueChange = { onceResetBlockedTaskIds = it },
                                            label = { Text("Blocked by (Task IDs)", style = MaterialTheme.typography.labelSmall) },
                                            placeholder = { Text("e.g., TASK-100, TASK-200", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )
                                        OutlinedTextField(
                                            value = onceResetBlockedComment,
                                            onValueChange = { onceResetBlockedComment = it },
                                            label = { Text("Comment", style = MaterialTheme.typography.labelSmall) },
                                            placeholder = { Text("Optional comment", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                AnimatedVisibility(visible = onceResetStatus is TaskStatus.Declined) {
                                    OutlinedTextField(
                                        value = onceResetDeclinedReason,
                                        onValueChange = { onceResetDeclinedReason = it },
                                        label = { Text("Decline reason", style = MaterialTheme.typography.labelSmall) },
                                        placeholder = { Text("Reason for declining", style = MaterialTheme.typography.bodySmall) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        RecurrenceType.AFTER_INTERVAL -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = periodText,
                                    onValueChange = { periodText = it },
                                    label = { Text("Interval (e.g., 1w 2d 3h)") },
                                    placeholder = { Text("1w", style = MaterialTheme.typography.bodySmall) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    isError = periodText.isNotBlank() && parseCompactTimeToPeriod(periodText) == null
                                )
                            }
                        }

                        RecurrenceType.FIXED_DAYS_OF_WEEK -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Select days:", style = MaterialTheme.typography.labelMedium)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    RecurrenceDayOfWeek.entries.forEach { day ->
                                        FilterChip(
                                            selected = day in selectedDays,
                                            onClick = {
                                                selectedDays = if (day in selectedDays) {
                                                    selectedDays - day
                                                } else {
                                                    selectedDays + day
                                                }
                                            },
                                            label = { Text(day.name.take(3)) }
                                        )
                                    }
                                }

                                TimeOfDayInput(
                                    hour = timeHour,
                                    minute = timeMinute,
                                    onTimeClick = { showTimePicker = true },
                                    useSystemTimezone = useSystemTimezone,
                                    onTimezoneToggle = { useSystemTimezone = it },
                                    selectedTimezone = selectedTimezone,
                                    onTimezoneSelected = { selectedTimezone = it }
                                )
                            }
                        }

                        RecurrenceType.FIXED_DAY_OF_MONTH -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = dayOfMonth,
                                    onValueChange = { dayOfMonth = it.filter { c -> c.isDigit() }.take(2) },
                                    label = { Text("Day of month (1-31)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                TimeOfDayInput(
                                    hour = timeHour,
                                    minute = timeMinute,
                                    onTimeClick = { showTimePicker = true },
                                    useSystemTimezone = useSystemTimezone,
                                    onTimezoneToggle = { useSystemTimezone = it },
                                    selectedTimezone = selectedTimezone,
                                    onTimezoneSelected = { selectedTimezone = it }
                                )
                            }
                        }

                        RecurrenceType.NTH_DAY_OF_WEEK -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                WeekOrdinalDropdown(
                                    selectedOrdinal = selectedOrdinal,
                                    onOrdinalSelected = { selectedOrdinal = it }
                                )

                                DayOfWeekDropdown(
                                    selectedDayOfWeek = selectedDayOfWeek,
                                    onDayOfWeekSelected = { selectedDayOfWeek = it }
                                )

                                TimeOfDayInput(
                                    hour = timeHour,
                                    minute = timeMinute,
                                    onTimeClick = { showTimePicker = true },
                                    useSystemTimezone = useSystemTimezone,
                                    onTimezoneToggle = { useSystemTimezone = it },
                                    selectedTimezone = selectedTimezone,
                                    onTimezoneSelected = { selectedTimezone = it }
                                )
                            }
                        }

                        RecurrenceType.YEARLY_ON_DATE -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Month multi-select chips
                                Text("Months:", style = MaterialTheme.typography.labelMedium)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    RecurrenceMonth.entries.forEach { month ->
                                        FilterChip(
                                            selected = month in selectedMonths,
                                            onClick = {
                                                selectedMonths = if (month in selectedMonths && selectedMonths.size > 1) {
                                                    selectedMonths - month
                                                } else {
                                                    selectedMonths + month
                                                }
                                            },
                                            label = { Text(month.name.take(3)) }
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = yearlyDayOfMonth,
                                    onValueChange = { yearlyDayOfMonth = it.filter { c -> c.isDigit() }.take(2) },
                                    label = { Text("Day of month (1-31)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                TimeOfDayInput(
                                    hour = timeHour,
                                    minute = timeMinute,
                                    onTimeClick = { showTimePicker = true },
                                    useSystemTimezone = useSystemTimezone,
                                    onTimezoneToggle = { useSystemTimezone = it },
                                    selectedTimezone = selectedTimezone,
                                    onTimezoneSelected = { selectedTimezone = it }
                                )
                            }
                        }

                        RecurrenceType.YEARLY_ON_NTH_DAY_OF_WEEK -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                WeekOrdinalDropdown(
                                    selectedOrdinal = selectedOrdinal,
                                    onOrdinalSelected = { selectedOrdinal = it }
                                )

                                DayOfWeekDropdown(
                                    selectedDayOfWeek = selectedDayOfWeek,
                                    onDayOfWeekSelected = { selectedDayOfWeek = it }
                                )

                                // Month multi-select chips
                                Text("of Months:", style = MaterialTheme.typography.labelMedium)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    RecurrenceMonth.entries.forEach { month ->
                                        FilterChip(
                                            selected = month in selectedMonths,
                                            onClick = {
                                                selectedMonths = if (month in selectedMonths && selectedMonths.size > 1) {
                                                    selectedMonths - month
                                                } else {
                                                    selectedMonths + month
                                                }
                                            },
                                            label = { Text(month.name.take(3)) }
                                        )
                                    }
                                }

                                TimeOfDayInput(
                                    hour = timeHour,
                                    minute = timeMinute,
                                    onTimeClick = { showTimePicker = true },
                                    useSystemTimezone = useSystemTimezone,
                                    onTimezoneToggle = { useSystemTimezone = it },
                                    selectedTimezone = selectedTimezone,
                                    onTimezoneSelected = { selectedTimezone = it }
                                )
                            }
                        }
                    }
                }

                // Trigger and state management (if not NONE or ONCE - ONCE has its own reset status)
                AnimatedVisibility(visible = selectedType != RecurrenceType.NONE && selectedType != RecurrenceType.ONCE) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HorizontalDivider()

                        // Trigger statuses (only for interval-based recurrence)
                        if (selectedType == RecurrenceType.AFTER_INTERVAL) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Trigger on status change", style = MaterialTheme.typography.labelLarge)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        TaskStatus.Open,
                                        TaskStatus.InProgress,
                                        TaskStatus.Blocked(emptySet()),
                                        TaskStatus.Done,
                                        TaskStatus.Declined("")
                                    ).forEach { status ->
                                        val isSelected = triggerStatuses.any { it::class == status::class }
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                triggerStatuses = if (isSelected && triggerStatuses.size > 1) {
                                                    triggerStatuses.filterNot { it::class == status::class }.toSet()
                                                } else {
                                                    triggerStatuses + status
                                                }
                                            },
                                            label = { Text(status.displayName, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }
                                Text(
                                    "When task reaches these statuses, wait the interval then reset",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Reset status
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Reset to status", style = MaterialTheme.typography.labelLarge)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    TaskStatus.Open,
                                    TaskStatus.InProgress,
                                    TaskStatus.Blocked(emptySet()),
                                    TaskStatus.Done,
                                    TaskStatus.Declined("")
                                ).forEach { status ->
                                    FilterChip(
                                        selected = resetStatus::class == status::class,
                                        onClick = { resetStatus = status },
                                        label = { Text(status.displayName, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                            Text(
                                "Status to set when recurrence creates next occurrence",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Complex status properties
                            AnimatedVisibility(visible = resetStatus is TaskStatus.Blocked) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = resetBlockedTaskIds,
                                        onValueChange = { resetBlockedTaskIds = it },
                                        label = { Text("Blocked by (Task IDs)", style = MaterialTheme.typography.labelSmall) },
                                        placeholder = { Text("e.g., TASK-100, TASK-200", style = MaterialTheme.typography.bodySmall) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall
                                    )
                                    OutlinedTextField(
                                        value = resetBlockedComment,
                                        onValueChange = { resetBlockedComment = it },
                                        label = { Text("Comment", style = MaterialTheme.typography.labelSmall) },
                                        placeholder = { Text("Optional comment", style = MaterialTheme.typography.bodySmall) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            AnimatedVisibility(visible = resetStatus is TaskStatus.Declined) {
                                OutlinedTextField(
                                    value = resetDeclinedReason,
                                    onValueChange = { resetDeclinedReason = it },
                                    label = { Text("Decline reason", style = MaterialTheme.typography.labelSmall) },
                                    placeholder = { Text("Reason for declining", style = MaterialTheme.typography.bodySmall) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        HorizontalDivider()
                        Text("Ends", style = MaterialTheme.typography.labelLarge)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = terminationType == "never",
                                onClick = { terminationType = "never" },
                                label = { Text("Never", style = MaterialTheme.typography.labelSmall) }
                            )

                            FilterChip(
                                selected = terminationType == "count",
                                onClick = { terminationType = "count" },
                                label = { Text("After N times", style = MaterialTheme.typography.labelSmall) }
                            )

                            FilterChip(
                                selected = terminationType == "date",
                                onClick = { terminationType = "date" },
                                label = { Text("On time", style = MaterialTheme.typography.labelSmall) }
                            )
                        }

                        AnimatedVisibility(visible = terminationType == "count") {
                            OutlinedTextField(
                                value = terminationCount,
                                onValueChange = { terminationCount = it.filter { c -> c.isDigit() } },
                                label = { Text("Number of occurrences") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        AnimatedVisibility(visible = terminationType == "date") {
                            OutlinedButton(
                                onClick = { showTerminationDatePicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(terminationDate?.let { formatDueDate(it) } ?: "Select end time")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val rule = buildRecurrenceRule(
                        type = selectedType,
                        periodText = periodText,
                        triggerStatuses = triggerStatuses,
                        selectedDays = selectedDays,
                        dayOfMonth = dayOfMonth.toIntOrNull() ?: 1,
                        selectedOrdinal = selectedOrdinal,
                        selectedDayOfWeek = selectedDayOfWeek,
                        selectedMonths = selectedMonths,
                        yearlyDayOfMonth = yearlyDayOfMonth.toIntOrNull() ?: 1,
                        timeHour = timeHour,
                        timeMinute = timeMinute,
                        terminationType = terminationType,
                        terminationCount = terminationCount.toIntOrNull(),
                        terminationDate = terminationDate,
                        currentDueDate = currentDueDate,
                        useSystemTimezone = useSystemTimezone,
                        selectedTimezone = selectedTimezone,
                        onceScheduledTime = onceScheduledTime,
                        onceResetStatus = onceResetStatus,
                        onceResetBlockedTaskIds = onceResetBlockedTaskIds,
                        onceResetBlockedComment = onceResetBlockedComment,
                        onceResetDeclinedReason = onceResetDeclinedReason,
                        clock = Clock.System
                    )
                    onRecurrenceSelected(rule)
                },
                enabled = isValidConfiguration(
                    type = selectedType,
                    periodText = periodText,
                    triggerStatuses = triggerStatuses,
                    selectedDays = selectedDays,
                    dayOfMonth = dayOfMonth.toIntOrNull(),
                    yearlyDayOfMonth = yearlyDayOfMonth.toIntOrNull(),
                    terminationType = terminationType,
                    terminationCount = terminationCount.toIntOrNull()
                )
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    if (showTerminationDatePicker) {
        DatePickerDialog(
            currentDate = terminationDate,
            onDismiss = { showTerminationDatePicker = false },
            onDateSelected = { date ->
                terminationDate = date
                showTerminationDatePicker = false
            }
        )
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialHour = timeHour,
            initialMinute = timeMinute,
            onDismiss = { showTimePicker = false },
            onTimeSelected = { hour, minute ->
                timeHour = hour
                timeMinute = minute
                showTimePicker = false
            }
        )
    }

    if (showOnceDatePicker) {
        DatePickerDialog(
            currentDate = onceScheduledTime,
            onDismiss = { showOnceDatePicker = false },
            onDateSelected = { date ->
                if (date != null) {
                    onceScheduledTime = date
                }
                showOnceDatePicker = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onTimeSelected: (Int, Int) -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Time") },
        text = {
            TimePicker(state = timePickerState, layoutType = TimePickerLayoutType.Vertical)
        },
        confirmButton = {
            TextButton(onClick = {
                onTimeSelected(timePickerState.hour, timePickerState.minute)
            }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Reusable dropdown for selecting a WeekOrdinal (First, Second, Third, etc.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekOrdinalDropdown(
    selectedOrdinal: WeekOrdinal,
    onOrdinalSelected: (WeekOrdinal) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedOrdinal.name.lowercase().replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("Which") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            WeekOrdinal.entries.forEach { ordinal ->
                DropdownMenuItem(
                    text = { Text(ordinal.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onOrdinalSelected(ordinal)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Reusable dropdown for selecting a RecurrenceDayOfWeek
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayOfWeekDropdown(
    selectedDayOfWeek: RecurrenceDayOfWeek,
    onDayOfWeekSelected: (RecurrenceDayOfWeek) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedDayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("Day") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            RecurrenceDayOfWeek.entries.forEach { day ->
                DropdownMenuItem(
                    text = { Text(day.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onDayOfWeekSelected(day)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeOfDayInput(
    hour: Int,
    minute: Int,
    onTimeClick: () -> Unit,
    useSystemTimezone: Boolean,
    onTimezoneToggle: (Boolean) -> Unit,
    selectedTimezone: String,
    onTimezoneSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Time:", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = onTimeClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = useSystemTimezone,
                onCheckedChange = onTimezoneToggle
            )
            Text(
                "Use system timezone",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AnimatedVisibility(visible = !useSystemTimezone) {
            var timezoneExpanded by remember { mutableStateOf(false) }
            val commonTimezones = remember {
                listOf(
                    // UTC
                    "UTC",
                    // North America - USA
                    "America/New_York",
                    "America/Detroit",
                    "America/Chicago",
                    "America/Indianapolis",
                    "America/Denver",
                    "America/Phoenix",
                    "America/Los_Angeles",
                    "America/Anchorage",
                    "America/Juneau",
                    "America/Honolulu",
                    // North America - Canada
                    "America/Toronto",
                    "America/Vancouver",
                    "America/Montreal",
                    "America/Edmonton",
                    "America/Calgary",
                    "America/Winnipeg",
                    "America/Halifax",
                    "America/St_Johns",
                    // Central America & Caribbean
                    "America/Mexico_City",
                    "America/Tijuana",
                    "America/Cancun",
                    "America/Guatemala",
                    "America/Panama",
                    "America/Havana",
                    "America/Jamaica",
                    "America/Puerto_Rico",
                    "America/Costa_Rica",
                    // South America
                    "America/Bogota",
                    "America/Lima",
                    "America/Quito",
                    "America/Santiago",
                    "America/Buenos_Aires",
                    "America/Sao_Paulo",
                    "America/Rio_Branco",
                    "America/Manaus",
                    "America/Caracas",
                    "America/La_Paz",
                    "America/Montevideo",
                    "America/Asuncion",
                    // Western Europe
                    "Europe/London",
                    "Europe/Dublin",
                    "Europe/Lisbon",
                    "Europe/Paris",
                    "Europe/Madrid",
                    "Europe/Barcelona",
                    "Europe/Berlin",
                    "Europe/Amsterdam",
                    "Europe/Brussels",
                    "Europe/Luxembourg",
                    "Europe/Zurich",
                    "Europe/Vienna",
                    "Europe/Rome",
                    "Europe/Milan",
                    "Europe/Monaco",
                    // Central Europe
                    "Europe/Prague",
                    "Europe/Warsaw",
                    "Europe/Budapest",
                    "Europe/Bratislava",
                    "Europe/Ljubljana",
                    "Europe/Zagreb",
                    "Europe/Belgrade",
                    "Europe/Sarajevo",
                    "Europe/Skopje",
                    "Europe/Podgorica",
                    "Europe/Tirana",
                    // Northern Europe
                    "Europe/Stockholm",
                    "Europe/Oslo",
                    "Europe/Copenhagen",
                    "Europe/Helsinki",
                    "Europe/Tallinn",
                    "Europe/Riga",
                    "Europe/Vilnius",
                    "Europe/Reykjavik",
                    // Eastern Europe
                    "Europe/Athens",
                    "Europe/Bucharest",
                    "Europe/Sofia",
                    "Europe/Kyiv",
                    "Europe/Chisinau",
                    "Europe/Moscow",
                    "Europe/Minsk",
                    "Europe/Kaliningrad",
                    "Europe/Samara",
                    "Europe/Istanbul",
                    // Africa - North
                    "Africa/Cairo",
                    "Africa/Casablanca",
                    "Africa/Tunis",
                    "Africa/Algiers",
                    "Africa/Tripoli",
                    // Africa - West
                    "Africa/Lagos",
                    "Africa/Accra",
                    "Africa/Abidjan",
                    "Africa/Dakar",
                    // Africa - East
                    "Africa/Nairobi",
                    "Africa/Addis_Ababa",
                    "Africa/Dar_es_Salaam",
                    "Africa/Kampala",
                    "Africa/Khartoum",
                    // Africa - South
                    "Africa/Johannesburg",
                    "Africa/Cape_Town",
                    "Africa/Harare",
                    "Africa/Lusaka",
                    // Middle East
                    "Asia/Dubai",
                    "Asia/Abu_Dhabi",
                    "Asia/Riyadh",
                    "Asia/Jeddah",
                    "Asia/Tehran",
                    "Asia/Jerusalem",
                    "Asia/Tel_Aviv",
                    "Asia/Beirut",
                    "Asia/Damascus",
                    "Asia/Amman",
                    "Asia/Baghdad",
                    "Asia/Kuwait",
                    "Asia/Qatar",
                    "Asia/Bahrain",
                    "Asia/Muscat",
                    // Central Asia
                    "Asia/Almaty",
                    "Asia/Tashkent",
                    "Asia/Bishkek",
                    "Asia/Dushanbe",
                    "Asia/Ashgabat",
                    "Asia/Baku",
                    "Asia/Tbilisi",
                    "Asia/Yerevan",
                    // South Asia
                    "Asia/Kolkata",
                    "Asia/Mumbai",
                    "Asia/Delhi",
                    "Asia/Bangalore",
                    "Asia/Chennai",
                    "Asia/Dhaka",
                    "Asia/Karachi",
                    "Asia/Lahore",
                    "Asia/Colombo",
                    "Asia/Kathmandu",
                    "Asia/Thimphu",
                    // Southeast Asia
                    "Asia/Bangkok",
                    "Asia/Jakarta",
                    "Asia/Singapore",
                    "Asia/Kuala_Lumpur",
                    "Asia/Ho_Chi_Minh",
                    "Asia/Hanoi",
                    "Asia/Manila",
                    "Asia/Phnom_Penh",
                    "Asia/Vientiane",
                    "Asia/Yangon",
                    "Asia/Brunei",
                    // East Asia
                    "Asia/Hong_Kong",
                    "Asia/Macau",
                    "Asia/Taipei",
                    "Asia/Shanghai",
                    "Asia/Beijing",
                    "Asia/Chongqing",
                    "Asia/Seoul",
                    "Asia/Pyongyang",
                    "Asia/Tokyo",
                    "Asia/Osaka",
                    "Asia/Ulaanbaatar",
                    // Russia - Asian
                    "Asia/Vladivostok",
                    "Asia/Yakutsk",
                    "Asia/Irkutsk",
                    "Asia/Krasnoyarsk",
                    "Asia/Novosibirsk",
                    "Asia/Omsk",
                    "Asia/Yekaterinburg",
                    "Asia/Magadan",
                    "Asia/Kamchatka",
                    // Australia
                    "Australia/Perth",
                    "Australia/Adelaide",
                    "Australia/Darwin",
                    "Australia/Brisbane",
                    "Australia/Sydney",
                    "Australia/Melbourne",
                    "Australia/Hobart",
                    "Australia/Canberra",
                    // Pacific
                    "Pacific/Auckland",
                    "Pacific/Wellington",
                    "Pacific/Fiji",
                    "Pacific/Honolulu",
                    "Pacific/Guam",
                    "Pacific/Port_Moresby",
                    "Pacific/Noumea",
                    "Pacific/Tahiti",
                    "Pacific/Samoa",
                    "Pacific/Tongatapu",
                    // Atlantic
                    "Atlantic/Azores",
                    "Atlantic/Canary",
                    "Atlantic/Bermuda",
                    "Atlantic/Reykjavik"
                )
            }

            ExposedDropdownMenuBox(
                expanded = timezoneExpanded,
                onExpandedChange = { timezoneExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedTimezone,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Timezone", style = MaterialTheme.typography.labelSmall) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(timezoneExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                ExposedDropdownMenu(
                    expanded = timezoneExpanded,
                    onDismissRequest = { timezoneExpanded = false }
                ) {
                    commonTimezones.forEach { timezone ->
                        DropdownMenuItem(
                            text = { Text(timezone, style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                onTimezoneSelected(timezone)
                                timezoneExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun isValidConfiguration(
    type: RecurrenceType,
    periodText: String,
    triggerStatuses: Set<TaskStatus>,
    selectedDays: Set<RecurrenceDayOfWeek>,
    dayOfMonth: Int?,
    yearlyDayOfMonth: Int?,
    terminationType: String,
    terminationCount: Int?
): Boolean {
    when (type) {
        RecurrenceType.NONE -> return true
        RecurrenceType.ONCE -> return true // Once is always valid (has a scheduled time)
        RecurrenceType.AFTER_INTERVAL -> {
            if (periodText.isBlank() || parseCompactTimeToPeriod(periodText) == null) {
                return false
            }
        }
        RecurrenceType.FIXED_DAYS_OF_WEEK -> {
            if (selectedDays.isEmpty()) return false
        }
        RecurrenceType.FIXED_DAY_OF_MONTH -> {
            if (dayOfMonth == null || dayOfMonth !in 1..31) return false
        }
        RecurrenceType.NTH_DAY_OF_WEEK -> {
            // Always valid if ordinal and day are selected
        }
        RecurrenceType.YEARLY_ON_DATE -> {
            if (yearlyDayOfMonth == null || yearlyDayOfMonth !in 1..31) return false
        }
        RecurrenceType.YEARLY_ON_NTH_DAY_OF_WEEK -> {
            // Always valid if ordinal, day, and month are selected
        }
    }

    // Only check termination for types that use it (not ONCE)
    if (type != RecurrenceType.ONCE && terminationType == "count" && (terminationCount == null || terminationCount <= 0)) {
        return false
    }

    return true
}

private fun buildRecurrenceRule(
    type: RecurrenceType,
    periodText: String,
    triggerStatuses: Set<TaskStatus>,
    selectedDays: Set<RecurrenceDayOfWeek>,
    dayOfMonth: Int,
    selectedOrdinal: WeekOrdinal,
    selectedDayOfWeek: RecurrenceDayOfWeek,
    selectedMonths: Set<RecurrenceMonth>,
    yearlyDayOfMonth: Int,
    timeHour: Int,
    timeMinute: Int,
    terminationType: String,
    terminationCount: Int?,
    terminationDate: Instant?,
    currentDueDate: Instant?,
    useSystemTimezone: Boolean,
    selectedTimezone: String,
    onceScheduledTime: Instant,
    onceResetStatus: TaskStatus,
    onceResetBlockedTaskIds: String,
    onceResetBlockedComment: String,
    onceResetDeclinedReason: String,
    clock: Clock
): RecurrenceRule {
    val termination = when (terminationType) {
        "count" -> RecurrenceTermination.AfterOccurrences(terminationCount ?: 1)
        "date" -> terminationDate?.let { RecurrenceTermination.OnDate(it) } ?: RecurrenceTermination.Never
        else -> RecurrenceTermination.Never
    }

    val timeOfDay = TimeOfDay(
        hour = timeHour.coerceIn(0, 23),
        minute = timeMinute.coerceIn(0, 59)
    )

    val startFrom = currentDueDate ?: clock.now()

    val timezone = if (useSystemTimezone) {
        RecurrenceTimeZone.SystemDefault
    } else {
        RecurrenceTimeZone.Specific(selectedTimezone)
    }

    return when (type) {
        RecurrenceType.NONE -> RecurrenceRule.None

        RecurrenceType.ONCE -> {
            val finalResetStatus = when (onceResetStatus) {
                is TaskStatus.Blocked -> {
                    val taskIds = onceResetBlockedTaskIds
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    TaskStatus.Blocked(taskIds, onceResetBlockedComment)
                }
                is TaskStatus.Declined -> TaskStatus.Declined(onceResetDeclinedReason)
                else -> onceResetStatus
            }
            RecurrenceRule.Once(
                scheduledTime = onceScheduledTime,
                resetToStatus = finalResetStatus,
                timezone = timezone
            )
        }

        RecurrenceType.AFTER_INTERVAL -> {
            val period = parseCompactTimeToPeriod(periodText) ?: RecurrencePeriod(days = 1)

            RecurrenceRule.AfterInterval(
                period = period,
                firstOccurrence = startFrom,
                termination = termination
            )
        }

        RecurrenceType.FIXED_DAYS_OF_WEEK -> {
            RecurrenceRule.AtFixedPoints(
                pattern = FixedPointPattern.DaysOfWeek(
                    days = selectedDays,
                    timeOfDay = timeOfDay
                ),
                startFrom = startFrom,
                timezone = timezone,
                termination = termination
            )
        }

        RecurrenceType.FIXED_DAY_OF_MONTH -> {
            RecurrenceRule.AtFixedPoints(
                pattern = FixedPointPattern.DayOfMonth(
                    dayOfMonth = dayOfMonth.coerceIn(1, 31),
                    timeOfDay = timeOfDay
                ),
                startFrom = startFrom,
                timezone = timezone,
                termination = termination
            )
        }

        RecurrenceType.NTH_DAY_OF_WEEK -> {
            RecurrenceRule.AtFixedPoints(
                pattern = FixedPointPattern.NthDayOfWeekInMonth(
                    ordinal = selectedOrdinal,
                    dayOfWeek = selectedDayOfWeek,
                    timeOfDay = timeOfDay
                ),
                startFrom = startFrom,
                timezone = timezone,
                termination = termination
            )
        }

        RecurrenceType.YEARLY_ON_DATE -> {
            RecurrenceRule.AtFixedPoints(
                pattern = FixedPointPattern.YearlyOnDate(
                    month = selectedMonths.first(),
                    dayOfMonth = yearlyDayOfMonth.coerceIn(1, 31),
                    timeOfDay = timeOfDay
                ),
                startFrom = startFrom,
                timezone = timezone,
                termination = termination
            )
        }

        RecurrenceType.YEARLY_ON_NTH_DAY_OF_WEEK -> {
            RecurrenceRule.AtFixedPoints(
                pattern = FixedPointPattern.NthDayOfWeekInMonths(
                    ordinal = selectedOrdinal,
                    dayOfWeek = selectedDayOfWeek,
                    months = selectedMonths,
                    timeOfDay = timeOfDay
                ),
                startFrom = startFrom,
                timezone = timezone,
                termination = termination
            )
        }
    }
}

