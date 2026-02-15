@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.datetime.LocalDate
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Interface defining all task repository operations.
 * Can be implemented by in-memory or persistent storage backends.
 *
 * Note: Space selection is managed by the UI layer, not the repository.
 * All methods that operate on tasks within a space require an explicit spaceId parameter.
 */
interface TaskRepository {
    // ============ Space management ============

    /**
     * Get all spaces in the repository.
     * @return List of all spaces
     */
    suspend fun getAllSpaces(): List<Space>

    /**
     * Get a space by its ID.
     * @param id The space ID
     * @return The space, or null if not found
     */
    suspend fun getSpaceById(id: String): Space?

    /**
     * Create a new space with the given name and ID prefix.
     * @param name The space name
     * @param idPrefix The prefix for task IDs in this space (e.g., "PRJ")
     * @return The created space, or null if creation failed (e.g., duplicate prefix)
     */
    suspend fun createSpace(name: String, idPrefix: String): Space?

    /**
     * Update the name of an existing space.
     * @param spaceId The space ID
     * @param newName The new name for the space
     * @return true if updated successfully, false otherwise
     */
    suspend fun updateSpaceName(spaceId: String, newName: String): Boolean

    /**
     * Delete a space and all its tasks.
     * Also handles cleanup of cross-space relationships (connections, blockers).
     * @param spaceId The space ID to delete
     * @return true if deleted successfully, false otherwise
     */
    suspend fun deleteSpace(spaceId: String): Boolean

    /**
     * Get all existing space ID prefixes.
     * Used to prevent duplicate prefixes when creating new spaces.
     * @return List of all prefixes currently in use
     */
    suspend fun getAllSpacePrefixes(): List<String>

    // ============ Task CRUD operations ============

    /**
     * Get all tasks in a space.
     * @param spaceId The space ID
     * @return List of all tasks in the space
     */
    suspend fun getAll(spaceId: String): List<Task>

    /**
     * Get all tasks in a space except one specific task.
     * Useful when selecting connections to avoid self-references.
     * @param spaceId The space ID
     * @param excludeTaskId The task ID to exclude
     * @return List of tasks excluding the specified one
     */
    suspend fun getAllExcept(spaceId: String, excludeTaskId: String): List<Task>

    /**
     * Get all tasks in a space with calculated totals (total due date, total priority).
     * @param spaceId The space ID
     * @return List of tasks with their calculated totals
     */
    suspend fun getAllWithTotals(spaceId: String): List<TaskWithTotals>

    /**
     * Get a task by its ID.
     * @param id The task ID
     * @return The task, or null if not found
     */
    suspend fun getById(id: String): Task?

    /**
     * Get multiple tasks by their IDs.
     * @param ids Set of task IDs
     * @return List of found tasks (may be fewer than requested if some don't exist)
     */
    suspend fun getByIds(ids: Set<String>): List<Task>

    /**
     * Get a task by its ID with calculated totals.
     * @param id The task ID
     * @return The task with totals, or null if not found
     */
    suspend fun getByIdWithTotals(id: String): TaskWithTotals?

    /**
     * Get all tags used across all tasks.
     * @return Set of all tag strings
     */
    suspend fun getAllTags(): Set<String>

    /**
     * Add a new tag to the global tag list.
     * @param tag The tag to add
     * @return true if added, false if already exists
     */
    suspend fun addTag(tag: String): Boolean

    /**
     * Delete a tag from the global tag list.
     * Note: Does not remove the tag from existing tasks.
     * @param tag The tag to delete
     * @return true if deleted successfully
     */
    suspend fun deleteTag(tag: String): Boolean

    /**
     * Preview what the next task ID would be without incrementing the counter.
     * @param spaceId The space ID
     * @return The next task ID that would be generated
     */
    suspend fun peekNextId(spaceId: String): String

    /**
     * Generate and reserve the next task ID, incrementing the counter.
     * @param spaceId The space ID
     * @return The generated task ID
     */
    suspend fun generateNextId(spaceId: String): String

