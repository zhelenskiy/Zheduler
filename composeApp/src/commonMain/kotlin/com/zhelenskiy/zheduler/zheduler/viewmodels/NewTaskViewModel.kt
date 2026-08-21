@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.events.ChosenSound
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.form.FormStatePersistence
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
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
        val autoUpdateStatusFromSubtasks: Boolean,
        val dueSound: ChosenSound,
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

    /** The repository declined to create it — see [TaskRepository.addTask]'s nullable return. */
    data object TaskCreationFailed : NewTaskAction
}

private typealias NewTaskPipelineContext = PipelineContext<NewTaskState, NewTaskIntent, NewTaskAction>

class NewTaskContainer(
    private val repository: TaskRepository,
    private val spaceId: String,
    private val prefilledConnection: TaskConnection?,
    private val taskIdToCopy: String?,
    savedStateHandle: SavedStateHandle,
) : ScopedContainer(), Container<NewTaskState, NewTaskIntent, NewTaskAction> {

    override val store = store(NewTaskState(), scope) {
        reportingFailuresAs("NewTaskStore")

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

    /** Keeps a half-written task across process death. See [FormStatePersistence]. */
    val formPersistence = FormStatePersistence(savedStateHandle)

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
            autoUpdateStatusFromSubtasks = intent.autoUpdateStatusFromSubtasks,
            dueSound = intent.dueSound,
        )
        if (task != null) {
            formPersistence.clear()
            action(NewTaskAction.TaskCreated(task))
        } else {
            // Saying nothing left the screen looking as though the tap had not registered, with
            // its in-flight guard still latched and Save dead for good.
            action(NewTaskAction.TaskCreationFailed)
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

    private val searches = TaskFormSearches(scope, repository, spaceId, excludeTaskId = null)

    val filteredTags: Flow<PagingData<String>> get() = searches.tags
    val filteredTasksForSelection: Flow<PagingData<Task>> get() = searches.tasksForSelection
    val searchedTasksForConnection: Flow<PagingData<Task>> get() = searches.tasksForConnection

    private fun filterTags(searchQuery: String, excludeTags: Set<String>) =
        searches.filterTags(searchQuery, excludeTags)

    private fun filterTasksForSelection(searchQuery: String) =
        searches.filterTasksForSelection(searchQuery)

    private fun searchTasksForConnection(intent: NewTaskIntent.SearchTasksForConnection) =
        searches.searchTasksForConnection(
            searchQuery = intent.searchQuery,
            excludeTaskIds = intent.excludeTaskIds,
            connectionType = intent.connectionType,
            existingConnections = intent.existingConnections,
        )
}

/**
 * Factory interface for creating NewTaskContainer instances with runtime parameters.
 */
fun interface NewTaskContainerFactory {
    fun create(
        spaceId: String,
        prefilledConnection: TaskConnection?,
        taskIdToCopy: String?,
        savedStateHandle: SavedStateHandle,
    ): NewTaskContainer
}
