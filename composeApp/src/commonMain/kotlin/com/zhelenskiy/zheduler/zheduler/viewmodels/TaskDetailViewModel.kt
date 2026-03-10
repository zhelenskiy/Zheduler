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

class TaskDetailViewModel(
    private val repository: TaskRepository,
    private val spaceId: String,
    private val taskId: String
) : ViewModel() {

    private val _taskWithTotals = MutableStateFlow<TaskWithTotals?>(null)
    val taskWithTotals: StateFlow<TaskWithTotals?> = _taskWithTotals.asStateFlow()

    private val _connectionsByType = MutableStateFlow<Map<ConnectionType, List<Task>>>(emptyMap())
    val connectionsByType: StateFlow<Map<ConnectionType, List<Task>>> = _connectionsByType.asStateFlow()

    init {
        loadTask()
    }

    fun loadTask() {
        viewModelScope.launch {
            _taskWithTotals.value = repository.getTasksByIdWithTotals(taskId)
            _connectionsByType.value = repository.getConnectionsByType(taskId)
        }
    }

    suspend fun getTaskById(id: String): Task? = repository.getTaskById(id)

    suspend fun getCurrentSpaceIdPrefix(): String? = repository.getSpaceById(spaceId)?.idPrefix

    suspend fun getAllSpacePrefixes(): List<String> = repository.getAllSpacePrefixes()

    suspend fun getStatusTimeline(taskId: String): List<StatusChange> = repository.getStatusTimeline(taskId)
}
