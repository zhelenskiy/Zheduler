package com.zhelenskiy.zheduler.zheduler.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListContainer
import dev.zacsweers.metro.createGraph
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CompositionLocal for accessing the AppGraph throughout the Compose hierarchy.
 */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("No AppGraph provided. Make sure to wrap your composable with AppGraphProvider.")
}

private val appGraphLock = Mutex()
private var appGraphInstance: AppGraph? = null

/**
 * The graph, if it has already been built.
 *
 * Lets a composition that is starting over pick the existing one up in its first frame instead of
 * rendering nothing until [obtainAppGraph] resumes.
 */
fun peekAppGraph(): AppGraph? = appGraphInstance

/**
 * The process's one object graph, built on first use once the database is ready.
 *
 * Deliberately not held in the composition. Android recreates the activity for every configuration
 * change — a rotation, a theme switch, a locale change — and a graph per recreation means a
 * repository whose caches start cold every time and an app-scoped [SpaceListContainer] whose
 * coroutine scope nothing is left to close.
 */
suspend fun obtainAppGraph(): AppGraph = appGraphInstance ?: appGraphLock.withLock {
    appGraphInstance ?: run {
        deferredDatabaseInstance.await()
        createGraph<AppGraph>().also { appGraphInstance = it }
    }
}

/**
 * Provides the AppGraph to the Compose hierarchy.
 */
@Composable
fun AppGraphProvider(
    appGraph: AppGraph,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalAppGraph provides appGraph) {
        content()
    }
}
