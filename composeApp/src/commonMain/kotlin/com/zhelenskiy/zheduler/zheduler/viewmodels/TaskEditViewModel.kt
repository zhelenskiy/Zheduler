@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.form.FormStatePersistence
import com.zhelenskiy.zheduler.zheduler.sync.CloudSpaces
import com.zhelenskiy.zheduler.zheduler.sync.CommitOutcome
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
    /** Whether the load has run, so the screen can tell "not read yet" from "no such task". */
    val loadAttempted: Boolean = false,
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

    /**
     * The server would not take it, so it did not happen — and the form still has it.
     *
     * Distinct from [TaskSaveFailed] because the answer is different: nothing is wrong with what
     * the user wrote, and the way out is to wait for the server rather than to change anything.
     */
    data object TaskSaveNotAccepted : TaskEditAction
}

private typealias TaskEditPipelineContext = PipelineContext<TaskEditState, TaskEditIntent, TaskEditAction>

class TaskEditContainer(
    private val repository: TaskRepository,
    /**
     * Where a cloud space's changes have to be agreed. Null in a build with no sync, and for
     * every space that belongs to this device alone.
     */
    private val cloud: CloudSpaces? = null,
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
                loadAttempted = true,
                currentSpaceIdPrefix = currentSpaceIdPrefix,
                allSpacePrefixes = allSpacePrefixes
            )
        }
    }

    /**
     * Writes the edit and gets it agreed, or reports why it did not happen.
     *
     * The write goes *inside* [CloudSpaces.commit] rather than before it. A save on a cloud space
     * is only half an edit until the server has taken it, and the other half can be refused —
     * which takes the change back out. Doing the two separately leaves a gap in which somebody
     * else's rollback can land on top of the write, and everything afterwards then reports a space
     * that is perfectly in step, having quietly thrown this edit away.
     */
    private suspend fun TaskEditPipelineContext.saveTask(updatedTask: Task) {
        // A null return means the task is no longer there — deleted from another window, say.
        // Reporting success anyway discarded the edit, wiped the copy kept for process death and
        // navigated away, all while behaving as though the save had worked.
        val write: suspend () -> Boolean = { repository.updateTask(updatedTask) != null }
        val outcome = cloud?.commit(spaceId, write)
            ?: if (write()) CommitOutcome.Accepted else CommitOutcome.NotWritten

        when (outcome) {
            CommitOutcome.NotWritten -> action(TaskEditAction.TaskSaveFailed)
            // The one answer that must not leave: the change is gone from the space and the form
            // in front of the user is the only place it still exists.
            CommitOutcome.Undone,
            // And a conflict too. The copy does survive here, but only until the question is
            // answered — and until then the form is the one place it cannot be adopted over.
            CommitOutcome.AwaitingYourChoice -> action(TaskEditAction.TaskSaveNotAccepted)

            CommitOutcome.Accepted -> {
                formPersistence.clear()
                action(TaskEditAction.TaskSaved)
            }
        }
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
