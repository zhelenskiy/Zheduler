@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.compose.ui.unit.times
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.FixedPointPattern.*
import com.zhelenskiy.zheduler.zheduler.RecurrenceTerminationCondition.AfterOccurrences
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.*
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.StatusChange
import com.zhelenskiy.zheduler.zheduler.components.common.TimeZoneSelector
import com.zhelenskiy.zheduler.zheduler.components.common.icon
import com.zhelenskiy.zheduler.zheduler.components.dialogs.FormResult.NoData
import com.zhelenskiy.zheduler.zheduler.components.dialogs.FormResult.Success
import com.zhelenskiy.zheduler.zheduler.util.TaskStatus
import com.zhelenskiy.zheduler.zheduler.util.formatDueDate
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Type of recurrence pattern
 */
enum class RecurrenceTriggerType {
    AFTER_TIMEOUT,
    FIXED_DAYS_OF_WEEK,
    FIXED_DAY_OF_MONTH,
    NTH_DAY_OF_WEEK,
    YEARLY_ON_DATE,
    YEARLY_ON_NTH_DAY_OF_WEEK
}

sealed class FormResult<out T> {
    data class Success<T>(val value: T) : FormResult<T>()
    data object Failure : FormResult<Nothing>()
    data object NoData : FormResult<Nothing>()
}

