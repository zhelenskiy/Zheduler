@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Represents a field that can be used for grouping tasks.
 */
@Serializable
enum class GroupableField {
    Status,
    Priority,
    DueDate,
    EstimatedTime,
    Tags,
    HasConnections,
    IsRecurring,
    HasNotifications,
    AutoUpdateStatus;

    val displayName: String
        get() = when (this) {
            Status -> "Status"
            Priority -> "Priority"
            DueDate -> "Due Date"
            EstimatedTime -> "Estimated Time"
            Tags -> "Tags"
            HasConnections -> "Has Connections"
            IsRecurring -> "Is Recurring"
            HasNotifications -> "Has Notifications"
            AutoUpdateStatus -> "Auto-Update Status"
        }

    /**
     * Returns all possible values for this field as strings.
     * Used for validation to ensure all values are covered by groups.
     * Priority, DueDate, and EstimatedTime use custom ranges instead of predefined values.
     */
    fun getAllPossibleValues(): Set<String> = when (this) {
        Status -> setOf("Open", "InProgress", "Blocked", "Done", "Declined")
        Priority -> emptySet() // Uses custom ranges
        DueDate -> emptySet() // Uses custom ranges
        EstimatedTime -> emptySet() // Uses custom ranges
        Tags -> emptySet() // Tags are dynamic, cannot be exhaustively enumerated
        HasConnections -> setOf("true", "false")
        IsRecurring -> setOf("true", "false")
        HasNotifications -> setOf("true", "false")
        AutoUpdateStatus -> setOf("true", "false")
    }

    /**
     * Returns whether this field requires exhaustive coverage of all values.
     * Non-exhaustive coverage is always allowed - uncategorized tasks go to the "Uncategorized" group.
     */
    fun requiresExhaustiveCoverage(): Boolean = false
}

/**
 * Represents a field that can be used for ordering tasks.
 */
@Serializable
enum class OrderableField {
    Id,
    Title,
    Status,
    Priority,
    TotalPriority,
    DueDate,
    TotalDueDate,
    EstimatedTime;

    val displayName: String
        get() = when (this) {
            Id -> "ID"
            Title -> "Title"
            Status -> "Status"
            Priority -> "Priority"
            TotalPriority -> "Total Priority"
            DueDate -> "Due Date"
            TotalDueDate -> "Total Due Date"
            EstimatedTime -> "Estimated Time"
        }

    /**
     * Returns whether this field can have null values.
     */
    fun canBeNull(): Boolean = when (this) {
        Id, Title, Status -> false
        Priority, TotalPriority, DueDate, TotalDueDate, EstimatedTime -> true
    }
}

/**
 * Order direction for ordering rules.
 */
@Serializable
enum class OrderDirection {
    Ascending,
    Descending;

    val displayName: String
        get() = when (this) {
            Ascending -> "Ascending"
            Descending -> "Descending"
        }
}

/**
 * Position of null values in ordering.
 */
@Serializable
enum class NullPosition {
    First,
    Last;

    val displayName: String
        get() = when (this) {
            First -> "First"
            Last -> "Last"
        }
}

/**
 * A single ordering rule specifying how to order tasks.
 */
@Serializable
data class OrderingRule(
    val field: OrderableField,
    val direction: OrderDirection = OrderDirection.Ascending,
    val nullPosition: NullPosition = NullPosition.Last
)

/**
 * Defines a group of values for a groupable field.
 * For example, for Status field, you might have:
 * - "Unresolved" group containing ["Open", "InProgress"]
 * - "Blocked" group containing ["Blocked"]
 * - "Resolved" group containing ["Done", "Declined"]
 *
 * For numeric fields (Priority, EstimatedTime, DueDate), you can also specify custom ranges
 * using the optional range parameters. If a range is specified, it takes precedence over values.
 */
@Serializable
data class GroupDefinition(
    val label: String,
    val values: Set<String>,
    val orderingRules: List<OrderingRule> = emptyList(),
    // Custom range for Priority (0-100)
    val priorityMin: Int? = null,
    val priorityMax: Int? = null,
    val includeNoPriority: Boolean = false,
    // Custom range for Estimated Time
    val estimatedTimeMin: RecurrencePeriod? = null,
    val estimatedTimeMax: RecurrencePeriod? = null,
    val includeNoEstimatedTime: Boolean = false,
    // Custom range for Due Date (days from today, negative = past)
    val dueDateMinDays: Int? = null,
    val dueDateMaxDays: Int? = null,
    val includeNoDueDate: Boolean = false
)

