@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Data class representing the exported space data in JSON format.
 * Contains all information needed to fully restore a space with all its tasks.
 */
@Serializable
data class SpaceExportData(
    val space: Space,
    val tasks: List<Task>,
    val statusTimelines: Map<String, List<StatusChange>>,
    val nextId: Int,
    val tags: Set<String>
)

/**
 * In-memory implementation of task repository.
 * Data is not persisted - useful for testing or temporary usage.
 * All operations are thread-safe via a coroutine Mutex.
 *
 * Note: Space selection is managed by the UI layer. All methods that operate
 * on tasks within a space require an explicit spaceId parameter.
 */
class InMemoryTaskRepository(clock: Clock = Clock.System) : AbstractTaskRepository(clock) {
    private val mutex = Mutex()
    private val tasks = mutableMapOf<String, Task>()
    private val statusTimelines = mutableMapOf<String, List<StatusChange>>()
    private val spaces = mutableMapOf<String, Space>()
    private val nextIdBySpace = mutableMapOf<String, Int>()
    private val allTags = mutableSetOf<String>()
    private val filterStateBySpaceId = mutableMapOf<String, TaskFilterCriteria>()
    private val viewModeBySpaceId = mutableMapOf<String, String>()
    private val filterPanelOpenBySpaceId = mutableMapOf<String, Boolean>()

    override suspend fun hasSpaces(): Boolean = mutex.withLock { spaces.isNotEmpty() }

    override suspend fun getAllSpaces(): List<Space> = mutex.withLock { spaces.values.toList() }

    override suspend fun getSpaceById(id: String): Space? = mutex.withLock { spaces[id] }

    override suspend fun filterSpaces(
        query: String,
        searchInName: Boolean,
        searchInPrefix: Boolean
    ): List<Space> {
        if (query.isBlank()) return spaces.values.toList()

        return spaces.values.filter { space ->
            val matchesName = searchInName && space.name.contains(query, ignoreCase = true)
            val matchesPrefix = searchInPrefix && space.idPrefix.contains(query, ignoreCase = true)
            matchesName || matchesPrefix
        }
    }

    override suspend fun createSpace(name: String, idPrefix: String): Space? = mutex.withLock {
        // Validate prefix
        if (!idPrefix.matches(Regex("^[A-Z]+$")) || idPrefix.isEmpty()) return@withLock null

        // Check if prefix is already used
        if (spaces.values.any { it.idPrefix == idPrefix }) return@withLock null

        val spaceId = "space-${spaces.size}-${idPrefix}"
        val space = Space(
            id = spaceId,
            name = name,
            idPrefix = idPrefix
        )
        spaces[spaceId] = space
        nextIdBySpace[spaceId] = 1
        space
    }

    override suspend fun updateSpaceName(spaceId: String, newName: String): Boolean = mutex.withLock {
        val space = spaces[spaceId] ?: return@withLock false
        if (newName.isBlank()) return@withLock false

        val updatedSpace = space.copy(name = newName)
        spaces[spaceId] = updatedSpace
        true
    }

    override suspend fun deleteSpace(spaceId: String): Boolean = mutex.withLock {
        if (!spaces.containsKey(spaceId)) return@withLock false

        // Get all task IDs in this space before deletion
        val taskIdsInSpace = tasks.values.filter { it.spaceId == spaceId }.map { it.id }.toSet()

        // Handle cross-space relationships
        handleCrossSpaceRelationshipsOnSpaceDeletionUnsafe(taskIdsInSpace)

        // Remove all tasks and their timelines in this space directly
        taskIdsInSpace.forEach { taskId ->
            tasks.remove(taskId)
            statusTimelines.remove(taskId)
        }

        spaces.remove(spaceId)
        nextIdBySpace.remove(spaceId)
        filterStateBySpaceId.remove(spaceId)
        viewModeBySpaceId.remove(spaceId)
        filterPanelOpenBySpaceId.remove(spaceId)

        true
    }

