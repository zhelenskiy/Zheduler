package com.zhelenskiy.zheduler.zheduler.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import io.github.kdroidfilter.nucleus.systemcolor.isSystemAccentColorSupported
import io.github.kdroidfilter.nucleus.systemcolor.systemAccentColor

actual val supportsDynamicColors: Boolean
    get() = isSystemAccentColorSupported()

@Composable
actual fun getDynamicColorScheme(isDark: Boolean): ColorScheme? {
    val accentColor = systemAccentColor() ?: return null
    return if (isDark) darkColorScheme(primary = accentColor) else lightColorScheme(primary = accentColor)
}
