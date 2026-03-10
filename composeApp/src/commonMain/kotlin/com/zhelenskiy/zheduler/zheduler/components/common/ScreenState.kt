package com.zhelenskiy.zheduler.zheduler.components.common

/**
 * Represents the loading state of a screen, used to control animations during initialization.
 *
 * State transitions:
 * Loading -> InitiallyLoaded -> Ready
 *
 * @param T The type of data held by the state
 */
sealed class ScreenState<out T> {
    /**
     * Initial state before any data is loaded from repository.
     */
    data object Loading : ScreenState<Nothing>()

    /**
     * Data has been loaded from repository but animations should still be disabled.
     * This prevents jarring animations on first render.
     */
    data class InitiallyLoaded<T>(val data: T) : ScreenState<T>()

    /**
     * Normal operational state with animations enabled.
     */
    data class Ready<T>(val data: T) : ScreenState<T>()
}

/**
 * Returns true if this state should enable animations.
 */
val ScreenState<*>.shouldAnimate: Boolean
    get() = this is ScreenState.Ready

/**
 * Returns the data from this state, or null if still loading.
 */
val <T> ScreenState<T>.dataOrNull: T?
    get() = when (this) {
        is ScreenState.Loading -> null
        is ScreenState.InitiallyLoaded -> data
        is ScreenState.Ready -> data
    }
