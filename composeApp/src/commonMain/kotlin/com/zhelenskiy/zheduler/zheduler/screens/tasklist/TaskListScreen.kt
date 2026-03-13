@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.common.TaskCard
import com.zhelenskiy.zheduler.zheduler.components.common.EmptyState
import com.zhelenskiy.zheduler.zheduler.components.common.EmptySearchResults
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.common.ScreenState
import com.zhelenskiy.zheduler.zheduler.components.common.shouldAnimate
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DeleteConfirmationDialog
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListViewModel
import kotlin.time.ExperimentalTime

@Composable
private fun rememberRepositoryFilterState(
    repository: TaskRepository,
    spaceId: String?
): TaskFilterState {
    val filterState = rememberTaskFilterState()

    LaunchedEffect(spaceId) {
        if (spaceId != null) {
            val criteria = repository.getFilterState(spaceId)
            filterState.loadFromCriteria(criteria)
        }
    }

    LaunchedEffect(
        filterState.searchQuery, filterState.textSearchFields, filterState.statusFilters,
        filterState.dueDateFilter, filterState.priorityFilter, filterState.estimatedTimeFilter,
        filterState.recurrenceFilter, filterState.notificationsFilter, filterState.autoUpdateStatusFilter,
        filterState.connectionTypeFilters, filterState.selectedTags, filterState.tagMatchMode,
        filterState.customPriorityMin, filterState.customPriorityMax, filterState.customDueDateBefore,
        filterState.customDueDateAfter, filterState.customEstimatedTimeMin, filterState.customEstimatedTimeMax,
        filterState.dependsOnTaskIds, filterState.isDependencyOfTaskIds, filterState.relatesToTaskIds,
        filterState.subtaskOfTaskIds, filterState.parentOfTaskIds,
        filterState.blockedByTaskIds, filterState.blockedByComment, filterState.declinedReason
    ) {
        if (spaceId != null) {
            repository.saveFilterState(spaceId, filterState.toCriteria())
        }
    }

    return filterState
}

private data class TaskListUiData(
    val activeViewMode: ViewMode,
    val viewModes: List<ViewMode>,
    val isFilterPanelOpen: Boolean,
    val filteredTasks: List<TaskWithTotals>
)

@Composable
private fun rememberTaskListUiState(
    repository: TaskRepository,
    spaceId: String?,
    viewModel: TaskListViewModel,
    filterState: TaskFilterState,
    tasksWithTotals: List<TaskWithTotals>
): MutableState<ScreenState<TaskListUiData>> {
    val uiState = remember { mutableStateOf<ScreenState<TaskListUiData>>(ScreenState.Loading) }

    // Load view modes from repository
    LaunchedEffect(spaceId) {
        if (spaceId != null) {
            val viewModes = repository.getAllViewModes(spaceId)
            val activeViewMode = repository.getActiveViewMode(spaceId)
            val isFilterPanelOpen = repository.getFilterPanelOpen(spaceId)
            val data = TaskListUiData(
                activeViewMode = activeViewMode,
                viewModes = viewModes,
                isFilterPanelOpen = isFilterPanelOpen,
                filteredTasks = emptyList()
            )
            uiState.value = ScreenState.InitiallyLoaded(data)
        }
    }

    // Enable animations after initial load
    LaunchedEffect(Unit) {
        snapshotFlow { uiState.value }
            .collect { state ->
                if (state is ScreenState.InitiallyLoaded) {
                    uiState.value = ScreenState.Ready(state.data)
                }
            }
    }

    // Save active view mode
    LaunchedEffect(uiState.value) {
        val data = (uiState.value as? ScreenState.InitiallyLoaded)?.data
            ?: (uiState.value as? ScreenState.Ready)?.data
        if (data != null && spaceId != null) {
            repository.setActiveViewMode(spaceId, data.activeViewMode.id)
        }
    }

    // Save filter panel state
    LaunchedEffect(uiState.value) {
        val state = uiState.value
        if (state is ScreenState.Ready && spaceId != null) {
            repository.saveFilterPanelOpen(spaceId, state.data.isFilterPanelOpen)
        }
    }

    // Filter tasks
    LaunchedEffect(filterState.toCriteria(), tasksWithTotals) {
        val filtered = viewModel.getFilteredTasks(filterState.toCriteria())
        when (val state = uiState.value) {
            is ScreenState.Loading -> {}
            is ScreenState.InitiallyLoaded -> uiState.value = ScreenState.InitiallyLoaded(state.data.copy(filteredTasks = filtered))
            is ScreenState.Ready -> uiState.value = ScreenState.Ready(state.data.copy(filteredTasks = filtered))
        }
    }

    return uiState
}

