@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.paging.InvalidatingPagingSourceFactory
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.paging.PagingDefaults
import com.zhelenskiy.zheduler.zheduler.paging.tasksForGroupPagingSource
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.generateId as generateIdImpl
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val hasAnyTasks: Boolean = false,
    val currentSpace: Space? = null,
    val allTags: Set<String>? = null, // null = not loaded yet
    val viewModes: List<ViewMode> = emptyList(),
    val activeViewMode: ViewMode? = null,
    val filterState: TaskFilterCriteria? = null,
    val filterPanelOpen: Boolean? = null
) : MVIState

sealed interface TaskListIntent : MVIIntent {
    data object LoadTasks : TaskListIntent
    data object LoadAllTags : TaskListIntent
    data class DeleteTask(val taskId: String) : TaskListIntent
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
    val spaceId: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ScopedContainer(dispatcher), Container<TaskListState, TaskListIntent, TaskListAction> {

    override val store = store(TaskListState(), scope) {
        reportingFailuresAs("TaskListStore")

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
                is TaskListIntent.LoadAllTags -> loadAllTags()
                is TaskListIntent.DeleteTask -> deleteTask(intent.taskId)
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
        val hasAny = repository.hasAnyTasks(spaceId)
        val space = repository.getSpaceById(spaceId)
        updateState {
            copy(
                hasAnyTasks = hasAny,
                currentSpace = space
            )
        }
    }

    private suspend fun TaskListPipelineContext.loadAllTags() {
        val tags = repository.getAllTags(spaceId)
        updateState { copy(allTags = tags) }
    }

    private suspend fun TaskListPipelineContext.deleteTask(taskId: String) {
        repository.deleteTask(taskId)
        loadTasks()
        action(TaskListAction.TaskDeleted(true))
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
        parentFilters: PersistentList<GroupFilter>,
        filterCriteria: TaskFilterCriteria
    ): List<TaskGroupInfo> = repository.getTaskGroups(spaceId, viewMode, levelIndex, parentFilters, filterCriteria)

    // ============ Paged group contents ============

    private data class GroupPagingKey(
        val filters: PersistentList<GroupFilter>,
        val orderingRules: PersistentList<OrderingRule>,
    )

    private val groupPages = mutableMapOf<GroupPagingKey, Flow<PagingData<TaskWithTotals>>>()
    private val groupFactories = mutableListOf<InvalidatingPagingSourceFactory<Int, TaskWithTotals>>()
    private var groupPagesCriteria: TaskFilterCriteria? = null
    private var groupPagesViewMode: ViewMode? = null

    /**
     * The scope the current criteria's cached pages live in, replaced whenever they change.
     *
     * `cachedIn` keeps a flow alive for as long as its scope is, so dropping the map left every
     * previous set still running and still holding the rows it had loaded. On the screen where the
     * user types into a search box, that is a set stranded per keystroke per open group, for as
     * long as the screen is up.
     */
    private var groupPagesScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))

    private val _dataVersion = MutableStateFlow(0L)

    /**
     * Bumped on every stored-data change. The paged lists reload themselves through their paging
     * sources; the group tree around them — labels and counts — is plain state, and reloads when
     * the screen sees this change.
     */
    val dataVersion: StateFlow<Long> = _dataVersion.asStateFlow()

    init {
        // A task edit can move rows into or out of any expanded group, so refresh them all.
        scope.launch {
            repository.changes.collect {
                groupFactories.forEach { it.invalidate() }
                _dataVersion.value++
            }
        }
    }

    /**
     * Pages of one leaf group's tasks.
     *
     * Flows are cached per group so that collapsing and expanding a group — or a recomposition —
     * resumes where the user was instead of reloading from the top. Changing the filter criteria
     * makes every cached group obsolete at once, so the cache is dropped wholesale.
     */
    fun tasksForGroupPages(
        filters: PersistentList<GroupFilter>,
        orderingRules: PersistentList<OrderingRule>,
        filterCriteria: TaskFilterCriteria,
        viewMode: ViewMode,
    ): Flow<PagingData<TaskWithTotals>> {
        // The view mode counts as much as the criteria: its groups are different groups, and the
        // ones cached before it went on running, holding the rows they had loaded. Compared whole
        // rather than by id, because editing a mode keeps its id while changing every group in it.
        if (filterCriteria != groupPagesCriteria || viewMode != groupPagesViewMode) {
            groupPages.clear()
            groupFactories.clear()
            groupPagesScope.cancel()
            groupPagesScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
            groupPagesCriteria = filterCriteria
            groupPagesViewMode = viewMode
        }
        return groupPages.getOrPut(GroupPagingKey(filters, orderingRules)) {
            val factory = InvalidatingPagingSourceFactory {
                repository.tasksForGroupPagingSource(spaceId, filters, orderingRules, filterCriteria)
            }
            groupFactories += factory
            // See PagedQuery: the factory is invoked through a lambda so this compiles on every target.
            Pager(PagingDefaults.taskList, pagingSourceFactory = { factory() }).flow.cachedIn(groupPagesScope)
        }
    }

    /** How many tasks a group holds, for the header and the empty state. */
    suspend fun countTasksForGroup(
        filters: PersistentList<GroupFilter>,
        filterCriteria: TaskFilterCriteria
    ): Int = repository.countTasksForGroup(spaceId, filters, filterCriteria)
}

/**
 * Factory interface for creating TaskListContainer instances with runtime parameters.
 */
fun interface TaskListContainerFactory {
    fun create(spaceId: String): TaskListContainer
}
