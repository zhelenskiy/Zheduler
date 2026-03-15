package com.zhelenskiy.zheduler.zheduler.settings

import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import kotlinx.serialization.Serializable

/**
 * Serializable data class for persisting theme settings.
 * Color is stored as ARGB Int value for serialization compatibility.
 */
@Serializable
data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val useDynamicColors: Boolean = true,
    val customSeedColorArgb: Int = 0xFF1E90FF.toInt() // DefaultSeedColor
)
