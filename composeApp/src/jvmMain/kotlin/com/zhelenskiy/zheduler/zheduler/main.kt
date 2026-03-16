package com.zhelenskiy.zheduler.zheduler

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.nucleus.window.material.MaterialDecoratedWindow
import io.github.kdroidfilter.nucleus.window.material.MaterialTitleBar

fun main() = application {
    val themeState = rememberThemeState()
    val colorScheme = themeState.colorScheme

    MaterialTheme(colorScheme = colorScheme) {
        MaterialDecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "Zheduler",
            state = rememberWindowState(width = 900.dp, height = 800.dp),
        ) {
            val backgroundColor by animateColorAsState(
                targetValue = colorScheme.primaryContainer,
                animationSpec = if (themeState.settingsLoaded) spring() else snap(),
            )
            MaterialTitleBar(
                backgroundContent = { Box(Modifier.fillMaxSize().background(backgroundColor)) }
            ) {
                Text(
                    text = title,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }

            App(themeState)
        }
    }
}