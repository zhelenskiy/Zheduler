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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.common.TaskCard
import com.zhelenskiy.zheduler.zheduler.components.common.EmptyState
import com.zhelenskiy.zheduler.zheduler.components.common.EmptySearchResults
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.common.ScreenState
import com.zhelenskiy.zheduler.zheduler.components.common.mapData
import com.zhelenskiy.zheduler.zheduler.components.common.pagingLoadStatus
import com.zhelenskiy.zheduler.zheduler.components.common.dataOrNull
import com.zhelenskiy.zheduler.zheduler.components.common.shouldAnimate
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DeleteConfirmationDialog
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.reportingFailure
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskListIntent
import kotlinx.coroutines.flow.Flow
import pro.respawn.flowmvi.compose.dsl.subscribe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlin.time.ExperimentalTime

private data class TaskListUiData(
    val activeViewMode: ViewMode,
    val viewModes: List<ViewMode>,
    val isFilterPanelOpen: Boolean,
)

/**
 * The filter panel, restored from storage and saved back as it is edited.
 *
 * The stored criteria arrive after the screen is composed, so the panel starts on its defaults.
 * Two rules keep that from destroying what was stored:
 *
 * Nothing is saved until the stored criteria have been applied — otherwise the very first
 * composition writes the defaults over them, and a space's filter is lost by opening it.
 *
 * They are applied once. Every save comes back through the store as a new value here, and
 * re-applying that echo puts a stale snapshot on top of what has been typed since: the search
 * field would jump back a few characters mid-word, and the revert would itself be saved.
 */
@Composable
internal fun rememberPersistedFilterState(
    onLoadFilterState: () -> TaskFilterCriteria?,
    onSaveFilterState: (TaskFilterCriteria) -> Unit
): TaskFilterState {
    val filterState = rememberTaskFilterState()
    var restored by remember { mutableStateOf(false) }

    val storedCriteria = onLoadFilterState()
    LaunchedEffect(storedCriteria) {
        if (storedCriteria != null && !restored) {
            filterState.loadFromCriteria(storedCriteria)
            restored = true
        }
    }

    LaunchedEffect(
        restored,
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
        if (restored) onSaveFilterState(filterState.toCriteria())
    }

    return filterState
}

@Composable
private fun rememberTaskListUiState(
    onLoadInitialData: () -> TaskListUiData?,
    onSaveActiveViewMode: (String) -> Unit,
    onSaveFilterPanelOpen: (Boolean) -> Unit,
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
    matchingTaskCount: Int?,
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
        visible = hasAnyTasks && matchingTaskCount == 0,
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
    /**
     * Position of this group within the tree, as a dotted path — "0", "0.2", "0.2.1".
     *
     * Identity has to come from the shape of the tree rather than from labels. Two groups may
     * carry the same label, and one may be called "Uncategorized" like the automatic bucket: the
     * key is a LazyColumn item key, so a collision is a duplicate-key crash rather than a muddle.
     * Labels containing the separator used to alias across levels as well.
     */
    val groupKey: String,
    val level: Int,
    val parentFilters: PersistentList<GroupFilter>
)

/**
 * An expanded leaf group: everything needed to page through its tasks.
 * The tasks themselves are not held here — they arrive through [LazyPagingItems].
 */
private data class OpenLeafGroup(
    val groupKey: String,
    val level: Int,
    val filters: PersistentList<GroupFilter>,
    /** The group's own ordering rules. Empty means the view mode's defaults apply. */
    val orderingRules: PersistentList<OrderingRule> = persistentListOf(),
)

/**
 * A [PersistentSet] is not one of the types a platform state registry can persist on its own (on
 * Android it has to fit in a Bundle), so [rememberSaveable] needs to be told how to flatten it.
 */
private val persistentStringSetSaver = listSaver<PersistentSet<String>, String>(
    save = { it.toList() },
    restore = { it.toPersistentSet() },
)

/**
 * Displays tasks according to the view mode's grouping and ordering configuration.
 *
 * Groups are loaded lazily when expanded, and each expanded leaf group pages its own tasks: the
 * group tree holds counts and filters only, never task lists.
 */
