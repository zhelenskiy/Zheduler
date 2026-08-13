@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.paging.connectionSearchPagingSource
import com.zhelenskiy.zheduler.zheduler.paging.tagsPagingSource
import com.zhelenskiy.zheduler.zheduler.paging.taskSelectionPagingSource
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import pro.respawn.flowmvi.api.Container
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class NewTaskState(
    val taskToCopy: Task? = null,
    val prefilledTask: Task? = null,
    val nextId: String? = null,
    val currentSpaceIdPrefix: String? = null,
    val allSpacePrefixes: List<String> = emptyList(),
    val initialConnections: PersistentSet<TaskConnection> = persistentSetOf(),
    val loadedTasks: PersistentMap<String, Task> = persistentMapOf()
) : MVIState

sealed interface NewTaskIntent : MVIIntent {
    data object LoadInitialData : NewTaskIntent
    data class CreateTask(
        val title: String,
        val description: String,
        val status: TaskStatus,
        val dueDate: Instant?,
        val priority: Priority?,
        val estimatedTime: RecurrencePeriod?,
        val tags: PersistentSet<String>,
        val connections: PersistentSet<TaskConnection>,
        val notifications: PersistentList<TaskNotification>,
        val recurrenceRules: PersistentList<Pair<RecurrenceRule, RecurrenceState>>,
        val autoUpdateStatusFromSubtasks: Boolean
    ) : NewTaskIntent

    // Search intents
    data class LoadTask(val taskId: String) : NewTaskIntent
    data class FilterTags(val searchQuery: String, val excludeTags: Set<String>) : NewTaskIntent
    data class FilterTasksForSelection(val searchQuery: String) : NewTaskIntent
    data class SearchTasksForConnection(
        val searchQuery: String,
        val excludeTaskIds: Set<String>,
        val connectionType: ConnectionType,
        val existingConnections: Set<TaskConnection>
    ) : NewTaskIntent
}

sealed interface NewTaskAction : MVIAction {
    data class TaskCreated(val task: Task) : NewTaskAction
}

private typealias NewTaskPipelineContext = PipelineContext<NewTaskState, NewTaskIntent, NewTaskAction>

class NewTaskContainer(
    private val repository: TaskRepository,
    private val spaceId: String,
    private val prefilledConnection: TaskConnection?,
    private val taskIdToCopy: String?
) : Container<NewTaskState, NewTaskIntent, NewTaskAction> {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val store = store(NewTaskState(), scope) {
        configure {
            name = "NewTaskStore"
        }

        whileSubscribed {
            loadInitialData()
        }

        reduce { intent ->
            when (intent) {
                is NewTaskIntent.LoadInitialData -> loadInitialData()
                is NewTaskIntent.CreateTask -> createTask(intent)
                is NewTaskIntent.LoadTask -> loadTask(intent.taskId)
                is NewTaskIntent.FilterTags -> filterTags(intent.searchQuery, intent.excludeTags)
                is NewTaskIntent.FilterTasksForSelection -> filterTasksForSelection(intent.searchQuery)
                is NewTaskIntent.SearchTasksForConnection -> searchTasksForConnection(intent)
            }
        }
    }

    private suspend fun NewTaskPipelineContext.loadInitialData() {
        val taskToCopy = taskIdToCopy?.let { repository.getTaskById(it) }
        val prefilledTask = prefilledConnection?.let { repository.getTaskById(it.targetTaskId) }
        val nextId = repository.peekNextId(spaceId)
        val currentSpaceIdPrefix = repository.getSpaceById(spaceId)?.idPrefix
        val allSpacePrefixes = repository.getAllSpacePrefixes()
        val initialConnections = prefilledConnection?.let { persistentSetOf(it) } ?: persistentSetOf()

        updateState {
            copy(
                taskToCopy = taskToCopy,
                prefilledTask = prefilledTask,
                nextId = nextId,
                currentSpaceIdPrefix = currentSpaceIdPrefix,
                allSpacePrefixes = allSpacePrefixes,
                initialConnections = initialConnections
            )
        }
    }

    private suspend fun NewTaskPipelineContext.createTask(intent: NewTaskIntent.CreateTask) {
        val task = repository.addTask(
            spaceId = spaceId,
            title = intent.title,
            description = intent.description,
            status = intent.status,
            dueDate = intent.dueDate,
            priority = intent.priority,
            estimatedTime = intent.estimatedTime,
            tags = intent.tags,
            connections = intent.connections,
            notifications = intent.notifications,
            recurrenceRules = intent.recurrenceRules,
            autoUpdateStatusFromSubtasks = intent.autoUpdateStatusFromSubtasks
        )
        if (task != null) {
            action(NewTaskAction.TaskCreated(task))
        }
    }

    private suspend fun NewTaskPipelineContext.loadTask(taskId: String) {
        val task = repository.getTaskById(taskId)
        if (task != null) {
            updateState { copy(loadedTasks = loadedTasks.putting(taskId, task)) }
        }
    }

    // ============ Paged search results ============
    // Streams rather than state, for the same reasons as in TaskEditContainer.

    private val tagSearch = PagedQuery(scope, TagQuery(), repository.changes) { query ->
        repository.tagsPagingSource(spaceId, query.searchQuery, query.excludeTags)
    }

    private val taskSelectionSearch = PagedQuery(scope, "", repository.changes) { query ->
        repository.taskSelectionPagingSource(spaceId, null, query)
    }

    private val connectionSearch = PagedQuery(scope, ConnectionQuery(), repository.changes) { query ->
        repository.connectionSearchPagingSource(
            spaceId = spaceId,
            excludeTaskId = null,
            searchQuery = query.searchQuery,
            excludeTaskIds = query.excludeTaskIds,
            connectionType = query.connectionType,
            existingConnections = query.existingConnections,
        )
    }

    val filteredTags: Flow<PagingData<String>> get() = tagSearch.pages
    val filteredTasksForSelection: Flow<PagingData<Task>> get() = taskSelectionSearch.pages
    val searchedTasksForConnection: Flow<PagingData<Task>> get() = connectionSearch.pages

    private fun filterTags(searchQuery: String, excludeTags: Set<String>) {
        tagSearch.setQuery(TagQuery(searchQuery, excludeTags))
    }

    private fun filterTasksForSelection(searchQuery: String) {
        taskSelectionSearch.setQuery(searchQuery)
    }

    private fun searchTasksForConnection(intent: NewTaskIntent.SearchTasksForConnection) {
        connectionSearch.setQuery(
            ConnectionQuery(
                searchQuery = intent.searchQuery,
                excludeTaskIds = intent.excludeTaskIds,
                connectionType = intent.connectionType,
                existingConnections = intent.existingConnections,
            )
        )
    }
}

/**
 * Factory interface for creating NewTaskContainer instances with runtime parameters.
 */
fun interface NewTaskContainerFactory {
    fun create(
        spaceId: String,
        prefilledConnection: TaskConnection?,
        taskIdToCopy: String?
    ): NewTaskContainer
}