/**
 * A single grouping level in the view mode.
 * Tasks are first grouped by this field according to the group definitions.
 */
@Serializable
data class GroupingLevel(
    val field: GroupableField,
    val groups: List<GroupDefinition>,
    val showEmptyGroups: Boolean = false
)

/**
 * A single validation error for a grouping configuration.
 */
sealed class GroupingValidationError {
    data class EmptyGroup(
        val field: GroupableField,
        val groupLabel: String
    ) : GroupingValidationError()

    data class EmptyGroupLabel(
        val field: GroupableField
    ) : GroupingValidationError()

    data class InvalidRange(
        val field: GroupableField,
        val groupLabel: String
    ) : GroupingValidationError()
}

/**
 * Result of validating a grouping configuration.
 */
sealed class GroupingValidationResult {
    data object Valid : GroupingValidationResult()

    data class Invalid(val errors: List<GroupingValidationError>) : GroupingValidationResult()
}

/**
 * A complete view mode configuration.
 * Defines how tasks should be grouped and ordered in the task list.
 */
@Serializable
data class ViewMode(
    val id: String,
    val name: String,
    val spaceId: String,
    val isBuiltIn: Boolean = false,
    val groupingLevels: List<GroupingLevel> = emptyList(),
    val defaultOrderingRules: List<OrderingRule> = listOf(
        OrderingRule(OrderableField.TotalDueDate, OrderDirection.Ascending, NullPosition.Last),
        OrderingRule(OrderableField.TotalPriority, OrderDirection.Descending, NullPosition.Last),
        OrderingRule(OrderableField.Id, OrderDirection.Ascending)
    )
) {
    companion object {
        /**
         * Creates the default "Chronological" view mode.
         */
        fun chronological(spaceId: String) = ViewMode(
            id = "chronological",
            name = "Chronological",
            spaceId = spaceId,
            isBuiltIn = true,
            groupingLevels = emptyList(),
            defaultOrderingRules = listOf(
                OrderingRule(OrderableField.Id, OrderDirection.Descending)
            )
        )

        /**
         * Creates the default "Priority" view mode with status grouping.
         */
        fun priority(spaceId: String) = ViewMode(
            id = "priority",
            name = "Priority",
            spaceId = spaceId,
            isBuiltIn = true,
            groupingLevels = listOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = listOf(
                        GroupDefinition(
                            label = "Unresolved",
                            values = setOf("Open", "InProgress")
                        ),
                        GroupDefinition(
                            label = "Blocked",
                            values = setOf("Blocked")
                        ),
                        GroupDefinition(
                            label = "Resolved",
                            values = setOf("Done", "Declined")
                        )
                    )
                )
            ),
            defaultOrderingRules = listOf(
                OrderingRule(OrderableField.TotalDueDate, OrderDirection.Ascending, NullPosition.Last),
                OrderingRule(OrderableField.TotalPriority, OrderDirection.Descending, NullPosition.Last),
                OrderingRule(OrderableField.Id, OrderDirection.Ascending)
            )
        )

        /**
         * Returns default built-in view modes for a space.
         */
        fun getBuiltInModes(spaceId: String): List<ViewMode> = listOf(
            chronological(spaceId),
            priority(spaceId)
        )
    }

    /**
     * Validates that all grouping levels have complete coverage of their field values.
     * Returns all validation errors found, or Valid if all is well.
     */
    fun validate(): GroupingValidationResult {
        val errors = mutableListOf<GroupingValidationError>()

        for (level in groupingLevels) {
            val field = level.field

            // Check for empty group labels and empty groups
            for (group in level.groups) {
                if (group.label.isBlank()) {
                    errors.add(GroupingValidationError.EmptyGroupLabel(field))
                }
                // Allow empty values if custom range is used
                val hasCustomRange = when (field) {
                    GroupableField.Priority, GroupableField.DueDate, GroupableField.EstimatedTime -> true
                    else -> false
                }
                if (group.values.isEmpty() && !hasCustomRange) {
                    errors.add(GroupingValidationError.EmptyGroup(field, group.label))
                }
            }
            // Duplicate values and non-exhaustive coverage are allowed
            // Tasks matching multiple groups will appear in the all matching groups
            // Tasks not matching any group will appear in the uncategorized group
        }

        return if (errors.isEmpty()) {
            GroupingValidationResult.Valid
        } else {
            GroupingValidationResult.Invalid(errors)
        }
    }
}