@Composable
private fun TaskListTopAppBar(
    spaceName: String?,
    viewModeName: String,
    onViewModeClick: () -> Unit,
    onNavigateToCalendar: () -> Unit,
    onNavigateToSpaceList: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text("Zheduler")
                if (spaceName != null) {
                    Text(text = spaceName, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        actions = {
            TextButton(onClick = onViewModeClick) {
                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(viewModeName, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onNavigateToCalendar) {
                Icon(Icons.Default.CalendarMonth, contentDescription = "Calendar")
            }
            IconButton(onClick = onNavigateToSpaceList) {
                Icon(Icons.Default.Home, contentDescription = "Spaces")
            }
            ThemeMenuButton(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onDynamicColorsChange
            )
        },
        colors = appTopAppBarColors(),
    )
}

@Composable
private fun TaskListSearchAndFilter(
    filterState: TaskFilterState,
    allTags: Set<String>,
    spaceIdPrefix: String?,
    isFilterPanelOpen: Boolean,
    onToggleFilterPanel: () -> Unit,
    shouldAnimate: Boolean,
) {
    TaskSearchBar(
        filterState = filterState,
        isFilterPanelOpen = isFilterPanelOpen,
        onToggleFilterPanel = onToggleFilterPanel,
        shouldAnimate = shouldAnimate,
        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 8.dp)
    )

    AnimatedVisibility(
        visible = isFilterPanelOpen,
        enter = if (shouldAnimate) expandVertically() else EnterTransition.None,
        exit = if (shouldAnimate) shrinkVertically() else ExitTransition.None
    ) {
        TaskFilterPanel(
            filterState = filterState,
            allTags = allTags,
            spaceIdPrefix = spaceIdPrefix,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun TaskListEmptyStates(
    tasksWithTotals: List<TaskWithTotals>,
    filteredTasks: List<TaskWithTotals>,
    shouldAnimate: Boolean,
    onClearFilters: () -> Unit,
) {
    AnimatedVisibility(
        visible = tasksWithTotals.isEmpty(),
        enter = if (shouldAnimate) fadeIn() else EnterTransition.None,
        exit = if (shouldAnimate) fadeOut() else ExitTransition.None
    ) {
        EmptyState(message = "No tasks yet. Tap + to add one!")
    }

    AnimatedVisibility(
        visible = tasksWithTotals.isNotEmpty() && filteredTasks.isEmpty(),
        enter = if (shouldAnimate) fadeIn() else EnterTransition.None,
        exit = if (shouldAnimate) fadeOut() else ExitTransition.None
    ) {
        EmptySearchResults(
            message = "No tasks match your filters",
            clearButtonText = "Clear all filters",
            onClearFilters = onClearFilters
        )
    }
}

/**
 * Displays tasks according to the view mode's grouping and ordering configuration.
 */
@Composable
private fun DynamicTaskList(
    viewMode: ViewMode,
    filteredTasks: List<TaskWithTotals>,
    shouldAnimate: Boolean,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
) {
    val taskGroups = remember(viewMode, filteredTasks) {
        viewMode.applyTo(filteredTasks)
    }

    // Track collapsed state for each group by its key (survives configuration changes)
    var collapsedGroupsSet by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // Track expanded state for uncategorized groups (collapsed by default)
    var expandedUncategorizedSet by rememberSaveable { mutableStateOf(emptySet<String>()) }

    AnimatedVisibility(
        visible = filteredTasks.isNotEmpty(),
        enter = if (shouldAnimate) fadeIn() else EnterTransition.None,
        exit = if (shouldAnimate) fadeOut() else ExitTransition.None,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
        ) {
            TaskGroupItems(
                groups = taskGroups,
                collapsedGroups = collapsedGroupsSet,
                expandedUncategorized = expandedUncategorizedSet,
                onToggleCollapse = { key ->
                    collapsedGroupsSet = if (key in collapsedGroupsSet) {
                        collapsedGroupsSet - key
                    } else {
                        collapsedGroupsSet + key
                    }
                },
                onToggleUncategorized = { key ->
                    expandedUncategorizedSet = if (key in expandedUncategorizedSet) {
                        expandedUncategorizedSet - key
                    } else {
                        expandedUncategorizedSet + key
                    }
                },
                onTaskClick = onTaskClick,
                onDelete = onDelete,
                onCopy = onCopy,
                parentKey = ""
            )
        }
    }
}

/**
 * Recursively adds task group items to the LazyListScope.
 */
private fun LazyListScope.TaskGroupItems(
    groups: List<TaskGroup>,
    collapsedGroups: Set<String>,
    expandedUncategorized: Set<String>,
    onToggleCollapse: (String) -> Unit,
    onToggleUncategorized: (String) -> Unit,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
    parentKey: String
) {
    for (group in groups) {
        val displayLabel = if (group.isUncategorized) "Uncategorized" else group.label
        val groupKey = if (parentKey.isEmpty()) displayLabel else "${parentKey}_$displayLabel"
        val isCollapsed = if (group.isUncategorized) {
            groupKey !in expandedUncategorized // Uncategorized collapsed by default
        } else {
            groupKey in collapsedGroups
        }

        val showHeader = group.label.isNotEmpty() || group.isUncategorized

        // Add group header
        if (showHeader) {
            item(key = "header_$groupKey") {
                val rotationAngle by animateFloatAsState(
                    targetValue = if (isCollapsed) 0f else 90f,
                    label = "collapse_rotation"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .padding(
                            top = if (group.level == 0) 2.dp else 0.dp,
                            bottom = if (group.level == 0) 2.dp else 0.dp,
                            start = (group.level * 16).dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (group.isUncategorized) {
                                onToggleUncategorized(groupKey)
                            } else {
                                onToggleCollapse(groupKey)
                            }
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (isCollapsed) "Expand" else "Collapse",
                            tint = if (group.isUncategorized)
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else
                                MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp).rotate(rotationAngle)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = displayLabel,
                        style = if (group.level == 0)
                            MaterialTheme.typography.titleMedium
                        else
                            MaterialTheme.typography.titleSmall,
                        fontStyle = if (group.isUncategorized) FontStyle.Italic else FontStyle.Normal,
                        color = if (group.isUncategorized)
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        else
                            MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Add children only if not collapsed (or if there's no header to collapse)
        if (!isCollapsed || !showHeader) {
            if (group.subgroups.isNotEmpty()) {
                // Recursively add subgroup items
                TaskGroupItems(
                    groups = group.subgroups,
                    collapsedGroups = collapsedGroups,
                    expandedUncategorized = expandedUncategorized,
                    onToggleCollapse = onToggleCollapse,
                    onToggleUncategorized = onToggleUncategorized,
                    onTaskClick = onTaskClick,
                    onDelete = onDelete,
                    onCopy = onCopy,
                    parentKey = groupKey
                )
            } else {
                // Add task items at leaf level
                items(group.tasks, key = { "${groupKey}_${it.task.id}" }) { taskWithTotals ->
                    TaskCard(
                        taskWithTotals = taskWithTotals,
                        onClick = { onTaskClick(taskWithTotals.task.id) },
                        onDelete = { onDelete(taskWithTotals) },
                        onCopy = { onCopy(taskWithTotals.task.id) },
                        modifier = Modifier
                            .animateItem()
                            .padding(start = (group.level * 16).dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskListContent(
    tasksWithTotals: List<TaskWithTotals>,
    filteredTasks: List<TaskWithTotals>,
    filterState: TaskFilterState,
    allTags: Set<String>,
    spaceIdPrefix: String?,
    viewMode: ViewMode,
    isFilterPanelOpen: Boolean,
    onToggleFilterPanel: () -> Unit,
    shouldAnimate: Boolean,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (tasksWithTotals.isNotEmpty()) {
            TaskListSearchAndFilter(
                filterState = filterState,
                allTags = allTags,
                spaceIdPrefix = spaceIdPrefix,
                isFilterPanelOpen = isFilterPanelOpen,
                onToggleFilterPanel = onToggleFilterPanel,
                shouldAnimate = shouldAnimate
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            TaskListEmptyStates(
                tasksWithTotals = tasksWithTotals,
                filteredTasks = filteredTasks,
                shouldAnimate = shouldAnimate,
                onClearFilters = { filterState.clearAll() }
            )

            DynamicTaskList(
                viewMode = viewMode,
                filteredTasks = filteredTasks,
                shouldAnimate = shouldAnimate,
                onTaskClick = onTaskClick,
                onDelete = onDelete,
                onCopy = onCopy
            )
        }
    }
}

@Composable
fun TaskListScreen(
    viewModel: TaskListViewModel,
    repository: TaskRepository,
    refreshTrigger: Int,
    onTaskClick: (String) -> Unit,
    onAddTask: () -> Unit,
    onCopyTask: (String) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToSpaceList: () -> Unit,
    onNavigateToCalendar: () -> Unit,
    onNavigateToViewModeManagement: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
) {
    val tasksWithTotals by viewModel.tasksWithTotals.collectAsState()
    val currentSpace by viewModel.currentSpace.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    var taskToDelete by remember { mutableStateOf<TaskWithTotals?>(null) }

    val spaceId = currentSpace?.id

    LaunchedEffect(refreshTrigger) {
        viewModel.loadTasks()
    }

    val filterState = rememberRepositoryFilterState(repository, spaceId)
    val uiState = rememberTaskListUiState(repository, spaceId, viewModel, filterState, tasksWithTotals)

    val currentUiState = uiState.value
    val uiData = when (currentUiState) {
        is ScreenState.Loading -> null
        is ScreenState.InitiallyLoaded -> currentUiState.data
        is ScreenState.Ready -> currentUiState.data
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TaskListTopAppBar(
                spaceName = currentSpace?.name,
                viewModeName = uiData?.activeViewMode?.name ?: "Loading...",
                onViewModeClick = onNavigateToViewModeManagement,
                onNavigateToCalendar = onNavigateToCalendar,
                onNavigateToSpaceList = onNavigateToSpaceList,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onDynamicColorsChange
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTask) {
                Icon(Icons.Default.Add, contentDescription = "Add Task")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (uiData == null || currentSpace == null) {
                // Show loading state - important for navigation animation
                Box(modifier = Modifier.fillMaxSize())
            } else {
                TaskListContent(
                    tasksWithTotals = tasksWithTotals,
                    filteredTasks = uiData.filteredTasks,
                    filterState = filterState,
                    allTags = allTags,
                    spaceIdPrefix = currentSpace?.idPrefix,
                    viewMode = uiData.activeViewMode,
                    isFilterPanelOpen = uiData.isFilterPanelOpen,
                    onToggleFilterPanel = {
                        uiState.value = when (currentUiState) {
                            is ScreenState.Loading -> currentUiState
                            is ScreenState.InitiallyLoaded -> ScreenState.InitiallyLoaded(uiData.copy(isFilterPanelOpen = !uiData.isFilterPanelOpen))
                            is ScreenState.Ready -> ScreenState.Ready(uiData.copy(isFilterPanelOpen = !uiData.isFilterPanelOpen))
                        }
                    },
                    shouldAnimate = currentUiState.shouldAnimate,
                    onTaskClick = onTaskClick,
                    onDelete = { taskToDelete = it },
                    onCopy = onCopyTask
                )
            }
        }
    }

    taskToDelete?.let { task ->
        DeleteConfirmationDialog(
            title = "Delete Task",
            message = "Are you sure you want to delete \"${task.task.title}\"?",
            onConfirm = {
                viewModel.deleteTask(task.task.id)
                taskToDelete = null
                onRefresh()
            },
            onDismiss = { taskToDelete = null }
        )
    }
}
