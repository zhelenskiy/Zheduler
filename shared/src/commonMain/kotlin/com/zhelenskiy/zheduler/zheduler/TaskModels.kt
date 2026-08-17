@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
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
 * Task status representing the current state of a task.
 *
 * Each variant's stored name is spelled out rather than left to default to its fully-qualified
 * class name. The name is on disk — in every task row, every timeline entry, every saved filter
 * and every export — and the SQL that groups tasks by status matches it as text, so a rename or a
 * move of any of these classes would leave the stored rows naming something that no longer exists:
 * decoding would fail, and the grouping would quietly stop matching without failing at all. Pinned
 * here to what is already written, these classes can be renamed and moved freely.
 */
@Serializable
sealed class TaskStatus : Presentable {
    @SerialName("com.zhelenskiy.zheduler.zheduler.TaskStatus.Open")
    @Serializable
    data object Open : TaskStatus()

    @SerialName("com.zhelenskiy.zheduler.zheduler.TaskStatus.Blocked")
    @Serializable
    data class Blocked(
        @Serializable(with = PersistentSetSerializer::class)
        val blockerTaskIds: PersistentSet<String>,
        val comment: String = "",
    ) : TaskStatus()

    @SerialName("com.zhelenskiy.zheduler.zheduler.TaskStatus.InProgress")
    @Serializable
    data object InProgress : TaskStatus()

    @SerialName("com.zhelenskiy.zheduler.zheduler.TaskStatus.Done")
    @Serializable
    data object Done : TaskStatus()

    @SerialName("com.zhelenskiy.zheduler.zheduler.TaskStatus.Declined")
    @Serializable
    data class Declined(val reason: String) : TaskStatus()

    val displayName: String
        get() = when (this) {
            is Open -> "Open"
            is Blocked -> "Blocked"
            is InProgress -> "In Progress"
            is Done -> "Done"
            is Declined -> "Declined"
        }

    override fun toBriefString(): String = when (this) {
        is Blocked -> listOfNotNull(
            displayName,
            blockerTaskIds.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "by "),
            comment.takeIf { it.isNotBlank() }?.let { "(comment: ${it.addEllipsis()})" },
        ).joinToString(" ")
        is Declined if reason.isBlank() -> displayName
        is Declined -> "$displayName (reason: ${reason.addEllipsis()})"
        else -> displayName
    }

    private fun String.isMultiline(): Boolean = lineSequence().drop(1).any()
    private fun String.prependSeparator(): String = if (isMultiline()) ":\n$this" else " $this"
    private fun String.addEllipsis(): String = when {
        isMultiline() -> "${lineSequence().first().take(32)}..."
        length > 32 -> "${take(32)}..."
        else -> this
    }

    override fun toFullString(): String = when (this) {
        is Blocked -> listOfNotNull(
            displayName,
            blockerTaskIds.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "by "),
            comment.takeIf { it.isNotBlank() }?.let { "with comment${it.prependSeparator()}" },
        ).joinToString(" ")

        is Declined if reason.isBlank() -> displayName
        is Declined -> "$displayName with reason${reason.prependSeparator()}"
        else -> displayName
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

    val displayName: String
        get() = when (this) {
            RelatesTo -> "Relates to"
            DependsOn -> "Depends on"
            IsDependencyOf -> "Is dependency of"
            SubtaskOf -> "Is subtask of"
            ParentOf -> "Is parent for"
        }

    /**
     * Returns the symmetric connection type that should be created on the target task
     */
    val symmetric: ConnectionType
        get() = when (this) {
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

    @SerialName("com.zhelenskiy.zheduler.zheduler.AutomaticChangeReason.Unblocked")
    @Serializable
    data object Unblocked : AutomaticChangeReason() {
        override val text: String = "Unblocked"
    }

    @SerialName("com.zhelenskiy.zheduler.zheduler.AutomaticChangeReason.UpdatedFromSubtasks")
    @Serializable
    data class UpdatedFromSubtasks(
        @Serializable(with = PersistentListSerializer::class)
        val relatedTaskIds: PersistentList<String>
    ) : AutomaticChangeReason() {
        override val text: String = if (relatedTaskIds.size == 1) "by subtask" else "by subtasks"
    }

    @SerialName("com.zhelenskiy.zheduler.zheduler.AutomaticChangeReason.Recurrence")
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
    @Serializable(with = PersistentSetSerializer::class)
    val tags: PersistentSet<String> = persistentSetOf(),
    @Serializable(with = PersistentSetSerializer::class)
    val connections: PersistentSet<TaskConnection> = persistentSetOf(),
    @Serializable(with = PersistentListSerializer::class)
    val notifications: PersistentList<TaskNotification> = persistentListOf(), // Notifications before deadline
    val spaceId: String, // ID of the space this task belongs to
    @Serializable(with = PersistentListSerializer::class)
    val recurrenceRules: PersistentList<Pair<RecurrenceRule, RecurrenceState>> = persistentListOf(), // Multiple recurrence rules
    val autoUpdateStatusFromSubtasks: Boolean = false // Automatically update status based on subtasks
) {
    /**
     * Check if this task is recurring
     */
    val isRecurring: Boolean get() = recurrenceRules.isNotEmpty()

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
 * A task with what the work waiting on it makes of its due date and priority.
 *
 * The totals look *downstream*, not up: they gather this task together with everything that
 * reaches it — the tasks that depend on it, and the tasks blocked by it, transitively. A task of
 * no importance in itself that three urgent tasks are waiting on is urgent, and one that nothing
 * waits on is only as pressing as it says it is. (The word "dependencies" elsewhere in this
 * codebase means the opposite direction — what a task depends on — and does not come into this.)
 */
data class TaskWithTotals(
    val task: Task,
    /** The earliest due date of this task and everything waiting on it. */
    val totalDueDate: Instant?,
    /** The highest priority of this task and everything waiting on it. */
    val totalPriority: Priority?,
) {
    /**
     * Whether this task is late, counting what waits on it.
     *
     * True once [totalDueDate] is in the past, unless the task is Done or Declined — so a task
     * with no deadline of its own is late when something waiting on it is.
     */
    fun isMissed(currentTime: Instant): Boolean {
        val due = totalDueDate ?: return false
        if (task.status is TaskStatus.Done || task.status is TaskStatus.Declined) return false
        return due < currentTime
    }
}
