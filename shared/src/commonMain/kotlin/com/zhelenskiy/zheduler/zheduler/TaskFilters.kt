@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Filter enums and data classes
enum class TaskTextSearchField {
    Id, Title, Tags, Description;

    val displayName: String get() = when (this) {
        Id -> "ID"
        Title -> "Title"
        Tags -> "Tags"
        Description -> "Description"
    }
}

enum class DueDateFilter {
    Any, Overdue, Today, ThisWeek, ThisMonth, NoDueDate, Custom;

    val displayName: String get() = when (this) {
        Any -> "Any"
        Overdue -> "Overdue"
        Today -> "Today"
        ThisWeek -> "This Week"
        ThisMonth -> "This Month"
        NoDueDate -> "No Due Time"
        Custom -> "Custom"
    }
}

enum class PriorityFilter {
    Any, High, Medium, Low, NoPriority, Custom;

    val displayName: String get() = when (this) {
        Any -> "Any"
        High -> "High (75-100)"
        Medium -> "Medium (50-74)"
        Low -> "Low (1-49)"
        NoPriority -> "No Priority"
        Custom -> "Custom"
    }
}

enum class EstimatedTimeFilter {
    Any, NoEstimate, Quick, Short, Medium, Long, VeryLong, Custom;

    val displayName: String get() = when (this) {
        Any -> "Any"
        NoEstimate -> "No Estimate"
        Quick -> "< 15 min"
        Short -> "15-30 min"
        Medium -> "30 min - 1 hr"
        Long -> "1-4 hrs"
        VeryLong -> "> 4 hrs"
        Custom -> "Custom"
    }
}

enum class TagMatchMode {
    Any, All;

    val displayName: String get() = when (this) {
        Any -> "Match any"
        All -> "Match all"
    }
}

enum class RecurrenceFilter {
    Any, NoRecurrence, HasRecurrence, AfterTimeout, FixedDaysOfWeek, FixedDayOfMonth, NthDayOfWeek, Yearly;

    val displayName: String get() = when (this) {
        Any -> "Any"
        NoRecurrence -> "No Recurrence"
        HasRecurrence -> "Has Recurrence"
        AfterTimeout -> "After Timeout"
        FixedDaysOfWeek -> "Weekly"
        FixedDayOfMonth -> "Monthly"
        NthDayOfWeek -> "Monthly (weekday)"
        Yearly -> "Yearly"
    }
}

/** Whether the `isRecurring` column answers this filter on its own, or [matches] must refine it. */
internal val RecurrenceFilter.bucketedInSqlOnly: Boolean
    get() = when (this) {
        RecurrenceFilter.Any, RecurrenceFilter.NoRecurrence, RecurrenceFilter.HasRecurrence -> false
        else -> true
    }

/**
 * Whether a task's recurrence rules satisfy this filter.
 *
 * Both repositories decide it here: which kind of rule a task carries lives inside the serialized
 * rules, so the database one narrows on `isRecurring` and refines the candidates through this
 * rather than keeping a second definition that can drift.
 */
internal fun RecurrenceFilter.matches(rules: List<Pair<RecurrenceRule, RecurrenceState>>): Boolean {
    fun anyPattern(predicate: (FixedPointPattern) -> Boolean) = rules.any { (rule, _) ->
        val trigger = rule.timeRecurrenceTrigger
        trigger is RecurrenceTrigger.AtFixedPoints && predicate(trigger.pattern)
    }

    return when (this) {
        RecurrenceFilter.Any -> true
        RecurrenceFilter.NoRecurrence -> rules.isEmpty()
        RecurrenceFilter.HasRecurrence -> rules.isNotEmpty()
        RecurrenceFilter.AfterTimeout ->
            rules.any { (rule, _) -> rule.timeRecurrenceTrigger is RecurrenceTrigger.AfterTimeout }
        RecurrenceFilter.FixedDaysOfWeek -> anyPattern { it is FixedPointPattern.DaysOfWeek }
        RecurrenceFilter.FixedDayOfMonth -> anyPattern { it is FixedPointPattern.DayOfMonth }
        RecurrenceFilter.NthDayOfWeek -> anyPattern { it is FixedPointPattern.NthDayOfWeekInMonth }
        RecurrenceFilter.Yearly ->
            anyPattern { it is FixedPointPattern.YearlyOnDate || it is FixedPointPattern.NthDayOfWeekInMonths }
    }
}

/**
 * The half-open `[min, max)` bounds in seconds a fixed [EstimatedTimeFilter] bucket accepts, or
 * `null` for the three that are not ranges.
 *
 * Read by both repositories, so neither can disagree about whether 30 minutes is Short or Medium.
 */
