package com.zhelenskiy.zheduler.zheduler

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import io.github.kdroidfilter.nucleus.window.material.MaterialDecoratedWindow
import io.github.kdroidfilter.nucleus.window.material.MaterialTitleBar

fun main() = application {
    val themeModeState = remember { mutableStateOf(ThemeMode.System) }
    val useDynamicColorsState = remember { mutableStateOf(true) }
    val colorScheme = getColorScheme(themeModeState.value, useDynamicColorsState.value)
    MaterialTheme(colorScheme = colorScheme) {
        MaterialDecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "Zheduler",
            state = rememberWindowState(width = 900.dp, height = 800.dp),
        ) {
            MaterialTitleBar(
                backgroundContent = { Box(Modifier.fillMaxSize().background(colorScheme.primaryContainer)) }
            ) {
                Text(
                    text = title,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }

            App(themeModeState, useDynamicColorsState)
        }
    }
}