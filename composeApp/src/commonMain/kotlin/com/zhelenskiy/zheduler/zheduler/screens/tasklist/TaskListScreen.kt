@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.zhelenskiy.zheduler.zheduler.components.common.ScreenState
import com.zhelenskiy.zheduler.zheduler.components.common.shouldAnimate
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DeleteConfirmationDialog
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListViewModel
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListViewMode
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
    val viewMode: TaskListViewMode,
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

    // Load view mode from repository
    LaunchedEffect(spaceId) {
        if (spaceId != null) {
            val viewMode = when (repository.getViewMode(spaceId)) {
                "Chronological" -> TaskListViewMode.Chronological
                else -> TaskListViewMode.Priority
            }
            val isFilterPanelOpen = repository.getFilterPanelOpen(spaceId)
            val data = TaskListUiData(
                viewMode = viewMode,
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

    // Save view mode
    LaunchedEffect(uiState.value) {
        val data = (uiState.value as? ScreenState.InitiallyLoaded)?.data
            ?: (uiState.value as? ScreenState.Ready)?.data
        if (data != null && spaceId != null) {
            repository.saveViewMode(spaceId, data.viewMode.name)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskListTopAppBar(
    spaceName: String?,
    viewMode: TaskListViewMode,
    onViewModeToggle: () -> Unit,
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
            IconButton(onClick = onViewModeToggle) {
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

@Composable
private fun ChronologicalTaskList(
    filteredTasks: List<TaskWithTotals>,
    shouldAnimate: Boolean,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
) {
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
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            items(filteredTasks, key = { it.task.id }) { taskWithTotals ->
                TaskCard(
                    taskWithTotals = taskWithTotals,
                    onClick = { onTaskClick(taskWithTotals.task.id) },
                    onDelete = { onDelete(taskWithTotals) },
                    onCopy = { onCopy(taskWithTotals.task.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun PriorityTaskList(
    filteredTasks: List<TaskWithTotals>,
    tasksWithTotals: List<TaskWithTotals>,
    filterState: TaskFilterState,
    viewModel: TaskListViewModel,
    shouldAnimate: Boolean,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
) {
    var groupedTasks by remember { mutableStateOf(GroupedTasks(emptyList(), emptyList(), emptyList())) }
    val currentFilterCriteria = filterState.toCriteria()

    LaunchedEffect(tasksWithTotals, currentFilterCriteria) {
        groupedTasks = viewModel.getTasksGroupedByResolutionStatus()
    }

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
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            PriorityViewTaskGroup(
                title = "Unresolved",
                tasks = groupedTasks.unresolved,
                onTaskClick = onTaskClick,
                onDelete = onDelete,
                onCopy = onCopy
            )

            PriorityViewTaskGroup(
                title = "Blocked",
                tasks = groupedTasks.blocked,
                onTaskClick = onTaskClick,
                onDelete = onDelete,
                onCopy = onCopy
            )

            PriorityViewTaskGroup(
                title = "Resolved",
                tasks = groupedTasks.resolved,
                onTaskClick = onTaskClick,
                onDelete = onDelete,
                onCopy = onCopy
            )
        }
    }
}

@Composable
private fun TaskListContent(
    viewModel: TaskListViewModel,
    tasksWithTotals: List<TaskWithTotals>,
    filteredTasks: List<TaskWithTotals>,
    filterState: TaskFilterState,
    allTags: Set<String>,
    spaceIdPrefix: String?,
    viewMode: TaskListViewMode,
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

            when (viewMode) {
                TaskListViewMode.Chronological -> ChronologicalTaskList(
                    filteredTasks = filteredTasks,
                    shouldAnimate = shouldAnimate,
                    onTaskClick = onTaskClick,
                    onDelete = onDelete,
                    onCopy = onCopy
                )
                TaskListViewMode.Priority -> PriorityTaskList(
                    filteredTasks = filteredTasks,
                    tasksWithTotals = tasksWithTotals,
                    filterState = filterState,
                    viewModel = viewModel,
                    shouldAnimate = shouldAnimate,
                    onTaskClick = onTaskClick,
                    onDelete = onDelete,
                    onCopy = onCopy
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
                viewMode = uiData?.viewMode ?: TaskListViewMode.Priority,
                onViewModeToggle = {
                    val data = uiData ?: return@TaskListTopAppBar
                    val newViewMode = when (data.viewMode) {
                        TaskListViewMode.Chronological -> TaskListViewMode.Priority
                        TaskListViewMode.Priority -> TaskListViewMode.Chronological
                    }
                    uiState.value = when (currentUiState) {
                        is ScreenState.Loading -> currentUiState
                        is ScreenState.InitiallyLoaded -> ScreenState.InitiallyLoaded(data.copy(viewMode = newViewMode))
                        is ScreenState.Ready -> ScreenState.Ready(data.copy(viewMode = newViewMode))
                    }
                },
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
                    viewModel = viewModel,
                    tasksWithTotals = tasksWithTotals,
                    filteredTasks = uiData.filteredTasks,
                    filterState = filterState,
                    allTags = allTags,
                    spaceIdPrefix = currentSpace?.idPrefix,
                    viewMode = uiData.viewMode,
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

private fun LazyListScope.PriorityViewTaskGroup(
    title: String,
    tasks: List<TaskWithTotals>,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
) {
    if (tasks.isEmpty()) return

    item(key = "header_$title") {
        Text(
            text = "$title (${tasks.size})",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    items(tasks, key = { "${title}_${it.task.id}" }) { taskWithTotals ->
        TaskCard(
            taskWithTotals = taskWithTotals,
            onClick = { onTaskClick(taskWithTotals.task.id) },
            onDelete = { onDelete(taskWithTotals) },
            onCopy = { onCopy(taskWithTotals.task.id) },
            modifier = Modifier.animateItem(),
        )
    }
}
