package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Runs every check the app would have run on its own, now. */
@Stable
fun interface ManualCheck {
    suspend fun run()

    companion object {
        /** Nothing to run — for a test composing a screen on its own. */
        val None = ManualCheck { }
    }
}

val LocalManualCheck = compositionLocalOf { ManualCheck.None }

/**
 * Asks every waiting rule whether its moment has come, without waiting for the next sweep.
 *
 * The checks run on a schedule the device decides, and that schedule is a compromise — a phone
 * asked where it is every fifteen minutes is a phone whose battery lasts, and also one that tells
 * you about the shop you walked past ten minutes ago. This is the way out of the compromise for
 * the one moment it matters: the user is standing where the rule is about, and wants to know now.
 *
 * It is also the honest answer to "is this thing working at all", which is worth having on a
 * feature whose failures are all silence.
 */
@Composable
fun CheckNowButton() {
    val check = LocalManualCheck.current
    val scope = rememberCoroutineScope()
    // Remembered, or every recomposition would forget that a check is under way and the button
    // would come back enabled mid-sweep.
    var running by remember { mutableStateOf(false) }

    // Turning while it runs, because a sweep can take twenty seconds to get a fix and a button
    // that does nothing visible for twenty seconds is a button the user presses again.
    // Only while it runs. A transition left running idle is a frame of work in both top bars for
    // every frame the app draws, for a picture that is not turning.
    val angle = if (running) {
        val spin = rememberInfiniteTransition(label = "checking")
        val turning by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "angle",
        )
        turning
    } else {
        0f
    }

    IconButton(
        enabled = !running,
        onClick = {
            running = true
            scope.launch {
                try {
                    check.run()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Every other caller of a sweep guards it, and this one is pressed exactly when
                    // something is already wrong. Thrown from here it reaches no handler and takes
                    // the app down — from a button whose whole job is to ask whether things work.
                } finally {
                    running = false
                }
            }
        },
    ) {
        Icon(
            Icons.Default.Refresh,
            contentDescription = "Check now",
            modifier = if (running) Modifier.rotate(angle) else Modifier,
        )
    }
}
