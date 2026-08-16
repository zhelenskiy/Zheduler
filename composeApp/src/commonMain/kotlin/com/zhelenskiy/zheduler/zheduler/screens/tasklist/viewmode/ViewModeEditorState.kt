package com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode

import androidx.compose.runtime.*
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import com.zhelenskiy.zheduler.zheduler.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * State holder for the view mode editor.
 */
@Stable
class ViewModeEditorState(
    private val initialViewMode: ViewMode? = null,
    private val spaceId: String,
    private val isCopy: Boolean = false
) {
    var name by mutableStateOf(initialViewMode?.name ?: "")
    var groupingLevels = mutableStateListOf<GroupingLevelState>().apply {
        initialViewMode?.groupingLevels?.forEach { level ->
            add(GroupingLevelState(level))
        }
    }
    var defaultOrderingRules = mutableStateListOf<OrderingRuleState>().apply {
        (initialViewMode?.defaultOrderingRules ?: ViewMode.chronological(spaceId).defaultOrderingRules).forEach { rule ->
            add(OrderingRuleState(rule))
        }
    }

    private val originalId: String? = initialViewMode?.id

    fun toViewMode(): ViewMode = ViewMode(
        id = originalId ?: generateId(),
        name = name.trim(),
        spaceId = spaceId,
        isBuiltIn = false,
        groupingLevels = groupingLevels.mapToPersistentList { it.toGroupingLevel() },
        defaultOrderingRules = defaultOrderingRules.mapToPersistentList { it.toOrderingRule() }
    )

    /**
     * Every error the editor can show, which is what enabling Save depends on.
     *
     * Delegates to the levels rather than to [ViewMode.validate]: by the time a GroupDefinition
     * exists, "10 to 2" and an unparseable bound have both become nulls, so gating Save on the
     * model check left the button enabled next to a range the editor was already marking red.
     */
    fun validate(): GroupingValidationResult {
        val errors = groupingLevels.flatMap { it.validate() }.toPersistentList()
        return if (errors.isEmpty()) {
            GroupingValidationResult.Valid
        } else {
            GroupingValidationResult.Invalid(errors)
        }
    }

    fun hasChanges(): Boolean {
        // Copied mode is always considered to have changes (it's a new unsaved mode)
        if (isCopy) return true

        if (initialViewMode == null) {
            // New mode - has changes if name is not empty or has grouping levels
            return name.isNotBlank() || groupingLevels.isNotEmpty()
        }
        // Compare with the initial state
        val current = toViewMode()
        return current.name != initialViewMode.name ||
                current.groupingLevels != initialViewMode.groupingLevels ||
                current.defaultOrderingRules != initialViewMode.defaultOrderingRules
    }

    fun removeGroupingLevel(index: Int) {
        if (index in groupingLevels.indices) {
            groupingLevels.removeAt(index)
        }
    }

    fun addOrderingRule() {
        defaultOrderingRules.add(OrderingRuleState())
    }

    fun removeOrderingRule(index: Int) {
        if (index in defaultOrderingRules.indices && defaultOrderingRules.size > 1) {
            defaultOrderingRules.removeAt(index)
        }
    }

    fun moveGroupingLevel(fromIndex: Int, toIndex: Int) {
        if (fromIndex in groupingLevels.indices && toIndex in groupingLevels.indices && fromIndex != toIndex) {
            val item = groupingLevels.removeAt(fromIndex)
            groupingLevels.add(toIndex, item)
        }
    }

    fun moveOrderingRule(fromIndex: Int, toIndex: Int) {
        if (fromIndex in defaultOrderingRules.indices && toIndex in defaultOrderingRules.indices && fromIndex != toIndex) {
            val item = defaultOrderingRules.removeAt(fromIndex)
            defaultOrderingRules.add(toIndex, item)
        }
    }
}

/**
 * State holder for a single grouping level.
 */
