package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import com.zhelenskiy.zheduler.zheduler.TaskStatus

/**
 * Common interface for ViewModels that provide data for task forms.
 * This interface extracts the shared functionality between NewTaskViewModel and TaskDetailViewModel
 * to follow the DRY principle.
 */
interface TaskFormDataProvider {
    suspend fun getTaskById(id: String): Task?
    suspend fun getAllTags(): Set<String>
    suspend fun getAvailableTasks(): List<Task>
    suspend fun wouldCreateCycle(
        currentId: String,
        targetId: String,
        connectionType: ConnectionType,
        existingConnections: Set<TaskConnection>
    ): Boolean
    suspend fun getCalculatedStatusFromSubtasks(id: String): TaskStatus?
    suspend fun getCurrentSpaceIdPrefix(): String?
    suspend fun getAllSpacePrefixes(): List<String>
}
