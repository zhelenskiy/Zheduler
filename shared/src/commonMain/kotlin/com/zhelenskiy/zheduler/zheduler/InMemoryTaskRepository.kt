@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.paging.Page
import com.zhelenskiy.zheduler.zheduler.paging.toPage
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
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
    private val tagsBySpace = mutableMapOf<String, MutableSet<String>>()
    private val filterStateBySpaceId = mutableMapOf<String, TaskFilterCriteria>()
    private val viewModeBySpaceId = mutableMapOf<String, String>()
    private val filterPanelOpenBySpaceId = mutableMapOf<String, Boolean>()
    private val customViewModes = mutableMapOf<String, MutableMap<String, ViewMode>>() // spaceId -> (viewModeId -> ViewMode)
    private val activeViewModeBySpaceId = mutableMapOf<String, String>()
    private val savedFilters = mutableMapOf<String, MutableMap<String, SavedFilter>>() // spaceId -> (filterId -> SavedFilter)

    override suspend fun hasSpaces(): Boolean = mutex.withLock { spaces.isNotEmpty() }

    override suspend fun getAllSpaces(): List<Space> = mutex.withLock { spaces.values.toList() }

    override suspend fun getSpaceById(id: String): Space? = mutex.withLock { spaces[id] }

    override suspend fun filterSpacesPage(
        query: String,
        searchInName: Boolean,
        searchInPrefix: Boolean,
        offset: Int,
        limit: Int
    ): Page<Space> = mutex.withLock {
        if (query.isBlank()) return@withLock spaces.values.toList().toPage(offset, limit)

        spaces.values.filter { space ->
            val matchesName = searchInName && space.name.contains(query, ignoreCase = true)
            val matchesPrefix = searchInPrefix && space.idPrefix.contains(query, ignoreCase = true)
            matchesName || matchesPrefix
        }.toPage(offset, limit)
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
        notifyChanged()
        space
    }

    override suspend fun updateSpaceName(spaceId: String, newName: String): Boolean = mutex.withLock {
        val space = spaces[spaceId] ?: return@withLock false
        if (newName.isBlank()) return@withLock false

        val updatedSpace = space.copy(name = newName)
        spaces[spaceId] = updatedSpace
        notifyChanged()
        true
    }

    override suspend fun deleteSpace(spaceId: String): Boolean = mutex.withLock {
        if (!spaces.containsKey(spaceId)) return@withLock false

        // Get all task IDs in this space before deletion
        val taskIdsInSpace = tasks.values.filter { it.spaceId == spaceId }.map { it.id }.toSet()

        // Scanned from the map directly: this already holds the lock those reads would take.
        handleCrossSpaceRelationshipsOnSpaceDeletion(taskIdsInSpace, tasks.values.toList())

        // Remove all tasks and their timelines in this space directly
        taskIdsInSpace.forEach { taskId ->
            tasks.remove(taskId)
            statusTimelines.remove(taskId)
        }

        // Matching the schema's ON DELETE CASCADE. Space ids are derived from how many spaces
        // exist, so a leftover is inherited by the next space created with the same prefix.
        spaces.remove(spaceId)
        nextIdBySpace.remove(spaceId)
        filterStateBySpaceId.remove(spaceId)
        viewModeBySpaceId.remove(spaceId)
        filterPanelOpenBySpaceId.remove(spaceId)
        savedFilters.remove(spaceId)
        tagsBySpace.remove(spaceId)
        customViewModes.remove(spaceId)
        activeViewModeBySpaceId.remove(spaceId)
        notifyChanged()

        true
    }

    override suspend fun getTasksInSpace(spaceId: String): List<Task> = mutex.withLock {
        tasks.values.filter { it.spaceId == spaceId }
    }

    override suspend fun removeConnectionsToDeletedTasks(taskId: String, connections: List<TaskConnection>) {
        // In-memory: connections are already removed via the copy in handleCrossSpaceRelationshipsOnSpaceDeletion
        // No additional action needed
    }

    override suspend fun hasAnyTasks(spaceId: String): Boolean = mutex.withLock {
        tasks.values.any { it.spaceId == spaceId }
    }

    override suspend fun getAllTasks(spaceId: String): List<Task> = mutex.withLock {
        tasks.values.filter { it.spaceId == spaceId }.toList()
    }

    override suspend fun filterTasksForSelectionPage(
        spaceId: String,
        excludeTaskId: String?,
        searchQuery: String,
        offset: Int,
        limit: Int
    ): Page<Task> = mutex.withLock {
        tasks.values.filter { task ->
            task.spaceId == spaceId &&
            (excludeTaskId == null || task.id != excludeTaskId) &&
            (searchQuery.isBlank() ||
             task.id.contains(searchQuery, ignoreCase = true) ||
             task.title.contains(searchQuery, ignoreCase = true))
        }.toPage(offset, limit)
    }

    override suspend fun searchTasksForConnectionPage(
        spaceId: String,
        excludeTaskId: String?,
        searchQuery: String,
        excludeTaskIds: Set<String>,
        connectionType: ConnectionType,
        existingConnections: Set<TaskConnection>,
        offset: Int,
        limit: Int
    ): Page<Task> = mutex.withLock {
        val matching = tasks.values.filter { task ->
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
        val from = offset.coerceIn(0, matching.size)
        val to = (from.toLong() + limit.coerceAtLeast(0)).coerceAtMost(matching.size.toLong()).toInt()
        // Total left unknown to match the database repository, which cannot count cycle-free
        // candidates without walking the whole space.
        Page(
            items = matching.subList(from, to).toList(),
            offset = offset.coerceAtLeast(0),
            totalCount = null,
            hasMore = to < matching.size,
        )
    }

    override suspend fun getAllSpacePrefixes(): List<String> = mutex.withLock {
        spaces.values.map { it.idPrefix }
    }

    override suspend fun getAllTasksWithTotals(spaceId: String): List<TaskWithTotals> = mutex.withLock {
        calculateTotals(tasks.values.filter { it.spaceId == spaceId }, getBlockedTasks())
    }

    // Note: getById does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches RoomTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun getTaskById(id: String): Task? = tasks[id]

    override suspend fun getTasksByIds(ids: Set<String>): List<Task> = ids.mapNotNull { tasks[it] }

    override suspend fun getTasksByIdWithTotals(id: String): TaskWithTotals? = mutex.withLock {
        tasks[id]?.let { task ->
            val totals = taskTotals(task, getBlockedTasks(), tasks)
            TaskWithTotals(
                task = task,
                totalDueDate = totals.totalDueDate,
                totalPriority = totals.totalPriority,
            )
        }
    }

    override suspend fun getAllTags(spaceId: String): PersistentSet<String> = mutex.withLock {
        tagsBySpace[spaceId]?.toPersistentSet() ?: persistentSetOf()
    }

    override suspend fun filterTagsPage(
        spaceId: String,
        searchQuery: String,
        excludeTags: Set<String>,
        offset: Int,
        limit: Int
    ): Page<String> = mutex.withLock {
        val spaceTags = tagsBySpace[spaceId] ?: emptySet()
        val availableTags = spaceTags.filter { it !in excludeTags }
        val matching = if (searchQuery.isBlank()) {
            availableTags.sorted()
        } else {
            availableTags.filter { it.contains(searchQuery, ignoreCase = true) }.sorted()
        }
        matching.toPage(offset, limit)
    }

    override suspend fun addTag(spaceId: String, tag: String): Boolean = mutex.withLock {
        val name = tag.trim()
        if (name.isBlank()) return@withLock false
        val added = tagsBySpace.getOrPut(spaceId) { mutableSetOf() }.add(name)
        if (added) notifyChanged()
        added
    }

    override suspend fun deleteTag(spaceId: String, tag: String): Boolean = mutex.withLock {
        val name = tag.trim()
        if (name.isBlank()) return@withLock false
        val removed = tagsBySpace[spaceId]?.remove(name) ?: false
        if (removed) notifyChanged()
        removed
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

                    if (date.year == year && date.month.number == month) {
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
        // See RoomTaskRepository.generateNextIdUnsafe: the counter is not the only source of ids.
        var nextNum = nextIdBySpace.getOrPut(spaceId) { 1 }
        while ("${space.idPrefix}-$nextNum" in tasks) nextNum++
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
        tags: PersistentSet<String>,
        connections: PersistentSet<TaskConnection>,
        notifications: PersistentList<TaskNotification>,
        customId: String?,
        recurrenceRules: PersistentList<Pair<RecurrenceRule, RecurrenceState>>,
        autoUpdateStatusFromSubtasks: Boolean
    ): Task? = mutex.withLock {
        if (!spaces.containsKey(spaceId)) return@withLock null
        val taskId = customId ?: generateNextIdUnsafe(spaceId)

        val status = if (autoUpdateStatusFromSubtasks) {
            val subtasksIds = connections
                .mapNotNull { if (it.type == ConnectionType.ParentOf) it.targetTaskId else null }
            getCalculatedStatusFromSubtasks(subtasksIds, ::getTaskById) ?: status
        } else {
            status
        }.let { withoutResolvedBlockers(it) }

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
        tagsBySpace.getOrPut(spaceId) { mutableSetOf() }.addAll(tags)

        // Same check the database repository makes. This one is the oracle the comparison suites
        // measure against, so leaving it out meant an identical save could be refused by one and
        // accepted by the other.
        rejectCycles(task.id, connections, connections)
        connections.forEach { connection ->
            addSymmetricConnectionUnsafe(task.id, connection)
        }
        notifyChanged()

        task
    }

    override suspend fun updateTask(task: Task): Task? = mutex.withLock {
        val oldTask = tasks[task.id] ?: return@withLock null

        val removedConnections = oldTask.connections.removingAll(task.connections)
        removedConnections.forEach { connection ->
            removeSymmetricConnectionUnsafe(task.id, connection)
        }

        val addedConnections = task.connections.removingAll(oldTask.connections)
        rejectCycles(task.id, task.connections, addedConnections)
        addedConnections.forEach { connection ->
            addSymmetricConnectionUnsafe(task.id, connection)
        }

        tasks[task.id] = task

        // Use AbstractTaskRepository's shared methods (they don't acquire mutex)
        val (finalTask, automaticReason) = calculateFinalTaskStatusOnUpdate(task.id, task, oldTask)
        handleStatusChangeOnUpdate(finalTask, oldTask, automaticReason)

        tasks[task.id] = finalTask
        tagsBySpace.getOrPut(finalTask.spaceId) { mutableSetOf() }.addAll(finalTask.tags)

        handleStatusCascadeOnUpdate(finalTask.id, oldTask.status, finalTask.status)
        notifyChanged()

        finalTask
    }

    override suspend fun addConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean = mutex.withLock {
        val fromTask = tasks[fromTaskId] ?: return@withLock false
        if (!tasks.containsKey(toTaskId)) return@withLock false

        val connection = TaskConnection(toTaskId, type)
        if (connection in fromTask.connections) return@withLock true

        // See RoomTaskRepository.addConnectionUnsafe: the task's committed edges are what the
        // cycle check would otherwise be blind to.
        if (wouldCreateCycle(fromTaskId, toTaskId, type, fromTask.connections)) {
            return@withLock false
        }

        val updatedTask = fromTask.copy(connections = fromTask.connections.adding(connection))
        tasks[fromTaskId] = updatedTask

        addSymmetricConnectionUnsafe(fromTaskId, connection)
        notifyChanged()

        true
    }

    override suspend fun removeConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean = mutex.withLock {
        val fromTask = tasks[fromTaskId] ?: return@withLock false

        val connection = TaskConnection(toTaskId, type)
        if (connection !in fromTask.connections) return@withLock true

        val updatedTask = fromTask.copy(connections = fromTask.connections.removing(connection))
        tasks[fromTaskId] = updatedTask

        removeSymmetricConnectionUnsafe(fromTaskId, connection)
        notifyChanged()

        true
    }

    private suspend fun addSymmetricConnectionUnsafe(sourceTaskId: String, connection: TaskConnection) {
        val targetTask = tasks[connection.targetTaskId] ?: return
        val symmetricConnection = TaskConnection(sourceTaskId, connection.type.symmetric)

        if (symmetricConnection !in targetTask.connections) {
            val updatedTask = targetTask.copy(
                connections = targetTask.connections.adding(symmetricConnection)
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
                connections = targetTask.connections.removing(symmetricConnection)
            )
            tasks[connection.targetTaskId] = updatedTask

            if (symmetricConnection.type == ConnectionType.ParentOf) {
                // Use AbstractTaskRepository's shared method (doesn't acquire mutex)
                updateParentStatusIfNeeded(connection.targetTaskId)
            }
        }
    }

    // Note: getParentTasks does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches RoomTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun getParentTasks(taskId: String): List<Task> {
        val task = tasks[taskId] ?: return emptyList()
        return task.connections
            .filter { it.type == ConnectionType.SubtaskOf }
            .mapNotNull { tasks[it.targetTaskId] }
    }

    // Note: getSubtasks does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches RoomTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun getSubtasks(taskId: String): List<Task> {
        val task = tasks[taskId] ?: return emptyList()
        return task.connections
            .filter { it.type == ConnectionType.ParentOf }
            .mapNotNull { tasks[it.targetTaskId] }
    }

    override suspend fun getConnectionsForTaskSync(taskId: String): PersistentSet<TaskConnection>? =
        tasks[taskId]?.connections

    // Note: recordStatusChange does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches RoomTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun recordStatusChange(
        taskId: String,
        previousStatus: TaskStatus?,
        newStatus: TaskStatus,
        automaticChangeReason: AutomaticChangeReason?
    ) {
        statusTimelines[taskId] = (statusTimelines[taskId]?.toPersistentList() ?: persistentListOf())
            .adding(
                StatusChange(
                    timestamp = clock.now(),
                    previousStatus = previousStatus,
                    newStatus = newStatus,
                    automaticChangeReason = automaticChangeReason
                )
            )
    }

    // Note: persistTaskUpdate does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches RoomTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun persistTaskUpdate(task: Task) {
        tasks[task.id] = task
    }

    // Note: getBlockedTasks does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches RoomTaskRepository and allows AbstractTaskRepository's internal methods to work.
    override suspend fun getBlockedTasks(): List<Task> =
        tasks.values.filter { it.status is TaskStatus.Blocked }

    override suspend fun getTasksBlockedBy(blockerId: String): List<Task> =
        tasks.values.filter { blockerId in (it.status as? TaskStatus.Blocked)?.blockerTaskIds.orEmpty() }

    // Note: getRecurringTasksDueBefore does NOT acquire mutex. Callers needing thread safety must acquire mutex themselves.
    // This design matches RoomTaskRepository and allows AbstractTaskRepository's internal methods to work.
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
        notifyChanged()

        true
    }

    override suspend fun getAllWithTotalsFilteredPage(
        spaceId: String,
        criteria: TaskFilterCriteria,
        offset: Int,
        limit: Int
    ): Page<TaskWithTotals> = mutex.withLock {
        getAllWithTotalsFilteredUnsafe(spaceId, criteria).toPage(offset, limit)
    }

    override suspend fun countAllWithTotalsFiltered(spaceId: String, criteria: TaskFilterCriteria): Int = mutex.withLock {
        getAllWithTotalsFilteredUnsafe(spaceId, criteria).size
    }

    /**
     * Internal version that doesn't acquire mutex - for use within methods that already hold the lock.
     */
    private suspend fun getAllWithTotalsFilteredUnsafe(spaceId: String, criteria: TaskFilterCriteria): List<TaskWithTotals> {
        val spaceTasks = tasks.values.filter { it.spaceId == spaceId }
        val tasksWithTotals = calculateTotals(spaceTasks, getBlockedTasks())
        return filterTasksWithCriteria(tasksWithTotals, criteria)
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

    // ============ View mode management ============

    override suspend fun getAllViewModes(spaceId: String): List<ViewMode> = mutex.withLock {
        val builtIn = ViewMode.getBuiltInModes(spaceId)
        val custom = customViewModes[spaceId]?.values?.toList() ?: emptyList()
        builtIn.toPersistentList().addingAll(custom)
    }

    override suspend fun getViewModeById(spaceId: String, viewModeId: String): ViewMode? = mutex.withLock {
        // Check built-in modes first
        ViewMode.getBuiltInModes(spaceId).find { it.id == viewModeId } ?: customViewModes[spaceId]?.get(viewModeId)
    }

    override suspend fun saveViewMode(viewMode: ViewMode): ViewMode = mutex.withLock {
        require(!viewMode.isBuiltIn) { "Cannot modify built-in view modes" }
        val spaceViewModes = customViewModes.getOrPut(viewMode.spaceId) { mutableMapOf() }
        spaceViewModes[viewMode.id] = viewMode
        viewMode
    }

    override suspend fun deleteViewMode(spaceId: String, viewModeId: String): Boolean = mutex.withLock {
        // Cannot delete built-in modes
        if (ViewMode.getBuiltInModes(spaceId).any { it.id == viewModeId }) {
            return@withLock false
        }
        val removed = customViewModes[spaceId]?.remove(viewModeId) != null
        // If this was the active mode, reset to default
        if (removed && activeViewModeBySpaceId[spaceId] == viewModeId) {
            activeViewModeBySpaceId[spaceId] = "priority"
        }
        removed
    }

    override suspend fun getActiveViewMode(spaceId: String): ViewMode = mutex.withLock {
        val activeId = activeViewModeBySpaceId[spaceId] ?: "priority"
        // Check built-in modes first
        ViewMode.getBuiltInModes(spaceId).find { it.id == activeId }
            ?: customViewModes[spaceId]?.get(activeId)
            ?: ViewMode.priority(spaceId)
    }

    override suspend fun setActiveViewMode(spaceId: String, viewModeId: String) = mutex.withLock {
        activeViewModeBySpaceId[spaceId] = viewModeId
    }

    override suspend fun exportSpaceToJson(spaceId: String, prettyPrint: Boolean): String? = mutex.withLock {
        val space = spaces[spaceId] ?: return@withLock null
        val spaceTasks = tasks.values.filter { it.spaceId == spaceId }
        val spaceTimelines = spaceTasks.associate { task ->
            task.id to (statusTimelines[task.id] ?: emptyList())
        }
        // The space's whole vocabulary, as in RoomTaskRepository: a tag created but not yet put
        // on a task is still the user's and has to survive an export.
        val spaceTags = tagsBySpace[spaceId].orEmpty().toSet()
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
            val newPrefix = uniqueSpacePrefix(exportData.space.idPrefix) { candidate ->
                spaces.values.any { it.idPrefix == candidate }
            }

            val newSpaceId = "space-${spaces.size}-$newPrefix"

            val newSpace = exportData.space.copy(
                id = newSpaceId,
                idPrefix = newPrefix
            )
            spaces[newSpaceId] = newSpace

            val oldToNewTaskId = createTaskIdMapping(exportData.tasks, newPrefix)
            nextIdBySpace[newSpaceId] = nextIdAfter(oldToNewTaskId.values, exportData.nextId)

            exportData.tasks.forEach { task ->
                val newTaskId = oldToNewTaskId[task.id] ?: return@forEach
                val remappedConnections = task.connections.mapNotNullToPersistentSet { connection ->
                    oldToNewTaskId[connection.targetTaskId]?.let { newTargetId ->
                        connection.copy(targetTaskId = newTargetId)
                    }
                }

                val remappedStatus = remapBlockedStatus(task.status, oldToNewTaskId)

                val newTask = task.copy(
                    id = newTaskId,
                    spaceId = newSpaceId,
                    connections = remappedConnections,
                    status = remappedStatus
                )
                tasks[newTaskId] = newTask

                // Blocked entries in the history name the tasks that blocked it, so they are
                // remapped like the current status; left alone they point at whatever space now
                // answers to the old prefix.
                val timeline = (exportData.statusTimelines[task.id] ?: emptyList()).map { change ->
                    change.copy(
                        previousStatus = change.previousStatus?.let { remapBlockedStatus(it, oldToNewTaskId) },
                        newStatus = remapBlockedStatus(change.newStatus, oldToNewTaskId),
                    )
                }
                if (timeline.isNotEmpty()) {
                    statusTimelines[newTaskId] = timeline
                }
            }

            tagsBySpace.getOrPut(newSpace.id) { mutableSetOf() }.addAll(exportData.tags)
            notifyChanged()

            newSpace
        }
    }

    // ============ Space deletion helper ============


    // ============ Thread-safe overrides for AbstractTaskRepository methods ============
    // These methods do read-modify-write operations and need atomic mutex protection.
    // We wrap the AbstractTaskRepository's internal methods with mutex to ensure atomicity.
    // This works because getById, persistTaskUpdate, recordStatusChange, etc. don't acquire mutex.

    override suspend fun processRecurrenceTrigger(
        taskId: String,
        triggerEvent: RecurrenceTriggerEvent
    ): Task? = mutex.withLock {
        processRecurrenceTriggerInternal(taskId, triggerEvent)?.also { notifyChanged() }
    }

    override suspend fun processDateBasedRecurrences(currentTime: Instant): List<Task> = mutex.withLock {
        processDateBasedRecurrencesInternal(currentTime).also { if (it.isNotEmpty()) notifyChanged() }
    }

    override suspend fun clearAllData() = mutex.withLock {
        spaces.clear()
        tasks.clear()
        statusTimelines.clear()
        tagsBySpace.clear()
        nextIdBySpace.clear()
        filterStateBySpaceId.clear()
        viewModeBySpaceId.clear()
        filterPanelOpenBySpaceId.clear()
        customViewModes.clear()
        activeViewModeBySpaceId.clear()
        savedFilters.clear()
        notifyChanged()
    }

    // ============ Grouped task queries ============

    override suspend fun getTaskGroups(
        spaceId: String,
        viewMode: ViewMode,
        levelIndex: Int,
        parentFilters: PersistentList<GroupFilter>,
        filterCriteria: TaskFilterCriteria
    ): List<TaskGroupInfo> = mutex.withLock {
        if (levelIndex >= viewMode.groupingLevels.size) {
            return@withLock emptyList()
        }

        val level = viewMode.groupingLevels[levelIndex]

        // Get all tasks matching parent filters and filter criteria
        // Note: use internal method without mutex since we already hold it
        val today = today()
        val allTasks = getAllWithTotalsFilteredUnsafe(spaceId, filterCriteria)
        val filteredTasks = allTasks.filter { task ->
            parentFilters.all { filter -> task.matchesGroupFilter(filter, today) }
        }

        val result = mutableListOf<TaskGroupInfo>()
        val matchedTaskIds = mutableSetOf<String>()

        // Process each group definition
        for (group in level.groups) {
            val groupFilter = group.toFilter(level.field)
            val matchingTasks = filteredTasks.filter { task ->
                task.matchesGroupFilter(groupFilter, today)
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

        // Add uncategorized group for tasks that didn't match any group
        val uncategorizedTasks = filteredTasks.filter { it.task.id !in matchedTaskIds }
        if (uncategorizedTasks.isNotEmpty()) {
            // Create a negation filter for uncategorized
            val allGroupFilters = level.groups.mapToPersistentList { it.toFilter(level.field) }
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

        result
    }

    override suspend fun getTasksForGroupPage(
        spaceId: String,
        filters: PersistentList<GroupFilter>,
        orderingRules: PersistentList<OrderingRule>,
        filterCriteria: TaskFilterCriteria,
        offset: Int,
        limit: Int
    ): Page<TaskWithTotals> = mutex.withLock {
        orderedTasksForGroupUnsafe(spaceId, filters, orderingRules, filterCriteria).toPage(offset, limit)
    }

    override suspend fun countTasksForGroup(
        spaceId: String,
        filters: PersistentList<GroupFilter>,
        filterCriteria: TaskFilterCriteria
    ): Int = mutex.withLock {
        // Note: use internal method without mutex since we already hold it
        val today = today()
        getAllWithTotalsFilteredUnsafe(spaceId, filterCriteria).count { task ->
            filters.all { filter -> task.matchesGroupFilter(filter, today) }
        }
    }

    /**
     * Everything a group matches, in display order. In-memory storage has nothing cheaper to page
     * against, so windows are cut from this list.
     */
    private suspend fun orderedTasksForGroupUnsafe(
        spaceId: String,
        filters: PersistentList<GroupFilter>,
        orderingRules: PersistentList<OrderingRule>,
        filterCriteria: TaskFilterCriteria
    ): List<TaskWithTotals> {
        // Note: use internal method without mutex since we already hold it
        val today = today()
        val allTasks = getAllWithTotalsFilteredUnsafe(spaceId, filterCriteria)

        val filteredTasks = allTasks.filter { task ->
            filters.all { filter -> task.matchesGroupFilter(filter, today) }
        }

        return filteredTasks.sortedWith(createComparator(orderingRules))
    }

    // ============ Saved filter management ============

    override suspend fun getAllSavedFilters(spaceId: String): List<SavedFilter> = mutex.withLock {
        savedFilters[spaceId]?.values?.toList().orEmpty()
    }

    override suspend fun getAllSavedFiltersWithViewModes(spaceId: String): List<SavedFilterWithViewMode> {
        val filters = getAllSavedFilters(spaceId)
        return filters.map { filter ->
            SavedFilterWithViewMode(
                filter = filter,
                attachedViewMode = filter.viewModeId?.let { getViewModeById(spaceId, it) }
            )
        }
    }

    override suspend fun getSavedFilterById(spaceId: String, filterId: String): SavedFilter? = mutex.withLock {
        savedFilters[spaceId]?.get(filterId)
    }

    override suspend fun saveSavedFilter(filter: SavedFilter): SavedFilter = mutex.withLock {
        val spaceFilters = savedFilters.getOrPut(filter.spaceId) { mutableMapOf() }
        spaceFilters[filter.id] = filter
        filter
    }

    override suspend fun deleteSavedFilter(spaceId: String, filterId: String): Boolean = mutex.withLock {
        savedFilters[spaceId]?.remove(filterId) != null
    }
}
