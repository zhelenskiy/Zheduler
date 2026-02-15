package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

/**
 * Standard top app bar colors used across the application.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
)
