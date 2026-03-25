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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ViewAgenda
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.savedfilter.SaveFilterDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.*
import com.zhelenskiy.zheduler.zheduler.ColorSettings
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
import com.zhelenskiy.zheduler.zheduler.components.common.mapData
import com.zhelenskiy.zheduler.zheduler.components.common.dataOrNull
import com.zhelenskiy.zheduler.zheduler.components.common.shouldAnimate
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DeleteConfirmationDialog
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListIntent
import pro.respawn.flowmvi.compose.dsl.subscribe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.time.ExperimentalTime

private data class TaskListUiData(
    val activeViewMode: ViewMode,
    val viewModes: List<ViewMode>,
    val isFilterPanelOpen: Boolean,
    val filteredTasks: List<TaskWithTotals>,
)

@Composable
private fun rememberPersistedFilterState(
    onLoadFilterState: () -> TaskFilterCriteria,
    onSaveFilterState: (TaskFilterCriteria) -> Unit
): TaskFilterState {
    val filterState = rememberTaskFilterState()

    // Load filter state when it becomes available
    val loadedCriteria = onLoadFilterState()
    LaunchedEffect(loadedCriteria) {
        filterState.loadFromCriteria(loadedCriteria)
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
        onSaveFilterState(filterState.toCriteria())
    }

    return filterState
}

