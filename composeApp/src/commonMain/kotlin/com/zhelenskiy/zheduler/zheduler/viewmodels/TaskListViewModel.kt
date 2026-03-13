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
}