    /**
     * Add a new task to the repository.
     * @param spaceId The space ID where the task will be created
     * @param title The task title
     * @param description The task description (optional)
     * @param status The initial status (defaults to Open)
     * @param dueDate The due date (optional)
     * @param priority The priority level (optional)
     * @param estimatedTime The estimated time to complete (optional)
     * @param tags Set of tags (optional)
     * @param connections Set of connections to other tasks (optional)
     * @param notifications List of notification settings (optional)
     * @param customId Custom task ID (optional, generates auto-incrementing ID if not provided)
     * @param recurrenceRule Rule for recurring tasks (defaults to None)
     * @param resetStatusOnRecurrence Status to reset to when recurrence triggers (defaults to Open)
     * @param autoUpdateStatusFromSubtasks Whether to automatically update status based on subtasks (defaults to false)
     * @return The created task, or null if creation failed
     */
    suspend fun add(
        spaceId: String,
        title: String,
        description: String = "",
        status: TaskStatus = TaskStatus.Open,
        dueDate: kotlinx.datetime.Instant? = null,
        priority: Priority? = null,
        estimatedTime: RecurrencePeriod? = null,
        tags: Set<String> = emptySet(),
        connections: Set<TaskConnection> = emptySet(),
        notifications: List<TaskNotification> = emptyList(),
        customId: String? = null,
        recurrenceRule: RecurrenceRule = RecurrenceRule.None,
        resetStatusOnRecurrence: TaskStatus = TaskStatus.Open,
        autoUpdateStatusFromSubtasks: Boolean = false
    ): Task?

    /**
     * Update an existing task.
     * Handles connection syncing, parent status updates, and blocker validation.
     * @param task The task with updated values
     * @return The updated task, or null if update failed
     */
    suspend fun update(task: Task): Task?

    /**
     * Delete a task by its ID.
     * Also handles cleanup of connections and updates parent tasks.
     * @param id The task ID to delete
     * @return true if deleted successfully, false otherwise
     */
    suspend fun delete(id: String): Boolean

    // ============ Connection operations ============

    /**
     * Add a connection between two tasks.
     * Automatically syncs the symmetric connection on the target task.
     * @param fromTaskId The source task ID
     * @param toTaskId The target task ID
     * @param type The connection type
     * @return true if added successfully, false if would create a cycle
     */
    suspend fun addConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean

    /**
     * Remove a connection between two tasks.
     * Automatically removes the symmetric connection on the target task.
     * @param fromTaskId The source task ID
     * @param toTaskId The target task ID
     * @param type The connection type
     * @return true if removed successfully
     */
    suspend fun removeConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean

    /**
     * Check if adding a connection would create a circular dependency.
     * Only checks for DependsOn/IsDependencyOf and SubtaskOf/ParentOf cycles.
     * @param fromTaskId The source task ID
     * @param toTaskId The target task ID
     * @param type The connection type
     * @param currentConnections Uncommitted connections for the task being edited (for validation during edit)
     * @return true if adding this connection would create a cycle
     */
    suspend fun wouldCreateCycle(
        fromTaskId: String,
        toTaskId: String,
        type: ConnectionType,
        currentConnections: Set<TaskConnection> = emptySet()
    ): Boolean

    /**
     * Get all tasks that the given task depends on.
     * @param taskId The task ID
     * @return List of tasks this task depends on
     */
    suspend fun getDependencies(taskId: String): List<Task>

    /**
     * Get all tasks that depend on the given task.
     * @param taskId The task ID
     * @return List of tasks that depend on this task
     */
    suspend fun getDependents(taskId: String): List<Task>

    /**
     * Get all tasks related to the given task (RelatesTo connections).
     * @param taskId The task ID
     * @return List of related tasks
     */
    suspend fun getRelatedTasks(taskId: String): List<Task>

    /**
     * Get all parent tasks (tasks that this task is a subtask of).
     * @param taskId The task ID
     * @return List of parent tasks
     */
    suspend fun getParentTasks(taskId: String): List<Task>

    /**
     * Get all subtasks (tasks that are subtasks of this task).
     * @param taskId The task ID
     * @return List of subtasks
     */
    suspend fun getSubtasks(taskId: String): List<Task>

    /**
     * Get all connections grouped by type.
     * @param taskId The task ID
     * @return Map of connection type to list of connected tasks
     */
    suspend fun getConnectionsByType(taskId: String): Map<ConnectionType, List<Task>>

    /**
     * Resolve a set of connections to their actual tasks.
     * @param connections Set of task connections
     * @return Map of connection type to list of resolved tasks
     */
    suspend fun resolveConnections(connections: Set<TaskConnection>): Map<ConnectionType, List<Task>>

    // ============ Status operations ============

