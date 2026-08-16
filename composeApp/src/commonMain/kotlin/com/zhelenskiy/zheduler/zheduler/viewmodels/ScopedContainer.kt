package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.dsl.StoreBuilder
import pro.respawn.flowmvi.plugins.recover

/**
 * The coroutine scope a container's store, paged queries and repository subscriptions live in.
 *
 * Closing it is not optional: a container collects `TaskRepository.changes` on the app-wide
 * repository, which then holds a reference to it, so one dropped without being closed stays alive
 * and is woken by every later mutation. Screens rebuild theirs whenever a destination re-enters
 * the composition, which would accumulate one per visit.
 *
 * Compose closes these through [rememberContainer]; the app-scoped container is never closed.
 */
abstract class ScopedContainer(
    /** Overridden in tests so a container's work can be driven deterministically. */
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AutoCloseable {

    protected val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _failures = MutableSharedFlow<Throwable>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Whatever handling an intent threw.
     *
     * Screens show these; see `FailureSnackbar`. Nothing is replayed, so a failure raised while no
     * screen is listening is dropped rather than shown late against unrelated content.
     */
    val failures: SharedFlow<Throwable> = _failures.asSharedFlow()

    /**
     * Names the store and gives it somewhere to put the exceptions its intents raise.
     *
     * Without a recover plugin FlowMVI rethrows into the store's coroutine, and this scope has no
     * exception handler to catch it: a single failed database call would take down the process on
     * Android and leave the store dead everywhere else. Recovering keeps the store answering
     * intents, and the user is told rather than left looking at a screen that stopped responding.
     */
    protected fun <S : MVIState, I : MVIIntent, A : MVIAction> StoreBuilder<S, I, A>.reportingFailuresAs(
        storeName: String,
    ) {
        configure { name = storeName }
        recover { e ->
            _failures.tryEmit(e)
            null
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/**
 * Remembers a container for as long as the calling composable is in the composition, and closes it
 * when it leaves or when [keys] change.
 *
 * A navigation destination leaves the composition as soon as it is navigated away from, so
 * `remember` already rebuilds the container on the way back; this stops the previous one.
 */
@Composable
fun <T : AutoCloseable> rememberContainer(vararg keys: Any?, create: () -> T): T =
    remember(*keys) { ClosedOnForget(create()) }.container

private class ClosedOnForget<T : AutoCloseable>(val container: T) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() = container.close()

    /** The composition was discarded before it ran; the container still has to be stopped. */
    override fun onAbandoned() = container.close()
}
