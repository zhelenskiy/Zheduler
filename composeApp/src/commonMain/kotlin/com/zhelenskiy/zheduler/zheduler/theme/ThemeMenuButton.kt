package com.zhelenskiy.zheduler.zheduler.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
private fun getThemeIcon(themeMode: ThemeMode): ImageVector {
    return when (themeMode) {
        ThemeMode.Light -> Icons.Default.LightMode
        ThemeMode.Dark -> Icons.Default.DarkMode
        ThemeMode.System -> Icons.Default.Brightness4
    }
}

@Composable
private fun ThemeModeMenuItem(
    label: String,
    mode: ThemeMode,
    currentMode: ThemeMode,
    onSelect: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onSelect,
        leadingIcon = {
            if (currentMode == mode) {
                Icon(Icons.Default.Check, contentDescription = null)
            }
        }
    )
}

@Composable
private fun DynamicColorsMenuItem(
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit
) {
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
            imageVector = getThemeIcon(themeMode),
            contentDescription = "Theme settings"
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        ThemeModeMenuItem(
            label = "Light",
            mode = ThemeMode.Light,
            currentMode = themeMode,
            onSelect = {
                onThemeModeChange(ThemeMode.Light)
                expanded = false
            }
        )
        ThemeModeMenuItem(
            label = "Dark",
            mode = ThemeMode.Dark,
            currentMode = themeMode,
            onSelect = {
                onThemeModeChange(ThemeMode.Dark)
                expanded = false
            }
        )
        ThemeModeMenuItem(
            label = "System",
            mode = ThemeMode.System,
            currentMode = themeMode,
            onSelect = {
                onThemeModeChange(ThemeMode.System)
                expanded = false
            }
        )

        DynamicColorsMenuItem(
            useDynamicColors = useDynamicColors,
            onDynamicColorsChange = onDynamicColorsChange
        )
    }
}
