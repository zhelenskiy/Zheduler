package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.sync.LocalSpaceEditing

/**
 * Standard top app bar colors used across the application.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
)

/**
 * Shared top app bar for task form screens (new task, edit task).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFormTopAppBar(
    title: String,
    taskId: String?,
    isFormValid: Boolean,
    onBackPress: () -> Unit,
    onSave: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(title)
                if (taskId != null) {
                    Text(
                        text = taskId,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBackPress) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            // Both forms are reached from an affordance that is already hidden while the space
            // cannot be changed. This is for the way back into one that is still on the back
            // stack when the server drops — the screen stays, but it stops being able to save.
            val canSave = isFormValid && LocalSpaceEditing.current.isEditable
            IconButton(
                onClick = onSave,
                enabled = canSave,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    disabledContentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = "Save")
            }
            SettingsButton(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        },
        colors = appTopAppBarColors(),
    )
}
