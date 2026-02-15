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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DatePickerDialog
import com.zhelenskiy.zheduler.zheduler.util.formatDueDate
import com.zhelenskiy.zheduler.zheduler.parseCompactTimeToPeriod
import kotlin.time.ExperimentalTime

// Enum to track which filter category is expanded
private enum class FilterCategory {
    SearchIn, Status, DueDate, Priority, EstimatedTime, Recurrence, Notifications, Connections, Tags
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFilterPanel(
    filterState: TaskFilterState,
    allTags: Set<String>,
    modifier: Modifier = Modifier
) {
    // Track which filter category is expanded (only one at a time, null means none)
    var expandedCategory by remember { mutableStateOf<FilterCategory?>(null) }

    // Keep track of the last non-null category for display during collapse animation
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
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            // First row: horizontally scrollable filter category chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Search in
                FilterCategoryChip(
                    title = "Search in",
                    isExpanded = expandedCategory == FilterCategory.SearchIn,
                    onClick = { expandedCategory = if (expandedCategory == FilterCategory.SearchIn) null else FilterCategory.SearchIn },
                    hasActiveFilter = filterState.textSearchFields != setOf(TaskTextSearchField.Title)
                )

                // Status
                FilterCategoryChip(
                    title = "Status",
                    isExpanded = expandedCategory == FilterCategory.Status,
                    onClick = { expandedCategory = if (expandedCategory == FilterCategory.Status) null else FilterCategory.Status },
                    hasActiveFilter = filterState.statusFilters.isNotEmpty()
                )

                // Due Time
                FilterCategoryChip(
                    title = "Due Time",
                    isExpanded = expandedCategory == FilterCategory.DueDate,
                    onClick = { expandedCategory = if (expandedCategory == FilterCategory.DueDate) null else FilterCategory.DueDate },
                    hasActiveFilter = filterState.dueDateFilter != DueDateFilter.Any
                )

                // Priority
                FilterCategoryChip(
                    title = "Priority",
                    isExpanded = expandedCategory == FilterCategory.Priority,
                    onClick = { expandedCategory = if (expandedCategory == FilterCategory.Priority) null else FilterCategory.Priority },
                    hasActiveFilter = filterState.priorityFilter != PriorityFilter.Any
                )

                // Estimated Time
                FilterCategoryChip(
                    title = "Est. Time",
                    isExpanded = expandedCategory == FilterCategory.EstimatedTime,
                    onClick = { expandedCategory = if (expandedCategory == FilterCategory.EstimatedTime) null else FilterCategory.EstimatedTime },
                    hasActiveFilter = filterState.estimatedTimeFilter != EstimatedTimeFilter.Any
                )

                // Recurrence
                FilterCategoryChip(
                    title = "Recurrence",
                    isExpanded = expandedCategory == FilterCategory.Recurrence,
                    onClick = { expandedCategory = if (expandedCategory == FilterCategory.Recurrence) null else FilterCategory.Recurrence },
                    hasActiveFilter = filterState.recurrenceFilter != RecurrenceFilter.Any
                )

                // Notifications
                FilterCategoryChip(
                    title = "Notifications",
                    isExpanded = expandedCategory == FilterCategory.Notifications,
                    onClick = { expandedCategory = if (expandedCategory == FilterCategory.Notifications) null else FilterCategory.Notifications },
                    hasActiveFilter = filterState.notificationsFilter != NotificationsFilter.Any
                )

                // Connections
                FilterCategoryChip(
                    title = "Connections",
                    isExpanded = expandedCategory == FilterCategory.Connections,
                    onClick = { expandedCategory = if (expandedCategory == FilterCategory.Connections) null else FilterCategory.Connections },
                    hasActiveFilter = filterState.connectionTypeFilters.isNotEmpty()
                )

                // Tags (only show if there are tags)
                if (allTags.isNotEmpty()) {
                    FilterCategoryChip(
                        title = "Tags",
                        isExpanded = expandedCategory == FilterCategory.Tags,
                        onClick = { expandedCategory = if (expandedCategory == FilterCategory.Tags) null else FilterCategory.Tags },
                        hasActiveFilter = filterState.selectedTags.isNotEmpty()
                    )
                }
            }