    override suspend fun getTasksInSpace(spaceId: String): List<Task> = mutex.withLock {
        tasks.values.filter { it.spaceId == spaceId }
    }

    override suspend fun removeConnectionsToDeletedTasks(taskId: String, connections: List<TaskConnection>) {
        // In-memory: connections are already removed via the copy in handleCrossSpaceRelationshipsOnSpaceDeletion
        // No additional action needed
    }

    override suspend fun getAll(spaceId: String): List<Task> = mutex.withLock {
        tasks.values.filter { it.spaceId == spaceId }.toList()
    }

    override suspend fun filterTasksForSelection(
        spaceId: String,
        excludeTaskId: String?,
        searchQuery: String
    ): List<Task> = mutex.withLock {
        tasks.values.filter { task ->
            task.spaceId == spaceId &&
            (excludeTaskId == null || task.id != excludeTaskId) &&
            (searchQuery.isBlank() ||
             task.id.contains(searchQuery, ignoreCase = true) ||
             task.title.contains(searchQuery, ignoreCase = true))
        }
    }

    override suspend fun searchTasksForConnection(
        spaceId: String,
        excludeTaskId: String?,
        searchQuery: String,
        excludeTaskIds: Set<String>,
        connectionType: ConnectionType,
        existingConnections: Set<TaskConnection>
    ): List<Task> = mutex.withLock {
        tasks.values.filter { task ->
            // Filter by space
            task.spaceId == spaceId &&
            // Exclude current task
            (excludeTaskId == null || task.id != excludeTaskId) &&
            // Exclude additional task IDs
            task.id !in excludeTaskIds &&
            // Filter by search query (case-insensitive)
            (searchQuery.isBlank() ||
                task.id.contains(searchQuery, ignoreCase = true) ||
                task.title.contains(searchQuery, ignoreCase = true)) &&
            // Check for cycles - this is done in the repository layer
            !wouldCreateCycle(excludeTaskId, task.id, connectionType, existingConnections)
        }
    }

    override suspend fun getAllSpacePrefixes(): List<String> = mutex.withLock {
        spaces.values.map { it.idPrefix }
    }

    override suspend fun getAllTasksWithTotals(spaceId: String): List<TaskWithTotals> = mutex.withLock {
        val spaceTasks = tasks.values.filter { it.spaceId == spaceId }
        val blockedTasks = getBlockedTasks()
        val tasksById = spaceTasks.associateBy { it.id }
        spaceTasks.map { task ->
            TaskWithTotals(
                task = task,
                totalDueDate = calculateTotalDueDate(task, blockedTasks, tasksById),
                totalPriority = calculateTotalPriority(task, blockedTasks, tasksById)
            )
        }
    }

    // Note: getById does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches SqlDelightTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun getTaskById(id: String): Task? = tasks[id]

    override suspend fun getTasksByIds(ids: Set<String>): List<Task> = ids.mapNotNull { tasks[it] }

    override suspend fun getTasksByIdWithTotals(id: String): TaskWithTotals? = mutex.withLock {
        tasks[id]?.let { task ->
            val blockedTasks = getBlockedTasks()
            val tasksById = tasks.values.associateBy { it.id }
            TaskWithTotals(
                task = task,
                totalDueDate = calculateTotalDueDate(task, blockedTasks, tasksById),
                totalPriority = calculateTotalPriority(task, blockedTasks, tasksById)
            )
        }
    }

    override suspend fun getAllTags(): Set<String> = mutex.withLock { allTags.toSet() }

    override suspend fun filterTags(searchQuery: String, excludeTags: Set<String>): List<String> = mutex.withLock {
        val availableTags = allTags.filter { it !in excludeTags }
        if (searchQuery.isBlank()) {
            availableTags.sorted()
        } else {
            availableTags.filter { it.contains(searchQuery, ignoreCase = true) }.sorted()
        }
    }

    override suspend fun addTag(tag: String): Boolean = mutex.withLock {
        if (tag.isBlank()) return@withLock false
        allTags.add(tag.trim())
        true
    }

