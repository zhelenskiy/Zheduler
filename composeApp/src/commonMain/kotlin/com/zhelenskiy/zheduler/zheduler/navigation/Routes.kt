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
    val fromCreation: Boolean = false,
    val startInEditMode: Boolean = false
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