@Composable
private fun rememberTaskListUiState(
    hasAnyTasks: Boolean,
    onLoadInitialData: () -> TaskListUiData?,
    onSaveActiveViewMode: (String) -> Unit,
    onSaveFilterPanelOpen: (Boolean) -> Unit,
    onGetFilteredTasks: (TaskFilterCriteria) -> List<TaskWithTotals>,
    filterState: TaskFilterState
): MutableState<ScreenState<TaskListUiData>> {
    val uiState = remember { mutableStateOf<ScreenState<TaskListUiData>>(ScreenState.Loading) }

    // Try to load initial data when state becomes available
    val initialData = onLoadInitialData()
    LaunchedEffect(initialData) {
        if (initialData != null && uiState.value is ScreenState.Loading) {
            uiState.value = ScreenState.InitiallyLoaded(initialData)
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { uiState.value }
            .collect { state ->
                if (state is ScreenState.InitiallyLoaded) {
                    uiState.value = ScreenState.Ready(state.data)
                }
            }
    }

    LaunchedEffect(uiState.value) {
        val data = uiState.value.dataOrNull
        if (data != null) {
            onSaveActiveViewMode(data.activeViewMode.id)
        }
    }

    LaunchedEffect(uiState.value) {
        val state = uiState.value
        if (state is ScreenState.Ready) {
            onSaveFilterPanelOpen(state.data.isFilterPanelOpen)
        }
    }

    LaunchedEffect(filterState.toCriteria(), hasAnyTasks, uiState.value) {
        if (uiState.value.dataOrNull == null) return@LaunchedEffect
        val criteria = filterState.toCriteria()
        val filtered = onGetFilteredTasks(criteria)
        when (val state = uiState.value) {
            is ScreenState.Loading -> {}
            is ScreenState.InitiallyLoaded -> uiState.value = ScreenState.InitiallyLoaded(
                state.data.copy(filteredTasks = filtered)
            )
            is ScreenState.Ready -> uiState.value = ScreenState.Ready(
                state.data.copy(filteredTasks = filtered)
            )
        }
    }

    return uiState
}

@Composable
private fun TaskListTopAppBar(
    spaceName: String?,
    onNavigateToViewModeManagement: () -> Unit,
    onNavigateToSavedFilterManagement: () -> Unit,
    onNavigateToCalendar: () -> Unit,
    onNavigateToSpaceList: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    var settingsMenuExpanded by remember { mutableStateOf(false) }

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
            Box {
                IconButton(onClick = { settingsMenuExpanded = true }) {
                    Icon(Icons.Default.Bookmarks, contentDescription = "Settings menu")
                }
                DropdownMenu(
                    expanded = settingsMenuExpanded,
                    onDismissRequest = { settingsMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("View Modes") },
                        onClick = {
                            settingsMenuExpanded = false
                            onNavigateToViewModeManagement()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.ViewAgenda, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Saved Filters") },
                        onClick = {
                            settingsMenuExpanded = false
                            onNavigateToSavedFilterManagement()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.FilterList, contentDescription = null)
                        }
                    )
                }
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
                onDynamicColorsChange = onDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
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
    onSaveFilter: () -> Unit,
    shouldAnimate: Boolean,
) {
    TaskSearchBar(
        filterState = filterState,
        isFilterPanelOpen = isFilterPanelOpen,
        onToggleFilterPanel = onToggleFilterPanel,
        onSaveFilter = onSaveFilter,
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
    hasAnyTasks: Boolean,
    filteredTasks: List<TaskWithTotals>,
    shouldAnimate: Boolean,
    onClearFilters: () -> Unit,
) {
    AnimatedVisibility(
        visible = !hasAnyTasks,
        enter = if (shouldAnimate) fadeIn() else EnterTransition.None,
        exit = if (shouldAnimate) fadeOut() else ExitTransition.None
    ) {
        EmptyState(message = "No tasks yet. Tap + to add one!")
    }

    AnimatedVisibility(
        visible = hasAnyTasks && filteredTasks.isEmpty(),
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
 * Wrapper to hold loaded group data with its metadata.
 */
private data class LoadedGroupData(
    val groupInfo: TaskGroupInfo,
    val groupKey: String,
    val level: Int,
    val parentFilters: PersistentList<GroupFilter>
)

/**
 * Wrapper to hold loaded tasks with metadata.
 */
private data class LoadedTasksData(
    val tasks: List<TaskWithTotals>,
    val groupKey: String,
    val level: Int
)

/**
 * Displays tasks according to the view mode's grouping and ordering configuration.
 * Groups are loaded lazily when expanded.
 */
@Composable
private fun DynamicTaskList(
    viewMode: ViewMode,
    filterCriteria: TaskFilterCriteria,
    hasAnyFilteredTasks: Boolean,
    shouldAnimate: Boolean,
    onGetTaskGroups: suspend (ViewMode, Int, PersistentList<GroupFilter>, TaskFilterCriteria) -> List<TaskGroupInfo>,
    onGetTasksForGroup: suspend (PersistentList<GroupFilter>, PersistentList<OrderingRule>, TaskFilterCriteria) -> List<TaskWithTotals>,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
) {
    // Track collapsed state for each group by its key (survives configuration changes)
    var collapsedGroupsSet by rememberSaveable { mutableStateOf(persistentSetOf<String>()) }
    // Track expanded state for uncategorized groups (collapsed by default)
    var expandedUncategorizedSet by rememberSaveable { mutableStateOf(persistentSetOf<String>()) }

    // Cache for loaded groups at each level, keyed by parentKey
    var loadedGroups by remember { mutableStateOf<PersistentMap<String, List<LoadedGroupData>>>(persistentMapOf()) }
    // Cache for loaded tasks, keyed by groupKey
    var loadedTasks by remember { mutableStateOf<PersistentMap<String, LoadedTasksData>>(persistentMapOf()) }

    // Helper function to load a group's children
    suspend fun loadGroupChildren(groupData: LoadedGroupData): Pair<String, Any> {
        val newFilters = groupData.groupInfo.filter?.let { groupData.parentFilters.add(it) } ?: groupData.parentFilters
        val nextLevelIndex = groupData.level + 1

        return if (nextLevelIndex < viewMode.groupingLevels.size) {
            val subgroups = onGetTaskGroups(viewMode, nextLevelIndex, newFilters, filterCriteria)
            groupData.groupKey to subgroups.map { groupInfo ->
                val displayLabel = if (groupInfo.isUncategorized) "Uncategorized" else groupInfo.label
                val subgroupKey = "${groupData.groupKey}_$displayLabel"
                LoadedGroupData(
                    groupInfo = groupInfo,
                    groupKey = subgroupKey,
                    level = nextLevelIndex,
                    parentFilters = newFilters
                )
            }
        } else {
            // Leaf level - load tasks
            val tasks = onGetTasksForGroup(newFilters, viewMode.defaultOrderingRules, filterCriteria)
            groupData.groupKey to LoadedTasksData(tasks, groupData.groupKey, nextLevelIndex)
        }
    }

    // Load root level groups and reload all expanded groups when filter changes
    LaunchedEffect(viewMode, filterCriteria) {
        if (viewMode.groupingLevels.isNotEmpty()) {
            // Load root level
            val groups = onGetTaskGroups(viewMode, 0, persistentListOf(), filterCriteria)
            val rootGroups = groups.map { groupInfo ->
                val displayLabel = if (groupInfo.isUncategorized) "Uncategorized" else groupInfo.label
                LoadedGroupData(
                    groupInfo = groupInfo,
                    groupKey = displayLabel,
                    level = 0,
                    parentFilters = persistentListOf()
                )
            }

            // Collect keys that were previously expanded (had loaded children)
            val previouslyExpandedGroupKeys = loadedGroups.keys.filter { it.isNotEmpty() }.toSet()
            val previouslyLoadedTaskKeys = loadedTasks.keys.filter { it.isNotEmpty() }.toSet()

            // Start with root groups and use builders
            val newLoadedGroupsBuilder = persistentMapOf("" to rootGroups).builder()
            val newLoadedTasksBuilder = persistentMapOf<String, LoadedTasksData>().builder()

            // BFS to reload expanded groups in order
            val groupsToProcess = ArrayDeque(rootGroups)
            while (groupsToProcess.isNotEmpty()) {
                val groupData = groupsToProcess.removeFirst()
                val wasExpanded = groupData.groupKey in previouslyExpandedGroupKeys ||
                                  groupData.groupKey in previouslyLoadedTaskKeys

                if (wasExpanded) {
                    val result = loadGroupChildren(groupData)
                    when (val value = result.second) {
                        is List<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val subgroups = value as List<LoadedGroupData>
                            newLoadedGroupsBuilder[result.first] = subgroups
                            groupsToProcess.addAll(subgroups)
                        }
                        is LoadedTasksData -> {
                            newLoadedTasksBuilder[result.first] = value
                        }
                    }
                }
            }

            loadedGroups = newLoadedGroupsBuilder.build()
            loadedTasks = newLoadedTasksBuilder.build()
        } else {
            // No grouping levels - load all tasks directly
            val tasks = onGetTasksForGroup(persistentListOf(), viewMode.defaultOrderingRules, filterCriteria)
            loadedTasks = persistentMapOf("" to LoadedTasksData(tasks, "", 0))
            loadedGroups = persistentMapOf()
        }
    }

    // Function to load subgroups for a specific group (used for on-demand loading when expanding)
    val loadSubgroups: suspend (LoadedGroupData) -> Unit = { groupData ->
        val result = loadGroupChildren(groupData)
        when (val value = result.second) {
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                loadedGroups = loadedGroups.put(result.first, value as List<LoadedGroupData>)
            }
            is LoadedTasksData -> {
                loadedTasks = loadedTasks.put(result.first, value)
            }
        }
    }

    // Only show the list when data is loaded to avoid animation glitches on initial render
    val hasLoadedData = loadedGroups.isNotEmpty() || loadedTasks.isNotEmpty()
    var shouldAnimateInner by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = hasAnyFilteredTasks && hasLoadedData,
        enter = if (shouldAnimate) fadeIn() else EnterTransition.None,
        exit = if (shouldAnimate) fadeOut() else ExitTransition.None,
    ) {
        LaunchedEffect(transition.isRunning) {
            shouldAnimateInner = !transition.isRunning
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
        ) {
            // If no grouping levels, show tasks directly
            if (viewMode.groupingLevels.isEmpty()) {
                val tasksData = loadedTasks[""]
                if (tasksData != null) {
                    items(tasksData.tasks, key = { it.task.id }) { taskWithTotals ->
                        TaskCard(
                            taskWithTotals = taskWithTotals,
                            onClick = { onTaskClick(taskWithTotals.task.id) },
                            onDelete = { onDelete(taskWithTotals) },
                            onCopy = { onCopy(taskWithTotals.task.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            } else {
                // Render groups recursively
                TaskGroupItems(
                    loadedGroups = loadedGroups,
                    loadedTasks = loadedTasks,
                    parentKey = "",
                    collapsedGroups = collapsedGroupsSet,
                    expandedUncategorized = expandedUncategorizedSet,
                    onToggleCollapse = { key ->
                        collapsedGroupsSet = if (key in collapsedGroupsSet) {
                            collapsedGroupsSet.remove(key)
                        } else {
                            collapsedGroupsSet.add(key)
                        }
                    },
                    onToggleUncategorized = { key ->
                        expandedUncategorizedSet = if (key in expandedUncategorizedSet) {
                            expandedUncategorizedSet.remove(key)
                        } else {
                            expandedUncategorizedSet.add(key)
                        }
                    },
                    onRequestLoad = loadSubgroups,
                    viewMode = viewMode,
                    onTaskClick = onTaskClick,
                    onDelete = onDelete,
                    onCopy = onCopy,
                    shouldAnimateInner = shouldAnimateInner,
                )
            }
        }
    }
}

/**
 * Recursively renders task groups with proper LazyColumn item animations.
 */
private fun LazyListScope.TaskGroupItems(
    loadedGroups: Map<String, List<LoadedGroupData>>,
    loadedTasks: Map<String, LoadedTasksData>,
    parentKey: String,
    collapsedGroups: Set<String>,
    expandedUncategorized: Set<String>,
    onToggleCollapse: (String) -> Unit,
    onToggleUncategorized: (String) -> Unit,
    onRequestLoad: suspend (LoadedGroupData) -> Unit,
    viewMode: ViewMode,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
    shouldAnimateInner: Boolean,
) {
    val groups = loadedGroups[parentKey] ?: return

    for (groupData in groups) {
        val groupInfo = groupData.groupInfo
        val groupKey = groupData.groupKey
        val level = groupData.level

        val isCollapsed = if (groupInfo.isUncategorized) {
            groupKey !in expandedUncategorized
        } else {
            groupKey in collapsedGroups
        }

        val showHeader = groupInfo.label.isNotEmpty() || groupInfo.isUncategorized

        // Group header
        if (showHeader) {
            item(key = "header_$groupKey") {
                val rotationAngle by animateFloatAsState(
                    targetValue = if (isCollapsed) 0f else 90f,
                    label = "collapse_rotation"
                )

                // Trigger loading when expanded
                LaunchedEffect(isCollapsed) {
                    if (!isCollapsed) {
                        val hasSubgroups = groupKey in loadedGroups
                        val hasTasks = groupKey in loadedTasks
                        if (!hasSubgroups && !hasTasks) {
                            onRequestLoad(groupData)
                        }
                    }
                }

                val displayLabel = if (groupInfo.isUncategorized) "Uncategorized" else groupInfo.label
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (shouldAnimateInner) Modifier.animateItem() else Modifier)
                        .padding(
                            top = if (level == 0) 2.dp else 0.dp,
                            bottom = if (level == 0) 2.dp else 0.dp,
                            start = (level * 16).dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (groupInfo.isUncategorized) {
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
                            tint = if (groupInfo.isUncategorized)
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else
                                MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp).rotate(rotationAngle)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$displayLabel (${groupInfo.taskCount})",
                        style = if (level == 0)
                            MaterialTheme.typography.titleMedium
                        else
                            MaterialTheme.typography.titleSmall,
                        fontStyle = if (groupInfo.isUncategorized) FontStyle.Italic else FontStyle.Normal,
                        color = if (groupInfo.isUncategorized)
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        else
                            MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Children (only if expanded)
        if (!isCollapsed || !showHeader) {
            val nextLevelIndex = level + 1
            val hasMoreGroupingLevels = nextLevelIndex < viewMode.groupingLevels.size

            if (hasMoreGroupingLevels) {
                // Recurse into subgroups
                TaskGroupItems(
                    loadedGroups = loadedGroups,
                    loadedTasks = loadedTasks,
                    parentKey = groupKey,
                    collapsedGroups = collapsedGroups,
                    expandedUncategorized = expandedUncategorized,
                    onToggleCollapse = onToggleCollapse,
                    onToggleUncategorized = onToggleUncategorized,
                    onRequestLoad = onRequestLoad,
                    viewMode = viewMode,
                    onTaskClick = onTaskClick,
                    onDelete = onDelete,
                    onCopy = onCopy,
                    shouldAnimateInner = shouldAnimateInner,
                )
            } else {
                // Leaf level - render tasks (tasks always animate for smooth expand/collapse)
                val tasksData = loadedTasks[groupKey]
                if (tasksData != null) {
                    items(tasksData.tasks, key = { "${groupKey}_${it.task.id}" }) { taskWithTotals ->
                        TaskCard(
                            taskWithTotals = taskWithTotals,
                            onClick = { onTaskClick(taskWithTotals.task.id) },
                            onDelete = { onDelete(taskWithTotals) },
                            onCopy = { onCopy(taskWithTotals.task.id) },
                            modifier = Modifier
                                .animateItem()
                                .padding(start = (nextLevelIndex * 16).dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskListContent(
    viewMode: ViewMode,
    hasAnyTasks: Boolean,
    filteredTasks: List<TaskWithTotals>,
    filterState: TaskFilterState,
    allTags: Set<String>,
    spaceIdPrefix: String?,
    isFilterPanelOpen: Boolean,
    onToggleFilterPanel: () -> Unit,
    onSaveFilter: () -> Unit,
    shouldAnimate: Boolean,
    onGetTaskGroups: suspend (ViewMode, Int, PersistentList<GroupFilter>, TaskFilterCriteria) -> List<TaskGroupInfo>,
    onGetTasksForGroup: suspend (PersistentList<GroupFilter>, PersistentList<OrderingRule>, TaskFilterCriteria) -> List<TaskWithTotals>,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (hasAnyTasks) {
            TaskListSearchAndFilter(
                filterState = filterState,
                allTags = allTags,
                spaceIdPrefix = spaceIdPrefix,
                isFilterPanelOpen = isFilterPanelOpen,
                onToggleFilterPanel = onToggleFilterPanel,
                onSaveFilter = onSaveFilter,
                shouldAnimate = shouldAnimate
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            TaskListEmptyStates(
                hasAnyTasks = hasAnyTasks,
                filteredTasks = filteredTasks,
                shouldAnimate = shouldAnimate,
                onClearFilters = { filterState.clearAll() }
            )

            DynamicTaskList(
                viewMode = viewMode,
                filterCriteria = filterState.toCriteria(),
                hasAnyFilteredTasks = filteredTasks.isNotEmpty(),
                shouldAnimate = shouldAnimate,
                onGetTaskGroups = onGetTaskGroups,
                onGetTasksForGroup = onGetTasksForGroup,
                onTaskClick = onTaskClick,
                onDelete = onDelete,
                onCopy = onCopy
            )
        }
    }
}

@Composable
fun TaskListScreen(
    container: TaskListContainer,
    refreshTrigger: Int,
    loadedFilterId: String?,
    onFilterLoaded: () -> Unit,
    onTaskClick: (String) -> Unit,
    onAddTask: () -> Unit,
    onCopyTask: (String) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToSpaceList: () -> Unit,
    onNavigateToCalendar: () -> Unit,
    onNavigateToViewModeManagement: () -> Unit,
    onNavigateToSavedFilterManagement: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    val state by container.store.subscribe { }
    val hasAnyTasks = state.hasAnyTasks
    val currentSpace = state.currentSpace
    val allTags = state.allTags ?: emptySet()
    var taskToDelete by remember { mutableStateOf<TaskWithTotals?>(null) }
    var showSaveFilterDialog by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        container.store.intent(TaskListIntent.LoadTasks)
    }

    val filterState = rememberPersistedFilterState(
        onLoadFilterState = { state.filterState ?: TaskFilterCriteria() },
        onSaveFilterState = { criteria ->
            container.store.intent(TaskListIntent.SaveFilterState(criteria))
        }
    )

    val uiState = rememberTaskListUiState(
        hasAnyTasks = hasAnyTasks,
        onLoadInitialData = {
            TaskListUiData(
                activeViewMode = state.activeViewMode ?: return@rememberTaskListUiState null,
                viewModes = state.viewModes.takeIf { it.isNotEmpty() } ?: return@rememberTaskListUiState null,
                isFilterPanelOpen = state.filterPanelOpen ?: return@rememberTaskListUiState null,
                filteredTasks = emptyList(),
            )
        },
        onSaveActiveViewMode = { viewModeId ->
            container.store.intent(TaskListIntent.SetActiveViewMode(viewModeId))
        },
        onSaveFilterPanelOpen = { isOpen ->
            container.store.intent(TaskListIntent.SaveFilterPanelOpen(isOpen))
        },
        onGetFilteredTasks = { criteria ->
            state.filteredTasks[criteria] ?: run {
                container.store.intent(TaskListIntent.GetFilteredTasks(criteria))
                emptyList()
            }
        },
        filterState = filterState
    )

    val currentUiState = uiState.value
    val uiData = currentUiState.dataOrNull

    // Load tags lazily when filter panel opens or save dialog is shown
    val needsTags = (uiData?.isFilterPanelOpen == true) || showSaveFilterDialog
    LaunchedEffect(needsTags) {
        if (needsTags && state.allTags == null) {
            container.store.intent(TaskListIntent.LoadAllTags)
        }
    }

    // Sync filtered tasks from state
    LaunchedEffect(state.filteredTasks, filterState.toCriteria()) {
        val criteria = filterState.toCriteria()
        val filtered = state.filteredTasks[criteria]
        if (filtered != null) {
            uiState.value = uiState.value.mapData { it.copy(filteredTasks = filtered) }
        }
    }

    // Handle loading saved filter - wait for UI state to be ready
    var filterApplied by remember { mutableStateOf(false) }
    LaunchedEffect(loadedFilterId, uiState.value) {
        if (loadedFilterId != null && uiState.value.dataOrNull != null && !filterApplied) {
            filterApplied = true
            applySavedFilter(
                loadedFilterId = loadedFilterId,
                container = container,
                filterState = filterState,
                uiState = uiState
            )
            onFilterLoaded()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TaskListTopAppBar(
                spaceName = currentSpace?.name,
                onNavigateToViewModeManagement = onNavigateToViewModeManagement,
                onNavigateToSavedFilterManagement = onNavigateToSavedFilterManagement,
                onNavigateToCalendar = onNavigateToCalendar,
                onNavigateToSpaceList = onNavigateToSpaceList,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
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
                    viewMode = uiData.activeViewMode,
                    hasAnyTasks = hasAnyTasks,
                    filteredTasks = uiData.filteredTasks,
                    filterState = filterState,
                    allTags = allTags,
                    spaceIdPrefix = currentSpace.idPrefix,
                    isFilterPanelOpen = uiData.isFilterPanelOpen,
                    onToggleFilterPanel = {
                        uiState.value = when (val s = uiState.value) {
                            is ScreenState.Loading -> s
                            is ScreenState.InitiallyLoaded -> ScreenState.InitiallyLoaded(s.data.copy(isFilterPanelOpen = !s.data.isFilterPanelOpen))
                            is ScreenState.Ready -> ScreenState.Ready(s.data.copy(isFilterPanelOpen = !s.data.isFilterPanelOpen))
                        }
                    },
                    onSaveFilter = { showSaveFilterDialog = true },
                    shouldAnimate = currentUiState.shouldAnimate,
                    onGetTaskGroups = container::getTaskGroups,
                    onGetTasksForGroup = container::getTasksForGroup,
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
                container.store.intent(TaskListIntent.DeleteTask(task.task.id))
                taskToDelete = null
                onRefresh()
            },
            onDismiss = { taskToDelete = null }
        )
    }

    // Save filter dialog
    val space = currentSpace
    if (showSaveFilterDialog && uiData != null && space != null) {
        SaveFilterDialog(
            criteria = filterState.toCriteria(),
            viewModes = uiData.viewModes,
            currentActiveViewModeId = uiData.activeViewMode.id,
            spaceId = space.id,
            allTags = allTags,
            spaceIdPrefix = space.idPrefix,
            generateId = container::generateId,
            onSave = { filter ->
                container.store.intent(TaskListIntent.SaveSavedFilter(filter))
                showSaveFilterDialog = false
            },
            onDismiss = { showSaveFilterDialog = false }
        )
    }
}

private suspend fun applySavedFilter(
    loadedFilterId: String,
    container: TaskListContainer,
    filterState: TaskFilterState,
    uiState: MutableState<ScreenState<TaskListUiData>>
) {
    val savedFilter = container.getSavedFilterById(loadedFilterId) ?: return
    filterState.loadFromCriteria(savedFilter.criteria)

    val attachedViewModeId = savedFilter.viewModeId ?: return
    val viewMode = container.getViewModeById(attachedViewModeId) ?: return
    uiState.value = uiState.value.mapData { it.copy(activeViewMode = viewMode) }
    container.store.intent(TaskListIntent.SetActiveViewMode(viewMode.id))
}
