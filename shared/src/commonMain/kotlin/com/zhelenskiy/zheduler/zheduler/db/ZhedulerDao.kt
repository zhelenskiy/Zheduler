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

    @Query(
        """
        SELECT * FROM spaces
        WHERE (:searchInName = 1 AND LOWER(name) LIKE '%' || LOWER(:query) || '%')
           OR (:searchInPrefix = 1 AND LOWER(idPrefix) LIKE '%' || LOWER(:query) || '%')
        """
    )
    suspend fun filterSpaces(searchInName: Long, query: String, searchInPrefix: Long): List<Spaces>

    // ============ Task queries ============

    @Query("SELECT EXISTS(SELECT 1 FROM tasks WHERE spaceId = :spaceId)")
    suspend fun hasAnyTasks(spaceId: String): Boolean

    @Query("SELECT * FROM tasks WHERE spaceId = :spaceId")
    suspend fun getTasksBySpace(spaceId: String): List<Tasks>

    @Query("SELECT * FROM tasks WHERE spaceId = :spaceId AND id != :id")
    suspend fun getTasksBySpaceExcept(spaceId: String, id: String): List<Tasks>

    @Query(
        """
        SELECT * FROM tasks
        WHERE spaceId = :spaceId AND (:id IS NULL OR id != :id)
          AND (:searchQuery = '' OR LOWER(id) LIKE '%' || LOWER(:searchQuery) || '%' OR LOWER(title) LIKE '%' || LOWER(:searchQuery) || '%')
        """
    )
    suspend fun searchTasksForConnection(spaceId: String, id: String?, searchQuery: String): List<Tasks>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskById(id: String): Tasks?

    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun getTasksByIds(ids: Collection<String>): List<Tasks>

    @Query(
        """
        INSERT INTO tasks(id, title, description, status, dueDate, priority, estimatedTimeJson,
                           tagsJson, notificationsJson, spaceId, recurrenceRulesJson, autoUpdateStatusFromSubtasks,
                           isRecurring, isBlocked)
        VALUES (:id, :title, :description, :status, :dueDate, :priority, :estimatedTimeJson,
                :tagsJson, :notificationsJson, :spaceId, :recurrenceRulesJson, :autoUpdateStatusFromSubtasks,
                :isRecurring, :isBlocked)
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
            isBlocked = :isBlocked
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

    // ============ Connection queries ============

    @Query("SELECT * FROM task_connections WHERE sourceTaskId = :sourceTaskId")
    suspend fun getConnectionsForTask(sourceTaskId: String): List<TaskConnections>

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

    @Query("SELECT name FROM tags WHERE spaceId = :spaceId")
    suspend fun getAllTagsForSpace(spaceId: String): List<String>

    @Query(
        """
        SELECT name FROM tags
        WHERE spaceId = :spaceId
          AND (:searchQuery = '' OR LOWER(name) LIKE '%' || LOWER(:searchQuery) || '%')
        ORDER BY name ASC
        """
    )
    suspend fun filterTagsForSpace(spaceId: String, searchQuery: String): List<String>

    @Query("INSERT OR IGNORE INTO tags(spaceId, name) VALUES (:spaceId, :name)")
    suspend fun insertTagForSpace(spaceId: String, name: String)

    @Query("DELETE FROM tags WHERE spaceId = :spaceId AND name = :name")
    suspend fun deleteTagForSpace(spaceId: String, name: String)

    // ============ Task tag queries (normalized task_tags table) ============

    @Query("INSERT OR IGNORE INTO task_tags(taskId, tag) VALUES (:taskId, :tag)")
    suspend fun insertTaskTag(taskId: String, tag: String)

    @Query("DELETE FROM task_tags WHERE taskId = :taskId")
    suspend fun deleteTaskTags(taskId: String)

    @Query("SELECT tag FROM task_tags WHERE taskId = :taskId")
    suspend fun getTagsForTask(taskId: String): List<String>

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

    /** Count tasks by status in a space. */
    @Query("SELECT status, COUNT(*) AS count FROM tasks WHERE spaceId = :spaceId GROUP BY status")
    suspend fun countTasksByStatus(spaceId: String): List<StatusCount>

    /** Count tasks by priority range (null, low: 0-49, medium: 50-74, high: 75-100). */
    @Query(
        """
        SELECT
            CASE
                WHEN priority IS NULL THEN 'null'
                WHEN priority < 50 THEN 'low'
                WHEN priority < 75 THEN 'medium'
                ELSE 'high'
            END AS priorityRange,
            COUNT(*) AS count
        FROM tasks
        WHERE spaceId = :spaceId
        GROUP BY priorityRange
        """
    )
    suspend fun countTasksByPriorityRange(spaceId: String): List<PriorityRangeCount>

    /** Count tasks by whether they have a due date. */
    @Query(
        """
        SELECT
            CASE WHEN dueDate IS NULL THEN 0 ELSE 1 END AS hasDueDate,
            COUNT(*) AS count
        FROM tasks
        WHERE spaceId = :spaceId
        GROUP BY hasDueDate
        """
    )
    suspend fun countTasksByHasDueDate(spaceId: String): List<HasDueDateCount>

    @Query("SELECT isRecurring, COUNT(*) AS count FROM tasks WHERE spaceId = :spaceId GROUP BY isRecurring")
    suspend fun countTasksByIsRecurring(spaceId: String): List<IsRecurringCount>

    @Query("SELECT isBlocked, COUNT(*) AS count FROM tasks WHERE spaceId = :spaceId GROUP BY isBlocked")
    suspend fun countTasksByIsBlocked(spaceId: String): List<IsBlockedCount>

    @Query(
        """
        SELECT autoUpdateStatusFromSubtasks, COUNT(*) AS count FROM tasks
        WHERE spaceId = :spaceId GROUP BY autoUpdateStatusFromSubtasks
        """
    )
    suspend fun countTasksByAutoUpdateStatus(spaceId: String): List<AutoUpdateStatusCount>

    /** Count tasks that have connections. */
    @Query(
        """
        SELECT
            CASE WHEN EXISTS (SELECT 1 FROM task_connections WHERE sourceTaskId = t.id) THEN 1 ELSE 0 END AS hasConnections,
            COUNT(*) AS count
        FROM tasks t
        WHERE t.spaceId = :spaceId
        GROUP BY hasConnections
        """
    )
    suspend fun countTasksByHasConnections(spaceId: String): List<HasConnectionsCount>

    /** Count tasks that have notifications (non-empty JSON array). */
    @Query(
        """
        SELECT
            CASE WHEN notificationsJson = '[]' THEN 0 ELSE 1 END AS hasNotifications,
            COUNT(*) AS count
        FROM tasks
        WHERE spaceId = :spaceId
        GROUP BY hasNotifications
        """
    )
    suspend fun countTasksByHasNotifications(spaceId: String): List<HasNotificationsCount>

    /** Tasks filtered by exact status values (for simple statuses like Open, InProgress, Done). */
    @Query("SELECT * FROM tasks WHERE spaceId = :spaceId AND status IN (:statusValues)")
    suspend fun getTasksByStatusValues(spaceId: String, statusValues: Collection<String>): List<Tasks>

    /** Tasks filtered by status type prefix (for statuses with parameters like Blocked, Declined). */
    @Query("SELECT * FROM tasks WHERE spaceId = :spaceId AND status LIKE :statusPrefix")
    suspend fun getTasksByStatusPrefix(spaceId: String, statusPrefix: String): List<Tasks>

    @Query(
        """
        SELECT * FROM tasks
        WHERE spaceId = :spaceId
          AND (
            (:includeNull = 1 AND priority IS NULL)
            OR (priority IS NOT NULL AND (:minPriority IS NULL OR priority >= :minPriority) AND (:maxPriority IS NULL OR priority <= :maxPriority))
          )
        """
    )
    suspend fun getTasksByPriorityRange(
        spaceId: String,
        includeNull: Long,
        minPriority: Long?,
        maxPriority: Long?,
    ): List<Tasks>

    /** Tasks filtered by due date range (using epoch milliseconds). */
    @Query(
        """
        SELECT * FROM tasks
        WHERE spaceId = :spaceId
          AND (
            (:includeNull = 1 AND dueDate IS NULL)
            OR (dueDate IS NOT NULL AND (:minDueDate IS NULL OR dueDate >= :minDueDate) AND (:maxDueDate IS NULL OR dueDate <= :maxDueDate))
          )
        """
    )
    suspend fun getTasksByDueDateRange(
        spaceId: String,
        includeNull: Long,
        minDueDate: Long?,
        maxDueDate: Long?,
    ): List<Tasks>

    @Query("SELECT * FROM tasks WHERE spaceId = :spaceId AND isRecurring = :value")
    suspend fun getTasksByIsRecurring(spaceId: String, value: Long): List<Tasks>

    @Query("SELECT * FROM tasks WHERE spaceId = :spaceId AND autoUpdateStatusFromSubtasks = :value")
    suspend fun getTasksByAutoUpdateStatus(spaceId: String, value: Long): List<Tasks>

    @Query(
        """
        SELECT t.* FROM tasks t
        WHERE t.spaceId = :spaceId AND EXISTS (SELECT 1 FROM task_connections WHERE sourceTaskId = t.id)
        """
    )
    suspend fun getTasksWithConnections(spaceId: String): List<Tasks>

    @Query(
        """
        SELECT t.* FROM tasks t
        WHERE t.spaceId = :spaceId AND NOT EXISTS (SELECT 1 FROM task_connections WHERE sourceTaskId = t.id)
        """
    )
    suspend fun getTasksWithoutConnections(spaceId: String): List<Tasks>

    @Query("SELECT * FROM tasks WHERE spaceId = :spaceId AND notificationsJson != '[]'")
    suspend fun getTasksWithNotifications(spaceId: String): List<Tasks>

    @Query("SELECT * FROM tasks WHERE spaceId = :spaceId AND notificationsJson = '[]'")
    suspend fun getTasksWithoutNotifications(spaceId: String): List<Tasks>

    // ============ Comprehensive filtered task query ============
    // Handles all TaskFilterCriteria options in SQL. Parameters are nullable; when NULL, that
    // filter is not applied.

    @Query(
        """
        SELECT t.* FROM tasks t
        WHERE t.spaceId = :spaceId
          -- Text search (OR across selected fields)
          AND (
            :searchQuery IS NULL
            OR :searchQuery = ''
            OR (:searchInId = 1 AND LOWER(t.id) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInTitle = 1 AND LOWER(t.title) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInDescription = 1 AND LOWER(t.description) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInTags = 1 AND LOWER(t.tagsJson) LIKE '%' || LOWER(:searchQuery) || '%')
          )
          -- Priority filter
          AND (
            :priorityFilterType = 0 -- Any
            OR (:priorityFilterType = 1 AND t.priority >= 75) -- High
            OR (:priorityFilterType = 2 AND t.priority >= 50 AND t.priority < 75) -- Medium
            OR (:priorityFilterType = 3 AND t.priority >= 1 AND t.priority < 50) -- Low
            OR (:priorityFilterType = 4 AND t.priority IS NULL) -- NoPriority
            OR (:priorityFilterType = 5 AND ( -- Custom
              (t.priority IS NOT NULL AND (:customPriorityMin IS NULL OR t.priority >= :customPriorityMin) AND (:customPriorityMax IS NULL OR t.priority <= :customPriorityMax))
            ))
          )
          -- Due date filter
          AND (
            :dueDateFilterType = 0 -- Any
            OR (:dueDateFilterType = 1 AND t.dueDate IS NOT NULL AND t.dueDate < :nowMillis) -- Overdue
            OR (:dueDateFilterType = 2 AND t.dueDate IS NOT NULL AND t.dueDate >= :todayStartMillis AND t.dueDate < :todayEndMillis) -- Today
            OR (:dueDateFilterType = 3 AND t.dueDate IS NOT NULL AND t.dueDate >= :todayStartMillis AND t.dueDate < :weekEndMillis) -- ThisWeek
            OR (:dueDateFilterType = 4 AND t.dueDate IS NOT NULL AND t.dueDate >= :todayStartMillis AND t.dueDate < :monthEndMillis) -- ThisMonth
            OR (:dueDateFilterType = 5 AND t.dueDate IS NULL) -- NoDueDate
            OR (:dueDateFilterType = 6 AND ( -- Custom
              (:customDueDateAfter IS NULL OR t.dueDate >= :customDueDateAfter)
              AND (:customDueDateBefore IS NULL OR t.dueDate <= :customDueDateBefore)
            ))
          )
          -- Estimated time filter (stored as JSON)
          AND (
            :estimatedTimeFilterType = 0 -- Any
            OR (:estimatedTimeFilterType = 1 AND t.estimatedTimeJson IS NULL) -- NoEstimate
            OR (:estimatedTimeFilterType = 2 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMaxMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) < :estimatedTimeMaxMinutes)
            OR (:estimatedTimeFilterType = 3 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMinMinutes IS NOT NULL AND :estimatedTimeMaxMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) >= :estimatedTimeMinMinutes AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) < :estimatedTimeMaxMinutes)
            OR (:estimatedTimeFilterType = 4 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMinMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) >= :estimatedTimeMinMinutes)
          )
          -- Recurrence filter
          AND (
            :recurrenceFilterType = 0 -- Any
            OR (:recurrenceFilterType = 1 AND t.isRecurring = 0) -- NoRecurrence
            OR (:recurrenceFilterType = 2 AND t.isRecurring = 1) -- HasRecurrence
          )
          -- Notifications filter
          AND (
            :notificationsFilterType = 0 -- Any
            OR (:notificationsFilterType = 1 AND t.notificationsJson = '[]') -- NoNotifications
            OR (:notificationsFilterType = 2 AND t.notificationsJson != '[]') -- HasNotifications
          )
          -- Auto update status filter
          AND (
            :autoUpdateStatusFilterType = 0 -- Any
            OR (:autoUpdateStatusFilterType = 1 AND t.autoUpdateStatusFromSubtasks = 1) -- Auto
            OR (:autoUpdateStatusFilterType = 2 AND t.autoUpdateStatusFromSubtasks = 0) -- Manual
          )
        """
    )
    suspend fun getTasksFiltered(
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
        estimatedTimeMinMinutes: Long?,
        estimatedTimeMaxMinutes: Long?,
        recurrenceFilterType: Long,
        notificationsFilterType: Long,
        autoUpdateStatusFilterType: Long,
    ): List<Tasks>

    /** Count tasks by status with all filters applied. */
    @Query(
        """
        SELECT t.status, COUNT(*) AS count FROM tasks t
        WHERE t.spaceId = :spaceId
          AND (
            :searchQuery IS NULL
            OR :searchQuery = ''
            OR (:searchInId = 1 AND LOWER(t.id) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInTitle = 1 AND LOWER(t.title) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInDescription = 1 AND LOWER(t.description) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInTags = 1 AND LOWER(t.tagsJson) LIKE '%' || LOWER(:searchQuery) || '%')
          )
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
            OR (:dueDateFilterType = 6 AND ((:customDueDateAfter IS NULL OR t.dueDate >= :customDueDateAfter) AND (:customDueDateBefore IS NULL OR t.dueDate <= :customDueDateBefore)))
          )
          AND (
            :estimatedTimeFilterType = 0
            OR (:estimatedTimeFilterType = 1 AND t.estimatedTimeJson IS NULL)
            OR (:estimatedTimeFilterType = 2 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMaxMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) < :estimatedTimeMaxMinutes)
            OR (:estimatedTimeFilterType = 3 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMinMinutes IS NOT NULL AND :estimatedTimeMaxMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) >= :estimatedTimeMinMinutes AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) < :estimatedTimeMaxMinutes)
            OR (:estimatedTimeFilterType = 4 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMinMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) >= :estimatedTimeMinMinutes)
          )
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
        GROUP BY t.status
        """
    )
    suspend fun countTasksByStatusFiltered(
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
        estimatedTimeMinMinutes: Long?,
        estimatedTimeMaxMinutes: Long?,
        recurrenceFilterType: Long,
        notificationsFilterType: Long,
        autoUpdateStatusFilterType: Long,
    ): List<StatusCount>

    /** Count tasks by priority range with all filters applied. */
    @Query(
        """
        SELECT
            CASE
                WHEN t.priority IS NULL THEN 'null'
                WHEN t.priority < 50 THEN 'low'
                WHEN t.priority < 75 THEN 'medium'
                ELSE 'high'
            END AS priorityRange,
            COUNT(*) AS count
        FROM tasks t
        WHERE t.spaceId = :spaceId
          AND (
            :searchQuery IS NULL
            OR :searchQuery = ''
            OR (:searchInId = 1 AND LOWER(t.id) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInTitle = 1 AND LOWER(t.title) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInDescription = 1 AND LOWER(t.description) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInTags = 1 AND LOWER(t.tagsJson) LIKE '%' || LOWER(:searchQuery) || '%')
          )
          AND (
            :dueDateFilterType = 0
            OR (:dueDateFilterType = 1 AND t.dueDate IS NOT NULL AND t.dueDate < :nowMillis)
            OR (:dueDateFilterType = 2 AND t.dueDate IS NOT NULL AND t.dueDate >= :todayStartMillis AND t.dueDate < :todayEndMillis)
            OR (:dueDateFilterType = 3 AND t.dueDate IS NOT NULL AND t.dueDate >= :todayStartMillis AND t.dueDate < :weekEndMillis)
            OR (:dueDateFilterType = 4 AND t.dueDate IS NOT NULL AND t.dueDate >= :todayStartMillis AND t.dueDate < :monthEndMillis)
            OR (:dueDateFilterType = 5 AND t.dueDate IS NULL)
            OR (:dueDateFilterType = 6 AND ((:customDueDateAfter IS NULL OR t.dueDate >= :customDueDateAfter) AND (:customDueDateBefore IS NULL OR t.dueDate <= :customDueDateBefore)))
          )
          AND (
            :estimatedTimeFilterType = 0
            OR (:estimatedTimeFilterType = 1 AND t.estimatedTimeJson IS NULL)
            OR (:estimatedTimeFilterType = 2 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMaxMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) < :estimatedTimeMaxMinutes)
            OR (:estimatedTimeFilterType = 3 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMinMinutes IS NOT NULL AND :estimatedTimeMaxMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) >= :estimatedTimeMinMinutes AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) < :estimatedTimeMaxMinutes)
            OR (:estimatedTimeFilterType = 4 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMinMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) >= :estimatedTimeMinMinutes)
          )
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
        GROUP BY priorityRange
        """
    )
    suspend fun countTasksByPriorityRangeFiltered(
        spaceId: String,
        searchQuery: String?,
        searchInId: Long,
        searchInTitle: Long,
        searchInDescription: Long,
        searchInTags: Long,
        dueDateFilterType: Long,
        nowMillis: Long,
        todayStartMillis: Long,
        todayEndMillis: Long,
        weekEndMillis: Long,
        monthEndMillis: Long,
        customDueDateAfter: Long?,
        customDueDateBefore: Long?,
        estimatedTimeFilterType: Long,
        estimatedTimeMinMinutes: Long?,
        estimatedTimeMaxMinutes: Long?,
        recurrenceFilterType: Long,
        notificationsFilterType: Long,
        autoUpdateStatusFilterType: Long,
    ): List<PriorityRangeCount>

    /** Filtered tasks matching group filter conditions. */
    @Query(
        """
        SELECT t.* FROM tasks t
        WHERE t.spaceId = :spaceId
          -- Text search
          AND (
            :searchQuery IS NULL
            OR :searchQuery = ''
            OR (:searchInId = 1 AND LOWER(t.id) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInTitle = 1 AND LOWER(t.title) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInDescription = 1 AND LOWER(t.description) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInTags = 1 AND LOWER(t.tagsJson) LIKE '%' || LOWER(:searchQuery) || '%')
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
            OR (:dueDateFilterType = 6 AND ((:customDueDateAfter IS NULL OR t.dueDate >= :customDueDateAfter) AND (:customDueDateBefore IS NULL OR t.dueDate <= :customDueDateBefore)))
          )
          AND (
            :estimatedTimeFilterType = 0
            OR (:estimatedTimeFilterType = 1 AND t.estimatedTimeJson IS NULL)
            OR (:estimatedTimeFilterType = 2 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMaxMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) < :estimatedTimeMaxMinutes)
            OR (:estimatedTimeFilterType = 3 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMinMinutes IS NOT NULL AND :estimatedTimeMaxMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) >= :estimatedTimeMinMinutes AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) < :estimatedTimeMaxMinutes)
            OR (:estimatedTimeFilterType = 4 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMinMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) >= :estimatedTimeMinMinutes)
          )
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
            OR (:groupEstimatedTimeFilterType = 1 AND t.estimatedTimeJson IS NULL) -- Include null only
            OR (:groupEstimatedTimeFilterType = 2 AND t.estimatedTimeJson IS NOT NULL AND (
              :groupEstimatedTimeMinSeconds IS NULL OR (
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.years') AS INTEGER), 0) * 365 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.months') AS INTEGER), 0) * 30 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.weeks') AS INTEGER), 0) * 7 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.days') AS INTEGER), 0) * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.hours') AS INTEGER), 0) * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.minutes') AS INTEGER), 0) * 60 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.seconds') AS INTEGER), 0)
              ) >= :groupEstimatedTimeMinSeconds
            ) AND (
              :groupEstimatedTimeMaxSeconds IS NULL OR (
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.years') AS INTEGER), 0) * 365 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.months') AS INTEGER), 0) * 30 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.weeks') AS INTEGER), 0) * 7 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.days') AS INTEGER), 0) * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.hours') AS INTEGER), 0) * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.minutes') AS INTEGER), 0) * 60 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.seconds') AS INTEGER), 0)
              ) <= :groupEstimatedTimeMaxSeconds
            )) -- Range only
            OR (:groupEstimatedTimeFilterType = 3 AND (t.estimatedTimeJson IS NULL OR (
              (:groupEstimatedTimeMinSeconds IS NULL OR (
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.years') AS INTEGER), 0) * 365 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.months') AS INTEGER), 0) * 30 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.weeks') AS INTEGER), 0) * 7 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.days') AS INTEGER), 0) * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.hours') AS INTEGER), 0) * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.minutes') AS INTEGER), 0) * 60 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.seconds') AS INTEGER), 0)
              ) >= :groupEstimatedTimeMinSeconds) AND
              (:groupEstimatedTimeMaxSeconds IS NULL OR (
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.years') AS INTEGER), 0) * 365 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.months') AS INTEGER), 0) * 30 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.weeks') AS INTEGER), 0) * 7 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.days') AS INTEGER), 0) * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.hours') AS INTEGER), 0) * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.minutes') AS INTEGER), 0) * 60 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.seconds') AS INTEGER), 0)
              ) <= :groupEstimatedTimeMaxSeconds)
            ))) -- Null + Range
          )
          -- Group filter: Has connections
          AND (
            :groupHasConnections IS NULL
            OR (:groupHasConnections = 1 AND EXISTS (SELECT 1 FROM task_connections WHERE sourceTaskId = t.id))
            OR (:groupHasConnections = 0 AND NOT EXISTS (SELECT 1 FROM task_connections WHERE sourceTaskId = t.id))
          )
          -- Group filter: Status values (multiple patterns with OR, up to 5 status types)
          -- Status is serialized as {"type":"com.zhelenskiy.zheduler.zheduler.TaskStatus.Open"} etc.
          AND (
            :groupStatusFilterType = 0 -- Not applied
            OR (
              (:groupStatusOpen = 1 AND t.status LIKE '%TaskStatus.Open%')
              OR (:groupStatusInProgress = 1 AND t.status LIKE '%TaskStatus.InProgress%')
              OR (:groupStatusBlocked = 1 AND t.status LIKE '%TaskStatus.Blocked%')
              OR (:groupStatusDone = 1 AND t.status LIKE '%TaskStatus.Done%')
              OR (:groupStatusDeclined = 1 AND t.status LIKE '%TaskStatus.Declined%')
            )
          )
          -- TaskFilterCriteria: Status filters (additional filtering by status types)
          AND (
            :criteriaStatusFilterType = 0
            OR (
              (:criteriaStatusOpen = 1 AND t.status LIKE '%TaskStatus.Open%')
              OR (:criteriaStatusInProgress = 1 AND t.status LIKE '%TaskStatus.InProgress%')
              OR (:criteriaStatusBlocked = 1 AND t.status LIKE '%TaskStatus.Blocked%')
              OR (:criteriaStatusDone = 1 AND t.status LIKE '%TaskStatus.Done%')
              OR (:criteriaStatusDeclined = 1 AND t.status LIKE '%TaskStatus.Declined%')
            )
          )
          -- TaskFilterCriteria: Connection type filters
          AND (
            :connectionFilterType = 0 -- Not applied
            OR (
              (:requireDependsOn = 0 OR EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'DependsOn'))
              AND (:requireIsDependencyOf = 0 OR EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'IsDependencyOf'))
              AND (:requireRelatesTo = 0 OR EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'RelatesTo'))
              AND (:requireSubtaskOf = 0 OR EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'SubtaskOf'))
              AND (:requireParentOf = 0 OR EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'ParentOf'))
              AND (:requireNotSubtask = 0 OR NOT EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'SubtaskOf'))
            )
          )
        """
    )
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
        estimatedTimeMinMinutes: Long?,
        estimatedTimeMaxMinutes: Long?,
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

    /** Count tasks matching comprehensive filters (for efficient group counting). */
    @Query(
        """
        SELECT COUNT(*) FROM tasks t
        WHERE t.spaceId = :spaceId
          AND (
            :searchQuery IS NULL
            OR :searchQuery = ''
            OR (:searchInId = 1 AND LOWER(t.id) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInTitle = 1 AND LOWER(t.title) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInDescription = 1 AND LOWER(t.description) LIKE '%' || LOWER(:searchQuery) || '%')
            OR (:searchInTags = 1 AND LOWER(t.tagsJson) LIKE '%' || LOWER(:searchQuery) || '%')
          )
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
            OR (:dueDateFilterType = 6 AND ((:customDueDateAfter IS NULL OR t.dueDate >= :customDueDateAfter) AND (:customDueDateBefore IS NULL OR t.dueDate <= :customDueDateBefore)))
          )
          AND (
            :estimatedTimeFilterType = 0
            OR (:estimatedTimeFilterType = 1 AND t.estimatedTimeJson IS NULL)
            OR (:estimatedTimeFilterType = 2 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMaxMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) < :estimatedTimeMaxMinutes)
            OR (:estimatedTimeFilterType = 3 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMinMinutes IS NOT NULL AND :estimatedTimeMaxMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) >= :estimatedTimeMinMinutes AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) < :estimatedTimeMaxMinutes)
            OR (:estimatedTimeFilterType = 4 AND t.estimatedTimeJson IS NOT NULL AND :estimatedTimeMinMinutes IS NOT NULL AND CAST(json_extract(t.estimatedTimeJson, '$.totalMinutes') AS INTEGER) >= :estimatedTimeMinMinutes)
          )
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
          -- Group filters
          AND (
            :groupPriorityFilterType = 0
            OR (:groupPriorityFilterType = 1 AND t.priority IS NULL)
            OR (:groupPriorityFilterType = 2 AND t.priority IS NOT NULL AND (:groupPriorityMin IS NULL OR t.priority >= :groupPriorityMin) AND (:groupPriorityMax IS NULL OR t.priority <= :groupPriorityMax))
            OR (:groupPriorityFilterType = 3 AND (t.priority IS NULL OR ((:groupPriorityMin IS NULL OR t.priority >= :groupPriorityMin) AND (:groupPriorityMax IS NULL OR t.priority <= :groupPriorityMax))))
          )
          AND (
            :groupDueDateFilterType = 0
            OR (:groupDueDateFilterType = 1 AND t.dueDate IS NULL)
            OR (:groupDueDateFilterType = 2 AND t.dueDate IS NOT NULL AND (:groupDueDateMin IS NULL OR t.dueDate >= :groupDueDateMin) AND (:groupDueDateMax IS NULL OR t.dueDate <= :groupDueDateMax))
            OR (:groupDueDateFilterType = 3 AND (t.dueDate IS NULL OR ((:groupDueDateMin IS NULL OR t.dueDate >= :groupDueDateMin) AND (:groupDueDateMax IS NULL OR t.dueDate <= :groupDueDateMax))))
          )
          AND (:groupIsRecurring IS NULL OR t.isRecurring = :groupIsRecurring)
          AND (:groupAutoUpdateStatus IS NULL OR t.autoUpdateStatusFromSubtasks = :groupAutoUpdateStatus)
          AND (:groupHasNotifications IS NULL OR ((:groupHasNotifications = 1 AND t.notificationsJson != '[]') OR (:groupHasNotifications = 0 AND t.notificationsJson = '[]')))
          AND (
            :groupEstimatedTimeFilterType = 0
            OR (:groupEstimatedTimeFilterType = 1 AND t.estimatedTimeJson IS NULL)
            OR (:groupEstimatedTimeFilterType = 2 AND t.estimatedTimeJson IS NOT NULL AND (
              :groupEstimatedTimeMinSeconds IS NULL OR (
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.years') AS INTEGER), 0) * 365 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.months') AS INTEGER), 0) * 30 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.weeks') AS INTEGER), 0) * 7 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.days') AS INTEGER), 0) * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.hours') AS INTEGER), 0) * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.minutes') AS INTEGER), 0) * 60 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.seconds') AS INTEGER), 0)
              ) >= :groupEstimatedTimeMinSeconds
            ) AND (
              :groupEstimatedTimeMaxSeconds IS NULL OR (
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.years') AS INTEGER), 0) * 365 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.months') AS INTEGER), 0) * 30 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.weeks') AS INTEGER), 0) * 7 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.days') AS INTEGER), 0) * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.hours') AS INTEGER), 0) * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.minutes') AS INTEGER), 0) * 60 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.seconds') AS INTEGER), 0)
              ) <= :groupEstimatedTimeMaxSeconds
            ))
            OR (:groupEstimatedTimeFilterType = 3 AND (t.estimatedTimeJson IS NULL OR (
              (:groupEstimatedTimeMinSeconds IS NULL OR (
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.years') AS INTEGER), 0) * 365 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.months') AS INTEGER), 0) * 30 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.weeks') AS INTEGER), 0) * 7 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.days') AS INTEGER), 0) * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.hours') AS INTEGER), 0) * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.minutes') AS INTEGER), 0) * 60 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.seconds') AS INTEGER), 0)
              ) >= :groupEstimatedTimeMinSeconds) AND
              (:groupEstimatedTimeMaxSeconds IS NULL OR (
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.years') AS INTEGER), 0) * 365 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.months') AS INTEGER), 0) * 30 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.weeks') AS INTEGER), 0) * 7 * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.days') AS INTEGER), 0) * 24 * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.hours') AS INTEGER), 0) * 3600 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.minutes') AS INTEGER), 0) * 60 +
                COALESCE(CAST(json_extract(t.estimatedTimeJson, '$.seconds') AS INTEGER), 0)
              ) <= :groupEstimatedTimeMaxSeconds)
            )))
          )
          AND (
            :groupHasConnections IS NULL
            OR (:groupHasConnections = 1 AND EXISTS (SELECT 1 FROM task_connections WHERE sourceTaskId = t.id))
            OR (:groupHasConnections = 0 AND NOT EXISTS (SELECT 1 FROM task_connections WHERE sourceTaskId = t.id))
          )
          AND (
            :groupStatusFilterType = 0
            OR (
              (:groupStatusOpen = 1 AND t.status LIKE '%TaskStatus.Open%')
              OR (:groupStatusInProgress = 1 AND t.status LIKE '%TaskStatus.InProgress%')
              OR (:groupStatusBlocked = 1 AND t.status LIKE '%TaskStatus.Blocked%')
              OR (:groupStatusDone = 1 AND t.status LIKE '%TaskStatus.Done%')
              OR (:groupStatusDeclined = 1 AND t.status LIKE '%TaskStatus.Declined%')
            )
          )
          -- TaskFilterCriteria: Status filters (additional filtering by status types)
          AND (
            :criteriaStatusFilterType = 0
            OR (
              (:criteriaStatusOpen = 1 AND t.status LIKE '%TaskStatus.Open%')
              OR (:criteriaStatusInProgress = 1 AND t.status LIKE '%TaskStatus.InProgress%')
              OR (:criteriaStatusBlocked = 1 AND t.status LIKE '%TaskStatus.Blocked%')
              OR (:criteriaStatusDone = 1 AND t.status LIKE '%TaskStatus.Done%')
              OR (:criteriaStatusDeclined = 1 AND t.status LIKE '%TaskStatus.Declined%')
            )
          )
          -- TaskFilterCriteria: Connection type filters
          AND (
            :connectionFilterType = 0 -- Not applied
            OR (
              (:requireDependsOn = 0 OR EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'DependsOn'))
              AND (:requireIsDependencyOf = 0 OR EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'IsDependencyOf'))
              AND (:requireRelatesTo = 0 OR EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'RelatesTo'))
              AND (:requireSubtaskOf = 0 OR EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'SubtaskOf'))
              AND (:requireParentOf = 0 OR EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'ParentOf'))
              AND (:requireNotSubtask = 0 OR NOT EXISTS (SELECT 1 FROM task_connections c WHERE c.sourceTaskId = t.id AND c.type = 'SubtaskOf'))
            )
          )
        """
    )
    suspend fun countTasksFiltered(
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
        estimatedTimeMinMinutes: Long?,
        estimatedTimeMaxMinutes: Long?,
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
    ): Long

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
}

// Projections for the grouped count queries.

data class StatusCount(val status: String, val count: Long)

data class PriorityRangeCount(val priorityRange: String, val count: Long)

data class HasDueDateCount(val hasDueDate: Long, val count: Long)

data class IsRecurringCount(val isRecurring: Long, val count: Long)

data class IsBlockedCount(val isBlocked: Long, val count: Long)

data class AutoUpdateStatusCount(val autoUpdateStatusFromSubtasks: Long, val count: Long)

data class HasConnectionsCount(val hasConnections: Long, val count: Long)

data class HasNotificationsCount(val hasNotifications: Long, val count: Long)
