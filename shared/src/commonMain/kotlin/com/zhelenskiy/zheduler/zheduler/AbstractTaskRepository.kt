@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.FixedPointPattern.NthDayOfWeekInMonths
import com.zhelenskiy.zheduler.zheduler.FixedPointPattern.YearlyOnDate
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.AfterTimeout
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.AtFixedPoints
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Abstract base class implementing shared business logic for task repositories.
 * Concrete implementations must provide data access methods.
 */
abstract class AbstractTaskRepository(protected val clock: Clock = Clock.System) : TaskRepository {

    // ============ Shared JSON configuration ============

    protected val jsonCompact = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    protected val jsonPretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ============ Cycle detection ============

    /**
     * Check if adding a connection would create a circular dependency.
     * This checks for cycles in DependsOn/IsDependencyOf and SubtaskOf/ParentOf relationships.
     * @param fromTaskId The task that would have the connection added
     * @param toTaskId The target task of the connection
     * @param type The type of connection being added
     * @param currentConnections The current set of connections for the task being edited/created
     * @return true if adding this connection would create a cycle
     */
    override suspend fun wouldCreateCycle(
        fromTaskId: String?,
        toTaskId: String,
        type: ConnectionType,
        currentConnections: Set<TaskConnection>
    ): Boolean {
        if (fromTaskId == null) return false
        // Only DependsOn/IsDependencyOf and SubtaskOf/ParentOf can create cycles
        if (type == ConnectionType.RelatesTo) return false

        // Determine the direction based on connection type
        // DependsOn: fromTaskId depends on toTaskId (edge: fromTaskId -> toTaskId in dependency graph)
        // IsDependencyOf: toTaskId depends on fromTaskId (edge: toTaskId -> fromTaskId in dependency graph)
        // SubtaskOf: fromTaskId is subtask of toTaskId (edge: fromTaskId -> toTaskId in subtask graph)
        // ParentOf: toTaskId is subtask of fromTaskId (edge: toTaskId -> fromTaskId in subtask graph)
        val (childId, parentId) = when (type) {
            ConnectionType.DependsOn -> fromTaskId to toTaskId
            ConnectionType.IsDependencyOf -> toTaskId to fromTaskId
            ConnectionType.SubtaskOf -> fromTaskId to toTaskId
            ConnectionType.ParentOf -> toTaskId to fromTaskId
            ConnectionType.RelatesTo -> return false
        }

        // Check if parentId can reach childId through existing relationships
        // If so, adding this edge would create a cycle
        val connectionTypes = when (type) {
            ConnectionType.DependsOn, ConnectionType.IsDependencyOf ->
                setOf(ConnectionType.DependsOn, ConnectionType.IsDependencyOf)

            ConnectionType.SubtaskOf, ConnectionType.ParentOf ->
                setOf(ConnectionType.SubtaskOf, ConnectionType.ParentOf)

            ConnectionType.RelatesTo -> return false
        }
        return canReach(parentId, childId, fromTaskId, currentConnections, connectionTypes)
    }

    /**
     * Check if we can reach targetId starting from startId following directed edges.
     * Follows the "child -> parent" direction for the specified connection types.
     * @param startId The starting task
     * @param targetId The task we're trying to reach
     * @param editingTaskId The task being edited (use currentConnections for this task)
     * @param currentConnections The uncommitted connections for the task being edited
     * @param connectionTypes The connection types to consider for traversal
     */
    protected suspend fun canReach(
        startId: String,
        targetId: String,
        editingTaskId: String,
        currentConnections: Set<TaskConnection>,
        connectionTypes: Set<ConnectionType>
    ): Boolean {
        if (startId == targetId) return true

        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(startId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue

            if (current == targetId) return true

            // Get all tasks that 'current' points to (in the child -> parent direction)
            val dependencies = getDependenciesForCycleCheck(current, editingTaskId, currentConnections, connectionTypes)

            dependencies.forEach { depId ->
                if (depId !in visited) {
                    queue.add(depId)
                }
            }
        }

        return false
    }

    /**
     * Get all task IDs that the given task points to in the "child -> parent" direction,
     * considering the specified connection types.
     * For DependsOn/IsDependencyOf: follows DependsOn edges forward
     * For SubtaskOf/ParentOf: follows SubtaskOf edges forward
     */
    protected suspend fun getDependenciesForCycleCheck(
        taskId: String,
        editingTaskId: String,
        currentConnections: Set<TaskConnection>,
        connectionTypes: Set<ConnectionType>
    ): List<String> {
        val result = mutableListOf<String>()

        // Determine the "forward" connection type (child -> parent direction)
        val forwardType = when {
            ConnectionType.DependsOn in connectionTypes -> ConnectionType.DependsOn
            ConnectionType.SubtaskOf in connectionTypes -> ConnectionType.SubtaskOf
            else -> return result
        }

        // Determine the "reverse" connection type (parent -> child direction)
        val reverseType = forwardType.symmetric

        // Get direct forward connections from this task
        if (taskId == editingTaskId) {
            // Use uncommitted connections for the task being edited
            currentConnections
                .filter { it.type == forwardType }
                .mapTo(result) { it.targetTaskId }
        } else {
            getConnectionsForTaskSync(taskId)
                ?.filter { it.type == forwardType }
                ?.mapTo(result) { it.targetTaskId }
        }

        // Also check reverse connections from editing task that point to this task
        // If editingTask has reverse -> taskId, it means taskId has forward -> editingTask
        // So we need to add editingTask as a "parent" of taskId
        if (taskId != editingTaskId) {
            currentConnections
                .filter { it.type == reverseType && it.targetTaskId == taskId }
                .forEach { _ -> result.add(editingTaskId) }
        }

        return result
    }

