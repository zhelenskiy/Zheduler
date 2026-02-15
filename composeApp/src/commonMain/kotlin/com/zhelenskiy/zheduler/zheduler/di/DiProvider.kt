package com.zhelenskiy.zheduler.zheduler.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.zacsweers.metro.createGraph

/**
 * CompositionLocal for accessing the AppGraph throughout the Compose hierarchy.
 */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("No AppGraph provided. Make sure to wrap your composable with AppGraphProvider.")
}

/**
 * Creates the AppGraph instance using Metro's generated implementation.
 * Must be called after awaitDatabaseInitialization().
 */
fun createAppGraph(): AppGraph = createGraph<AppGraph>()

/**
 * Awaits database initialization. Must be called before createAppGraph().
 */
suspend fun awaitDatabaseInitialization() {
    deferredDatabaseInstance.await()
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
