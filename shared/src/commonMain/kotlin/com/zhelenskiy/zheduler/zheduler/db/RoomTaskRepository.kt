@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.db

import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.paging.Page
import com.zhelenskiy.zheduler.zheduler.paging.toPage
import kotlinx.collections.immutable.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Upper bound on ids passed to an `IN (...)` clause. SQLite's own limit is higher, but reading a
 * space's rows in one statement beats binding thousands of parameters, so the repository switches
 * strategy here.
 */
private const val MAX_SQL_PARAMETERS = 500

/** How many candidate rows to pull per round when a query post-filters what SQL returned. */
private const val CANDIDATE_SCAN_SIZE = 100

/**
 * How many ranked candidate sets to keep. A screen ranks one per visible group, so this only has
 * to cover the groups of a view mode with room to spare; entries are dropped on the next mutation
 * anyway.
 */
private const val MAX_CACHED_QUERIES = 64

/**
 * Room-based implementation of TaskRepository
 * Uses SQLite for persistence with proper indexing across all platforms.
 * All compound operations (read-modify-write) are protected by a coroutine Mutex
 * to ensure thread safety.
 *
 */
class RoomTaskRepository(
    private val database: ZhedulerDatabase,
    clock: Clock = Clock.System
) : AbstractTaskRepository(clock) {
    private val mutex = Mutex()
    private val dao = database.dao()

    // ============ Paged query memoisation ============
    //
    // Ordering can reference totals derived from the dependency graph, so [getTasksForGroupPage]
    // has to rank the whole matching set before it can cut a window. Recomputing that per page
    // makes every page cost O(space size) — page 60 costs what page 1 does — and scrolling then
    // re-scans the space once per page. Remembering the ranking makes each subsequent page cost
    // only the hydration of its own window.
    //
    // Entries hold ids rather than rows so the memory is proportional to the number of tasks, not
    // to their contents, and they live only until the next mutation: one query per group per data
    // version. As a side effect pages of one scroll now come from a single consistent ranking,
    // where before each page re-ranked against a freshly read `now`.

    private data class CandidateKey(
        val spaceId: String,
        val filters: List<GroupFilter>,
        val filterCriteria: TaskFilterCriteria,
    )

    private data class OrderedKey(
        val candidate: CandidateKey,
        val orderingRules: List<OrderingRule>,
    )

    /** A ranked candidate set: ids in display order, plus the totals they were ranked by. */
    private class OrderedCandidates(
        val ids: List<String>,
        val totals: Map<String, TaskTotals>,
    )

    private val orderedCache = mutableMapOf<OrderedKey, OrderedCandidates>()
    private val countCache = mutableMapOf<CandidateKey, Int>()

    /**
     * Bumped on every mutation. Read and compared under [mutex] so the caches themselves are only
     * ever touched by one coroutine; a lost increment under a racing write is harmless, since all
     * that matters is that the value differs from the one the caches were filled at.
     */
    @Volatile
    private var dataVersion = 0L
    private var cachedVersion = 0L

    override fun onDataChanged() {
        dataVersion++
    }

    /** Drop memoised rankings if anything has changed since they were computed. Call under [mutex]. */
    private fun evictStaleCaches() {
        val current = dataVersion
        if (cachedVersion != current) {
            orderedCache.clear()
            countCache.clear()
            cachedVersion = current
        }
    }

    /** Insert, evicting the oldest entry first so a long-lived space cannot grow the cache without bound. */
    private fun <K, V> MutableMap<K, V>.putCapped(key: K, value: V) {
        if (size >= MAX_CACHED_QUERIES && key !in this) {
            remove(keys.first())
        }
        put(key, value)
    }

    /**
     * The ranked candidate set for one paged query, computed once per data version.
     *
     * Must be called under [mutex].
     */
    private suspend fun orderedCandidatesFor(
        spaceId: String,
        filters: PersistentList<GroupFilter>,
        orderingRules: PersistentList<OrderingRule>,
        filterCriteria: TaskFilterCriteria,
    ): OrderedCandidates {
        evictStaleCaches()

        val candidateKey = CandidateKey(spaceId, filters, filterCriteria)
        val key = OrderedKey(candidateKey, orderingRules)
        orderedCache[key]?.let { return it }

        val rows = queryCandidateRows(spaceId, filters, buildFilterParams(filterCriteria))
        val ordered = orderCandidates(spaceId, rows, orderingRules)
        val result = OrderedCandidates(
            ids = ordered.map { it.row.id },
            totals = ordered.associate { it.row.id to it.totals },
        )

        orderedCache.putCapped(key, result)
        // Ranking neither adds nor drops candidates, so this is the group's count as well.
        countCache.putCapped(candidateKey, result.ids.size)
        return result
    }

    // Space operations
    override suspend fun hasSpaces(): Boolean =
        dao.hasSpaces()

    override suspend fun getAllTasks(): List<Space> =
        dao.getAllSpaces().map { it.toModel() }

    override suspend fun getSpaceById(id: String): Space? =
        dao.getSpaceById(id)?.toModel()

    override suspend fun filterSpacesPage(
        query: String,
        searchInName: Boolean,
        searchInPrefix: Boolean,
        offset: Int,
        limit: Int
    ): Page<Space> {
        if (query.isBlank()) {
            return Page(
                items = dao.getAllSpacesPaged(limit = limit, offset = offset).map { it.toModel() },
                offset = offset,
                totalCount = dao.countSpaces(),
            )
        }
        val searchInNameFlag = if (searchInName) 1L else 0L
        val searchInPrefixFlag = if (searchInPrefix) 1L else 0L
        return Page(
            items = dao.filterSpacesPaged(
                searchInName = searchInNameFlag,
                query = query,
                searchInPrefix = searchInPrefixFlag,
                limit = limit,
                offset = offset,
            ).map { it.toModel() },
            offset = offset,
            totalCount = dao.countFilteredSpaces(
                searchInName = searchInNameFlag,
                query = query,
                searchInPrefix = searchInPrefixFlag,
            ),
        )
    }

    override suspend fun createSpace(name: String, idPrefix: String): Space? = mutex.withLock {
        if (!idPrefix.matches(Regex("^[A-Z]+$")) || idPrefix.isEmpty()) return@withLock null
        if (dao.prefixExists(idPrefix)) return@withLock null

        val spaces = dao.getAllSpaces()
        val spaceId = "space-${spaces.size}-${idPrefix}"
        val space = Space(id = spaceId, name = name, idPrefix = idPrefix)

        dao.insertSpace(spaceId, name, idPrefix)
        dao.setNextId(spaceId, 1)
        notifyChanged()

        space
    }

    override suspend fun updateSpaceName(spaceId: String, newName: String): Boolean = mutex.withLock {
        if (dao.getSpaceById(spaceId) == null) return@withLock false
        if (newName.isBlank()) return@withLock false

        dao.updateSpace(newName, spaceId)
        notifyChanged()
        true
    }

    override suspend fun deleteSpace(spaceId: String): Boolean = mutex.withLock {
        if (dao.getSpaceById(spaceId) == null) return@withLock false

        val taskIdsInSpace = dao.getTasksBySpace(spaceId).map { it.id }.toSet()

        handleCrossSpaceRelationshipsOnSpaceDeletion(taskIdsInSpace)

        dao.deleteSpace(spaceId)
        notifyChanged()

        true
    }

    override suspend fun getTasksInSpace(spaceId: String): List<Task> =
        hydrateTasks(spaceId, dao.getTasksBySpace(spaceId))

    override suspend fun removeConnectionsToDeletedTasks(taskId: String, connections: List<TaskConnection>) {
        connections.forEach { connection ->
            dao.deleteConnection(taskId, connection.targetTaskId, connection.type.name)
        }
    }

    override suspend fun hasAnyTasks(spaceId: String): Boolean =
        dao.hasAnyTasks(spaceId)

    override suspend fun getAllTasks(spaceId: String): List<Task> =
        hydrateTasks(spaceId, dao.getTasksBySpace(spaceId))

    override suspend fun filterTasksForSelectionPage(
        spaceId: String,
        excludeTaskId: String?,
        searchQuery: String,
        offset: Int,
        limit: Int
    ): Page<Task> {
        val rows = dao.searchTasksForConnectionPaged(
            spaceId = spaceId,
            id = excludeTaskId,
            searchQuery = searchQuery,
            limit = limit,
            offset = offset,
        )
        return Page(
            items = hydrateTasks(spaceId, rows),
            offset = offset,
            totalCount = dao.countTasksForConnection(spaceId, excludeTaskId, searchQuery),
        )
    }

    /**
     * Search tasks for connection dialog with SQL filtering and cycle detection.
     * Filters by spaceId, excludes current task, optionally filters by search query,
     * and checks for cycles. Uses SQL indexes for efficient search on id and title fields.
     *
     * SQL cannot express the cycle check, so candidate rows are scanned in chunks and rejected
     * ones are simply skipped: only the ids that survive reach the window, and only that window is
     * loaded with its connections. The total is unknown until the whole table has been scanned,
     * which is why the returned page reports `totalCount == null`.
     *
     * @param spaceId The space to search in
     * @param excludeTaskId The current task ID to exclude
     * @param searchQuery Optional search query to filter by id or title (case-insensitive)
     * @param excludeTaskIds Additional task IDs to exclude (e.g., already connected tasks)
     * @param connectionType The type of connection being created (for cycle detection)
     * @param existingConnections Existing connections to check for cycles
     * @param offset Number of accepted tasks to skip
     * @param limit Maximum number of tasks to return
     * @return One window of tasks matching the criteria that won't create cycles
     */
    override suspend fun searchTasksForConnectionPage(
        spaceId: String,
        excludeTaskId: String?,
        searchQuery: String,
        excludeTaskIds: Set<String>,
        connectionType: ConnectionType,
        existingConnections: Set<TaskConnection>,
        offset: Int,
        limit: Int
    ): Page<Task> {
        val windowStart = offset.coerceAtLeast(0)
        val windowSize = limit.coerceAtLeast(0)
        // One extra accepted row tells us whether a further page exists without counting them all.
        val wanted = if (windowSize >= Int.MAX_VALUE - 1) Int.MAX_VALUE else windowSize + 1
        val scanSize = maxOf(windowSize, CANDIDATE_SCAN_SIZE)

        val acceptedRows = mutableListOf<Tasks>()
        var skipped = 0
        var scanOffset = 0
        while (acceptedRows.size < wanted) {
            val rows = dao.searchTasksForConnectionPaged(
                spaceId = spaceId,
                id = excludeTaskId,
                searchQuery = searchQuery,
                limit = scanSize,
                offset = scanOffset,
            )
            if (rows.isEmpty()) break
            scanOffset += rows.size

            for (row in rows) {
                if (row.id in excludeTaskIds) continue
                if (wouldCreateCycle(excludeTaskId, row.id, connectionType, existingConnections)) continue
                if (skipped < windowStart) {
                    skipped++
                    continue
                }
                acceptedRows.add(row)
                if (acceptedRows.size == wanted) break
            }

            if (rows.size < scanSize) break
        }

        val hasMore = acceptedRows.size > windowSize
        val windowRows = if (hasMore) acceptedRows.subList(0, windowSize) else acceptedRows
        return Page(
            items = hydrateTasks(spaceId, windowRows),
            offset = windowStart,
            totalCount = null,
            hasMore = hasMore,
        )
    }

    override suspend fun getAllSpacePrefixes(): List<String> =
        dao.getAllPrefixes()

    override suspend fun getAllTasksWithTotals(spaceId: String): List<TaskWithTotals> =
        calculateTotals(getAllTasks(spaceId), getBlockedTasks())

    override suspend fun getTaskById(id: String): Task? = getByIdUnsafe(id)

    private suspend fun getByIdUnsafe(id: String): Task? {
        val entity = dao.getTaskById(id) ?: return null
        return loadTaskWithConnections(entity)
    }

    override suspend fun getTasksByIdWithTotals(id: String): TaskWithTotals? {
        val task = getTaskById(id) ?: return null
        val blockedTasks = getBlockedTasks()
        val neededTaskIds = collectNeededTaskIds(task, blockedTasks)
        // Batch fetch all necessary tasks in a single query
        val tasksById = getTasksByIds(neededTaskIds).associateByToPersistentMap { it.id }.putting(task.id, task)
        return TaskWithTotals(
            task = task,
            totalDueDate = calculateTotalDueDate(task, blockedTasks, tasksById),
            totalPriority = calculateTotalPriority(task, blockedTasks, tasksById)
        )
    }

    override suspend fun getTasksByIds(ids: Set<String>): List<Task> {
        if (ids.isEmpty()) return emptyList()
        return hydrateTasksAcrossSpaces(byIds(ids))
    }

    /**
     * Runs an `IN (...)` [query] over [ids] in batches SQLite can bind.
     *
     * Nothing bounds these id sets — a dependency graph, a month of status changes, a space's
     * timelines — and past the parameter ceiling SQLite rejects the statement outright.
     */
    private suspend fun <T> chunkedByIds(
        ids: Collection<String>,
        query: suspend (List<String>) -> List<T>,
    ): List<T> = when {
        ids.isEmpty() -> emptyList()
        ids.size <= MAX_SQL_PARAMETERS -> query(ids.toList())
        else -> ids.chunked(MAX_SQL_PARAMETERS).flatMap { query(it) }
    }

    /** The task rows for [ids], read in bindable batches. */
    private suspend fun byIds(ids: Collection<String>): List<Tasks> =
        chunkedByIds(ids) { dao.getTasksByIds(it) }

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

    private suspend fun loadTaskWithConnections(entity: Tasks): Task = entity.toTask(
        dao.getConnectionsForTask(entity.id)
            .mapToPersistentSet { TaskConnection(it.targetTaskId, ConnectionType.valueOf(it.type)) }
    )

    private fun Tasks.toTask(connections: PersistentSet<TaskConnection>): Task = Task(
        id = id,
        title = title,
        description = description,
        status = status.toTaskStatus(),
        dueDate = dueDate?.let { Instant.fromEpochMilliseconds(it) },
        priority = priority?.let { Priority(it.toInt()) },
        estimatedTime = estimatedTimeJson.toRecurrencePeriodOrNull(),
        tags = tagsJson.toStringSet(),
        connections = connections,
        notifications = notificationsJson.toNotificationList(),
        spaceId = spaceId,
        recurrenceRules = recurrenceRulesJson.toRecurrenceRuleList(),
        autoUpdateStatusFromSubtasks = autoUpdateStatusFromSubtasks != 0L
    )

    /**
     * Load a set of rows of one space into tasks, fetching their connections in a single query.
     * This is what keeps the cost of a page proportional to the page size rather than to the number
     * of tasks the query matched.
     */
    private suspend fun hydrateTasks(spaceId: String, rows: List<Tasks>): List<Task> {
        if (rows.isEmpty()) return emptyList()
        val connectionsBySource = connectionsForTasksInSpace(spaceId, rows.map { it.id })
        return rows.map { row -> row.toTask(connectionsBySource[row.id] ?: persistentSetOf()) }
    }

    /** [hydrateTasks] for rows that need not share a space: blocked tasks, parents, link targets. */
    private suspend fun hydrateTasksAcrossSpaces(rows: List<Tasks>): List<Task> {
        if (rows.isEmpty()) return emptyList()
        val connectionsBySource = connectionsBySource(chunkedByIds(rows.map { it.id }) { dao.getConnectionsForTasks(it) })
        return rows.map { row -> row.toTask(connectionsBySource[row.id] ?: persistentSetOf()) }
    }

    /**
     * Connections of [ids], which must all belong to [spaceId]: past SQLite's bound-parameter
     * limit it is cheaper (and safer) to read the space's connections in one go than to chunk the
     * id list.
     */
    private suspend fun connectionsForTasksInSpace(
        spaceId: String,
        ids: List<String>,
    ): Map<String, PersistentSet<TaskConnection>> {
        val rows = if (ids.size <= MAX_SQL_PARAMETERS) {
            dao.getConnectionsForTasks(ids)
        } else {
            dao.getConnectionsBySpace(spaceId)
        }
        return connectionsBySource(rows)
    }

    private fun connectionsBySource(rows: List<TaskConnections>): Map<String, PersistentSet<TaskConnection>> =
        rows.groupBy { it.sourceTaskId }
            .mapValues { (_, connections) ->
                connections.mapToPersistentSet { TaskConnection(it.targetTaskId, ConnectionType.valueOf(it.type)) }
            }

    /** The totals view of a row; see [TotalsNode]. */
    private fun Tasks.toTotalsNode(dependentIds: List<String>): TotalsNode = TotalsNode(
        id = id,
        dueDate = dueDate?.let { Instant.fromEpochMilliseconds(it) },
        priority = priority?.let { Priority(it.toInt()) },
        dependentIds = dependentIds,
        // Only blocked rows carry blocker ids, and only those need their status parsed.
        blockerIds = if (isBlocked != 0L) {
            (status.toTaskStatus() as? TaskStatus.Blocked)?.blockerTaskIds.orEmpty()
        } else {
            emptySet()
        },
    )

    override suspend fun getAllTags(spaceId: String): PersistentSet<String> =
        dao.getAllTagsForSpace(spaceId).toPersistentSet()

    override suspend fun filterTagsPage(
        spaceId: String,
        searchQuery: String,
        excludeTags: Set<String>,
        offset: Int,
        limit: Int
    ): Page<String> = Page(
        items = dao.filterTagsForSpacePaged(
            spaceId = spaceId,
            searchQuery = searchQuery,
            excludeTags = excludeTags,
            limit = limit,
            offset = offset,
        ),
        offset = offset,
        totalCount = dao.countFilteredTagsForSpace(spaceId, searchQuery, excludeTags),
    )

    override suspend fun addTag(spaceId: String, tag: String): Boolean = mutex.withLock {
        val name = tag.trim()
        if (name.isBlank()) return@withLock false
        // Checked rather than relying on INSERT OR IGNORE, which cannot say whether it inserted.
        if (dao.tagExists(spaceId, name)) return@withLock false
        dao.insertTagForSpace(spaceId, name)
        notifyChanged()
        true
    }

    override suspend fun deleteTag(spaceId: String, tag: String): Boolean = mutex.withLock {
        val name = tag.trim()
        if (name.isBlank()) return@withLock false
        val removed = dao.deleteTagForSpace(spaceId, name) > 0
        if (removed) notifyChanged()
        removed
    }

    override suspend fun peekNextId(spaceId: String): String {
        val space = dao.getSpaceById(spaceId) ?: return "TASK-1"
        val nextNum = dao.getNextId(spaceId) ?: 1
        return "${space.idPrefix}-$nextNum"
    }

    private suspend fun generateNextIdUnsafe(spaceId: String): String {
        val space = dao.getSpaceById(spaceId) ?: return "TASK-1"
        val nextNum = dao.getNextId(spaceId) ?: 1
        dao.setNextId(spaceId, nextNum + 1)
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
        if (dao.getSpaceById(spaceId) == null) return@withLock null
        addTaskUnsafe(
            spaceId, title, description, status, dueDate, priority, estimatedTime,
            tags, connections, notifications, customId, recurrenceRules, autoUpdateStatusFromSubtasks
        )?.also { notifyChanged() }
    }

    private suspend fun syncToDatabase(task: Task) {
        dao.updateTask(
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

        val removedConnections = oldTask.connections.removingAll(task.connections)
        removedConnections.forEach { connection ->
            dao.deleteConnection(task.id, connection.targetTaskId, connection.type.name)
            dao.deleteConnection(connection.targetTaskId, task.id, connection.type.symmetric.name)
        }

        val addedConnections = task.connections.removingAll(oldTask.connections)
        addedConnections.forEach { connection ->
            dao.insertConnection(task.id, connection.targetTaskId, connection.type.name)
            addSymmetricConnectionUnsafe(task.id, connection)
        }

        val (finalTask, automaticReason) = calculateFinalTaskStatusOnUpdate(task.id, task, oldTask)
        handleStatusChangeOnUpdate(finalTask, oldTask, automaticReason)

        syncToDatabase(finalTask)

        val newTags = finalTask.tags.removingAll(oldTask.tags)
        newTags.forEach { dao.insertTagForSpace(finalTask.spaceId, it) }

        // Update task_tags junction table
        if (finalTask.tags != oldTask.tags) {
            dao.deleteTaskTags(finalTask.id)
            finalTask.tags.forEach { dao.insertTaskTag(finalTask.id, it) }
        }

        handleStatusCascadeOnUpdate(finalTask.id, oldTask.status, finalTask.status)
        notifyChanged()

        finalTask
    }

    override suspend fun recordStatusChange(
        taskId: String,
        previousStatus: TaskStatus?,
        newStatus: TaskStatus,
        automaticChangeReason: AutomaticChangeReason?
    ) {
        dao.insertStatusChange(
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
        hydrateTasksAcrossSpaces(dao.getBlockedTasks())

    private suspend fun addSymmetricConnectionUnsafe(sourceTaskId: String, connection: TaskConnection) {
        val targetTask = getByIdUnsafe(connection.targetTaskId) ?: return
        val symmetricConnection = TaskConnection(sourceTaskId, connection.type.symmetric)

        if (!targetTask.connections.contains(symmetricConnection)) {
            dao.insertConnection(connection.targetTaskId, sourceTaskId, connection.type.symmetric.name)

            if (symmetricConnection.type == ConnectionType.ParentOf) {
                updateParentStatusIfNeeded(connection.targetTaskId)
            }
        }
    }

    override suspend fun addConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean = mutex.withLock {
        addConnectionUnsafe(fromTaskId, toTaskId, type).also { added -> if (added) notifyChanged() }
    }

    override suspend fun removeConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean = mutex.withLock {
        val fromTask = getByIdUnsafe(fromTaskId) ?: return@withLock false
        val connection = TaskConnection(toTaskId, type)
        if (!fromTask.connections.contains(connection)) return@withLock true

        dao.deleteConnection(fromTaskId, toTaskId, type.name)
        dao.deleteConnection(toTaskId, fromTaskId, type.symmetric.name)

        if (type == ConnectionType.SubtaskOf) {
            updateParentStatusIfNeeded(toTaskId)
        }
        notifyChanged()

        true
    }

    override suspend fun getConnectionsForTaskSync(taskId: String): PersistentSet<TaskConnection>? {
        val connections = dao.getConnectionsForTask(taskId)
        return if (connections.isEmpty() && dao.getTaskById(taskId) == null) {
            null
        } else {
            connections.mapToPersistentSet { TaskConnection(it.targetTaskId, ConnectionType.valueOf(it.type)) }
        }
    }

    override suspend fun getParentTasks(taskId: String): List<Task> =
        hydrateTasksAcrossSpaces(dao.getParentTasks(taskId))

    override suspend fun getSubtasks(taskId: String): List<Task> =
        hydrateTasksAcrossSpaces(dao.getSubtasks(taskId))

    override suspend fun deleteTask(id: String): Boolean = mutex.withLock {
        val task = getByIdUnsafe(id) ?: return@withLock false

        task.connections.forEach { connection ->
            dao.deleteConnection(connection.targetTaskId, id, connection.type.symmetric.name)
        }

        handleBlockerDeleted(id)

        val parentTasks = getParentTasks(id)

        dao.deleteTask(id)

        // Update parent tasks' statuses after subtask deletion
        updateParentStatuses(parentTasks)
        notifyChanged()

        true
    }

    override suspend fun getStatusTimeline(taskId: String): List<StatusChange> =
        dao.getStatusTimeline(taskId).map {
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

        val statusChanges = dao.getStatusChangesBySpaceAndDateRange(
            spaceId,
            monthStart.toEpochMilliseconds(),
            monthEnd.toEpochMilliseconds()
        )

        // Only the tasks the month's changes actually refer to, loaded in two queries
        val taskIds = statusChanges.mapTo(mutableSetOf()) { it.taskId }
        val tasksById = hydrateTasks(spaceId, byIds(taskIds)).associateBy { it.id }

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

    /**
     * Unlike the grouped queries, [TaskFilterCriteria] here is matched in Kotlin against whole
     * tasks (descriptions, tags, recurrence rules, connections), so the set has to be built before
     * a window can be cut. The task list itself pages through [getTasksForGroupPage]; this stays
     * for callers that want the criteria applied verbatim.
     */
    override suspend fun getAllWithTotalsFilteredPage(
        spaceId: String,
        criteria: TaskFilterCriteria,
        offset: Int,
        limit: Int
    ): Page<TaskWithTotals> =
        filterTasksWithCriteria(getAllTasksWithTotals(spaceId), criteria).toPage(offset, limit)

    override suspend fun countAllWithTotalsFiltered(spaceId: String, criteria: TaskFilterCriteria): Int =
        filterTasksWithCriteria(getAllTasksWithTotals(spaceId), criteria).size

    override suspend fun getFilterState(spaceId: String): TaskFilterCriteria =
        dao.getFilterState(spaceId)?.toTaskFilterCriteria() ?: TaskFilterCriteria()

    override suspend fun saveFilterState(spaceId: String, criteria: TaskFilterCriteria) {
        dao.setFilterState(spaceId, criteria.toJson())
    }

    override suspend fun getViewMode(spaceId: String): String =
        dao.getViewMode(spaceId) ?: "Priority"

    override suspend fun saveViewMode(spaceId: String, viewMode: String) {
        dao.setViewMode(spaceId, viewMode)
    }

    override suspend fun getFilterPanelOpen(spaceId: String): Boolean =
        dao.getFilterPanelState(spaceId) == 1L

    override suspend fun saveFilterPanelOpen(spaceId: String, isOpen: Boolean) {
        dao.setFilterPanelState(spaceId, if (isOpen) 1 else 0)
    }

    // ============ View mode management ============

    override suspend fun getAllViewModes(spaceId: String): List<ViewMode> {
        val builtIn = ViewMode.getBuiltInModes(spaceId)
        val custom = dao.getAllCustomViewModes(spaceId).map { row ->
            row.configJson.toViewMode(spaceId, row.id, row.name)
        }
        return builtIn.toPersistentList().addingAll(custom)
    }

    override suspend fun getViewModeById(spaceId: String, viewModeId: String): ViewMode? {
        // Check built-in modes first
        ViewMode.getBuiltInModes(spaceId).find { it.id == viewModeId }?.let { return it }
        // Check custom modes
        return dao.getCustomViewModeById(spaceId, viewModeId)?.let { row ->
            row.configJson.toViewMode(spaceId, row.id, row.name)
        }
    }

    override suspend fun saveViewMode(viewMode: ViewMode): ViewMode {
        require(!viewMode.isBuiltIn) { "Cannot modify built-in view modes" }
        dao.insertOrUpdateCustomViewMode(
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
        if (dao.getCustomViewModeById(spaceId, viewModeId) == null) {
            return@withLock false
        }
        dao.deleteCustomViewMode(spaceId, viewModeId)
        // If this was the active mode, reset to default
        val activeId = dao.getActiveViewModeId(spaceId)
        if (activeId == viewModeId) {
            dao.setActiveViewModeId(spaceId, "priority")
        }
        true
    }

    override suspend fun getActiveViewMode(spaceId: String): ViewMode {
        val activeId = dao.getActiveViewModeId(spaceId) ?: "priority"
        return getViewModeById(spaceId, activeId) ?: ViewMode.priority(spaceId)
    }

    override suspend fun setActiveViewMode(spaceId: String, viewModeId: String) {
        dao.setActiveViewModeId(spaceId, viewModeId)
    }

    override suspend fun exportSpaceToJson(spaceId: String, prettyPrint: Boolean): String? {
        val space = getSpaceById(spaceId) ?: return null
        val spaceTasks = hydrateTasks(spaceId, dao.getTasksBySpace(spaceId))
        val taskIds = spaceTasks.map { it.id }.toSet()
        // Batch fetch all status timelines in a single query
        val spaceTimelines = if (taskIds.isEmpty()) {
            emptyMap()
        } else {
            chunkedByIds(taskIds) { dao.getStatusTimelinesForTasks(it) }
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
        val nextId = dao.getNextId(spaceId)?.toInt() ?: 1

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
            val newPrefix = uniqueSpacePrefix(exportData.space.idPrefix) { dao.prefixExists(it) }
            val newSpaceId = "space-${dao.countSpaces()}-$newPrefix"
            val newSpace = Space(id = newSpaceId, name = exportData.space.name, idPrefix = newPrefix)

            dao.insertSpace(newSpaceId, newSpace.name, newPrefix)
            dao.setNextId(newSpaceId, exportData.nextId.toLong())

            val oldToNewTaskId = createTaskIdMapping(exportData.tasks, newPrefix)

            exportData.tasks.forEach { task ->
                val newTaskId = oldToNewTaskId[task.id] ?: return@forEach

                val remappedStatus = remapBlockedStatus(task.status, oldToNewTaskId)
                val timeline = exportData.statusTimelines[task.id].orEmpty()

                addTaskUnsafe(
                    spaceId = newSpaceId,
                    title = task.title,
                    description = task.description,
                    status = remappedStatus,
                    dueDate = task.dueDate,
                    priority = task.priority,
                    estimatedTime = task.estimatedTime,
                    tags = task.tags,
                    connections = persistentSetOf(),
                    notifications = task.notifications,
                    customId = newTaskId,
                    recurrenceRules = task.recurrenceRules,
                    autoUpdateStatusFromSubtasks = task.autoUpdateStatusFromSubtasks,
                    // Its own creation entry would date the task to the moment of the import.
                    recordInitialStatusChange = timeline.isEmpty(),
                )

                timeline.forEach { statusChange ->
                    dao.insertStatusChange(
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

            exportData.tags.forEach { dao.insertTagForSpace(newSpaceId, it) }
            notifyChanged()

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
        tags: PersistentSet<String>,
        connections: PersistentSet<TaskConnection>,
        notifications: PersistentList<TaskNotification>,
        customId: String?,
        recurrenceRules: PersistentList<Pair<RecurrenceRule, RecurrenceState>>,
        autoUpdateStatusFromSubtasks: Boolean,
        /** Import writes the task's real history instead; see [importSpaceFromJson]. */
        recordInitialStatusChange: Boolean = true,
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

        dao.insertTask(
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
            dao.insertConnection(taskId, connection.targetTaskId, connection.type.name)
            addSymmetricConnectionUnsafe(taskId, connection)
        }

        if (recordInitialStatusChange) {
            dao.insertStatusChange(
                taskId = taskId,
                timestamp = clock.now().toEpochMilliseconds(),
                previousStatusJson = null,
                newStatusJson = status.toJson(),
                automaticChangeReasonJson = null
            )
        }

        tags.forEach { dao.insertTagForSpace(spaceId, it) }

        // Populate task_tags junction table
        tags.forEach { dao.insertTaskTag(taskId, it) }

        return task
    }

    private suspend fun addConnectionUnsafe(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean {
        val fromTask = getByIdUnsafe(fromTaskId) ?: return false
        if (getByIdUnsafe(toTaskId) == null) return false

        val connection = TaskConnection(toTaskId, type)
        if (fromTask.connections.contains(connection)) return true

        if (wouldCreateCycle(fromTaskId, toTaskId, type)) return false

        dao.insertConnection(fromTaskId, toTaskId, type.name)
        addSymmetricConnectionUnsafe(fromTaskId, connection)

        return true
    }

    override suspend fun clearAllData() {
        dao.getAllSpaces().forEach { space ->
            dao.deleteSpace(space.id)
        }
        notifyChanged()
    }

    override suspend fun getRecurringTasksDueBefore(time: Instant): List<Task> =
        hydrateTasksAcrossSpaces(dao.getRecurringTasksDueBefore(time.toEpochMilliseconds()))

    // Override methods from AbstractTaskRepository that do read-modify-write to add mutex protection.
    // Note: processDateBasedRecurrences calls processRecurrenceTrigger internally, so we only
    // protect processDateBasedRecurrences with mutex (not both, to avoid deadlock with non-reentrant mutex).
    // processRecurrenceTrigger is also protected independently for when it's called directly.

    override suspend fun processRecurrenceTrigger(
        taskId: String,
        triggerEvent: RecurrenceTriggerEvent
    ): Task? = mutex.withLock {
        processRecurrenceTriggerInternal(taskId, triggerEvent)?.also { notifyChanged() }
    }

    override suspend fun processDateBasedRecurrences(currentTime: Instant): List<Task> = mutex.withLock {
        processDateBasedRecurrencesInternal(currentTime).also { if (it.isNotEmpty()) notifyChanged() }
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
        val estimatedTimeBounds = filterCriteria.estimatedTimeFilter.bucketSeconds
            ?: customEstimatedTimeBounds(filterCriteria)

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
            // 0 = any, 1 = no estimate, 2 = bucket [min, max), 3 = custom [min, max].
            estimatedTimeFilterType = when (filterCriteria.estimatedTimeFilter) {
                EstimatedTimeFilter.Any -> 0L
                EstimatedTimeFilter.NoEstimate -> 1L
                EstimatedTimeFilter.Custom -> 3L
                else -> 2L
            },
            estimatedTimeMinSeconds = estimatedTimeBounds.first,
            estimatedTimeMaxSeconds = estimatedTimeBounds.second,
            // SQL only separates recurring from non-recurring; see `recurrenceFilter` below.
            recurrenceFilterType = when (filterCriteria.recurrenceFilter) {
                RecurrenceFilter.Any -> 0L
                RecurrenceFilter.NoRecurrence -> 1L
                else -> 2L
            },
            recurrenceFilter = filterCriteria.recurrenceFilter,
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
     * Get task groups at a specific grouping level using SQL dao.
     * Uses indexed queries to count tasks efficiently without loading all task data.
     */
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
        parentFilters: PersistentList<GroupFilter>,
        filterParams: FilterParams
    ): List<TaskGroupInfo> {
        val result = mutableListOf<TaskGroupInfo>()

        // For each group definition, count matching tasks using SQL
        for (group in level.groups) {
            val groupFilter = group.toFilter(level.field)
            val combinedFilters = parentFilters.adding(groupFilter)

            // Counting stops at the row level: headers never need the tasks themselves.
            val tasks = queryCandidateRows(spaceId, combinedFilters, filterParams)

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
            val allGroupFilters = level.groups.mapToPersistentList { it.toFilter(level.field) }
            val uncategorizedFilter = GroupFilter.Not(
                field = level.field,
                filters = allGroupFilters
            )

            // Get uncategorized tasks via SQL (Not filter is handled in queryCandidateRows)
            val uncategorizedTasks = queryCandidateRows(
                spaceId,
                parentFilters.adding(uncategorizedFilter),
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
            val allTasks = queryCandidateRows(spaceId, parentFilters, filterParams)
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
     * Rows matching a group's filters, straight from SQL.
     *
     * Deliberately stops at the row level: counting a group, ordering a result set and loading the
     * page the user is looking at all start here, and only the last of those needs connections or
     * parsed JSON.
     */
    private suspend fun queryCandidateRows(
        spaceId: String,
        groupFilters: List<GroupFilter>,
        filterParams: FilterParams
    ): List<Tasks> {
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
        var rows = dao.getTasksFilteredWithGroupFilter(
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
            estimatedTimeMinSeconds = filterParams.estimatedTimeMinSeconds,
            estimatedTimeMaxSeconds = filterParams.estimatedTimeMaxSeconds,
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
        )

        // The kind of rule is only visible once the rules are decoded; see RecurrenceFilter.matches.
        if (filterParams.recurrenceFilter.bucketedInSqlOnly) {
            rows = rows.filter { filterParams.recurrenceFilter.matches(it.recurrenceRulesJson.toRecurrenceRuleList()) }
        }

        // Handle HasTags filters (from group filters) - get matching task IDs via SQL
        for (tagsFilter in hasTagsFilters) {
            rows = filterRowsByTagsAny(spaceId, rows, tagsFilter.tags)
        }

        // Handle Not filters - compute excluded task IDs
        for (notFilter in notFilters) {
            // For each inner filter, get matching tasks and exclude them
            for (innerFilter in notFilter.filters) {
                val innerTaskIds = queryCandidateRows(spaceId, listOf(innerFilter), filterParams)
                    .mapTo(mutableSetOf()) { it.id }
                rows = rows.filter { it.id !in innerTaskIds }
            }
        }

        // Handle selectedTags from TaskFilterCriteria
        if (filterParams.selectedTags.isNotEmpty()) {
            rows = when (filterParams.tagMatchMode) {
                TagMatchMode.Any -> filterRowsByTagsAny(spaceId, rows, filterParams.selectedTags)
                TagMatchMode.All -> rows.filter { row ->
                    val tags = row.tagsJson.toStringSet()
                    filterParams.selectedTags.all { it in tags }
                }
            }
        }

        return rows
    }

    /**
     * Filter rows to only those having at least one of the specified tags.
     * Uses SQL with normalized task_tags junction table.
     */
    private suspend fun filterRowsByTagsAny(
        spaceId: String,
        rows: List<Tasks>,
        tags: Set<String>
    ): List<Tasks> {
        if (tags.isEmpty()) return emptyList()
        val matchingIds = dao.getTaskIdsByTags(
            spaceId = spaceId,
            tags = tags
        ).toSet()
        return rows.filter { it.id in matchingIds }
    }

    /** A candidate row with the totals it will be ordered and displayed by. */
    private data class OrderedCandidate(
        val row: Tasks,
        val totals: TaskTotals,
        /** Ordering values, precomputed for the fields the current rules mention. */
        val orderValues: Map<OrderableField, Comparable<*>?>,
    )

    /**
     * Put candidate rows in the order the view mode asks for.
     *
     * The ordering rules can reference totals, which are derived from the dependency graph rather
     * than stored, so the whole matching set has to be ranked before a window can be cut. Doing it
     * on rows keeps that phase to three queries no matter how many tasks match.
     */
    private suspend fun orderCandidates(
        spaceId: String,
        rows: List<Tasks>,
        orderingRules: List<OrderingRule>
    ): List<OrderedCandidate> {
        if (rows.isEmpty()) return emptyList()

        val totals = calculateCandidateTotals(spaceId, rows)
        val orderedFields = orderingRules.map { it.field }.distinct()
        val candidates = rows.map { row ->
            val rowTotals = totals[row.id] ?: TaskTotals(null, null)
            OrderedCandidate(
                row = row,
                totals = rowTotals,
                orderValues = orderedFields.associateWith { field -> orderableValue(row, rowTotals, field) },
            )
        }

        if (orderingRules.isEmpty()) return candidates
        return candidates.sortedWith(
            createComparator(orderingRules) { candidate: OrderedCandidate, field -> candidate.orderValues[field] }
        )
    }

    /**
     * Totals for every candidate, computed from rows plus two connection queries instead of the
     * per-task lookups a fully loaded task list would need.
     *
     * Like the task-level calculation, dependents are only followed within the candidate set, and
     * blocked tasks are considered across all spaces.
     */
    private suspend fun calculateCandidateTotals(
        spaceId: String,
        rows: List<Tasks>
    ): Map<String, TaskTotals> {
        val dependentType = ConnectionType.IsDependencyOf.name

        val dependentsBySource = dao.getConnectionsBySpaceAndType(spaceId, dependentType)
            .groupBy({ it.sourceTaskId }, { it.targetTaskId })
        val nodes = rows.map { it.toTotalsNode(dependentsBySource[it.id].orEmpty()) }

        val blockedDependentsBySource = dao.getConnectionsForBlockedTasks(dependentType)
            .groupBy({ it.sourceTaskId }, { it.targetTaskId })
        val blockedNodes = dao.getBlockedTasks().map { it.toTotalsNode(blockedDependentsBySource[it.id].orEmpty()) }

        return calculateTotals(nodes, blockedNodes)
    }

    /** [getOrderableValue] for a row that has not been loaded into a [Task] yet. */
    private fun orderableValue(row: Tasks, totals: TaskTotals, field: OrderableField): Comparable<*>? = when (field) {
        OrderableField.Id -> taskIdOrderValue(row.id)
        OrderableField.Title -> row.title
        OrderableField.Status -> row.status.toTaskStatus().orderRank()
        OrderableField.Priority -> row.priority?.toInt()
        OrderableField.TotalPriority -> totals.totalPriority?.value
        OrderableField.DueDate -> row.dueDate
        OrderableField.TotalDueDate -> totals.totalDueDate?.toEpochMilliseconds()
        OrderableField.EstimatedTime -> row.estimatedTimeJson.toRecurrencePeriodOrNull()?.toApproximateSeconds()
    }

    /**
     * Get task groups with in-memory processing for complex filters.
     */
    private suspend fun getTaskGroupsWithInMemoryProcessing(
        spaceId: String,
        level: GroupingLevel,
        parentFilters: PersistentList<GroupFilter>,
        filterCriteria: TaskFilterCriteria
    ): List<TaskGroupInfo> {
        // Get all tasks with totals filtered by criteria
        val today = today()
        val allTasks = getAllWithTotalsFiltered(spaceId, filterCriteria)

        // Apply parent filters
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

        // Add uncategorized group
        val uncategorizedTasks = filteredTasks.filter { it.task.id !in matchedTaskIds }
        if (uncategorizedTasks.isNotEmpty()) {
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

        return result
    }

    /**
     * Get one page of a group's tasks using SQL-optimized dao.
     *
     * Filtering and ordering cover every matching task — that is what makes the window meaningful —
     * but only the window itself is loaded with connections and decoded JSON.
     */
    override suspend fun getTasksForGroupPage(
        spaceId: String,
        filters: PersistentList<GroupFilter>,
        orderingRules: PersistentList<OrderingRule>,
        filterCriteria: TaskFilterCriteria,
        offset: Int,
        limit: Int
    ): Page<TaskWithTotals> = mutex.withLock {
        val ordered = orderedCandidatesFor(spaceId, filters, orderingRules, filterCriteria)

        val from = offset.coerceIn(0, ordered.ids.size)
        val to = (from.toLong() + limit.coerceAtLeast(0)).coerceAtMost(ordered.ids.size.toLong()).toInt()
        val windowIds = ordered.ids.subList(from, to)

        // Only the window is read back as rows; chunked because the whole-set window (limit =
        // UNLIMITED) would otherwise blow past SQLite's bound-parameter limit.
        val rowsById = byIds(windowIds).associateBy { it.id }
        val windowRows = windowIds.mapNotNull { rowsById[it] }

        val tasks = hydrateTasks(spaceId, windowRows)
        Page(
            items = tasks.map { task ->
                val totals = ordered.totals[task.id]
                TaskWithTotals(
                    task = task,
                    totalDueDate = totals?.totalDueDate,
                    totalPriority = totals?.totalPriority,
                )
            },
            offset = from,
            totalCount = ordered.ids.size,
        )
    }

    override suspend fun countTasksForGroup(
        spaceId: String,
        filters: PersistentList<GroupFilter>,
        filterCriteria: TaskFilterCriteria
    ): Int = mutex.withLock {
        evictStaleCaches()
        val key = CandidateKey(spaceId, filters, filterCriteria)
        countCache[key] ?: queryCandidateRows(spaceId, filters, buildFilterParams(filterCriteria)).size
            .also { countCache.putCapped(key, it) }
    }

    /**
     * Data class to hold filter parameters for SQL dao.
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
        val estimatedTimeMinSeconds: Long? = null,
        val estimatedTimeMaxSeconds: Long? = null,
        val recurrenceFilterType: Long = 0,
        /** Applied in Kotlin: SQL cannot tell one kind of recurrence rule from another. */
        val recurrenceFilter: RecurrenceFilter = RecurrenceFilter.Any,
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
        val selectedTags: PersistentSet<String> = persistentSetOf(),
        val tagMatchMode: TagMatchMode = TagMatchMode.Any
    )

    /**
     * Data class to hold group filter parameters for SQL dao.
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
        val tagFilterTaskIds: PersistentSet<String>? = null,
        // For Not filter - task IDs to exclude (computed externally)
        val excludeTaskIds: PersistentSet<String>? = null
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
                GroupFilterParams(tagFilterTaskIds = persistentSetOf()) // Will be filled in later
            }
            is GroupFilter.Not -> {
                // For Not filter, we need to compute excluded task IDs
                GroupFilterParams(excludeTaskIds = persistentSetOf()) // Will be filled in later
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
                    p.excludeTaskIds != null && result.excludeTaskIds != null -> p.excludeTaskIds.addingAll(result.excludeTaskIds)
                    p.excludeTaskIds != null -> p.excludeTaskIds
                    else -> result.excludeTaskIds
                }
            )
        }
        return result
    }

    // ============ Saved filter management ============

    override suspend fun getAllSavedFilters(spaceId: String): List<SavedFilter> =
        dao.getAllSavedFilters(spaceId).map { it.toSavedFilterModel() }

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
        dao.getSavedFilterById(spaceId, filterId)?.toSavedFilterModel()

    override suspend fun saveSavedFilter(filter: SavedFilter): SavedFilter {
        dao.insertOrUpdateSavedFilter(
            id = filter.id,
            spaceId = filter.spaceId,
            name = filter.name,
            criteriaJson = filter.criteria.toJson(),
            viewModeId = filter.viewModeId
        )
        return filter
    }

    override suspend fun deleteSavedFilter(spaceId: String, filterId: String): Boolean = mutex.withLock {
        val exists = dao.getSavedFilterById(spaceId, filterId) != null
        if (exists) {
            dao.deleteSavedFilter(spaceId, filterId)
        }
        exists
    }
}

private fun SavedFilters.toSavedFilterModel() = SavedFilter(
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
