package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
