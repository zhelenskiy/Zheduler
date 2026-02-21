@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.taskdetail

import androidx.compose.animation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.common.AutomaticChangeIndicator
import com.zhelenskiy.zheduler.zheduler.components.common.ConnectedTaskChip
import com.zhelenskiy.zheduler.zheduler.components.common.DueDateBadge
import com.zhelenskiy.zheduler.zheduler.components.common.PriorityBadge
import com.zhelenskiy.zheduler.zheduler.components.common.StatusBadge
import com.zhelenskiy.zheduler.zheduler.components.common.TagChip
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DiscardChangesDialog
import com.zhelenskiy.zheduler.zheduler.components.form.TaskFormContent
import com.zhelenskiy.zheduler.zheduler.components.form.rememberTaskFormState
import com.zhelenskiy.zheduler.zheduler.components.markdown.SimpleMarkdownText
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.util.TaskStatus
import com.zhelenskiy.zheduler.zheduler.util.TaskStatusChange
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskDetailViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    externalRefreshTrigger: Int = 0,
    onNavigateBack: () -> Unit,
    onAddNewTaskWithConnection: (String, ConnectionType) -> Unit,
    onTaskClick: (String) -> Unit,
    onNavigateToSpaceList: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit
) {
    val taskWithTotals by viewModel.taskWithTotals.collectAsState()
    val taskLoadAttempted by viewModel.taskLoadAttempted.collectAsState()
    val isEditing by viewModel.isEditing.collectAsState()

    LaunchedEffect(externalRefreshTrigger) {
        viewModel.loadTask()
    }

    val currentTaskWithTotals = taskWithTotals

    // Only navigate back if load was attempted and task still doesn't exist
    if (taskLoadAttempted && currentTaskWithTotals == null) {
        LaunchedEffect(Unit) {
            onNavigateBack()
        }
        return
    }

    // Show nothing while task is being loaded
    if (currentTaskWithTotals == null) {
        return
    }

    var task by remember { mutableStateOf(currentTaskWithTotals.task) }

    LaunchedEffect(taskWithTotals) {
        val updated = taskWithTotals
        if (updated != null) {
            task = updated.task
        }
    }

    val formState = rememberTaskFormState(task)

    // Restore form state from ViewModel's persisted state (when returning from nested task creation)
    LaunchedEffect(Unit) {
        val persistedState = viewModel.getPersistedFormState()
        persistedState.title?.let { formState.title = it }
        persistedState.description?.let { formState.description = it }
        persistedState.priority?.let { formState.priority = it }
        persistedState.estimatedTime?.let { formState.estimatedTime = it }
        if (persistedState.tags.isNotEmpty()) {
            formState.tags = persistedState.tags
        }
        persistedState.dueDate?.let { formState.dueDate = it }
    }

    // Persist form state to ViewModel when it changes (for when navigating away)
    LaunchedEffect(formState.title, formState.description, formState.priority, formState.estimatedTime, formState.tags, formState.dueDate) {
        viewModel.persistFormState(
            title = formState.title,
            description = formState.description,
            priority = formState.priority,
            estimatedTime = formState.estimatedTime,
            tags = formState.tags,
            dueDate = formState.dueDate
        )
    }

    // Load async data for TaskFormContent and read-only view
    var currentSpaceIdPrefix by remember { mutableStateOf<String?>(null) }
    var allSpacePrefixes by remember { mutableStateOf<List<String>>(emptyList()) }
    var statusTimeline by remember { mutableStateOf<List<StatusChange>>(emptyList()) }
    var blockerTasks by remember { mutableStateOf<Map<String, Task>>(emptyMap()) }
    var connectedTasks by remember { mutableStateOf<Map<String, Task>>(emptyMap()) }

    LaunchedEffect(Unit) {
        currentSpaceIdPrefix = viewModel.getCurrentSpaceIdPrefix()
        allSpacePrefixes = viewModel.getAllSpacePrefixes()
    }

    LaunchedEffect(task) {
        val timeline = viewModel.getStatusTimeline(task.id)
        statusTimeline = timeline

        // Load blocker tasks from current status and history
        val blockers = mutableMapOf<String, Task>()
        val status = task.status
        if (status is TaskStatus.Blocked) {
            status.blockerTaskIds.forEach { blockerId ->
                viewModel.getTaskById(blockerId)?.let { blockers[blockerId] = it }
            }
        }

        // Also load blocker tasks from status history
        timeline.forEach { change ->
            val prevStatus = change.previousStatus
            if (prevStatus is TaskStatus.Blocked) {
                prevStatus.blockerTaskIds.forEach { blockerId ->
                    if (blockerId !in blockers) {
                        viewModel.getTaskById(blockerId)?.let { blockers[blockerId] = it }
                    }
                }
            }
            val newStatus = change.newStatus
            if (newStatus is TaskStatus.Blocked) {
                newStatus.blockerTaskIds.forEach { blockerId ->
                    if (blockerId !in blockers) {
                        viewModel.getTaskById(blockerId)?.let { blockers[blockerId] = it }
                    }
                }
            }
        }
        blockerTasks = blockers

        // Load connected tasks
        val connected = mutableMapOf<String, Task>()
        task.connections.forEach { connection ->
            viewModel.getTaskById(connection.targetTaskId)?.let {
                connected[connection.targetTaskId] = it
            }
        }
        connectedTasks = connected
    }

    var showDiscardChangesDialog by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // Refresh task data when returning from nested task creation
    // This updates the connections that were added via symmetric connection
    // Using taskWithTotals as key ensures we refresh when the repository data changes
    LaunchedEffect(taskWithTotals) {
        val currentTaskWithTotals = taskWithTotals
        if (isEditing && currentTaskWithTotals != null) {
            val freshTask = currentTaskWithTotals.task
            // Update connections in form state with new connections from repository
            val newConnections = freshTask.connections - task.connections
            if (newConnections.isNotEmpty()) {
                formState.connections = formState.connections + newConnections
                task = freshTask
            }
        }
    }

    val connectionsByType by viewModel.connectionsByType.collectAsState()

    LaunchedEffect(refreshTrigger) {
        viewModel.loadTask()
    }

    LaunchedEffect(isEditing, formState.connections) {
        if (isEditing) {
            viewModel.refreshConnectionsByType(formState.connections)
        } else {
            viewModel.loadTask()
        }
    }

    fun saveChanges() {
        val parsed = formState.toParsedValues() ?: return
        val updatedTask = task.copy(
            title = parsed.title,
            description = parsed.description,
            priority = parsed.priority,
            estimatedTime = parsed.estimatedTime,
            tags = parsed.tags,
            dueDate = parsed.dueDate,
            status = parsed.status,
            connections = parsed.connections,
            notifications = parsed.notifications,
            recurrenceRule = parsed.recurrenceRule,
            resetStatusOnRecurrence = parsed.resetStatusOnRecurrence,
            autoUpdateStatusFromSubtasks = parsed.autoUpdateStatusFromSubtasks
        )
        viewModel.saveTask(updatedTask)
        task = updatedTask
        refreshTrigger++
    }

    fun cancelEditing() {
        viewModel.cancelEditing()
        formState.resetTo(task)
    }

    fun handleBackPress() {
        if (isEditing && formState.hasUnsavedChanges(task)) {
            showDiscardChangesDialog = true
        } else {
            cancelEditing()
        }
    }

    // Discard changes confirmation dialog
    if (showDiscardChangesDialog) {
        DiscardChangesDialog(
            title = "Discard changes?",
            message = "You have unsaved changes. Are you sure you want to discard them?",
            confirmText = "Discard",
            dismissText = "Keep editing",
            onConfirm = {
                showDiscardChangesDialog = false
                cancelEditing()
            },
            onDismiss = { showDiscardChangesDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (isEditing) "Edit Task" else "Task Details")
                        Text(
                            text = task.id,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (isEditing) { { handleBackPress() } } else { onNavigateBack }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(
                            onClick = { saveChanges() },
                            enabled = formState.isFormValid,
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = if (formState.isFormValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                disabledContentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    } else {
                        IconButton(onClick = { viewModel.startEditing() }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = onNavigateToSpaceList) {
                            Icon(Icons.Default.Home, contentDescription = "Spaces")
                        }
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
        }
    ) { padding ->
        AnimatedContent(
            targetState = isEditing,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "edit_mode_transition"
        ) { editing ->
            if (editing) {
                Box(modifier = Modifier.padding(padding)) {
                    TaskFormContent(
                        formState = formState,
                        taskId = task.id,
                        isNewTask = false,
                        onTaskClick = onTaskClick,
                        onCreateNewTaskWithConnection = { connectionType ->
                            onAddNewTaskWithConnection(task.id, connectionType.symmetric)
                        },
                        getTaskById = viewModel::getTaskById,
                        filterTags = viewModel::filterTags,
                        filterTasksForSelection = viewModel::filterTasksForSelection,
                        searchTasksForConnection = viewModel::searchTasksForConnection,
                        getCalculatedStatusFromSubtasks = viewModel::getCalculatedStatusFromSubtasks,
                        currentSpaceIdPrefix = currentSpaceIdPrefix,
                        allSpacePrefixes = allSpacePrefixes
                    )
                }
            } else {
                // Read-only view
                Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title
                val isDone = task.status is TaskStatus.Done
                val isDeclined = task.status is TaskStatus.Declined
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textDecoration = if (isDone || isDeclined) TextDecoration.LineThrough else null
                )

                // Status section
                var isTimelineExpanded by rememberSaveable(task.id) { mutableStateOf(false) }

                Column {
                    if (currentTaskWithTotals.isMissed(Clock.System.now())) {
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
                            Text(
                                text = "Missed",
                                color = color
                            )
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

                        // History button
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

                        TaskStatus(status = task.status, blockerTasks = blockerTasks, onBlockerTaskClick = onTaskClick)
                    }

                    // Animated timeline
                    AnimatedVisibility(
                        visible = isTimelineExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            // Header
                            Text(
                                text = "History",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            statusTimeline.asReversed().forEachIndexed { index, change ->
                                val changeNumber = statusTimeline.size - 1 - index
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
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
                                            text = com.zhelenskiy.zheduler.zheduler.util.formatCompactDateTime(change.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        TaskStatusChange(
                                            change = change,
                                            blockerTasks = blockerTasks,
                                            onBlockerTaskClick = onTaskClick
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Priority section
                if (task.priority != null || taskWithTotals?.totalPriority != null) {
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

                // Estimated Time section
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

                // Due date section
                if (task.dueDate != null || taskWithTotals?.totalDueDate != null) {
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

                // Recurrence section
                if (task.isRecurring) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = task.recurrenceRule.toFullString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (task.recurrenceState.occurrenceCount > 0) {
                                Text(
                                    text = "Occurrence #${task.recurrenceState.occurrenceCount + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Notifications section
                if (task.notifications.isNotEmpty()) {
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

                // Tags section
                if (task.tags.isNotEmpty()) {
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

                // Connections section
                if (connectionsByType.isNotEmpty()) {
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
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                tasksForType.forEach { connectedTask ->
                                    ConnectedTaskChip(
                                        task = connectedTask,
                                        taskId = connectedTask.id,
                                        onClick = { onTaskClick(connectedTask.id) },
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }
                }

                // Description section
                if (task.description.isNotEmpty()) {
                    SimpleMarkdownText(
                        markdown = task.description,
                        allSpacePrefixes = allSpacePrefixes,
                        getTaskById = { taskId -> connectedTasks[taskId] },
                        onTaskClick = onTaskClick
                    )
                }
            }
        }
        }
    }
}
