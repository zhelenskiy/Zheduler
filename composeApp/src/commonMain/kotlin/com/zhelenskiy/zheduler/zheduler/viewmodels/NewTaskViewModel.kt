@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import pro.respawn.flowmvi.api.Container
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.time.ExperimentalTime

data class NewTaskState(
    val taskToCopy: Task? = null,
    val prefilledTask: Task? = null,
    val nextId: String? = null,
    val currentSpaceIdPrefix: String? = null,
    val allSpacePrefixes: List<String> = emptyList(),
    val initialConnections: Set<TaskConnection> = emptySet(),
    // Search results for form components
    val filteredTags: List<String> = emptyList(),
    val filteredTasksForSelection: List<Task> = emptyList(),
    val searchedTasksForConnection: List<Task> = emptyList(),
    val loadedTasks: Map<String, Task> = emptyMap()
) : MVIState

sealed interface NewTaskIntent : MVIIntent {
    data object LoadInitialData : NewTaskIntent
    data class CreateTask(
        val title: String,
        val description: String,
        val status: TaskStatus,
        val dueDate: kotlin.time.Instant?,
        val priority: Priority?,
        val estimatedTime: RecurrencePeriod?,
        val tags: Set<String>,
        val connections: Set<TaskConnection>,
        val notifications: List<TaskNotification>,
        val recurrenceRules: List<Pair<RecurrenceRule, RecurrenceState>>,
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
        val initialConnections = prefilledConnection?.let { setOf(it) } ?: emptySet()

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
            updateState { copy(loadedTasks = loadedTasks + (taskId to task)) }
        }
    }

    private suspend fun NewTaskPipelineContext.filterTags(searchQuery: String, excludeTags: Set<String>) {
        val tags = repository.filterTags(spaceId, searchQuery, excludeTags)
        updateState { copy(filteredTags = tags) }
    }

    private suspend fun NewTaskPipelineContext.filterTasksForSelection(searchQuery: String) {
        val tasks = repository.filterTasksForSelection(spaceId, null, searchQuery)
        updateState { copy(filteredTasksForSelection = tasks) }
    }

    private suspend fun NewTaskPipelineContext.searchTasksForConnection(intent: NewTaskIntent.SearchTasksForConnection) {
        val tasks = repository.searchTasksForConnection(
            spaceId = spaceId,
            excludeTaskId = null,
            searchQuery = intent.searchQuery,
            excludeTaskIds = intent.excludeTaskIds,
            connectionType = intent.connectionType,
            existingConnections = intent.existingConnections
        )
        updateState { copy(searchedTasksForConnection = tasks) }
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