    /**
     * Get all connections for a task synchronously (for cycle detection).
     * Returns null if task doesn't exist.
     * Subclasses must implement this to provide data access.
     */
    protected abstract suspend fun getConnectionsForTaskSync(taskId: String): Set<TaskConnection>?

    // todo implement generalised
    override suspend fun groupTasksByResolutionStatus(tasks: List<TaskWithTotals>): GroupedTasks {
        val comparator = prioritySortComparator()
        val unresolved = mutableListOf<TaskWithTotals>()
        val blocked = mutableListOf<TaskWithTotals>()
        val resolved = mutableListOf<TaskWithTotals>()

        tasks.forEach { taskWithTotals ->
            when (taskWithTotals.task.status) {
                is TaskStatus.Done, is TaskStatus.Declined -> resolved.add(taskWithTotals)
                is TaskStatus.Blocked -> blocked.add(taskWithTotals)
                else -> unresolved.add(taskWithTotals)
            }
        }

        return GroupedTasks(
            unresolved = unresolved.sortedWith(comparator),
            blocked = blocked.sortedWith(comparator),
            resolved = resolved.sortedWith(comparator)
        )
    }

    protected suspend fun getCalculatedStatusFromSubtasks(subtasksIds: List<String>, getByIdUnsafe: suspend (String) -> Task?): TaskStatus? {
        val subtasks = subtasksIds.mapNotNull { getByIdUnsafe(it) }
        if (subtasks.isEmpty()) return null

        return calculateStatusFromSubtasks(subtasks.map { it.status })
    }

    override suspend fun getCalculatedStatusFromSubtasks(taskId: String): TaskStatus? {
        val subtasks = getSubtasks(taskId)
        if (subtasks.isEmpty()) return null

        return calculateStatusFromSubtasks(subtasks.map { it.status })
    }

    /**
     * Calculate the status for a parent task based on its subtasks
     * Rules:
     * 1. If all subtasks have the same status class, use that status
     * 2. Otherwise, if any subtask is InProgress -> InProgress
     * 3. If any subtask is Open -> Open
     * 4. If any subtask is Blocked -> Blocked
     * 5. Otherwise -> Done
     */
    protected fun calculateStatusFromSubtasks(subtaskStatuses: List<TaskStatus>): TaskStatus? {
        if (subtaskStatuses.isEmpty()) return null

        val firstStatus = subtaskStatuses.first()

        fun combineBlocks(list: List<TaskStatus.Blocked>): TaskStatus.Blocked {
            val allBlockers = list
                .flatMap { it.blockerTaskIds }
                .toSet()
            val allComments = list
                .mapNotNull { status -> if (status.comment.isBlank()) null else status.blockerTaskIds to status.comment }
                .toSet()
            val allCommentsString = allComments.joinToString("\n") { (tasks, comment) ->
                "${tasks.joinToString()}: $comment"
            }
            return TaskStatus.Blocked(allBlockers, allCommentsString)
        }

        // Rule 1: If all have the same status class, use that status
        if (subtaskStatuses.all { it::class == firstStatus::class }) {
            return when (firstStatus) {
                is TaskStatus.Open -> TaskStatus.Open
                is TaskStatus.InProgress -> TaskStatus.InProgress
                is TaskStatus.Done -> TaskStatus.Done
                is TaskStatus.Blocked -> combineBlocks(subtaskStatuses.filterIsInstance<TaskStatus.Blocked>())
                is TaskStatus.Declined -> {
                    val pluralSuffix = if (subtaskStatuses.size != 1) "s" else ""
                    val messages = subtaskStatuses
                        .mapNotNull { status -> (status as TaskStatus.Declined).reason.takeIf { it.isNotBlank() } }
                    val message = when (messages.size) {
                        0 -> "Subtask$pluralSuffix declined without a reason"
                        1 -> "Subtask$pluralSuffix declined with a reason:\n$messages"
                        else -> "Subtask$pluralSuffix declined with reasons:\n" + messages.joinToString("\n")
                    }
                    TaskStatus.Declined(message)
                }
            }
        }

        // Rule 2: Any InProgress -> InProgress
        if (subtaskStatuses.any { it is TaskStatus.InProgress }) {
            return TaskStatus.InProgress
        }

        // Rule 3: Any Open -> Open
        if (subtaskStatuses.any { it is TaskStatus.Open }) {
            return TaskStatus.Open
        }

        // Rule 4: Any Blocked -> Blocked (collect all blocker IDs)
        if (subtaskStatuses.any { it is TaskStatus.Blocked }) {
            return combineBlocks(subtaskStatuses.filterIsInstance<TaskStatus.Blocked>())
        }

        // Rule 5: Otherwise Done (all are Done or Declined)
        return TaskStatus.Done
    }

    // ============ Blocker resolution logic ============

    /**
     * Check if all blockers in the given set are resolved (Done or Declined).
     * Returns true if all blockers exist and are resolved.
     */
    protected suspend fun areAllBlockersResolved(blockerIds: Set<String>): Boolean {
        return blockerIds.all { blockerTaskId ->
            val blockerTask = getTaskById(blockerTaskId)
            blockerTask != null &&
                    (blockerTask.status is TaskStatus.Done || blockerTask.status is TaskStatus.Declined)
        }
    }

