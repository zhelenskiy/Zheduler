@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhelenskiy.zheduler.zheduler.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

class TaskListViewModel(
    private val repository: TaskRepository,
    private val spaceId: String
) : ViewModel() {

    private val _tasksWithTotals = MutableStateFlow<List<TaskWithTotals>>(emptyList())
    val tasksWithTotals: StateFlow<List<TaskWithTotals>> = _tasksWithTotals.asStateFlow()

    private val _currentSpace = MutableStateFlow<Space?>(null)
    val currentSpace: StateFlow<Space?> = _currentSpace.asStateFlow()

    private val _allTags = MutableStateFlow<Set<String>>(emptySet())
    val allTags: StateFlow<Set<String>> = _allTags.asStateFlow()

    init {
        loadTasks()
    }

    fun loadTasks() {
        viewModelScope.launch {
            _tasksWithTotals.value = repository.getAllTasksWithTotals(spaceId)
            _currentSpace.value = repository.getSpaceById(spaceId)
            _allTags.value = repository.getAllTags()
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            repository.deleteTask(taskId)
            loadTasks()
        }
    }

    /**
     * Get filtered tasks based on the provided criteria
     */
    suspend fun getFilteredTasks(criteria: TaskFilterCriteria): List<TaskWithTotals> =
        repository.getAllWithTotalsFiltered(spaceId, criteria)

    // ============ View mode operations ============

    suspend fun getAllViewModes(): List<ViewMode> =
        repository.getAllViewModes(spaceId)

    suspend fun getActiveViewMode(): ViewMode =
        repository.getActiveViewMode(spaceId)

    suspend fun setActiveViewMode(viewModeId: String) {
        repository.setActiveViewMode(spaceId, viewModeId)
    }

    // ============ Filter state operations ============

    suspend fun getFilterState(): TaskFilterCriteria =
        repository.getFilterState(spaceId)

    suspend fun saveFilterState(criteria: TaskFilterCriteria) {
        repository.saveFilterState(spaceId, criteria)
    }

    suspend fun getFilterPanelOpen(): Boolean =
        repository.getFilterPanelOpen(spaceId)

    suspend fun saveFilterPanelOpen(isOpen: Boolean) {
        repository.saveFilterPanelOpen(spaceId, isOpen)
    }

    // ============ Task group operations ============

    suspend fun getTaskGroups(
        viewMode: ViewMode,
        levelIndex: Int,
        parentFilters: List<GroupFilter>,
        filterCriteria: TaskFilterCriteria
    ): List<TaskGroupInfo> =
        repository.getTaskGroups(spaceId, viewMode, levelIndex, parentFilters, filterCriteria)

    suspend fun getTasksForGroup(
        filters: List<GroupFilter>,
        orderingRules: List<OrderingRule>,
        filterCriteria: TaskFilterCriteria
    ): List<TaskWithTotals> =
        repository.getTasksForGroup(spaceId, filters, orderingRules, filterCriteria)
}
