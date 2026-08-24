package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.sync.Outcome
import com.zhelenskiy.zheduler.zheduler.sync.RemoteError
import com.zhelenskiy.zheduler.zheduler.sync.RemoteRemedy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Something fetched over the network, as a screen has to show it.
 *
 * The point of the type is that there is no fourth case. A screen that renders a [RemoteContent]
 * has been made to say what it puts on screen while the answer is still coming and what it puts
 * there when it never came — which is the half that gets left out when a failure is an exception
 * somebody remembered to catch.
 */
sealed interface RemoteContent<out T> {
    data object Loading : RemoteContent<Nothing>
    data class Loaded<T>(val value: T) : RemoteContent<T>
    data class Failed(val error: RemoteError) : RemoteContent<Nothing>
}

/**
 * A remote call held as state, with a `retry` that runs it again.
 *
 * [key] is what the call depends on: change it — a different server address, a different account —
 * and the call is made again rather than showing the previous one's answer under the new question.
 */
@Stable
class RemoteContentState<T> internal constructor(
    private val load: suspend () -> Outcome<T>,
    private val runner: (suspend () -> Unit) -> Unit,
) {
    var content: RemoteContent<T> by mutableStateOf(RemoteContent.Loading)
        private set

    /** True while a call is in flight, including a retry over an already-shown failure. */
    var isRefreshing: Boolean by mutableStateOf(false)
        private set

    fun retry() {
        // Claimed before the coroutine starts, not inside it. Set inside, two taps in one frame
        // would both get past the check, and the first to finish would re-enable the button while
        // the second was still running.
        if (isRefreshing) return
        isRefreshing = true
        runner {
            try {
                content = when (val outcome = load()) {
                    is Outcome.Success -> RemoteContent.Loaded(outcome.value)
                    is Outcome.Failure -> RemoteContent.Failed(outcome.error)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // A loader is meant to answer with an Outcome, but one that throws must still
                // leave something on screen: uncaught it would take the coroutine's parent down
                // and leave this spinning forever.
                content = RemoteContent.Failed(RemoteError.Malformed(failure.message))
            } finally {
                isRefreshing = false
            }
        }
    }
}

/**
 * Runs [load] when [key] changes, and gives back state that can be retried.
 *
 * Deliberately not a plain `LaunchedEffect` returning a value: a retry has to be startable from a
 * button, which means the call has to outlive the composition that started it and be re-runnable
 * without a key changing.
 */
@Composable
fun <T> rememberRemoteContent(
    key: Any?,
    load: suspend () -> Outcome<T>,
): RemoteContentState<T> {
    val scope = rememberCoroutineScope()
    // `load` is captured fresh each recomposition, so a lambda closing over changing state does
    // not go stale between the first attempt and the retry.
    val currentLoad = rememberUpdatedState(load)
    val state = remember(key) {
        RemoteContentState<T>(
            load = { currentLoad.value() },
            runner = { block -> scope.launch { block() } },
        )
    }
    LaunchedEffect(state) { state.retry() }
    return state
}

/**
 * Draws [state]: the value when it arrived, a spinner while it is coming, and a failure with
 * whatever the user can do about it when it did not.
 *
 * [onSignIn] and [onResolveConflict] are optional because not every screen has somewhere to send
 * those: where one is missing, the failure is shown without that button rather than with a button
 * that does nothing.
 */
@Composable
fun <T> RemoteContentBox(
    state: RemoteContentState<T>,
    modifier: Modifier = Modifier,
    onSignIn: (() -> Unit)? = null,
    onResolveConflict: (() -> Unit)? = null,
    onReviewSettings: (() -> Unit)? = null,
    loading: @Composable () -> Unit = { RemoteLoading() },
    content: @Composable (T) -> Unit,
) {
    Box(modifier) {
        when (val current = state.content) {
            is RemoteContent.Loading -> loading()
            is RemoteContent.Loaded -> content(current.value)
            is RemoteContent.Failed -> RemoteFailure(
                error = current.error,
                isRetrying = state.isRefreshing,
                onRetry = state::retry,
                onSignIn = onSignIn,
                onResolveConflict = onResolveConflict,
                onReviewSettings = onReviewSettings,
            )
        }
    }
}

@Composable
fun RemoteLoading(modifier: Modifier = Modifier, label: String = "Contacting the server…") {
    Row(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One failed remote call, with the one action that fits it.
 *
 * Which action that is comes from [RemoteError.remedy] rather than from matching on the error
 * here, so an error added later arrives with its button already chosen instead of silently
 * falling through to "Retry" — which is the wrong offer for a wrong password and for a conflict
 * alike.
 */
@Composable
fun RemoteFailure(
    error: RemoteError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    isRetrying: Boolean = false,
    onSignIn: (() -> Unit)? = null,
    onResolveConflict: (() -> Unit)? = null,
    onReviewSettings: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Sync problem" },
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = error.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (error.remedy) {
                    // The same button for both: a rate limit's message already says how long is
                    // left, and "Try again now" beside it read as a way to skip the wait.
                    RemoteRemedy.Retry, RemoteRemedy.RetryLater ->
                        RemedyButton(label = "Try again", enabled = !isRetrying, onClick = onRetry)

                    RemoteRemedy.SignIn -> onSignIn?.let {
                        RemedyButton(label = "Sign in", enabled = !isRetrying, onClick = it)
                    }

                    RemoteRemedy.ResolveConflict -> onResolveConflict?.let {
                        RemedyButton(label = "Resolve", enabled = !isRetrying, onClick = it)
                    }

                    RemoteRemedy.ReviewSettings -> onReviewSettings?.let {
                        RemedyButton(label = "Change settings", enabled = !isRetrying, onClick = it)
                    }

                    // Nothing the user can do from here; the sentence above is the whole answer.
                    RemoteRemedy.None -> Unit
                }
            }
        }
    }
}

@Composable
private fun RemedyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) { Text(label) }
}

private fun RemoteError.icon(): ImageVector = when (remedy) {
    RemoteRemedy.Retry, RemoteRemedy.RetryLater -> Icons.Default.CloudOff
    RemoteRemedy.SignIn -> Icons.Default.Lock
    RemoteRemedy.ResolveConflict -> Icons.Default.SyncProblem
    RemoteRemedy.ReviewSettings -> Icons.Default.Settings
    RemoteRemedy.None -> Icons.Default.SyncProblem
}
