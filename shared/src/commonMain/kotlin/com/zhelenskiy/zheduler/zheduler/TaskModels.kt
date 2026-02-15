@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Custom serializer for kotlin.time.Instant
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilliseconds())
    override fun deserialize(decoder: Decoder): Instant = Instant.fromEpochMilliseconds(decoder.decodeLong())
}

/**
 * Priority value from 1 to 100 (representing 1/100 to 100/100)
 */
@Serializable
@JvmInline
value class Priority(val value: Int) : Comparable<Priority> {
    init {
        require(value in 1..100) { "Priority must be between 1 and 100" }
    }

    override fun compareTo(other: Priority): Int = value.compareTo(other.value)

    companion object {
        val MIN = Priority(1)
        val LOW = Priority(25)
        val MEDIUM = Priority(50)
        val HIGH = Priority(75)
        val MAX = Priority(100)
    }
}

/**
 * Task status representing the current state of a task
 */
@Serializable
sealed class TaskStatus {
    @Serializable
    data object Open : TaskStatus()
    
    @Serializable
    data class Blocked(val blockerTaskIds: Set<String>, val comment: String = "") : TaskStatus()
    
    @Serializable
    data object InProgress : TaskStatus()
    
    @Serializable
    data object Done : TaskStatus()
    
    @Serializable
    data class Declined(val reason: String) : TaskStatus()
    
    val displayName: String get() = when (this) {
        is Open -> "Open"
        is Blocked -> "Blocked"
        is InProgress -> "In Progress"
        is Done -> "Done"
        is Declined -> "Declined"
    }
}

/**
 * Connection type between tasks
 */
@Serializable
enum class ConnectionType {
    RelatesTo,
    DependsOn,
    IsDependencyOf,
    SubtaskOf,
    ParentOf;

    val displayName: String get() = when (this) {
        RelatesTo -> "Relates to"
        DependsOn -> "Depends on"
        IsDependencyOf -> "Is dependency of"
        SubtaskOf -> "Is subtask of"
        ParentOf -> "Is parent for"
    }

    /**
     * Returns the symmetric connection type that should be created on the target task
     */
    val symmetric: ConnectionType get() = when (this) {
        RelatesTo -> RelatesTo
        DependsOn -> IsDependencyOf
        IsDependencyOf -> DependsOn
        SubtaskOf -> ParentOf
        ParentOf -> SubtaskOf
    }
}

/**
 * A connection from this task to another task
 */
@Serializable
data class TaskConnection(
    val targetTaskId: String,
    val type: ConnectionType
)

/**
 * A notification for a task, triggered at a specific time before the deadline.
 * The time is specified using RecurrencePeriod in compact format (e.g., "1d", "2h30m").
 */
@Serializable
data class TaskNotification(
    val timeBeforeDeadline: RecurrencePeriod
)

/**
 * Reason for an automatic status change
 */
@Serializable
sealed class AutomaticChangeReason {
    abstract val text: String

    @Serializable
    data object Unblocked : AutomaticChangeReason() {
        override val text: String = "Unblocked"
    }

    @Serializable
    data class UpdatedFromSubtasks(val relatedTaskIds: List<String>) : AutomaticChangeReason() {
        override val text: String = if (relatedTaskIds.size == 1) "by subtask" else "by subtasks"
    }

    @Serializable
    data object Recurrence : AutomaticChangeReason() {
        override val text: String = "recurrence"
    }
}

/**
 * Represents a single state change in the task's timeline.
 */
@Serializable
data class StatusChange(
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant,
    val previousStatus: TaskStatus?,
    val newStatus: TaskStatus,
    val automaticChangeReason: AutomaticChangeReason? = null // The actual reason for automatic change (null if manual)
)

/**
 * Represents a status change event for calendar/timeline views
 */
data class StatusChangeEvent(
    val task: Task,
    val statusChange: StatusChange
)

/**
 * A space is a separate workspace with its own tasks and ID prefix
 */
@Serializable
data class Space(
    val id: String,
    val name: String,
    val idPrefix: String // Capitalized English letter sequence (e.g., "TASK", "BUG", "FEAT")
) {
    init {
        require(idPrefix.matches(Regex("^[A-Z]+$"))) { "ID prefix must contain only uppercase English letters" }
        require(idPrefix.isNotEmpty()) { "ID prefix cannot be empty" }
    }
}

@Serializable
data class Task(
    val id: String,
    val title: String,
    val description: String = "", // Markdown format, can reference other tasks by ID
    val status: TaskStatus = TaskStatus.Open,
    @Serializable(with = InstantSerializer::class)
    val dueDate: Instant? = null,
    val priority: Priority? = null,
    val estimatedTime: RecurrencePeriod? = null,
    val tags: Set<String> = emptySet(),
    val connections: Set<TaskConnection> = emptySet(),
    val notifications: List<TaskNotification> = emptyList(), // Notifications before deadline
    val spaceId: String, // ID of the space this task belongs to
    val recurrenceRule: RecurrenceRule = RecurrenceRule.None,
    val recurrenceState: RecurrenceState = RecurrenceState(),
    val resetStatusOnRecurrence: TaskStatus = TaskStatus.Open, // Status to reset to when recurring
    val autoUpdateStatusFromSubtasks: Boolean = false // Automatically update status based on subtasks
) {
    companion object

    /**
     * Check if this task is recurring
     */
    val isRecurring: Boolean get() = recurrenceRule !is RecurrenceRule.None

    /**
     * Check if this task is missed (overdue and not resolved).
     * A task is missed if it has a due date in the past and is not Done or Declined.
     */
    fun isMissed(currentTime: Instant): Boolean {
        val due = dueDate ?: return false
        if (status is TaskStatus.Done || status is TaskStatus.Declined) return false
        return due < currentTime
    }
}

/**
 * Computed properties for total priority and due date based on dependencies
 */
data class TaskWithTotals(
    val task: Task,
    val totalDueDate: Instant?, // Closest due date from self and dependencies
    val totalPriority: Priority? // Highest priority from self and dependencies
) {
    /**
     * Check if this task is missed based on total due date (considers dependencies).
     * A task is missed if its total due date is in the past and the task is not Done or Declined.
     */
    fun isMissed(currentTime: Instant): Boolean {
        val due = totalDueDate ?: return false
        if (task.status is TaskStatus.Done || task.status is TaskStatus.Declined) return false
        return due < currentTime
    }
}
