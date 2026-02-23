@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.runtime.*
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.parseCompactTimeToPeriod
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Stable
class TaskFormState(
    initialTitle: String,
    initialDescription: String,
    initialPriority: String,
    initialEstimatedTime: String,
    initialTags: Set<String>,
    initialDueDate: Instant?,
    initialStatus: TaskStatus,
    initialConnections: Set<TaskConnection>,
    initialNotifications: List<String>, // Compact time strings (e.g., "1d", "2h 30m")
    initialRecurrenceRules: List<Pair<RecurrenceRule, RecurrenceState>>,
    initialAutoUpdateStatusFromSubtasks: Boolean
) {
    var title by mutableStateOf(initialTitle)
    var description by mutableStateOf(initialDescription)
    var priority by mutableStateOf(initialPriority)
    var estimatedTime by mutableStateOf(initialEstimatedTime)
    var tags by mutableStateOf(initialTags)
    var dueDate by mutableStateOf(initialDueDate)
    var status by mutableStateOf(initialStatus)
    var connections by mutableStateOf(initialConnections)
    var notifications by mutableStateOf(initialNotifications)
    var recurrenceRules by mutableStateOf(initialRecurrenceRules)
    var autoUpdateStatusFromSubtasks by mutableStateOf(initialAutoUpdateStatusFromSubtasks)

    val isFormValid: Boolean
        get() = title.isNotBlank() &&
                (priority.isEmpty() || priority.toIntOrNull()?.let { it in 1..100 } == true) &&
                (estimatedTime.isEmpty() || parseCompactTimeToPeriod(estimatedTime) != null) &&
                notifications.all { it.isNotBlank() && parseCompactTimeToPeriod(it) != null }

    fun validate(): Boolean {
        if (title.isBlank()) return false
        if (priority.isNotEmpty() && priority.toIntOrNull()?.let { it in 1..100 } != true) return false
        if (estimatedTime.isNotEmpty() && parseCompactTimeToPeriod(estimatedTime) == null) return false
        if (notifications.any { it.isBlank() || parseCompactTimeToPeriod(it) == null }) return false
        return true
    }

    fun toParsedValues(): ParsedTaskValues? {
        if (!validate()) return null
        return ParsedTaskValues(
            title = title,
            description = description,
            priority = priority.toIntOrNull()?.let { if (it in 1..100) Priority(it) else null },
            estimatedTime = parseCompactTimeToPeriod(estimatedTime),
            tags = tags,
            dueDate = dueDate,
            status = status,
            connections = connections,
            notifications = notifications
                .takeIf { dueDate != null }
                ?.mapNotNull { parseCompactTimeToPeriod(it) }
                ?.map { TaskNotification(it) }
                ?: emptyList(),
            recurrenceRules = recurrenceRules,
            autoUpdateStatusFromSubtasks = autoUpdateStatusFromSubtasks
        )
    }

    fun resetTo(task: Task) {
        title = task.title
        description = task.description
        priority = task.priority?.value?.toString() ?: ""
        estimatedTime = task.estimatedTime?.let { it.toBriefString() } ?: ""
        tags = task.tags
        dueDate = task.dueDate
        status = task.status
        connections = task.connections
        notifications = task.notifications.map { it.timeBeforeDeadline.toBriefString() }
        recurrenceRules = task.recurrenceRules
        autoUpdateStatusFromSubtasks = task.autoUpdateStatusFromSubtasks
    }

    fun hasUnsavedChanges(task: Task): Boolean {
        val expectedPriority = task.priority?.value?.toString() ?: ""
        val expectedEstimatedTime = task.estimatedTime?.toBriefString() ?: ""
        val expectedNotifications = task.notifications.map { it.timeBeforeDeadline.toBriefString() }

        return title != task.title ||
                description != task.description ||
                priority != expectedPriority ||
                estimatedTime != expectedEstimatedTime ||
                tags != task.tags ||
                dueDate != task.dueDate ||
                status != task.status ||
                connections != task.connections ||
                notifications != expectedNotifications ||
                recurrenceRules != task.recurrenceRules ||
                autoUpdateStatusFromSubtasks != task.autoUpdateStatusFromSubtasks
    }

    fun hasAnyContent(initialConnections: Set<TaskConnection> = emptySet()): Boolean {
        return title.isNotBlank() ||
                description.isNotBlank() ||
                priority.isNotBlank() ||
                estimatedTime.isNotBlank() ||
                tags.isNotEmpty() ||
                dueDate != null ||
                status != TaskStatus.Open ||
                connections != initialConnections ||
                notifications.isNotEmpty() ||
                recurrenceRules.isNotEmpty() ||
                autoUpdateStatusFromSubtasks
    }
}

data class ParsedTaskValues(
    val title: String,
    val description: String,
    val priority: Priority?,
    val estimatedTime: RecurrencePeriod?,
    val tags: Set<String>,
    val dueDate: Instant?,
    val status: TaskStatus,
    val connections: Set<TaskConnection>,
    val notifications: List<TaskNotification> = emptyList(),
    val recurrenceRules: List<Pair<RecurrenceRule, RecurrenceState>> = emptyList(),
    val autoUpdateStatusFromSubtasks: Boolean = false
)

@Composable
fun rememberTaskFormState(
    initialTitle: String = "",
    initialDescription: String = "",
    initialPriority: String = "",
    initialEstimatedTime: String = "",
    initialTags: Set<String> = emptySet(),
    initialDueDate: Instant? = null,
    initialStatus: TaskStatus = TaskStatus.Open,
    initialConnections: Set<TaskConnection> = emptySet(),
    initialNotifications: List<String> = emptyList(),
    initialRecurrenceRules: List<Pair<RecurrenceRule, RecurrenceState>> = emptyList(),
    initialAutoUpdateStatusFromSubtasks: Boolean = false
): TaskFormState {
    return remember {
        TaskFormState(
            initialTitle = initialTitle,
            initialDescription = initialDescription,
            initialPriority = initialPriority,
            initialEstimatedTime = initialEstimatedTime,
            initialTags = initialTags,
            initialDueDate = initialDueDate,
            initialStatus = initialStatus,
            initialConnections = initialConnections,
            initialNotifications = initialNotifications,
            initialRecurrenceRules = initialRecurrenceRules,
            initialAutoUpdateStatusFromSubtasks = initialAutoUpdateStatusFromSubtasks
        )
    }
}

@Composable
fun rememberTaskFormState(task: Task): TaskFormState {
    return remember {
        TaskFormState(
            initialTitle = task.title,
            initialDescription = task.description,
            initialPriority = task.priority?.value?.toString() ?: "",
            initialEstimatedTime = task.estimatedTime?.toBriefString() ?: "",
            initialTags = task.tags,
            initialDueDate = task.dueDate,
            initialStatus = task.status,
            initialConnections = task.connections,
            initialNotifications = task.notifications.map { it.timeBeforeDeadline.toBriefString() },
            initialRecurrenceRules = task.recurrenceRules,
            initialAutoUpdateStatusFromSubtasks = task.autoUpdateStatusFromSubtasks
        )
    }
}
