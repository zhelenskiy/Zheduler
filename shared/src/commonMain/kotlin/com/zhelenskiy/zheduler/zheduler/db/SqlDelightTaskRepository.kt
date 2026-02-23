@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.db

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.zhelenskiy.zheduler.zheduler.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
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
 * Note: Space selection is managed by the UI layer. All methods that operate
 * on tasks within a space require an explicit spaceId parameter.
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

    override suspend fun getAllSpaces(): List<Space> =
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

    override suspend fun getAll(spaceId: String): List<Task> =
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
        val tasks = getAll(spaceId)
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
        // Batch fetch all needed tasks in a single query
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
            recurrenceState = entity.recurrenceStateJson.toRecurrenceState(),
            resetStatusOnRecurrence = entity.resetStatusOnRecurrenceJson.toTaskStatus(),
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
        recurrenceRules: List<RecurrenceRule>,
        resetStatusOnRecurrence: TaskStatus,
        autoUpdateStatusFromSubtasks: Boolean
    ): Task? = mutex.withLock {
        if (queries.getSpaceById(spaceId).awaitAsOneOrNull() == null) return@withLock null
        addTaskUnsafe(
            spaceId, title, description, status, dueDate, priority, estimatedTime,
            tags, connections, notifications, customId, recurrenceRules,
            resetStatusOnRecurrence, autoUpdateStatusFromSubtasks
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
            recurrenceStateJson = task.recurrenceState.toJson(),
            resetStatusOnRecurrenceJson = task.resetStatusOnRecurrence.toJson(),
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
        val tasksById = getAll(spaceId).associateBy { it.id }

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
                    resetStatusOnRecurrence = task.resetStatusOnRecurrence,
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
        recurrenceRules: List<RecurrenceRule>,
        resetStatusOnRecurrence: TaskStatus,
        autoUpdateStatusFromSubtasks: Boolean
    ): Task? {
        val taskId = customId ?: generateNextIdUnsafe(spaceId)

        val recurrenceState = RecurrenceService.initializeRecurrence(recurrenceRules)
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
            recurrenceState = recurrenceState,
            resetStatusOnRecurrence = resetStatusOnRecurrence,
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
            recurrenceStateJson = recurrenceState.toJson(),
            resetStatusOnRecurrenceJson = resetStatusOnRecurrence.toJson(),
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
        triggerEvent: RecurrenceTriggerEvent,
        triggerTime: Instant
    ): Task? = mutex.withLock {
        processRecurrenceTriggerInternal(taskId, triggerEvent, triggerTime)
    }

    override suspend fun processDateBasedRecurrences(currentTime: Instant): List<Task> = mutex.withLock {
        processDateBasedRecurrencesInternal(currentTime)
    }
}

private fun Spaces.toModel() = Space(
    id = id,
    name = name,
    idPrefix = idPrefix
)
