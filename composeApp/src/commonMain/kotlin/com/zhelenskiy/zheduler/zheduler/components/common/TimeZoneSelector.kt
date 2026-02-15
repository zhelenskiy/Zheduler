package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeZoneSelector(
    useSystemTimezone: Boolean,
    selectedTimezone: String,
    onUseSystemTimezoneChange: (Boolean) -> Unit,
    onTimezoneSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(
                checked = useSystemTimezone,
                onCheckedChange = onUseSystemTimezoneChange
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
                    "UTC",
                    "America/New_York",
                    "America/Chicago",
                    "America/Los_Angeles",
                    "Europe/London",
                    "Europe/Paris",
                    "Europe/Berlin",
                    "Europe/Moscow",
                    "Asia/Tokyo",
                    "Asia/Shanghai",
                    "Asia/Dubai",
                    "Asia/Kolkata",
                    "Australia/Sydney",
                    "Pacific/Auckland"
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
