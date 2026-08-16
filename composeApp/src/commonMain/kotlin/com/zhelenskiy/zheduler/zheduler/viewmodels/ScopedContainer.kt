package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The coroutine scope a container's store, paged queries and repository subscriptions live in.
 *
 * Closing it is not optional. A container subscribes to [com.zhelenskiy.zheduler.zheduler.TaskRepository.changes]
 * on the repository, which is a singleton for the whole app: as long as that collector is running
 * the repository holds a reference to the container, so a container that is dropped without being
 * closed stays alive, keeps its paging sources, and is woken by every later mutation. Screens are
 * remembered per navigation entry and rebuilt whenever one re-enters the composition, so that would
 * accumulate one live container per visit.
 *
 * Compose closes these through `rememberContainer`; the one app-scoped container lives as long as
 * the process and is never closed.
 */
abstract class ScopedContainer : AutoCloseable {

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