    /**
     * Check and update blocked tasks when any blocker task's status changes.
     * A blocked task should only unblock (transition to InProgress) when ALL its blockers are Done or Declined.
     */
    protected suspend fun unblockTasksBlockedBy(blockerId: String) {
        getBlockedTasks().forEach { task ->
            val status = task.status
            if (status is TaskStatus.Blocked && blockerId in status.blockerTaskIds) {
                if (areAllBlockersResolved(status.blockerTaskIds)) {
                    val newStatus = TaskStatus.InProgress
                    recordStatusChange(
                        taskId = task.id,
                        previousStatus = status,
                        newStatus = newStatus,
                        automaticChangeReason = AutomaticChangeReason.Unblocked
                    )
                    val updated = task.copy(status = newStatus)
                    persistTaskUpdate(updated)
                    updateParentTasksStatus(task.id)
                }
            }
        }
    }

    /**
     * Get all blocked tasks. Subclasses implement based on their storage.
     */
    protected abstract suspend fun getBlockedTasks(): List<Task>

    /**
     * Handle the deletion of a task that might be blocking other tasks.
     * Removes the deleted blocker from blocked tasks and unblocks them if appropriate.
     * @param deletedBlockerId The ID of the task being deleted
     */
    protected suspend fun handleBlockerDeleted(deletedBlockerId: String) {
        getBlockedTasks().forEach { task ->
            val status = task.status
            if (status is TaskStatus.Blocked && deletedBlockerId in status.blockerTaskIds) {
                val remainingBlockers = status.blockerTaskIds - deletedBlockerId

                val shouldUnblock = areAllBlockersResolved(remainingBlockers)
                if (shouldUnblock) {
                    // Unblock the task
                    val newStatus = TaskStatus.InProgress
                    recordStatusChange(
                        taskId = task.id,
                        previousStatus = status,
                        newStatus = newStatus,
                        automaticChangeReason = AutomaticChangeReason.Unblocked
                    )
                    val updated = task.copy(status = newStatus)
                    persistTaskUpdate(updated)
                    updateParentTasksStatus(task.id)
                } else {
                    val updated = task.copy(
                        status = TaskStatus.Blocked(remainingBlockers, status.comment)
                    )
                    persistTaskUpdate(updated)
                }
            }
        }
    }

    /**
     * Update status for a list of parent tasks if they have auto-update enabled.
     * @param parentTasks The parent tasks to update
     */
    protected suspend fun updateParentStatuses(parentTasks: List<Task>) {
        parentTasks.forEach { parent ->
            updateParentStatusIfNeeded(parent.id)
        }
    }

    // ============ Recurrence processing ============

    /**
     * Process a recurrence trigger for a task.
     * Advances the recurrence state and resets the task for the next occurrence.
     * @param taskId The task ID
     * @param triggerEvent The event that triggered the recurrence
     * @return The updated task, or `null` if not found or not recurring
     *
     * Note: Subclasses may call [processRecurrenceTriggerInternal] from within a mutex lock.
     */
    abstract override suspend fun processRecurrenceTrigger(
        taskId: String,
        triggerEvent: RecurrenceTriggerEvent
    ): Task?

    /**
     * Internal implementation of recurrence trigger logic.
     * Can be called from subclasses within a mutex lock.
     */
    protected suspend fun processRecurrenceTriggerInternal(
        taskId: String,
        triggerEvent: RecurrenceTriggerEvent,
    ): Task? {
        val task = getTaskById(taskId) ?: return null

        val (newRules, newStatus) = RecurrenceService.processRecurrence(
            rules = task.recurrenceRules,
            triggerEvent = triggerEvent,
        ) ?: return null

        // Record status change in timeline if status is being reset (automatic recurrence)
        if (task.status != newStatus) {
            recordStatusChange(
                taskId = taskId,
                previousStatus = task.status,
                newStatus = newStatus,
                automaticChangeReason = AutomaticChangeReason.Recurrence
            )
        }

        val updated = task.copy(
            status = newStatus,
            recurrenceRules = newRules,
        )
        persistTaskUpdate(updated)
        return updated
    }

    /**
     * Process all date-based recurrences for tasks with past due dates.
     * Should be called periodically (e.g., on app startup).
     * @param currentTime The current time
     * @return List of tasks that were updated
     *
     * Note: Subclasses that need mutex protection should override this method
     * and call [processDateBasedRecurrencesInternal] from within a mutex lock.
     */
    abstract override suspend fun processDateBasedRecurrences(currentTime: Instant): List<Task>

    /**
     * Internal implementation of date-based recurrence processing.
     * Can be called from subclasses within a mutex lock.
     */
    protected suspend fun processDateBasedRecurrencesInternal(currentTime: Instant): List<Task> {
        val updatedTasks = mutableListOf<Task>()

        getRecurringTasksDueBefore(currentTime).forEach { task ->
            val triggerEvent = RecurrenceTriggerEvent(task.status, currentTime)
            processRecurrenceTriggerInternal(task.id, triggerEvent)?.let {
                updatedTasks.add(it)
            }
        }

        return updatedTasks
    }

    /**
     * Get recurring tasks with due date before or at the given time.
     * Subclasses implement based on their storage for optimal performance.
     */
    protected abstract suspend fun getRecurringTasksDueBefore(time: Instant): List<Task>

    // ============ Status update logic ============


    // ============ Parent status update logic ============

