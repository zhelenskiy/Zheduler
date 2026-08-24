package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.Dao
import androidx.room3.Query

/**
 * Every statement the app runs, ported one-for-one from the SQLDelight `ZhedulerDatabase.sq` it
 * replaces. Writes stay spelled out as [Query] rather than `@Insert`/`@Update` so the original
 * conflict clauses (`INSERT OR REPLACE`, `INSERT OR IGNORE`) survive verbatim.
 */
@Dao
interface ZhedulerDao {

    // ============ Space queries ============

    @Query("SELECT EXISTS(SELECT 1 FROM spaces)")
    suspend fun hasSpaces(): Boolean

    @Query("SELECT * FROM spaces")
    suspend fun getAllSpaces(): List<Spaces>

    /**
     * One window of the space list. Ordered by `rowid`, i.e. by creation order, which is the order
     * the unpaged queries happen to return and the one the screen showed before it was paged.
     */
    @Query("SELECT * FROM spaces ORDER BY rowid LIMIT :limit OFFSET :offset")
    suspend fun getAllSpacesPaged(limit: Int, offset: Int): List<Spaces>

    @Query("SELECT COUNT(*) FROM spaces")
    suspend fun countSpaces(): Int

    @Query("SELECT * FROM spaces WHERE id = :id")
    suspend fun getSpaceById(id: String): Spaces?

    @Query("SELECT COUNT(*) > 0 FROM spaces WHERE idPrefix = :idPrefix")
    suspend fun prefixExists(idPrefix: String): Boolean

    @Query("INSERT INTO spaces(id, name, idPrefix) VALUES (:id, :name, :idPrefix)")
    suspend fun insertSpace(id: String, name: String, idPrefix: String)

    @Query("UPDATE spaces SET name = :name WHERE id = :id")
    suspend fun updateSpace(name: String, id: String)

    @Query("DELETE FROM spaces WHERE id = :id")
    suspend fun deleteSpace(id: String)

    @Query("SELECT idPrefix FROM spaces")
    suspend fun getAllPrefixes(): List<String>

    // ============ Task queries ============

    @Query("SELECT EXISTS(SELECT 1 FROM tasks WHERE spaceId = :spaceId)")
    suspend fun hasAnyTasks(spaceId: String): Boolean

    @Query("SELECT * FROM tasks WHERE spaceId = :spaceId")
    suspend fun getTasksBySpace(spaceId: String): List<Tasks>

