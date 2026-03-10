@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
    shouldAnimate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SearchInputRow(
            filterState = filterState,
            isFilterPanelOpen = isFilterPanelOpen,
            onToggleFilterPanel = onToggleFilterPanel,
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
    val noOpInteractionSource = remember {
        object : MutableInteractionSource {
            override val interactions: Flow<Interaction> = emptyFlow()
            override suspend fun emit(interaction: Interaction) {}
            override fun tryEmit(interaction: Interaction) = true
        }
    }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(buildFilterChips(filterState).entries.toList(), key = { it.key }) { (_, chip) ->
            SuggestionChip(
                onClick = { },
                label = { Text(text = chip, style = MaterialTheme.typography.labelSmall) },
                interactionSource = noOpInteractionSource,
                modifier = Modifier.animateItem()
            )
        }
    }
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

private enum class FilterChipType {
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

private fun buildFilterChips(filterState: TaskFilterState): Map<FilterChipType, String> = buildMap {
    addSearchChip(filterState)
    addStatusChip(filterState)
    addDueDateChip(filterState)
    addPriorityChip(filterState)
    addEstimatedTimeChip(filterState)
    addRecurrenceChip(filterState)
    addNotificationsChip(filterState)
    addAutoUpdateChip(filterState)
    addConnectionChips(filterState)
    addTagsChip(filterState)
}

private fun MutableMap<FilterChipType, String>.addSearchChip(filterState: TaskFilterState) {
    if (filterState.searchQuery.isNotBlank()) {
        val searchFields = filterState.textSearchFields.joinToString(", ") { it.displayName }
        put(FilterChipType.Search, "Search in: $searchFields")
    }
}

private fun MutableMap<FilterChipType, String>.addStatusChip(filterState: TaskFilterState) {
    if (filterState.statusFilters.isEmpty()) return

    val updatedStatuses = filterState.statusFilters.map { status ->
        when (status) {
            is TaskStatus.Blocked -> {
                val ids = filterState.blockedByTaskIds
                    .takeIf { it.isNotBlank() }
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { Regex("[a-zA-Z]+-[1-9][0-9]*").matchEntire(it) != null }
                    .orEmpty()
                    .toSet()
                status.copy(blockerTaskIds = ids, comment = filterState.blockedByComment)
            }
            is TaskStatus.Declined -> status.copy(reason = filterState.declinedReason)
            else -> status
        }
    }
    put(FilterChipType.Status, "Status: ${updatedStatuses.joinToString(", ") { it.toBriefString() }}")
}

private fun MutableMap<FilterChipType, String>.addDueDateChip(filterState: TaskFilterState) {
    if (filterState.dueDateFilter == DueDateFilter.Any) return

    val dueSummary = if (filterState.dueDateFilter == DueDateFilter.Custom) {
        val after = filterState.customDueDateAfter?.let { formatDueDate(it) }
        val before = filterState.customDueDateBefore?.let { formatDueDate(it) }
        when {
            after != null && before != null && after == before -> "Due: $after"
            after != null && before != null -> "Due: $after - $before"
            after != null -> "Due: ≥$after"
            before != null -> "Due: ≤$before"
            else -> "Due: Some"
        }
    } else {
        "Due: ${filterState.dueDateFilter.displayName}"
    }
    put(FilterChipType.DueDate, dueSummary)
}

private fun MutableMap<FilterChipType, String>.addPriorityChip(filterState: TaskFilterState) {
    if (filterState.priorityFilter == PriorityFilter.Any) return

    val prioritySummary = if (filterState.priorityFilter == PriorityFilter.Custom) {
        val min = filterState.customPriorityMin.takeIf { it.isNotBlank() }
        val max = filterState.customPriorityMax.takeIf { it.isNotBlank() }
        when {
            min != null && max != null && min == max -> "Priority: $min"
            min != null && max != null -> "Priority: $min-$max"
            min != null -> "Priority: ≥$min"
            max != null -> "Priority: ≤$max"
            else -> "Priority: Some"
        }
    } else {
        "Priority: ${filterState.priorityFilter.displayName}"
    }
    put(FilterChipType.Priority, prioritySummary)
}

private fun MutableMap<FilterChipType, String>.addEstimatedTimeChip(filterState: TaskFilterState) {
    if (filterState.estimatedTimeFilter == EstimatedTimeFilter.Any) return

    val timeSummary = if (filterState.estimatedTimeFilter == EstimatedTimeFilter.Custom) {
        val minStr = filterState.customEstimatedTimeMin.takeIf { it.isNotBlank() }
        val maxStr = filterState.customEstimatedTimeMax.takeIf { it.isNotBlank() }
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
        "Duration: ${filterState.estimatedTimeFilter.displayName}"
    }
    put(FilterChipType.EstimatedTime, timeSummary)
}

private fun MutableMap<FilterChipType, String>.addRecurrenceChip(filterState: TaskFilterState) {
    if (filterState.recurrenceFilter != RecurrenceFilter.Any) {
        put(FilterChipType.Recurrence, "Recurrence: ${filterState.recurrenceFilter.displayName}")
    }
}

private fun MutableMap<FilterChipType, String>.addNotificationsChip(filterState: TaskFilterState) {
    if (filterState.notificationsFilter != NotificationsFilter.Any) {
        put(FilterChipType.Notifications, "Notifications: ${filterState.notificationsFilter.displayName}")
    }
}

private fun MutableMap<FilterChipType, String>.addAutoUpdateChip(filterState: TaskFilterState) {
    if (filterState.autoUpdateStatusFilter != AutoUpdateStatusFilter.Any) {
        put(FilterChipType.AutoUpdate, "Status update: ${filterState.autoUpdateStatusFilter.displayName}")
    }
}

private fun MutableMap<FilterChipType, String>.addConnectionChips(filterState: TaskFilterState) {
    ConnectionTypeOption.entries.forEach { type ->
        if (type in filterState.connectionTypeFilters) {
            val (chipType, label) = when (type) {
                ConnectionTypeOption.DependsOn ->
                    FilterChipType.ConnectionDependsOn to if (filterState.dependsOnTaskIds.isNotBlank())
                        "Depends on: ${filterState.dependsOnTaskIds}"
                    else type.displayName
                ConnectionTypeOption.IsDependencyOf ->
                    FilterChipType.ConnectionIsDependencyOf to if (filterState.isDependencyOfTaskIds.isNotBlank())
                        "Is dependency of: ${filterState.isDependencyOfTaskIds}"
                    else type.displayName
                ConnectionTypeOption.RelatesTo ->
                    FilterChipType.ConnectionRelatesTo to if (filterState.relatesToTaskIds.isNotBlank())
                        "Relates to: ${filterState.relatesToTaskIds}"
                    else type.displayName
                ConnectionTypeOption.SubtaskOf ->
                    FilterChipType.ConnectionSubtaskOf to if (filterState.subtaskOfTaskIds.isNotBlank())
                        "Is subtask of: ${filterState.subtaskOfTaskIds}"
                    else type.displayName
                ConnectionTypeOption.ParentOf ->
                    FilterChipType.ConnectionParentOf to if (filterState.parentOfTaskIds.isNotBlank())
                        "Is parent of: ${filterState.parentOfTaskIds}"
                    else type.displayName
                ConnectionTypeOption.NotSubtask -> FilterChipType.ConnectionNotSubtask to type.displayName
            }
            put(chipType, label)
        }
    }
}

private fun MutableMap<FilterChipType, String>.addTagsChip(filterState: TaskFilterState) {
    if (filterState.selectedTags.isNotEmpty()) {
        put(FilterChipType.Tags, "Tag: ${filterState.selectedTags.joinToString(", ")}")
    }
}
