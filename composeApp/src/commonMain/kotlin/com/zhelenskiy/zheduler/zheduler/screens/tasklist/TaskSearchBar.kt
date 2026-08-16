@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.parseCompactTimeToPeriod
import com.zhelenskiy.zheduler.zheduler.util.formatDueDate
import kotlin.time.ExperimentalTime

@Composable
fun TaskSearchBar(
    filterState: TaskFilterState,
    isFilterPanelOpen: Boolean,
    onToggleFilterPanel: () -> Unit,
    onSaveFilter: (() -> Unit)? = null,
    shouldAnimate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SearchInputRow(
            filterState = filterState,
            isFilterPanelOpen = isFilterPanelOpen,
            onToggleFilterPanel = onToggleFilterPanel,
            onSaveFilter = onSaveFilter,
            shouldAnimate = shouldAnimate,
        )

        ActiveFiltersChips(
            filterState = filterState,
            shouldAnimate = shouldAnimate,
        )
    }
}

@Composable
private fun SearchInputRow(
    filterState: TaskFilterState,
    isFilterPanelOpen: Boolean,
    onToggleFilterPanel: () -> Unit,
    onSaveFilter: (() -> Unit)?,
    shouldAnimate: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchTextField(
            filterState = filterState,
            modifier = Modifier.weight(1f),
        )

        // Save filter button - appears when filters are active
        AnimatedVisibility(
            visible = filterState.hasActiveFilters && onSaveFilter != null,
            enter = if (shouldAnimate) fadeIn() + expandHorizontally() else EnterTransition.None,
            exit = if (shouldAnimate) fadeOut() + shrinkHorizontally() else ExitTransition.None,
        ) {
            IconButton(onClick = { onSaveFilter?.invoke() }) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = "Save current filter"
                )
            }
        }

        FilterToggleButton(
            isFilterPanelOpen = isFilterPanelOpen,
            hasActiveFilters = filterState.hasActiveFilters,
            onClick = onToggleFilterPanel,
        )
    }
}

@Composable
private fun SearchTextField(
    filterState: TaskFilterState,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = filterState.searchQuery,
        onValueChange = { filterState.searchQuery = it },
        placeholder = {
            Text(
                text = "Search in ${filterState.textSearchFields.joinToString(", ") { it.displayName }}",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        modifier = modifier,
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (filterState.searchQuery.isNotBlank()) {
                IconButton(onClick = { filterState.searchQuery = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterToggleButton(
    isFilterPanelOpen: Boolean,
    hasActiveFilters: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (hasActiveFilters) {
                    Badge()
                }
            },
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = if (isFilterPanelOpen) "Hide filters" else "Show filters",
                tint = if (isFilterPanelOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current
            )
        }
    }
}

@Composable
private fun ActiveFiltersChips(
    filterState: TaskFilterState,
    shouldAnimate: Boolean,
) {
    AnimatedVisibility(
        visible = filterState.hasActiveFilters,
        enter = if (shouldAnimate) fadeIn() + expandVertically() else EnterTransition.None,
        exit = if (shouldAnimate) fadeOut() + shrinkVertically() else ExitTransition.None,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChipsList(
                filterState = filterState,
                modifier = Modifier.weight(1f),
            )

            ClearFiltersButton(onClick = { filterState.clearAll() })
        }
    }
}

@Composable
private fun FilterChipsList(
    filterState: TaskFilterState,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(buildFilterChips(filterState).entries.toList(), key = { it.key }) { (_, chip) ->
            FilterChip(
                text = chip,
                modifier = Modifier.animateItem()
            )
        }
    }
}

@Composable
fun FilterChip(
    text: String,
    modifier: Modifier = Modifier
) {
    val noOpInteractionSource = remember {
        object : MutableInteractionSource {
            override val interactions: Flow<Interaction> = emptyFlow()
            override suspend fun emit(interaction: Interaction) {}
            override fun tryEmit(interaction: Interaction) = true
        }
    }

    SuggestionChip(
        onClick = { },
        label = { Text(text = text, style = MaterialTheme.typography.labelSmall) },
        interactionSource = noOpInteractionSource,
        modifier = modifier
    )
}

@Composable
private fun ClearFiltersButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("Clear", style = MaterialTheme.typography.labelSmall)
    }
}

enum class FilterChipType {
    Search,
    Status,
    DueDate,
    Priority,
    EstimatedTime,
    Recurrence,
    Notifications,
    AutoUpdate,
    ConnectionDependsOn,
    ConnectionIsDependencyOf,
    ConnectionRelatesTo,
    ConnectionSubtaskOf,
    ConnectionParentOf,
    ConnectionNotSubtask,
    Tags
}