@OptIn(ExperimentalUuidApi::class)
@Stable
class GroupingLevelState(
    initialLevel: GroupingLevel? = null
) {
    val id: String = Uuid.random().toString()
    var field by mutableStateOf(initialLevel?.field ?: GroupableField.Status)
    var showEmptyGroups by mutableStateOf(initialLevel?.showEmptyGroups ?: false)
    var groups = mutableStateListOf<GroupDefinitionState>().apply {
        initialLevel?.groups?.forEach { group ->
            add(GroupDefinitionState(group))
        }
    }

    init {
        // Initialize default groups when creating a new level (no initial data)
        if (initialLevel == null) {
            initializeDefaultGroups()
        }
    }

    fun toGroupingLevel(): GroupingLevel = GroupingLevel(
        field = field,
        groups = groups.mapToPersistentList { it.toGroupDefinition() },
        showEmptyGroups = showEmptyGroups
    )

    /**
     * Validates this grouping level and returns all errors found.
     */
    fun validate(): List<GroupingValidationError> {
        val errors = mutableListOf<GroupingValidationError>()

        // Check for empty group labels and empty groups
        for (group in groups) {
            if (group.label.isBlank()) {
                errors.add(GroupingValidationError.EmptyGroupLabel(field))
            }
            // For fields with custom ranges, check range validity
            val usesCustomRange = when (field) {
                GroupableField.Priority, GroupableField.DueDate, GroupableField.EstimatedTime -> true
                else -> false
            }
            if (usesCustomRange) {
                if (!group.hasValidRangeForField(field)) {
                    errors.add(GroupingValidationError.InvalidRange(field, group.label))
                }
            } else if (group.values.isEmpty()) {
                errors.add(GroupingValidationError.EmptyGroup(field, group.label))
            }
        }
        // Duplicate values and non-exhaustive coverage are allowed
        // Tasks matching multiple groups will appear in the first matching group
        // Tasks not matching any group will appear in the uncategorized group

        return errors
    }

    fun addGroup() {
        groups.add(GroupDefinitionState())
    }

    /**
     * Returns true if a new group can be added. Always returns true since
     * non-exhaustive coverage is allowed.
     */
    fun canAddGroup(): Boolean = true

    fun removeGroup(index: Int) {
        if (index in groups.indices) {
            groups.removeAt(index)
        }
    }

    fun moveGroup(fromIndex: Int, toIndex: Int) {
        if (fromIndex in groups.indices && toIndex in groups.indices && fromIndex != toIndex) {
            val item = groups.removeAt(fromIndex)
            groups.add(toIndex, item)
        }
    }

    /**
     * Create a snapshot of current state for cancel/restore functionality.
     */
    fun createSnapshot(): GroupingLevelSnapshot = GroupingLevelSnapshot(
        field = field,
        showEmptyGroups = showEmptyGroups,
        groups = groups.map { it.createSnapshot() }
    )

    /**
     * Restore state from a snapshot.
     */
    fun restoreFromSnapshot(snapshot: GroupingLevelSnapshot) {
        field = snapshot.field
        showEmptyGroups = snapshot.showEmptyGroups
        groups.clear()
        snapshot.groups.forEach { groupSnapshot ->
            groups.add(GroupDefinitionState().apply { restoreFromSnapshot(groupSnapshot) })
        }
    }

    /**
     * Initialize groups with default coverage for the selected field.
     */
    fun initializeDefaultGroups() {
        groups.clear()
        when (field) {
            GroupableField.Status -> {
                groups.add(GroupDefinitionState(GroupDefinition("Unresolved Tasks", persistentSetOf("Open", "InProgress"))))
                groups.add(GroupDefinitionState(GroupDefinition("Blocked Tasks", persistentSetOf("Blocked"))))
                groups.add(GroupDefinitionState(GroupDefinition("Completed Tasks", persistentSetOf("Done", "Declined"))))
            }
            GroupableField.Priority -> {
                groups.add(GroupDefinitionState(GroupDefinition("High Priority", persistentSetOf(), priorityMin = 75, priorityMax = 100)))
                groups.add(GroupDefinitionState(GroupDefinition("Medium Priority", persistentSetOf(), priorityMin = 25, priorityMax = 74)))
                groups.add(GroupDefinitionState(GroupDefinition("Low Priority", persistentSetOf(), priorityMin = 1, priorityMax = 24, includeNoPriority = true)))
            }
            GroupableField.DueDate -> {
                groups.add(GroupDefinitionState(GroupDefinition("Overdue", persistentSetOf(), dueDateMaxDays = -1)))
                groups.add(GroupDefinitionState(GroupDefinition("Due Today", persistentSetOf(), dueDateMinDays = 0, dueDateMaxDays = 0)))
                groups.add(GroupDefinitionState(GroupDefinition("Due This Week", persistentSetOf(), dueDateMinDays = 1, dueDateMaxDays = 7)))
                groups.add(GroupDefinitionState(GroupDefinition("Due Later", persistentSetOf(), dueDateMinDays = 8, includeNoDueDate = true)))
            }
            GroupableField.HasConnections -> {
                groups.add(GroupDefinitionState(GroupDefinition("With Connections", persistentSetOf("true"))))
                groups.add(GroupDefinitionState(GroupDefinition("No Connections", persistentSetOf("false"))))
            }
            GroupableField.IsRecurring -> {
                groups.add(GroupDefinitionState(GroupDefinition("Recurring Tasks", persistentSetOf("true"))))
                groups.add(GroupDefinitionState(GroupDefinition("One-time Tasks", persistentSetOf("false"))))
            }
            GroupableField.HasNotifications -> {
                groups.add(GroupDefinitionState(GroupDefinition("With Notifications", persistentSetOf("true"))))
                groups.add(GroupDefinitionState(GroupDefinition("No Notifications", persistentSetOf("false"))))
            }
            GroupableField.AutoUpdateStatus -> {
                groups.add(GroupDefinitionState(GroupDefinition("Auto-updating Status", persistentSetOf("true"))))
                groups.add(GroupDefinitionState(GroupDefinition("Manual Status", persistentSetOf("false"))))
            }
            GroupableField.EstimatedTime -> {
                groups.add(GroupDefinitionState(GroupDefinition("Quick Tasks", persistentSetOf(), estimatedTimeMax = RecurrencePeriod(minutes = 30))))
                groups.add(GroupDefinitionState(GroupDefinition("Medium Tasks", persistentSetOf(), estimatedTimeMin = RecurrencePeriod(minutes = 30, seconds = 1), estimatedTimeMax = RecurrencePeriod(hours = 4))))
                groups.add(GroupDefinitionState(GroupDefinition("Long Tasks", persistentSetOf(), estimatedTimeMin = RecurrencePeriod(hours = 4, seconds = 1), includeNoEstimatedTime = true)))
            }
            GroupableField.Tags -> {
                // Tags don't need default groups - user will define them
            }
        }
    }
}

