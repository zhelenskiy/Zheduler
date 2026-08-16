package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import com.zhelenskiy.zheduler.zheduler.viewmodels.ScopedContainer

/**
 * Where a failed intent gets reported.
 *
 * One host for the whole app, above the navigation graph, so that a screen does not have to own a
 * snackbar of its own to be able to say that something went wrong.
 */
val LocalFailureSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * Shows whatever [container]'s store failed on.
 *
 * The store recovers from these rather than dying, so without something showing them the app would
 * simply appear to ignore the action — a save that quietly did nothing is worse than an error.
 */
@Composable
fun ReportFailures(container: ScopedContainer) {
    val host = LocalFailureSnackbar.current ?: return
    LaunchedEffect(container, host) {
        container.failures.collect { failure ->
            host.showSnackbar(failure.userMessage(), duration = SnackbarDuration.Long)
        }
    }
}

/**
 * What to put in front of the user.
 *
 * Exception messages are not written for users, but they are what distinguishes "that id is
 * already taken" from "the disk is full", so one is shown when there is one.
 */
internal fun Throwable.userMessage(): String =
    message?.takeIf { it.isNotBlank() }?.let { "Something went wrong: $it" }
        ?: "Something went wrong. Please try again."
