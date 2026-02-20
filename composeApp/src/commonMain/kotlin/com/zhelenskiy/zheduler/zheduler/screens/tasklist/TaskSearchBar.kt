@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist

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
import com.zhelenskiy.zheduler.zheduler.util.formatPeriod
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSearchBar(
    filterState: TaskFilterState,
    isFilterPanelOpen: Boolean,
    onToggleFilterPanel: () -> Unit,
    isInitialLoad: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Search text field with options button to the right
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = filterState.searchQuery,
                onValueChange = { filterState.searchQuery = it },
                placeholder = {
                    Text(
                        "Search in ${filterState.textSearchFields.joinToString(", ") { it.displayName }}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                modifier = Modifier.weight(1f),
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
            IconButton(onClick = onToggleFilterPanel) {
                BadgedBox(
                    badge = {
                        if (filterState.hasActiveFilters) {
                            Badge()
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = if (isFilterPanelOpen) "Hide filters" else "Show filters",
                        tint = if (isFilterPanelOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
            }
        }

        // Filter summary as chips - always shown when filters active
        androidx.compose.animation.AnimatedVisibility(
            visible = filterState.hasActiveFilters,
            enter = if (isInitialLoad) androidx.compose.animation.EnterTransition.None
                    else androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = if (isInitialLoad) androidx.compose.animation.ExitTransition.None
                   else androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Active filters as chips in horizontally scrollable LazyRow
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(buildFilterChips(filterState), key = { it.first }) { (chipKey, chip) ->
                        SuggestionChip(
                            onClick = { },
                            label = {
                                Text(
                                    text = chip,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            interactionSource = remember {
                                object : MutableInteractionSource {
                                    override val interactions: Flow<Interaction> = emptyFlow()
                                    override suspend fun emit(interaction: Interaction) {}
                                    override fun tryEmit(interaction: Interaction) = true
                                }
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                TextButton(
                    onClick = { filterState.clearAll() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun buildFilterChips(filterState: TaskFilterState): List<Pair<String, String>> {
    val chips = mutableListOf<Pair<String, String>>()

    if (filterState.searchQuery.isNotBlank()) {
        val searchFields = filterState.textSearchFields.joinToString(", ") { it.displayName }
        chips.add("search" to "Search in: $searchFields")
    }

    if (filterState.statusFilters.isNotEmpty()) {
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
        chips.add("status" to "Status: ${updatedStatuses.joinToString(", ") { it.toBriefString() }}")
    }

    if (filterState.dueDateFilter != DueDateFilter.Any) {
        val dueSummary = if (filterState.dueDateFilter == DueDateFilter.Custom) {
            val after = filterState.customDueDateAfter?.let { formatDueDate(it) }
            val before = filterState.customDueDateBefore?.let { formatDueDate(it) }
            when {
                after != null && before != null && after == before -> "Due: $after"
                after != null && before != null -> "Due: $after - $before"
                after != null -> "Due: ≥$after"
                before != null -> "Due: ≤$before"
                else -> "Due: Custom"
            }
        } else {
            "Due: ${filterState.dueDateFilter.displayName}"
        }
        chips.add("dueDate" to dueSummary)
    }

    if (filterState.priorityFilter != PriorityFilter.Any) {
        val prioritySummary = if (filterState.priorityFilter == PriorityFilter.Custom) {
            val min = filterState.customPriorityMin.takeIf { it.isNotBlank() }
            val max = filterState.customPriorityMax.takeIf { it.isNotBlank() }
            when {
                min != null && max != null && min == max -> "Priority: $min"
                min != null && max != null -> "Priority: $min-$max"
                min != null -> "Priority: ≥$min"
                max != null -> "Priority: ≤$max"
                else -> "Priority: Custom"
            }
        } else {
            "Priority: ${filterState.priorityFilter.displayName}"
        }
        chips.add("priority" to prioritySummary)
    }

    if (filterState.estimatedTimeFilter != EstimatedTimeFilter.Any) {
        val timeSummary = if (filterState.estimatedTimeFilter == EstimatedTimeFilter.Custom) {
            val minStr = filterState.customEstimatedTimeMin.takeIf { it.isNotBlank() }
            val maxStr = filterState.customEstimatedTimeMax.takeIf { it.isNotBlank() }
            val minPeriod = minStr?.let { parseCompactTimeToPeriod(it) }
            val maxPeriod = maxStr?.let { parseCompactTimeToPeriod(it) }

            when {
                minPeriod != null && maxPeriod != null && minStr == maxStr -> "Time: ${formatPeriod(minPeriod)}"
                minPeriod != null && maxPeriod != null -> "Time: ${formatPeriod(minPeriod)}-${formatPeriod(maxPeriod)}"
                minPeriod != null -> "Time: ≥${formatPeriod(minPeriod)}"
                maxPeriod != null -> "Time: ≤${formatPeriod(maxPeriod)}"
                else -> "Time: Custom"
            }
        } else {
            "Time: ${filterState.estimatedTimeFilter.displayName}"
        }
        chips.add("estimatedTime" to timeSummary)
    }

    if (filterState.recurrenceFilter != RecurrenceFilter.Any) {
        chips.add("recurrence" to "Recurrence: ${filterState.recurrenceFilter.displayName}")
    }

    if (filterState.notificationsFilter != NotificationsFilter.Any) {
        chips.add("notifications" to "Notifications: ${filterState.notificationsFilter.displayName}")
    }

    if (filterState.autoUpdateStatusFilter != AutoUpdateStatusFilter.Any) {
        chips.add("autoUpdate" to "Status update: ${filterState.autoUpdateStatusFilter.displayName}")
    }

    // Add separate chip for each connection type in enum order
    ConnectionTypeOption.entries.forEach { type ->
        if (type in filterState.connectionTypeFilters) {
            val (key, label) = when (type) {
                ConnectionTypeOption.DependsOn ->
                    "connection_dependsOn" to if (filterState.dependsOnTaskIds.isNotBlank())
                        "Depends on: ${filterState.dependsOnTaskIds}"
                    else type.displayName
                ConnectionTypeOption.IsDependencyOf ->
                    "connection_isDependencyOf" to if (filterState.isDependencyOfTaskIds.isNotBlank())
                        "Is dependency of: ${filterState.isDependencyOfTaskIds}"
                    else type.displayName
                ConnectionTypeOption.RelatesTo ->
                    "connection_relatesTo" to if (filterState.relatesToTaskIds.isNotBlank())
                        "Relates to: ${filterState.relatesToTaskIds}"
                    else type.displayName
                ConnectionTypeOption.SubtaskOf ->
                    "connection_subtaskOf" to if (filterState.subtaskOfTaskIds.isNotBlank())
                        "Is subtask of: ${filterState.subtaskOfTaskIds}"
                    else type.displayName
                ConnectionTypeOption.ParentOf ->
                    "connection_parentOf" to if (filterState.parentOfTaskIds.isNotBlank())
                        "Is parent of: ${filterState.parentOfTaskIds}"
                    else type.displayName
                else -> "connection_${type.name}" to type.displayName
            }
            chips.add(key to label)
        }
    }

    if (filterState.selectedTags.isNotEmpty()) {
        chips.add("tags" to "Tag: ${filterState.selectedTags.joinToString(", ")}")
    }

    return chips
}
