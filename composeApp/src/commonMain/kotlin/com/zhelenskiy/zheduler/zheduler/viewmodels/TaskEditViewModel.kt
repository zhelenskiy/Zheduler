@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.form.FormStatePersistence
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
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

    /** The task could not be saved — most likely it no longer exists. */
    data object TaskSaveFailed : TaskEditAction
}

private typealias TaskEditPipelineContext = PipelineContext<TaskEditState, TaskEditIntent, TaskEditAction>

class TaskEditContainer(
    private val repository: TaskRepository,
    private val spaceId: String,
    private val taskId: String,
    private val savedStateHandle: SavedStateHandle
) : ScopedContainer(), Container<TaskEditState, TaskEditIntent, TaskEditAction> {

    override val store = store(TaskEditState(), scope) {
        reportingFailuresAs("TaskEditStore")

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
        // A null return means the task is no longer there — deleted from another window, say.
        // Reporting success anyway discarded the edit, wiped the copy kept for process death and
        // navigated away, all while behaving as though the save had worked.
        if (repository.updateTask(updatedTask) == null) {
            action(TaskEditAction.TaskSaveFailed)
            return
        }
        formPersistence.clear()
        action(TaskEditAction.TaskSaved)
    }

    /** Keeps a half-written edit across process death. See [FormStatePersistence]. */
    val formPersistence = FormStatePersistence(savedStateHandle)

    private suspend fun TaskEditPipelineContext.loadTaskById(id: String) {
        val task = repository.getTaskById(id)
        if (task != null) {
            updateState { copy(loadedTasks = loadedTasks.putting(id, task)) }
        }
    }

    // ============ Paged search results ============
    // These are streams rather than state: PagingData is not comparable state, and the lists they
    // feed are unbounded (every task or tag of a space).

    private val searches = TaskFormSearches(scope, repository, spaceId, excludeTaskId = taskId)

    val filteredTags: Flow<PagingData<String>> get() = searches.tags
    val filteredTasksForSelection: Flow<PagingData<Task>> get() = searches.tasksForSelection
    val searchedTasksForConnection: Flow<PagingData<Task>> get() = searches.tasksForConnection

    private fun filterTags(searchQuery: String, excludeTags: Set<String>) =
        searches.filterTags(searchQuery, excludeTags)

    private fun filterTasksForSelection(searchQuery: String) =
        searches.filterTasksForSelection(searchQuery)

    private fun searchTasksForConnection(intent: TaskEditIntent.SearchTasksForConnection) =
        searches.searchTasksForConnection(
            searchQuery = intent.searchQuery,
            excludeTaskIds = intent.excludeTaskIds,
            connectionType = intent.connectionType,
            existingConnections = intent.existingConnections,
        )
}

/**
 * Factory interface for creating TaskEditContainer instances with runtime parameters.
 */
fun interface TaskEditContainerFactory {
    fun create(spaceId: String, taskId: String, savedStateHandle: SavedStateHandle): TaskEditContainer
}
