@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.components.common.TimeZoneSelector
import kotlinx.datetime.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialog(
    currentDate: Instant?,
    onDismiss: () -> Unit,
    onDateSelected: (Instant) -> Unit
) {
    val now = Clock.System.now()
    val systemTz = TimeZone.currentSystemDefault()
    val initialDateTime = (currentDate ?: now).toLocalDateTime(systemTz)

    var showingDatePicker by remember { mutableStateOf(true) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var useSystemTimezone by remember { mutableStateOf(true) }
    var selectedTimezoneId by remember { mutableStateOf(TimeZone.currentSystemDefault().id) }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = currentDate?.toEpochMilliseconds() ?: now.toEpochMilliseconds()
    )

    val timePickerState = rememberTimePickerState(
        initialHour = initialDateTime.hour,
        initialMinute = initialDateTime.minute,
        is24Hour = true
    )

    if (showingDatePicker) {
        DateSelectionStep(
            datePickerState = datePickerState,
            onDismiss = onDismiss,
            onNext = { date ->
                selectedDate = date
                showingDatePicker = false
            }
        )
    } else {
        TimeSelectionStep(
            timePickerState = timePickerState,
            selectedDate = selectedDate,
            useSystemTimezone = useSystemTimezone,
            selectedTimezoneId = selectedTimezoneId,
            onUseSystemTimezoneChange = { useSystemTimezone = it },
            onTimezoneSelected = { selectedTimezoneId = it },
            onDismiss = onDismiss,
            onConfirm = onDateSelected
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSelectionStep(
    datePickerState: DatePickerState,
    onDismiss: () -> Unit,
    onNext: (LocalDate) -> Unit
) {
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val utcMillis = Instant.fromEpochMilliseconds(millis)
                        onNext(utcMillis.toLocalDateTime(TimeZone.UTC).date)
                    }
                }
            ) {
                Text("Next")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSelectionStep(
    timePickerState: TimePickerState,
    selectedDate: LocalDate?,
    useSystemTimezone: Boolean,
    selectedTimezoneId: String,
    onUseSystemTimezoneChange: (Boolean) -> Unit,
    onTimezoneSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Instant) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    selectedDate?.let { date ->
                        val finalInstant = combineDateAndTime(
                            date = date,
                            hour = timePickerState.hour,
                            minute = timePickerState.minute,
                            useSystemTimezone = useSystemTimezone,
                            selectedTimezoneId = selectedTimezoneId
                        )
                        onConfirm(finalInstant)
                    }
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Select Time") },
        text = {
            TimePickerContent(
                timePickerState = timePickerState,
                useSystemTimezone = useSystemTimezone,
                selectedTimezoneId = selectedTimezoneId,
                onUseSystemTimezoneChange = onUseSystemTimezoneChange,
                onTimezoneSelected = onTimezoneSelected
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerContent(
    timePickerState: TimePickerState,
    useSystemTimezone: Boolean,
    selectedTimezoneId: String,
    onUseSystemTimezoneChange: (Boolean) -> Unit,
    onTimezoneSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TimePicker(
            state = timePickerState,
            layoutType = TimePickerLayoutType.Vertical,
        )

        HorizontalDivider()

        TimeZoneSelector(
            useSystemTimezone = useSystemTimezone,
            selectedTimezone = selectedTimezoneId,
            onUseSystemTimezoneChange = onUseSystemTimezoneChange,
            onTimezoneSelected = onTimezoneSelected,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun combineDateAndTime(
    date: LocalDate,
    hour: Int,
    minute: Int,
    useSystemTimezone: Boolean,
    selectedTimezoneId: String
): Instant {
    val selectedTz = if (useSystemTimezone) {
        TimeZone.currentSystemDefault()
    } else {
        TimeZone.of(selectedTimezoneId)
    }

    val dateTime = LocalDateTime(
        year = date.year,
        month = date.month,
        day = date.day,
        hour = hour,
        minute = minute,
        second = 0,
        nanosecond = 0
    )

    return dateTime.toInstant(selectedTz)
}
