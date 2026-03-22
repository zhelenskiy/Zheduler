@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.generateId as generateIdImpl
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

data class TaskListState(
    val tasksWithTotals: List<TaskWithTotals> = emptyList(),
    val currentSpace: Space? = null,
    val allTags: Set<String> = emptySet(),
    val filteredTasks: Map<TaskFilterCriteria, List<TaskWithTotals>> = emptyMap(),
    val viewModes: List<ViewMode> = emptyList(),
    val activeViewMode: ViewMode? = null,
    val filterState: TaskFilterCriteria? = null,
    val filterPanelOpen: Boolean? = null
) : MVIState

sealed interface TaskListIntent : MVIIntent {
    data object LoadTasks : TaskListIntent
    data class DeleteTask(val taskId: String) : TaskListIntent
    data class GetFilteredTasks(val criteria: TaskFilterCriteria) : TaskListIntent
    data object LoadViewModes : TaskListIntent
    data object LoadActiveViewMode : TaskListIntent
    data class SetActiveViewMode(val viewModeId: String) : TaskListIntent
    data object LoadFilterState : TaskListIntent
    data class SaveFilterState(val criteria: TaskFilterCriteria) : TaskListIntent
    data object LoadFilterPanelOpen : TaskListIntent
    data class SaveFilterPanelOpen(val isOpen: Boolean) : TaskListIntent
    data class SaveSavedFilter(val filter: SavedFilter) : TaskListIntent
}

sealed interface TaskListAction : MVIAction {
    data class TaskDeleted(val success: Boolean) : TaskListAction
}

private typealias TaskListPipelineContext = PipelineContext<TaskListState, TaskListIntent, TaskListAction>

class TaskListContainer(
    val repository: TaskRepository,
    val spaceId: String
) : Container<TaskListState, TaskListIntent, TaskListAction> {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val store = store(TaskListState(), scope) {
        configure {
            name = "TaskListStore"
        }

        whileSubscribed {
            loadTasks()
            loadViewModes()
            loadActiveViewMode()
            loadFilterState()
            loadFilterPanelOpen()
        }

        reduce { intent ->
            when (intent) {
                is TaskListIntent.LoadTasks -> loadTasks()
                is TaskListIntent.DeleteTask -> deleteTask(intent.taskId)
                is TaskListIntent.GetFilteredTasks -> getFilteredTasks(intent.criteria)
                is TaskListIntent.LoadViewModes -> loadViewModes()
                is TaskListIntent.LoadActiveViewMode -> loadActiveViewMode()
                is TaskListIntent.SetActiveViewMode -> setActiveViewMode(intent.viewModeId)
                is TaskListIntent.LoadFilterState -> loadFilterState()
                is TaskListIntent.SaveFilterState -> saveFilterState(intent.criteria)
                is TaskListIntent.LoadFilterPanelOpen -> loadFilterPanelOpen()
                is TaskListIntent.SaveFilterPanelOpen -> saveFilterPanelOpen(intent.isOpen)
                is TaskListIntent.SaveSavedFilter -> saveSavedFilter(intent.filter)
            }
        }
    }

    private suspend fun TaskListPipelineContext.loadTasks() {
        val tasks = repository.getAllTasksWithTotals(spaceId)
        val space = repository.getSpaceById(spaceId)
        val tags = repository.getAllTags(spaceId)
        updateState {
            copy(
                tasksWithTotals = tasks,
                currentSpace = space,
                allTags = tags
            )
        }
    }

    private suspend fun TaskListPipelineContext.deleteTask(taskId: String) {
        repository.deleteTask(taskId)
        loadTasks()
        action(TaskListAction.TaskDeleted(true))
    }

    private suspend fun TaskListPipelineContext.getFilteredTasks(criteria: TaskFilterCriteria) {
        val filtered = repository.getAllWithTotalsFiltered(spaceId, criteria)
        updateState {
            copy(filteredTasks = filteredTasks + (criteria to filtered))
        }
    }

    private suspend fun TaskListPipelineContext.loadViewModes() {
        val modes = repository.getAllViewModes(spaceId)
        updateState { copy(viewModes = modes) }
    }

    private suspend fun TaskListPipelineContext.loadActiveViewMode() {
        val mode = repository.getActiveViewMode(spaceId)
        updateState { copy(activeViewMode = mode) }
    }

    private suspend fun TaskListPipelineContext.setActiveViewMode(viewModeId: String) {
        repository.setActiveViewMode(spaceId, viewModeId)
        loadActiveViewMode()
    }

    private suspend fun TaskListPipelineContext.loadFilterState() {
        val state = repository.getFilterState(spaceId)
        updateState { copy(filterState = state) }
    }

    private suspend fun TaskListPipelineContext.saveFilterState(criteria: TaskFilterCriteria) {
        repository.saveFilterState(spaceId, criteria)
        updateState { copy(filterState = criteria) }
    }

    private suspend fun TaskListPipelineContext.loadFilterPanelOpen() {
        val isOpen = repository.getFilterPanelOpen(spaceId)
        updateState { copy(filterPanelOpen = isOpen) }
    }

    private suspend fun TaskListPipelineContext.saveFilterPanelOpen(isOpen: Boolean) {
        repository.saveFilterPanelOpen(spaceId, isOpen)
        updateState { copy(filterPanelOpen = isOpen) }
    }

    private suspend fun TaskListPipelineContext.saveSavedFilter(filter: SavedFilter) {
        repository.saveSavedFilter(filter)
    }

    // Utility functions that don't need state
    suspend fun getSavedFilterById(filterId: String): SavedFilter? =
        repository.getSavedFilterById(spaceId, filterId)

    suspend fun getViewModeById(viewModeId: String): ViewMode? =
        repository.getViewModeById(spaceId, viewModeId)

    fun generateId(): String = generateIdImpl()

    // Functions for DynamicTaskList that need spaceId bound
    suspend fun getTaskGroups(
        viewMode: ViewMode,
        levelIndex: Int,
        parentFilters: List<GroupFilter>,
        filterCriteria: TaskFilterCriteria
    ): List<TaskGroupInfo> = repository.getTaskGroups(spaceId, viewMode, levelIndex, parentFilters, filterCriteria)

    suspend fun getTasksForGroup(
        filters: List<GroupFilter>,
        orderingRules: List<OrderingRule>,
        filterCriteria: TaskFilterCriteria
    ): List<TaskWithTotals> = repository.getTasksForGroup(spaceId, filters, orderingRules, filterCriteria)
}

/**
 * Factory interface for creating TaskListContainer instances with runtime parameters.
 */
fun interface TaskListContainerFactory {
    fun create(spaceId: String): TaskListContainer
}
