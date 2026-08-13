@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.paging.connectionSearchPagingSource
import com.zhelenskiy.zheduler.zheduler.paging.tagsPagingSource
import com.zhelenskiy.zheduler.zheduler.paging.taskSelectionPagingSource
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
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

private const val KEY_FORM_TITLE = "formTitle"
private const val KEY_FORM_DESCRIPTION = "formDescription"
private const val KEY_FORM_PRIORITY = "formPriority"
private const val KEY_FORM_ESTIMATED_TIME = "formEstimatedTime"
private const val KEY_FORM_TAGS = "formTags"
private const val KEY_FORM_DUE_DATE = "formDueDate"

data class TaskEditState(
    val task: Task? = null,
    val currentSpaceIdPrefix: String? = null,
    val allSpacePrefixes: List<String> = emptyList(),
    val loadedTasks: PersistentMap<String, Task> = persistentMapOf()
) : MVIState

sealed interface TaskEditIntent : MVIIntent {
    data object LoadInitialData : TaskEditIntent
    data class SaveTask(val updatedTask: Task) : TaskEditIntent

    // Search intents
    data class LoadTaskById(val taskId: String) : TaskEditIntent
    data class FilterTags(val searchQuery: String, val excludeTags: Set<String>) : TaskEditIntent
    data class FilterTasksForSelection(val searchQuery: String) : TaskEditIntent
    data class SearchTasksForConnection(
        val searchQuery: String,
        val excludeTaskIds: Set<String>,
        val connectionType: ConnectionType,
        val existingConnections: Set<TaskConnection>
    ) : TaskEditIntent
}

sealed interface TaskEditAction : MVIAction {
    data object TaskSaved : TaskEditAction
}

private typealias TaskEditPipelineContext = PipelineContext<TaskEditState, TaskEditIntent, TaskEditAction>

class TaskEditContainer(
    private val repository: TaskRepository,
    private val spaceId: String,
    private val taskId: String,
    private val savedStateHandle: SavedStateHandle
) : Container<TaskEditState, TaskEditIntent, TaskEditAction> {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val store = store(TaskEditState(), scope) {
        configure {
            name = "TaskEditStore"
        }

        whileSubscribed {
            loadTask()
        }

        reduce { intent ->
            when (intent) {
                is TaskEditIntent.LoadInitialData -> loadTask()
                is TaskEditIntent.SaveTask -> saveTask(intent.updatedTask)
                is TaskEditIntent.LoadTaskById -> loadTaskById(intent.taskId)
                is TaskEditIntent.FilterTags -> filterTags(intent.searchQuery, intent.excludeTags)
                is TaskEditIntent.FilterTasksForSelection -> filterTasksForSelection(intent.searchQuery)
                is TaskEditIntent.SearchTasksForConnection -> searchTasksForConnection(intent)
            }
        }
    }

    private suspend fun TaskEditPipelineContext.loadTask() {
        val task = repository.getTaskById(taskId)
        val currentSpaceIdPrefix = repository.getSpaceById(spaceId)?.idPrefix
        val allSpacePrefixes = repository.getAllSpacePrefixes()
        updateState {
            copy(
                task = task,
                currentSpaceIdPrefix = currentSpaceIdPrefix,
                allSpacePrefixes = allSpacePrefixes
            )
        }
    }

    private suspend fun TaskEditPipelineContext.saveTask(updatedTask: Task) {
        repository.updateTask(updatedTask)
        clearPersistedFormState()
        action(TaskEditAction.TaskSaved)
    }

    // Form state persistence using SavedStateHandle (survives navigation)
    fun getPersistedFormState(): PersistedFormState {
        val tags = savedStateHandle.get<String>(KEY_FORM_TAGS)
            ?.let { tagsJson -> runCatching { Json.decodeFromString<Set<String>>(tagsJson) }.getOrNull() }
            ?.toPersistentSet()
            ?: persistentSetOf()

        val dueDate = savedStateHandle.get<Long>(KEY_FORM_DUE_DATE)?.let { epochMillis ->
            Instant.fromEpochMilliseconds(epochMillis)
        }

        return PersistedFormState(
            title = savedStateHandle.get<String>(KEY_FORM_TITLE),
            description = savedStateHandle.get<String>(KEY_FORM_DESCRIPTION),
            priority = savedStateHandle.get<String>(KEY_FORM_PRIORITY),
            estimatedTime = savedStateHandle.get<String>(KEY_FORM_ESTIMATED_TIME),
            tags = tags,
            dueDate = dueDate
        )
    }

    fun persistFormState(
        title: String,
        description: String,
        priority: String,
        estimatedTime: String,
        tags: PersistentSet<String>,
        dueDate: Instant?
    ) {
        savedStateHandle[KEY_FORM_TITLE] = title
        savedStateHandle[KEY_FORM_DESCRIPTION] = description
        savedStateHandle[KEY_FORM_PRIORITY] = priority
        savedStateHandle[KEY_FORM_ESTIMATED_TIME] = estimatedTime
        savedStateHandle[KEY_FORM_TAGS] = Json.encodeToString<Set<String>>(tags)
        savedStateHandle[KEY_FORM_DUE_DATE] = dueDate?.toEpochMilliseconds()
    }

    fun clearPersistedFormState() {
        savedStateHandle.remove<String>(KEY_FORM_TITLE)
        savedStateHandle.remove<String>(KEY_FORM_DESCRIPTION)
        savedStateHandle.remove<String>(KEY_FORM_PRIORITY)
        savedStateHandle.remove<String>(KEY_FORM_ESTIMATED_TIME)
        savedStateHandle.remove<String>(KEY_FORM_TAGS)
        savedStateHandle.remove<Long>(KEY_FORM_DUE_DATE)
    }

    private suspend fun TaskEditPipelineContext.loadTaskById(id: String) {
        val task = repository.getTaskById(id)
        if (task != null) {
            updateState { copy(loadedTasks = loadedTasks.putting(id, task)) }
        }
    }

    // ============ Paged search results ============
    // These are streams rather than state: PagingData is not comparable state, and the lists they
    // feed are unbounded (every task or tag of a space).

    private val tagSearch = PagedQuery(scope, TagQuery(), repository.changes) { query ->
        repository.tagsPagingSource(spaceId, query.searchQuery, query.excludeTags)
    }

    private val taskSelectionSearch = PagedQuery(scope, "", repository.changes) { query ->
        repository.taskSelectionPagingSource(spaceId, taskId, query)
    }

    private val connectionSearch = PagedQuery(scope, ConnectionQuery(), repository.changes) { query ->
        repository.connectionSearchPagingSource(
            spaceId = spaceId,
            excludeTaskId = taskId,
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

    private fun searchTasksForConnection(intent: TaskEditIntent.SearchTasksForConnection) {
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
 * Factory interface for creating TaskEditContainer instances with runtime parameters.
 */
fun interface TaskEditContainerFactory {
    fun create(spaceId: String, taskId: String, savedStateHandle: SavedStateHandle): TaskEditContainer
}