@Composable
fun TimeRecurrenceTriggerSelector(
    oldTimeTrigger: TimeRecurrenceTrigger?,
    terminationCount: Int?,
    onTriggerSelected: (FormResult<TimeRecurrenceTrigger>) -> Unit
) {
    var selectedTimeBasedType by remember {
        mutableStateOf(
            when (oldTimeTrigger) {
                null -> null
                is AfterTimeout -> RecurrenceTriggerType.AFTER_TIMEOUT
                is AtFixedPoints -> when (oldTimeTrigger.pattern) {
                    is DaysOfWeek -> RecurrenceTriggerType.FIXED_DAYS_OF_WEEK
                    is DayOfMonth -> RecurrenceTriggerType.FIXED_DAY_OF_MONTH
                    is NthDayOfWeekInMonth -> RecurrenceTriggerType.NTH_DAY_OF_WEEK
                    is YearlyOnDate -> RecurrenceTriggerType.YEARLY_ON_DATE
                    is NthDayOfWeekInMonths -> RecurrenceTriggerType.YEARLY_ON_NTH_DAY_OF_WEEK
                }
            }
        )
    }

    Column {
        TimeBasedTypeSelector(
            selectedTimeBasedType = selectedTimeBasedType,
            onTypeSelected = { selectedTimeBasedType = it }
        )
        AnimatedContent(targetState = selectedTimeBasedType) { type ->
            RecurrenceTypeConfiguration(
                type = type,
                oldTimeTrigger = oldTimeTrigger,
                terminationCount = terminationCount,
                onTriggerSelected = onTriggerSelected,
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun TimeBasedTypeSelector(
    selectedTimeBasedType: RecurrenceTriggerType?,
    onTypeSelected: (RecurrenceTriggerType?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Time-based", style = MaterialTheme.typography.titleSmall)

        FilterChip(
            selected = selectedTimeBasedType == null,
            onClick = { onTypeSelected(null) },
            label = { Text("None", style = MaterialTheme.typography.labelSmall) }
        )

        FilterChip(
            selected = selectedTimeBasedType == RecurrenceTriggerType.AFTER_TIMEOUT,
            onClick = { onTypeSelected(RecurrenceTriggerType.AFTER_TIMEOUT) },
            label = { Text("After timeout", style = MaterialTheme.typography.labelSmall) }
        )

        FilterChip(
            selected = selectedTimeBasedType == RecurrenceTriggerType.FIXED_DAYS_OF_WEEK,
            onClick = { onTypeSelected(RecurrenceTriggerType.FIXED_DAYS_OF_WEEK) },
            label = { Text("Weekly", style = MaterialTheme.typography.labelSmall) }
        )

        FilterChip(
            selected = selectedTimeBasedType == RecurrenceTriggerType.FIXED_DAY_OF_MONTH,
            onClick = { onTypeSelected(RecurrenceTriggerType.FIXED_DAY_OF_MONTH) },
            label = { Text("Monthly", style = MaterialTheme.typography.labelSmall) }
        )

        FilterChip(
            selected = selectedTimeBasedType == RecurrenceTriggerType.NTH_DAY_OF_WEEK,
            onClick = { onTypeSelected(RecurrenceTriggerType.NTH_DAY_OF_WEEK) },
            label = { Text("Monthly (weekday)", style = MaterialTheme.typography.labelSmall) }
        )

        FilterChip(
            selected = selectedTimeBasedType == RecurrenceTriggerType.YEARLY_ON_DATE,
            onClick = { onTypeSelected(RecurrenceTriggerType.YEARLY_ON_DATE) },
            label = { Text("Yearly", style = MaterialTheme.typography.labelSmall) }
        )

        FilterChip(
            selected = selectedTimeBasedType == RecurrenceTriggerType.YEARLY_ON_NTH_DAY_OF_WEEK,
            onClick = { onTypeSelected(RecurrenceTriggerType.YEARLY_ON_NTH_DAY_OF_WEEK) },
            label = { Text("Yearly (weekday)", style = MaterialTheme.typography.labelSmall) }
        )
    }
}

/**
 * Dialog for configuring a single recurrence rule
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleRecurrenceRuleDialog(
    currentRule: RecurrenceRule?,
    filteredTasks: LazyPagingItems<Task>,
    loadedTasks: Map<String, Task>,
    onFilterTasks: (String) -> Unit,
    onLoadTask: (String) -> Unit,
    onDismiss: () -> Unit,
    onRecurrenceSelected: (RecurrenceRule?) -> Unit
) {
    var selectedTimeTrigger by remember {
        mutableStateOf(currentRule?.timeRecurrenceTrigger?.let(::Success) ?: NoData)
    }

    // Trigger statuses (the statuses that trigger recurrence)
    // null means "any status"
    var statusChangesTrigger by remember { mutableStateOf(currentRule?.statusChangeTrigger) }

    // Reset status (what status to set when recurrence happens)
    var resetToStatus by remember { mutableStateOf(currentRule?.resetToStatus ?: TaskStatus.Open) }

    var termination by remember {
        mutableStateOf(
            RecurrenceTermination(
                afterOccurrences = currentRule?.termination?.maxOccurrences?.let(::AfterOccurrences),
                onDate = currentRule?.termination?.endDate?.let(RecurrenceTerminationCondition::OnDate)
            )
        )
    }

    val isTerminationCountValid = termination.afterOccurrences?.let { it.count >= 0 } ?: true
    val isFormValid = selectedTimeTrigger !is FormResult.Failure && isTerminationCountValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure Recurrence Rule") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TriggerSelectors(
                    selectedTimeTrigger = selectedTimeTrigger,
                    onTimeTriggerSelected = { selectedTimeTrigger = it },
                    terminationCount = termination.afterOccurrences?.count,
                    statusChangesTrigger = statusChangesTrigger,
                    onStatusChangeChange = { statusChangesTrigger = it }
                )

                AnimatedVisibility(visible = selectedTimeTrigger !is NoData || statusChangesTrigger != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HorizontalDivider()

                        ResetToStatusButton(
                            selectedStatus = resetToStatus,
                            filteredTasks = filteredTasks,
                            loadedTasks = loadedTasks,
                            onFilterTasks = onFilterTasks,
                            onLoadTask = onLoadTask,
                            onStatusSelected = { resetToStatus = it },
                        )

                        HorizontalDivider()

                        TerminationSettings(
                            termination = termination,
                            onTerminationChange = { termination = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val timeTrigger = (selectedTimeTrigger as? Success)?.value
                    val rule = if (timeTrigger != null || statusChangesTrigger != null) {
                        RecurrenceRule(
                            timeRecurrenceTrigger = timeTrigger,
                            statusChangeTrigger = statusChangesTrigger,
                            resetToStatus = resetToStatus,
                            termination = termination
                        )
                    } else {
                        null
                    }
                    onRecurrenceSelected(rule)
                },
                enabled = isFormValid,
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
            TextButton(onClick = { onTimeSelected(timePickerState.hour, timePickerState.minute) }) {
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
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

@Composable
private fun RecurrenceTypeConfiguration(
    type: RecurrenceTriggerType?,
    oldTimeTrigger: TimeRecurrenceTrigger?,
    terminationCount: Int?,
    onTriggerSelected: (FormResult<TimeRecurrenceTrigger>) -> Unit
) {
    val onTriggerSelectedNullable = { trigger: TimeRecurrenceTrigger? ->
        onTriggerSelected(trigger?.let(::Success) ?: FormResult.Failure)
    }
    when (type) {
        null -> LaunchedEffect(Unit) { onTriggerSelected(NoData) }

        RecurrenceTriggerType.AFTER_TIMEOUT -> AfterTimeoutConfiguration(
            oldTimeTrigger = oldTimeTrigger as? AfterTimeout?,
            terminationCount = terminationCount,
            onTriggerSelected = onTriggerSelectedNullable,
        )

        RecurrenceTriggerType.FIXED_DAYS_OF_WEEK -> FixedDaysOfWeekConfiguration(
            oldTimeTrigger = oldTimeTrigger as? AtFixedPoints?,
            onTriggerSelected = onTriggerSelectedNullable
        )

        RecurrenceTriggerType.FIXED_DAY_OF_MONTH -> FixedDayOfMonthConfiguration(
            oldTimeTrigger = oldTimeTrigger as? AtFixedPoints?,
            onTriggerSelected = onTriggerSelectedNullable,
        )

        RecurrenceTriggerType.NTH_DAY_OF_WEEK -> NthDayOfWeekConfiguration(
            oldTimeTrigger = oldTimeTrigger as? AtFixedPoints?,
            onTriggerSelected = onTriggerSelectedNullable,
        )

        RecurrenceTriggerType.YEARLY_ON_DATE -> YearlyOnDateConfiguration(
            oldTimeTrigger = oldTimeTrigger as? AtFixedPoints?,
            onTriggerSelected = onTriggerSelectedNullable,
        )

        RecurrenceTriggerType.YEARLY_ON_NTH_DAY_OF_WEEK -> YearlyOnNthDayOfWeekConfiguration(
            oldTimeTrigger = oldTimeTrigger as? AtFixedPoints?,
            onTriggerSelected = onTriggerSelectedNullable,
        )
    }
}
@Composable
fun timeSelector(initialTimeOfDay: TimeOfDay?): Pair<TimeOfDay, () -> Unit> {
    var timeOfDay by remember { mutableStateOf(initialTimeOfDay ?: TimeOfDay(9, 0)) }
    var showTimePicker by remember { mutableStateOf(false) }

    if (showTimePicker) {
        TimePickerDialog(
            initialHour = timeOfDay.hour,
            initialMinute = timeOfDay.minute,
            onDismiss = { showTimePicker = false },
            onTimeSelected = { hour, minute ->
                timeOfDay = TimeOfDay(
                    hour = hour.coerceIn(0, 23),
                    minute = minute.coerceIn(0, 59),
                )
                showTimePicker = false
            }
        )
    }
    return timeOfDay to { showTimePicker = true }
}

@Composable
private fun AfterTimeoutConfiguration(
    oldTimeTrigger: AfterTimeout?,
    terminationCount: Int?,
    onTriggerSelected: (TimeRecurrenceTrigger?) -> Unit
) {
    // Compact format
    var periodText by remember { mutableStateOf(oldTimeTrigger?.period?.let { it.toBriefString() } ?: "") }
    var startTime by remember {
        mutableStateOf(
            when (oldTimeTrigger) {
                is AfterTimeout -> oldTimeTrigger.firstOccurrence
                else -> Clock.System.now()
            }
        )
    }

    LaunchedEffect(periodText, startTime, terminationCount) {
        val canSkipPeriod = terminationCount == 1
        if (!canSkipPeriod && periodText.isBlank()) {
            onTriggerSelected(null)
            return@LaunchedEffect
        }
        val period = parseCompactTimeToPeriod(periodText)
        if (!canSkipPeriod && period == null) {
            onTriggerSelected(null)
        } else {
            onTriggerSelected(AfterTimeout(period, startTime))
        }
    }

    var showStartPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val multipleOccurrences = terminationCount == null || terminationCount > 1
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(multipleOccurrences) {
                if (it) {
                    Text("Start from:", style = MaterialTheme.typography.labelMedium)
                } else {
                    Text("At:", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = { showStartPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(formatDueDate(startTime))
            }
        }

        AnimatedVisibility(visible = multipleOccurrences) {
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

    if (showStartPicker) {
        DatePickerDialog(
            currentDate = startTime,
            onDismiss = { showStartPicker = false },
            onDateSelected = { date ->
                startTime = date
                showStartPicker = false
            }
        )
    }
}

@Composable
private fun FixedDaysOfWeekConfiguration(
    oldTimeTrigger: AtFixedPoints?,
    onTriggerSelected: (TimeRecurrenceTrigger?) -> Unit
) {
    val (timeOfDay, onShowTimePicker) = timeSelector(initialTimeOfDay = oldTimeTrigger?.pattern?.timeOfDay)

    var selectedDays by remember {
        mutableStateOf((oldTimeTrigger?.pattern as? DaysOfWeek)?.days ?: persistentSetOf())
    }

    var useSystemTimezone by remember { mutableStateOf(oldTimeTrigger.isUsingSystemDefaultTimezone) }
    var selectedTimezone by remember { mutableStateOf(oldTimeTrigger.getTimeZoneOrDefault()) }

    LaunchedEffect(selectedDays, timeOfDay, useSystemTimezone, selectedTimezone) {
        if (selectedDays.isEmpty()) {
            onTriggerSelected(null)
        } else {
            onTriggerSelected(
                AtFixedPoints(
                    pattern = DaysOfWeek(days = selectedDays, timeOfDay = timeOfDay),
                    startFrom = Clock.System.now(),
                    timezone = getRecurrenceTimeZone(useSystemTimezone, selectedTimezone)
                )
            )
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
            Text("Days:", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.width(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                RecurrenceDayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in selectedDays,
                        onClick = {
                            selectedDays = if (day in selectedDays) selectedDays.removing(day) else selectedDays.adding(day)
                        },
                        label = { Text(day.name.take(3)) }
                    )
                }
            }
        }

        TimeOfDayInput(
            timeOfDay = timeOfDay,
            onTimeClick = onShowTimePicker,
            useSystemTimezone = useSystemTimezone,
            onTimezoneToggle = { useSystemTimezone = it },
            selectedTimezone = selectedTimezone,
            onTimezoneSelected = { selectedTimezone = it }
        )
    }
}

private fun AtFixedPoints?.getTimeZoneOrDefault(): String = when (val tz = this?.timezone) {
    is RecurrenceTimeZone.SystemDefault? -> TimeZone.currentSystemDefault().id
    is RecurrenceTimeZone.Specific -> tz.zoneId
}

@Composable
private fun FixedDayOfMonthConfiguration(
    oldTimeTrigger: AtFixedPoints?,
    onTriggerSelected: (TimeRecurrenceTrigger?) -> Unit
) {
    val (timeOfDay, onShowTimePicker) = timeSelector(initialTimeOfDay = oldTimeTrigger?.pattern?.timeOfDay)

    var dayOfMonth by remember {
        mutableStateOf(
            when (val pattern = oldTimeTrigger?.pattern) {
                is DayOfMonth -> pattern.dayOfMonth.toString()
                else -> "1"
            }
        )
    }

    var useSystemTimezone by remember { mutableStateOf(oldTimeTrigger.isUsingSystemDefaultTimezone) }
    var selectedTimezone by remember { mutableStateOf(oldTimeTrigger.getTimeZoneOrDefault()) }
    LaunchedEffect(dayOfMonth, timeOfDay, useSystemTimezone, selectedTimezone) {
        val dayOfMonth = dayOfMonth.toIntOrNull()
        if (dayOfMonth == null || dayOfMonth !in 1..31) {
            onTriggerSelected(null)
        } else {
            onTriggerSelected(
                AtFixedPoints(
                    pattern = DayOfMonth(dayOfMonth = dayOfMonth, timeOfDay = timeOfDay),
                    startFrom = Clock.System.now(),
                    timezone = getRecurrenceTimeZone(useSystemTimezone, selectedTimezone)
                )
            )
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DayOfMonthSelector(
            dayOfMonth = dayOfMonth,
            onDayOfMonthChange = { dayOfMonth = it },
            timeOfDay = timeOfDay,
            onTimeClick = onShowTimePicker,
            useSystemTimezone = useSystemTimezone,
            onTimezoneToggle = { useSystemTimezone = it },
            selectedTimezone = selectedTimezone,
            onTimezoneSelected = { selectedTimezone = it }
        )
    }
}

@Composable
private fun NthDayOfWeekConfiguration(
    oldTimeTrigger: AtFixedPoints?,
    onTriggerSelected: (TimeRecurrenceTrigger?) -> Unit
) {
    val (timeOfDay, onShowTimePicker) = timeSelector(initialTimeOfDay = oldTimeTrigger?.pattern?.timeOfDay)

    var selectedOrdinal by remember {
        mutableStateOf(
            when (val pattern = oldTimeTrigger?.pattern) {
                is NthDayOfWeekInMonth -> pattern.ordinal
                else -> WeekOrdinal.FIRST
            }
        )
    }
    var selectedDayOfWeek by remember {
        mutableStateOf(
            when (val pattern = oldTimeTrigger?.pattern) {
                is NthDayOfWeekInMonth -> pattern.dayOfWeek
                else -> RecurrenceDayOfWeek.MONDAY
            }
        )
    }

    var useSystemTimezone by remember { mutableStateOf(oldTimeTrigger.isUsingSystemDefaultTimezone) }
    var selectedTimezone by remember { mutableStateOf(oldTimeTrigger.getTimeZoneOrDefault()) }
    LaunchedEffect(selectedOrdinal, selectedDayOfWeek, timeOfDay, useSystemTimezone, selectedTimezone) {
        onTriggerSelected(
            AtFixedPoints(
                pattern = NthDayOfWeekInMonth(
                    ordinal = selectedOrdinal,
                    dayOfWeek = selectedDayOfWeek,
                    timeOfDay = timeOfDay,
                ),
                startFrom = Clock.System.now(),
                timezone = getRecurrenceTimeZone(useSystemTimezone, selectedTimezone)
            )
        )
    }

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
            timeOfDay = timeOfDay,
            onTimeClick = onShowTimePicker,
            useSystemTimezone = useSystemTimezone,
            onTimezoneToggle = { useSystemTimezone = it },
            selectedTimezone = selectedTimezone,
            onTimezoneSelected = { selectedTimezone = it }
        )
    }
}

@Composable
private fun YearlyOnDateConfiguration(
    oldTimeTrigger: AtFixedPoints?,
    onTriggerSelected: (TimeRecurrenceTrigger?) -> Unit
) {
    val (timeOfDay, onShowTimePicker) = timeSelector(initialTimeOfDay = oldTimeTrigger?.pattern?.timeOfDay)
    var selectedMonths by remember {
        mutableStateOf(
            when (val pattern = oldTimeTrigger?.pattern) {
                is YearlyOnDate -> pattern.months
                else -> persistentSetOf(RecurrenceMonth.JANUARY)
            }
        )
    }
    var yearlyDayOfMonth by remember {
        mutableStateOf(
            when (val pattern = oldTimeTrigger?.pattern) {
                is YearlyOnDate -> pattern.dayOfMonth.toString()
                else -> "1"
            }
        )
    }

    var useSystemTimezone by remember { mutableStateOf(oldTimeTrigger.isUsingSystemDefaultTimezone) }
    var selectedTimezone by remember { mutableStateOf(oldTimeTrigger.getTimeZoneOrDefault()) }

    LaunchedEffect(selectedMonths, yearlyDayOfMonth, timeOfDay, useSystemTimezone, selectedTimezone) {
        val yearlyDayOfMonth = yearlyDayOfMonth.toIntOrNull()
        if (yearlyDayOfMonth == null || yearlyDayOfMonth !in 1..31) {
            onTriggerSelected(null)
        } else {
            onTriggerSelected(
                AtFixedPoints(
                    pattern = YearlyOnDate(
                        months = selectedMonths,
                        dayOfMonth = yearlyDayOfMonth,
                        timeOfDay = timeOfDay,
                    ),
                    startFrom = Clock.System.now(),
                    timezone = getRecurrenceTimeZone(useSystemTimezone, selectedTimezone)
                )
            )
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MonthSelector(
            selectedMonths = selectedMonths,
            onSelectedMonthsChange = { selectedMonths = it }
        )

        DayOfMonthSelector(
            dayOfMonth = yearlyDayOfMonth,
            onDayOfMonthChange = { yearlyDayOfMonth = it },
            timeOfDay = timeOfDay,
            onTimeClick = onShowTimePicker,
            useSystemTimezone = useSystemTimezone,
            onTimezoneToggle = { useSystemTimezone = it },
            selectedTimezone = selectedTimezone,
            onTimezoneSelected = { selectedTimezone = it }
        )
    }
}

@Composable
private fun YearlyOnNthDayOfWeekConfiguration(
    oldTimeTrigger: AtFixedPoints?,
    onTriggerSelected: (TimeRecurrenceTrigger?) -> Unit
) {
    val (timeOfDay, onShowTimePicker) = timeSelector(initialTimeOfDay = oldTimeTrigger?.pattern?.timeOfDay)

    var selectedOrdinal by remember {
        mutableStateOf(
            when (val pattern = oldTimeTrigger?.pattern) {
                is NthDayOfWeekInMonths -> pattern.ordinal
                else -> WeekOrdinal.FIRST
            }
        )
    }
    var selectedDayOfWeek by remember {
        mutableStateOf(
            when (val pattern = oldTimeTrigger?.pattern) {
                is NthDayOfWeekInMonths -> pattern.dayOfWeek
                else -> RecurrenceDayOfWeek.MONDAY
            }
        )
    }

    var selectedMonths by remember {
        mutableStateOf(
            when (val pattern = oldTimeTrigger?.pattern) {
                is NthDayOfWeekInMonths -> pattern.months
                else -> persistentSetOf(RecurrenceMonth.JANUARY)
            }
        )
    }

    var useSystemTimezone by remember { mutableStateOf(oldTimeTrigger.isUsingSystemDefaultTimezone) }
    var selectedTimezone by remember { mutableStateOf(oldTimeTrigger.getTimeZoneOrDefault()) }

    LaunchedEffect(selectedOrdinal, selectedDayOfWeek, selectedMonths, timeOfDay, useSystemTimezone, selectedTimezone) {
        onTriggerSelected(
            AtFixedPoints(
                pattern = NthDayOfWeekInMonths(
                    ordinal = selectedOrdinal,
                    dayOfWeek = selectedDayOfWeek,
                    months = selectedMonths,
                    timeOfDay = timeOfDay,
                ),
                startFrom = Clock.System.now(),
                timezone = getRecurrenceTimeZone(useSystemTimezone, selectedTimezone)
            )
        )
    }

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

        MonthSelector(
            selectedMonths = selectedMonths,
            onSelectedMonthsChange = { selectedMonths = it },
        )

        TimeOfDayInput(
            timeOfDay = timeOfDay,
            onTimeClick = onShowTimePicker,
            useSystemTimezone = useSystemTimezone,
            onTimezoneToggle = { useSystemTimezone = it },
            selectedTimezone = selectedTimezone,
            onTimezoneSelected = { selectedTimezone = it }
        )
    }
}

private val AtFixedPoints?.isUsingSystemDefaultTimezone: Boolean
    get() = this?.timezone is RecurrenceTimeZone.SystemDefault?

@Composable
private fun MonthSelector(
    selectedMonths: PersistentSet<RecurrenceMonth>,
    onSelectedMonthsChange: (PersistentSet<RecurrenceMonth>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
            Text("Months:", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.width(8.dp))
            var chipWidth by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            BoxWithConstraints {
                val itemSpacing = 4.dp
                val maxNumberOfColumns = when {
                    maxWidth >= 12 * chipWidth + 11 * itemSpacing -> 12
                    maxWidth >= 6 * chipWidth + 5 * itemSpacing -> 6
                    maxWidth >= 4 * chipWidth + 3 * itemSpacing -> 4
                    maxWidth >= 3 * chipWidth + 2 * itemSpacing -> 3
                    maxWidth >= 2 * chipWidth + 1 * itemSpacing -> 2
                    else -> 1
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                    maxItemsInEachRow = maxNumberOfColumns,
                ) {
                    RecurrenceMonth.entries.forEach { month ->
                        FilterChip(
                            selected = month in selectedMonths,
                            onClick = {
                                onSelectedMonthsChange(
                                    if (month in selectedMonths && selectedMonths.size > 1) {
                                        selectedMonths.removing(month)
                                    } else {
                                        selectedMonths.adding(month)
                                    }
                                )
                            },
                            label = { Text(month.name.take(3)) },
                            modifier = Modifier.onSizeChanged {
                                with(density) { chipWidth = it.width.toDp() }
                            }
                        )
                    }
                }
            }
        }
    }
}

val allStatusDefaultValues = listOf(
    TaskStatus.Open,
    TaskStatus.InProgress,
    TaskStatus.Blocked(persistentSetOf()),
    TaskStatus.Done,
    TaskStatus.Declined("")
)

@Composable
private fun ResetToStatusButton(
    selectedStatus: TaskStatus,
    filteredTasks: LazyPagingItems<Task>,
    loadedTasks: Map<String, Task>,
    onFilterTasks: (String) -> Unit,
    onLoadTask: (String) -> Unit,
    onStatusSelected: (TaskStatus) -> Unit,
) {
    var showResetStatusDialog by remember { mutableStateOf(false) }

    if (showResetStatusDialog) {
        StatusSelectionDialog(
            currentStatus = selectedStatus,
            filteredTasks = filteredTasks,
            loadedTasks = loadedTasks,
            onFilterTasks = onFilterTasks,
            onLoadTask = onLoadTask,
            onDismiss = { showResetStatusDialog = false },
            onStatusSelected = { status ->
                onStatusSelected(status)
                showResetStatusDialog = false
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Reset to status", style = MaterialTheme.typography.titleSmall)
            TaskStatus(
                status = selectedStatus,
                blockerTasks = null,
                onBlockerTaskClick = null,
                badgeModifier = Modifier
                    .clickable(onClick = { showResetStatusDialog = true })
                    .padding(6.dp)
            )
        }
    }
}

@Composable
private fun TriggerSelectors(
    selectedTimeTrigger: FormResult<TimeRecurrenceTrigger>,
    onTimeTriggerSelected: (FormResult<TimeRecurrenceTrigger>) -> Unit,
    terminationCount: Int?,
    statusChangesTrigger: StatusChange?,
    onStatusChangeChange: (StatusChange?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Triggers", style = MaterialTheme.typography.titleMedium)

        TimeRecurrenceTriggerSelector(
            oldTimeTrigger = (selectedTimeTrigger as? Success)?.value,
            terminationCount = terminationCount,
            onTriggerSelected = onTimeTriggerSelected
        )
        StatusChangesSelector(
            statusChangeChange = statusChangesTrigger,
            onStatusChangeChange = onStatusChangeChange,
        )
    }
}

@Composable
private fun TerminationSettings(
    termination: RecurrenceTermination,
    onTerminationChange: (RecurrenceTermination) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ends", style = MaterialTheme.typography.titleSmall)

        TerminationCountSelector(
            terminationCount = termination.afterOccurrences?.count?.toString() ?: "",
            onTerminationCountChange = { count ->
                onTerminationChange(
                    termination.copy(
                        afterOccurrences = if (count.isNotEmpty()) {
                            AfterOccurrences(count.toIntOrNull() ?: 0)
                        } else {
                            null
                        }
                    )
                )
            }
        )

        TerminationDateSelector(
            terminationDate = termination.endDate,
            onTerminationDateSelected = { date ->
                onTerminationChange(
                    termination.copy(
                        onDate = date?.let(RecurrenceTerminationCondition::OnDate)
                    )
                )
            }
        )
    }
}

@Composable
private fun TerminationCountSelector(
    terminationCount: String,
    onTerminationCountChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "After",
            style = MaterialTheme.typography.titleSmall
        )
        OutlinedTextField(
            value = terminationCount,
            onValueChange = onTerminationCountChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("+∞") }
        )
        Text(
            text = if (terminationCount != "1") "times" else "time",
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
private fun TerminationDateSelector(
    terminationDate: Instant?,
    onTerminationDateSelected: (Instant?) -> Unit
) {
    var showTerminationDatePicker by remember { mutableStateOf(false) }

    if (showTerminationDatePicker) {
        DatePickerDialog(
            currentDate = terminationDate,
            onDismiss = { showTerminationDatePicker = false },
            onDateSelected = { date ->
                onTerminationDateSelected(date)
                showTerminationDatePicker = false
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Until",
            style = MaterialTheme.typography.titleSmall
        )
        OutlinedButton(
            onClick = { showTerminationDatePicker = true },
            modifier = Modifier.weight(1f),
        ) {
            Text(terminationDate?.let { formatDueDate(it) } ?: "Select date")
        }
        AnimatedVisibility(terminationDate != null) {
            IconButton(onClick = { onTerminationDateSelected(null) }) {
                Icon(Icons.Default.Close, contentDescription = "Clear date")
            }
        }
    }
}

@Composable
private fun StatusChangesSelector(
    statusChangeChange: StatusChange?,
    onStatusChangeChange: (StatusChange?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            itemVerticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("If status is", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = statusChangeChange?.requiredStatuses == null,
                    onClick = { onStatusChangeChange(null) },
                    label = { Text("Any", style = MaterialTheme.typography.labelSmall) }
                )
                allStatusDefaultValues.forEach { status ->
                    val isSelected = statusChangeChange?.requiredStatuses?.any { it::class == status::class } == true
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            val currentStatuses = statusChangeChange?.requiredStatuses ?: persistentSetOf()
                            onStatusChangeChange(
                                StatusChange(
                                    if (isSelected && currentStatuses.size > 1) {
                                        currentStatuses.fold(currentStatuses) { acc, s ->
                                            if (s::class == status::class) acc.removing(s) else acc
                                        }
                                    } else if (!isSelected) {
                                        currentStatuses.adding(status)
                                    } else {
                                        currentStatuses
                                    }
                                )
                            )
                        },
                        label = { Text(status.displayName, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(status.icon, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DayOfMonthSelector(
    dayOfMonth: String,
    onDayOfMonthChange: (String) -> Unit,
    timeOfDay: TimeOfDay,
    onTimeClick: () -> Unit,
    useSystemTimezone: Boolean,
    onTimezoneToggle: (Boolean) -> Unit,
    selectedTimezone: String,
    onTimezoneSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = dayOfMonth,
            onValueChange = { onDayOfMonthChange(it.filter { c -> c.isDigit() }.take(2)) },
            label = { Text("Day of month (1-31)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        TimeOfDayInput(
            timeOfDay = timeOfDay,
            onTimeClick = onTimeClick,
            useSystemTimezone = useSystemTimezone,
            onTimezoneToggle = onTimezoneToggle,
            selectedTimezone = selectedTimezone,
            onTimezoneSelected = onTimezoneSelected
        )
    }
}

@Composable
private fun TimeOfDayInput(
    timeOfDay: TimeOfDay,
    onTimeClick: () -> Unit,
    useSystemTimezone: Boolean,
    onTimezoneToggle: (Boolean) -> Unit,
    selectedTimezone: String,
    onTimezoneSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Time:", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onTimeClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                val formattedHour = timeOfDay.hour.toString().padStart(2, '0')
                val formattedMinute = timeOfDay.minute.toString().padStart(2, '0')
                Text("$formattedHour:$formattedMinute")
            }
        }

        TimeZoneSelector(
            useSystemTimezone = useSystemTimezone,
            selectedTimezone = selectedTimezone,
            onUseSystemTimezoneChange = onTimezoneToggle,
            onTimezoneSelected = onTimezoneSelected,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun getRecurrenceTimeZone(
    useSystemTimezone: Boolean,
    selectedTimezone: String
): RecurrenceTimeZone = if (useSystemTimezone) {
    RecurrenceTimeZone.SystemDefault
} else {
    RecurrenceTimeZone.Specific(selectedTimezone)
}
