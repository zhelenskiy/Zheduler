@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DatePickerDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.allStatusDefaultValues
import com.zhelenskiy.zheduler.zheduler.util.formatDueDate
import com.zhelenskiy.zheduler.zheduler.parseCompactTimeToPeriod
import kotlinx.collections.immutable.persistentSetOf
import kotlin.time.ExperimentalTime

private enum class FilterCategory {
    SearchIn, Status, DueDate, Priority, EstimatedTime, Recurrence, Notifications, Connections, Tags
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFilterPanel(
    filterState: TaskFilterState,
    allTags: Set<String>,
    spaceIdPrefix: String?,
    modifier: Modifier = Modifier
) {
    var expandedCategory by remember { mutableStateOf<FilterCategory?>(null) }
    var lastExpandedCategory by remember { mutableStateOf<FilterCategory?>(null) }

    LaunchedEffect(expandedCategory) {
        if (expandedCategory != null) {
            lastExpandedCategory = expandedCategory
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring()),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            FilterCategoryChipsRow(
                filterState = filterState,
                allTags = allTags,
                expandedCategory = expandedCategory,
                onCategoryClick = { category ->
                    expandedCategory = if (expandedCategory == category) null else category
                }
            )

            FilterOptionsContent(
                filterState = filterState,
                allTags = allTags,
                spaceIdPrefix = spaceIdPrefix,
                expandedCategory = expandedCategory,
                lastExpandedCategory = lastExpandedCategory
            )
        }
    }
}

/*
 * A note on chip heights: these used to be pinned with `Modifier.height(28.dp)`, which clipped
 * their labels once the system font scale grew. The pin is gone, and nothing replaces it — a
 * Material chip already carries a minimum of its own that the label grows past when it needs to,
 * so a stated minimum below that could never take effect and only read as though it did.
 */
@Composable
private fun FilterCategoryChipsRow(
    filterState: TaskFilterState,
    allTags: Set<String>,
    expandedCategory: FilterCategory?,
    onCategoryClick: (FilterCategory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterCategoryChip(
            title = "Search in",
            isExpanded = expandedCategory == FilterCategory.SearchIn,
            onClick = { onCategoryClick(FilterCategory.SearchIn) },
            hasActiveFilter = filterState.textSearchFields != TaskFilterState.DefaultTextSearchFields
        )

        FilterCategoryChip(
            title = "Status",
            isExpanded = expandedCategory == FilterCategory.Status,
            onClick = { onCategoryClick(FilterCategory.Status) },
            hasActiveFilter = filterState.statusFilters.isNotEmpty()
        )

        FilterCategoryChip(
            title = "Due Time",
            isExpanded = expandedCategory == FilterCategory.DueDate,
            onClick = { onCategoryClick(FilterCategory.DueDate) },
            hasActiveFilter = filterState.dueDateFilter != DueDateFilter.Any
        )

        FilterCategoryChip(
            title = "Priority",
            isExpanded = expandedCategory == FilterCategory.Priority,
            onClick = { onCategoryClick(FilterCategory.Priority) },
            hasActiveFilter = filterState.priorityFilter != PriorityFilter.Any
        )

        FilterCategoryChip(
            title = "Est. Time",
            isExpanded = expandedCategory == FilterCategory.EstimatedTime,
            onClick = { onCategoryClick(FilterCategory.EstimatedTime) },
            hasActiveFilter = filterState.estimatedTimeFilter != EstimatedTimeFilter.Any
        )

        FilterCategoryChip(
            title = "Recurrence",
            isExpanded = expandedCategory == FilterCategory.Recurrence,
            onClick = { onCategoryClick(FilterCategory.Recurrence) },
            hasActiveFilter = filterState.recurrenceFilter != RecurrenceFilter.Any
        )

        FilterCategoryChip(
            title = "Notifications",
            isExpanded = expandedCategory == FilterCategory.Notifications,
            onClick = { onCategoryClick(FilterCategory.Notifications) },
            hasActiveFilter = filterState.notificationsFilter != NotificationsFilter.Any
        )

        FilterCategoryChip(
            title = "Connections",
            isExpanded = expandedCategory == FilterCategory.Connections,
            onClick = { onCategoryClick(FilterCategory.Connections) },
            // Ids count as an active filter here too: they narrow the list on their own, and
            // their boxes are only visible once this category is expanded, so an unlit chip was
            // the last place the filter could have been noticed.
            hasActiveFilter = filterState.connectionTypeFilters.isNotEmpty() ||
                    filterState.dependsOnTaskIds.isNotBlank() ||
                    filterState.isDependencyOfTaskIds.isNotBlank() ||
                    filterState.relatesToTaskIds.isNotBlank() ||
                    filterState.subtaskOfTaskIds.isNotBlank() ||
                    filterState.parentOfTaskIds.isNotBlank()
        )

        // Also when the space has no tags left to offer but some are still selected. Deleting a tag
        // from a space does not take it off the tasks that carry it, so the filter stays in force —
        // and hiding the whole category left it working with nothing on screen to switch it off.
        if (allTags.isNotEmpty() || filterState.selectedTags.isNotEmpty()) {
            FilterCategoryChip(
                title = "Tags",
                isExpanded = expandedCategory == FilterCategory.Tags,
                onClick = { onCategoryClick(FilterCategory.Tags) },
                hasActiveFilter = filterState.selectedTags.isNotEmpty()
            )
        }
    }
}

