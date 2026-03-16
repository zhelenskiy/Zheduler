@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhelenskiy.zheduler.zheduler.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val KEY_FORM_TITLE = "formTitle"
private const val KEY_FORM_DESCRIPTION = "formDescription"
private const val KEY_FORM_PRIORITY = "formPriority"
private const val KEY_FORM_ESTIMATED_TIME = "formEstimatedTime"
private const val KEY_FORM_TAGS = "formTags"
private const val KEY_FORM_DUE_DATE = "formDueDate"

class TaskEditViewModel(
    private val repository: TaskRepository,
    private val spaceId: String,
    private val taskId: String,
    private val savedStateHandle: SavedStateHandle
) : ViewModel(), TaskFormDataProvider {

    private val _task = MutableStateFlow<Task?>(null)
    val task: StateFlow<Task?> = _task.asStateFlow()

    init {
        loadTask()
    }

    fun loadTask() {
        viewModelScope.launch {
            _task.value = repository.getTaskById(taskId)
        }
    }

    fun getPersistedFormState(): PersistedFormState {
        val tags = savedStateHandle.get<String>(KEY_FORM_TAGS)
            ?.let { tagsJson -> runCatching { Json.decodeFromString<Set<String>>(tagsJson) }.getOrNull() }
            ?: emptySet()

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

    fun persistFormState(
        title: String,
        description: String,
        priority: String,
        estimatedTime: String,
        tags: Set<String>,
        dueDate: Instant?
    ) {
        savedStateHandle[KEY_FORM_TITLE] = title
        savedStateHandle[KEY_FORM_DESCRIPTION] = description
        savedStateHandle[KEY_FORM_PRIORITY] = priority
        savedStateHandle[KEY_FORM_ESTIMATED_TIME] = estimatedTime
        savedStateHandle[KEY_FORM_TAGS] = Json.encodeToString(tags)
        savedStateHandle[KEY_FORM_DUE_DATE] = dueDate?.toEpochMilliseconds()
    }

    fun clearPersistedFormState() {
        savedStateHandle.remove<String>(KEY_FORM_TITLE)
        savedStateHandle.remove<String>(KEY_FORM_DESCRIPTION)
        savedStateHandle.remove<String>(KEY_FORM_PRIORITY)
        savedStateHandle.remove<String>(KEY_FORM_ESTIMATED_TIME)
        savedStateHandle.remove<String>(KEY_FORM_TAGS)
        savedStateHandle.remove<Long>(KEY_FORM_DUE_DATE)
    }

    fun saveTask(updatedTask: Task) {
        viewModelScope.launch {
            repository.updateTask(updatedTask)
            clearPersistedFormState()
        }
    }

    override suspend fun getTaskById(id: String): Task? = repository.getTaskById(id)

    override suspend fun filterTags(searchQuery: String, excludeTags: Set<String>): List<String> =
        repository.filterTags(spaceId, searchQuery, excludeTags)

    override suspend fun filterTasksForSelection(searchQuery: String): List<Task> =
        repository.filterTasksForSelection(spaceId, taskId, searchQuery)

    override suspend fun searchTasksForConnection(
        searchQuery: String,
        excludeTaskIds: Set<String>,
        connectionType: ConnectionType,
        existingConnections: Set<TaskConnection>
    ): List<Task> = repository.searchTasksForConnection(
        spaceId = spaceId,
        excludeTaskId = taskId,
        searchQuery = searchQuery,
        excludeTaskIds = excludeTaskIds,
        connectionType = connectionType,
        existingConnections = existingConnections
    )

    override suspend fun getCalculatedStatusFromSubtasks(id: String): TaskStatus? =
        repository.getCalculatedStatusFromSubtasks(id)

    override suspend fun getCurrentSpaceIdPrefix(): String? = repository.getSpaceById(spaceId)?.idPrefix

    override suspend fun getAllSpacePrefixes(): List<String> = repository.getAllSpacePrefixes()
}