internal val EstimatedTimeFilter.bucketSeconds: Pair<Long?, Long?>?
    get() = when (this) {
        EstimatedTimeFilter.Any, EstimatedTimeFilter.NoEstimate, EstimatedTimeFilter.Custom -> null
        EstimatedTimeFilter.Quick -> null to 15L * 60
        EstimatedTimeFilter.Short -> 15L * 60 to 30L * 60
        EstimatedTimeFilter.Medium -> 30L * 60 to 60L * 60
        EstimatedTimeFilter.Long -> 60L * 60 to 4L * 60 * 60
        EstimatedTimeFilter.VeryLong -> 4L * 60 * 60 to null
    }

enum class NotificationsFilter {
    Any, NoNotifications, HasNotifications;

    val displayName: String get() = when (this) {
        Any -> "Any"
        NoNotifications -> "No Notifications"
        HasNotifications -> "Has Notifications"
    }
}

enum class AutoUpdateStatusFilter {
    Any, Auto, Manual;

    val displayName: String get() = when (this) {
        Any -> "Any"
        Auto -> "Auto"
        Manual -> "Manual"
    }
}

enum class ConnectionTypeOption {
    DependsOn, IsDependencyOf, RelatesTo, SubtaskOf, ParentOf, NotSubtask;

    val displayName: String get() = when (this) {
        DependsOn -> "Has dependencies"
        IsDependencyOf -> "Has dependents"
        RelatesTo -> "Has related"
        SubtaskOf -> "Is subtask"
        ParentOf -> "Has subtasks"
        NotSubtask -> "Is not subtask"
    }
}

/**
 * Data class representing all filter criteria for tasks
 */
data class TaskFilterCriteria(
    val searchQuery: String = "",
    val textSearchFields: PersistentSet<TaskTextSearchField> = persistentSetOf(TaskTextSearchField.Id, TaskTextSearchField.Title),
    val statusFilters: PersistentSet<TaskStatus> = persistentSetOf(),
    val dueDateFilter: DueDateFilter = DueDateFilter.Any,
    val priorityFilter: PriorityFilter = PriorityFilter.Any,
    val estimatedTimeFilter: EstimatedTimeFilter = EstimatedTimeFilter.Any,
    val recurrenceFilter: RecurrenceFilter = RecurrenceFilter.Any,
    val notificationsFilter: NotificationsFilter = NotificationsFilter.Any,
    val autoUpdateStatusFilter: AutoUpdateStatusFilter = AutoUpdateStatusFilter.Any,
    val connectionTypeFilters: PersistentSet<ConnectionTypeOption> = persistentSetOf(),
    val selectedTags: PersistentSet<String> = persistentSetOf(),
    val tagMatchMode: TagMatchMode = TagMatchMode.All,
    val customPriorityMin: String = "",
    val customPriorityMax: String = "",
    val customDueDateBefore: Instant? = null,
    val customDueDateAfter: Instant? = null,
    val customEstimatedTimeMin: String = "",
    val customEstimatedTimeMax: String = "",
    val dependsOnTaskIds: String = "",
    val isDependencyOfTaskIds: String = "",
    val relatesToTaskIds: String = "",
    val subtaskOfTaskIds: String = "",
    val parentOfTaskIds: String = "",
    val blockedByTaskIds: String = "",
    val blockedByComment: String = "",
    val declinedReason: String = ""
) {
    val hasActiveFilters: Boolean
        get() = searchQuery.isNotBlank() ||
                statusFilters.isNotEmpty() ||
                dueDateFilter != DueDateFilter.Any ||
                priorityFilter != PriorityFilter.Any ||
                estimatedTimeFilter != EstimatedTimeFilter.Any ||
                recurrenceFilter != RecurrenceFilter.Any ||
                notificationsFilter != NotificationsFilter.Any ||
                autoUpdateStatusFilter != AutoUpdateStatusFilter.Any ||
                connectionTypeFilters.isNotEmpty() ||
                selectedTags.isNotEmpty() ||
                dependsOnTaskIds.isNotBlank() ||
                isDependencyOfTaskIds.isNotBlank() ||
                relatesToTaskIds.isNotBlank() ||
                subtaskOfTaskIds.isNotBlank() ||
                parentOfTaskIds.isNotBlank() ||
                blockedByTaskIds.isNotBlank() ||
                blockedByComment.isNotBlank() ||
                declinedReason.isNotBlank()
}
