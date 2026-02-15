package com.zhelenskiy.zheduler.zheduler.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*

@Composable
fun ThemeMenuButton(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = when (themeMode) {
                ThemeMode.Light -> Icons.Default.LightMode
                ThemeMode.Dark -> Icons.Default.DarkMode
                ThemeMode.System -> Icons.Default.Brightness4
            },
            contentDescription = "Theme settings"
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        DropdownMenuItem(
            text = { Text("Light") },
            onClick = {
                onThemeModeChange(ThemeMode.Light)
                expanded = false
            },
            leadingIcon = {
                if (themeMode == ThemeMode.Light) {
                    Icon(Icons.Default.Check, contentDescription = null)
                }
            }
        )
        DropdownMenuItem(
            text = { Text("Dark") },
            onClick = {
                onThemeModeChange(ThemeMode.Dark)
                expanded = false
            },
            leadingIcon = {
                if (themeMode == ThemeMode.Dark) {
                    Icon(Icons.Default.Check, contentDescription = null)
                }
            }
        )
        DropdownMenuItem(
            text = { Text("System") },
            onClick = {
                onThemeModeChange(ThemeMode.System)
                expanded = false
            },
            leadingIcon = {
                if (themeMode == ThemeMode.System) {
                    Icon(Icons.Default.Check, contentDescription = null)
                }
            }
        )

        if (supportsDynamicColors) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Dynamic colors") },
                onClick = {
                    onDynamicColorsChange(!useDynamicColors)
                },
                leadingIcon = {
                    Checkbox(
                        checked = useDynamicColors,
                        onCheckedChange = null
                    )
                }
            )
        }
    }
}