private fun buildFilterChips(filterState: TaskFilterState): Map<FilterChipType, String> =
    buildFilterChipsFromCriteria(filterState.toCriteria())

/**
 * Build filter chips from TaskFilterCriteria.
 * This can be used to display filter chips in various contexts.
 */
fun buildFilterChipsFromCriteria(criteria: TaskFilterCriteria): Map<FilterChipType, String> = buildMap {
    addSearchChip(criteria)
    addStatusChip(criteria)
    addDueDateChip(criteria)
    addPriorityChip(criteria)
    addEstimatedTimeChip(criteria)
    addRecurrenceChip(criteria)
    addNotificationsChip(criteria)
    addAutoUpdateChip(criteria)
    addConnectionChips(criteria)
    addTagsChip(criteria)
}

private fun MutableMap<FilterChipType, String>.addSearchChip(criteria: TaskFilterCriteria) {
    if (criteria.searchQuery.isNotBlank()) {
        val searchFields = criteria.textSearchFields.joinToString(", ") { it.displayName }
        put(FilterChipType.Search, "\"${criteria.searchQuery}\" in $searchFields")
    }
}

private fun MutableMap<FilterChipType, String>.addStatusChip(criteria: TaskFilterCriteria) {
    if (criteria.statusFilters.isEmpty()) return

    val updatedStatuses = criteria.statusFilters.map { status ->
        when (status) {
            is TaskStatus.Blocked -> {
                val ids = criteria.blockedByTaskIds
                    .takeIf { it.isNotBlank() }
                    .orEmpty()
                    .split(",")
                    .map { it.trim() }
                    .filterToPersistentSet { Regex("[a-zA-Z]+-[1-9][0-9]*").matchEntire(it) != null }
                status.copy(blockerTaskIds = ids, comment = criteria.blockedByComment)
            }
            is TaskStatus.Declined -> status.copy(reason = criteria.declinedReason)
            else -> status
        }
    }
    put(FilterChipType.Status, "Status: ${updatedStatuses.joinToString(", ") { it.toBriefString() }}")
}

private fun MutableMap<FilterChipType, String>.addDueDateChip(criteria: TaskFilterCriteria) {
    if (criteria.dueDateFilter == DueDateFilter.Any) return

    val dueSummary = if (criteria.dueDateFilter == DueDateFilter.Custom) {
        val after = criteria.customDueDateAfter?.let { formatDueDate(it) }
        val before = criteria.customDueDateBefore?.let { formatDueDate(it) }
        when {
            after != null && before != null && after == before -> "Due: $after"
            after != null && before != null -> "Due: $after - $before"
            after != null -> "Due: ≥$after"
            before != null -> "Due: ≤$before"
            else -> "Due: Some"
        }
    } else {
        "Due: ${criteria.dueDateFilter.displayName}"
    }
    put(FilterChipType.DueDate, dueSummary)
}

private fun MutableMap<FilterChipType, String>.addPriorityChip(criteria: TaskFilterCriteria) {
    if (criteria.priorityFilter == PriorityFilter.Any) return

    val prioritySummary = if (criteria.priorityFilter == PriorityFilter.Custom) {
        val min = criteria.customPriorityMin.takeIf { it.isNotBlank() }
        val max = criteria.customPriorityMax.takeIf { it.isNotBlank() }
        when {
            min != null && max != null && min == max -> "Priority: $min"
            min != null && max != null -> "Priority: $min-$max"
            min != null -> "Priority: ≥$min"
            max != null -> "Priority: ≤$max"
            else -> "Priority: Some"
        }
    } else {
        "Priority: ${criteria.priorityFilter.displayName}"
    }
    put(FilterChipType.Priority, prioritySummary)
}

private fun MutableMap<FilterChipType, String>.addEstimatedTimeChip(criteria: TaskFilterCriteria) {
    if (criteria.estimatedTimeFilter == EstimatedTimeFilter.Any) return

    val timeSummary = if (criteria.estimatedTimeFilter == EstimatedTimeFilter.Custom) {
        val minStr = criteria.customEstimatedTimeMin.takeIf { it.isNotBlank() }
        val maxStr = criteria.customEstimatedTimeMax.takeIf { it.isNotBlank() }
        val minPeriod = minStr?.let { parseCompactTimeToPeriod(it) }
        val maxPeriod = maxStr?.let { parseCompactTimeToPeriod(it) }

        when {
            minPeriod != null && maxPeriod != null && minStr == maxStr -> "Duration: ${minPeriod.toBriefString()}"
            minPeriod != null && maxPeriod != null -> "Duration: ${minPeriod.toBriefString()}-${maxPeriod.toBriefString()}"
            minPeriod != null -> "Duration: ≥${minPeriod.toBriefString()}"
            maxPeriod != null -> "Duration: ≤${maxPeriod.toBriefString()}"
            else -> "Duration: Some"
        }
    } else {
        "Duration: ${criteria.estimatedTimeFilter.displayName}"
    }
    put(FilterChipType.EstimatedTime, timeSummary)
}

