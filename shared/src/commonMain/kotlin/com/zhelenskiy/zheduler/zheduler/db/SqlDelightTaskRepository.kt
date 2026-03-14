@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.db

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.zhelenskiy.zheduler.zheduler.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * SQLDelight-based implementation of TaskRepository
 * Uses SQLite for persistence with proper indexing across all platforms.
 * All compound operations (read-modify-write) are protected by a coroutine Mutex
 * to ensure thread safety.
 *
 */
class SqlDelightTaskRepository(
    private val database: ZhedulerDatabase,
    clock: Clock = Clock.System
) : AbstractTaskRepository(clock) {
    private val mutex = Mutex()
    private val queries = database.zhedulerDatabaseQueries

    // Space operations
    override suspend fun hasSpaces(): Boolean =
        queries.hasSpaces().awaitAsOne()

    override suspend fun getAllTasks(): List<Space> =
        queries.getAllSpaces().awaitAsList().map { it.toModel() }

    override suspend fun getSpaceById(id: String): Space? =
        queries.getSpaceById(id).awaitAsOneOrNull()?.toModel()

    override suspend fun filterSpaces(
        query: String,
        searchInName: Boolean,
        searchInPrefix: Boolean
    ): List<Space> {
        if (query.isBlank()) return queries.getAllSpaces().awaitAsList().map { it.toModel() }
        return queries.filterSpaces(
            searchInName = if (searchInName) 1L else 0L,
            query = query,
            searchInPrefix = if (searchInPrefix) 1L else 0L
        ).awaitAsList().map { it.toModel() }
    }

    override suspend fun createSpace(name: String, idPrefix: String): Space? = mutex.withLock {
        if (!idPrefix.matches(Regex("^[A-Z]+$")) || idPrefix.isEmpty()) return@withLock null
        if (queries.prefixExists(idPrefix).awaitAsOne()) return@withLock null

        val spaces = queries.getAllSpaces().awaitAsList()
        val spaceId = "space-${spaces.size}-${idPrefix}"
        val space = Space(id = spaceId, name = name, idPrefix = idPrefix)

        queries.insertSpace(spaceId, name, idPrefix)
        queries.setNextId(spaceId, 1)

        space
    }

    override suspend fun updateSpaceName(spaceId: String, newName: String): Boolean = mutex.withLock {
        if (queries.getSpaceById(spaceId).awaitAsOneOrNull() == null) return@withLock false
        if (newName.isBlank()) return@withLock false

        queries.updateSpace(newName, spaceId)
        true
    }

    override suspend fun deleteSpace(spaceId: String): Boolean = mutex.withLock {
        if (queries.getSpaceById(spaceId).awaitAsOneOrNull() == null) return@withLock false

        val taskIdsInSpace = queries.getTasksBySpace(spaceId).awaitAsList().map { it.id }.toSet()

        handleCrossSpaceRelationshipsOnSpaceDeletion(taskIdsInSpace)

        queries.deleteSpace(spaceId)

        true
    }

    override suspend fun getTasksInSpace(spaceId: String): List<Task> =
        queries.getTasksBySpace(spaceId).awaitAsList().map { loadTaskWithConnections(it) }

    override suspend fun removeConnectionsToDeletedTasks(taskId: String, connections: List<TaskConnection>) {
        connections.forEach { connection ->
            queries.deleteConnection(taskId, connection.targetTaskId, connection.type.name)
        }
    }

    override suspend fun getAllTasks(spaceId: String): List<Task> =
        queries.getTasksBySpace(spaceId).awaitAsList().map { loadTaskWithConnections(it) }

    override suspend fun filterTasksForSelection(
        spaceId: String,
        excludeTaskId: String?,
        searchQuery: String
    ): List<Task> =
        queries.searchTasksForConnection(
            spaceId = spaceId,
            id = excludeTaskId,
            searchQuery = searchQuery
        ).awaitAsList().map { loadTaskWithConnections(it) }

    /**
     * Search tasks for connection dialog with SQL filtering and cycle detection.
     * Filters by spaceId, excludes current task, optionally filters by search query,
     * and checks for cycles. Uses SQL indexes for efficient search on id and title fields.
     *
     * @param spaceId The space to search in
     * @param excludeTaskId The current task ID to exclude
     * @param searchQuery Optional search query to filter by id or title (case-insensitive)
     * @param excludeTaskIds Additional task IDs to exclude (e.g., already connected tasks)
     * @param connectionType The type of connection being created (for cycle detection)
     * @param existingConnections Existing connections to check for cycles
     * @return List of tasks matching the criteria that won't create cycles
     */
    override suspend fun searchTasksForConnection(
        spaceId: String,
        excludeTaskId: String?,
        searchQuery: String,
        excludeTaskIds: Set<String>,
        connectionType: ConnectionType,
        existingConnections: Set<TaskConnection>
    ): List<Task> {
        // First, use SQL to filter by space, search query, and excluded task
        val sqlResults = queries.searchTasksForConnection(
            spaceId = spaceId,
            id = excludeTaskId,
            searchQuery = searchQuery
        ).awaitAsList().map { loadTaskWithConnections(it) }

        // Then filter in memory for: additional excluded IDs and cycle detection
        return sqlResults.filter { task ->
            task.id !in excludeTaskIds &&
            !wouldCreateCycle(excludeTaskId, task.id, connectionType, existingConnections)
        }
    }

    override suspend fun getAllSpacePrefixes(): List<String> =
        queries.getAllPrefixes().awaitAsList()

    override suspend fun getAllTasksWithTotals(spaceId: String): List<TaskWithTotals> {
        val tasks = getAllTasks(spaceId)
        val blockedTasks = getBlockedTasks()
        val tasksById = tasks.associateBy { it.id }
        return tasks.map { task ->
            TaskWithTotals(
                task = task,
                totalDueDate = calculateTotalDueDate(task, blockedTasks, tasksById),
                totalPriority = calculateTotalPriority(task, blockedTasks, tasksById)
            )
        }
    }

    override suspend fun getTaskById(id: String): Task? = getByIdUnsafe(id)

    private suspend fun getByIdUnsafe(id: String): Task? {
        val entity = queries.getTaskById(id).awaitAsOneOrNull() ?: return null
        return loadTaskWithConnections(entity)
    }

    override suspend fun getTasksByIdWithTotals(id: String): TaskWithTotals? {
        val task = getTaskById(id) ?: return null
        val blockedTasks = getBlockedTasks()
        val neededTaskIds = collectNeededTaskIds(task, blockedTasks)
        // Batch fetch all necessary tasks in a single query
        val tasksById = getTasksByIds(neededTaskIds).associateBy { it.id } + (task.id to task)
        return TaskWithTotals(
            task = task,
            totalDueDate = calculateTotalDueDate(task, blockedTasks, tasksById),
            totalPriority = calculateTotalPriority(task, blockedTasks, tasksById)
        )
    }

    override suspend fun getTasksByIds(ids: Set<String>): List<Task> {
        if (ids.isEmpty()) return emptyList()
        return queries.getTasksByIds(ids).awaitAsList().map { loadTaskWithConnections(it) }
    }

    private suspend fun collectNeededTaskIds(task: Task, blockedTasks: List<Task>): Set<String> {
        val blockerToBlockedTasks = mutableMapOf<String, MutableList<Task>>()
        blockedTasks.forEach { blockedTask ->
            val status = blockedTask.status
            if (status is TaskStatus.Blocked) {
                status.blockerTaskIds.forEach { blockerId ->
                    blockerToBlockedTasks.getOrPut(blockerId) { mutableListOf() }.add(blockedTask)
                }
            }
        }

        val needed = mutableSetOf<String>()
        val pendingIds = ArrayDeque<String>()
        pendingIds.add(task.id)

        while (pendingIds.isNotEmpty()) {
            val currentId = pendingIds.removeFirst()
            if (currentId in needed) continue
            needed.add(currentId)

            val current = getTaskById(currentId) ?: continue

            current.connections
                .filter { it.type == ConnectionType.IsDependencyOf }
                .forEach { connection ->
                    if (connection.targetTaskId !in needed) {
                        pendingIds.add(connection.targetTaskId)
                    }
                }

            blockerToBlockedTasks[currentId]?.forEach { blockedTask ->
                if (blockedTask.id !in needed) {
                    pendingIds.add(blockedTask.id)
                }
            }
        }

        return needed
    }

    private suspend fun loadTaskWithConnections(entity: Tasks): Task {
        val connections = queries.getConnectionsForTask(entity.id).awaitAsList()
            .map { TaskConnection(it.targetTaskId, ConnectionType.valueOf(it.type)) }
            .toSet()

        return Task(
            id = entity.id,
            title = entity.title,
            description = entity.description,
            status = entity.status.toTaskStatus(),
            dueDate = entity.dueDate?.let { Instant.fromEpochMilliseconds(it) },
            priority = entity.priority?.let { Priority(it.toInt()) },
            estimatedTime = entity.estimatedTimeJson.toRecurrencePeriodOrNull(),
            tags = entity.tagsJson.toStringSet(),
            connections = connections,
            notifications = entity.notificationsJson.toNotificationList(),
            spaceId = entity.spaceId,
            recurrenceRules = entity.recurrenceRulesJson.toRecurrenceRuleList(),
            autoUpdateStatusFromSubtasks = entity.autoUpdateStatusFromSubtasks != 0L
        )
    }

    override suspend fun getAllTags(): Set<String> =
        queries.getAllTags().awaitAsList().toSet()

    override suspend fun filterTags(searchQuery: String, excludeTags: Set<String>): List<String> {
        val filteredTags = queries.filterTags(searchQuery).awaitAsList()
        return if (excludeTags.isEmpty()) {
            filteredTags
        } else {
            filteredTags.filter { it !in excludeTags }
        }
    }

    override suspend fun addTag(tag: String): Boolean {
        if (tag.isBlank()) return false
        queries.insertTag(tag.trim())
        return true
    }

    override suspend fun deleteTag(tag: String): Boolean {
        if (tag.isBlank()) return false
        queries.deleteTag(tag.trim())
        return true
    }

    override suspend fun peekNextId(spaceId: String): String {
        val space = queries.getSpaceById(spaceId).awaitAsOneOrNull() ?: return "TASK-1"
        val nextNum = queries.getNextId(spaceId).awaitAsOneOrNull() ?: 1
        return "${space.idPrefix}-$nextNum"
    }

    private suspend fun generateNextIdUnsafe(spaceId: String): String {
        val space = queries.getSpaceById(spaceId).awaitAsOneOrNull() ?: return "TASK-1"
        val nextNum = queries.getNextId(spaceId).awaitAsOneOrNull() ?: 1
        queries.setNextId(spaceId, nextNum + 1)
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
        recurrenceRules: List<Pair<RecurrenceRule, RecurrenceState>>,
        autoUpdateStatusFromSubtasks: Boolean
    ): Task? = mutex.withLock {
        if (queries.getSpaceById(spaceId).awaitAsOneOrNull() == null) return@withLock null
        addTaskUnsafe(
            spaceId, title, description, status, dueDate, priority, estimatedTime,
            tags, connections, notifications, customId, recurrenceRules, autoUpdateStatusFromSubtasks
        )
    }

    private suspend fun syncToDatabase(task: Task) {
        queries.updateTask(
            title = task.title,
            description = task.description,
            status = task.status.toJson(),
            dueDate = task.dueDate?.toEpochMilliseconds(),
            priority = task.priority?.value?.toLong(),
            estimatedTimeJson = task.estimatedTime.toJsonOrNull(),
            tagsJson = task.tags.toJson(),
            notificationsJson = task.notifications.toJson(),
            spaceId = task.spaceId,
            recurrenceRulesJson = task.recurrenceRules.toJson(),
            autoUpdateStatusFromSubtasks = if (task.autoUpdateStatusFromSubtasks) 1 else 0,
            isRecurring = if (task.recurrenceRules.isNotEmpty()) 1 else 0,
            isBlocked = if (task.status is TaskStatus.Blocked) 1 else 0,
            id = task.id
        )
    }

    override suspend fun updateTask(task: Task): Task? = mutex.withLock {
        val oldTask = getByIdUnsafe(task.id) ?: return@withLock null

        val removedConnections = oldTask.connections - task.connections
        removedConnections.forEach { connection ->
            queries.deleteConnection(task.id, connection.targetTaskId, connection.type.name)
            queries.deleteConnection(connection.targetTaskId, task.id, connection.type.symmetric.name)
        }

        val addedConnections = task.connections - oldTask.connections
        addedConnections.forEach { connection ->
            queries.insertConnection(task.id, connection.targetTaskId, connection.type.name)
            addSymmetricConnectionUnsafe(task.id, connection)
        }

        val (finalTask, automaticReason) = calculateFinalTaskStatusOnUpdate(task.id, task, oldTask)
        handleStatusChangeOnUpdate(finalTask, oldTask, automaticReason)

        syncToDatabase(finalTask)

        val newTags = finalTask.tags - oldTask.tags
        newTags.forEach { queries.insertTag(it) }

        // Update task_tags junction table
        if (finalTask.tags != oldTask.tags) {
            queries.deleteTaskTags(finalTask.id)
            finalTask.tags.forEach { queries.insertTaskTag(finalTask.id, it) }
        }

        handleStatusCascadeOnUpdate(finalTask.id, oldTask.status, finalTask.status)

        finalTask
    }

    override suspend fun recordStatusChange(
        taskId: String,
        previousStatus: TaskStatus?,
        newStatus: TaskStatus,
        automaticChangeReason: AutomaticChangeReason?
    ) {
        queries.insertStatusChange(
            taskId = taskId,
            timestamp = clock.now().toEpochMilliseconds(),
            previousStatusJson = previousStatus.toJsonOrNull(),
            newStatusJson = newStatus.toJson(),
            automaticChangeReasonJson = automaticChangeReason.toJsonOrNull(),
        )
    }

    override suspend fun persistTaskUpdate(task: Task) {
        syncToDatabase(task)
    }

    override suspend fun getBlockedTasks(): List<Task> =
        queries.getBlockedTasks().awaitAsList().map { loadTaskWithConnections(it) }

    private suspend fun addSymmetricConnectionUnsafe(sourceTaskId: String, connection: TaskConnection) {
        val targetTask = getByIdUnsafe(connection.targetTaskId) ?: return
        val symmetricConnection = TaskConnection(sourceTaskId, connection.type.symmetric)

        if (!targetTask.connections.contains(symmetricConnection)) {
            queries.insertConnection(connection.targetTaskId, sourceTaskId, connection.type.symmetric.name)

            if (symmetricConnection.type == ConnectionType.ParentOf) {
                updateParentStatusIfNeeded(connection.targetTaskId)
            }
        }
    }

    override suspend fun addConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean = mutex.withLock {
        addConnectionUnsafe(fromTaskId, toTaskId, type)
    }

    override suspend fun removeConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean = mutex.withLock {
        val fromTask = getByIdUnsafe(fromTaskId) ?: return@withLock false
        val connection = TaskConnection(toTaskId, type)
        if (!fromTask.connections.contains(connection)) return@withLock true

        queries.deleteConnection(fromTaskId, toTaskId, type.name)
        queries.deleteConnection(toTaskId, fromTaskId, type.symmetric.name)

        if (type == ConnectionType.SubtaskOf) {
            updateParentStatusIfNeeded(toTaskId)
        }

        true
    }

    override suspend fun getConnectionsForTaskSync(taskId: String): Set<TaskConnection>? {
        val connections = queries.getConnectionsForTask(taskId).awaitAsList()
        return if (connections.isEmpty() && queries.getTaskById(taskId).awaitAsOneOrNull() == null) {
            null
        } else {
            connections.map { TaskConnection(it.targetTaskId, ConnectionType.valueOf(it.type)) }.toSet()
        }
    }

    override suspend fun getParentTasks(taskId: String): List<Task> =
        queries.getParentTasks(taskId).awaitAsList().map { loadTaskWithConnections(it) }

    override suspend fun getSubtasks(taskId: String): List<Task> =
        queries.getSubtasks(taskId).awaitAsList().map { loadTaskWithConnections(it) }

    override suspend fun deleteTask(id: String): Boolean = mutex.withLock {
        val task = getByIdUnsafe(id) ?: return@withLock false

        task.connections.forEach { connection ->
            queries.deleteConnection(connection.targetTaskId, id, connection.type.symmetric.name)
        }

        handleBlockerDeleted(id)

        val parentTasks = getParentTasks(id)

        queries.deleteTask(id)

        // Update parent tasks' statuses after subtask deletion
        updateParentStatuses(parentTasks)

        true
    }

    override suspend fun getStatusTimeline(taskId: String): List<StatusChange> =
        queries.getStatusTimeline(taskId).awaitAsList().map {
            StatusChange(
                timestamp = Instant.fromEpochMilliseconds(it.timestamp),
                previousStatus = it.previousStatusJson.toTaskStatusOrNull(),
                newStatus = it.newStatusJson.toTaskStatus(),
                automaticChangeReason = it.automaticChangeReasonJson.toAutomaticChangeReasonOrNull()
            )
        }

    override suspend fun getStatusChangesByDate(spaceId: String, year: Int, month: Int): Map<LocalDate, List<StatusChangeEvent>> {
        val changesByDate = mutableMapOf<LocalDate, MutableList<StatusChangeEvent>>()

        val tz = TimeZone.currentSystemDefault()
        val monthStart = LocalDate(year, month, 1).atStartOfDayIn(tz)
        val monthEnd = if (month == 12) {
            LocalDate(year + 1, 1, 1).atStartOfDayIn(tz)
        } else {
            LocalDate(year, month + 1, 1).atStartOfDayIn(tz)
        }

        val statusChanges = queries.getStatusChangesBySpaceAndDateRange(
            spaceId,
            monthStart.toEpochMilliseconds(),
            monthEnd.toEpochMilliseconds()
        ).awaitAsList()

        // Fetch all tasks for this space in a single query to avoid N+1 queries
        val tasksById = getAllTasks(spaceId).associateBy { it.id }

        statusChanges.forEach { changeEntity ->
            val task = tasksById[changeEntity.taskId] ?: return@forEach
            val statusChange = StatusChange(
                timestamp = Instant.fromEpochMilliseconds(changeEntity.timestamp),
                previousStatus = changeEntity.previousStatusJson.toTaskStatusOrNull(),
                newStatus = changeEntity.newStatusJson.toTaskStatus(),
                automaticChangeReason = changeEntity.automaticChangeReasonJson.toAutomaticChangeReasonOrNull()
            )
            val dateTime = statusChange.timestamp.toLocalDateTime(tz)
            val date = dateTime.date

            val event = StatusChangeEvent(task, statusChange)
            changesByDate.getOrPut(date) { mutableListOf() }.add(event)
        }

        return changesByDate.mapValues { (_, events) ->
            events.sortedByDescending { it.statusChange.timestamp }
        }
    }

    override suspend fun getAllWithTotalsFiltered(spaceId: String, criteria: TaskFilterCriteria): List<TaskWithTotals> =
        filterTasksWithCriteria(getAllTasksWithTotals(spaceId), criteria)

    override suspend fun getFilterState(spaceId: String): TaskFilterCriteria =
        queries.getFilterState(spaceId).awaitAsOneOrNull()?.toTaskFilterCriteria() ?: TaskFilterCriteria()

    override suspend fun saveFilterState(spaceId: String, criteria: TaskFilterCriteria) {
        queries.setFilterState(spaceId, criteria.toJson())
    }

    override suspend fun getViewMode(spaceId: String): String =
        queries.getViewMode(spaceId).awaitAsOneOrNull() ?: "Priority"

    override suspend fun saveViewMode(spaceId: String, viewMode: String) {
        queries.setViewMode(spaceId, viewMode)
    }

    override suspend fun getFilterPanelOpen(spaceId: String): Boolean =
        queries.getFilterPanelState(spaceId).awaitAsOneOrNull() == 1L

    override suspend fun saveFilterPanelOpen(spaceId: String, isOpen: Boolean) {
        queries.setFilterPanelState(spaceId, if (isOpen) 1 else 0)
    }

    // ============ View mode management ============

    override suspend fun getAllViewModes(spaceId: String): List<ViewMode> {
        val builtIn = ViewMode.getBuiltInModes(spaceId)
        val custom = queries.getAllCustomViewModes(spaceId).awaitAsList().map { row ->
            row.configJson.toViewMode(spaceId, row.id, row.name)
        }
        return builtIn + custom
    }

    override suspend fun getViewModeById(spaceId: String, viewModeId: String): ViewMode? {
        // Check built-in modes first
        ViewMode.getBuiltInModes(spaceId).find { it.id == viewModeId }?.let { return it }
        // Check custom modes
        return queries.getCustomViewModeById(spaceId, viewModeId).awaitAsOneOrNull()?.let { row ->
            row.configJson.toViewMode(spaceId, row.id, row.name)
        }
    }

    override suspend fun saveViewMode(viewMode: ViewMode): ViewMode {
        require(!viewMode.isBuiltIn) { "Cannot modify built-in view modes" }
        queries.insertOrUpdateCustomViewMode(
            id = viewMode.id,
            spaceId = viewMode.spaceId,
            name = viewMode.name,
            configJson = viewMode.toConfigJson()
        )
        return viewMode
    }

    override suspend fun deleteViewMode(spaceId: String, viewModeId: String): Boolean = mutex.withLock {
        // Cannot delete built-in modes
        if (ViewMode.getBuiltInModes(spaceId).any { it.id == viewModeId }) {
            return@withLock false
        }
        // Check if the view mode exists before deleting
        if (queries.getCustomViewModeById(spaceId, viewModeId).awaitAsOneOrNull() == null) {
            return@withLock false
        }
        queries.deleteCustomViewMode(spaceId, viewModeId)
        // If this was the active mode, reset to default
        val activeId = queries.getActiveViewModeId(spaceId).awaitAsOneOrNull()
        if (activeId == viewModeId) {
            queries.setActiveViewModeId(spaceId, "priority")
        }
        true
    }

    override suspend fun getActiveViewMode(spaceId: String): ViewMode {
        val activeId = queries.getActiveViewModeId(spaceId).awaitAsOneOrNull() ?: "priority"
        return getViewModeById(spaceId, activeId) ?: ViewMode.priority(spaceId)
    }

    override suspend fun setActiveViewMode(spaceId: String, viewModeId: String) {
        queries.setActiveViewModeId(spaceId, viewModeId)
    }

    override suspend fun exportSpaceToJson(spaceId: String, prettyPrint: Boolean): String? {
        val space = getSpaceById(spaceId) ?: return null
        val spaceTasks = queries.getTasksBySpace(spaceId).awaitAsList().map { loadTaskWithConnections(it) }
        val taskIds = spaceTasks.map { it.id }.toSet()
        // Batch fetch all status timelines in a single query
        val spaceTimelines = if (taskIds.isEmpty()) {
            emptyMap()
        } else {
            queries.getStatusTimelinesForTasks(taskIds).awaitAsList()
                .groupBy({ it.taskId }) { statusChange ->
                    StatusChange(
                        timestamp = Instant.fromEpochMilliseconds(statusChange.timestamp),
                        previousStatus = statusChange.previousStatusJson.toTaskStatusOrNull(),
                        newStatus = statusChange.newStatusJson.toTaskStatus(),
                        automaticChangeReason = statusChange.automaticChangeReasonJson.toAutomaticChangeReasonOrNull()
                    )
                }
        }
        val spaceTags = spaceTasks.flatMap { it.tags }.toSet()
        val nextId = queries.getNextId(spaceId).awaitAsOneOrNull()?.toInt() ?: 1

        val exportData = SpaceExportData(
            space = space,
            tasks = spaceTasks,
            statusTimelines = spaceTimelines,
            nextId = nextId,
            tags = spaceTags
        )

        val json = if (prettyPrint) jsonPretty else jsonCompact
        return json.encodeToString(exportData)
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
            while (queries.prefixExists(newPrefix).awaitAsOne()) {
                newPrefix = "${exportData.space.idPrefix}$counter"
                counter++
            }

            val spaces = queries.getAllSpaces().awaitAsList()
            val newSpaceId = "space-${spaces.size}-$newPrefix"
            val newSpace = Space(id = newSpaceId, name = exportData.space.name, idPrefix = newPrefix)

            queries.insertSpace(newSpaceId, newSpace.name, newPrefix)
            queries.setNextId(newSpaceId, exportData.nextId.toLong())

            val oldToNewTaskId = createTaskIdMapping(exportData.tasks, newPrefix)

            exportData.tasks.forEach { task ->
                val newTaskId = oldToNewTaskId[task.id] ?: return@forEach

                val remappedStatus = remapBlockedStatus(task.status, oldToNewTaskId)

                addTaskUnsafe(
                    spaceId = newSpaceId,
                    title = task.title,
                    description = task.description,
                    status = remappedStatus,
                    dueDate = task.dueDate,
                    priority = task.priority,
                    estimatedTime = task.estimatedTime,
                    tags = task.tags,
                    connections = emptySet(),
                    notifications = task.notifications,
                    customId = newTaskId,
                    recurrenceRules = task.recurrenceRules,
                    autoUpdateStatusFromSubtasks = task.autoUpdateStatusFromSubtasks
                )

                val oldTaskId = task.id
                val timeline = exportData.statusTimelines[oldTaskId] ?: emptyList()
                timeline.drop(1).forEach { statusChange ->
                    queries.insertStatusChange(
                        taskId = newTaskId,
                        timestamp = statusChange.timestamp.toEpochMilliseconds(),
                        previousStatusJson = statusChange.previousStatus.toJsonOrNull(),
                        newStatusJson = statusChange.newStatus.toJson(),
                        automaticChangeReasonJson = statusChange.automaticChangeReason.toJsonOrNull()
                    )
                }
            }

            exportData.tasks.forEach { task ->
                val newTaskId = oldToNewTaskId[task.id] ?: return@forEach
                task.connections.forEach { conn ->
                    val newTargetId = oldToNewTaskId[conn.targetTaskId] ?: return@forEach
                    addConnectionUnsafe(newTaskId, newTargetId, conn.type)
                }
            }

            exportData.tags.forEach { queries.insertTag(it) }

            newSpace
        }
    }

    private suspend fun addTaskUnsafe(
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
        recurrenceRules: List<Pair<RecurrenceRule, RecurrenceState>>,
        autoUpdateStatusFromSubtasks: Boolean
    ): Task? {
        val taskId = customId ?: generateNextIdUnsafe(spaceId)

        val effectiveDueDate = dueDate?.let {
            Instant.fromEpochMilliseconds(it.toEpochMilliseconds())
        }
        val status = if (autoUpdateStatusFromSubtasks) {
            val subtasksIds = connections
                .mapNotNull { if (it.type == ConnectionType.ParentOf) it.targetTaskId else null }
            getCalculatedStatusFromSubtasks(subtasksIds, ::getByIdUnsafe) ?: status
        } else {
            status
        }

        val task = Task(
            id = taskId,
            title = title,
            description = description,
            status = status,
            dueDate = effectiveDueDate,
            priority = priority,
            estimatedTime = estimatedTime,
            tags = tags,
            connections = connections,
            notifications = notifications,
            spaceId = spaceId,
            recurrenceRules = recurrenceRules,
            autoUpdateStatusFromSubtasks = autoUpdateStatusFromSubtasks
        )

        queries.insertTask(
            id = taskId,
            title = title,
            description = description,
            status = status.toJson(),
            dueDate = effectiveDueDate?.toEpochMilliseconds(),
            priority = priority?.value?.toLong(),
            estimatedTimeJson = estimatedTime.toJsonOrNull(),
            tagsJson = tags.toJson(),
            notificationsJson = notifications.toJson(),
            spaceId = spaceId,
            recurrenceRulesJson = recurrenceRules.toJson(),
            autoUpdateStatusFromSubtasks = if (autoUpdateStatusFromSubtasks) 1 else 0,
            isRecurring = if (recurrenceRules.isNotEmpty()) 1 else 0,
            isBlocked = if (status is TaskStatus.Blocked) 1 else 0
        )

        connections.forEach { connection ->
            queries.insertConnection(taskId, connection.targetTaskId, connection.type.name)
            addSymmetricConnectionUnsafe(taskId, connection)
        }

        queries.insertStatusChange(
            taskId = taskId,
            timestamp = clock.now().toEpochMilliseconds(),
            previousStatusJson = null,
            newStatusJson = status.toJson(),
            automaticChangeReasonJson = null
        )

        tags.forEach { queries.insertTag(it) }

        // Populate task_tags junction table
        tags.forEach { queries.insertTaskTag(taskId, it) }

        return task
    }

    private suspend fun addConnectionUnsafe(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean {
        val fromTask = getByIdUnsafe(fromTaskId) ?: return false
        if (getByIdUnsafe(toTaskId) == null) return false

        val connection = TaskConnection(toTaskId, type)
        if (fromTask.connections.contains(connection)) return true

        if (wouldCreateCycle(fromTaskId, toTaskId, type)) return false

        queries.insertConnection(fromTaskId, toTaskId, type.name)
        addSymmetricConnectionUnsafe(fromTaskId, connection)

        return true
    }

    override suspend fun clearAllData() {
        queries.getAllSpaces().awaitAsList().forEach { space ->
            queries.deleteSpace(space.id)
        }
    }

    override suspend fun getRecurringTasksDueBefore(time: Instant): List<Task> =
        queries.getRecurringTasksDueBefore(time.toEpochMilliseconds(), ::Tasks).awaitAsList()
            .map { loadTaskWithConnections(it) }

    // Override methods from AbstractTaskRepository that do read-modify-write to add mutex protection.
    // Note: processDateBasedRecurrences calls processRecurrenceTrigger internally, so we only
    // protect processDateBasedRecurrences with mutex (not both, to avoid deadlock with non-reentrant mutex).
    // processRecurrenceTrigger is also protected independently for when it's called directly.

    override suspend fun processRecurrenceTrigger(
        taskId: String,
        triggerEvent: RecurrenceTriggerEvent
    ): Task? = mutex.withLock {
        processRecurrenceTriggerInternal(taskId, triggerEvent)
    }

    override suspend fun processDateBasedRecurrences(currentTime: Instant): List<Task> = mutex.withLock {
        processDateBasedRecurrencesInternal(currentTime)
    }

    // ============ SQL-based grouped task queries ============

    /**
     * Build SQL filter parameters from TaskFilterCriteria.
     */
    private fun buildFilterParams(filterCriteria: TaskFilterCriteria): FilterParams {
        val tz = TimeZone.currentSystemDefault()
        val now = clock.now()
        val today = now.toLocalDateTime(tz).date
        val todayStart = today.atStartOfDayIn(tz)
        val todayEnd = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
        val weekEnd = today.plus(7, DateTimeUnit.DAY).atStartOfDayIn(tz)
        val monthEnd = today.plus(1, DateTimeUnit.MONTH).atStartOfDayIn(tz)

        return FilterParams(
            searchQuery = filterCriteria.searchQuery.takeIf { it.isNotBlank() },
            searchInId = if (TaskTextSearchField.Id in filterCriteria.textSearchFields) 1L else 0L,
            searchInTitle = if (TaskTextSearchField.Title in filterCriteria.textSearchFields) 1L else 0L,
            searchInDescription = if (TaskTextSearchField.Description in filterCriteria.textSearchFields) 1L else 0L,
            searchInTags = if (TaskTextSearchField.Tags in filterCriteria.textSearchFields) 1L else 0L,
            priorityFilterType = when (filterCriteria.priorityFilter) {
                PriorityFilter.Any -> 0L
                PriorityFilter.High -> 1L
                PriorityFilter.Medium -> 2L
                PriorityFilter.Low -> 3L
                PriorityFilter.NoPriority -> 4L
                PriorityFilter.Custom -> 5L
            },
            customPriorityMin = filterCriteria.customPriorityMin.toLongOrNull(),
            customPriorityMax = filterCriteria.customPriorityMax.toLongOrNull(),
            dueDateFilterType = when (filterCriteria.dueDateFilter) {
                DueDateFilter.Any -> 0L
                DueDateFilter.Overdue -> 1L
                DueDateFilter.Today -> 2L
                DueDateFilter.ThisWeek -> 3L
                DueDateFilter.ThisMonth -> 4L
                DueDateFilter.NoDueDate -> 5L
                DueDateFilter.Custom -> 6L
            },
            nowMillis = now.toEpochMilliseconds(),
            todayStartMillis = todayStart.toEpochMilliseconds(),
            todayEndMillis = todayEnd.toEpochMilliseconds(),
            weekEndMillis = weekEnd.toEpochMilliseconds(),
            monthEndMillis = monthEnd.toEpochMilliseconds(),
            customDueDateAfter = filterCriteria.customDueDateAfter?.toEpochMilliseconds(),
            customDueDateBefore = filterCriteria.customDueDateBefore?.toEpochMilliseconds(),
            estimatedTimeFilterType = when (filterCriteria.estimatedTimeFilter) {
                EstimatedTimeFilter.Any -> 0L
                EstimatedTimeFilter.NoEstimate -> 1L
                EstimatedTimeFilter.Quick -> 2L // < 15 min
                EstimatedTimeFilter.Short -> 3L // 15-30 min
                EstimatedTimeFilter.Medium -> 3L // 30-60 min
                EstimatedTimeFilter.Long -> 3L // 1-4 hrs
                EstimatedTimeFilter.VeryLong -> 4L // > 4 hrs
                EstimatedTimeFilter.Custom -> 3L
            },
            estimatedTimeMinMinutes = when (filterCriteria.estimatedTimeFilter) {
                EstimatedTimeFilter.Short -> 15L
                EstimatedTimeFilter.Medium -> 30L
                EstimatedTimeFilter.Long -> 60L
                EstimatedTimeFilter.VeryLong -> 240L
                EstimatedTimeFilter.Custom -> filterCriteria.customEstimatedTimeMin.toLongOrNull()
                else -> null
            },
            estimatedTimeMaxMinutes = when (filterCriteria.estimatedTimeFilter) {
                EstimatedTimeFilter.Quick -> 15L
                EstimatedTimeFilter.Short -> 30L
                EstimatedTimeFilter.Medium -> 60L
                EstimatedTimeFilter.Long -> 240L
                EstimatedTimeFilter.Custom -> filterCriteria.customEstimatedTimeMax.toLongOrNull()
                else -> null
            },
            recurrenceFilterType = when (filterCriteria.recurrenceFilter) {
                RecurrenceFilter.Any -> 0L
                RecurrenceFilter.NoRecurrence -> 1L
                RecurrenceFilter.HasRecurrence, RecurrenceFilter.AfterTimeout,
                RecurrenceFilter.FixedDaysOfWeek, RecurrenceFilter.FixedDayOfMonth,
                RecurrenceFilter.NthDayOfWeek, RecurrenceFilter.Yearly -> 2L
            },
            notificationsFilterType = when (filterCriteria.notificationsFilter) {
                NotificationsFilter.Any -> 0L
                NotificationsFilter.NoNotifications -> 1L
                NotificationsFilter.HasNotifications -> 2L
            },
            autoUpdateStatusFilterType = when (filterCriteria.autoUpdateStatusFilter) {
                AutoUpdateStatusFilter.Any -> 0L
                AutoUpdateStatusFilter.Auto -> 1L
                AutoUpdateStatusFilter.Manual -> 2L
            },
            // Status filters from TaskFilterCriteria
            criteriaStatusFilterType = if (filterCriteria.statusFilters.isEmpty()) 0L else 1L,
            criteriaStatusOpen = if (filterCriteria.statusFilters.any { it is TaskStatus.Open }) 1L else 0L,
            criteriaStatusInProgress = if (filterCriteria.statusFilters.any { it is TaskStatus.InProgress }) 1L else 0L,
            criteriaStatusBlocked = if (filterCriteria.statusFilters.any { it is TaskStatus.Blocked }) 1L else 0L,
            criteriaStatusDone = if (filterCriteria.statusFilters.any { it is TaskStatus.Done }) 1L else 0L,
            criteriaStatusDeclined = if (filterCriteria.statusFilters.any { it is TaskStatus.Declined }) 1L else 0L,
            // Connection type filters from TaskFilterCriteria
            connectionFilterType = if (filterCriteria.connectionTypeFilters.isEmpty()) 0L else 1L,
            requireDependsOn = if (ConnectionTypeOption.DependsOn in filterCriteria.connectionTypeFilters) 1L else 0L,
            requireIsDependencyOf = if (ConnectionTypeOption.IsDependencyOf in filterCriteria.connectionTypeFilters) 1L else 0L,
            requireRelatesTo = if (ConnectionTypeOption.RelatesTo in filterCriteria.connectionTypeFilters) 1L else 0L,
            requireSubtaskOf = if (ConnectionTypeOption.SubtaskOf in filterCriteria.connectionTypeFilters) 1L else 0L,
            requireParentOf = if (ConnectionTypeOption.ParentOf in filterCriteria.connectionTypeFilters) 1L else 0L,
            requireNotSubtask = if (ConnectionTypeOption.NotSubtask in filterCriteria.connectionTypeFilters) 1L else 0L,
            // Selected tags
            selectedTags = filterCriteria.selectedTags,
            tagMatchMode = filterCriteria.tagMatchMode
        )
    }

    /**
     * Get task groups at a specific grouping level using SQL queries.
     * Uses indexed queries to count tasks efficiently without loading all task data.
     */
    override suspend fun getTaskGroups(
        spaceId: String,
        viewMode: ViewMode,
        levelIndex: Int,
        parentFilters: List<GroupFilter>,
        filterCriteria: TaskFilterCriteria
    ): List<TaskGroupInfo> = mutex.withLock {
        if (levelIndex >= viewMode.groupingLevels.size) {
            return@withLock emptyList()
        }

        val level = viewMode.groupingLevels[levelIndex]
        val filterParams = buildFilterParams(filterCriteria)

        // Only Tags grouping field requires in-memory processing since tags are stored as JSON
        // and we need to extract individual tags to create groups
        val needsInMemoryProcessing = level.field == GroupableField.Tags

        if (needsInMemoryProcessing) {
            return@withLock getTaskGroupsWithInMemoryProcessing(spaceId, level, parentFilters, filterCriteria)
        }

        // Use SQL-based counting with the filtered query
        getTaskGroupsFromSqlFiltered(spaceId, level, parentFilters, filterParams)
    }

    /**
     * Get task groups using SQL COUNT queries with all filters applied.
     */
    private suspend fun getTaskGroupsFromSqlFiltered(
        spaceId: String,
        level: GroupingLevel,
        parentFilters: List<GroupFilter>,
        filterParams: FilterParams
    ): List<TaskGroupInfo> {
        val result = mutableListOf<TaskGroupInfo>()

        // For each group definition, count matching tasks using SQL
        for (group in level.groups) {
            val groupFilter = group.toFilter(level.field)
            val combinedFilters = parentFilters + groupFilter

            // Get tasks matching all filters
            val tasks = getTasksWithSqlFilters(spaceId, combinedFilters, filterParams)

            if (tasks.isNotEmpty() || level.showEmptyGroups) {
                result.add(
                    TaskGroupInfo(
                        label = group.label,
                        taskCount = tasks.size,
                        isUncategorized = false,
                        groupDefinition = group,
                        filter = groupFilter
                    )
                )
            }
        }

        // Count uncategorized tasks using SQL with Not filter
        // This avoids in-memory filtering by using the existing Not filter support
        if (level.groups.isNotEmpty()) {
            val allGroupFilters = level.groups.map { it.toFilter(level.field) }
            val uncategorizedFilter = GroupFilter.Not(
                field = level.field,
                filters = allGroupFilters
            )

            // Get uncategorized tasks via SQL (Not filter is handled in getTasksWithSqlFilters)
            val uncategorizedTasks = getTasksWithSqlFilters(
                spaceId,
                parentFilters + uncategorizedFilter,
                filterParams
            )

            if (uncategorizedTasks.isNotEmpty()) {
                result.add(
                    TaskGroupInfo(
                        label = "",
                        taskCount = uncategorizedTasks.size,
                        isUncategorized = true,
                        groupDefinition = null,
                        filter = uncategorizedFilter
                    )
                )
            }
        } else {
            // No groups defined - all tasks are uncategorized
            val allTasks = getTasksWithSqlFilters(spaceId, parentFilters, filterParams)
            if (allTasks.isNotEmpty()) {
                result.add(
                    TaskGroupInfo(
                        label = "",
                        taskCount = allTasks.size,
                        isUncategorized = true,
                        groupDefinition = null,
                        filter = null
                    )
                )
            }
        }

        return result
    }

    /**
     * Get tasks matching filters using SQL.
     * Builds a single SQL query with all filter conditions.
     */
    private suspend fun getTasksWithSqlFilters(
        spaceId: String,
        groupFilters: List<GroupFilter>,
        filterParams: FilterParams
    ): List<Task> {
        // Build combined group filter params from all group filters
        val groupFilterParamsList = mutableListOf<GroupFilterParams>()
        val hasTagsFilters = mutableListOf<GroupFilter.HasTags>()
        val notFilters = mutableListOf<GroupFilter.Not>()

        for (filter in groupFilters) {
            when (filter) {
                is GroupFilter.HasTags -> hasTagsFilters.add(filter)
                is GroupFilter.Not -> notFilters.add(filter)
                else -> groupFilterParamsList.add(buildGroupFilterParams(filter))
            }
        }

        val groupParams = mergeGroupFilterParams(groupFilterParamsList)

        // Execute main SQL query with all group filters
        var tasks = queries.getTasksFilteredWithGroupFilter(
            spaceId = spaceId,
            searchQuery = filterParams.searchQuery,
            searchInId = filterParams.searchInId,
            searchInTitle = filterParams.searchInTitle,
            searchInDescription = filterParams.searchInDescription,
            searchInTags = filterParams.searchInTags,
            priorityFilterType = filterParams.priorityFilterType,
            customPriorityMin = filterParams.customPriorityMin,
            customPriorityMax = filterParams.customPriorityMax,
            dueDateFilterType = filterParams.dueDateFilterType,
            nowMillis = filterParams.nowMillis,
            todayStartMillis = filterParams.todayStartMillis,
            todayEndMillis = filterParams.todayEndMillis,
            weekEndMillis = filterParams.weekEndMillis,
            monthEndMillis = filterParams.monthEndMillis,
            customDueDateAfter = filterParams.customDueDateAfter,
            customDueDateBefore = filterParams.customDueDateBefore,
            estimatedTimeFilterType = filterParams.estimatedTimeFilterType,
            estimatedTimeMinMinutes = filterParams.estimatedTimeMinMinutes,
            estimatedTimeMaxMinutes = filterParams.estimatedTimeMaxMinutes,
            recurrenceFilterType = filterParams.recurrenceFilterType,
            notificationsFilterType = filterParams.notificationsFilterType,
            autoUpdateStatusFilterType = filterParams.autoUpdateStatusFilterType,
            // Group filter params
            groupPriorityFilterType = groupParams.groupPriorityFilterType,
            groupPriorityMin = groupParams.groupPriorityMin,
            groupPriorityMax = groupParams.groupPriorityMax,
            groupDueDateFilterType = groupParams.groupDueDateFilterType,
            groupDueDateMin = groupParams.groupDueDateMin,
            groupDueDateMax = groupParams.groupDueDateMax,
            groupIsRecurring = groupParams.groupIsRecurring,
            groupAutoUpdateStatus = groupParams.groupAutoUpdateStatus,
            groupHasNotifications = groupParams.groupHasNotifications,
            groupEstimatedTimeFilterType = groupParams.groupEstimatedTimeFilterType,
            groupEstimatedTimeMinSeconds = groupParams.groupEstimatedTimeMinSeconds,
            groupEstimatedTimeMaxSeconds = groupParams.groupEstimatedTimeMaxSeconds,
            groupHasConnections = groupParams.groupHasConnections,
            groupStatusFilterType = groupParams.groupStatusFilterType,
            groupStatusOpen = groupParams.groupStatusOpen,
            groupStatusInProgress = groupParams.groupStatusInProgress,
            groupStatusBlocked = groupParams.groupStatusBlocked,
            groupStatusDone = groupParams.groupStatusDone,
            groupStatusDeclined = groupParams.groupStatusDeclined,
            // TaskFilterCriteria: status filters
            criteriaStatusFilterType = filterParams.criteriaStatusFilterType,
            criteriaStatusOpen = filterParams.criteriaStatusOpen,
            criteriaStatusInProgress = filterParams.criteriaStatusInProgress,
            criteriaStatusBlocked = filterParams.criteriaStatusBlocked,
            criteriaStatusDone = filterParams.criteriaStatusDone,
            criteriaStatusDeclined = filterParams.criteriaStatusDeclined,
            // TaskFilterCriteria: connection type filters
            connectionFilterType = filterParams.connectionFilterType,
            requireDependsOn = filterParams.requireDependsOn,
            requireIsDependencyOf = filterParams.requireIsDependencyOf,
            requireRelatesTo = filterParams.requireRelatesTo,
            requireSubtaskOf = filterParams.requireSubtaskOf,
            requireParentOf = filterParams.requireParentOf,
            requireNotSubtask = filterParams.requireNotSubtask
        ).awaitAsList().map { loadTaskWithConnections(it) }

        // Handle HasTags filters (from group filters) - get matching task IDs via SQL
        for (tagsFilter in hasTagsFilters) {
            tasks = filterTasksByTagsAny(spaceId, tasks, tagsFilter.tags)
        }

        // Handle Not filters - compute excluded task IDs
        for (notFilter in notFilters) {
            // For each inner filter, get matching tasks and exclude them
            for (innerFilter in notFilter.filters) {
                val innerTasks = getTasksWithSqlFilters(spaceId, listOf(innerFilter), filterParams)
                val innerTaskIds = innerTasks.map { it.id }.toSet()
                tasks = tasks.filter { it.id !in innerTaskIds }
            }
        }

        // Handle selectedTags from TaskFilterCriteria
        if (filterParams.selectedTags.isNotEmpty()) {
            tasks = when (filterParams.tagMatchMode) {
                TagMatchMode.Any -> filterTasksByTagsAny(spaceId, tasks, filterParams.selectedTags)
                TagMatchMode.All -> tasks.filter { task ->
                    filterParams.selectedTags.all { it in task.tags }
                }
            }
        }

        return tasks
    }

    /**
     * Filter tasks to only those having at least one of the specified tags.
     * Uses SQL with normalized task_tags junction table.
     */
    private suspend fun filterTasksByTagsAny(
        spaceId: String,
        tasks: List<Task>,
        tags: Set<String>
    ): List<Task> {
        if (tags.isEmpty()) return emptyList()
        val matchingIds = queries.getTaskIdsByTags(
            spaceId = spaceId,
            tags = tags
        ).awaitAsList().toSet()
        return tasks.filter { it.id in matchingIds }
    }

    /**
     * Get task groups with in-memory processing for complex filters.
     */
    private suspend fun getTaskGroupsWithInMemoryProcessing(
        spaceId: String,
        level: GroupingLevel,
        parentFilters: List<GroupFilter>,
        filterCriteria: TaskFilterCriteria
    ): List<TaskGroupInfo> {
        // Get all tasks with totals filtered by criteria
        val allTasks = getAllWithTotalsFiltered(spaceId, filterCriteria)

        // Apply parent filters
        val filteredTasks = allTasks.filter { task ->
            parentFilters.all { filter -> task.matchesGroupFilter(filter) }
        }

        val result = mutableListOf<TaskGroupInfo>()
        val matchedTaskIds = mutableSetOf<String>()

        // Process each group definition
        for (group in level.groups) {
            val groupFilter = group.toFilter(level.field)
            val matchingTasks = filteredTasks.filter { task ->
                task.matchesGroupFilter(groupFilter)
            }

            matchedTaskIds.addAll(matchingTasks.map { it.task.id })

            if (matchingTasks.isNotEmpty() || level.showEmptyGroups) {
                result.add(
                    TaskGroupInfo(
                        label = group.label,
                        taskCount = matchingTasks.size,
                        isUncategorized = false,
                        groupDefinition = group,
                        filter = groupFilter
                    )
                )
            }
        }

        // Add uncategorized group
        val uncategorizedTasks = filteredTasks.filter { it.task.id !in matchedTaskIds }
        if (uncategorizedTasks.isNotEmpty()) {
            val allGroupFilters = level.groups.map { it.toFilter(level.field) }
            val uncategorizedFilter = GroupFilter.Not(
                field = level.field,
                filters = allGroupFilters
            )

            result.add(
                TaskGroupInfo(
                    label = "",
                    taskCount = uncategorizedTasks.size,
                    isUncategorized = true,
                    groupDefinition = null,
                    filter = uncategorizedFilter
                )
            )
        }

        return result
    }

    /**
     * Get tasks for a specific group using SQL-optimized queries.
     */
    override suspend fun getTasksForGroup(
        spaceId: String,
        filters: List<GroupFilter>,
        orderingRules: List<OrderingRule>,
        filterCriteria: TaskFilterCriteria
    ): List<TaskWithTotals> = mutex.withLock {
        val filterParams = buildFilterParams(filterCriteria)

        // Get tasks using SQL filters - all filtering is now done in SQL
        val tasks = getTasksWithSqlFilters(spaceId, filters, filterParams)

        // Calculate totals
        val blockedTasks = getBlockedTasks()
        val tasksById = tasks.associateBy { it.id }
        val tasksWithTotals = tasks.map { task ->
            TaskWithTotals(
                task = task,
                totalDueDate = calculateTotalDueDate(task, blockedTasks, tasksById),
                totalPriority = calculateTotalPriority(task, blockedTasks, tasksById)
            )
        }

        tasksWithTotals.sortedWith(createComparator(orderingRules))
    }

    /**
     * Data class to hold filter parameters for SQL queries.
     */
    private data class FilterParams(
        val searchQuery: String? = null,
        val searchInId: Long = 1,
        val searchInTitle: Long = 1,
        val searchInDescription: Long = 0,
        val searchInTags: Long = 0,
        val priorityFilterType: Long = 0,
        val customPriorityMin: Long? = null,
        val customPriorityMax: Long? = null,
        val dueDateFilterType: Long = 0,
        val nowMillis: Long = 0,
        val todayStartMillis: Long = 0,
        val todayEndMillis: Long = 0,
        val weekEndMillis: Long = 0,
        val monthEndMillis: Long = 0,
        val customDueDateAfter: Long? = null,
        val customDueDateBefore: Long? = null,
        val estimatedTimeFilterType: Long = 0,
        val estimatedTimeMinMinutes: Long? = null,
        val estimatedTimeMaxMinutes: Long? = null,
        val recurrenceFilterType: Long = 0,
        val notificationsFilterType: Long = 0,
        val autoUpdateStatusFilterType: Long = 0,
        // Status filters from TaskFilterCriteria
        val criteriaStatusFilterType: Long = 0,
        val criteriaStatusOpen: Long = 0,
        val criteriaStatusInProgress: Long = 0,
        val criteriaStatusBlocked: Long = 0,
        val criteriaStatusDone: Long = 0,
        val criteriaStatusDeclined: Long = 0,
        // Connection type filters from TaskFilterCriteria
        val connectionFilterType: Long = 0,
        val requireDependsOn: Long = 0,
        val requireIsDependencyOf: Long = 0,
        val requireRelatesTo: Long = 0,
        val requireSubtaskOf: Long = 0,
        val requireParentOf: Long = 0,
        val requireNotSubtask: Long = 0,
        // Selected tags from TaskFilterCriteria (handled via separate query)
        val selectedTags: Set<String> = emptySet(),
        val tagMatchMode: TagMatchMode = TagMatchMode.Any
    )

    /**
     * Data class to hold group filter parameters for SQL queries.
     */
    private data class GroupFilterParams(
        val groupPriorityFilterType: Long = 0,
        val groupPriorityMin: Long? = null,
        val groupPriorityMax: Long? = null,
        val groupDueDateFilterType: Long = 0,
        val groupDueDateMin: Long? = null,
        val groupDueDateMax: Long? = null,
        val groupIsRecurring: Long? = null,
        val groupAutoUpdateStatus: Long? = null,
        val groupHasNotifications: Long? = null,
        val groupEstimatedTimeFilterType: Long = 0,
        val groupEstimatedTimeMinSeconds: Long? = null,
        val groupEstimatedTimeMaxSeconds: Long? = null,
        val groupHasConnections: Long? = null,
        val groupStatusFilterType: Long = 0,
        val groupStatusOpen: Long = 0,
        val groupStatusInProgress: Long = 0,
        val groupStatusBlocked: Long = 0,
        val groupStatusDone: Long = 0,
        val groupStatusDeclined: Long = 0,
        // For HasTags filter - task IDs that match tags (computed externally)
        val tagFilterTaskIds: Set<String>? = null,
        // For Not filter - task IDs to exclude (computed externally)
        val excludeTaskIds: Set<String>? = null
    )

    /**
     * Convert a boolean value to SQL Long parameter (1 for true, 0 for false, null for null).
     */
    private fun booleanToSqlParam(values: Set<String>): Long? {
        val value = values.firstOrNull()?.toBooleanStrictOrNull()
        return when (value) {
            true -> 1L
            false -> 0L
            null -> null
        }
    }

    /**
     * Compute range filter type based on includeNull flag and whether range bounds are present.
     * Returns: 0 = Not applied, 1 = Null only, 2 = Range only, 3 = Null + Range
     */
    private fun rangeFilterType(includeNull: Boolean, hasRange: Boolean): Long = when {
        includeNull && hasRange -> 3L // Null + Range
        includeNull -> 1L // Null only
        hasRange -> 2L // Range only
        else -> 0L // Not applied
    }

    /**
     * Build group filter params from a single GroupFilter.
     */
    private suspend fun buildGroupFilterParams(filter: GroupFilter): GroupFilterParams {
        val tz = TimeZone.currentSystemDefault()
        val today = clock.now().toLocalDateTime(tz).date

        return when (filter) {
            is GroupFilter.Values -> {
                when (filter.field) {
                    GroupableField.Status -> {
                        GroupFilterParams(
                            groupStatusFilterType = 1,
                            groupStatusOpen = if ("Open" in filter.values) 1 else 0,
                            groupStatusInProgress = if ("InProgress" in filter.values) 1 else 0,
                            groupStatusBlocked = if ("Blocked" in filter.values) 1 else 0,
                            groupStatusDone = if ("Done" in filter.values) 1 else 0,
                            groupStatusDeclined = if ("Declined" in filter.values) 1 else 0
                        )
                    }
                    GroupableField.IsRecurring -> GroupFilterParams(groupIsRecurring = booleanToSqlParam(filter.values))
                    GroupableField.HasConnections -> GroupFilterParams(groupHasConnections = booleanToSqlParam(filter.values))
                    GroupableField.HasNotifications -> GroupFilterParams(groupHasNotifications = booleanToSqlParam(filter.values))
                    GroupableField.AutoUpdateStatus -> GroupFilterParams(groupAutoUpdateStatus = booleanToSqlParam(filter.values))
                    else -> GroupFilterParams()
                }
            }
            is GroupFilter.PriorityRange -> {
                GroupFilterParams(
                    groupPriorityFilterType = rangeFilterType(filter.includeNull, filter.min != null || filter.max != null),
                    groupPriorityMin = filter.min?.toLong(),
                    groupPriorityMax = filter.max?.toLong()
                )
            }
            is GroupFilter.DueDateRange -> {
                val minDate = filter.minDays?.let {
                    LocalDate.fromEpochDays(today.toEpochDays() + it).atStartOfDayIn(tz).toEpochMilliseconds()
                }
                val maxDate = filter.maxDays?.let {
                    LocalDate.fromEpochDays(today.toEpochDays() + it + 1).atStartOfDayIn(tz).toEpochMilliseconds() - 1
                }
                GroupFilterParams(
                    groupDueDateFilterType = rangeFilterType(filter.includeNull, minDate != null || maxDate != null),
                    groupDueDateMin = minDate,
                    groupDueDateMax = maxDate
                )
            }
            is GroupFilter.EstimatedTimeRange -> {
                GroupFilterParams(
                    groupEstimatedTimeFilterType = rangeFilterType(filter.includeNull, filter.minSeconds != null || filter.maxSeconds != null),
                    groupEstimatedTimeMinSeconds = filter.minSeconds,
                    groupEstimatedTimeMaxSeconds = filter.maxSeconds
                )
            }
            is GroupFilter.HasTags -> {
                // For HasTags, we need to query task IDs separately
                GroupFilterParams(tagFilterTaskIds = emptySet()) // Will be filled in later
            }
            is GroupFilter.Not -> {
                // For Not filter, we need to compute excluded task IDs
                GroupFilterParams(excludeTaskIds = emptySet()) // Will be filled in later
            }
        }
    }

    /**
     * Merge multiple group filter params into one (ANDing the conditions).
     */
    private fun mergeGroupFilterParams(params: List<GroupFilterParams>): GroupFilterParams {
        if (params.isEmpty()) return GroupFilterParams()
        if (params.size == 1) return params.first()

        var result = GroupFilterParams()
        for (p in params) {
            // For each filter type, if it's applied (non-zero/non-null), use its value
            // If both have values, they must both match (AND semantics)
            result = result.copy(
                groupPriorityFilterType = if (p.groupPriorityFilterType != 0L) p.groupPriorityFilterType else result.groupPriorityFilterType,
                groupPriorityMin = p.groupPriorityMin ?: result.groupPriorityMin,
                groupPriorityMax = p.groupPriorityMax ?: result.groupPriorityMax,
                groupDueDateFilterType = if (p.groupDueDateFilterType != 0L) p.groupDueDateFilterType else result.groupDueDateFilterType,
                groupDueDateMin = p.groupDueDateMin ?: result.groupDueDateMin,
                groupDueDateMax = p.groupDueDateMax ?: result.groupDueDateMax,
                groupIsRecurring = p.groupIsRecurring ?: result.groupIsRecurring,
                groupAutoUpdateStatus = p.groupAutoUpdateStatus ?: result.groupAutoUpdateStatus,
                groupHasNotifications = p.groupHasNotifications ?: result.groupHasNotifications,
                groupEstimatedTimeFilterType = if (p.groupEstimatedTimeFilterType != 0L) p.groupEstimatedTimeFilterType else result.groupEstimatedTimeFilterType,
                groupEstimatedTimeMinSeconds = p.groupEstimatedTimeMinSeconds ?: result.groupEstimatedTimeMinSeconds,
                groupEstimatedTimeMaxSeconds = p.groupEstimatedTimeMaxSeconds ?: result.groupEstimatedTimeMaxSeconds,
                groupHasConnections = p.groupHasConnections ?: result.groupHasConnections,
                groupStatusFilterType = if (p.groupStatusFilterType != 0L) p.groupStatusFilterType else result.groupStatusFilterType,
                groupStatusOpen = if (p.groupStatusFilterType != 0L) p.groupStatusOpen else result.groupStatusOpen,
                groupStatusInProgress = if (p.groupStatusFilterType != 0L) p.groupStatusInProgress else result.groupStatusInProgress,
                groupStatusBlocked = if (p.groupStatusFilterType != 0L) p.groupStatusBlocked else result.groupStatusBlocked,
                groupStatusDone = if (p.groupStatusFilterType != 0L) p.groupStatusDone else result.groupStatusDone,
                groupStatusDeclined = if (p.groupStatusFilterType != 0L) p.groupStatusDeclined else result.groupStatusDeclined,
                tagFilterTaskIds = when {
                    p.tagFilterTaskIds != null && result.tagFilterTaskIds != null -> p.tagFilterTaskIds.intersect(result.tagFilterTaskIds)
                    p.tagFilterTaskIds != null -> p.tagFilterTaskIds
                    else -> result.tagFilterTaskIds
                },
                excludeTaskIds = when {
                    p.excludeTaskIds != null && result.excludeTaskIds != null -> p.excludeTaskIds + result.excludeTaskIds
                    p.excludeTaskIds != null -> p.excludeTaskIds
                    else -> result.excludeTaskIds
                }
            )
        }
        return result
    }

    // ============ Saved filter management ============

    override suspend fun getAllSavedFilters(spaceId: String): List<SavedFilter> =
        queries.getAllSavedFilters(spaceId).awaitAsList().map { it.toSavedFilterModel() }

    override suspend fun getAllSavedFiltersWithViewModes(spaceId: String): List<SavedFilterWithViewMode> = mutex.withLock{
        val filters = getAllSavedFilters(spaceId)
        filters.map { filter ->
            SavedFilterWithViewMode(
                filter = filter,
                attachedViewMode = filter.viewModeId?.let { getViewModeById(spaceId, it) }
            )
        }
    }

    override suspend fun getSavedFilterById(spaceId: String, filterId: String): SavedFilter? =
        queries.getSavedFilterById(spaceId, filterId).awaitAsOneOrNull()?.toSavedFilterModel()

    override suspend fun saveSavedFilter(filter: SavedFilter): SavedFilter {
        queries.insertOrUpdateSavedFilter(
            id = filter.id,
            spaceId = filter.spaceId,
            name = filter.name,
            criteriaJson = filter.criteria.toJson(),
            viewModeId = filter.viewModeId
        )
        return filter
    }

    override suspend fun deleteSavedFilter(spaceId: String, filterId: String): Boolean = mutex.withLock {
        val exists = queries.getSavedFilterById(spaceId, filterId).awaitAsOneOrNull() != null
        if (exists) {
            queries.deleteSavedFilter(spaceId, filterId)
        }
        exists
    }
}

private fun Saved_filters.toSavedFilterModel() = SavedFilter(
    id = id,
    name = name,
    spaceId = spaceId,
    criteria = criteriaJson.toTaskFilterCriteria(),
    viewModeId = viewModeId
)

private fun Spaces.toModel() = Space(
    id = id,
    name = name,
    idPrefix = idPrefix
)
