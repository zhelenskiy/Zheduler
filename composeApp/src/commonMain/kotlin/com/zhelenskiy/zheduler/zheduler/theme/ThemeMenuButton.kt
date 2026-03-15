package com.zhelenskiy.zheduler.zheduler.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.github.skydoves.colorpicker.compose.ColorPickerController
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.DefaultSeedColor

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
private fun CustomColorMenuItem(
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    var showColorPicker by remember { mutableStateOf(false) }

    Column {
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Custom color") },
            onClick = { showColorPicker = true },
            leadingIcon = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(colorSettings.savedColor)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
            }
        )
    }

    if (showColorPicker) {
        ColorPickerDialog(
            colorSettings = colorSettings,
            onColorSettingsChange = onColorSettingsChange,
            onDismiss = {
                // Clear preview and close
                onColorSettingsChange(colorSettings.copy(previewColor = null))
                showColorPicker = false
            },
            onSave = { color ->
                // Save the color and clear preview
                onColorSettingsChange(ColorSettings(savedColor = color, previewColor = null))
                showColorPicker = false
            }
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

@Composable
private fun ColorComparison(
    savedColor: Color,
    newColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ColorSwatch(color = savedColor, label = "Saved")
        Spacer(modifier = Modifier.width(24.dp))
        ColorSwatch(color = newColor, label = "New")
    }
}

@Composable
private fun ColorPickerDialogContent(
    savedColor: Color,
    currentPreviewColor: Color,
    controller: ColorPickerController,
    onColorChanged: (Color) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HsvColorPicker(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(8.dp),
            controller = controller,
            initialColor = savedColor,
            onColorChanged = { onColorChanged(it.color) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        ColorComparison(
            savedColor = savedColor,
            newColor = currentPreviewColor
        )
    }
}

@Composable
private fun ColorPickerDialogButtons(
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    Row {
        TextButton(onClick = onReset) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Reset")
        }
        TextButton(onClick = onDismiss) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ColorPickerDialog(
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Color) -> Unit
) {
    val controller = rememberColorPickerController()
    val currentPreviewColor = colorSettings.previewColor ?: colorSettings.savedColor

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose seed color") },
        text = {
            ColorPickerDialogContent(
                savedColor = colorSettings.savedColor,
                currentPreviewColor = currentPreviewColor,
                controller = controller,
                onColorChanged = { onColorSettingsChange(colorSettings.copy(previewColor = it)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(currentPreviewColor) }) {
                Text("Save")
            }
        },
        dismissButton = {
            ColorPickerDialogButtons(
                onReset = {
                    controller.selectByColor(DefaultSeedColor, false)
                    onColorSettingsChange(colorSettings.copy(previewColor = DefaultSeedColor))
                },
                onDismiss = onDismiss
            )
        }
    )
}

@Composable
fun ThemeMenuButton(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
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

        // Animated visibility for custom color option
        val showCustomColor = !useDynamicColors || !supportsDynamicColors
        AnimatedVisibility(
            visible = showCustomColor,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            CustomColorMenuItem(
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        }
    }
}