    /**
     * Recursively update parent tasks' statuses if they have auto-update enabled.
     * Called when a child task's status changes.
     */
    protected suspend fun updateParentTasksStatus(childTaskId: String) {
        val parentTasks = getParentTasks(childTaskId)
        updateParentStatuses(parentTasks)
    }

    /**
     * Record a status change in the timeline. Subclasses implement based on their storage.
     */
    protected abstract suspend fun recordStatusChange(
        taskId: String,
        previousStatus: TaskStatus?,
        newStatus: TaskStatus,
        automaticChangeReason: AutomaticChangeReason? = null
    )

    /**
     * Persist a task update. Subclasses implement based on their storage.
     */
    protected abstract suspend fun persistTaskUpdate(task: Task)

    // ============ Total calculations (due date and priority) ============

    /**
     * Calculate the total (effective) due date for a task.
     * Takes into account tasks that depend on this task and tasks blocked by this task.
     * @param task The task to calculate the total due date for
     * @param blockedTasks List of all blocked tasks (for checking if this task is a blocker)
     * @param tasksById Map of task ID to task for efficient lookups
     * @param visited Set of already visited task IDs to prevent cycles
     */
    protected fun calculateTotalDueDate(
        task: Task,
        blockedTasks: List<Task>,
        tasksById: Map<String, Task>,
        visited: Set<String> = emptySet()
    ): Instant? {
        if (task.id in visited) return null
        val newVisited = visited + task.id

        val dependentDueDates = task.connections
            .filter { it.type == ConnectionType.IsDependencyOf }
            .mapNotNull { connection ->
                tasksById[connection.targetTaskId]?.let {
                    calculateTotalDueDate(it, blockedTasks, tasksById, newVisited)
                }
            }

        val blockedTaskDueDates = blockedTasks
            .mapNotNull { blockedTask ->
                val status = blockedTask.status
                if (blockedTask.id !in newVisited &&
                    status is TaskStatus.Blocked &&
                    task.id in status.blockerTaskIds
                ) {
                    calculateTotalDueDate(blockedTask, blockedTasks, tasksById, newVisited)
                } else null
            }

        return (listOfNotNull(task.dueDate) + dependentDueDates + blockedTaskDueDates).minOrNull()
    }

    /**
     * Calculate the total (effective) priority for a task.
     * Takes into account tasks that depend on this task and tasks blocked by this task.
     * @param task The task to calculate total priority for
     * @param blockedTasks List of all blocked tasks (for checking if this task is a blocker)
     * @param tasksById Map of task ID to task for efficient lookups
     * @param visited Set of already visited task IDs to prevent cycles
     */
    protected fun calculateTotalPriority(
        task: Task,
        blockedTasks: List<Task>,
        tasksById: Map<String, Task>,
        visited: MutableSet<String> = mutableSetOf()
    ): Priority? {
        if (!visited.add(task.id)) return null

        val dependentPriorities = task.connections
            .filter { it.type == ConnectionType.IsDependencyOf }
            .mapNotNull { connection ->
                tasksById[connection.targetTaskId]?.let {
                    calculateTotalPriority(it, blockedTasks, tasksById, visited)
                }
            }

        val blockedTaskPriorities = blockedTasks
            .mapNotNull { blockedTask ->
                val status = blockedTask.status
                if (blockedTask.id !in visited &&
                    status is TaskStatus.Blocked &&
                    task.id in status.blockerTaskIds
                ) {
                    calculateTotalPriority(blockedTask, blockedTasks, tasksById, visited)
                } else null
            }

        return (listOfNotNull(task.priority) + dependentPriorities + blockedTaskPriorities).maxOrNull()
    }

    // ============ Task update business logic ============

    /**
     * Result of calculating the final task status on update.
     */
    protected data class TaskStatusUpdateResult(
        val task: Task,
        val automaticChangeReason: AutomaticChangeReason?
    )

    /**
     * Calculate the final status after a task update, considering autoUpdateStatusFromSubtasks.
     * Call this after connections have been synced.
     *
     * @param taskId The task being updated
     * @param inputTask The task with updated values (connections already synced)
     * @param oldTask The task before the update
     * @return The task with potentially calculated status from subtasks and the reason if automatic
     */
    protected suspend fun calculateFinalTaskStatusOnUpdate(
        taskId: String,
        inputTask: Task,
        oldTask: Task
    ): TaskStatusUpdateResult {
        // Prevent manual status changes when autoUpdateStatusFromSubtasks is enabled
        require(
            !inputTask.autoUpdateStatusFromSubtasks ||
                    inputTask.status == oldTask.status ||
                    !oldTask.autoUpdateStatusFromSubtasks
        ) { "Cannot manually change status when autoUpdateStatusFromSubtasks is enabled" }

        // Check if autoUpdateStatusFromSubtasks was just enabled
        val autoUpdateJustEnabled = inputTask.autoUpdateStatusFromSubtasks && !oldTask.autoUpdateStatusFromSubtasks

        // Check if ParentOf connections changed (subtasks added/removed)
        val oldParentOfConnections = oldTask.connections.filter { it.type == ConnectionType.ParentOf }.toSet()
        val newParentOfConnections = inputTask.connections.filter { it.type == ConnectionType.ParentOf }.toSet()
        val subtasksChanged = oldParentOfConnections != newParentOfConnections

        var finalTask = inputTask
        var automaticReason: AutomaticChangeReason? = null

        // If autoUpdateStatusFromSubtasks is enabled AND (it was just enabled OR subtasks changed),
        // calculate status from subtasks
        if (finalTask.autoUpdateStatusFromSubtasks && (autoUpdateJustEnabled || subtasksChanged)) {
            val calculatedStatus = getCalculatedStatusFromSubtasks(taskId)
            if (calculatedStatus != null && calculatedStatus != finalTask.status) {
                finalTask = finalTask.copy(status = calculatedStatus)
                val subtasks = getSubtasks(taskId)
                automaticReason = AutomaticChangeReason.UpdatedFromSubtasks(subtasks.map { it.id })
            }
        }

        // If setting status to Blocked, check if all blockers are already resolved
        // (but only if there are actual blockers - empty blockers means it should stay Blocked)
        val newStatus = finalTask.status
        if (newStatus is TaskStatus.Blocked &&
            newStatus.blockerTaskIds.isNotEmpty() &&
            areAllBlockersResolved(newStatus.blockerTaskIds)
        ) {
            finalTask = finalTask.copy(status = TaskStatus.InProgress)
        }

        return TaskStatusUpdateResult(finalTask, automaticReason)
    }