/**
 * State holder for a single group definition.
 */
@OptIn(ExperimentalUuidApi::class)
@Stable
class GroupDefinitionState(
    initialGroup: GroupDefinition? = null
) {
    val id: String = Uuid.random().toString()
    var label by mutableStateOf(initialGroup?.label ?: "")
    var values = mutableStateListOf<String>().apply {
        initialGroup?.values?.forEach { add(it) }
    }
    var orderingRules = mutableStateListOf<OrderingRuleState>().apply {
        initialGroup?.orderingRules?.forEach { add(OrderingRuleState(it)) }
    }

    // Custom range for Priority (stored as strings for validation)
    var priorityMinText by mutableStateOf(initialGroup?.priorityMin?.toString() ?: "")
    var priorityMaxText by mutableStateOf(initialGroup?.priorityMax?.toString() ?: "")
    var includeNoPriority by mutableStateOf(initialGroup?.includeNoPriority ?: false)

    // Custom range for Estimated Time (compact string format like "2h 30m")
    var estimatedTimeMinText by mutableStateOf(
        initialGroup?.estimatedTimeMin?.toBriefString() ?: ""
    )
    var estimatedTimeMaxText by mutableStateOf(
        initialGroup?.estimatedTimeMax?.toBriefString() ?: ""
    )
    var includeNoEstimatedTime by mutableStateOf(initialGroup?.includeNoEstimatedTime ?: false)

    // Custom range for Due Date (days from today, stored as strings for validation)
    var dueDateMinDaysText by mutableStateOf(initialGroup?.dueDateMinDays?.toString() ?: "")
    var dueDateMaxDaysText by mutableStateOf(initialGroup?.dueDateMaxDays?.toString() ?: "")
    var includeNoDueDate by mutableStateOf(initialGroup?.includeNoDueDate ?: false)

    /**
     * Validates priority range and returns an error message if invalid, null if valid.
     */
    fun validatePriorityRange(): String? {
        val minText = priorityMinText.trim()
        val maxText = priorityMaxText.trim()

        if (minText.isNotBlank() && minText.toIntOrNull() == null) {
            return "Min priority must be a number"
        }
        if (maxText.isNotBlank() && maxText.toIntOrNull() == null) {
            return "Max priority must be a number"
        }

        val min = minText.toIntOrNull()
        val max = maxText.toIntOrNull()

        if (min != null && (min !in 1..100)) {
            return "Min priority must be between 1 and 100"
        }
        if (max != null && (max !in 1..100)) {
            return "Max priority must be between 1 and 100"
        }
        if (min != null && max != null && min > max) {
            return "Min priority cannot be greater than max"
        }
        if (min == null && max == null && !includeNoPriority) {
            return "Specify a range or include tasks without priority"
        }
        return null
    }

    /**
     * Validates estimated time range and returns error message if invalid, null if valid.
     */
    fun validateEstimatedTimeRange(): String? {
        val minParsed = if (estimatedTimeMinText.isNotBlank()) parseCompactTimeToPeriod(estimatedTimeMinText) else null
        val maxParsed = if (estimatedTimeMaxText.isNotBlank()) parseCompactTimeToPeriod(estimatedTimeMaxText) else null

        if (estimatedTimeMinText.isNotBlank() && minParsed == null) {
            return "Invalid min time format (use e.g. 2h 30m)"
        }
        if (estimatedTimeMaxText.isNotBlank() && maxParsed == null) {
            return "Invalid max time format (use e.g. 2h 30m)"
        }
        if (minParsed != null && maxParsed != null &&
            minParsed.toApproximateSeconds() > maxParsed.toApproximateSeconds()) {
            return "Min time cannot be greater than max"
        }
        if (minParsed == null && maxParsed == null && !includeNoEstimatedTime) {
            return "Specify a range or include tasks without estimate"
        }
        return null
    }

    /**
     * Validates due date range and returns error message if invalid, null if valid.
     */
    fun validateDueDateRange(): String? {
        val minText = dueDateMinDaysText.trim()
        val maxText = dueDateMaxDaysText.trim()

        if (minText.isNotBlank() && minText.toIntOrNull() == null) {
            return "From days must be a number"
        }
        if (maxText.isNotBlank() && maxText.toIntOrNull() == null) {
            return "To days must be a number"
        }

        val min = minText.toIntOrNull()
        val max = maxText.toIntOrNull()

        if (min != null && max != null && min > max) {
            return "From days cannot be greater than To days"
        }
        if (min == null && max == null && !includeNoDueDate) {
            return "Specify a range or include tasks without due date"
        }
        return null
    }

    /**
     * Returns true if this group definition has valid custom range values for the given field.
     */
    fun hasValidRangeForField(field: GroupableField): Boolean = when (field) {
        GroupableField.Priority -> validatePriorityRange() == null
        GroupableField.EstimatedTime -> validateEstimatedTimeRange() == null
        GroupableField.DueDate -> validateDueDateRange() == null
        else -> true
    }

    fun toGroupDefinition(): GroupDefinition {
        return GroupDefinition(
            label = label.trim(),
            values = values.toPersistentSet(),
            orderingRules = orderingRules.mapToPersistentList { it.toOrderingRule() },
            priorityMin = priorityMinText.trim().toIntOrNull(),
            priorityMax = priorityMaxText.trim().toIntOrNull(),
            includeNoPriority = includeNoPriority,
            estimatedTimeMin = if (estimatedTimeMinText.isNotBlank()) parseCompactTimeToPeriod(estimatedTimeMinText) else null,
            estimatedTimeMax = if (estimatedTimeMaxText.isNotBlank()) parseCompactTimeToPeriod(estimatedTimeMaxText) else null,
            includeNoEstimatedTime = includeNoEstimatedTime,
            dueDateMinDays = dueDateMinDaysText.trim().toIntOrNull(),
            dueDateMaxDays = dueDateMaxDaysText.trim().toIntOrNull(),
            includeNoDueDate = includeNoDueDate
        )
    }

    fun addValue(value: String) {
        if (value.isNotBlank() && value !in values) {
            values.add(value)
        }
    }

    fun removeValue(value: String) {
        values.remove(value)
    }

    fun addOrderingRule() {
        orderingRules.add(OrderingRuleState())
    }

    fun removeOrderingRule(index: Int) {
        if (index in orderingRules.indices) {
            orderingRules.removeAt(index)
        }
    }

    fun moveOrderingRule(fromIndex: Int, toIndex: Int) {
        if (fromIndex in orderingRules.indices && toIndex in orderingRules.indices && fromIndex != toIndex) {
            val item = orderingRules.removeAt(fromIndex)
            orderingRules.add(toIndex, item)
        }
    }

    /**
     * Create a snapshot of current state for cancel/restore functionality.
     */
    fun createSnapshot(): GroupDefinitionSnapshot = GroupDefinitionSnapshot(
        label = label,
        values = values.toList(),
        orderingRules = orderingRules.map { OrderingRuleSnapshot(it.field, it.direction, it.nullPosition) },
        priorityMinText = priorityMinText,
        priorityMaxText = priorityMaxText,
        includeNoPriority = includeNoPriority,
        estimatedTimeMinText = estimatedTimeMinText,
        estimatedTimeMaxText = estimatedTimeMaxText,
        includeNoEstimatedTime = includeNoEstimatedTime,
        dueDateMinDaysText = dueDateMinDaysText,
        dueDateMaxDaysText = dueDateMaxDaysText,
        includeNoDueDate = includeNoDueDate
    )

    /**
     * Restore state from a snapshot.
     */
    fun restoreFromSnapshot(snapshot: GroupDefinitionSnapshot) {
        label = snapshot.label
        values.clear()
        values.addAll(snapshot.values)
        orderingRules.clear()
        snapshot.orderingRules.forEach { ruleSnapshot ->
            orderingRules.add(OrderingRuleState().apply {
                field = ruleSnapshot.field
                direction = ruleSnapshot.direction
                nullPosition = ruleSnapshot.nullPosition
            })
        }
        priorityMinText = snapshot.priorityMinText
        priorityMaxText = snapshot.priorityMaxText
        includeNoPriority = snapshot.includeNoPriority
        estimatedTimeMinText = snapshot.estimatedTimeMinText
        estimatedTimeMaxText = snapshot.estimatedTimeMaxText
        includeNoEstimatedTime = snapshot.includeNoEstimatedTime
        dueDateMinDaysText = snapshot.dueDateMinDaysText
        dueDateMaxDaysText = snapshot.dueDateMaxDaysText
        includeNoDueDate = snapshot.includeNoDueDate
    }
}

