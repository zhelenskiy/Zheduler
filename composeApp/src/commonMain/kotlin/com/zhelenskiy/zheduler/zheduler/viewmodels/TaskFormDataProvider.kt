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
    /**
     * Retrieves a task by its unique identifier.
     *
     * @param id The unique identifier of the task to retrieve.
     * @return The task with the specified identifier, or null if no such task exists.
     */
    suspend fun getTaskById(id: String): Task?

    /**
     * Filter tags based on search query and exclude list.
     *
     * @param searchQuery The search query to filter tags by.
     * @param excludeTags A set of tags to exclude from the results.
     * @return A list of tags that match the search query and are not in the exclude list.
     */
    suspend fun filterTags(searchQuery: String, excludeTags: Set<String>): List<String>

    /**
     * Filter tasks for selection dialog (e.g., blocker selection in StatusSelectionDialog).
     * Delegates to repository for SQL-based filtering.
     *
     * @param searchQuery Search query to filter by id or title
     * @return List of tasks that match the search
     */
    suspend fun filterTasksForSelection(searchQuery: String): List<Task>

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

    /**
     * Calculates and retrieves the aggregate status of a task based on the statuses of its subtasks.
     *
     * @param id The unique identifier of the parent task whose subtasks' statuses will be evaluated.
     * @return The calculated status of the task based on its subtasks, or null if the task or its subtasks
     *         are not found.
     */
    suspend fun getCalculatedStatusFromSubtasks(id: String): TaskStatus?

    /**
     * Retrieves the prefix associated with the current space's unique identifier.
     *
     * @return The prefix of the current space's ID, or null if the prefix cannot be determined.
     */
    suspend fun getCurrentSpaceIdPrefix(): String?

    /**
     * Retrieves all prefixes for all spaces.
     */
    suspend fun getAllSpacePrefixes(): List<String>
}
