@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.common.TaskCard
import com.zhelenskiy.zheduler.zheduler.components.common.EmptyState
import com.zhelenskiy.zheduler.zheduler.components.common.EmptySearchResults
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DeleteConfirmationDialog
import com.zhelenskiy.zheduler.zheduler.db.SqlDelightTaskRepository
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListViewModel
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListViewMode
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Creates a TaskFilterState that loads from repository and saves on every change.
 */
@Composable
private fun rememberRepositoryFilterState(
    repository: SqlDelightTaskRepository,
    spaceId: String?
): TaskFilterState {
    val filterState = remember { TaskFilterState() }

    // Load filter state from repository when space changes
    LaunchedEffect(spaceId) {
        if (spaceId != null) {
            val criteria = repository.getFilterState(spaceId)
            filterState.fromCriteria(criteria)
        }
    }

    // Save filter state to repository whenever it changes
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    viewModel: TaskListViewModel,
    repository: SqlDelightTaskRepository,
    refreshTrigger: Int,
    onTaskClick: (String) -> Unit,
    onAddTask: () -> Unit,
    onCopyTask: (String) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToSpaceList: () -> Unit,
    onNavigateToCalendar: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit
) {
    val tasksWithTotals by viewModel.tasksWithTotals.collectAsState()
    val currentSpace by viewModel.currentSpace.collectAsState()
    val spaceLoadAttempted by viewModel.spaceLoadAttempted.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    var taskToDelete by remember { mutableStateOf<TaskWithTotals?>(null) }

    val spaceId = currentSpace?.id

    LaunchedEffect(refreshTrigger) {
        viewModel.loadTasks()
    }

    // Filter state stored in repository per-space
    val filterState = rememberRepositoryFilterState(repository, spaceId)

    // View mode stored in repository per-space
    var viewMode by remember { mutableStateOf(TaskListViewMode.Priority) }

    LaunchedEffect(spaceId) {
        if (spaceId != null) {
            viewMode = when (repository.getViewMode(spaceId)) {
                "Chronological" -> TaskListViewMode.Chronological
                else -> TaskListViewMode.Priority
            }
        }
    }
    LaunchedEffect(viewMode, spaceId) {
        if (spaceId != null) {
            repository.saveViewMode(spaceId, viewMode.name)
        }
    }

    // Filter panel open state stored in repository per-space
    var isFilterPanelOpen by remember { mutableStateOf(false) }
    var isInitialLoad by remember { mutableStateOf(true) }
    var hasLoadedInitialState by remember { mutableStateOf(false) }

    LaunchedEffect(spaceId) {
        if (spaceId != null) {
            isFilterPanelOpen = repository.getFilterPanelOpen(spaceId)
            hasLoadedInitialState = true
        }
    }

    // Delay enabling animations until after first composition
    LaunchedEffect(hasLoadedInitialState) {
        if (hasLoadedInitialState) {
            // Wait for next frame to complete initial rendering without animation
            kotlinx.coroutines.delay(50)
            isInitialLoad = false
        }
    }

    LaunchedEffect(isFilterPanelOpen, spaceId) {
        if (spaceId != null && !isInitialLoad) {
            repository.saveFilterPanelOpen(spaceId, isFilterPanelOpen)
        }
    }

    // Filter tasks using filterState
    var filteredTasks by remember { mutableStateOf<List<TaskWithTotals>>(emptyList()) }

    LaunchedEffect(filterState.toCriteria(), tasksWithTotals) {
        filteredTasks = viewModel.getFilteredTasks(filterState.toCriteria())
    }

    // Only navigate back if load was attempted and space still doesn't exist
    if (spaceLoadAttempted && currentSpace == null) {
        LaunchedEffect(Unit) {
            onNavigateToSpaceList()
        }
        return
    }

    // Show loading while space is being loaded
    if (currentSpace == null) {
        return
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Zheduler")
                        val space = currentSpace
                        if (space != null) {
                            Text(
                                text = space.name,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                },
                actions = {
                    // View mode toggle
                    IconButton(
                        onClick = {
                            viewMode = when (viewMode) {
                                TaskListViewMode.Chronological -> TaskListViewMode.Priority
                                TaskListViewMode.Priority -> TaskListViewMode.Chronological
                            }
                        }
                    ) {
                        Icon(
                            imageVector = when (viewMode) {
                                TaskListViewMode.Chronological -> Icons.Default.Schedule
                                TaskListViewMode.Priority -> Icons.Default.ViewAgenda
                            },
                            contentDescription = when (viewMode) {
                                TaskListViewMode.Chronological -> "Switch to Priority view"
                                TaskListViewMode.Priority -> "Switch to Chronological view"
                            }
                        )
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
                colors = appTopAppBarColors()
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTask) {
                Icon(Icons.Default.Add, contentDescription = "Add Task")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar visible when tasks exist
            if (tasksWithTotals.isNotEmpty()) {
                TaskSearchBar(
                    filterState = filterState,
                    isFilterPanelOpen = isFilterPanelOpen,
                    onToggleFilterPanel = { isFilterPanelOpen = !isFilterPanelOpen },
                    isInitialLoad = isInitialLoad,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 8.dp)
                )

                // Expandable filter panel
                AnimatedVisibility(
                    visible = isFilterPanelOpen,
                    enter = if (isInitialLoad) EnterTransition.None else expandVertically(),
                    exit = if (isInitialLoad) ExitTransition.None else shrinkVertically()
                ) {
                    TaskFilterPanel(
                        filterState = filterState,
                        allTags = allTags,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = tasksWithTotals.isEmpty(),
                    enter = if (isInitialLoad) EnterTransition.None else androidx.compose.animation.fadeIn(),
                    exit = if (isInitialLoad) ExitTransition.None else androidx.compose.animation.fadeOut()
                ) {
                    EmptyState(message = "No tasks yet. Tap + to add one!")
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = tasksWithTotals.isNotEmpty() && filteredTasks.isEmpty(),
                    enter = if (isInitialLoad) EnterTransition.None else androidx.compose.animation.fadeIn(),
                    exit = if (isInitialLoad) ExitTransition.None else androidx.compose.animation.fadeOut()
                ) {
                    EmptySearchResults(
                        message = "No tasks match your filters",
                        clearButtonText = "Clear all filters",
                        onClearFilters = { filterState.clearAll() }
                    )
                }

                when (viewMode) {
                    TaskListViewMode.Chronological -> {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = filteredTasks.isNotEmpty(),
                            enter = if (isInitialLoad) EnterTransition.None else androidx.compose.animation.fadeIn(),
                            exit = if (isInitialLoad) ExitTransition.None else androidx.compose.animation.fadeOut()
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 16.dp)
                            ) {
                                items(filteredTasks, key = { it.task.id }) { taskWithTotals ->
                                    TaskCard(
                                        taskWithTotals = taskWithTotals,
                                        onClick = { onTaskClick(taskWithTotals.task.id) },
                                        onStatusChange = { newStatus ->
                                            viewModel.updateTaskStatus(taskWithTotals.task.id, newStatus)
                                            onRefresh()
                                        },
                                        onDelete = {
                                            taskToDelete = taskWithTotals
                                        },
                                        onCopy = {
                                            onCopyTask(taskWithTotals.task.id)
                                        },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }
                    }
                    TaskListViewMode.Priority -> {
                        // Group tasks by resolution status using ViewModel
                        var groupedTasks by remember { mutableStateOf(GroupedTasks(emptyList(), emptyList(), emptyList())) }

                        // Snapshot current filter criteria to trigger re-grouping when filters change
                        val currentFilterCriteria = filterState.toCriteria()

                        LaunchedEffect(tasksWithTotals, currentFilterCriteria) {
                            groupedTasks = viewModel.getTasksGroupedByResolutionStatus()
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = filteredTasks.isNotEmpty(),
                            enter = if (isInitialLoad) EnterTransition.None else androidx.compose.animation.fadeIn(),
                            exit = if (isInitialLoad) ExitTransition.None else androidx.compose.animation.fadeOut()
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 16.dp)
                            ) {
                                priorityViewTaskGroup(
                                    title = "Unresolved",
                                    tasks = groupedTasks.unresolved,
                                    onTaskClick = onTaskClick,
                                    onStatusChange = { taskId, newStatus ->
                                        viewModel.updateTaskStatus(taskId, newStatus)
                                        onRefresh()
                                    },
                                    onDelete = { taskToDelete = it },
                                    onCopy = onCopyTask
                                )

                                priorityViewTaskGroup(
                                    title = "Blocked",
                                    tasks = groupedTasks.blocked,
                                    onTaskClick = onTaskClick,
                                    onStatusChange = { taskId, newStatus ->
                                        viewModel.updateTaskStatus(taskId, newStatus)
                                        onRefresh()
                                    },
                                    onDelete = { taskToDelete = it },
                                    onCopy = onCopyTask
                                )

                                priorityViewTaskGroup(
                                    title = "Resolved",
                                    tasks = groupedTasks.resolved,
                                    onTaskClick = onTaskClick,
                                    onStatusChange = { taskId, newStatus ->
                                        viewModel.updateTaskStatus(taskId, newStatus)
                                        onRefresh()
                                    },
                                    onDelete = { taskToDelete = it },
                                    onCopy = onCopyTask
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
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

private fun LazyListScope.priorityViewTaskGroup(
    title: String,
    tasks: List<TaskWithTotals>,
    onTaskClick: (String) -> Unit,
    onStatusChange: (String, TaskStatus) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit
) {
    if (tasks.isEmpty()) return

    item(key = "header_$title") {
        Text(
            text = "$title (${tasks.size})",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    items(tasks, key = { "${title}_${it.task.id}" }) { taskWithTotals ->
        TaskCard(
            taskWithTotals = taskWithTotals,
            onClick = { onTaskClick(taskWithTotals.task.id) },
            onStatusChange = { newStatus ->
                onStatusChange(taskWithTotals.task.id, newStatus)
            },
            onDelete = { onDelete(taskWithTotals) },
            onCopy = { onCopy(taskWithTotals.task.id) },
            modifier = Modifier.animateItem()
        )
    }
}
