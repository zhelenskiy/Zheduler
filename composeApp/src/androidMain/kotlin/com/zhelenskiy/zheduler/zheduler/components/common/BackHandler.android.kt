package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.runtime.Composable

/** Delegates to activity-compose, so the gesture goes through the screen's own back handling. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
