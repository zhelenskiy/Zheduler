package com.zhelenskiy.zheduler.zheduler.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import com.materialkolor.rememberDynamicColorScheme
import io.github.kdroidfilter.nucleus.systemcolor.isSystemAccentColorSupported
import io.github.kdroidfilter.nucleus.systemcolor.systemAccentColor

actual val supportsDynamicColors: Boolean
    get() = isSystemAccentColorSupported()

@Composable
actual fun getDynamicColorScheme(isDark: Boolean): ColorScheme? {
    val accentColor = systemAccentColor() ?: return null
    return rememberDynamicColorScheme(seedColor = accentColor, isDark = isDark)
}
