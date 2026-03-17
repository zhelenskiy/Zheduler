@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.taskdetail

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.common.ConnectedTaskChip
import com.zhelenskiy.zheduler.zheduler.components.common.DueDateBadge
import com.zhelenskiy.zheduler.zheduler.components.common.PriorityBadge
import com.zhelenskiy.zheduler.zheduler.components.common.TagChip
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.form.RecurrenceRuleItem
import com.zhelenskiy.zheduler.zheduler.components.markdown.SimpleMarkdownText
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.util.TaskStatus
import com.zhelenskiy.zheduler.zheduler.util.TaskStatusChange
import com.zhelenskiy.zheduler.zheduler.util.formatCompactDateTime
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskDetailViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private data class TaskDetailData(
    val currentSpaceIdPrefix: String?,
    val allSpacePrefixes: List<String>,
    val statusTimeline: List<StatusChange>,
    val blockerTasks: Map<String, Task>,
    val connectedTasks: Map<String, Task>,
    val loadedTasks: Map<String, Task>,
    val loadTask: (String) -> Unit
)

@Composable
private fun rememberTaskDetailData(
    task: Task,
    viewModel: TaskDetailViewModel
): TaskDetailData {
    var currentSpaceIdPrefix by remember { mutableStateOf<String?>(null) }
    var allSpacePrefixes by remember { mutableStateOf<List<String>>(emptyList()) }
    var statusTimeline by remember { mutableStateOf<List<StatusChange>>(emptyList()) }
    var blockerTasks by remember { mutableStateOf<Map<String, Task>>(emptyMap()) }
    var connectedTasks by remember { mutableStateOf<Map<String, Task>>(emptyMap()) }
    var loadedTasks by remember { mutableStateOf<Map<String, Task>>(emptyMap()) }

    val scope = rememberCoroutineScope()
    val loadTask: (String) -> Unit = { taskId: String ->
        if (taskId !in loadedTasks) {
            scope.launch {
                viewModel.getTaskById(taskId)?.let { task ->
                    loadedTasks = loadedTasks + (taskId to task)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        currentSpaceIdPrefix = viewModel.getCurrentSpaceIdPrefix()
        allSpacePrefixes = viewModel.getAllSpacePrefixes()
    }

    LaunchedEffect(task) {
        val timeline = viewModel.getStatusTimeline(task.id)
        statusTimeline = timeline

        val blockerIds = buildSet {
            val status = task.status
            if (status is TaskStatus.Blocked) {
                addAll(status.blockerTaskIds)
            }
            timeline.forEach { change ->
                val prevStatus = change.previousStatus
                if (prevStatus is TaskStatus.Blocked) {
                    addAll(prevStatus.blockerTaskIds)
                }
                val newStatus = change.newStatus
                if (newStatus is TaskStatus.Blocked) {
                    addAll(newStatus.blockerTaskIds)
                }
            }
        }
        blockerTasks = buildMap {
            blockerIds.forEach { blockerId ->
                viewModel.getTaskById(blockerId)?.let { put(blockerId, it) }
            }
        }

        connectedTasks = buildMap {
            task.connections.forEach { connection ->
                viewModel.getTaskById(connection.targetTaskId)?.let {
                    put(connection.targetTaskId, it)
                }
            }
        }
    }

    return TaskDetailData(
        currentSpaceIdPrefix = currentSpaceIdPrefix,
        allSpacePrefixes = allSpacePrefixes,
        statusTimeline = statusTimeline,
        blockerTasks = blockerTasks,
        connectedTasks = connectedTasks,
        loadedTasks = loadedTasks,
        loadTask = loadTask
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailTopAppBar(
    taskId: String,
    onNavigateBack: () -> Unit,
    onStartEditing: () -> Unit,
    onNavigateToSpaceList: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text("Task Details")
                Text(
                    text = taskId,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onStartEditing) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
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
private fun TaskReadOnlyView(
    task: Task,
    taskWithTotals: TaskWithTotals?,
    detailData: TaskDetailData,
    connectionsByType: Map<ConnectionType, List<Task>>,
    onTaskClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TaskTitleSection(task = task)
        TaskStatusSection(
            task = task,
            taskWithTotals = taskWithTotals,
            statusTimeline = detailData.statusTimeline,
            blockerTasks = detailData.blockerTasks,
            loadedTasks = detailData.loadedTasks,
            loadTask = detailData.loadTask,
            onTaskClick = onTaskClick
        )
        TaskPrioritySection(task = task, taskWithTotals = taskWithTotals)
        TaskEstimatedTimeSection(task = task)
        TaskDueDateSection(task = task, taskWithTotals = taskWithTotals)
        TaskRecurrenceSection(task = task, onTaskClick = onTaskClick)
        TaskNotificationsSection(task = task)
        TaskTagsSection(task = task)
        TaskConnectionsSection(
            connectionsByType = connectionsByType,
            onTaskClick = onTaskClick
        )
        TaskDescriptionSection(
            task = task,
            allSpacePrefixes = detailData.allSpacePrefixes,
            connectedTasks = detailData.connectedTasks,
            onTaskClick = onTaskClick
        )
    }
}

@Composable
private fun TaskTitleSection(task: Task) {
    val isDone = task.status is TaskStatus.Done
    val isDeclined = task.status is TaskStatus.Declined
    Text(
        text = task.title,
        style = MaterialTheme.typography.headlineSmall,
        textDecoration = if (isDone || isDeclined) TextDecoration.LineThrough else null
    )
}

@Composable
private fun TaskStatusSection(
    task: Task,
    taskWithTotals: TaskWithTotals?,
    statusTimeline: List<StatusChange>,
    blockerTasks: Map<String, Task>,
    loadedTasks: Map<String, Task>,
    loadTask: (String) -> Unit,
    onTaskClick: (String) -> Unit
) {
    var isTimelineExpanded by rememberSaveable(task.id) { mutableStateOf(false) }

    Column {
        if (taskWithTotals?.isMissed(Clock.System.now()) == true) {
            val color = MaterialTheme.colorScheme.error
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = color
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Missed", color = color)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Status:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = { isTimelineExpanded = !isTimelineExpanded },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "History",
                    modifier = Modifier.size(18.dp)
                )
            }
            TaskStatus(
                status = task.status,
                blockerTasks = blockerTasks,
                onBlockerTaskClick = onTaskClick
            )
        }

        AnimatedVisibility(
            visible = isTimelineExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            StatusTimelineContent(
                statusTimeline = statusTimeline,
                blockerTasks = blockerTasks,
                loadedTasks = loadedTasks,
                loadTask = loadTask,
                onTaskClick = onTaskClick
            )
        }
    }
}

@Composable
private fun StatusTimelineContent(
    statusTimeline: List<StatusChange>,
    blockerTasks: Map<String, Task>,
    loadedTasks: Map<String, Task>,
    loadTask: (String) -> Unit,
    onTaskClick: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = "History",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        statusTimeline.asReversed().forEachIndexed { index, change ->
            val changeNumber = statusTimeline.size - 1 - index
            Column(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "$changeNumber.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = formatCompactDateTime(change.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    TaskStatusChange(
                        change = change,
                        blockerTasks = blockerTasks,
                        onBlockerTaskClick = onTaskClick,
                        loadedTasks = loadedTasks,
                        loadTask = loadTask,
                        onTaskClick = onTaskClick
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskPrioritySection(task: Task, taskWithTotals: TaskWithTotals?) {
    if (task.priority == null && taskWithTotals?.totalPriority == null) return

    val ownPriority = task.priority
    val totalPriority = taskWithTotals?.totalPriority
    val isSingleLine = ownPriority == totalPriority && ownPriority != null

    if (isSingleLine) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Priority:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PriorityBadge(priority = ownPriority, isTotal = false)
        }
    } else {
        Column {
            Text(
                text = "Priority",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Own", style = MaterialTheme.typography.labelMedium)
                    ownPriority?.let {
                        PriorityBadge(priority = it, isTotal = false)
                    } ?: Text("-", style = MaterialTheme.typography.bodyMedium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Total", style = MaterialTheme.typography.labelMedium)
                    totalPriority?.let {
                        PriorityBadge(priority = it, isTotal = it != task.priority)
                    } ?: Text("-", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TaskEstimatedTimeSection(task: Task) {
    task.estimatedTime?.let { estimatedTime ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Estimated Time:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = estimatedTime.toBriefString(),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun TaskDueDateSection(task: Task, taskWithTotals: TaskWithTotals?) {
    if (task.dueDate == null && taskWithTotals?.totalDueDate == null) return

    val ownDate = task.dueDate
    val totalDate = taskWithTotals?.totalDueDate
    val isSingleLine = ownDate == totalDate && ownDate != null

    if (isSingleLine) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Due Time:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DueDateBadge(dueDate = ownDate, isTotal = false)
        }
    } else {
        Column {
            Text(
                text = "Due Time",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Own", style = MaterialTheme.typography.labelMedium)
                    ownDate?.let {
                        DueDateBadge(dueDate = it, isTotal = false)
                    } ?: Text("-", style = MaterialTheme.typography.bodyMedium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Total", style = MaterialTheme.typography.labelMedium)
                    totalDate?.let {
                        DueDateBadge(dueDate = it, isTotal = it != task.dueDate)
                    } ?: Text("-", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TaskRecurrenceSection(task: Task, onTaskClick: (String) -> Unit) {
    if (!task.isRecurring) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Recurrence",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(
            modifier = Modifier.heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(task.recurrenceRules) { index, (rule, _) ->
                RecurrenceRuleItem(
                    rule = rule,
                    onEdit = null,
                    onDelete = null,
                    index = index,
                    onTaskClick = onTaskClick
                )
            }
        }
        val recurrenceCount = task.recurrenceRules.sumOf { (_, state) -> state.occurrenceCount }
        if (recurrenceCount > 0) {
            Text(
                text = "Occurrence #${recurrenceCount + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TaskNotificationsSection(task: Task) {
    if (task.notifications.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Notifications:",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            task.notifications.forEach { notification ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = notification.timeBeforeDeadline.toBriefString(),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskTagsSection(task: Task) {
    if (task.tags.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Tags:",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            task.tags.forEach { tag ->
                TagChip(tag = tag)
            }
        }
    }
}

@Composable
private fun TaskConnectionsSection(
    connectionsByType: Map<ConnectionType, List<Task>>,
    onTaskClick: (String) -> Unit
) {
    if (connectionsByType.isEmpty()) return

    Column {
        Text(
            text = "Connections",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        ConnectionType.entries.forEach { connectionType ->
            val tasksForType = connectionsByType[connectionType] ?: emptyList()
            if (tasksForType.isNotEmpty()) {
                Text(
                    text = connectionType.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    tasksForType.forEach { connectedTask ->
                        ConnectedTaskChip(
                            task = connectedTask,
                            taskId = connectedTask.id,
                            onClick = { onTaskClick(connectedTask.id) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun TaskDescriptionSection(
    task: Task,
    allSpacePrefixes: List<String>,
    connectedTasks: Map<String, Task>,
    onTaskClick: (String) -> Unit
) {
    if (task.description.isEmpty()) return

    SimpleMarkdownText(
        markdown = task.description,
        allSpacePrefixes = allSpacePrefixes,
        getTaskById = { taskId -> connectedTasks[taskId] },
        onTaskClick = onTaskClick
    )
}

@Composable
fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    externalRefreshTrigger: Int,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: () -> Unit,
    onTaskClick: (String) -> Unit,
    onNavigateToSpaceList: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    val taskWithTotals by viewModel.taskWithTotals.collectAsState()
    val connectionsByType by viewModel.connectionsByType.collectAsState()

    LaunchedEffect(externalRefreshTrigger) {
        viewModel.loadTask()
    }

    val currentTaskWithTotals = taskWithTotals ?: return
    val task = currentTaskWithTotals.task

    val detailData = rememberTaskDetailData(task, viewModel)

    Scaffold(
        topBar = {
            TaskDetailTopAppBar(
                taskId = task.id,
                onNavigateBack = onNavigateBack,
                onStartEditing = onNavigateToEdit,
                onNavigateToSpaceList = onNavigateToSpaceList,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        }
    ) { padding ->
        TaskReadOnlyView(
            task = task,
            taskWithTotals = taskWithTotals,
            detailData = detailData,
            connectionsByType = connectionsByType,
            onTaskClick = onTaskClick,
            modifier = Modifier.padding(padding)
        )
    }
}

