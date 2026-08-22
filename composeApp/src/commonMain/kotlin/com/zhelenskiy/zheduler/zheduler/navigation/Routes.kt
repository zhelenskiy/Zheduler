package com.zhelenskiy.zheduler.zheduler.navigation

import kotlinx.serialization.Serializable

@Serializable
object SpaceListRoute

@Serializable
data class TaskListRoute(val spaceId: String)

@Serializable
data class TaskDetailRoute(
    val spaceId: String,
    val taskId: String,
    val fromCreation: Boolean = false
)

@Serializable
data class TaskEditRoute(
    val spaceId: String,
    val taskId: String
)

@Serializable
data class NewTaskRoute(
    val spaceId: String,
    val prefilledConnectionTargetId: String? = null,
    val prefilledConnectionType: String? = null,
    val returnToEditTaskId: String? = null,
    val taskIdToCopy: String? = null
)

@Serializable
data class CalendarRoute(val spaceId: String)

@Serializable
data class ViewModeManagementRoute(val spaceId: String)

@Serializable
data class ViewModeEditorRoute(
    val spaceId: String,
    val viewModeId: String? = null,
    val copyFromViewModeId: String? = null
)

@Serializable
data class SavedFilterManagementRoute(val spaceId: String)