    /**
     * Handle status change tracking and cascading updates after a task update.
     * Should be called after the task has been persisted.
     *
     * @param finalTask The task after all updates
     * @param oldTask The task before the update
     * @param automaticChangeReason The reason if the status change was automatic, null if manual
     */
    protected suspend fun handleStatusChangeOnUpdate(
        finalTask: Task,
        oldTask: Task,
        automaticChangeReason: AutomaticChangeReason?
    ) {
        if (oldTask.status != finalTask.status) {
            recordStatusChange(
                taskId = finalTask.id,
                previousStatus = oldTask.status,
                newStatus = finalTask.status,
                automaticChangeReason = automaticChangeReason
            )
        }
    }

    /**
     * Handle cascading updates after a task's status changes.
     * Updates parent tasks and unblocks blocked tasks if needed.
     *
     * @param taskId The ID of the task whose status changed
     * @param oldStatus The previous status
     * @param newStatus The new status
     */
    protected suspend fun handleStatusCascadeOnUpdate(
        taskId: String,
        oldStatus: TaskStatus,
        newStatus: TaskStatus
    ) {
        // If status class changed, update parent tasks and unblock blocked tasks
        if (oldStatus::class != newStatus::class) {
            updateParentTasksStatus(taskId)
            unblockTasksBlockedBy(taskId)
        }
    }

    // ============ Auto-update parent status on subtask change ============

    /**
     * Update parent task's status if it has autoUpdateStatusFromSubtasks enabled.
     * Called when a subtask is added or removed.
     * @param parentTaskId The ID of the parent task to potentially update
     */
    protected suspend fun updateParentStatusIfNeeded(parentTaskId: String) {
        val parentTask = getTaskById(parentTaskId) ?: return
        if (!parentTask.autoUpdateStatusFromSubtasks) return

        val calculatedStatus = getCalculatedStatusFromSubtasks(parentTaskId)
        if (calculatedStatus != null && calculatedStatus != parentTask.status) {
            val subtasks = getSubtasks(parentTaskId)
            recordStatusChange(
                taskId = parentTaskId,
                previousStatus = parentTask.status,
                newStatus = calculatedStatus,
                automaticChangeReason = AutomaticChangeReason.UpdatedFromSubtasks(subtasks.map { it.id })
            )
            val updated = parentTask.copy(status = calculatedStatus)
            persistTaskUpdate(updated)
            updateParentTasksStatus(parentTaskId)
            unblockTasksBlockedBy(parentTaskId)
        }
    }

    // ============ Connection query methods ============

    /**
     * Get all tasks that this task depends on
     */
    override suspend fun getDependencies(taskId: String): List<Task> {
        val task = getTaskById(taskId) ?: return emptyList()
        val targetIds = task.connections
            .filter { it.type == ConnectionType.DependsOn }
            .map { it.targetTaskId }
            .toSet()
        return getTasksByIds(targetIds)
    }

    /**
     * Get all tasks that depend on this task
     */
    override suspend fun getDependents(taskId: String): List<Task> {
        val task = getTaskById(taskId) ?: return emptyList()
        val targetIds = task.connections
            .filter { it.type == ConnectionType.IsDependencyOf }
            .map { it.targetTaskId }
            .toSet()
        return getTasksByIds(targetIds)
    }

    /**
     * Get all related tasks (RelatesTo connection)
     */
    override suspend fun getRelatedTasks(taskId: String): List<Task> {
        val task = getTaskById(taskId) ?: return emptyList()
        val targetIds = task.connections
            .filter { it.type == ConnectionType.RelatesTo }
            .map { it.targetTaskId }
            .toSet()
        return getTasksByIds(targetIds)
    }

    /**
     * Get connections grouped by type
     */
    override suspend fun getConnectionsByType(taskId: String): Map<ConnectionType, List<Task>> {
        val task = getTaskById(taskId) ?: return emptyMap()
        val allTargetIds = task.connections.map { it.targetTaskId }.toSet()
        val tasksById = getTasksByIds(allTargetIds).associateBy { it.id }
        return task.connections
            .groupBy { it.type }
            .mapValues { (_, connections) ->
                connections.mapNotNull { tasksById[it.targetTaskId] }
            }
    }

    override suspend fun resolveConnections(connections: Set<TaskConnection>): Map<ConnectionType, List<Task>> {
        val allTargetIds = connections.map { it.targetTaskId }.toSet()
        val tasksById = getTasksByIds(allTargetIds).associateBy { it.id }
        return connections.groupBy { it.type }.mapValues { (_, connectionsInType) ->
            connectionsInType.mapNotNull { tasksById[it.targetTaskId] }
        }
    }