/**
 * Extracts the string value for a given field from a task.
 * Note: Priority, DueDate, EstimatedTime, and Tags are handled specially in matchesGroup.
 */
fun TaskWithTotals.getFieldValue(field: GroupableField): String = when (field) {
    GroupableField.Status -> when (task.status) {
        is TaskStatus.Open -> "Open"
        is TaskStatus.InProgress -> "InProgress"
        is TaskStatus.Blocked -> "Blocked"
        is TaskStatus.Done -> "Done"
        is TaskStatus.Declined -> "Declined"
    }
    GroupableField.HasConnections -> task.connections.isNotEmpty().toString()
    GroupableField.IsRecurring -> task.isRecurring.toString()
    GroupableField.HasNotifications -> task.notifications.isNotEmpty().toString()
    GroupableField.AutoUpdateStatus -> task.autoUpdateStatusFromSubtasks.toString()
    // These fields are handled specially in matchesGroup
    GroupableField.Priority, GroupableField.DueDate, GroupableField.EstimatedTime, GroupableField.Tags ->
        error("Unexpected field: $field")
}

/**
 * Gets the orderable value for a field from a task.
 * Returns a Comparable that can be used for ordering.
 */
fun TaskWithTotals.getOrderableValue(field: OrderableField): Comparable<*>? = when (field) {
    OrderableField.Id -> task.id.takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0
    OrderableField.Title -> task.title
    OrderableField.Status -> when (task.status) {
        is TaskStatus.Open -> 0
        is TaskStatus.InProgress -> 1
        is TaskStatus.Blocked -> 2
        is TaskStatus.Done -> 3
        is TaskStatus.Declined -> 4
    }
    OrderableField.Priority -> task.priority?.value
    OrderableField.TotalPriority -> totalPriority?.value
    OrderableField.DueDate -> task.dueDate?.toEpochMilliseconds()
    OrderableField.TotalDueDate -> totalDueDate?.toEpochMilliseconds()
    OrderableField.EstimatedTime -> task.estimatedTime?.toApproximateSeconds()
}

/**
 * Represents a group of tasks after applying grouping.
 */
data class TaskGroup(
    val label: String,
    val tasks: List<TaskWithTotals>,
    val level: Int = 0,
    val subgroups: List<TaskGroup> = emptyList(),
    val isUncategorized: Boolean = false
)

/**
 * Groups and orders tasks according to the view mode configuration.
 */
fun ViewMode.applyTo(tasks: List<TaskWithTotals>): List<TaskGroup> {
    if (groupingLevels.isEmpty()) {
        // No grouping, just order
        return listOf(
            TaskGroup(
                label = "",
                tasks = tasks.sortedWith(createComparator(defaultOrderingRules))
            )
        )
    }

    return applyGroupingLevel(tasks, groupingLevels, 0)
}

