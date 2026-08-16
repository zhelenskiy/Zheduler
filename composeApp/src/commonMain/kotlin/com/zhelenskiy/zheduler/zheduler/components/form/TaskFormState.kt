@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.runtime.*
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.parseCompactTimeToPeriod
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Stable
class TaskFormState(
    initialTitle: String,
    initialDescription: String,
    initialPriority: String,
    initialEstimatedTime: String,
    initialTags: PersistentSet<String>,
    initialDueDate: Instant?,
    initialStatus: TaskStatus,
    initialConnections: PersistentSet<TaskConnection>,
    initialNotifications: PersistentList<String>, // Compact time strings (e.g., "1d", "2h 30m")
    initialRecurrenceRules: PersistentList<Pair<RecurrenceRule, RecurrenceState>>,
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
    var autoUpdateStatusFromSubtasks by mutableStateOf(initialAutoUpdateStatusFromSubtasks)

    private var nextEntryId = 0L

    var notifications by mutableStateOf(initialNotifications)
        private set
    var recurrenceRules by mutableStateOf(initialRecurrenceRules)
        private set

    /**
     * An identity per entry, kept alongside the two editable lists and reused as their list keys.
     *
     * Neither list has anything to be keyed by: notifications are plain strings that repeat, and a
     * rule is only its own definition. Keyed by position instead, deleting from the middle animates
     * as though the last row went, because every row below it changes key.
     */
    var notificationIds by mutableStateOf(freshIds(initialNotifications.size))
        private set
    var recurrenceRuleIds by mutableStateOf(freshIds(initialRecurrenceRules.size))
        private set

    private fun freshIds(count: Int): PersistentList<Long> =
        List(count) { nextEntryId++ }.toPersistentList()

    fun addNotification() {
        notifications = notifications.adding("")
        notificationIds = notificationIds.adding(nextEntryId++)
    }

    fun updateNotification(index: Int, value: String) {
        notifications = notifications.replacingAt(index, value)
    }

    fun removeNotification(index: Int) {
        notifications = notifications.removingAt(index)
        notificationIds = notificationIds.removingAt(index)
    }

    /** Replaces the rule at [index], or appends it when [index] is past the end. */
    fun setRecurrenceRule(index: Int, entry: Pair<RecurrenceRule, RecurrenceState>) {
        if (index < recurrenceRules.size) {
            recurrenceRules = recurrenceRules.replacingAt(index, entry)
        } else {
            recurrenceRules = recurrenceRules.adding(entry)
            recurrenceRuleIds = recurrenceRuleIds.adding(nextEntryId++)
        }
    }

    fun removeRecurrenceRule(index: Int) {
        recurrenceRules = recurrenceRules.removingAt(index)
        recurrenceRuleIds = recurrenceRuleIds.removingAt(index)
    }

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
            priority = if (priority.isNotEmpty()) Priority(priority.toInt()) else null,
            estimatedTime = parseCompactTimeToPeriod(estimatedTime),
            tags = tags,
            dueDate = dueDate,
            status = status,
            connections = connections,
            notifications = notifications
                .takeIf { dueDate != null }
                ?.mapNotNullToPersistentList { parseCompactTimeToPeriod(it)?.let(::TaskNotification) }
                ?: persistentListOf(),
            recurrenceRules = recurrenceRules,
            autoUpdateStatusFromSubtasks = autoUpdateStatusFromSubtasks
        )
    }

    fun resetTo(task: Task) {
        title = task.title
        description = task.description
        priority = task.priority?.value?.toString() ?: ""
        estimatedTime = task.estimatedTime?.toBriefString() ?: ""
        tags = task.tags
        dueDate = task.dueDate
        status = task.status
        connections = task.connections
        notifications = task.notifications.mapToPersistentList { it.timeBeforeDeadline.toBriefString() }
        recurrenceRules = task.recurrenceRules
        notificationIds = freshIds(notifications.size)
        recurrenceRuleIds = freshIds(recurrenceRules.size)
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
    val tags: PersistentSet<String>,
    val dueDate: Instant?,
    val status: TaskStatus,
    val connections: PersistentSet<TaskConnection>,
    val notifications: PersistentList<TaskNotification> = persistentListOf(),
    val recurrenceRules: PersistentList<Pair<RecurrenceRule, RecurrenceState>> = persistentListOf(),
    val autoUpdateStatusFromSubtasks: Boolean = false
)

@Composable
fun rememberTaskFormState(
    initialTitle: String = "",
    initialDescription: String = "",
    initialPriority: String = "",
    initialEstimatedTime: String = "",
    initialTags: PersistentSet<String> = persistentSetOf(),
    initialDueDate: Instant? = null,
    initialStatus: TaskStatus = TaskStatus.Open,
    initialConnections: PersistentSet<TaskConnection> = persistentSetOf(),
    initialNotifications: PersistentList<String> = persistentListOf(),
    initialRecurrenceRules: PersistentList<Pair<RecurrenceRule, RecurrenceState>> = persistentListOf(),
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
            initialNotifications = task.notifications.mapToPersistentList { it.timeBeforeDeadline.toBriefString() },
            initialRecurrenceRules = task.recurrenceRules,
            initialAutoUpdateStatusFromSubtasks = task.autoUpdateStatusFromSubtasks
        )
    }
}