/**
 * Snapshot data classes for cancel/restore functionality.
 */
data class GroupingLevelSnapshot(
    val field: GroupableField,
    val showEmptyGroups: Boolean,
    val groups: List<GroupDefinitionSnapshot>
)

data class GroupDefinitionSnapshot(
    val label: String,
    val values: List<String>,
    val orderingRules: List<OrderingRuleSnapshot>,
    val priorityMinText: String = "",
    val priorityMaxText: String = "",
    val includeNoPriority: Boolean = false,
    val estimatedTimeMinText: String = "",
    val estimatedTimeMaxText: String = "",
    val includeNoEstimatedTime: Boolean = false,
    val dueDateMinDaysText: String = "",
    val dueDateMaxDaysText: String = "",
    val includeNoDueDate: Boolean = false
)

data class OrderingRuleSnapshot(
    val field: OrderableField,
    val direction: OrderDirection,
    val nullPosition: NullPosition
)

/**
 * State holder for a single ordering rule.
 */
@OptIn(ExperimentalUuidApi::class)
@Stable
class OrderingRuleState(
    initialRule: OrderingRule? = null
) {
    val id: String = Uuid.random().toString()
    var field by mutableStateOf(initialRule?.field ?: OrderableField.Id)
    var direction by mutableStateOf(initialRule?.direction ?: OrderDirection.Ascending)
    var nullPosition by mutableStateOf(initialRule?.nullPosition ?: NullPosition.Last)

    fun toOrderingRule(): OrderingRule = OrderingRule(
        field = field,
        direction = direction,
        nullPosition = nullPosition
    )
}

@Composable
fun rememberViewModeEditorState(
    viewMode: ViewMode? = null,
    spaceId: String,
    isCopy: Boolean = false
): ViewModeEditorState = remember(viewMode?.id, spaceId, isCopy) {
    ViewModeEditorState(viewMode, spaceId, isCopy)
}

/**
 * Generate a random ID for view modes.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun generateId(): String = Uuid.random().toString()