    // ============ Import/Export helpers ============

    /**
     * Create a mapping from old task IDs to new task IDs for import operations.
     * @param tasks The tasks being imported
     * @param newPrefix The new prefix for task IDs
     * @return A map from old task ID to new task ID
     */
    protected fun createTaskIdMapping(tasks: List<Task>, newPrefix: String): Map<String, String> {
        return tasks.associate { task ->
            val oldIdNum = task.id.substringAfterLast("-").toIntOrNull() ?: 0
            task.id to "$newPrefix-$oldIdNum"
        }
    }

    /**
     * Remap a TaskStatus.Blocked to use new task IDs.
     * @param status The status to remap
     * @param oldToNewTaskId The mapping from old to new task IDs
     * @return The remapped status, or TaskStatus.Open if all blockers were removed
     */
    protected fun remapBlockedStatus(status: TaskStatus, oldToNewTaskId: Map<String, String>): TaskStatus {
        return when (status) {
            is TaskStatus.Blocked -> {
                val remappedBlockers = status.blockerTaskIds.mapNotNull { oldToNewTaskId[it] }.toSet()
                if (remappedBlockers.isEmpty()) TaskStatus.Open else TaskStatus.Blocked(
                    remappedBlockers,
                    status.comment
                )
            }

            else -> status
        }
    }

    // ============ Cross-space cleanup on space deletion ============

    /**
     * Handle cross-space relationships when a space is deleted.
     * Updates tasks in other spaces that have connections to or are blocked by tasks in the deleted space.
     * @param taskIdsInDeletedSpace The IDs of tasks that will be deleted with the space
     */
    protected suspend fun handleCrossSpaceRelationshipsOnSpaceDeletion(taskIdsInDeletedSpace: Set<String>) {
        // Get all tasks from other spaces and check their relationships
        getAllTasks().forEach { space ->
            getTasksInSpace(space.id).forEach { task ->
                var modified = false
                var updatedTask = task

                // Remove connections pointing to tasks in the deleted space
                val connectionsToRemove = task.connections.filter { it.targetTaskId in taskIdsInDeletedSpace }
                if (connectionsToRemove.isNotEmpty()) {
                    updatedTask = updatedTask.copy(
                        connections = updatedTask.connections - connectionsToRemove.toSet()
                    )
                    removeConnectionsToDeletedTasks(task.id, connectionsToRemove)
                    modified = true
                }

                // Update blocked status if blocker is being deleted
                val status = task.status
                if (status is TaskStatus.Blocked) {
                    val remainingBlockers = status.blockerTaskIds - taskIdsInDeletedSpace
                    if (remainingBlockers != status.blockerTaskIds) {
                        val newStatus = if (remainingBlockers.isEmpty()) {
                            TaskStatus.InProgress
                        } else {
                            TaskStatus.Blocked(remainingBlockers, status.comment)
                        }
                        updatedTask = updatedTask.copy(status = newStatus)
                        modified = true
                    }
                }

                if (modified) {
                    persistTaskUpdate(updatedTask)
                }
            }
        }
    }

    /**
     * Get all tasks in a specific space.
     * Subclasses implement based on their storage.
     */
    protected abstract suspend fun getTasksInSpace(spaceId: String): List<Task>

    /**
     * Remove connections from a task that point to deleted tasks.
     * Subclasses implement based on their storage.
     */
    protected abstract suspend fun removeConnectionsToDeletedTasks(
        taskId: String,
        connections: List<TaskConnection>
    )

    // ============ Shared filtering logic ============

    /**
     * Parses comma-separated task IDs into a set of normalized (uppercase, trimmed) IDs.
     */
    protected fun parseTaskIds(taskIdsString: String): Set<String> =
        if (taskIdsString.isBlank()) emptySet()
        else taskIdsString.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * Checks if task connections match the specified task IDs for each connection type.
     * All non-empty filters must match for the result to be true.
     */
    protected fun matchesConnectionsByTaskIds(
        connections: Set<TaskConnection>,
        filtersByType: Map<ConnectionType, String>
    ): Boolean = filtersByType.all { (connectionType, taskIdsString) ->
        val taskIds = parseTaskIds(taskIdsString)
        taskIds.isEmpty() || connections.any { connection ->
            connection.type == connectionType && connection.targetTaskId.uppercase() in taskIds
        }
    }

    /**
     * Sorting comparator for Priority view:
     * 1. Closeness of due time (those without due time have lower priority)
     * 2. Descending by priority (higher priority first, null priority last)
     * 3. Ascending by ID
     */
    protected fun prioritySortComparator(): Comparator<TaskWithTotals> = compareBy<TaskWithTotals>(
        // Due date: closer dates first, null dates last
        { it.totalDueDate == null },
        { it.totalDueDate },
        // Priority: higher priority first (descending), null priority last
        { it.totalPriority == null },
        { -(it.totalPriority?.value ?: 0) },
        // ID: ascending
        { it.task.id.takeLastWhile(Char::isDigit).toInt() }
    )

