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

enum class TaskListViewMode {
    Chronological, Priority;

    val displayName: String
        get() = when (this) {
            Chronological -> "Chronological"
            Priority -> "Priority"
        }
}

class TaskListViewModel(
    private val repository: TaskRepository,
    private val spaceId: String
) : ViewModel() {

    private val _tasksWithTotals = MutableStateFlow<List<TaskWithTotals>>(emptyList())
    val tasksWithTotals: StateFlow<List<TaskWithTotals>> = _tasksWithTotals.asStateFlow()

    private val _currentSpace = MutableStateFlow<Space?>(null)
    val currentSpace: StateFlow<Space?> = _currentSpace.asStateFlow()

    private val _spaceLoadAttempted = MutableStateFlow(false)
    val spaceLoadAttempted: StateFlow<Boolean> = _spaceLoadAttempted.asStateFlow()

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
            _spaceLoadAttempted.value = true
        }
    }

    suspend fun getFilterCriteria(): TaskFilterCriteria {
        return repository.getFilterState(spaceId)
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            repository.deleteTask(taskId)
            loadTasks()
        }
    }

    fun updateTaskStatus(taskId: String, status: TaskStatus) {
        viewModelScope.launch {
            val task = repository.getTaskById(taskId)
            if (task != null) {
                repository.updateTask(task.copy(status = status))
            }
            loadTasks()
        }
    }

    /**
     * Get filtered tasks based on the provided criteria
     */
    suspend fun getFilteredTasks(criteria: TaskFilterCriteria): List<TaskWithTotals> =
        repository.getAllWithTotalsFiltered(spaceId, criteria)

    /**
     * Get tasks grouped by resolution status using current filter state
     */
    suspend fun getTasksGroupedByResolutionStatus(): GroupedTasks {
        val filteredTasks = repository.getAllWithTotalsFiltered(spaceId, getFilterCriteria())
        return repository.groupTasksByResolutionStatus(filteredTasks)
    }
}