    /**
     * Calculate what the status should be based on subtask statuses.
     * Returns null if task has no subtasks.
     * @param taskId The task ID
     * @return The calculated status, or null if no subtasks
     */
    suspend fun getCalculatedStatusFromSubtasks(taskId: String): TaskStatus?

    /**
     * Get the status change timeline for a task.
     * @param taskId The task ID
     * @return List of status changes ordered by time
     */
    suspend fun getStatusTimeline(taskId: String): List<StatusChange>

    /**
     * Get status changes grouped by date for a specific month.
     * Used for calendar view.
     * @param spaceId The space ID
     * @param year The year
     * @param month The month (1-12)
     * @return Map of date to list of status change events
     */
    suspend fun getStatusChangesByDate(spaceId: String, year: Int, month: Int): Map<LocalDate, List<StatusChangeEvent>>

    // ============ Filtering and search ============

    /**
     * Filter spaces by search query.
     * @param spaces List of spaces to filter
     * @param query The search query
     * @param searchInName Whether to search in space name
     * @param searchInPrefix Whether to search in space ID prefix
     * @return Filtered list of spaces
     */
    suspend fun filterSpaces(
        spaces: List<Space>,
        query: String,
        searchInName: Boolean,
        searchInPrefix: Boolean
    ): List<Space>

    /**
     * Get all tasks in a space with totals, filtered by criteria.
     * @param spaceId The space ID
     * @param criteria The filter criteria
     * @return Filtered list of tasks with totals
     */
    suspend fun getAllWithTotalsFiltered(spaceId: String, criteria: TaskFilterCriteria): List<TaskWithTotals>

    /**
     * Group tasks by their resolution status (unresolved, blocked, resolved).
     * @param tasks List of tasks to group
     * @return Grouped tasks
     */
    suspend fun groupTasksByResolutionStatus(tasks: List<TaskWithTotals>): GroupedTasks

    // ============ Filter state persistence ============

    /**
     * Get the saved filter state for a space.
     * @param spaceId The space ID
     * @return The saved filter criteria
     */
    suspend fun getFilterState(spaceId: String): TaskFilterCriteria

    /**
     * Save the filter state for a space.
     * @param spaceId The space ID
     * @param criteria The filter criteria to save
     */
    suspend fun saveFilterState(spaceId: String, criteria: TaskFilterCriteria)

    /**
     * Get the saved view mode for a space.
     * @param spaceId The space ID
     * @return The view mode (e.g., "list", "calendar")
     */
    suspend fun getViewMode(spaceId: String): String

    /**
     * Save the view mode for a space.
     * @param spaceId The space ID
     * @param viewMode The view mode to save
     */
    suspend fun saveViewMode(spaceId: String, viewMode: String)

    /**
     * Get whether the filter panel is open for a space.
     * @param spaceId The space ID
     * @return true if filter panel is open
     */
    suspend fun getFilterPanelOpen(spaceId: String): Boolean

    /**
     * Save whether the filter panel is open for a space.
     * @param spaceId The space ID
     * @param isOpen Whether the filter panel is open
     */
    suspend fun saveFilterPanelOpen(spaceId: String, isOpen: Boolean)

    // ============ Import/Export ============

    /**
     * Export a space and all its tasks to JSON.
     * @param spaceId The space ID to export
     * @param prettyPrint Whether to format the JSON with indentation
     * @return JSON string, or null if space not found
     */
    suspend fun exportSpaceToJson(spaceId: String, prettyPrint: Boolean = false): String?

    /**
     * Import a space from JSON.
     * Creates a new space with a new prefix and remaps all task IDs.
     * @param jsonString The JSON string to import
     * @return The imported space, or null if import failed
     */
    suspend fun importSpaceFromJson(jsonString: String): Space?

    // ============ Recurrence operations ============

    /**
     * Process a recurrence trigger for a task.
     * Advances the recurrence state and resets the task for the next occurrence.
     * @param taskId The task ID
     * @param triggerEvent The event that triggered the recurrence
     * @param triggerTime The time when the trigger occurred
     * @return The updated task, or `null` if not found or not recurring
     */
    suspend fun processRecurrenceTrigger(
        taskId: String,
        triggerEvent: RecurrenceTriggerEvent,
        triggerTime: Instant
    ): Task?

    /**
     * Process all date-based recurrences for tasks with past due dates.
     * Should be called periodically (e.g., on app startup).
     * @param currentTime The current time
     * @return List of tasks that were updated
     */
    suspend fun processDateBasedRecurrences(currentTime: Instant): List<Task>
}
