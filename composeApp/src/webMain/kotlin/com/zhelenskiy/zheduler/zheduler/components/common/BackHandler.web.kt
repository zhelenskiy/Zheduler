package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.runtime.Composable

/** No system back gesture on this platform; the toolbar arrow is the only way back. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
