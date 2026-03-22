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
import kotlin.time.Instant

/**
 * Persisted form state for surviving process death during nested navigation
 */
data class PersistedFormState(
    val title: String?,
    val description: String?,
    val priority: String?,
    val estimatedTime: String?,
    val tags: Set<String>,
    val dueDate: Instant?
)

data class TaskDetailState(
    val taskWithTotals: TaskWithTotals? = null,
    val connectionsByType: Map<ConnectionType, List<Task>> = emptyMap(),
    val currentSpaceIdPrefix: String? = null,
    val allSpacePrefixes: List<String> = emptyList(),
    val statusTimeline: List<StatusChange> = emptyList(),
    val loadedTasks: Map<String, Task> = emptyMap()
) : MVIState

sealed interface TaskDetailIntent : MVIIntent {
    data object LoadTask : TaskDetailIntent
    data object LoadSpaceInfo : TaskDetailIntent
    data class LoadTaskById(val taskId: String) : TaskDetailIntent
}

sealed interface TaskDetailAction : MVIAction

private typealias TaskDetailPipelineContext = PipelineContext<TaskDetailState, TaskDetailIntent, TaskDetailAction>

class TaskDetailContainer(
    private val repository: TaskRepository,
    private val spaceId: String,
    private val taskId: String
) : Container<TaskDetailState, TaskDetailIntent, TaskDetailAction> {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val store = store(TaskDetailState(), scope) {
        configure {
            name = "TaskDetailStore"
        }

        whileSubscribed {
            loadTask()
            loadSpaceInfo()
        }

        reduce { intent ->
            when (intent) {
                is TaskDetailIntent.LoadTask -> loadTask()
                is TaskDetailIntent.LoadSpaceInfo -> loadSpaceInfo()
                is TaskDetailIntent.LoadTaskById -> loadTaskById(intent.taskId)
            }
        }
    }

    private suspend fun TaskDetailPipelineContext.loadTask() {
        val taskWithTotals = repository.getTasksByIdWithTotals(taskId)
        val connectionsByType = repository.getConnectionsByType(taskId)
        val statusTimeline = repository.getStatusTimeline(taskId)
        updateState {
            copy(
                taskWithTotals = taskWithTotals,
                connectionsByType = connectionsByType,
                statusTimeline = statusTimeline
            )
        }
    }

    private suspend fun TaskDetailPipelineContext.loadSpaceInfo() {
        val prefix = repository.getSpaceById(spaceId)?.idPrefix
        val allPrefixes = repository.getAllSpacePrefixes()
        updateState {
            copy(
                currentSpaceIdPrefix = prefix,
                allSpacePrefixes = allPrefixes
            )
        }
    }

    private suspend fun TaskDetailPipelineContext.loadTaskById(taskId: String) {
        val task = repository.getTaskById(taskId) ?: return
        updateState {
            if (taskId in loadedTasks) this else copy(loadedTasks = loadedTasks + (taskId to task))
        }
    }
}

/**
 * Factory interface for creating TaskDetailContainer instances with runtime parameters.
 */
fun interface TaskDetailContainerFactory {
    fun create(spaceId: String, taskId: String): TaskDetailContainer
}