@Composable
internal fun DynamicTaskList(
    viewMode: ViewMode,
    filterCriteria: TaskFilterCriteria,
    dataVersion: Long,
    shouldAnimate: Boolean,
    onGetTaskGroups: suspend (ViewMode, Int, PersistentList<GroupFilter>, TaskFilterCriteria) -> List<TaskGroupInfo>,
    onCountTasks: suspend (PersistentList<GroupFilter>, TaskFilterCriteria) -> Int,
    onGetTaskPages: (PersistentList<GroupFilter>, PersistentList<OrderingRule>, TaskFilterCriteria) -> Flow<PagingData<TaskWithTotals>>,
    onMatchingCountChange: (Int) -> Unit,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
) {
    // Track collapsed state for each group by its key (survives configuration changes)
    // Keyed on the view mode: a group is identified by where it sits in the tree, so the same key
    // means an entirely different group once the grouping changes. Carrying these across a switch
    // collapsed whichever group happened to occupy the position the user had closed.
    var collapsedGroupsSet by rememberSaveable(viewMode.id, stateSaver = persistentStringSetSaver) {
        mutableStateOf(persistentSetOf<String>())
    }
    // Track expanded state for uncategorized groups (collapsed by default)
    var expandedUncategorizedSet by rememberSaveable(viewMode.id, stateSaver = persistentStringSetSaver) {
        mutableStateOf(persistentSetOf<String>())
    }

    // Cache for loaded groups at each level, keyed by parentKey
    var loadedGroups by remember { mutableStateOf<PersistentMap<String, List<LoadedGroupData>>>(persistentMapOf()) }
    // Leaf groups whose tasks are currently on screen, keyed by groupKey
    var openLeaves by remember { mutableStateOf<PersistentMap<String, OpenLeafGroup>>(persistentMapOf()) }

    // Helper function to load a group's children
    suspend fun loadGroupChildren(groupData: LoadedGroupData): Pair<String, Any> {
        val newFilters = groupData.groupInfo.filter?.let { groupData.parentFilters.adding(it) } ?: groupData.parentFilters
        val nextLevelIndex = groupData.level + 1

        return if (nextLevelIndex < viewMode.groupingLevels.size) {
            val subgroups = onGetTaskGroups(viewMode, nextLevelIndex, newFilters, filterCriteria)
            groupData.groupKey to subgroups.mapIndexed { position, groupInfo ->
                LoadedGroupData(
                    groupInfo = groupInfo,
                    groupKey = "${groupData.groupKey}.$position",
                    level = nextLevelIndex,
                    parentFilters = newFilters
                )
            }
        } else {
            // Leaf level - its tasks are paged in, so only the filters that select them are kept
            groupData.groupKey to OpenLeafGroup(
                groupData.groupKey,
                nextLevelIndex,
                newFilters,
                // Uncategorized groups have no definition, and so no rules of their own.
                groupData.groupInfo.groupDefinition?.orderingRules ?: persistentListOf(),
            )
        }
    }

    // Load root level groups and reload all expanded groups when the filter, the view mode or the
    // stored data changes
    LaunchedEffect(viewMode, filterCriteria, dataVersion) {
        if (viewMode.groupingLevels.isNotEmpty()) {
            // Load root level
            val groups = onGetTaskGroups(viewMode, 0, persistentListOf(), filterCriteria)
            onMatchingCountChange(groups.sumOf { it.taskCount })
            val rootGroups = groups.mapIndexed { position, groupInfo ->
                LoadedGroupData(
                    groupInfo = groupInfo,
                    groupKey = position.toString(),
                    level = 0,
                    parentFilters = persistentListOf()
                )
            }

            // Which groups are open, read as the walk reaches each one rather than snapshotted
            // before it starts. The walk suspends on a query per group, and a group the user
            // expands during that is otherwise missed: it would be left looking expanded with
            // nothing beneath it until collapsed and opened again.
            fun isExpanded(key: String) = key in loadedGroups || key in openLeaves

            // Start with root groups and use builders
            val newLoadedGroupsBuilder = persistentMapOf("" to rootGroups).builder()
            val newOpenLeavesBuilder = persistentMapOf<String, OpenLeafGroup>().builder()

            // BFS to reload expanded groups in order
            val groupsToProcess = ArrayDeque(rootGroups)
            while (groupsToProcess.isNotEmpty()) {
                val groupData = groupsToProcess.removeFirst()
                val wasExpanded = isExpanded(groupData.groupKey)

                if (wasExpanded) {
                    val result = loadGroupChildren(groupData)
                    when (val value = result.second) {
                        is List<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val subgroups = value as List<LoadedGroupData>
                            newLoadedGroupsBuilder[result.first] = subgroups
                            groupsToProcess.addAll(subgroups)
                        }
                        is OpenLeafGroup -> {
                            newOpenLeavesBuilder[result.first] = value
                        }
                    }
                }
            }

            loadedGroups = newLoadedGroupsBuilder.build()
            openLeaves = newOpenLeavesBuilder.build()
        } else {
            // No grouping levels - every matching task is shown in one paged list
            onMatchingCountChange(onCountTasks(persistentListOf(), filterCriteria))
            openLeaves = persistentMapOf("" to OpenLeafGroup("", 0, persistentListOf()))
            loadedGroups = persistentMapOf()
        }
    }

    // Function to load subgroups for a specific group (used for on-demand loading when expanding)
    val loadSubgroups: suspend (LoadedGroupData) -> Unit = { groupData ->
        val result = loadGroupChildren(groupData)
        when (val value = result.second) {
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                loadedGroups = loadedGroups.putting(result.first, value as List<LoadedGroupData>)
            }
            is OpenLeafGroup -> {
                openLeaves = openLeaves.putting(result.first, value)
            }
        }
    }

    // One paged stream per open leaf group. Collected here rather than inside the LazyColumn so a
    // group keeps its loaded pages while it scrolls in and out of the viewport.
    val leafItems = mutableMapOf<String, LazyPagingItems<TaskWithTotals>>()
    for (leaf in openLeaves.values) {
        key(leaf.groupKey) {
            val pages = remember(leaf, filterCriteria, viewMode) {
                onGetTaskPages(
                    leaf.filters,
                    leaf.orderingRules.ifEmpty { viewMode.defaultOrderingRules },
                    filterCriteria,
                )
            }
            leafItems[leaf.groupKey] = pages.collectAsLazyPagingItems()
        }
    }

    // Only show the list when data is loaded to avoid animation glitches on initial render
    val hasLoadedData = loadedGroups.isNotEmpty() || openLeaves.isNotEmpty()
    var shouldAnimateInner by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = hasLoadedData,
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
                val tasks = leafItems[""]
                if (tasks != null) {
                    taskCards(
                        tasks = tasks,
                        groupKey = "",
                        indent = 0.dp,
                        onTaskClick = onTaskClick,
                        onDelete = onDelete,
                        onCopy = onCopy,
                    )
                }
            } else {
                // Render groups recursively
                TaskGroupItems(
                    loadedGroups = loadedGroups,
                    openLeaves = openLeaves,
                    leafItems = leafItems,
                    parentKey = "",
                    collapsedGroups = collapsedGroupsSet,
                    expandedUncategorized = expandedUncategorizedSet,
                    onToggleCollapse = { key ->
                        collapsedGroupsSet = if (key in collapsedGroupsSet) {
                            collapsedGroupsSet.removing(key)
                        } else {
                            collapsedGroupsSet.adding(key)
                        }
                    },
                    onToggleUncategorized = { key ->
                        expandedUncategorizedSet = if (key in expandedUncategorizedSet) {
                            expandedUncategorizedSet.removing(key)
                        } else {
                            expandedUncategorizedSet.adding(key)
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

/** Task cards of one (possibly nested) group, appended to the surrounding list. */
private fun LazyListScope.taskCards(
    tasks: LazyPagingItems<TaskWithTotals>,
    groupKey: String,
    indent: Dp,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
) {
    items(
        count = tasks.itemCount,
        // Keys carry the group so the same task can appear under two groups without colliding.
        key = tasks.itemKey { "${groupKey}_${it.task.id}" },
    ) { index ->
        val taskWithTotals = tasks[index]
        if (taskWithTotals != null) {
            TaskCard(
                taskWithTotals = taskWithTotals,
                onClick = { onTaskClick(taskWithTotals.task.id) },
                onDelete = { onDelete(taskWithTotals) },
                onCopy = { onCopy(taskWithTotals.task.id) },
                modifier = Modifier
                    .animateItem()
                    .padding(start = indent),
            )
        }
    }
    pagingLoadStatus(tasks, keyPrefix = "${groupKey}_")
}

/**
 * Recursively renders task groups with proper LazyColumn item animations.
 */
private fun LazyListScope.TaskGroupItems(
    loadedGroups: Map<String, List<LoadedGroupData>>,
    openLeaves: Map<String, OpenLeafGroup>,
    leafItems: Map<String, LazyPagingItems<TaskWithTotals>>,
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
                        val hasTasks = groupKey in openLeaves
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
                    openLeaves = openLeaves,
                    leafItems = leafItems,
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
                val tasks = leafItems[groupKey]
                if (tasks != null) {
                    taskCards(
                        tasks = tasks,
                        groupKey = groupKey,
                        indent = (nextLevelIndex * 16).dp,
                        onTaskClick = onTaskClick,
                        onDelete = onDelete,
                        onCopy = onCopy,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskListContent(
    viewMode: ViewMode,
    hasAnyTasks: Boolean,
    dataVersion: Long,
    filterState: TaskFilterState,
    allTags: Set<String>,
    spaceIdPrefix: String?,
    isFilterPanelOpen: Boolean,
    onToggleFilterPanel: () -> Unit,
    onSaveFilter: () -> Unit,
    shouldAnimate: Boolean,
    onGetTaskGroups: suspend (ViewMode, Int, PersistentList<GroupFilter>, TaskFilterCriteria) -> List<TaskGroupInfo>,
    onCountTasks: suspend (PersistentList<GroupFilter>, TaskFilterCriteria) -> Int,
    onGetTaskPages: (PersistentList<GroupFilter>, PersistentList<OrderingRule>, TaskFilterCriteria) -> Flow<PagingData<TaskWithTotals>>,
    onTaskClick: (String) -> Unit,
    onDelete: (TaskWithTotals) -> Unit,
    onCopy: (String) -> Unit,
) {
    // How many tasks the current filters match, reported by the group query that also fills the
    // list. Null until the first load, so the empty state does not flash.
    var matchingTaskCount by remember { mutableStateOf<Int?>(null) }

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
                matchingTaskCount = matchingTaskCount,
                shouldAnimate = shouldAnimate,
                onClearFilters = { filterState.clearAll() }
            )

            DynamicTaskList(
                viewMode = viewMode,
                filterCriteria = filterState.toCriteria(),
                dataVersion = dataVersion,
                shouldAnimate = shouldAnimate,
                onGetTaskGroups = onGetTaskGroups,
                onCountTasks = onCountTasks,
                onGetTaskPages = onGetTaskPages,
                onMatchingCountChange = { matchingTaskCount = it },
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
    var showSaveFilterDialog by rememberSaveable { mutableStateOf(false) }

    val dataVersion by container.dataVersion.collectAsState()

    LaunchedEffect(refreshTrigger, dataVersion) {
        container.store.intent(TaskListIntent.LoadTasks)
    }

    val filterState = rememberPersistedFilterState(
        onLoadFilterState = { state.filterState },
        onSaveFilterState = { criteria ->
            container.store.intent(TaskListIntent.SaveFilterState(criteria))
        }
    )

    val uiState = rememberTaskListUiState(
        onLoadInitialData = {
            TaskListUiData(
                activeViewMode = state.activeViewMode ?: return@rememberTaskListUiState null,
                viewModes = state.viewModes.takeIf { it.isNotEmpty() } ?: return@rememberTaskListUiState null,
                isFilterPanelOpen = state.filterPanelOpen ?: return@rememberTaskListUiState null,
            )
        },
        onSaveActiveViewMode = { viewModeId ->
            container.store.intent(TaskListIntent.SetActiveViewMode(viewModeId))
        },
        onSaveFilterPanelOpen = { isOpen ->
            container.store.intent(TaskListIntent.SaveFilterPanelOpen(isOpen))
        },
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

    // Handle loading saved filter - wait for UI state to be ready
    var filterApplied by remember { mutableStateOf(false) }
    LaunchedEffect(loadedFilterId, uiState.value) {
        if (loadedFilterId != null && uiState.value.dataOrNull != null && !filterApplied) {
            filterApplied = true
            container.reportingFailure(Unit) {
                applySavedFilter(
                    loadedFilterId = loadedFilterId,
                    container = container,
                    filterState = filterState,
                    uiState = uiState
                )
            }
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
                    dataVersion = dataVersion,
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
                    // Reported rather than thrown: these are awaited straight from effects, so
                    // a database error would otherwise escape into the composition, where the
                    // store's recover plugin cannot reach it.
                    onGetTaskGroups = { mode, level, filters, criteria ->
                        container.reportingFailure(emptyList()) {
                            container.getTaskGroups(mode, level, filters, criteria)
                        }
                    },
                    onCountTasks = { filters, criteria ->
                        container.reportingFailure(0) { container.countTasksForGroup(filters, criteria) }
                    },
                    onGetTaskPages = container::tasksForGroupPages,
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