private fun ViewMode.applyGroupingLevel(
    tasks: List<TaskWithTotals>,
    levels: List<GroupingLevel>,
    currentLevel: Int
): List<TaskGroup> {
    if (currentLevel >= levels.size) {
        return listOf(
            TaskGroup(
                label = "",
                tasks = tasks.sortedWith(createComparator(defaultOrderingRules)),
                level = currentLevel
            )
        )
    }

    val level = levels[currentLevel]
    val result = mutableListOf<TaskGroup>()
    val matchedTasks = mutableSetOf<String>()

    for (group in level.groups) {
        val matchingTasks = tasks.filter { task ->
            matchesGroup(task, level.field, group)
        }

        matchedTasks.addAll(matchingTasks.map { it.task.id })

        if (matchingTasks.isNotEmpty() || level.showEmptyGroups) {
            val orderingRules = group.orderingRules.ifEmpty { defaultOrderingRules }

            if (currentLevel + 1 < levels.size) {
                // Apply next grouping level
                val subgroups = applyGroupingLevel(matchingTasks, levels, currentLevel + 1)
                result.add(
                    TaskGroup(
                        label = group.label,
                        tasks = matchingTasks.sortedWith(createComparator(orderingRules)),
                        level = currentLevel,
                        subgroups = subgroups
                    )
                )
            } else {
                result.add(
                    TaskGroup(
                        label = group.label,
                        tasks = matchingTasks.sortedWith(createComparator(orderingRules)),
                        level = currentLevel
                    )
                )
            }
        }
    }

    // Add uncategorized group for tasks that didn't match any group
    val uncategorizedTasks = tasks.filter { it.task.id !in matchedTasks }
    if (uncategorizedTasks.isNotEmpty()) {
        if (currentLevel + 1 < levels.size) {
            val subgroups = applyGroupingLevel(uncategorizedTasks, levels, currentLevel + 1)
            result.add(
                TaskGroup(
                    label = "",
                    tasks = uncategorizedTasks.sortedWith(createComparator(defaultOrderingRules)),
                    level = currentLevel,
                    subgroups = subgroups,
                    isUncategorized = true
                )
            )
        } else {
            result.add(
                TaskGroup(
                    label = "",
                    tasks = uncategorizedTasks.sortedWith(createComparator(defaultOrderingRules)),
                    level = currentLevel,
                    isUncategorized = true
                )
            )
        }
    }

    return result
}

/**
 * Checks if a task matches a group definition based on the field type.
 * Handles both value-based matching and custom range matching.
 */
private fun matchesGroup(task: TaskWithTotals, field: GroupableField, group: GroupDefinition): Boolean {
    return when (field) {
        GroupableField.Priority -> {
            val priority = task.task.priority?.value
            if (priority == null) {
                group.includeNoPriority
            } else {
                val minOk = group.priorityMin == null || priority >= group.priorityMin
                val maxOk = group.priorityMax == null || priority <= group.priorityMax
                minOk && maxOk
            }
        }
        GroupableField.DueDate -> {
            val dueDate = task.task.dueDate
            if (dueDate == null) {
                group.includeNoDueDate
            } else {
                val now = Clock.System.now()
                val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
                val taskDate = dueDate.toLocalDateTime(TimeZone.currentSystemDefault()).date
                val daysDiff = taskDate.toEpochDays() - today.toEpochDays()
                val minOk = group.dueDateMinDays == null || daysDiff >= group.dueDateMinDays
                val maxOk = group.dueDateMaxDays == null || daysDiff <= group.dueDateMaxDays
                minOk && maxOk
            }
        }
        GroupableField.EstimatedTime -> {
            val estimatedTime = task.task.estimatedTime
            if (estimatedTime == null) {
                group.includeNoEstimatedTime
            } else {
                val seconds = estimatedTime.toApproximateSeconds()
                val minOk = group.estimatedTimeMin == null || seconds >= group.estimatedTimeMin.toApproximateSeconds()
                val maxOk = group.estimatedTimeMax == null || seconds <= group.estimatedTimeMax.toApproximateSeconds()
                minOk && maxOk
            }
        }
        GroupableField.Tags -> {
            task.task.tags.any { it in group.values }
        }
        else -> {
            task.getFieldValue(field) in group.values
        }
    }
}

/**
 * Creates a comparator from ordering rules.
 */
@Suppress("UNCHECKED_CAST")
private fun createComparator(rules: List<OrderingRule>): Comparator<TaskWithTotals> {
    return Comparator { a, b ->
        for (rule in rules) {
            val valueA = a.getOrderableValue(rule.field)
            val valueB = b.getOrderableValue(rule.field)

            // Handle nulls - null position is absolute and not affected by order direction
            when {
                valueA == null && valueB == null -> continue
                valueA == null -> return@Comparator if (rule.nullPosition == NullPosition.First) -1 else 1
                valueB == null -> return@Comparator if (rule.nullPosition == NullPosition.First) 1 else -1
            }

            // Compare non-null values, applying direction
            val comparison = (valueA as Comparable<Any>).compareTo(valueB as Comparable<Any>)
            if (comparison != 0) {
                return@Comparator if (rule.direction == OrderDirection.Descending) -comparison else comparison
            }
        }
        0
    }
}
