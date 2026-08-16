package com.zhelenskiy.zheduler.zheduler.paging

import androidx.paging.PagingSource
import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.GroupFilter
import com.zhelenskiy.zheduler.zheduler.OrderingRule
import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.TaskWithTotals
import kotlinx.collections.immutable.PersistentList

/**
 * [PagingSource] factories for the repository's paged queries.
 *
 * They live next to the repository rather than in the UI module so any consumer (the Compose app
 * today, another front end later) pages the same way. Callers normally wrap these in an
 * `InvalidatingPagingSourceFactory` and invalidate it from [TaskRepository.changes].
 */

/** Tasks of one leaf group in the task list, ordered by the view mode's rules. */
fun TaskRepository.tasksForGroupPagingSource(
    spaceId: String,
    filters: PersistentList<GroupFilter>,
    orderingRules: PersistentList<OrderingRule>,
    filterCriteria: TaskFilterCriteria,
): PagingSource<Int, TaskWithTotals> = OffsetPagingSource { offset, limit ->
    getTasksForGroupPage(spaceId, filters, orderingRules, filterCriteria, offset, limit)
}

/** Candidates for the task-selection dialogs (blockers, recurrence targets, ...). */
fun TaskRepository.taskSelectionPagingSource(
    spaceId: String,
    excludeTaskId: String?,
    searchQuery: String,
): PagingSource<Int, Task> = OffsetPagingSource { offset, limit ->
    filterTasksForSelectionPage(spaceId, excludeTaskId, searchQuery, offset, limit)
}

/** Candidates for the connection dialog, already filtered for cycles. */
fun TaskRepository.connectionSearchPagingSource(
    spaceId: String,
    excludeTaskId: String?,
    searchQuery: String,
    excludeTaskIds: Set<String>,
    connectionType: ConnectionType,
    existingConnections: Set<TaskConnection>,
): PagingSource<Int, Task> = OffsetPagingSource { offset, limit ->
    searchTasksForConnectionPage(
        spaceId = spaceId,
        excludeTaskId = excludeTaskId,
        searchQuery = searchQuery,
        excludeTaskIds = excludeTaskIds,
        connectionType = connectionType,
        existingConnections = existingConnections,
        offset = offset,
        limit = limit,
    )
}

/** Spaces on the space list screen. */
fun TaskRepository.spacesPagingSource(
    query: String,
    searchInName: Boolean,
    searchInPrefix: Boolean,
): PagingSource<Int, Space> = OffsetPagingSource { offset, limit ->
    filterSpacesPage(query, searchInName, searchInPrefix, offset, limit)
}

/** Tags in the tag-selection dialog. */
fun TaskRepository.tagsPagingSource(
    spaceId: String,
    searchQuery: String,
    excludeTags: Set<String>,
): PagingSource<Int, String> = OffsetPagingSource { offset, limit ->
    filterTagsPage(spaceId, searchQuery, excludeTags, offset, limit)
}