private fun MutableMap<FilterChipType, String>.addRecurrenceChip(criteria: TaskFilterCriteria) {
    if (criteria.recurrenceFilter != RecurrenceFilter.Any) {
        put(FilterChipType.Recurrence, "Recurrence: ${criteria.recurrenceFilter.displayName}")
    }
}

private fun MutableMap<FilterChipType, String>.addNotificationsChip(criteria: TaskFilterCriteria) {
    if (criteria.notificationsFilter != NotificationsFilter.Any) {
        put(FilterChipType.Notifications, "Notifications: ${criteria.notificationsFilter.displayName}")
    }
}

private fun MutableMap<FilterChipType, String>.addAutoUpdateChip(criteria: TaskFilterCriteria) {
    if (criteria.autoUpdateStatusFilter != AutoUpdateStatusFilter.Any) {
        put(FilterChipType.AutoUpdate, "Status update: ${criteria.autoUpdateStatusFilter.displayName}")
    }
}

private fun MutableMap<FilterChipType, String>.addConnectionChips(criteria: TaskFilterCriteria) {
    /** The ids typed for [type], which narrow the list whether or not its chip is ticked. */
    fun idsFor(type: ConnectionTypeOption): String = when (type) {
        ConnectionTypeOption.DependsOn -> criteria.dependsOnTaskIds
        ConnectionTypeOption.IsDependencyOf -> criteria.isDependencyOfTaskIds
        ConnectionTypeOption.RelatesTo -> criteria.relatesToTaskIds
        ConnectionTypeOption.SubtaskOf -> criteria.subtaskOfTaskIds
        ConnectionTypeOption.ParentOf -> criteria.parentOfTaskIds
        ConnectionTypeOption.NotSubtask -> ""
    }

    ConnectionTypeOption.entries.forEach { type ->
        // Ticked, or holding ids that are filtering anyway: a chip is how the user finds a filter
        // in force, so one that only shows while its type is ticked hides the very case where the
        // list is short for a reason nothing on screen explains.
        if (type in criteria.connectionTypeFilters || idsFor(type).isNotBlank()) {
            val (chipType, label) = when (type) {
                ConnectionTypeOption.DependsOn ->
                    FilterChipType.ConnectionDependsOn to if (criteria.dependsOnTaskIds.isNotBlank())
                        "Depends on: ${criteria.dependsOnTaskIds}"
                    else type.displayName
                ConnectionTypeOption.IsDependencyOf ->
                    FilterChipType.ConnectionIsDependencyOf to if (criteria.isDependencyOfTaskIds.isNotBlank())
                        "Is dependency of: ${criteria.isDependencyOfTaskIds}"
                    else type.displayName
                ConnectionTypeOption.RelatesTo ->
                    FilterChipType.ConnectionRelatesTo to if (criteria.relatesToTaskIds.isNotBlank())
                        "Relates to: ${criteria.relatesToTaskIds}"
                    else type.displayName
                ConnectionTypeOption.SubtaskOf ->
                    FilterChipType.ConnectionSubtaskOf to if (criteria.subtaskOfTaskIds.isNotBlank())
                        "Is subtask of: ${criteria.subtaskOfTaskIds}"
                    else type.displayName
                ConnectionTypeOption.ParentOf ->
                    FilterChipType.ConnectionParentOf to if (criteria.parentOfTaskIds.isNotBlank())
                        "Is parent of: ${criteria.parentOfTaskIds}"
                    else type.displayName
                ConnectionTypeOption.NotSubtask -> FilterChipType.ConnectionNotSubtask to type.displayName
            }
            put(chipType, label)
        }
    }
}

private fun MutableMap<FilterChipType, String>.addTagsChip(criteria: TaskFilterCriteria) {
    if (criteria.selectedTags.isNotEmpty()) {
        put(FilterChipType.Tags, "Tag: ${criteria.selectedTags.joinToString(", ")}")
    }
}
