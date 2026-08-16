@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.withWriteTransaction
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
 * Bound for a boolean group filter that two levels disagree on. The SQL compares equality
 * against 0 or 1, so any other value matches no row -- which is the correct intersection.
 */
private const val IMPOSSIBLE_FLAG = 2L

/**
 * Stands in for "no bound" at either end of a range the query states as a plain conjunction.
 *
 * Not Long.MIN_VALUE/MAX_VALUE: on Kotlin/JS a Long is bound to SQLite through a JavaScript
 * number, which is a double, so anything past 2^53 does not survive the trip and the comparison it
 * lands in stops matching. That is 2^53 - 1, exactly representable, and far outside any due date in
 * milliseconds or estimate in seconds this app will ever hold.
 */
private const val OPEN_BOUND = 9_007_199_254_740_991L

/** A range-filter kind outside the 0..3 the query knows, so no row can satisfy it. */
private const val IMPOSSIBLE_RANGE_TYPE = 4L

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
    private var cachedDay: LocalDate? = null

    override fun onDataChanged() {
        dataVersion++
    }

    /**
     * Announces a change once the transaction that made it has committed.
     *
     * Signalling from inside the transaction let a collector re-query and read the state from
     * before the commit, with no second signal to correct it — and a rollback announced a change
     * that never happened.
     */
    private inline fun <T> T.alsoNotifyIf(changed: (T) -> Boolean): T = also { if (changed(it)) notifyChanged() }

    /**
     * Drop memoised rankings if anything has changed since they were computed. Call under [mutex].
     *
     * The day counts as a change. "Due today", "this week" and "this month" are resolved against
     * the calendar day the ranking was built on, and an app left open over midnight would
     * otherwise keep answering with yesterday's groups until something was edited.
     *
     * Within a day the bounds are held fixed on purpose, so every page of one scroll comes from a
     * single ranking. Overdue is the one filter that genuinely moves by the second; it is pinned
     * to the instant the ranking was built rather than re-read per page.
     */
    private fun evictStaleCaches() {
        val current = dataVersion
        val day = today()
        if (cachedVersion != current || cachedDay != day) {
            orderedCache.clear()
            countCache.clear()
            cachedVersion = current
            cachedDay = day
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

    override suspend fun getAllSpaces(): List<Space> =
        dao.getAllSpaces().map { it.toModel() }

    override suspend fun getSpaceById(id: String): Space? =
        dao.getSpaceById(id)?.toModel()

    override suspend fun getSpaceIdForTask(taskId: String): String? =
        dao.getSpaceIdForTask(taskId)

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
        // Matched here, not in SQL: SQLite's `LOWER` folds only A–Z, so a lower-case query found
        // nothing in any alphabet but English. There are few enough spaces to read them all.
        return dao.getAllSpacesPaged(limit = Int.MAX_VALUE, offset = 0)
            .map { it.toModel() }
            .filter { space ->
                searchInName && space.name.contains(query, ignoreCase = true) ||
                        searchInPrefix && space.idPrefix.contains(query, ignoreCase = true)
            }
            .toPage(offset, limit)
    }

    override suspend fun createSpace(name: String, idPrefix: String): Space? = mutex.withLock {
        if (!idPrefix.matches(Regex("^[A-Z]+$")) || idPrefix.isEmpty()) return@withLock null
        if (dao.prefixExists(idPrefix)) return@withLock null

        val spaceId = "space-${dao.countSpaces()}-${idPrefix}"
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
        // Detaching other spaces' tasks has to commit with the deletion itself.
        database.withWriteTransaction {
            if (dao.getSpaceById(spaceId) == null) return@withWriteTransaction false

            val taskIdsInSpace = dao.getTasksBySpace(spaceId).map { it.id }.toSet()

            handleCrossSpaceRelationshipsOnSpaceDeletion(
                taskIdsInSpace,
                getAllSpaces().flatMap { getTasksInSpace(it.id) },
            )

            dao.deleteSpace(spaceId)
            true
        }.alsoNotifyIf { it }
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

    /**
     * Whether a picker row answers [query], by id or by title.
     *
     * Judged here rather than in SQL. SQLite's own `LOWER` folds nothing but A–Z, so a query typed
     * in lower case found nothing in any alphabet but English — while the in-memory repository,
     * which uses Kotlin's `contains(ignoreCase = true)`, matched it. The main filter box was moved
     * off SQL for the same reason.
     */
    private fun Tasks.matchesPickerQuery(query: String): Boolean =
        query.isBlank() || id.contains(query, ignoreCase = true) || title.contains(query, ignoreCase = true)

    /**
     * One window of picker rows, and whether more follow.
     *
     * Rows are read in chunks and offered to [accept]; only those it takes count towards the
     * window, so the total is not known without scanning the whole space — which is why callers
     * report `totalCount == null` and lean on `hasMore`.
     */
    private suspend fun scanPickerRows(
        spaceId: String,
        excludeTaskId: String?,
        offset: Int,
        limit: Int,
        accept: suspend (Tasks) -> Boolean,
    ): Pair<List<Tasks>, Boolean> {
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
                searchQuery = "",
                limit = scanSize,
                offset = scanOffset,
            )
            if (rows.isEmpty()) break
            scanOffset += rows.size

            for (row in rows) {
                if (!accept(row)) continue
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
        return (if (hasMore) acceptedRows.subList(0, windowSize) else acceptedRows) to hasMore
    }

    override suspend fun filterTasksForSelectionPage(
        spaceId: String,
        excludeTaskId: String?,
        searchQuery: String,
        offset: Int,
        limit: Int
    ): Page<Task> {
        // Nothing typed: the window is a plain slice, and the total comes for free.
        if (searchQuery.isBlank()) {
            val rows = dao.searchTasksForConnectionPaged(
                spaceId = spaceId,
                id = excludeTaskId,
                searchQuery = "",
                limit = limit,
                offset = offset,
            )
            return Page(
                items = hydrateTasks(spaceId, rows),
                offset = offset,
                totalCount = dao.countTasksForConnection(spaceId, excludeTaskId, ""),
            )
        }

        // Matching in Kotlin costs a scan of the space's rows, but this picker promises a total,
        // and only the window that is actually shown is loaded with its connections.
        val window = dao.searchTasksForConnectionPaged(
            spaceId = spaceId,
            id = excludeTaskId,
            searchQuery = "",
            limit = Int.MAX_VALUE,
            offset = 0,
        ).filter { it.matchesPickerQuery(searchQuery) }.toPage(offset, limit)

        return Page(
            items = hydrateTasks(spaceId, window.items),
            offset = window.offset,
            totalCount = window.totalCount,
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
        // Cheapest test first: the search rejects most rows without touching the database again.
        val (windowRows, hasMore) = scanPickerRows(spaceId, excludeTaskId, offset, limit) { row ->
            row.matchesPickerQuery(searchQuery) &&
                    row.id !in excludeTaskIds &&
                    !wouldCreateCycle(excludeTaskId, row.id, connectionType, existingConnections)
        }
        return Page(
            items = hydrateTasks(spaceId, windowRows),
            offset = offset.coerceAtLeast(0),
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
        val totals = taskTotals(task, blockedTasks, tasksById)
        return TaskWithTotals(
            task = task,
            totalDueDate = totals.totalDueDate,
            totalPriority = totals.totalPriority,
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

        // A level at a time, reading the whole frontier's connections in one query. Walking node
        // by node with getTaskById cost two queries each — and the tasks it read were thrown away,
        // since only the ids are wanted here and the rows are batch-read afterwards anyway.
        val needed = mutableSetOf<String>()
        var frontier = setOf(task.id)

        while (frontier.isNotEmpty()) {
            needed.addAll(frontier)

            val dependents = chunkedByIds(frontier) { dao.getConnectionsForTasks(it) }
                .filter { it.type == ConnectionType.IsDependencyOf.name }
                .map { it.targetTaskId }
            val blocked = frontier.flatMap { id -> blockerToBlockedTasks[id].orEmpty().map { it.id } }

            frontier = (dependents + blocked).filterTo(mutableSetOf()) { it !in needed }
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
    ): Page<String> {
        // Same as the space and task pickers: the match is Kotlin's, because SQLite's `LOWER`
        // folds only A–Z. A space's tag vocabulary is small enough to read in one go.
        val available = dao.getAllTagsForSpace(spaceId).filter { it !in excludeTags }
        val matching =
            if (searchQuery.isBlank()) available
            else available.filter { it.contains(searchQuery, ignoreCase = true) }
        return matching.sorted().toPage(offset, limit)
    }

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

    /** The id the next created task will get. Must agree with [generateNextIdUnsafe]. */
    override suspend fun peekNextId(spaceId: String): String {
        val space = dao.getSpaceById(spaceId) ?: return "TASK-1"
        // Skipping taken ids exactly as generation does. Reading the counter alone showed an id
        // that creation would step over, so the New Task header promised one id and Save made
        // another — and a description written against the promised one linked to the wrong task.
        var nextNum = dao.getNextId(spaceId) ?: 1
        while (dao.getTaskById("${space.idPrefix}-$nextNum") != null) nextNum++
        return "${space.idPrefix}-$nextNum"
    }

    private suspend fun generateNextIdUnsafe(spaceId: String): String {
        val space = dao.getSpaceById(spaceId) ?: return "TASK-1"
        // Skips anything already taken. The counter is not the only source of ids — a task can be
        // created with one of its own, and an import assigns them — so handing out its value
        // unchecked could repeat an existing id, and the insert that failed also rolled back the
        // counter's increment, leaving the space unable to create anything ever again.
        var nextNum = dao.getNextId(spaceId) ?: 1
        while (dao.getTaskById("${space.idPrefix}-$nextNum") != null) nextNum++
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
        database.withWriteTransaction {
            if (dao.getSpaceById(spaceId) == null) return@withWriteTransaction null
            addTaskUnsafe(
                spaceId, title, description, status, dueDate, priority, estimatedTime,
                tags, connections, notifications, customId, recurrenceRules, autoUpdateStatusFromSubtasks
            )
        }.alsoNotifyIf { it != null }
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
            estimatedTimeSeconds = task.estimatedTime?.toApproximateSeconds(),
            id = task.id
        )
    }

    override suspend fun updateTask(task: Task): Task? = mutex.withLock {
        // A task's row, the connections on both sides of it, its tags and the status changes
        // cascading from it are one edit; half of it leaves connections pointing one way only.
        database.withWriteTransaction {
        val oldTask = getByIdUnsafe(task.id) ?: return@withWriteTransaction null

        val removedConnections = oldTask.connections.removingAll(task.connections)
        removedConnections.forEach { connection ->
            dao.deleteConnection(task.id, connection.targetTaskId, connection.type.name)
            dao.deleteConnection(connection.targetTaskId, task.id, connection.type.symmetric.name)
            // Detaching a subtask changes what its parent derives its status from. The add path
            // does this through addSymmetricConnectionUnsafe; removal had no counterpart.
            if (connection.type == ConnectionType.SubtaskOf) {
                updateParentStatusIfNeeded(connection.targetTaskId)
            }
        }

        val addedConnections = task.connections.removingAll(oldTask.connections)
        // Checked here as well as in addConnection. Saving a form writes its whole connection set
        // straight to the table, and the picker that filled it can only judge the snapshot it was
        // opened with — so an edge that became cyclic since, or two new edges that close a loop
        // between them, arrived unexamined. Every added edge is judged against all the others.
        rejectCycles(task.id, task.connections, addedConnections)
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

        finalTask
        }.alsoNotifyIf { it != null }
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

    override suspend fun getTasksBlockedBy(blockerId: String): List<Task> =
        hydrateTasksAcrossSpaces(dao.getTasksBlockedBy(blockerId))

    private suspend fun addSymmetricConnectionUnsafe(
        sourceTaskId: String,
        connection: TaskConnection,
        cascadeParentStatus: Boolean = true,
    ) {
        val targetTask = getByIdUnsafe(connection.targetTaskId) ?: return
        val symmetricConnection = TaskConnection(sourceTaskId, connection.type.symmetric)

        if (!targetTask.connections.contains(symmetricConnection)) {
            dao.insertConnection(connection.targetTaskId, sourceTaskId, connection.type.symmetric.name)

            if (cascadeParentStatus && symmetricConnection.type == ConnectionType.ParentOf) {
                updateParentStatusIfNeeded(connection.targetTaskId)
            }
        }
    }

    override suspend fun addConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean = mutex.withLock {
        // Both directions of the link, and any parent status it changes, are one edit.
        database.withWriteTransaction {
            addConnectionUnsafe(fromTaskId, toTaskId, type)
        }.alsoNotifyIf { it }
    }

    override suspend fun removeConnection(fromTaskId: String, toTaskId: String, type: ConnectionType): Boolean = mutex.withLock {
        // As in addConnection: both directions and the parent restatement commit together.
        database.withWriteTransaction {
            val fromTask = getByIdUnsafe(fromTaskId) ?: return@withWriteTransaction false
            val connection = TaskConnection(toTaskId, type)
            if (!fromTask.connections.contains(connection)) return@withWriteTransaction true

            dao.deleteConnection(fromTaskId, toTaskId, type.name)
            dao.deleteConnection(toTaskId, fromTaskId, type.symmetric.name)

            if (type == ConnectionType.SubtaskOf) {
                updateParentStatusIfNeeded(toTaskId)
            }
            true
        }.alsoNotifyIf { it }
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
        // Unblocking what this task blocked belongs to the deletion: half of it would leave
        // tasks blocked by a task that no longer exists.
        database.withWriteTransaction {
            val task = getByIdUnsafe(id) ?: return@withWriteTransaction false

            task.connections.forEach { connection ->
                dao.deleteConnection(connection.targetTaskId, id, connection.type.symmetric.name)
            }

            handleBlockerDeleted(id)

            val parentTasks = getParentTasks(id)

            dao.deleteTask(id)

            // Update parent tasks' statuses after subtask deletion
            updateParentStatuses(parentTasks)
            true
        }.alsoNotifyIf { it }
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

    /**
     * The stored filter panel, or a fresh one if what is stored cannot be read.
     *
     * A criteria row that fails to decode — written by a later version, or edited by hand — is
     * worth losing. Letting it throw took the whole space down with it, which is a great deal
     * worse than a filter panel that has forgotten its settings.
     */
    override suspend fun getFilterState(spaceId: String): TaskFilterCriteria =
        dao.getFilterState(spaceId)
            ?.let { runCatching { it.toTaskFilterCriteria() }.getOrNull() }
            ?: TaskFilterCriteria()

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
        // A view mode that cannot be decoded is skipped rather than thrown: one unreadable row
        // should cost the user that view mode, not the space.
        val custom = dao.getAllCustomViewModes(spaceId).mapNotNull { row ->
            runCatching { row.configJson.toViewMode(spaceId, row.id, row.name) }.getOrNull()
        }
        return builtIn.toPersistentList().addingAll(custom)
    }

    override suspend fun getViewModeById(spaceId: String, viewModeId: String): ViewMode? {
        // Check built-in modes first
        ViewMode.getBuiltInModes(spaceId).find { it.id == viewModeId }?.let { return it }
        // Check custom modes
        return dao.getCustomViewModeById(spaceId, viewModeId)?.let { row ->
            runCatching { row.configJson.toViewMode(spaceId, row.id, row.name) }.getOrNull()
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

    /**
     * Under the lock, so the export is one consistent picture.
     *
     * Tasks, timelines, tags and the id counter are four separate reads. Taken while the space is
     * being edited they could disagree — a task exported with the status it had before a cascade,
     * beside a timeline already recording the status after it — and re-importing that restored a
     * task contradicting its own history.
     */
    override suspend fun exportSpaceToJson(spaceId: String, prettyPrint: Boolean): String? = mutex.withLock {
        val space = getSpaceById(spaceId) ?: return@withLock null
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
        // The space's whole vocabulary, not just the tags currently in use. Import re-inserts
        // this set, so deriving it from the tasks quietly lost every tag created but not yet
        // applied — a backup and restore came back missing them.
        val spaceTags = dao.getAllTagsForSpace(spaceId).toSet()
        val nextId = dao.getNextId(spaceId)?.toInt() ?: 1

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
            // An import is one space or none.
            database.withWriteTransaction {
            val newPrefix = uniqueSpacePrefix(exportData.space.idPrefix) { dao.prefixExists(it) }
            val newSpaceId = "space-${dao.countSpaces()}-$newPrefix"
            val newSpace = Space(id = newSpaceId, name = exportData.space.name, idPrefix = newPrefix)

            dao.insertSpace(newSpaceId, newSpace.name, newPrefix)

            val oldToNewTaskId = createTaskIdMapping(exportData.tasks, newPrefix)
            dao.setNextId(newSpaceId, nextIdAfter(oldToNewTaskId.values, exportData.nextId).toLong())

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
                    normalizeBlocked = false,
                )

                timeline.forEach { statusChange ->
                    // Remapped like the current status. A Blocked entry names the tasks that
                    // blocked it, and left as written those ids point into whatever space now
                    // answers to the old prefix — so the history claimed the task had been
                    // blocked by tasks it never had anything to do with.
                    dao.insertStatusChange(
                        taskId = newTaskId,
                        timestamp = statusChange.timestamp.toEpochMilliseconds(),
                        previousStatusJson = statusChange.previousStatus
                            ?.let { remapBlockedStatus(it, oldToNewTaskId) }
                            .toJsonOrNull(),
                        newStatusJson = remapBlockedStatus(statusChange.newStatus, oldToNewTaskId).toJson(),
                        automaticChangeReasonJson = statusChange.automaticChangeReason.toJsonOrNull()
                    )
                }
            }

            exportData.tasks.forEach { task ->
                val newTaskId = oldToNewTaskId[task.id] ?: return@forEach
                task.connections.forEach { conn ->
                    val newTargetId = oldToNewTaskId[conn.targetTaskId] ?: return@forEach
                    addConnectionUnsafe(newTaskId, newTargetId, conn.type, cascadeParentStatus = false)
                }
            }

            exportData.tags.forEach { dao.insertTagForSpace(newSpaceId, it) }

            // Now that every task exists, and not before.
            unblockTasksWithOnlyResolvedBlockers(oldToNewTaskId.values)

            newSpace
            }.alsoNotifyIf { it != null }
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
        /** Import passes false: blockers may not exist yet. See unblockTasksWithOnlyResolvedBlockers. */
        normalizeBlocked: Boolean = true,
    ): Task? {
        val taskId = customId ?: generateNextIdUnsafe(spaceId)

        // Rounded to the millisecond the column stores, so the returned task matches a later read.
        val effectiveDueDate = dueDate?.let { Instant.fromEpochMilliseconds(it.toEpochMilliseconds()) }
        val status = if (autoUpdateStatusFromSubtasks) {
            val subtasksIds = connections
                .mapNotNull { if (it.type == ConnectionType.ParentOf) it.targetTaskId else null }
            getCalculatedStatusFromSubtasks(subtasksIds, ::getByIdUnsafe) ?: status
        } else {
            status
        }.let { if (normalizeBlocked) withoutResolvedBlockers(it) else it }

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
            isBlocked = if (status is TaskStatus.Blocked) 1 else 0,
            estimatedTimeSeconds = estimatedTime?.toApproximateSeconds(),
        )

        // As in updateTask. A task being created has no id for the picker to reason about, so
        // wouldCreateCycle answers "no" for every candidate it is offered and the form can hand
        // over, say, both "subtask of A" and "parent of A" at once.
        rejectCycles(taskId, connections, connections)
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

    /**
     * Links two tasks, both directions.
     *
     * [cascadeParentStatus] is off for import, which is restoring a status every task already has
     * rather than making a change. Edges arrive one at a time, so a parent that derives its status
     * would recompute it from however many subtasks had been linked so far and pass through
     * statuses it never held — each a real change to everything downstream. A parent momentarily
     * Done released the tasks waiting on it for good, so a task exported as Blocked came back
     * unblocked, with invented history to match.
     */
    private suspend fun addConnectionUnsafe(
        fromTaskId: String,
        toTaskId: String,
        type: ConnectionType,
        cascadeParentStatus: Boolean = true,
    ): Boolean {
        val fromTask = getByIdUnsafe(fromTaskId) ?: return false
        if (getByIdUnsafe(toTaskId) == null) return false

        val connection = TaskConnection(toTaskId, type)
        if (fromTask.connections.contains(connection)) return true

        // The task's own edges have to be handed over: the check treats the task being edited as
        // described by this set alone, so leaving it empty hid every edge already committed.
        if (wouldCreateCycle(fromTaskId, toTaskId, type, fromTask.connections)) return false

        dao.insertConnection(fromTaskId, toTaskId, type.name)
        addSymmetricConnectionUnsafe(fromTaskId, connection, cascadeParentStatus)

        return true
    }

    override suspend fun clearAllData() = mutex.withLock {
        // Under the lock and in one transaction like every other mutation: a partial clear is
        // observable, and a cache built concurrently from half-deleted data outlives it.
        database.withWriteTransaction {
            dao.getAllSpaces().forEach { space -> dao.deleteSpace(space.id) }
        }
        notifyChanged()
    }

    override suspend fun getRecurringTasksDueBefore(time: Instant): List<Task> =
        hydrateTasksAcrossSpaces(dao.getRecurringTasksDueBefore(time.toEpochMilliseconds()))

    // Override methods from AbstractTaskRepository that do read-modify-write to add mutex protection.
    // Note: processDateBasedRecurrences calls processRecurrenceTrigger internally, so we only
    // protect processDateBasedRecurrences with mutex (not both, to avoid deadlock with non-reentrant mutex).
    // processRecurrenceTrigger is also protected independently for when it's called directly.

    // Both write a timeline entry and then the task itself, so both need one transaction around
    // them like every other mutation here: killed between the two, the timeline gained an
    // automatic entry for a recurrence the task had not actually advanced through, and the next
    // startup processed it again and wrote a second.

    override suspend fun processRecurrenceTrigger(
        taskId: String,
        triggerEvent: RecurrenceTriggerEvent
    ): Task? = mutex.withLock {
        database.withWriteTransaction {
            processRecurrenceTriggerInternal(taskId, triggerEvent)
        }.alsoNotifyIf { it != null }
    }

    override suspend fun processDateBasedRecurrences(currentTime: Instant): List<Task> = mutex.withLock {
        database.withWriteTransaction {
            processDateBasedRecurrencesInternal(currentTime)
        }.alsoNotifyIf { it.isNotEmpty() }
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
        // "Any" and "no estimate" are not ranges; every other case is, and each of them excludes
        // tasks without an estimate, which is what lets it be written as a plain conjunction.
        val estimatedTimeRange = when (filterCriteria.estimatedTimeFilter) {
            EstimatedTimeFilter.Any, EstimatedTimeFilter.NoEstimate -> null
            // A bucket's upper bound is already exclusive; a custom one is inclusive, and one
            // second past it is the same range over whole seconds.
            EstimatedTimeFilter.Custom -> (estimatedTimeBounds.first ?: -OPEN_BOUND) to
                    (estimatedTimeBounds.second?.plus(1) ?: OPEN_BOUND)
            else -> (estimatedTimeBounds.first ?: -OPEN_BOUND) to
                    (estimatedTimeBounds.second ?: OPEN_BOUND)
        }

        // The same bounds the guarded clause uses, as half-open ranges. A task without the value is
        // outside every one of them, which is what lets a range be a plain conjunction.
        val priorityRange: Pair<Long, Long>? = when (filterCriteria.priorityFilter) {
            PriorityFilter.Any, PriorityFilter.NoPriority -> null
            PriorityFilter.High -> 75L to OPEN_BOUND
            PriorityFilter.Medium -> 50L to 75L
            PriorityFilter.Low -> 1L to 50L
            // Custom bounds are inclusive; one past the top is the same range over whole numbers.
            PriorityFilter.Custom -> (filterCriteria.customPriorityMin.toLongOrNull() ?: -OPEN_BOUND) to
                    (filterCriteria.customPriorityMax.toLongOrNull()?.plus(1) ?: OPEN_BOUND)
        }
        val dueDateRange: Pair<Long, Long>? = when (filterCriteria.dueDateFilter) {
            DueDateFilter.Any, DueDateFilter.NoDueDate -> null
            DueDateFilter.Custom -> (filterCriteria.customDueDateAfter?.toEpochMilliseconds() ?: -OPEN_BOUND) to
                    (filterCriteria.customDueDateBefore?.toEpochMilliseconds()?.plus(1) ?: OPEN_BOUND)
            // The relative ones come from the shared definition, so SQL and Kotlin cannot disagree.
            else -> relativeDueDateRange(filterCriteria.dueDateFilter, now)!!
                .let { (start, end) -> start.toEpochMilliseconds() to end.toEpochMilliseconds() }
        }

        return FilterParams(
            searchQuery = null,
            searchTerms = searchTermsOf(filterCriteria),
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
            estimatedTimeRange = estimatedTimeRange,
            priorityRange = priorityRange,
            dueDateRange = dueDateRange,
            // SQL only separates recurring from non-recurring; see `recurrenceFilter` below.
            recurrenceFilterType = when (filterCriteria.recurrenceFilter) {
                RecurrenceFilter.Any -> 0L
                RecurrenceFilter.NoRecurrence -> 1L
                else -> 2L
            },
            recurrenceFilter = filterCriteria.recurrenceFilter,
            criteria = filterCriteria,
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
        val matchedIds = mutableSetOf<String>()

        // For each group definition, count matching tasks using SQL
        for (group in level.groups) {
            val groupFilter = group.toFilter(level.field)
            val combinedFilters = parentFilters.adding(groupFilter)

            // A header needs the count, and the uncategorised group needs to know which tasks
            // were claimed; neither needs the tasks themselves.
            val groupIds = candidateIds(spaceId, combinedFilters, filterParams)
            matchedIds += groupIds

            if (groupIds.isNotEmpty() || level.showEmptyGroups) {
                result.add(
                    TaskGroupInfo(
                        label = group.label,
                        taskCount = groupIds.size,
                        isUncategorized = false,
                        groupDefinition = group,
                        filter = groupFilter
                    )
                )
            }
        }

        if (level.groups.isNotEmpty()) {
            val allGroupFilters = level.groups.mapToPersistentList { it.toFilter(level.field) }
            val uncategorizedFilter = GroupFilter.Not(
                field = level.field,
                filters = allGroupFilters
            )

            // "Matches none of the groups" is what the loop above worked out, so take the
            // difference; querying the Not filter re-ran the whole query once more per group.
            val uncategorizedCount = candidateIds(spaceId, parentFilters, filterParams)
                .count { it !in matchedIds }

            if (uncategorizedCount > 0) {
                result.add(
                    TaskGroupInfo(
                        label = "",
                        taskCount = uncategorizedCount,
                        isUncategorized = true,
                        groupDefinition = null,
                        // The leaf query still resolves this filter when the group is opened.
                        filter = uncategorizedFilter
                    )
                )
            }
        } else {
            // No groups defined - all tasks are uncategorized
            val allCount = countCandidates(spaceId, parentFilters, filterParams)
            if (allCount > 0) {
                result.add(
                    TaskGroupInfo(
                        label = "",
                        taskCount = allCount,
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
    /**
     * Whether the candidate set can be narrowed by SQL alone.
     *
     * Three criteria are settled in Kotlin afterwards because they read into stored JSON: which
     * kind of recurrence rule a task has, whether it carries *all* of a set of tags, and the group
     * filters that exclude or match on tags. When any of those is in play the rows themselves are
     * needed; otherwise the database can answer with just a count or a list of ids.
     */
    private fun sqlAnswersFully(groupFilters: List<GroupFilter>, filterParams: FilterParams): Boolean =
        filterParams.searchTerms.isEmpty() &&
                !filterParams.recurrenceFilter.bucketedInSqlOnly &&
                !(filterParams.selectedTags.isNotEmpty() && filterParams.tagMatchMode == TagMatchMode.All) &&
                !filterParams.criteria.refinesStatusText() &&
                !filterParams.criteria.refinesByConnectionIds() &&
                groupFilters.none { it is GroupFilter.HasTags || it is GroupFilter.Not } &&
                filterParams.selectedTags.isEmpty()

    /** How many tasks match, reading as little as the filters allow. */
    private suspend fun countCandidates(
        spaceId: String,
        groupFilters: List<GroupFilter>,
        filterParams: FilterParams
    ): Int {
        if (!sqlAnswersFully(groupFilters, filterParams)) {
            return queryCandidateRows(spaceId, groupFilters, filterParams).size
        }
        val groupParams = mergeGroupFilterParams(groupFilters.map { buildGroupFilterParams(it) })
        return dao.countTasksFilteredWithGroupFilter(
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
    }

    /** Ids of the matching tasks, without their columns where the filters allow it. */
    private suspend fun candidateIds(
        spaceId: String,
        groupFilters: List<GroupFilter>,
        filterParams: FilterParams
    ): List<String> {
        if (!sqlAnswersFully(groupFilters, filterParams)) {
            return queryCandidateRows(spaceId, groupFilters, filterParams).map { it.id }
        }
        val groupParams = mergeGroupFilterParams(groupFilters.map { buildGroupFilterParams(it) })
        return dao.getTaskIdsFilteredWithGroupFilter(
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
    }

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
        // One filter's bound is restated as a plain conjunction so the planner can seek on its
        // index, and that filter's guarded clause stands aside with type 0. Only one of them:
        // SQLite uses at most one index per table reference, so a second range would be checked
        // row by row anyway. Due date first, because "today" or "this week" narrows a space far
        // more than a priority band, which is a quarter of the range.
        val dueDates = filterParams.dueDateRange
        val estimates = filterParams.estimatedTimeRange
        val priorities = filterParams.priorityRange
        var rows = when {
            dueDates != null -> dao.getTasksFilteredInDueDateRange(
                spaceId = spaceId,
                searchQuery = filterParams.searchQuery,
                searchInId = filterParams.searchInId,
                searchInTitle = filterParams.searchInTitle,
                searchInDescription = filterParams.searchInDescription,
                searchInTags = filterParams.searchInTags,
                priorityFilterType = filterParams.priorityFilterType,
                customPriorityMin = filterParams.customPriorityMin,
                customPriorityMax = filterParams.customPriorityMax,
                dueDateFilterType = 0L,
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
                requireNotSubtask = filterParams.requireNotSubtask,
                rangeMinDueDate = dueDates.first,
                rangeMaxDueDate = dueDates.second,
            )

            estimates != null -> dao.getTasksFilteredInEstimatedRange(
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
                estimatedTimeFilterType = 0L,
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
                requireNotSubtask = filterParams.requireNotSubtask,
                rangeMinSeconds = estimates.first,
                rangeMaxSeconds = estimates.second,
            )

            priorities != null -> dao.getTasksFilteredInPriorityRange(
                spaceId = spaceId,
                searchQuery = filterParams.searchQuery,
                searchInId = filterParams.searchInId,
                searchInTitle = filterParams.searchInTitle,
                searchInDescription = filterParams.searchInDescription,
                searchInTags = filterParams.searchInTags,
                priorityFilterType = 0L,
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
                requireNotSubtask = filterParams.requireNotSubtask,
                rangeMinPriority = priorities.first,
                rangeMaxPriority = priorities.second,
            )

            else -> dao.getTasksFilteredWithGroupFilter(
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
        }

        // The search box, matched here rather than in SQL. See FilterParams.searchQuery.
        if (filterParams.searchTerms.isNotEmpty()) {
            rows = rows.filter { row ->
                matchesSearchTerms(
                    terms = filterParams.searchTerms,
                    fields = filterParams.criteria.textSearchFields,
                    id = row.id,
                    title = row.title,
                    description = row.description,
                    tags = row.tagsJson.toStringSet(),
                )
            }
        }

        // The kind of rule is only visible once the rules are decoded; see RecurrenceFilter.matches.
        if (filterParams.recurrenceFilter.bucketedInSqlOnly) {
            rows = rows.filter { filterParams.recurrenceFilter.matches(it.recurrenceRulesJson.toRecurrenceRuleList()) }
        }

        // A blocker id, a blocker comment and a declined reason all live inside the serialized
        // status, which SQL narrows only to the status class.
        val criteria = filterParams.criteria
        if (criteria.refinesStatusText()) {
            val blockedByIds = parseTaskIds(criteria.blockedByTaskIds)
            rows = rows.filter { matchesStatusCriteria(it.status.toTaskStatus(), criteria, blockedByIds) }
        }

        // Naming particular connected tasks needs each candidate's connections, read in one query.
        if (criteria.refinesByConnectionIds()) {
            val idFilters = connectionIdFilters(criteria)
            val connections = connectionsForTasksInSpace(spaceId, rows.map { it.id })
            rows = rows.filter {
                matchesConnectionsByTaskIds(connections[it.id].orEmpty(), idFilters)
            }
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
     * Totals for every candidate, computed from rows plus a few queries instead of the per-task
     * lookups a fully loaded task list would need.
     *
     * The graph is the whole space, not the candidates. A dependency does not stop existing
     * because the group or the filter hides the task at the other end of it — following only the
     * candidates gave a task one total on the grouped screen, another on the filtered list, and a
     * third on its own detail page, and made the two repositories disagree outright. Blocked tasks
     * are considered across all spaces, as at task level.
     */
    private suspend fun calculateCandidateTotals(
        spaceId: String,
        rows: List<Tasks>
    ): Map<String, TaskTotals> {
        val dependentType = ConnectionType.IsDependencyOf.name

        val dependentsBySource = dao.getConnectionsBySpaceAndType(spaceId, dependentType)
            .groupBy({ it.sourceTaskId }, { it.targetTaskId })
        val nodes = dao.getTasksBySpace(spaceId).map { it.toTotalsNode(dependentsBySource[it.id].orEmpty()) }

        val blockedDependentsBySource = dao.getConnectionsForBlockedTasks(dependentType)
            .groupBy({ it.sourceTaskId }, { it.targetTaskId })
        val blockedNodes = dao.getBlockedTasks().map { it.toTotalsNode(blockedDependentsBySource[it.id].orEmpty()) }

        val totals = calculateTotals(nodes, blockedNodes)
        return if (rows.size == nodes.size) totals else rows.associate { it.id to (totals[it.id] ?: TaskTotals(null, null)) }
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
        OrderableField.EstimatedTime -> row.estimatedTimeSeconds
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
        countCache[key] ?: countCandidates(spaceId, filters, buildFilterParams(filterCriteria))
            .also { countCache.putCapped(key, it) }
    }

    /**
     * Data class to hold filter parameters for SQL dao.
     */
    private data class FilterParams(
        /**
         * Always null: the search box is matched in Kotlin, over the rows the query returns.
         *
         * The clause it fed could not be made to agree with [matchesSearchTerms]. It matched the
         * whole query as one substring where Kotlin requires each whitespace-separated term, it
         * let a typed `%` or `_` act as a wildcard, and SQLite's `LOWER` folds only ASCII — so it
         * could also *miss* rows, which rules out leaving it in as a cheap pre-filter.
         */
        val searchQuery: String? = null,
        /** The search box, split into terms; see [matchesSearchTerms]. */
        val searchTerms: List<String> = emptyList(),
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
        /**
         * The same bound as a half-open `[first, second)` range, set whenever the filter is a range
         * at all. Only this form can be seeked on; see `getTasksFilteredInEstimatedRange`.
         */
        val estimatedTimeRange: Pair<Long, Long>? = null,
        /** As [estimatedTimeRange], for `idx_tasks_priority`. */
        val priorityRange: Pair<Long, Long>? = null,
        /** As [estimatedTimeRange], for `idx_tasks_dueDate`. */
        val dueDateRange: Pair<Long, Long>? = null,
        val recurrenceFilterType: Long = 0,
        /** Applied in Kotlin: SQL cannot tell one kind of recurrence rule from another. */
        val recurrenceFilter: RecurrenceFilter = RecurrenceFilter.Any,
        /**
         * The criteria themselves, for the parts no query can express: text inside a status, and
         * the ids of particular connected tasks.
         */
        val criteria: TaskFilterCriteria = TaskFilterCriteria(),
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
    )

    /**
     * A boolean group's values as a SQL parameter: 1 for true, 0 for false, null for no restriction.
     *
     * Every value in the set counts. Reading only the first meant a group admitting both "true"
     * and "false" — which the editor offers and validation accepts — filtered on one of them and
     * pushed the rest of the space into Uncategorized; a value that is not a boolean at all
     * disabled the clause, matching everything where the Kotlin predicate matches nothing.
     */
    private fun booleanToSqlParam(values: Set<String>): Long? {
        val admitted = values.mapNotNullTo(mutableSetOf()) { it.toBooleanStrictOrNull() }
        return when {
            admitted.size == 2 -> null // both, so nothing to restrict
            true in admitted -> 1L
            false in admitted -> 0L
            else -> IMPOSSIBLE_FLAG
        }
    }

    /**
     * Compute range filter type based on includeNull flag and whether range bounds are present.
     * Returns: 1 = Null only, 2 = Range only, 3 = Null + Range
     *
     * A group with neither bound set and null values excluded is still a restriction: it admits
     * whatever has a value. "Range only" with no bounds says exactly that. Reading it as no
     * restriction at all — which the editor lets the user build — let the tasks with no value into
     * the group in SQL while the shared predicate put them in Uncategorized.
     */
    private fun rangeFilterType(includeNull: Boolean, hasRange: Boolean): Long = when {
        includeNull && hasRange -> 3L // Null + Range
        includeNull -> 1L // Null only
        else -> 2L // Range only, bounded or not
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
            // Neither reaches SQL as a parameter: queryCandidateRows peels these two off and
            // resolves them with their own queries before building the rest.
            is GroupFilter.HasTags, is GroupFilter.Not -> GroupFilterParams()
        }
    }

    /**
     * Merge multiple group filter params into one (ANDing the conditions).
     */
    /**
     * Combines the group filters of every level into one set of parameters, ANDed.
     *
     * Nesting can put the same field at more than one level — a "high priority" level split again
     * by a narrower priority range — and the levels have to intersect. Taking the later value
     * instead meant the inner group showed tasks the outer one excludes.
     */
    private fun mergeGroupFilterParams(params: List<GroupFilterParams>): GroupFilterParams {
        if (params.isEmpty()) return GroupFilterParams()
        if (params.size == 1) return params.first()

        /** Intersecting two lower bounds keeps the higher; two upper bounds keep the lower. */
        fun highest(a: Long?, b: Long?) = if (a == null || b == null) a ?: b else maxOf(a, b)
        fun lowest(a: Long?, b: Long?) = if (a == null || b == null) a ?: b else minOf(a, b)

        /**
         * Intersecting two range filters.
         *
         * The value says which rows the level admits: 0 none of this kind of restriction, 1 only
         * rows with no value, 2 only rows inside the bounds, 3 either. Two levels admit what both
         * admit, so this is set intersection over {null, in-range} — taking the larger of the two
         * instead widened every mixed pair: "no priority" nested inside "1-25" came out as 3 and
         * matched both, where the two have nothing in common at all.
         */
        fun rangeTypes(a: Long, b: Long): Long = when {
            a == 0L -> b
            b == 0L -> a
            // 1 = {null}, 2 = {in range}, 3 = both; intersect as bit sets. An empty intersection
            // is not 0 — that is "no restriction", which would match everything — but a value the
            // query recognises as nothing at all.
            else -> (a and b).takeIf { it != 0L } ?: IMPOSSIBLE_RANGE_TYPE
        }

        /** A flag two levels disagree on cannot be satisfied; 2 is the "match nothing" marker. */
        fun bothOrNothing(a: Long?, b: Long?) = when {
            a == null || b == null -> a ?: b
            a == b -> a
            else -> IMPOSSIBLE_FLAG
        }

        var result = GroupFilterParams()
        for (p in params) {
            result = result.copy(
                groupPriorityFilterType = rangeTypes(p.groupPriorityFilterType, result.groupPriorityFilterType),
                groupPriorityMin = highest(p.groupPriorityMin, result.groupPriorityMin),
                groupPriorityMax = lowest(p.groupPriorityMax, result.groupPriorityMax),
                groupDueDateFilterType = rangeTypes(p.groupDueDateFilterType, result.groupDueDateFilterType),
                groupDueDateMin = highest(p.groupDueDateMin, result.groupDueDateMin),
                groupDueDateMax = lowest(p.groupDueDateMax, result.groupDueDateMax),
                groupIsRecurring = bothOrNothing(p.groupIsRecurring, result.groupIsRecurring),
                groupAutoUpdateStatus = bothOrNothing(p.groupAutoUpdateStatus, result.groupAutoUpdateStatus),
                groupHasNotifications = bothOrNothing(p.groupHasNotifications, result.groupHasNotifications),
                groupEstimatedTimeFilterType = rangeTypes(p.groupEstimatedTimeFilterType, result.groupEstimatedTimeFilterType),
                groupEstimatedTimeMinSeconds = highest(p.groupEstimatedTimeMinSeconds, result.groupEstimatedTimeMinSeconds),
                groupEstimatedTimeMaxSeconds = lowest(p.groupEstimatedTimeMaxSeconds, result.groupEstimatedTimeMaxSeconds),
                groupHasConnections = bothOrNothing(p.groupHasConnections, result.groupHasConnections),
                // Two status levels intersect to the statuses both allow.
                groupStatusFilterType = maxOf(p.groupStatusFilterType, result.groupStatusFilterType),
                groupStatusOpen = bothAllow(p, result) { it.groupStatusOpen },
                groupStatusInProgress = bothAllow(p, result) { it.groupStatusInProgress },
                groupStatusBlocked = bothAllow(p, result) { it.groupStatusBlocked },
                groupStatusDone = bothAllow(p, result) { it.groupStatusDone },
                groupStatusDeclined = bothAllow(p, result) { it.groupStatusDeclined },
            )
        }
        return result
    }

    /** A status is allowed only where both levels allow it; a level that does not filter allows all. */
    private inline fun bothAllow(
        a: GroupFilterParams,
        b: GroupFilterParams,
        flag: (GroupFilterParams) -> Long,
    ): Long = when {
        a.groupStatusFilterType == 0L -> flag(b)
        b.groupStatusFilterType == 0L -> flag(a)
        else -> if (flag(a) == 1L && flag(b) == 1L) 1L else 0L
    }

    // ============ Saved filter management ============

    override suspend fun getAllSavedFilters(spaceId: String): List<SavedFilter> =
        dao.getAllSavedFilters(spaceId).mapNotNull { it.toSavedFilterModel() }

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

/**
 * A user's search text as the operand of a `LIKE`, with its wildcards made literal.
 *
 * `%` and `_` are pattern syntax, so typing "50%" matched everything beginning "50" and "a_b"
 * matched "axb". The queries pair this with `ESCAPE '\'`. (The main filter box does not come
 * through here at all — it is matched in Kotlin; see FilterParams.searchQuery.)
 */
private fun String.escapedForLike(): String =
    replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** Null when the stored criteria cannot be read; see [RoomTaskRepository.getFilterState]. */
private fun SavedFilters.toSavedFilterModel(): SavedFilter? {
    val criteria = runCatching { criteriaJson.toTaskFilterCriteria() }.getOrNull() ?: return null
    return SavedFilter(
        id = id,
        name = name,
        spaceId = spaceId,
        criteria = criteria,
        viewModeId = viewModeId,
    )
}

private fun Spaces.toModel() = Space(
    id = id,
    name = name,
    idPrefix = idPrefix
)
