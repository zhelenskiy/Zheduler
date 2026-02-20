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

    /**
     * Search tasks for connection dialog with filtering.
     * This method should delegate to repository for SQL-based filtering.
     *
     * @param searchQuery Search query to filter by id or title
     * @param excludeTaskIds Task IDs to exclude from results (e.g., already connected)
     * @param connectionType The type of connection being created
     * @param existingConnections Existing connections to check for cycles
     * @return List of tasks that match the search and don't create cycles
     */
    suspend fun searchTasksForConnection(
        searchQuery: String,
        excludeTaskIds: Set<String>,
        connectionType: ConnectionType,
        existingConnections: Set<TaskConnection>
    ): List<Task>

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
