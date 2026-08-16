@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
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
    fun getAllPossibleValues(): PersistentSet<String> = when (this) {
        Status -> persistentSetOf("Open", "InProgress", "Blocked", "Done", "Declined")
        Priority -> persistentSetOf() // Uses custom ranges
        DueDate -> persistentSetOf() // Uses custom ranges
        EstimatedTime -> persistentSetOf() // Uses custom ranges
        Tags -> persistentSetOf() // Tags are dynamic, cannot be exhaustively enumerated
        HasConnections -> persistentSetOf("true", "false")
        IsRecurring -> persistentSetOf("true", "false")
        HasNotifications -> persistentSetOf("true", "false")
        AutoUpdateStatus -> persistentSetOf("true", "false")
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
    @Serializable(with = PersistentSetSerializer::class)
    val values: PersistentSet<String> = persistentSetOf(),
    @Serializable(with = PersistentListSerializer::class)
    val orderingRules: PersistentList<OrderingRule> = persistentListOf(),
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
    @Serializable(with = PersistentListSerializer::class)
    val groups: PersistentList<GroupDefinition>,
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

    data class Invalid(val errors: PersistentList<GroupingValidationError>) : GroupingValidationResult()
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
    @Serializable(with = PersistentListSerializer::class)
    val groupingLevels: PersistentList<GroupingLevel> = persistentListOf(),
    @Serializable(with = PersistentListSerializer::class)
    val defaultOrderingRules: PersistentList<OrderingRule> = persistentListOf(
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
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf(
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
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition(
                            label = "Unresolved",
                            values = persistentSetOf("Open", "InProgress")
                        ),
                        GroupDefinition(
                            label = "Blocked",
                            values = persistentSetOf("Blocked")
                        ),
                        GroupDefinition(
                            label = "Resolved",
                            values = persistentSetOf("Done", "Declined")
                        )
                    )
                )
            ),
            defaultOrderingRules = persistentListOf(
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
        val errors = buildPersistentList {
            for (level in groupingLevels) {
                val field = level.field

                // Check for empty group labels and empty groups
                for (group in level.groups) {
                    if (group.label.isBlank()) {
                        add(GroupingValidationError.EmptyGroupLabel(field))
                    }
                    // Allow empty values if custom range is used
                    val hasCustomRange = when (field) {
                        GroupableField.Priority, GroupableField.DueDate, GroupableField.EstimatedTime -> true
                        else -> false
                    }
                    if (group.values.isEmpty() && !hasCustomRange) {
                        add(GroupingValidationError.EmptyGroup(field, group.label))
                    }
                }
                // Duplicate values and non-exhaustive coverage are allowed
                // Tasks matching multiple groups will appear in the all matching groups
                // Tasks not matching any group will appear in the uncategorized group
            }
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
    OrderableField.Id -> taskIdOrderValue(task.id)
    OrderableField.Title -> task.title
    OrderableField.Status -> task.status.orderRank()
    OrderableField.Priority -> task.priority?.value
    OrderableField.TotalPriority -> totalPriority?.value
    OrderableField.DueDate -> task.dueDate?.toEpochMilliseconds()
    OrderableField.TotalDueDate -> totalDueDate?.toEpochMilliseconds()
    OrderableField.EstimatedTime -> task.estimatedTime?.toApproximateSeconds()
}

/**
 * The trailing number of a task id ("TEST-12" -> 12), which is what [OrderableField.Id] orders by
 * so that TEST-2 comes before TEST-10.
 */
fun taskIdOrderValue(id: String): Int = id.takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0

/** Workflow position of a status, used when ordering by [OrderableField.Status]. */
fun TaskStatus.orderRank(): Int = when (this) {
    is TaskStatus.Open -> 0
    is TaskStatus.InProgress -> 1
    is TaskStatus.Blocked -> 2
    is TaskStatus.Done -> 3
    is TaskStatus.Declined -> 4
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
 * Represents a filter constraint for querying tasks within a group.
 * This is derived from a GroupDefinition and can be used to build SQL WHERE clauses.
 */
@Serializable
sealed class GroupFilter {
    abstract val field: GroupableField

    /**
     * Filter by exact string values (for Status, HasConnections, IsRecurring, etc.)
     */
    @Serializable
    data class Values(
        override val field: GroupableField,
        @Serializable(with = PersistentSetSerializer::class)
        val values: PersistentSet<String>
    ) : GroupFilter()

    /**
     * Filter by priority range
     */
    @Serializable
    data class PriorityRange(
        override val field: GroupableField = GroupableField.Priority,
        val min: Int? = null,
        val max: Int? = null,
        val includeNull: Boolean = false
    ) : GroupFilter()

    /**
     * Filter by estimated time range (in seconds)
     */
    @Serializable
    data class EstimatedTimeRange(
        override val field: GroupableField = GroupableField.EstimatedTime,
        val minSeconds: Long? = null,
        val maxSeconds: Long? = null,
        val includeNull: Boolean = false
    ) : GroupFilter()

    /**
     * Filter by due date range (days from today)
     */
    @Serializable
    data class DueDateRange(
        override val field: GroupableField = GroupableField.DueDate,
        val minDays: Int? = null,
        val maxDays: Int? = null,
        val includeNull: Boolean = false
    ) : GroupFilter()

    /**
     * Filter by tags (task must have at least one of the specified tags)
     */
    @Serializable
    data class HasTags(
        override val field: GroupableField = GroupableField.Tags,
        @Serializable(with = PersistentSetSerializer::class)
        val tags: PersistentSet<String>
    ) : GroupFilter()

    /**
     * Negation filter - matches tasks that DON'T match any of the given filters.
     * Used for "Uncategorized" groups.
     */
    @Serializable
    data class Not(
        override val field: GroupableField,
        @Serializable(with = PersistentListSerializer::class)
        val filters: PersistentList<GroupFilter>
    ) : GroupFilter()
}

/**
 * Information about a group retrieved from the repository.
 * Used for building the group hierarchy without loading all tasks.
 */
data class TaskGroupInfo(
    val label: String,
    val taskCount: Int,
    val isUncategorized: Boolean = false,
    val groupDefinition: GroupDefinition? = null, // null for uncategorized
    val filter: GroupFilter? = null // The filter to apply when loading tasks/subgroups for this group (null means no filtering)
)

/**
 * Converts a GroupDefinition to a GroupFilter for the given field.
 */
fun GroupDefinition.toFilter(field: GroupableField): GroupFilter = when (field) {
    GroupableField.Priority -> GroupFilter.PriorityRange(
        min = priorityMin,
        max = priorityMax,
        includeNull = includeNoPriority
    )
    GroupableField.EstimatedTime -> GroupFilter.EstimatedTimeRange(
        minSeconds = estimatedTimeMin?.toApproximateSeconds(),
        maxSeconds = estimatedTimeMax?.toApproximateSeconds(),
        includeNull = includeNoEstimatedTime
    )
    GroupableField.DueDate -> GroupFilter.DueDateRange(
        minDays = dueDateMinDays,
        maxDays = dueDateMaxDays,
        includeNull = includeNoDueDate
    )
    GroupableField.Tags -> GroupFilter.HasTags(tags = values)
    else -> GroupFilter.Values(field = field, values = values)
}

/**
 * Groups and orders tasks according to the view mode configuration.
 *
 * The repositories page groups straight out of storage rather than through this; it stays as the
 * one place that spells the grouping rules out end to end over a plain list, which is how they are
 * tested.
 *
 * @param today the date due-date groups are measured from.
 */
fun ViewMode.applyTo(tasks: List<TaskWithTotals>, today: LocalDate): List<TaskGroup> {
    if (groupingLevels.isEmpty()) {
        // No grouping, just order
        return listOf(
            TaskGroup(
                label = "",
                tasks = tasks.sortedWith(createComparator(defaultOrderingRules))
            )
        )
    }

    return applyGroupingLevel(tasks, groupingLevels, 0, today)
}

private fun ViewMode.applyGroupingLevel(
    tasks: List<TaskWithTotals>,
    levels: List<GroupingLevel>,
    currentLevel: Int,
    today: LocalDate
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
            task.matchesGroupFilter(group.toFilter(level.field), today)
        }

        matchedTasks.addAll(matchingTasks.map { it.task.id })

        if (matchingTasks.isNotEmpty() || level.showEmptyGroups) {
            val orderingRules = group.orderingRules.ifEmpty { defaultOrderingRules }

            if (currentLevel + 1 < levels.size) {
                // Apply next grouping level
                val subgroups = applyGroupingLevel(matchingTasks, levels, currentLevel + 1, today)
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
            val subgroups = applyGroupingLevel(uncategorizedTasks, levels, currentLevel + 1, today)
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
 * Checks if a task matches a GroupFilter.
 *
 * [today] is the date the caller considers current — a due-date group is expressed in days from
 * today, so this has to come from the repository's clock rather than the wall clock, or the
 * in-memory and SQL paths answer as of different days. Passing it in also reads it once per query
 * instead of once per task.
 */
internal fun TaskWithTotals.matchesGroupFilter(filter: GroupFilter, today: LocalDate): Boolean {
    return when (filter) {
        is GroupFilter.Values -> {
            getFieldValue(filter.field) in filter.values
        }
        is GroupFilter.PriorityRange -> {
            val priority = task.priority?.value
            if (priority == null) {
                filter.includeNull
            } else {
                // If there's no range specified (both min and max are null) and includeNull is true,
                // this filter is for "null only" - non-null values should NOT match
                if (filter.min == null && filter.max == null && filter.includeNull) {
                    false
                } else {
                    val minOk = filter.min == null || priority >= filter.min
                    val maxOk = filter.max == null || priority <= filter.max
                    minOk && maxOk
                }
            }
        }
        is GroupFilter.EstimatedTimeRange -> {
            val estimatedTime = task.estimatedTime
            if (estimatedTime == null) {
                filter.includeNull
            } else {
                // If there's no range specified (both min and max are null) and includeNull is true,
                // this filter is for "null only" - non-null values should NOT match
                if (filter.minSeconds == null && filter.maxSeconds == null && filter.includeNull) {
                    false
                } else {
                    val seconds = estimatedTime.toApproximateSeconds()
                    val minOk = filter.minSeconds == null || seconds >= filter.minSeconds
                    val maxOk = filter.maxSeconds == null || seconds <= filter.maxSeconds
                    minOk && maxOk
                }
            }
        }
        is GroupFilter.DueDateRange -> {
            val dueDate = task.dueDate
            if (dueDate == null) {
                filter.includeNull
            } else {
                // If there's no range specified (both min and max are null) and includeNull is true,
                // this filter is for "null only" - non-null values should NOT match
                if (filter.minDays == null && filter.maxDays == null && filter.includeNull) {
                    false
                } else {
                    val taskDate = dueDate.toLocalDateTime(TimeZone.currentSystemDefault()).date
                    val daysDiff = taskDate.toEpochDays() - today.toEpochDays()
                    val minOk = filter.minDays == null || daysDiff >= filter.minDays
                    val maxOk = filter.maxDays == null || daysDiff <= filter.maxDays
                    minOk && maxOk
                }
            }
        }
        is GroupFilter.HasTags -> {
            task.tags.any { it in filter.tags }
        }
        is GroupFilter.Not -> {
            filter.filters.none { matchesGroupFilter(it, today) }
        }
    }
}

/**
 * Creates a comparator from ordering rules.
 */
internal fun createComparator(rules: List<OrderingRule>): Comparator<TaskWithTotals> =
    createComparator(rules) { task, field -> task.getOrderableValue(field) }

/**
 * Creates a comparator from ordering rules for anything that can produce a value per
 * [OrderableField] — the database repository orders lightweight rows this way, before it loads the
 * page the user is actually looking at.
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> createComparator(
    rules: List<OrderingRule>,
    orderableValue: (T, OrderableField) -> Comparable<*>?,
): Comparator<T> {
    return Comparator { a, b ->
        for (rule in rules) {
            val valueA = orderableValue(a, rule.field)
            val valueB = orderableValue(b, rule.field)

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
