package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.paging.connectionSearchPagingSource
import com.zhelenskiy.zheduler.zheduler.paging.tagsPagingSource
import com.zhelenskiy.zheduler.zheduler.paging.taskSelectionPagingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * The three paged lookups the task form offers: tags, tasks to pick, and tasks to connect to.
 *
 * Creating and editing a task need the same three. Streams rather than state: PagingData is not
 * comparable state, and the lists are unbounded — every tag or task of a space.
 *
 * @param excludeTaskId the task being edited, which cannot be its own connection; absent when
 *   creating one.
 */
class TaskFormSearches(
    scope: CoroutineScope,
    private val repository: TaskRepository,
    private val spaceId: String,
    private val excludeTaskId: String?,
) {
    private val tagSearch = PagedQuery(scope, TagQuery(), repository.changes) { query ->
        repository.tagsPagingSource(spaceId, query.searchQuery, query.excludeTags)
    }

    private val taskSelectionSearch = PagedQuery(scope, "", repository.changes) { query ->
        repository.taskSelectionPagingSource(spaceId, excludeTaskId, query)
    }

    private val connectionSearch = PagedQuery(scope, ConnectionQuery(), repository.changes) { query ->
        repository.connectionSearchPagingSource(
            spaceId = spaceId,
            excludeTaskId = excludeTaskId,
            searchQuery = query.searchQuery,
            excludeTaskIds = query.excludeTaskIds,
            connectionType = query.connectionType,
            existingConnections = query.existingConnections,
        )
    }

    val tags: Flow<PagingData<String>> get() = tagSearch.pages
    val tasksForSelection: Flow<PagingData<Task>> get() = taskSelectionSearch.pages
    val tasksForConnection: Flow<PagingData<Task>> get() = connectionSearch.pages

    fun filterTags(searchQuery: String, excludeTags: Set<String>) {
        tagSearch.setQuery(TagQuery(searchQuery, excludeTags))
    }

    fun filterTasksForSelection(searchQuery: String) {
        taskSelectionSearch.setQuery(searchQuery)
    }

    fun searchTasksForConnection(
        searchQuery: String,
        excludeTaskIds: Set<String>,
        connectionType: ConnectionType,
        existingConnections: Set<TaskConnection>,
    ) {
        connectionSearch.setQuery(
            ConnectionQuery(searchQuery, excludeTaskIds, connectionType, existingConnections)
        )
    }
}
