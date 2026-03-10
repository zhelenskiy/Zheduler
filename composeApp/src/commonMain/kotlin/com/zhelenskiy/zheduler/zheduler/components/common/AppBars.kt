package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode

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
    onDynamicColorsChange: (Boolean) -> Unit
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
            IconButton(
                onClick = onSave,
                enabled = isFormValid,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (isFormValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    disabledContentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = "Save")
            }
            ThemeMenuButton(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onDynamicColorsChange
            )
        },
        colors = appTopAppBarColors(),
    )
}