    /** One window of [searchTasksForConnection], in creation order so paging is stable. */
    @Query(
        """
        SELECT * FROM tasks
        WHERE spaceId = :spaceId AND (:id IS NULL OR id != :id)
          AND (:searchQuery = '' OR LOWER(id) LIKE '%' || LOWER(:searchQuery) || '%' ESCAPE '\' OR LOWER(title) LIKE '%' || LOWER(:searchQuery) || '%' ESCAPE '\')
        ORDER BY rowid
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchTasksForConnectionPaged(
        spaceId: String,
        id: String?,
        searchQuery: String,
        limit: Int,
        offset: Int,
    ): List<Tasks>

    @Query(
        """
        SELECT COUNT(*) FROM tasks
        WHERE spaceId = :spaceId AND (:id IS NULL OR id != :id)
          AND (:searchQuery = '' OR LOWER(id) LIKE '%' || LOWER(:searchQuery) || '%' ESCAPE '\' OR LOWER(title) LIKE '%' || LOWER(:searchQuery) || '%' ESCAPE '\')
        """
    )
    suspend fun countTasksForConnection(spaceId: String, id: String?, searchQuery: String): Int

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskById(id: String): Tasks?

    @Query("SELECT spaceId FROM tasks WHERE id = :id")
    suspend fun getSpaceIdForTask(id: String): String?

    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun getTasksByIds(ids: Collection<String>): List<Tasks>

    @Query(
        """
        INSERT INTO tasks(id, title, description, status, dueDate, priority, estimatedTimeJson,
                           tagsJson, notificationsJson, spaceId, recurrenceRulesJson, autoUpdateStatusFromSubtasks,
                           isRecurring, isBlocked, estimatedTimeSeconds, dueSoundJson)
        VALUES (:id, :title, :description, :status, :dueDate, :priority, :estimatedTimeJson,
                :tagsJson, :notificationsJson, :spaceId, :recurrenceRulesJson, :autoUpdateStatusFromSubtasks,
                :isRecurring, :isBlocked, :estimatedTimeSeconds, :dueSoundJson)
        """
    )
    suspend fun insertTask(
        id: String,
        title: String,
        description: String,
        status: String,
        dueDate: Long?,
        priority: Long?,
        estimatedTimeJson: String?,
        tagsJson: String,
        notificationsJson: String,
        spaceId: String,
        recurrenceRulesJson: String,
        autoUpdateStatusFromSubtasks: Long,
        isRecurring: Long,
        isBlocked: Long,
        estimatedTimeSeconds: Long?,
        dueSoundJson: String?,
    )

    @Query(
        """
        UPDATE tasks SET
            title = :title,
            description = :description,
            status = :status,
            dueDate = :dueDate,
            priority = :priority,
            estimatedTimeJson = :estimatedTimeJson,
            tagsJson = :tagsJson,
            notificationsJson = :notificationsJson,
            spaceId = :spaceId,
            recurrenceRulesJson = :recurrenceRulesJson,
            autoUpdateStatusFromSubtasks = :autoUpdateStatusFromSubtasks,
            isRecurring = :isRecurring,
            isBlocked = :isBlocked,
            estimatedTimeSeconds = :estimatedTimeSeconds,
            dueSoundJson = :dueSoundJson
        WHERE id = :id
        """
    )
    suspend fun updateTask(
        title: String,
        description: String,
        status: String,
        dueDate: Long?,
        priority: Long?,
        estimatedTimeJson: String?,
        tagsJson: String,
        notificationsJson: String,
        spaceId: String,
        recurrenceRulesJson: String,
        autoUpdateStatusFromSubtasks: Long,
        isRecurring: Long,
        isBlocked: Long,
        estimatedTimeSeconds: Long?,
        dueSoundJson: String?,
        id: String,
    )

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query(
        """
        SELECT * FROM tasks
        WHERE isRecurring = 1 AND dueDate IS NOT NULL AND dueDate <= :dueDate
        """
    )
    suspend fun getRecurringTasksDueBefore(dueDate: Long): List<Tasks>

    @Query("SELECT * FROM tasks WHERE isBlocked = 1")
    suspend fun getBlockedTasks(): List<Tasks>

    /**
     * Candidates for "blocked by this task": every row whose status mentions the id in quotes.
     *
     * Deliberately a superset. Blockers live in a JSON array inside the status, and matching the
     * quoted id against the whole column also matches one written in a comment. Both callers
     * already re-check membership against the decoded status, so a few extra rows cost nothing,
     * whereas a missed row would leave a task blocked by something already resolved.
     *
     * Matching this way rather than with `json_each` keeps the query working on Android's own
     * SQLite, which the host tests run against and which has no JSON functions. Quoting the id is
     * what makes it exact enough to be worth doing: `"TEST-1"` does not occur inside `"TEST-10"`.
     * Task ids are letters, digits and a dash, so none of them are LIKE wildcards.
     */
    @Query(
        """
        SELECT * FROM tasks
        WHERE isBlocked = 1 AND status LIKE '%"' || :blockerTaskId || '"%'
        """
    )
    suspend fun getTasksBlockedBy(blockerTaskId: String): List<Tasks>

    // ============ Connection queries ============

    @Query("SELECT * FROM task_connections WHERE sourceTaskId = :sourceTaskId")
    suspend fun getConnectionsForTask(sourceTaskId: String): List<TaskConnections>

    /**
     * Connections of several tasks at once, so loading a page of tasks costs one query instead of
     * one per task. Keep the id list within SQLite's bound-parameter limit; the repository falls
     * back to [getConnectionsBySpace] for larger sets.
     */
    @Query("SELECT * FROM task_connections WHERE sourceTaskId IN (:sourceTaskIds)")
    suspend fun getConnectionsForTasks(sourceTaskIds: Collection<String>): List<TaskConnections>

    /** Every connection whose source task lives in the given space. */
    @Query(
        """
        SELECT c.* FROM task_connections c
        INNER JOIN tasks t ON t.id = c.sourceTaskId
        WHERE t.spaceId = :spaceId
        """
    )
    suspend fun getConnectionsBySpace(spaceId: String): List<TaskConnections>

    /**
     * Every connection that points *into* a space from a task living somewhere else.
     *
     * Read before a space is replaced from a snapshot. Both ends of a connection cascade, so
     * emptying a space also deletes the links other spaces hold into it — including links to
     * tasks the snapshot puts straight back, which would otherwise be quietly cut every time a
     * cloud space merely caught up with its server.
     */
    @Query(
        """
        SELECT c.* FROM task_connections c
        INNER JOIN tasks source ON source.id = c.sourceTaskId
        INNER JOIN tasks target ON target.id = c.targetTaskId
        WHERE target.spaceId = :spaceId AND source.spaceId != :spaceId
        """
    )
    suspend fun getConnectionsIntoSpace(spaceId: String): List<TaskConnections>

    /** As [getConnectionsBySpace], restricted to one connection type (totals only need one). */
    @Query(
        """
        SELECT c.* FROM task_connections c
        INNER JOIN tasks t ON t.id = c.sourceTaskId
        WHERE t.spaceId = :spaceId AND c.type = :type
        """
    )
    suspend fun getConnectionsBySpaceAndType(spaceId: String, type: String): List<TaskConnections>

    /**
     * Connections of the currently blocked tasks, of one type. Blocked tasks pull on the totals of
     * their blockers regardless of which space they are in, which is why this is not space-scoped.
     */
    @Query(
        """
        SELECT c.* FROM task_connections c
        INNER JOIN tasks t ON t.id = c.sourceTaskId
        WHERE t.isBlocked = 1 AND c.type = :type
        """
    )
    suspend fun getConnectionsForBlockedTasks(type: String): List<TaskConnections>

    @Query(
        """
        INSERT OR REPLACE INTO task_connections(sourceTaskId, targetTaskId, type)
        VALUES (:sourceTaskId, :targetTaskId, :type)
        """
    )
    suspend fun insertConnection(sourceTaskId: String, targetTaskId: String, type: String)

    @Query(
        """
        DELETE FROM task_connections
        WHERE sourceTaskId = :sourceTaskId AND targetTaskId = :targetTaskId AND type = :type
        """
    )
    suspend fun deleteConnection(sourceTaskId: String, targetTaskId: String, type: String)

    @Query(
        """
        SELECT t.* FROM tasks t
        INNER JOIN task_connections c ON t.id = c.targetTaskId
        WHERE c.sourceTaskId = :sourceTaskId AND c.type = 'SubtaskOf'
        """
    )
    suspend fun getParentTasks(sourceTaskId: String): List<Tasks>

    @Query(
        """
        SELECT t.* FROM tasks t
        INNER JOIN task_connections c ON t.id = c.targetTaskId
        WHERE c.sourceTaskId = :sourceTaskId AND c.type = 'ParentOf'
        """
    )
    suspend fun getSubtasks(sourceTaskId: String): List<Tasks>

    // ============ Status change queries ============

    @Query("SELECT * FROM status_changes WHERE taskId = :taskId ORDER BY timestamp ASC")
    suspend fun getStatusTimeline(taskId: String): List<StatusChanges>

    @Query("SELECT * FROM status_changes WHERE taskId IN (:taskIds) ORDER BY taskId, timestamp ASC")
    suspend fun getStatusTimelinesForTasks(taskIds: Collection<String>): List<StatusChanges>

    /**
     * Status changes the app made for the user, in `(since, until]`, across every space.
     *
     * A null reason is a change the user made by hand and is not news to them.
     */
    @Query(
        """
        SELECT * FROM status_changes
        WHERE timestamp > :since AND timestamp <= :until AND automaticChangeReasonJson IS NOT NULL
        ORDER BY timestamp ASC
        """
    )
    suspend fun getAutomaticStatusChangesBetween(since: Long, until: Long): List<StatusChanges>

    @Query(
        """
        INSERT INTO status_changes(taskId, timestamp, previousStatusJson, newStatusJson, automaticChangeReasonJson)
        VALUES (:taskId, :timestamp, :previousStatusJson, :newStatusJson, :automaticChangeReasonJson)
        """
    )
    suspend fun insertStatusChange(
        taskId: String,
        timestamp: Long,
        previousStatusJson: String?,
        newStatusJson: String,
        automaticChangeReasonJson: String?,
    )

    @Query(
        """
        SELECT sc.* FROM status_changes sc
        INNER JOIN tasks t ON sc.taskId = t.id
        WHERE t.spaceId = :spaceId AND sc.timestamp >= :fromTimestamp AND sc.timestamp < :untilTimestamp
        """
    )
    suspend fun getStatusChangesBySpaceAndDateRange(
        spaceId: String,
        fromTimestamp: Long,
        untilTimestamp: Long,
    ): List<StatusChanges>

    // ============ Space next ID queries ============

    @Query("SELECT nextId FROM space_next_ids WHERE spaceId = :spaceId")
    suspend fun getNextId(spaceId: String): Long?

    @Query("INSERT OR REPLACE INTO space_next_ids(spaceId, nextId) VALUES (:spaceId, :nextId)")
    suspend fun setNextId(spaceId: String, nextId: Long)

    // ============ Tag queries (space-scoped) ============

    /**
     * A space's whole tag vocabulary.
     *
     * The pickers read this and match in Kotlin. There used to be paged `LIKE` queries here to
     * search and exclude in SQL, and they are gone: SQLite's `LOWER` folds only A–Z, so they found
     * nothing in any alphabet but English. A space's vocabulary is small enough to read whole.
     */
    @Query("SELECT name FROM tags WHERE spaceId = :spaceId")
    suspend fun getAllTagsForSpace(spaceId: String): List<String>

    @Query("INSERT OR IGNORE INTO tags(spaceId, name) VALUES (:spaceId, :name)")
    suspend fun insertTagForSpace(spaceId: String, name: String)

    @Query("SELECT EXISTS(SELECT 1 FROM tags WHERE spaceId = :spaceId AND name = :name)")
    suspend fun tagExists(spaceId: String, name: String): Boolean

    @Query("DELETE FROM tags WHERE spaceId = :spaceId AND name = :name")
    suspend fun deleteTagForSpace(spaceId: String, name: String): Int

    // ============ Task tag queries (normalized task_tags table) ============

    @Query("INSERT OR IGNORE INTO task_tags(taskId, tag) VALUES (:taskId, :tag)")
    suspend fun insertTaskTag(taskId: String, tag: String)

    @Query("DELETE FROM task_tags WHERE taskId = :taskId")
    suspend fun deleteTaskTags(taskId: String)

    // ============ Filter state queries ============

    @Query("SELECT criteriaJson FROM filter_states WHERE spaceId = :spaceId")
    suspend fun getFilterState(spaceId: String): String?

    @Query("INSERT OR REPLACE INTO filter_states(spaceId, criteriaJson) VALUES (:spaceId, :criteriaJson)")
    suspend fun setFilterState(spaceId: String, criteriaJson: String)

    // ============ View mode queries ============

    @Query("SELECT viewMode FROM view_modes WHERE spaceId = :spaceId")
    suspend fun getViewMode(spaceId: String): String?

    @Query("INSERT OR REPLACE INTO view_modes(spaceId, viewMode) VALUES (:spaceId, :viewMode)")
    suspend fun setViewMode(spaceId: String, viewMode: String)

    // ============ Filter panel state queries ============

    @Query("SELECT isOpen FROM filter_panel_states WHERE spaceId = :spaceId")
    suspend fun getFilterPanelState(spaceId: String): Long?

    @Query("INSERT OR REPLACE INTO filter_panel_states(spaceId, isOpen) VALUES (:spaceId, :isOpen)")
    suspend fun setFilterPanelState(spaceId: String, isOpen: Long)

    // ============ Custom view mode queries ============

    @Query("SELECT * FROM custom_view_modes WHERE spaceId = :spaceId")
    suspend fun getAllCustomViewModes(spaceId: String): List<CustomViewModes>

    @Query("SELECT * FROM custom_view_modes WHERE spaceId = :spaceId AND id = :id")
    suspend fun getCustomViewModeById(spaceId: String, id: String): CustomViewModes?

    @Query(
        """
        INSERT OR REPLACE INTO custom_view_modes(id, spaceId, name, configJson)
        VALUES (:id, :spaceId, :name, :configJson)
        """
    )
    suspend fun insertOrUpdateCustomViewMode(id: String, spaceId: String, name: String, configJson: String)

    @Query("DELETE FROM custom_view_modes WHERE spaceId = :spaceId AND id = :id")
    suspend fun deleteCustomViewMode(spaceId: String, id: String)

    // ============ Active view mode queries ============

    @Query("SELECT viewModeId FROM active_view_modes WHERE spaceId = :spaceId")
    suspend fun getActiveViewModeId(spaceId: String): String?

    @Query("INSERT OR REPLACE INTO active_view_modes(spaceId, viewModeId) VALUES (:spaceId, :viewModeId)")
    suspend fun setActiveViewModeId(spaceId: String, viewModeId: String)

    // ============ Grouped task queries for efficient grouping by field values ============

    // ============ Comprehensive filtered task query ============
    // Handles all TaskFilterCriteria options in SQL. Parameters are nullable; when NULL, that
    // filter is not applied.

    /** Filtered tasks matching group filter conditions. */
    @Query("SELECT t.* FROM tasks t " + FILTERED_TASKS_WHERE)
    suspend fun getTasksFilteredWithGroupFilter(
        spaceId: String,
        searchQuery: String?,
        searchInId: Long,
        searchInTitle: Long,
        searchInDescription: Long,
        searchInTags: Long,
        priorityFilterType: Long,
        customPriorityMin: Long?,
        customPriorityMax: Long?,
        dueDateFilterType: Long,
        nowMillis: Long,
        todayStartMillis: Long,
        todayEndMillis: Long,
        weekEndMillis: Long,
        monthEndMillis: Long,
        customDueDateAfter: Long?,
        customDueDateBefore: Long?,
        estimatedTimeFilterType: Long,
        estimatedTimeMinSeconds: Long?,
        estimatedTimeMaxSeconds: Long?,
        recurrenceFilterType: Long,
        notificationsFilterType: Long,
        autoUpdateStatusFilterType: Long,
        groupPriorityFilterType: Long,
        groupPriorityMin: Long?,
        groupPriorityMax: Long?,
        groupDueDateFilterType: Long,
        groupDueDateMin: Long?,
        groupDueDateMax: Long?,
        groupIsRecurring: Long?,
        groupAutoUpdateStatus: Long?,
        groupHasNotifications: Long?,
        groupEstimatedTimeFilterType: Long,
        groupEstimatedTimeMinSeconds: Long?,
        groupEstimatedTimeMaxSeconds: Long?,
        groupHasConnections: Long?,
        groupStatusFilterType: Long,
        groupStatusOpen: Long,
        groupStatusInProgress: Long,
        groupStatusBlocked: Long,
        groupStatusDone: Long,
        groupStatusDeclined: Long,
        criteriaStatusFilterType: Long,
        criteriaStatusOpen: Long,
        criteriaStatusInProgress: Long,
        criteriaStatusBlocked: Long,
        criteriaStatusDone: Long,
        criteriaStatusDeclined: Long,
        connectionFilterType: Long,
        requireDependsOn: Long,
        requireIsDependencyOf: Long,
        requireRelatesTo: Long,
        requireSubtaskOf: Long,
        requireParentOf: Long,
        requireNotSubtask: Long,
    ): List<Tasks>

    /**
     * [getTasksFilteredWithGroupFilter] with the estimated time constrained to `[min, max)`.
     *
     * A separate query rather than another branch of the shared clause, because the shape is the
     * point: written as a plain conjunction the planner seeks on
     * `idx_tasks_estimatedTimeSeconds`, whereas the same bounds guarded by `:filterType = 0 OR ...`
     * compile to a scan — one plan has to serve every value the parameter might take.
     *
     * Callers pass `estimatedTimeFilterType = 0` so the guarded clause stands aside, and bounds
     * that are real numbers rather than nulls, since a comparison against null matches nothing.
     */
    @Query("SELECT t.* FROM tasks t " + FILTERED_TASKS_WHERE + ESTIMATED_TIME_RANGE)
    suspend fun getTasksFilteredInEstimatedRange(
        spaceId: String,
        searchQuery: String?,
        searchInId: Long,
        searchInTitle: Long,
        searchInDescription: Long,
        searchInTags: Long,
        priorityFilterType: Long,
        customPriorityMin: Long?,
        customPriorityMax: Long?,
        dueDateFilterType: Long,
        nowMillis: Long,
        todayStartMillis: Long,
        todayEndMillis: Long,
        weekEndMillis: Long,
        monthEndMillis: Long,
        customDueDateAfter: Long?,
        customDueDateBefore: Long?,
        estimatedTimeFilterType: Long,
        estimatedTimeMinSeconds: Long?,
        estimatedTimeMaxSeconds: Long?,
        recurrenceFilterType: Long,
        notificationsFilterType: Long,
        autoUpdateStatusFilterType: Long,
        groupPriorityFilterType: Long,
        groupPriorityMin: Long?,
        groupPriorityMax: Long?,
        groupDueDateFilterType: Long,
        groupDueDateMin: Long?,
        groupDueDateMax: Long?,
        groupIsRecurring: Long?,
        groupAutoUpdateStatus: Long?,
        groupHasNotifications: Long?,
        groupEstimatedTimeFilterType: Long,
        groupEstimatedTimeMinSeconds: Long?,
        groupEstimatedTimeMaxSeconds: Long?,
        groupHasConnections: Long?,
        groupStatusFilterType: Long,
        groupStatusOpen: Long,
        groupStatusInProgress: Long,
        groupStatusBlocked: Long,
        groupStatusDone: Long,
        groupStatusDeclined: Long,
        criteriaStatusFilterType: Long,
        criteriaStatusOpen: Long,
        criteriaStatusInProgress: Long,
        criteriaStatusBlocked: Long,
        criteriaStatusDone: Long,
        criteriaStatusDeclined: Long,
        connectionFilterType: Long,
        requireDependsOn: Long,
        requireIsDependencyOf: Long,
        requireRelatesTo: Long,
        requireSubtaskOf: Long,
        requireParentOf: Long,
        requireNotSubtask: Long,
        rangeMinSeconds: Long,
        rangeMaxSeconds: Long,
    ): List<Tasks>

    /**
     * [getTasksFilteredWithGroupFilter] with the priority constrained to `[min, max)`.
     *
     * A separate query rather than another branch of the shared clause, because the shape is the
     * point: written as a plain conjunction the planner seeks on
     * `idx_tasks_priority`, whereas the same bounds guarded by `:filterType = 0 OR ...`
     * compile to a scan — one plan has to serve every value the parameter might take.
     *
     * Callers pass `priorityFilterType = 0` so the guarded clause stands aside, and bounds
     * that are real numbers rather than nulls, since a comparison against null matches nothing.
     */
    @Query("SELECT t.* FROM tasks t " + FILTERED_TASKS_WHERE + PRIORITY_RANGE)
    suspend fun getTasksFilteredInPriorityRange(
        spaceId: String,
        searchQuery: String?,
        searchInId: Long,
        searchInTitle: Long,
        searchInDescription: Long,
        searchInTags: Long,
        priorityFilterType: Long,
        customPriorityMin: Long?,
        customPriorityMax: Long?,
        dueDateFilterType: Long,
        nowMillis: Long,
        todayStartMillis: Long,
        todayEndMillis: Long,
        weekEndMillis: Long,
        monthEndMillis: Long,
        customDueDateAfter: Long?,
        customDueDateBefore: Long?,
        estimatedTimeFilterType: Long,
        estimatedTimeMinSeconds: Long?,
        estimatedTimeMaxSeconds: Long?,
        recurrenceFilterType: Long,
        notificationsFilterType: Long,
        autoUpdateStatusFilterType: Long,
        groupPriorityFilterType: Long,
        groupPriorityMin: Long?,
        groupPriorityMax: Long?,
        groupDueDateFilterType: Long,
        groupDueDateMin: Long?,
        groupDueDateMax: Long?,
        groupIsRecurring: Long?,
        groupAutoUpdateStatus: Long?,
        groupHasNotifications: Long?,
        groupEstimatedTimeFilterType: Long,
        groupEstimatedTimeMinSeconds: Long?,
        groupEstimatedTimeMaxSeconds: Long?,
        groupHasConnections: Long?,
        groupStatusFilterType: Long,
        groupStatusOpen: Long,
        groupStatusInProgress: Long,
        groupStatusBlocked: Long,
        groupStatusDone: Long,
        groupStatusDeclined: Long,
        criteriaStatusFilterType: Long,
        criteriaStatusOpen: Long,
        criteriaStatusInProgress: Long,
        criteriaStatusBlocked: Long,
        criteriaStatusDone: Long,
        criteriaStatusDeclined: Long,
        connectionFilterType: Long,
        requireDependsOn: Long,
        requireIsDependencyOf: Long,
        requireRelatesTo: Long,
        requireSubtaskOf: Long,
        requireParentOf: Long,
        requireNotSubtask: Long,
        rangeMinPriority: Long,
        rangeMaxPriority: Long,
    ): List<Tasks>

    /**
     * [getTasksFilteredWithGroupFilter] with the due date constrained to `[min, max)`.
     *
     * A separate query rather than another branch of the shared clause, because the shape is the
     * point: written as a plain conjunction the planner seeks on
     * `idx_tasks_dueDate`, whereas the same bounds guarded by `:filterType = 0 OR ...`
     * compile to a scan — one plan has to serve every value the parameter might take.
     *
     * Callers pass `dueDateFilterType = 0` so the guarded clause stands aside, and bounds
     * that are real numbers rather than nulls, since a comparison against null matches nothing.
     */
    @Query("SELECT t.* FROM tasks t " + FILTERED_TASKS_WHERE + DUE_DATE_RANGE)
    suspend fun getTasksFilteredInDueDateRange(
        spaceId: String,
        searchQuery: String?,
        searchInId: Long,
        searchInTitle: Long,
        searchInDescription: Long,
        searchInTags: Long,
        priorityFilterType: Long,
        customPriorityMin: Long?,
        customPriorityMax: Long?,
        dueDateFilterType: Long,
        nowMillis: Long,
        todayStartMillis: Long,
        todayEndMillis: Long,
        weekEndMillis: Long,
        monthEndMillis: Long,
        customDueDateAfter: Long?,
        customDueDateBefore: Long?,
        estimatedTimeFilterType: Long,
        estimatedTimeMinSeconds: Long?,
        estimatedTimeMaxSeconds: Long?,
        recurrenceFilterType: Long,
        notificationsFilterType: Long,
        autoUpdateStatusFilterType: Long,
        groupPriorityFilterType: Long,
        groupPriorityMin: Long?,
        groupPriorityMax: Long?,
        groupDueDateFilterType: Long,
        groupDueDateMin: Long?,
        groupDueDateMax: Long?,
        groupIsRecurring: Long?,
        groupAutoUpdateStatus: Long?,
        groupHasNotifications: Long?,
        groupEstimatedTimeFilterType: Long,
        groupEstimatedTimeMinSeconds: Long?,
        groupEstimatedTimeMaxSeconds: Long?,
        groupHasConnections: Long?,
        groupStatusFilterType: Long,
        groupStatusOpen: Long,
        groupStatusInProgress: Long,
        groupStatusBlocked: Long,
        groupStatusDone: Long,
        groupStatusDeclined: Long,
        criteriaStatusFilterType: Long,
        criteriaStatusOpen: Long,
        criteriaStatusInProgress: Long,
        criteriaStatusBlocked: Long,
        criteriaStatusDone: Long,
        criteriaStatusDeclined: Long,
        connectionFilterType: Long,
        requireDependsOn: Long,
        requireIsDependencyOf: Long,
        requireRelatesTo: Long,
        requireSubtaskOf: Long,
        requireParentOf: Long,
        requireNotSubtask: Long,
        rangeMinDueDate: Long,
        rangeMaxDueDate: Long,
    ): List<Tasks>

    /** How many tasks [getTasksFilteredWithGroupFilter] would return, without reading them. */
    @Query("SELECT COUNT(*) FROM tasks t " + FILTERED_TASKS_WHERE)
    suspend fun countTasksFilteredWithGroupFilter(
        spaceId: String,
        searchQuery: String?,
        searchInId: Long,
        searchInTitle: Long,
        searchInDescription: Long,
        searchInTags: Long,
        priorityFilterType: Long,
        customPriorityMin: Long?,
        customPriorityMax: Long?,
        dueDateFilterType: Long,
        nowMillis: Long,
        todayStartMillis: Long,
        todayEndMillis: Long,
        weekEndMillis: Long,
        monthEndMillis: Long,
        customDueDateAfter: Long?,
        customDueDateBefore: Long?,
        estimatedTimeFilterType: Long,
        estimatedTimeMinSeconds: Long?,
        estimatedTimeMaxSeconds: Long?,
        recurrenceFilterType: Long,
        notificationsFilterType: Long,
        autoUpdateStatusFilterType: Long,
        groupPriorityFilterType: Long,
        groupPriorityMin: Long?,
        groupPriorityMax: Long?,
        groupDueDateFilterType: Long,
        groupDueDateMin: Long?,
        groupDueDateMax: Long?,
        groupIsRecurring: Long?,
        groupAutoUpdateStatus: Long?,
        groupHasNotifications: Long?,
        groupEstimatedTimeFilterType: Long,
        groupEstimatedTimeMinSeconds: Long?,
        groupEstimatedTimeMaxSeconds: Long?,
        groupHasConnections: Long?,
        groupStatusFilterType: Long,
        groupStatusOpen: Long,
        groupStatusInProgress: Long,
        groupStatusBlocked: Long,
        groupStatusDone: Long,
        groupStatusDeclined: Long,
        criteriaStatusFilterType: Long,
        criteriaStatusOpen: Long,
        criteriaStatusInProgress: Long,
        criteriaStatusBlocked: Long,
        criteriaStatusDone: Long,
        criteriaStatusDeclined: Long,
        connectionFilterType: Long,
        requireDependsOn: Long,
        requireIsDependencyOf: Long,
        requireRelatesTo: Long,
        requireSubtaskOf: Long,
        requireParentOf: Long,
        requireNotSubtask: Long,
    ): Int

    /** Ids of what [getTasksFilteredWithGroupFilter] would return, without its columns. */
    @Query("SELECT t.id FROM tasks t " + FILTERED_TASKS_WHERE)
    suspend fun getTaskIdsFilteredWithGroupFilter(
        spaceId: String,
        searchQuery: String?,
        searchInId: Long,
        searchInTitle: Long,
        searchInDescription: Long,
        searchInTags: Long,
        priorityFilterType: Long,
        customPriorityMin: Long?,
        customPriorityMax: Long?,
        dueDateFilterType: Long,
        nowMillis: Long,
        todayStartMillis: Long,
        todayEndMillis: Long,
        weekEndMillis: Long,
        monthEndMillis: Long,
        customDueDateAfter: Long?,
        customDueDateBefore: Long?,
        estimatedTimeFilterType: Long,
        estimatedTimeMinSeconds: Long?,
        estimatedTimeMaxSeconds: Long?,
        recurrenceFilterType: Long,
        notificationsFilterType: Long,
        autoUpdateStatusFilterType: Long,
        groupPriorityFilterType: Long,
        groupPriorityMin: Long?,
        groupPriorityMax: Long?,
        groupDueDateFilterType: Long,
        groupDueDateMin: Long?,
        groupDueDateMax: Long?,
        groupIsRecurring: Long?,
        groupAutoUpdateStatus: Long?,
        groupHasNotifications: Long?,
        groupEstimatedTimeFilterType: Long,
        groupEstimatedTimeMinSeconds: Long?,
        groupEstimatedTimeMaxSeconds: Long?,
        groupHasConnections: Long?,
        groupStatusFilterType: Long,
        groupStatusOpen: Long,
        groupStatusInProgress: Long,
        groupStatusBlocked: Long,
        groupStatusDone: Long,
        groupStatusDeclined: Long,
        criteriaStatusFilterType: Long,
        criteriaStatusOpen: Long,
        criteriaStatusInProgress: Long,
        criteriaStatusBlocked: Long,
        criteriaStatusDone: Long,
        criteriaStatusDeclined: Long,
        connectionFilterType: Long,
        requireDependsOn: Long,
        requireIsDependencyOf: Long,
        requireRelatesTo: Long,
        requireSubtaskOf: Long,
        requireParentOf: Long,
        requireNotSubtask: Long,
    ): List<String>

    /**
     * Task ids having at least one of the given tags (for the HasTags group filter).
     * Uses the normalized task_tags table for efficient lookups.
     */
    @Query(
        """
        SELECT DISTINCT t.id FROM tasks t
        INNER JOIN task_tags tt ON t.id = tt.taskId
        WHERE t.spaceId = :spaceId
          AND tt.tag IN (:tags)
        """
    )
    suspend fun getTaskIdsByTags(spaceId: String, tags: Collection<String>): List<String>

    // ============ Saved filters ============

    @Query("SELECT * FROM saved_filters WHERE spaceId = :spaceId")
    suspend fun getAllSavedFilters(spaceId: String): List<SavedFilters>

    @Query("SELECT * FROM saved_filters WHERE spaceId = :spaceId AND id = :id")
    suspend fun getSavedFilterById(spaceId: String, id: String): SavedFilters?

    @Query(
        """
        INSERT OR REPLACE INTO saved_filters(id, spaceId, name, criteriaJson, viewModeId)
        VALUES (:id, :spaceId, :name, :criteriaJson, :viewModeId)
        """
    )
    suspend fun insertOrUpdateSavedFilter(
        id: String,
        spaceId: String,
        name: String,
        criteriaJson: String,
        viewModeId: String?,
    )

    @Query("DELETE FROM saved_filters WHERE spaceId = :spaceId AND id = :id")
    suspend fun deleteSavedFilter(spaceId: String, id: String)

    // ============ Saved locations ============

    @Query("SELECT * FROM saved_locations ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllSavedLocations(): List<SavedLocations>

    @Query("SELECT * FROM saved_locations WHERE id = :id")
    suspend fun getSavedLocationById(id: String): SavedLocations?

    /**
     * Matched anywhere in the name or the address, so that searching the book by street works as
     * well as by the name it was given. The caller passes the query through `escapedForLike`, so
     * a `%` typed by the user matches a literal one.
     */
    @Query(
        """
        SELECT * FROM saved_locations
        WHERE :query = ''
           OR name LIKE '%' || :query || '%' ESCAPE '\'
           OR address LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY name COLLATE NOCASE ASC
        """
    )
    suspend fun searchSavedLocations(query: String): List<SavedLocations>

    @Query(
        """
        INSERT OR REPLACE INTO saved_locations(id, name, latitude, longitude, radiusMeters, address)
        VALUES (:id, :name, :latitude, :longitude, :radiusMeters, :address)
        """
    )
    suspend fun insertOrUpdateSavedLocation(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        address: String,
    )

    @Query("DELETE FROM saved_locations WHERE id = :id")
    suspend fun deleteSavedLocation(id: String)

    /**
     * For "clear all data", which nothing else reaches: the book hangs off no space, so deleting
     * every space — which is how the rest of the database is cleared — would leave it behind.
     */
    @Query("DELETE FROM saved_locations")
    suspend fun deleteAllSavedLocations()

    // ============ Saved signals ============

    @Query("SELECT * FROM saved_signals ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllSavedSignals(): List<SavedSignals>

    @Query("SELECT * FROM saved_signals WHERE id = :id")
    suspend fun getSavedSignalById(id: String): SavedSignals?

    /**
     * Matched on all three of the things a user might search by: the name they gave it, the name
     * the device gives itself, and the SSID or address underneath. As with the places, the caller
     * passes the query through `escapedForLike`.
     */
    @Query(
        """
        SELECT * FROM saved_signals
        WHERE :query = ''
           OR name LIKE '%' || :query || '%' ESCAPE '\'
           OR deviceName LIKE '%' || :query || '%' ESCAPE '\'
           OR value LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY name COLLATE NOCASE ASC
        """
    )
    suspend fun searchSavedSignals(query: String): List<SavedSignals>

    @Query(
        """
        INSERT OR REPLACE INTO saved_signals(id, name, kind, value, deviceName)
        VALUES (:id, :name, :kind, :value, :deviceName)
        """
    )
    suspend fun insertOrUpdateSavedSignal(
        id: String,
        name: String,
        kind: String,
        value: String,
        deviceName: String,
    )

    @Query("DELETE FROM saved_signals WHERE id = :id")
    suspend fun deleteSavedSignal(id: String)

    /** For "clear all data", which nothing else reaches — see [deleteAllSavedLocations]. */
    @Query("DELETE FROM saved_signals")
    suspend fun deleteAllSavedSignals()
}


/**
 * Everything a filtered task query selects on: the filter panel's criteria and the group
 * filters, as one clause.
 *
 * A constant rather than two copies of the text, so the query that reads the rows and the one
 * that counts them cannot drift. Kotlin folds it into each annotation at compile time.
 */
/**
 * Appended to [FILTERED_TASKS_WHERE] to make one filter an index-seekable range.
 *
 * Only one of these is ever appended. SQLite uses at most one index per table reference, so a
 * second conjunctive range would be checked row by row anyway; the caller picks whichever is
 * likeliest to narrow the search.
 */
private const val PRIORITY_RANGE = """
          AND t.priority IS NOT NULL
          AND t.priority >= :rangeMinPriority
          AND t.priority < :rangeMaxPriority
"""

/** See [PRIORITY_RANGE]. */
private const val DUE_DATE_RANGE = """
          AND t.dueDate IS NOT NULL
          AND t.dueDate >= :rangeMinDueDate
          AND t.dueDate < :rangeMaxDueDate
"""

/** See [PRIORITY_RANGE]. */
private const val ESTIMATED_TIME_RANGE = """
          AND t.estimatedTimeSeconds IS NOT NULL
          AND t.estimatedTimeSeconds >= :rangeMinSeconds
          AND t.estimatedTimeSeconds < :rangeMaxSeconds
"""

private const val FILTERED_TASKS_WHERE = """
        WHERE t.spaceId = :spaceId
          -- Text search
          AND (
            :searchQuery IS NULL
            OR :searchQuery = ''
            OR (:searchInId = 1 AND LOWER(t.id) LIKE '%' || LOWER(:searchQuery) || '%' ESCAPE '\')
            OR (:searchInTitle = 1 AND LOWER(t.title) LIKE '%' || LOWER(:searchQuery) || '%' ESCAPE '\')
            OR (:searchInDescription = 1 AND LOWER(t.description) LIKE '%' || LOWER(:searchQuery) || '%' ESCAPE '\')
            OR (:searchInTags = 1 AND LOWER(t.tagsJson) LIKE '%' || LOWER(:searchQuery) || '%' ESCAPE '\')
          )
          -- TaskFilterCriteria filters
          AND (
            :priorityFilterType = 0
            OR (:priorityFilterType = 1 AND t.priority >= 75)
            OR (:priorityFilterType = 2 AND t.priority >= 50 AND t.priority < 75)
            OR (:priorityFilterType = 3 AND t.priority >= 1 AND t.priority < 50)
            OR (:priorityFilterType = 4 AND t.priority IS NULL)
            OR (:priorityFilterType = 5 AND (t.priority IS NOT NULL AND (:customPriorityMin IS NULL OR t.priority >= :customPriorityMin) AND (:customPriorityMax IS NULL OR t.priority <= :customPriorityMax)))
          )
          AND (
            :dueDateFilterType = 0
            OR (:dueDateFilterType = 1 AND t.dueDate IS NOT NULL AND t.dueDate < :nowMillis)
            OR (:dueDateFilterType = 2 AND t.dueDate IS NOT NULL AND t.dueDate >= :todayStartMillis AND t.dueDate < :todayEndMillis)
            OR (:dueDateFilterType = 3 AND t.dueDate IS NOT NULL AND t.dueDate >= :todayStartMillis AND t.dueDate < :weekEndMillis)
            OR (:dueDateFilterType = 4 AND t.dueDate IS NOT NULL AND t.dueDate >= :todayStartMillis AND t.dueDate < :monthEndMillis)
            OR (:dueDateFilterType = 5 AND t.dueDate IS NULL)
            -- Custom. A task with no due date is outside any range, including an open one:
            -- without IS NOT NULL, leaving both bounds empty matched every task instead.
            OR (:dueDateFilterType = 6 AND t.dueDate IS NOT NULL
              AND (:customDueDateAfter IS NULL OR t.dueDate >= :customDueDateAfter)
              AND (:customDueDateBefore IS NULL OR t.dueDate <= :customDueDateBefore))
          )
          -- 1 = no estimate, 2 = bucket [min, max), 3 = custom [min, max]; bounds are optional.
          AND (
            :estimatedTimeFilterType = 0
            OR (:estimatedTimeFilterType = 1 AND t.estimatedTimeSeconds IS NULL)
            OR (:estimatedTimeFilterType = 2 AND t.estimatedTimeSeconds IS NOT NULL
              AND (:estimatedTimeMinSeconds IS NULL OR t.estimatedTimeSeconds >= :estimatedTimeMinSeconds)
              AND (:estimatedTimeMaxSeconds IS NULL OR t.estimatedTimeSeconds < :estimatedTimeMaxSeconds))
            OR (:estimatedTimeFilterType = 3 AND t.estimatedTimeSeconds IS NOT NULL
              AND (:estimatedTimeMinSeconds IS NULL OR t.estimatedTimeSeconds >= :estimatedTimeMinSeconds)
              AND (:estimatedTimeMaxSeconds IS NULL OR t.estimatedTimeSeconds <= :estimatedTimeMaxSeconds))
          )
          -- SQL only separates "recurs" from "does not"; which kind of rule lives inside
          -- recurrenceRulesJson and is refined in Kotlin by RecurrenceFilter.matches.
          AND (
            :recurrenceFilterType = 0
            OR (:recurrenceFilterType = 1 AND t.isRecurring = 0)
            OR (:recurrenceFilterType = 2 AND t.isRecurring = 1)
          )
          AND (
            :notificationsFilterType = 0
            OR (:notificationsFilterType = 1 AND t.notificationsJson = '[]')
            OR (:notificationsFilterType = 2 AND t.notificationsJson != '[]')
          )
          AND (
            :autoUpdateStatusFilterType = 0
            OR (:autoUpdateStatusFilterType = 1 AND t.autoUpdateStatusFromSubtasks = 1)
            OR (:autoUpdateStatusFilterType = 2 AND t.autoUpdateStatusFromSubtasks = 0)
          )
          -- Group filter: Priority range
          AND (
            :groupPriorityFilterType = 0 -- Not applied
            OR (:groupPriorityFilterType = 1 AND t.priority IS NULL) -- Include null only
            OR (:groupPriorityFilterType = 2 AND t.priority IS NOT NULL AND (:groupPriorityMin IS NULL OR t.priority >= :groupPriorityMin) AND (:groupPriorityMax IS NULL OR t.priority <= :groupPriorityMax)) -- Range only
            OR (:groupPriorityFilterType = 3 AND (t.priority IS NULL OR ((:groupPriorityMin IS NULL OR t.priority >= :groupPriorityMin) AND (:groupPriorityMax IS NULL OR t.priority <= :groupPriorityMax)))) -- Null + Range
          )
          -- Group filter: Due date range (days from today)
          AND (
            :groupDueDateFilterType = 0 -- Not applied
            OR (:groupDueDateFilterType = 1 AND t.dueDate IS NULL) -- Include null only
            OR (:groupDueDateFilterType = 2 AND t.dueDate IS NOT NULL AND (:groupDueDateMin IS NULL OR t.dueDate >= :groupDueDateMin) AND (:groupDueDateMax IS NULL OR t.dueDate <= :groupDueDateMax)) -- Range only
            OR (:groupDueDateFilterType = 3 AND (t.dueDate IS NULL OR ((:groupDueDateMin IS NULL OR t.dueDate >= :groupDueDateMin) AND (:groupDueDateMax IS NULL OR t.dueDate <= :groupDueDateMax)))) -- Null + Range
          )
          -- Group filter: Boolean fields
          AND (:groupIsRecurring IS NULL OR t.isRecurring = :groupIsRecurring)
          AND (:groupAutoUpdateStatus IS NULL OR t.autoUpdateStatusFromSubtasks = :groupAutoUpdateStatus)
          AND (:groupHasNotifications IS NULL OR ((:groupHasNotifications = 1 AND t.notificationsJson != '[]') OR (:groupHasNotifications = 0 AND t.notificationsJson = '[]')))
          -- Group filter: Estimated time range (in seconds)
          -- RecurrencePeriod JSON has fields: years, months, weeks, days, hours, minutes, seconds
          AND (
            :groupEstimatedTimeFilterType = 0 -- Not applied
            OR (:groupEstimatedTimeFilterType = 1 AND t.estimatedTimeSeconds IS NULL) -- Include null only
            OR (:groupEstimatedTimeFilterType = 2 AND t.estimatedTimeSeconds IS NOT NULL AND (
              :groupEstimatedTimeMinSeconds IS NULL OR t.estimatedTimeSeconds >= :groupEstimatedTimeMinSeconds
            ) AND (
              :groupEstimatedTimeMaxSeconds IS NULL OR t.estimatedTimeSeconds <= :groupEstimatedTimeMaxSeconds
            )) -- Range only
            OR (:groupEstimatedTimeFilterType = 3 AND (t.estimatedTimeSeconds IS NULL OR (
              (:groupEstimatedTimeMinSeconds IS NULL OR t.estimatedTimeSeconds >= :groupEstimatedTimeMinSeconds) AND
              (:groupEstimatedTimeMaxSeconds IS NULL OR t.estimatedTimeSeconds <= :groupEstimatedTimeMaxSeconds)
            ))) -- Null + Range
          )
          -- Group filter: Has connections
          AND (
            :groupHasConnections IS NULL
            OR (:groupHasConnections = 1 AND EXISTS (SELECT 1 FROM task_connections WHERE sourceTaskId = t.id))
            OR (:groupHasConnections = 0 AND NOT EXISTS (SELECT 1 FROM task_connections WHERE sourceTaskId = t.id))
          )
          -- Group filter: Status values (multiple patterns with OR, up to 5 status types)
          --
          -- Status is serialized as {"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"},
          -- with the class discriminator always first, so these match on the leading text and the
          -- closing quote. Matching the bare name anywhere in the column also matched it inside
          -- the free text a Blocked comment or a Declined reason carries: declining a task with
          -- the words "TaskStatus.Done" in the reason listed it under Done.
          AND (
            :groupStatusFilterType = 0 -- Not applied
            OR (
              (:groupStatusOpen = 1 AND t.status LIKE '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"%')
              OR (:groupStatusInProgress = 1 AND t.status LIKE '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.InProgress"%')
              OR (:groupStatusBlocked = 1 AND t.status LIKE '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Blocked"%')
              OR (:groupStatusDone = 1 AND t.status LIKE '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Done"%')
              OR (:groupStatusDeclined = 1 AND t.status LIKE '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Declined"%')
            )
          )
          -- TaskFilterCriteria: Status filters (additional filtering by status types)
          AND (
            :criteriaStatusFilterType = 0
            OR (
              (:criteriaStatusOpen = 1 AND t.status LIKE '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"%')
              OR (:criteriaStatusInProgress = 1 AND t.status LIKE '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.InProgress"%')
              OR (:criteriaStatusBlocked = 1 AND t.status LIKE '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Blocked"%')
              OR (:criteriaStatusDone = 1 AND t.status LIKE '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Done"%')
              OR (:criteriaStatusDeclined = 1 AND t.status LIKE '{"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Declined"%')
            )
          )
          -- TaskFilterCriteria: Connection type filters.
          -- The chips are a multi-select, and the shared Kotlin predicate matches a task that has
          -- ANY of the ticked kinds. Requiring all of them at once made two ticks narrower than
          -- one, and "Subtask of" together with "Not a subtask" unsatisfiable — an empty board.
          AND (
            :connectionFilterType = 0 -- Not applied
            OR (
              (:requireDependsOn = 1 AND EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'DependsOn'))
              OR (:requireIsDependencyOf = 1 AND EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'IsDependencyOf'))
              OR (:requireRelatesTo = 1 AND EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'RelatesTo'))
              OR (:requireSubtaskOf = 1 AND EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'SubtaskOf'))
              OR (:requireParentOf = 1 AND EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'ParentOf'))
              OR (:requireNotSubtask = 1 AND NOT EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'SubtaskOf'))
            )
          )
"""
