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
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant
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
    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }
    var useSystemTimezone by remember { mutableStateOf(true) }
    var selectedTimezoneId by remember { mutableStateOf(TimeZone.currentSystemDefault().id) }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = currentDate?.toEpochMilliseconds()
            ?: now.toEpochMilliseconds()
    )

    val timePickerState = rememberTimePickerState(
        initialHour = initialDateTime.hour,
        initialMinute = initialDateTime.minute,
        is24Hour = true
    )

    if (showingDatePicker) {
        // First show date picker
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { dateMillis ->
                            selectedDateMillis = dateMillis
                            showingDatePicker = false // Move to time picker
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
            DatePicker(
                state = datePickerState,
                modifier = Modifier
            )
        }
    } else {
        // Then show time picker with vertical layout and timezone selector
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedDateMillis?.let { dateMillis ->
                            // Combine date and time
                            val dateInstant = Instant.fromEpochMilliseconds(dateMillis)
                            val selectedTz = if (useSystemTimezone) {
                                TimeZone.currentSystemDefault()
                            } else {
                                TimeZone.of(selectedTimezoneId)
                            }
                            val dateTime = dateInstant.toLocalDateTime(selectedTz)

                            // Create new datetime with selected time
                            val newDateTime = kotlinx.datetime.LocalDateTime(
                                year = dateTime.year,
                                month = dateTime.month,
                                dayOfMonth = dateTime.day,
                                hour = timePickerState.hour,
                                minute = timePickerState.minute,
                                second = 0,
                                nanosecond = 0
                            )

                            val finalInstant = newDateTime.toInstant(selectedTz)
                            onDateSelected(finalInstant)
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
                        modifier = Modifier
                    )

                    HorizontalDivider()

                    TimeZoneSelector(
                        useSystemTimezone = useSystemTimezone,
                        selectedTimezone = selectedTimezoneId,
                        onUseSystemTimezoneChange = { useSystemTimezone = it },
                        onTimezoneSelected = { selectedTimezoneId = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )
    }
}