            // Second row: expanded filter options
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
                        FilterCategory.SearchIn -> {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(TaskTextSearchField.entries.toList(), key = { it }) { field ->
                                    FilterChip(
                                        selected = field in filterState.textSearchFields,
                                        onClick = {
                                            filterState.textSearchFields = if (field in filterState.textSearchFields && filterState.textSearchFields.size > 1) {
                                                filterState.textSearchFields - field
                                            } else {
                                                filterState.textSearchFields + field
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

                        FilterCategory.Status -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = filterState.statusFilters.isEmpty(),
                                        onClick = { filterState.statusFilters = emptySet() },
                                        label = { Text("Any", style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(28.dp)
                                    )

                                    listOf(
                                        TaskStatus.Open,
                                        TaskStatus.InProgress,
                                        TaskStatus.Blocked(emptySet()),
                                        TaskStatus.Done,
                                        TaskStatus.Declined("")
                                    ).forEach { status ->
                                        val isSelected = filterState.statusFilters.any { it::class == status::class }
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                filterState.statusFilters = if (isSelected) {
                                                    filterState.statusFilters.filterNot { it::class == status::class }.toSet()
                                                } else {
                                                    filterState.statusFilters + status
                                                }
                                            },
                                            label = { Text(status.displayName, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }

                                // Complex status properties
                                AnimatedVisibility(
                                    visible = filterState.statusFilters.any { it is TaskStatus.Blocked },
                                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = filterState.blockedByTaskIds,
                                            onValueChange = { filterState.blockedByTaskIds = it },
                                            label = { Text("Blocked by (Task IDs)", style = MaterialTheme.typography.labelSmall) },
                                            placeholder = { Text("e.g., TASK-100, TASK-200", style = MaterialTheme.typography.bodySmall) },
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
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                AnimatedVisibility(
                                    visible = filterState.statusFilters.any { it is TaskStatus.Declined },
                                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
                                ) {
                                    OutlinedTextField(
                                        value = filterState.declinedReason,
                                        onValueChange = { filterState.declinedReason = it },
                                        label = { Text("Decline reason (search)", style = MaterialTheme.typography.labelSmall) },
                                        placeholder = { Text("Search in decline reason", style = MaterialTheme.typography.bodySmall) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall
                                    )
                                }

                                // Auto-update status filter
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
                                            label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }
                            }
                        }

                        FilterCategory.DueDate -> {
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
                                            label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }
                                // Custom date range inputs
                                AnimatedVisibility(visible = filterState.dueDateFilter == DueDateFilter.Custom) {
                                    var showAfterPicker by remember { mutableStateOf(false) }
                                    var showBeforePicker by remember { mutableStateOf(false) }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { showAfterPicker = true },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = filterState.customDueDateAfter?.let { formatDueDate(it) } ?: "After...",
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1
                                            )
                                            if (filterState.customDueDateAfter != null) {
                                                IconButton(
                                                    onClick = { filterState.customDueDateAfter = null },
                                                    modifier = Modifier.size(16.dp)
                                                ) {
                                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(12.dp))
                                                }
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = { showBeforePicker = true },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = filterState.customDueDateBefore?.let { formatDueDate(it) } ?: "Before...",
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1
                                            )
                                            if (filterState.customDueDateBefore != null) {
                                                IconButton(
                                                    onClick = { filterState.customDueDateBefore = null },
                                                    modifier = Modifier.size(16.dp)
                                                ) {
                                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(12.dp))
                                                }
                                            }
                                        }
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
                        }

                        FilterCategory.Priority -> {
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
                                            label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(28.dp)
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

                        FilterCategory.EstimatedTime -> {
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
                                            label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(28.dp)
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

                        FilterCategory.Recurrence -> {
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
                                        label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                        }

                        FilterCategory.Notifications -> {
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
                                        label = { Text(filter.displayName, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                        }

                        FilterCategory.Connections -> {
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
                                                    filterState.connectionTypeFilters - typeOption
                                                } else {
                                                    filterState.connectionTypeFilters + typeOption
                                                }
                                            },
                                            label = { Text(typeOption.displayName, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }
                                if (filterState.connectionTypeFilters.isNotEmpty()) {
                                    if (ConnectionTypeOption.DependsOn in filterState.connectionTypeFilters) {
                                        OutlinedTextField(
                                            value = filterState.dependsOnTaskIds,
                                            onValueChange = { filterState.dependsOnTaskIds = it },
                                            label = { Text("Depends on (Task IDs)", style = MaterialTheme.typography.labelSmall) },
                                            placeholder = { Text("e.g., TASK-100, TASK-200", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    if (ConnectionTypeOption.IsDependencyOf in filterState.connectionTypeFilters) {
                                        OutlinedTextField(
                                            value = filterState.isDependencyOfTaskIds,
                                            onValueChange = { filterState.isDependencyOfTaskIds = it },
                                            label = { Text("Is dependency of (Task IDs)", style = MaterialTheme.typography.labelSmall) },
                                            placeholder = { Text("e.g., TASK-300, TASK-400", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    if (ConnectionTypeOption.RelatesTo in filterState.connectionTypeFilters) {
                                        OutlinedTextField(
                                            value = filterState.relatesToTaskIds,
                                            onValueChange = { filterState.relatesToTaskIds = it },
                                            label = { Text("Relates to (Task IDs)", style = MaterialTheme.typography.labelSmall) },
                                            placeholder = { Text("e.g., TASK-500, TASK-600", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    if (ConnectionTypeOption.SubtaskOf in filterState.connectionTypeFilters) {
                                        OutlinedTextField(
                                            value = filterState.subtaskOfTaskIds,
                                            onValueChange = { filterState.subtaskOfTaskIds = it },
                                            label = { Text("Is subtask of (Task IDs)", style = MaterialTheme.typography.labelSmall) },
                                            placeholder = { Text("e.g., TASK-700, TASK-800", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    if (ConnectionTypeOption.ParentOf in filterState.connectionTypeFilters) {
                                        OutlinedTextField(
                                            value = filterState.parentOfTaskIds,
                                            onValueChange = { filterState.parentOfTaskIds = it },
                                            label = { Text("Is parent for (Task IDs)", style = MaterialTheme.typography.labelSmall) },
                                            placeholder = { Text("e.g., TASK-900, TASK-1000", style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }

                        FilterCategory.Tags -> {
                            var tagSearchQuery by remember { mutableStateOf("") }
                            val filteredTags = remember(allTags, tagSearchQuery) {
                                if (tagSearchQuery.isBlank()) allTags.sorted()
                                else allTags.filter { it.contains(tagSearchQuery, ignoreCase = true) }.sorted()
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                            label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = tagSearchQuery,
                                    onValueChange = { tagSearchQuery = it },
                                    placeholder = { Text("Search tags...", style = MaterialTheme.typography.bodySmall) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        if (tagSearchQuery.isNotEmpty()) {
                                            IconButton(onClick = { tagSearchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                                Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    filteredTags.forEach { tag ->
                                        FilterChip(
                                            selected = tag in filterState.selectedTags,
                                            onClick = {
                                                filterState.selectedTags = if (tag in filterState.selectedTags) {
                                                    filterState.selectedTags - tag
                                                } else {
                                                    filterState.selectedTags + tag
                                                }
                                            },
                                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.LocalOffer,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }
                            }
                        }

                        null -> {}
                    }
                }
            }
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
    modifier: Modifier = Modifier
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