    override suspend fun deleteTag(tag: String): Boolean = mutex.withLock {
        if (tag.isBlank()) return@withLock false
        allTags.remove(tag.trim())
    }

    override suspend fun getStatusTimeline(taskId: String): List<StatusChange> = mutex.withLock {
        statusTimelines[taskId] ?: emptyList()
    }

    override suspend fun getStatusChangesByDate(spaceId: String, year: Int, month: Int): Map<LocalDate, List<StatusChangeEvent>> = mutex.withLock {
        val changesByDate = mutableMapOf<LocalDate, MutableList<StatusChangeEvent>>()

        tasks.values
            .filter { it.spaceId == spaceId }
            .forEach { task ->
                (statusTimelines[task.id] ?: emptyList()).forEach { statusChange ->
                    val dateTime = statusChange.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
                    val date = dateTime.date

                    if (date.year == year && date.monthNumber == month) {
                        val event = StatusChangeEvent(task, statusChange)
                        changesByDate.getOrPut(date) { mutableListOf() }.add(event)
                    }
                }
            }

        changesByDate.mapValues { (_, events) ->
            events.sortedByDescending { it.statusChange.timestamp }
        }
    }

    override suspend fun peekNextId(spaceId: String): String = mutex.withLock {
        val space = spaces[spaceId] ?: return@withLock "TASK-1"
        val nextNum = nextIdBySpace.getOrPut(spaceId) { 1 }
        "${space.idPrefix}-$nextNum"
    }

    private fun generateNextIdUnsafe(spaceId: String): String {
        val space = spaces[spaceId] ?: return "TASK-1"
        val nextNum = nextIdBySpace.getOrPut(spaceId) { 1 }
        nextIdBySpace[spaceId] = nextNum + 1
        return "${space.idPrefix}-$nextNum"
    }

    override suspend fun addTask(
        spaceId: String,
        title: String,
        description: String,
        status: TaskStatus,
        dueDate: Instant?,
        priority: Priority?,
        estimatedTime: RecurrencePeriod?,
        tags: Set<String>,
        connections: Set<TaskConnection>,
        notifications: List<TaskNotification>,
        customId: String?,
        recurrenceRules: List<RecurrenceRule>,
        resetStatusOnRecurrence: TaskStatus,
        autoUpdateStatusFromSubtasks: Boolean
    ): Task? = mutex.withLock {
        if (!spaces.containsKey(spaceId)) return@withLock null
        val taskId = customId ?: generateNextIdUnsafe(spaceId)

        val recurrenceState = RecurrenceService.initializeRecurrence(recurrenceRules)

        val status = if (autoUpdateStatusFromSubtasks) {
            val subtasksIds = connections
                .mapNotNull { if (it.type == ConnectionType.ParentOf) it.targetTaskId else null }
            getCalculatedStatusFromSubtasks(subtasksIds, ::getTaskById) ?: status
        } else {
            status
        }

        val task = Task(
            id = taskId,
            title = title,
            description = description,
            status = status,
            dueDate = dueDate,
            priority = priority,
            estimatedTime = estimatedTime,
            tags = tags,
            connections = connections,
            notifications = notifications,
            spaceId = spaceId,
            recurrenceRules = recurrenceRules,
            recurrenceState = recurrenceState,
            resetStatusOnRecurrence = resetStatusOnRecurrence,
            autoUpdateStatusFromSubtasks = autoUpdateStatusFromSubtasks
        )
        tasks[task.id] = task

        statusTimelines[taskId] = listOf(
            StatusChange(
                timestamp = clock.now(),
                previousStatus = null,
                newStatus = status
            )
        )
        allTags.addAll(tags)

        connections.forEach { connection ->
            addSymmetricConnectionUnsafe(task.id, connection)
        }

        task
    }

