package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.runtime.Composable

/**
 * Runs [onBack] instead of navigating back, while [enabled].
 *
 * A screen that asks before discarding unsaved work has to catch every way out, not only its own
 * toolbar arrow — on Android the system back gesture reaches the navigator directly, and used to
 * take the edits with it.
 *
 * Only Android has a back gesture to intercept; everywhere else this does nothing. Compose
 * Multiplatform 1.11 dropped `androidx.compose.ui.backhandler`, and its replacement,
 * `androidx.navigationevent:navigationevent-compose`, is not published for js, wasmJs or ios.
 *
 * Call it unconditionally and use [enabled] as the switch: composing it inside an `if` changes the
 * composition order, and the handler that wins is the one composed last.
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
