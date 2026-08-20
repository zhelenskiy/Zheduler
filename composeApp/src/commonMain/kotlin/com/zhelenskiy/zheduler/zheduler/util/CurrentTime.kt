@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The current time, as something that changes.
 *
 * Anything that decides how to draw itself from the clock — whether a task is late, how long until
 * it is — has to read it from here rather than calling the clock during composition. A composition
 * happens once; a deadline passing is not a change to any state Compose watches, so a badge worked
 * out that way stays wrong until something unrelated happens to redraw it. "Missed" appearing only
 * once the user navigated somewhere else and back was exactly that.
 *
 * Dynamic rather than static: a tick should redraw the handful of things that read the time, not
 * every screen underneath the provider.
 */
val LocalNow = compositionLocalOf { Clock.System.now() }

/**
 * Provides [LocalNow] to [content], moving it on every [interval].
 *
 * A minute is the resolution due dates are chosen at, so nothing finer would be visible.
 */
@Composable
fun ProvideCurrentTime(
    interval: Duration = 1.minutes,
    content: @Composable () -> Unit,
) {
    val now by produceState(Clock.System.now()) {
        while (true) {
            delay(interval)
            value = Clock.System.now()
        }
    }
    CompositionLocalProvider(LocalNow provides now, content = content)
}