    override suspend fun updateTask(task: Task): Task? = mutex.withLock {
        val oldTask = tasks[task.id] ?: return@withLock null

        val removedConnections = oldTask.connections - task.connections
        removedConnections.forEach { connection ->
            removeSymmetricConnectionUnsafe(task.id, connection)
        }

        val addedConnections = task.connections - oldTask.connections
        addedConnections.forEach { connection ->
            addSymmetricConnectionUnsafe(task.id, connection)
        }

        tasks[task.id] = task

        // Use AbstractTaskRepository's shared methods (they don't acquire mutex)
        val (finalTask, automaticReason) = calculateFinalTaskStatusOnUpdate(task.id, task, oldTask)
        handleStatusChangeOnUpdate(finalTask, oldTask, automaticReason)

        tasks[task.id] = finalTask
        allTags.addAll(finalTask.tags)

        handleStatusCascadeOnUpdate(finalTask.id, oldTask.status, finalTask.status)

        finalTask
    }

    override suspend fun addConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean = mutex.withLock {
        val fromTask = tasks[fromTaskId] ?: return@withLock false
        if (!tasks.containsKey(toTaskId)) return@withLock false

        val connection = TaskConnection(toTaskId, type)
        if (connection in fromTask.connections) return@withLock true

        if (wouldCreateCycle(fromTaskId, toTaskId, type)) {
            return@withLock false
        }

        val updatedTask = fromTask.copy(connections = fromTask.connections + connection)
        tasks[fromTaskId] = updatedTask

        addSymmetricConnectionUnsafe(fromTaskId, connection)

        true
    }

    override suspend fun removeConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean = mutex.withLock {
        val fromTask = tasks[fromTaskId] ?: return@withLock false

        val connection = TaskConnection(toTaskId, type)
        if (connection !in fromTask.connections) return@withLock true

        val updatedTask = fromTask.copy(connections = fromTask.connections - connection)
        tasks[fromTaskId] = updatedTask

        removeSymmetricConnectionUnsafe(fromTaskId, connection)

        true
    }

    private suspend fun addSymmetricConnectionUnsafe(sourceTaskId: String, connection: TaskConnection) {
        val targetTask = tasks[connection.targetTaskId] ?: return
        val symmetricConnection = TaskConnection(sourceTaskId, connection.type.symmetric)

        if (symmetricConnection !in targetTask.connections) {
            val updatedTask = targetTask.copy(
                connections = targetTask.connections + symmetricConnection
            )
            tasks[connection.targetTaskId] = updatedTask

            if (symmetricConnection.type == ConnectionType.ParentOf) {
                // Use AbstractTaskRepository's shared method (doesn't acquire mutex)
                updateParentStatusIfNeeded(connection.targetTaskId)
            }
        }
    }

    private suspend fun removeSymmetricConnectionUnsafe(sourceTaskId: String, connection: TaskConnection) {
        val targetTask = tasks[connection.targetTaskId] ?: return
        val symmetricConnection = TaskConnection(sourceTaskId, connection.type.symmetric)

        if (symmetricConnection in targetTask.connections) {
            val updatedTask = targetTask.copy(
                connections = targetTask.connections - symmetricConnection
            )
            tasks[connection.targetTaskId] = updatedTask

            if (symmetricConnection.type == ConnectionType.ParentOf) {
                // Use AbstractTaskRepository's shared method (doesn't acquire mutex)
                updateParentStatusIfNeeded(connection.targetTaskId)
            }
        }
    }

