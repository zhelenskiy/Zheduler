@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.lifecycle.ViewModel
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.db.SqlDelightTaskRepository
import kotlin.time.ExperimentalTime

class NewTaskViewModel(
    private val repository: SqlDelightTaskRepository,
    private val spaceId: String,
    private val prefilledConnection: TaskConnection?,
    private val taskIdToCopy: String?
) : ViewModel(), TaskFormDataProvider {

    suspend fun getTaskToCopy(): Task? = taskIdToCopy?.let { repository.getById(it) }

    suspend fun getPrefilledTask(): Task? = prefilledConnection?.let { repository.getById(it.targetTaskId) }

    suspend fun getNextId(): String = repository.peekNextId(spaceId)

    fun getInitialConnections(): Set<TaskConnection> = prefilledConnection?.let { setOf(it) } ?: emptySet()

    suspend fun createTask(
        title: String,
        description: String,
        status: TaskStatus,
        dueDate: kotlin.time.Instant?,
        priority: Priority?,
        estimatedTime: RecurrencePeriod?,
        tags: Set<String>,
        connections: Set<TaskConnection>,
        notifications: List<TaskNotification>,
        recurrenceRule: RecurrenceRule?,
        resetStatusOnRecurrence: TaskStatus,
        autoUpdateStatusFromSubtasks: Boolean
    ): Task? {
        return repository.add(
            spaceId = spaceId,
            title = title,
            description = description,
            status = status,
            dueDate = dueDate,
            priority = priority,
            estimatedTime = estimatedTime,
            tags = tags,
            connections = connections,
            notifications = notifications,
            recurrenceRule = recurrenceRule,
            resetStatusOnRecurrence = resetStatusOnRecurrence,
            autoUpdateStatusFromSubtasks = autoUpdateStatusFromSubtasks
        )
    }

    override suspend fun getTaskById(id: String): Task? = repository.getById(id)

    override suspend fun getAllTags(): Set<String> = repository.getAllTags()

    override suspend fun filterTags(searchQuery: String, excludeTags: Set<String>): List<String> =
        repository.filterTags(searchQuery, excludeTags)

    override suspend fun getAvailableTasks(): List<Task> {
        val nextId = getNextId()
        return repository.getAllExcept(spaceId, nextId)
    }

    override suspend fun filterTasksForSelection(searchQuery: String): List<Task> {
        val nextId = getNextId()
        return repository.filterTasksForSelection(spaceId, nextId, searchQuery)
    }

    override suspend fun searchTasksForConnection(
        searchQuery: String,
        excludeTaskIds: Set<String>,
        connectionType: ConnectionType,
        existingConnections: Set<TaskConnection>
    ): List<Task> {
        val nextId = getNextId()
        // Repository handles all filtering including SQL-based search and cycle detection
        return repository.searchTasksForConnection(
            spaceId = spaceId,
            excludeTaskId = nextId,
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
}
