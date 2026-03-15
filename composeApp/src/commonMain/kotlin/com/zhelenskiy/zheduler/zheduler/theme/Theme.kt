package com.zhelenskiy.zheduler.zheduler.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode {
    Light, Dark, System
}

@Composable
expect fun getDynamicColorScheme(isDark: Boolean): ColorScheme?

expect val supportsDynamicColors: Boolean
