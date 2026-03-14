@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlin.time.ExperimentalTime

/**
 * Represents a saved filter configuration that can be stored and loaded.
 * Optionally includes a reference to a view mode to apply when loading.
 */
data class SavedFilter(
    val id: String,
    val name: String,
    val spaceId: String,
    val criteria: TaskFilterCriteria,
    val viewModeId: String? = null // Optional attached view mode
)

/**
 * A saved filter with its attached view mode resolved.
 */
data class SavedFilterWithViewMode(
    val filter: SavedFilter,
    val attachedViewMode: ViewMode?
)