    // Note: getParentTasks does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches SqlDelightTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun getParentTasks(taskId: String): List<Task> {
        val task = tasks[taskId] ?: return emptyList()
        return task.connections
            .filter { it.type == ConnectionType.SubtaskOf }
            .mapNotNull { tasks[it.targetTaskId] }
    }

    // Note: getSubtasks does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches SqlDelightTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun getSubtasks(taskId: String): List<Task> {
        val task = tasks[taskId] ?: return emptyList()
        return task.connections
            .filter { it.type == ConnectionType.ParentOf }
            .mapNotNull { tasks[it.targetTaskId] }
    }

    override suspend fun getConnectionsForTaskSync(taskId: String): Set<TaskConnection>? =
        tasks[taskId]?.connections

    // Note: recordStatusChange does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches SqlDelightTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun recordStatusChange(
        taskId: String,
        previousStatus: TaskStatus?,
        newStatus: TaskStatus,
        automaticChangeReason: AutomaticChangeReason?
    ) {
        statusTimelines[taskId] = (statusTimelines[taskId] ?: emptyList()) + StatusChange(
            timestamp = clock.now(),
            previousStatus = previousStatus,
            newStatus = newStatus,
            automaticChangeReason = automaticChangeReason
        )
    }

    // Note: persistTaskUpdate does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches SqlDelightTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun persistTaskUpdate(task: Task) {
        tasks[task.id] = task
    }

    // Note: getBlockedTasks does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches SqlDelightTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun getBlockedTasks(): List<Task> =
        tasks.values.filter { it.status is TaskStatus.Blocked }

    // Note: getRecurringTasksDueBefore does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches SqlDelightTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun getRecurringTasksDueBefore(time: Instant): List<Task> =
        tasks.values.filter { task ->
            task.isRecurring && task.dueDate?.let { it <= time } == true
        }

    override suspend fun deleteTask(id: String): Boolean = mutex.withLock {
        val task = tasks[id] ?: return@withLock false

        task.connections.forEach { connection ->
            removeSymmetricConnectionUnsafe(id, connection)
        }

        // Use AbstractTaskRepository's shared method (doesn't acquire mutex)
        handleBlockerDeleted(id)

        val parentTasks = getParentTasks(id)

        tasks.remove(id)
        statusTimelines.remove(id)

        // Update parent tasks' statuses after subtask deletion
        updateParentStatuses(parentTasks)

        true
    }

    override suspend fun getAllWithTotalsFiltered(spaceId: String, criteria: TaskFilterCriteria): List<TaskWithTotals> = mutex.withLock {
        val spaceTasks = tasks.values.filter { it.spaceId == spaceId }
        val blockedTasks = getBlockedTasks()
        val tasksById = spaceTasks.associateBy { it.id }
        val tasksWithTotals = spaceTasks.map { task ->
            TaskWithTotals(
                task = task,
                totalDueDate = calculateTotalDueDate(task, blockedTasks, tasksById),
                totalPriority = calculateTotalPriority(task, blockedTasks, tasksById)
            )
        }
        filterTasksWithCriteria(tasksWithTotals, criteria)
    }

    override suspend fun getFilterState(spaceId: String): TaskFilterCriteria = mutex.withLock {
        filterStateBySpaceId.getOrPut(spaceId) { TaskFilterCriteria() }
    }

    override suspend fun saveFilterState(spaceId: String, criteria: TaskFilterCriteria) = mutex.withLock {
        filterStateBySpaceId[spaceId] = criteria
    }

    override suspend fun getViewMode(spaceId: String): String = mutex.withLock {
        viewModeBySpaceId.getOrPut(spaceId) { "Priority" }
    }

    override suspend fun saveViewMode(spaceId: String, viewMode: String) = mutex.withLock {
        viewModeBySpaceId[spaceId] = viewMode
    }

    override suspend fun getFilterPanelOpen(spaceId: String): Boolean = mutex.withLock {
        filterPanelOpenBySpaceId.getOrPut(spaceId) { false }
    }

    override suspend fun saveFilterPanelOpen(spaceId: String, isOpen: Boolean) = mutex.withLock {
        filterPanelOpenBySpaceId[spaceId] = isOpen
    }

    override suspend fun exportSpaceToJson(spaceId: String, prettyPrint: Boolean): String? = mutex.withLock {
        val space = spaces[spaceId] ?: return@withLock null
        val spaceTasks = tasks.values.filter { it.spaceId == spaceId }
        val spaceTimelines = spaceTasks.associate { task ->
            task.id to (statusTimelines[task.id] ?: emptyList())
        }
        val spaceTags = spaceTasks.flatMap { it.tags }.toSet()
        val nextId = nextIdBySpace[spaceId] ?: 1

        val exportData = SpaceExportData(
            space = space,
            tasks = spaceTasks,
            statusTimelines = spaceTimelines,
            nextId = nextId,
            tags = spaceTags
        )

        val json = if (prettyPrint) jsonPretty else jsonCompact
        json.encodeToString(exportData)
    }

    override suspend fun importSpaceFromJson(jsonString: String): Space? {
        val exportData = try {
            jsonCompact.decodeFromString<SpaceExportData>(jsonString)
        } catch (e: Exception) {
            return null
        }

        return mutex.withLock {
            var newPrefix = exportData.space.idPrefix
            var counter = 1
            while (spaces.values.any { it.idPrefix == newPrefix }) {
                newPrefix = "${exportData.space.idPrefix}$counter"
                counter++
            }

            val newSpaceId = "space-${spaces.size}-$newPrefix"

            val newSpace = exportData.space.copy(
                id = newSpaceId,
                idPrefix = newPrefix
            )
            spaces[newSpaceId] = newSpace
            nextIdBySpace[newSpaceId] = exportData.nextId

            val oldToNewTaskId = createTaskIdMapping(exportData.tasks, newPrefix)

            exportData.tasks.forEach { task ->
                val newTaskId = oldToNewTaskId[task.id] ?: return@forEach
                val remappedConnections = task.connections.mapNotNull { connection ->
                    val newTargetId = oldToNewTaskId[connection.targetTaskId]
                    if (newTargetId != null) {
                        connection.copy(targetTaskId = newTargetId)
                    } else {
                        null
                    }
                }.toSet()

                val remappedStatus = remapBlockedStatus(task.status, oldToNewTaskId)

                val newTask = task.copy(
                    id = newTaskId,
                    spaceId = newSpaceId,
                    connections = remappedConnections,
                    status = remappedStatus
                )
                tasks[newTaskId] = newTask

                val timeline = exportData.statusTimelines[task.id] ?: emptyList()
                if (timeline.isNotEmpty()) {
                    statusTimelines[newTaskId] = timeline
                }
            }

            allTags.addAll(exportData.tags)

            newSpace
        }
    }

    // ============ Space deletion helper ============

    /**
     * Handle cross-space relationships when a space is deleted.
     * This is InMemory-specific because it directly modifies the tasks map.
     */
    private fun handleCrossSpaceRelationshipsOnSpaceDeletionUnsafe(taskIdsInDeletedSpace: Set<String>) {
        spaces.keys.forEach { spaceId ->
            tasks.values.filter { it.spaceId == spaceId }.forEach { task ->
                var modified = false
                var updatedTask = task

                val connectionsToRemove = task.connections.filter { it.targetTaskId in taskIdsInDeletedSpace }
                if (connectionsToRemove.isNotEmpty()) {
                    updatedTask = updatedTask.copy(
                        connections = updatedTask.connections - connectionsToRemove.toSet()
                    )
                    modified = true
                }

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
                    tasks[task.id] = updatedTask
                }
            }
        }
    }

    // ============ Thread-safe overrides for AbstractTaskRepository methods ============
    // These methods do read-modify-write operations and need atomic mutex protection.
    // We wrap the AbstractTaskRepository's internal methods with mutex to ensure atomicity.
    // This works because getById, persistTaskUpdate, recordStatusChange, etc. don't acquire mutex.

    override suspend fun processRecurrenceTrigger(
        taskId: String,
        triggerEvent: RecurrenceTriggerEvent,
        triggerTime: Instant
    ): Task? = mutex.withLock {
        processRecurrenceTriggerInternal(taskId, triggerEvent, triggerTime)
    }

    override suspend fun processDateBasedRecurrences(currentTime: Instant): List<Task> = mutex.withLock {
        processDateBasedRecurrencesInternal(currentTime)
    }

    override suspend fun clearAllData() = mutex.withLock {
        spaces.clear()
        tasks.clear()
        statusTimelines.clear()
        allTags.clear()
        nextIdBySpace.clear()
        filterStateBySpaceId.clear()
        viewModeBySpaceId.clear()
        filterPanelOpenBySpaceId.clear()
    }
}