    /**
     * Filter tasks based on the provided criteria.
     * This is the shared filtering logic used by both repository implementations.
     */
    protected fun filterTasksWithCriteria(
        tasks: List<TaskWithTotals>,
        criteria: TaskFilterCriteria
    ): List<TaskWithTotals> {
        val now = clock.now()
        val todayStart = now.toLocalDateTime(TimeZone.currentSystemDefault()).date

        return tasks.filter { taskWithTotals ->
            val task = taskWithTotals.task

            // Text search filter - searches across ALL selected fields simultaneously
            val matchesTextSearch = if (criteria.searchQuery.isBlank()) true
            else {
                val queries = criteria.searchQuery.trim().split(Regex("\\s+"))
                queries.all { query ->
                    criteria.textSearchFields.any { field ->
                        when (field) {
                            TaskTextSearchField.Id -> task.id.contains(query, ignoreCase = true)
                            TaskTextSearchField.Title -> task.title.contains(query, ignoreCase = true)
                            TaskTextSearchField.Tags -> task.tags.any { it.contains(query, ignoreCase = true) }
                            TaskTextSearchField.Description -> task.description.contains(query, ignoreCase = true)
                        }
                    }
                }
            }

            // Status filter (if any status selected, task must match one of them)
            val matchesStatus = if (criteria.statusFilters.isEmpty()) true
            else criteria.statusFilters.any { statusFilter ->
                when (statusFilter) {
                    is TaskStatus.Open -> task.status is TaskStatus.Open
                    is TaskStatus.Blocked -> {
                        if (task.status !is TaskStatus.Blocked) return@any false

                        // Check blockedByTaskIds filter
                        val blockedByIds = parseTaskIds(criteria.blockedByTaskIds)
                        val matchesBlockerIds = blockedByIds.isEmpty() ||
                                task.status.blockerTaskIds.any { it.uppercase() in blockedByIds }

                        // Check blockedByComment filter
                        val matchesComment = criteria.blockedByComment.isBlank() ||
                                task.status.comment.contains(criteria.blockedByComment, ignoreCase = true)

                        matchesBlockerIds && matchesComment
                    }

                    is TaskStatus.InProgress -> task.status is TaskStatus.InProgress
                    is TaskStatus.Done -> task.status is TaskStatus.Done
                    is TaskStatus.Declined -> {
                        if (task.status !is TaskStatus.Declined) return@any false

                        // Check declinedReason filter
                        criteria.declinedReason.isBlank() ||
                                task.status.reason.contains(criteria.declinedReason, ignoreCase = true)
                    }
                }
            }

            // Due date filter
            val dueDate = task.dueDate
            val matchesDueDate = when (criteria.dueDateFilter) {
                DueDateFilter.Any -> true
                DueDateFilter.NoDueDate -> dueDate == null
                DueDateFilter.Overdue -> dueDate != null && dueDate < now
                DueDateFilter.Today -> dueDate != null &&
                        dueDate.toLocalDateTime(TimeZone.currentSystemDefault()).date == todayStart

                DueDateFilter.ThisWeek -> dueDate != null && run {
                    val dueLocalDate = dueDate.toLocalDateTime(TimeZone.currentSystemDefault()).date
                    val daysDiff = dueLocalDate.toEpochDays() - todayStart.toEpochDays()
                    daysDiff in 0..6
                }

                DueDateFilter.ThisMonth -> dueDate != null && run {
                    val dueLocalDate = dueDate.toLocalDateTime(TimeZone.currentSystemDefault()).date
                    dueLocalDate.year == todayStart.year && dueLocalDate.month == todayStart.month
                }

                DueDateFilter.Custom -> {
                    val after = criteria.customDueDateAfter
                    val before = criteria.customDueDateBefore
                    when {
                        dueDate == null -> false
                        after != null && before != null -> dueDate >= after && dueDate <= before
                        after != null -> dueDate >= after
                        before != null -> dueDate <= before
                        else -> true
                    }
                }
            }

            // Priority filter
            val priority = task.priority
            val matchesPriority = when (criteria.priorityFilter) {
                PriorityFilter.Any -> true
                PriorityFilter.NoPriority -> priority == null
                PriorityFilter.High -> priority != null && priority.value >= 75
                PriorityFilter.Medium -> priority != null && priority.value in 50..74
                PriorityFilter.Low -> priority != null && priority.value < 50
                PriorityFilter.Custom -> {
                    val min = criteria.customPriorityMin.toIntOrNull()
                    val max = criteria.customPriorityMax.toIntOrNull()
                    when {
                        priority == null -> false
                        min != null && max != null -> priority.value in min..max
                        min != null -> priority.value >= min
                        max != null -> priority.value <= max
                        else -> true
                    }
                }
            }

            // Estimated time filter (time is stored in seconds)
            val estimatedTime = task.estimatedTime
            val estimatedTimeSeconds = estimatedTime?.toApproximateSeconds()
            val matchesEstimatedTime = when (criteria.estimatedTimeFilter) {
                EstimatedTimeFilter.Any -> true
                EstimatedTimeFilter.NoEstimate -> estimatedTime == null
                EstimatedTimeFilter.Quick -> estimatedTimeSeconds != null && estimatedTimeSeconds < 15 * 60 // < 15 min
                EstimatedTimeFilter.Short -> estimatedTimeSeconds != null && estimatedTimeSeconds in (15 * 60)..(30 * 60 - 1) // 15-30 min
                EstimatedTimeFilter.Medium -> estimatedTimeSeconds != null && estimatedTimeSeconds in (30 * 60)..(60 * 60 - 1) // 30 min - 1 hr
                EstimatedTimeFilter.Long -> estimatedTimeSeconds != null && estimatedTimeSeconds in (60 * 60)..(4 * 60 * 60 - 1) // 1-4 hrs
                EstimatedTimeFilter.VeryLong -> estimatedTimeSeconds != null && estimatedTimeSeconds >= 4 * 60 * 60 // > 4 hrs
                EstimatedTimeFilter.Custom -> {
                    val minPeriod = parseCompactTimeToPeriod(criteria.customEstimatedTimeMin)
                    val maxPeriod = parseCompactTimeToPeriod(criteria.customEstimatedTimeMax)
                    val minSeconds = minPeriod?.toApproximateSeconds()
                    val maxSeconds = maxPeriod?.toApproximateSeconds()

                    when {
                        estimatedTimeSeconds == null -> false
                        minSeconds != null && maxSeconds != null -> estimatedTimeSeconds in minSeconds..maxSeconds
                        minSeconds != null -> estimatedTimeSeconds >= minSeconds
                        maxSeconds != null -> estimatedTimeSeconds <= maxSeconds
                        else -> true
                    }
                }
            }

            // Recurrence filter - matches if ANY rule matches the criteria
            val matchesRecurrence = when (criteria.recurrenceFilter) {
                RecurrenceFilter.Any -> true
                RecurrenceFilter.NoRecurrence -> task.recurrenceRules.isEmpty()
                RecurrenceFilter.HasRecurrence -> task.recurrenceRules.isNotEmpty()
                RecurrenceFilter.AfterTimeout -> task.recurrenceRules.any { (rule, _) ->
                    rule.timeRecurrenceTrigger is AfterTimeout
                }

                RecurrenceFilter.FixedDaysOfWeek -> task.recurrenceRules.any { (rule, _) ->
                    rule.timeRecurrenceTrigger.let {
                        it is AtFixedPoints && it.pattern is FixedPointPattern.DaysOfWeek
                    }
                }

                RecurrenceFilter.FixedDayOfMonth -> task.recurrenceRules.any { (rule, _) ->
                    val recurrenceTrigger = rule.timeRecurrenceTrigger
                    recurrenceTrigger is AtFixedPoints && recurrenceTrigger.pattern is FixedPointPattern.DayOfMonth
                }

                RecurrenceFilter.NthDayOfWeek -> task.recurrenceRules.any { (rule, _) ->
                    val recurrenceTrigger = rule.timeRecurrenceTrigger
                    recurrenceTrigger is AtFixedPoints && recurrenceTrigger.pattern is FixedPointPattern.NthDayOfWeekInMonth
                }

                RecurrenceFilter.Yearly -> task.recurrenceRules.any { (rule, _) ->
                    val recurrenceTrigger = rule.timeRecurrenceTrigger
                    recurrenceTrigger is AtFixedPoints && (recurrenceTrigger.pattern is YearlyOnDate || recurrenceTrigger.pattern is NthDayOfWeekInMonths)
                }
            }

            // Notifications filter
            val matchesNotifications = when (criteria.notificationsFilter) {
                NotificationsFilter.Any -> true
                NotificationsFilter.NoNotifications -> task.notifications.isEmpty()
                NotificationsFilter.HasNotifications -> task.notifications.isNotEmpty()
            }

            // Auto-update status filter
            val matchesAutoUpdateStatus = when (criteria.autoUpdateStatusFilter) {
                AutoUpdateStatusFilter.Any -> true
                AutoUpdateStatusFilter.Auto -> task.autoUpdateStatusFromSubtasks
                AutoUpdateStatusFilter.Manual -> !task.autoUpdateStatusFromSubtasks
            }

            // Connection type filter - can select multiple types
            val matchesConnection = if (criteria.connectionTypeFilters.isEmpty()) true
            else {
                criteria.connectionTypeFilters.any { typeFilter ->
                    when (typeFilter) {
                        ConnectionTypeOption.DependsOn -> task.connections.any { it.type == ConnectionType.DependsOn }
                        ConnectionTypeOption.IsDependencyOf -> task.connections.any { it.type == ConnectionType.IsDependencyOf }
                        ConnectionTypeOption.RelatesTo -> task.connections.any { it.type == ConnectionType.RelatesTo }
                        ConnectionTypeOption.SubtaskOf -> task.connections.any { it.type == ConnectionType.SubtaskOf }
                        ConnectionTypeOption.ParentOf -> task.connections.any { it.type == ConnectionType.ParentOf }
                        ConnectionTypeOption.NotSubtask -> task.connections.none { it.type == ConnectionType.SubtaskOf }
                    }
                }
            }

            // Specific task ID filter for connections - check each connection type separately
            val matchesSpecificConnections = matchesConnectionsByTaskIds(
                task.connections,
                mapOf(
                    ConnectionType.DependsOn to criteria.dependsOnTaskIds,
                    ConnectionType.IsDependencyOf to criteria.isDependencyOfTaskIds,
                    ConnectionType.RelatesTo to criteria.relatesToTaskIds,
                    ConnectionType.SubtaskOf to criteria.subtaskOfTaskIds,
                    ConnectionType.ParentOf to criteria.parentOfTaskIds
                )
            )

            // Tags filter with match mode
            val matchesTags = if (criteria.selectedTags.isEmpty()) true
            else when (criteria.tagMatchMode) {
                TagMatchMode.All -> criteria.selectedTags.all { it in task.tags }
                TagMatchMode.Any -> criteria.selectedTags.any { it in task.tags }
            }

            matchesTextSearch && matchesStatus && matchesDueDate && matchesPriority &&
                    matchesEstimatedTime && matchesRecurrence && matchesNotifications &&
                    matchesAutoUpdateStatus && matchesConnection && matchesSpecificConnections && matchesTags
        }
    }
}