@Composable
private fun FilterOptionsContent(
    filterState: TaskFilterState,
    allTags: Set<String>,
    spaceIdPrefix: String?,
    expandedCategory: FilterCategory?,
    lastExpandedCategory: FilterCategory?
) {
    AnimatedVisibility(
        visible = expandedCategory != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier)
            when (expandedCategory ?: lastExpandedCategory) {
                FilterCategory.SearchIn -> SearchInFilterOptions(filterState)
                FilterCategory.Status -> StatusFilterOptions(filterState, spaceIdPrefix)
                FilterCategory.DueDate -> DueDateFilterOptions(filterState)
                FilterCategory.Priority -> PriorityFilterOptions(filterState)
                FilterCategory.EstimatedTime -> EstimatedTimeFilterOptions(filterState)
                FilterCategory.Recurrence -> RecurrenceFilterOptions(filterState)
                FilterCategory.Notifications -> NotificationsFilterOptions(filterState)
                FilterCategory.Connections -> ConnectionsFilterOptions(filterState, spaceIdPrefix)
                FilterCategory.Tags -> TagsFilterOptions(filterState, allTags)
                null -> {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchInFilterOptions(filterState: TaskFilterState) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(TaskTextSearchField.entries.toList(), key = { it }) { field ->
            FilterChip(
                selected = field in filterState.textSearchFields,
                onClick = {
                    filterState.textSearchFields = if (field in filterState.textSearchFields && filterState.textSearchFields.size > 1) {
                        filterState.textSearchFields.removing(field)
                    } else {
                        filterState.textSearchFields.adding(field)
                    }
                },
                label = { Text(field.displayName, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier
                    .height(28.dp)
                    .animateItem()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusFilterOptions(filterState: TaskFilterState, spaceIdPrefix: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = filterState.statusFilters.isEmpty(),
                onClick = { filterState.statusFilters = persistentSetOf() },
                label = { Text("Any", style = MaterialTheme.typography.labelSmall) }
            )

            allStatusDefaultValues.forEach { status ->
                val isSelected = filterState.statusFilters.any { it::class == status::class }
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        filterState.statusFilters = if (isSelected) {
                            filterState.statusFilters.fold(filterState.statusFilters) { acc, s ->
                                if (s::class == status::class) acc.removing(s) else acc
                            }
                        } else {
                            filterState.statusFilters.adding(status)
                        }
                    },
                    label = { Text(status.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        BlockedStatusFields(filterState, spaceIdPrefix)
        DeclinedStatusFields(filterState)
        AutoUpdateStatusFilterRow(filterState)
    }
}

@Composable
private fun BlockedStatusFields(filterState: TaskFilterState, spaceIdPrefix: String?) {
    val examplePrefix = spaceIdPrefix ?: "TASK"
    AnimatedVisibility(
        visible = filterState.statusFilters.any { it is TaskStatus.Blocked },
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = filterState.blockedByTaskIds,
                onValueChange = { filterState.blockedByTaskIds = it },
                label = { Text("Blocked by (Task IDs)", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("e.g., $examplePrefix-100, $examplePrefix-200", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = filterState.blockedByComment,
                onValueChange = { filterState.blockedByComment = it },
                label = { Text("Blocked comment (search)", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("Search in block comment", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun DeclinedStatusFields(filterState: TaskFilterState) {
    AnimatedVisibility(
        visible = filterState.statusFilters.any { it is TaskStatus.Declined },
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        OutlinedTextField(
            value = filterState.declinedReason,
            onValueChange = { filterState.declinedReason = it },
            label = { Text("Decline reason (search)", style = MaterialTheme.typography.labelSmall) },
            placeholder = { Text("Search in decline reason", style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoUpdateStatusFilterRow(filterState: TaskFilterState) {
    Text("Status update mode:", style = MaterialTheme.typography.labelMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AutoUpdateStatusFilter.entries.forEach { filter ->
            FilterChip(
                selected = filterState.autoUpdateStatusFilter == filter,
                onClick = { filterState.autoUpdateStatusFilter = filter },
                label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDateFilterOptions(filterState: TaskFilterState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DueDateFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filterState.dueDateFilter == filter,
                    onClick = { filterState.dueDateFilter = filter },
                    label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        CustomDueDateRange(filterState)
    }
}

@Composable
private fun CustomDueDateRange(filterState: TaskFilterState) {
    AnimatedVisibility(visible = filterState.dueDateFilter == DueDateFilter.Custom) {
        var showAfterPicker by remember { mutableStateOf(false) }
        var showBeforePicker by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DateRangeButton(
                label = filterState.customDueDateAfter?.let(::formatDueDate) ?: "After...",
                onClear = if (filterState.customDueDateAfter != null) {{ filterState.customDueDateAfter = null }} else null,
                onClick = { showAfterPicker = true },
                modifier = Modifier.weight(1f)
            )

            DateRangeButton(
                label = filterState.customDueDateBefore?.let(::formatDueDate) ?: "Before...",
                onClear = if (filterState.customDueDateBefore != null) {{ filterState.customDueDateBefore = null }} else null,
                onClick = { showBeforePicker = true },
                modifier = Modifier.weight(1f)
            )
        }

        if (showAfterPicker) {
            DatePickerDialog(
                currentDate = filterState.customDueDateAfter,
                onDismiss = { showAfterPicker = false },
                onDateSelected = { date ->
                    filterState.customDueDateAfter = date
                    showAfterPicker = false
                }
            )
        }

        if (showBeforePicker) {
            DatePickerDialog(
                currentDate = filterState.customDueDateBefore,
                onDismiss = { showBeforePicker = false },
                onDateSelected = { date ->
                    filterState.customDueDateBefore = date
                    showBeforePicker = false
                }
            )
        }
    }
}

@Composable
private fun DateRangeButton(
    label: String,
    onClear: (() -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        if (onClear != null) {
            IconButton(onClick = onClear, modifier = Modifier.size(16.dp)) {
                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriorityFilterOptions(filterState: TaskFilterState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PriorityFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filterState.priorityFilter == filter,
                    onClick = { filterState.priorityFilter = filter },
                    label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        AnimatedVisibility(visible = filterState.priorityFilter == PriorityFilter.Custom) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = filterState.customPriorityMin,
                    onValueChange = { filterState.customPriorityMin = it },
                    label = { Text("Min (0-100)", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = filterState.customPriorityMax,
                    onValueChange = { filterState.customPriorityMax = it },
                    label = { Text("Max (0-100)", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EstimatedTimeFilterOptions(filterState: TaskFilterState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EstimatedTimeFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filterState.estimatedTimeFilter == filter,
                    onClick = { filterState.estimatedTimeFilter = filter },
                    label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        AnimatedVisibility(visible = filterState.estimatedTimeFilter == EstimatedTimeFilter.Custom) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = filterState.customEstimatedTimeMin,
                    onValueChange = { filterState.customEstimatedTimeMin = it },
                    label = { Text("Min (e.g., 1h 30m)", style = MaterialTheme.typography.labelSmall) },
                    placeholder = { Text("1h 30m", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    isError = filterState.customEstimatedTimeMin.isNotBlank() && parseCompactTimeToPeriod(filterState.customEstimatedTimeMin) == null
                )
                OutlinedTextField(
                    value = filterState.customEstimatedTimeMax,
                    onValueChange = { filterState.customEstimatedTimeMax = it },
                    label = { Text("Max (e.g., 2d)", style = MaterialTheme.typography.labelSmall) },
                    placeholder = { Text("2d", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    isError = filterState.customEstimatedTimeMax.isNotBlank() && parseCompactTimeToPeriod(filterState.customEstimatedTimeMax) == null
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurrenceFilterOptions(filterState: TaskFilterState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RecurrenceFilter.entries.forEach { filter ->
            FilterChip(
                selected = filterState.recurrenceFilter == filter,
                onClick = { filterState.recurrenceFilter = filter },
                label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsFilterOptions(filterState: TaskFilterState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NotificationsFilter.entries.forEach { filter ->
            FilterChip(
                selected = filterState.notificationsFilter == filter,
                onClick = { filterState.notificationsFilter = filter },
                label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionsFilterOptions(filterState: TaskFilterState, spaceIdPrefix: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ConnectionTypeOption.entries.forEach { typeOption ->
                FilterChip(
                    selected = typeOption in filterState.connectionTypeFilters,
                    onClick = {
                        filterState.connectionTypeFilters = if (typeOption in filterState.connectionTypeFilters) {
                            filterState.connectionTypeFilters.removing(typeOption)
                        } else {
                            filterState.connectionTypeFilters.adding(typeOption)
                        }
                    },
                    label = { Text(typeOption.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        ConnectionTaskIdFields(filterState, spaceIdPrefix)
    }
}

@Composable
private fun ConnectionTaskIdFields(filterState: TaskFilterState, spaceIdPrefix: String?) {
    val examplePrefix = spaceIdPrefix ?: "TASK"

    /**
     * One id field, shown while its connection type is ticked — or while it still holds something.
     *
     * Ids narrow the list on their own, ticked or not. Showing the field only alongside its chip
     * meant that clearing the chip hid text that went on filtering: a short list, nothing on
     * screen accounting for it, and the same state saved into saved filters and restored on the
     * next launch.
     */
    @Composable
    fun IdField(option: ConnectionTypeOption, label: String, value: String, onValueChange: (String) -> Unit) {
        if (option !in filterState.connectionTypeFilters && value.isEmpty()) return
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            placeholder = { Text("e.g., $examplePrefix-100, $examplePrefix-200", style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall
        )
    }

    IdField(
        ConnectionTypeOption.DependsOn, "Depends on (Task IDs)",
        filterState.dependsOnTaskIds,
    ) { filterState.dependsOnTaskIds = it }
    IdField(
        ConnectionTypeOption.IsDependencyOf, "Is dependency of (Task IDs)",
        filterState.isDependencyOfTaskIds,
    ) { filterState.isDependencyOfTaskIds = it }
    IdField(
        ConnectionTypeOption.RelatesTo, "Relates to (Task IDs)",
        filterState.relatesToTaskIds,
    ) { filterState.relatesToTaskIds = it }
    IdField(
        ConnectionTypeOption.SubtaskOf, "Is subtask of (Task IDs)",
        filterState.subtaskOfTaskIds,
    ) { filterState.subtaskOfTaskIds = it }
    IdField(
        ConnectionTypeOption.ParentOf, "Is parent for (Task IDs)",
        filterState.parentOfTaskIds,
    ) { filterState.parentOfTaskIds = it }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsFilterOptions(filterState: TaskFilterState, allTags: Set<String>) {
    var tagSearchQuery by remember { mutableStateOf("") }
    val selectedTags = filterState.selectedTags
    val filteredTags = remember(allTags, tagSearchQuery, selectedTags) {
        val matching =
            if (tagSearchQuery.isBlank()) allTags.sorted()
            else allTags.filter { it.contains(tagSearchQuery, ignoreCase = true) }.sorted()
        // Selected tags lead the row whatever the search says, and even when the space no longer
        // offers them. A tag that has since been taken off every task — or one restored from a
        // saved filter — still filters the list, and with no chip there was nothing left to click
        // to switch it off.
        (selectedTags.sorted() + matching).distinct()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TagMatchModeRow(filterState)
        TagSearchField(tagSearchQuery, onQueryChange = { tagSearchQuery = it })
        TagChipsRow(filteredTags, filterState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagMatchModeRow(filterState: TaskFilterState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Match:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TagMatchMode.entries.forEach { mode ->
            FilterChip(
                selected = filterState.tagMatchMode == mode,
                onClick = { filterState.tagMatchMode = mode },
                label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
private fun TagSearchField(tagSearchQuery: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = tagSearchQuery,
        onValueChange = onQueryChange,
        placeholder = { Text("Search tags...", style = MaterialTheme.typography.bodySmall) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        trailingIcon = {
            if (tagSearchQuery.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(14.dp))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagChipsRow(filteredTags: List<String>, filterState: TaskFilterState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        filteredTags.forEach { tag ->
            FilterChip(
                selected = tag in filterState.selectedTags,
                onClick = {
                    filterState.selectedTags = if (tag in filterState.selectedTags) {
                        filterState.selectedTags.removing(tag)
                    } else {
                        filterState.selectedTags.adding(tag)
                    }
                },
                label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(Icons.Default.LocalOffer, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterCategoryChip(
    title: String,
    isExpanded: Boolean,
    onClick: () -> Unit,
    hasActiveFilter: Boolean,
) {
    FilterChip(
        selected = isExpanded || hasActiveFilter,
        onClick = onClick,
        label = { Text(title, style = MaterialTheme.typography.labelSmall, ) },
        trailingIcon = {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(16.dp)
            )
        },
    )
}
