@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.*
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
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

data class TaskDetailState(
    val taskWithTotals: TaskWithTotals? = null,
    /** Whether the load has run, so the screen can tell "not read yet" from "no such task". */
    val loadAttempted: Boolean = false,
    val connectionsByType: Map<ConnectionType, List<Task>> = emptyMap(),
    val currentSpaceIdPrefix: String? = null,
    val allSpacePrefixes: List<String> = emptyList(),
    val statusTimeline: List<StatusChange> = emptyList(),
    val loadedTasks: PersistentMap<String, Task> = persistentMapOf()
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
) : ScopedContainer(), Container<TaskDetailState, TaskDetailIntent, TaskDetailAction> {

    override val store = store(TaskDetailState(), scope) {
        reportingFailuresAs("TaskDetailStore")

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
                loadAttempted = true,
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
        // Always the freshly read task. Keeping the cached one meant a blocker or referenced task
        // edited from this screen still showed its old title and status on return, for as long as
        // the screen stayed open.
        updateState { copy(loadedTasks = loadedTasks.putting(taskId, task)) }
    }
}

/**
 * Factory interface for creating TaskDetailContainer instances with runtime parameters.
 */
fun interface TaskDetailContainerFactory {
    fun create(spaceId: String, taskId: String): TaskDetailContainer
}
