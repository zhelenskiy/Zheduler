@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.db.SqlDelightTaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val KEY_IS_EDITING = "isEditing"
private const val KEY_FORM_TITLE = "formTitle"
private const val KEY_FORM_DESCRIPTION = "formDescription"
private const val KEY_FORM_PRIORITY = "formPriority"
private const val KEY_FORM_ESTIMATED_TIME = "formEstimatedTime"
private const val KEY_FORM_TAGS = "formTags"
private const val KEY_FORM_DUE_DATE = "formDueDate"

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
    private val repository: SqlDelightTaskRepository,
    private val spaceId: String,
    private val taskId: String,
    private val savedStateHandle: SavedStateHandle,
    startInEditMode: Boolean = false
) : ViewModel(), TaskFormDataProvider {

    private val _taskWithTotals = MutableStateFlow<TaskWithTotals?>(null)
    val taskWithTotals: StateFlow<TaskWithTotals?> = _taskWithTotals.asStateFlow()

    private val _taskLoadAttempted = MutableStateFlow(false)
    val taskLoadAttempted: StateFlow<Boolean> = _taskLoadAttempted.asStateFlow()

    private val _connectionsByType = MutableStateFlow<Map<ConnectionType, List<Task>>>(emptyMap())
    val connectionsByType: StateFlow<Map<ConnectionType, List<Task>>> = _connectionsByType.asStateFlow()

    // Editing state managed by ViewModel
    private val _isEditing = MutableStateFlow(savedStateHandle.get<Boolean>(KEY_IS_EDITING) ?: startInEditMode)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    init {
        loadTask()
    }

    fun loadTask() {
        viewModelScope.launch {
            _taskWithTotals.value = repository.getByIdWithTotals(taskId)
            _connectionsByType.value = repository.getConnectionsByType(taskId)
            _taskLoadAttempted.value = true
        }
    }

    fun setEditing(editing: Boolean) {
        _isEditing.value = editing
        savedStateHandle[KEY_IS_EDITING] = editing
    }

    fun startEditing() {
        setEditing(true)
    }

    /**
     * Restore persisted form state from SavedStateHandle (when returning from nested task creation)
     */
    fun getPersistedFormState(): PersistedFormState {
        val tags = savedStateHandle.get<String>(KEY_FORM_TAGS)?.let { tagsStr ->
            if (tagsStr.isNotEmpty()) tagsStr.split(",").toSet() else emptySet()
        } ?: emptySet()

        val dueDate = savedStateHandle.get<Long>(KEY_FORM_DUE_DATE)?.let { epochMillis ->
            Instant.fromEpochMilliseconds(epochMillis)
        }

        return PersistedFormState(
            title = savedStateHandle.get<String>(KEY_FORM_TITLE),
            description = savedStateHandle.get<String>(KEY_FORM_DESCRIPTION),
            priority = savedStateHandle.get<String>(KEY_FORM_PRIORITY),
            estimatedTime = savedStateHandle.get<String>(KEY_FORM_ESTIMATED_TIME),
            tags = tags,
            dueDate = dueDate
        )
    }

    /**
     * Persist form state to SavedStateHandle (for when navigating away to create nested task)
     */
    fun persistFormState(
        title: String,
        description: String,
        priority: String,
        estimatedTime: String,
        tags: Set<String>,
        dueDate: Instant?
    ) {
        if (_isEditing.value) {
            savedStateHandle[KEY_FORM_TITLE] = title
            savedStateHandle[KEY_FORM_DESCRIPTION] = description
            savedStateHandle[KEY_FORM_PRIORITY] = priority
            savedStateHandle[KEY_FORM_ESTIMATED_TIME] = estimatedTime
            savedStateHandle[KEY_FORM_TAGS] = tags.joinToString(",")
            savedStateHandle[KEY_FORM_DUE_DATE] = dueDate?.toEpochMilliseconds()
        }
    }

    /**
     * Clear persisted form state after save or cancel
     */
    fun clearPersistedFormState() {
        savedStateHandle.remove<String>(KEY_FORM_TITLE)
        savedStateHandle.remove<String>(KEY_FORM_DESCRIPTION)
        savedStateHandle.remove<String>(KEY_FORM_PRIORITY)
        savedStateHandle.remove<String>(KEY_FORM_ESTIMATED_TIME)
        savedStateHandle.remove<String>(KEY_FORM_TAGS)
        savedStateHandle.remove<Long>(KEY_FORM_DUE_DATE)
    }

    /**
     * Save task changes and exit edit mode
     */
    fun saveTask(updatedTask: Task) {
        viewModelScope.launch {
            repository.update(updatedTask)
            setEditing(false)
            clearPersistedFormState()
            loadTask()
        }
    }

    /**
     * Cancel editing and reset form state
     */
    fun cancelEditing() {
        setEditing(false)
        clearPersistedFormState()
    }

    fun updateTask(task: Task) {
        viewModelScope.launch {
            repository.update(task)
            loadTask()
        }
    }

    override suspend fun getTaskById(id: String): Task? = repository.getById(id)

    override suspend fun getAllTags(): Set<String> = repository.getAllTags()

    override suspend fun getAvailableTasks(): List<Task> = repository.getAllExcept(spaceId, taskId)

    override suspend fun searchTasksForConnection(
        searchQuery: String,
        excludeTaskIds: Set<String>,
        connectionType: ConnectionType,
        existingConnections: Set<TaskConnection>
    ): List<Task> {
        // Repository handles all filtering including SQL-based search and cycle detection
        return repository.searchTasksForConnection(
            spaceId = spaceId,
            excludeTaskId = taskId,
            searchQuery = searchQuery,
            excludeTaskIds = excludeTaskIds,
            connectionType = connectionType,
            existingConnections = existingConnections
        )
    }

    override suspend fun wouldCreateCycle(
        currentId: String,
        targetId: String,
        connectionType: ConnectionType,
        existingConnections: Set<TaskConnection>
    ): Boolean = repository.wouldCreateCycle(currentId, targetId, connectionType, existingConnections)

    override suspend fun getCalculatedStatusFromSubtasks(id: String): TaskStatus? =
        repository.getCalculatedStatusFromSubtasks(id)

    override suspend fun getCurrentSpaceIdPrefix(): String? = repository.getSpaceById(spaceId)?.idPrefix

    override suspend fun getAllSpacePrefixes(): List<String> = repository.getAllSpacePrefixes()

    fun refreshConnectionsByType(connections: Set<TaskConnection>) {
        viewModelScope.launch {
            _connectionsByType.value = repository.resolveConnections(connections)
        }
    }

    suspend fun getStatusTimeline(taskId: String): List<StatusChange> = repository.getStatusTimeline(taskId)
}
