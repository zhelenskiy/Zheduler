@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.common.ConnectedTaskChip
import com.zhelenskiy.zheduler.zheduler.components.common.StatusBadge
import com.zhelenskiy.zheduler.zheduler.components.common.TagChip
import com.zhelenskiy.zheduler.zheduler.components.dialogs.*
import com.zhelenskiy.zheduler.zheduler.components.markdown.SimpleMarkdownText
import com.zhelenskiy.zheduler.zheduler.util.TaskStatus
import com.zhelenskiy.zheduler.zheduler.util.formatDueDate
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

@Composable
private fun PrefilledConnectionInfo(
    prefilledTask: Task,
    prefilledConnection: TaskConnection
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${prefilledConnection.type.displayName}: ${prefilledTask.id} - ${prefilledTask.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun TitleField(formState: TaskFormState) {
    OutlinedTextField(
        value = formState.title,
        onValueChange = { formState.title = it },
        label = { Text("Title") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = formState.title.isBlank(),
        supportingText = if (formState.title.isBlank()) { { Text("Title is required") } } else null
    )
}

@Composable
private fun StatusSection(
    formState: TaskFormState,
    filterTasksForSelection: suspend (String) -> List<Task>,
    getTaskById: suspend (String) -> Task?
) {
    var showStatusDialog by remember { mutableStateOf(false) }

    AnimatedVisibility(visible = !formState.autoUpdateStatusFromSubtasks) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.clickable { showStatusDialog = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Status:",
                            style = MaterialTheme.typography.titleSmall
                        )
                        TaskStatus(status = formState.status, blockerTasks = null, onBlockerTaskClick = null)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    Icons.Default.Edit, contentDescription = "Change status",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    if (showStatusDialog) {
        StatusSelectionDialog(
            currentStatus = formState.status,
            filterTasks = filterTasksForSelection,
            getTaskById = getTaskById,
            onDismiss = { showStatusDialog = false },
            onStatusSelected = { status ->
                formState.status = status
                showStatusDialog = false
            }
        )
    }
}

@Composable
private fun PriorityField(formState: TaskFormState) {
    fun isError() = formState.priority.isNotEmpty() &&
            formState.priority.toIntOrNull()?.let { it in 1..100 } != true
    OutlinedTextField(
        value = formState.priority,
        onValueChange = { formState.priority = it },
        label = { Text("Priority (1-100)") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        supportingText = {
            Text(
                text = if (isError()) {
                    "Invalid priority. Must be between 1 and 100"
                } else {
                    "Leave empty for no priority"
                }
            )
        },
        isError = isError(),
    )
}

@Composable
private fun EstimatedTimeField(formState: TaskFormState) {
    OutlinedTextField(
        value = formState.estimatedTime,
        onValueChange = { formState.estimatedTime = it },
        label = { Text("Estimated time (e.g. 2h 30m, 1d, 1w 2d)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        supportingText = {
            Text(
                text = if (formState.estimatedTime.isNotEmpty() && parseCompactTimeToPeriod(formState.estimatedTime) == null) {
                    "Invalid format. Use: y, mo, w, d, h, m, s"
                } else {
                    "Leave empty for no estimate"
                }
            )
        },
        isError = formState.estimatedTime.isNotEmpty() && parseCompactTimeToPeriod(formState.estimatedTime) == null
    )
}

@Composable
private fun DueDatePicker(formState: TaskFormState) {
    var showDatePicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { showDatePicker = true },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.DateRange, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(formState.dueDate?.let { formatDueDate(it) } ?: "Set due time")
        }
        AnimatedVisibility(formState.dueDate != null) {
            IconButton(onClick = { formState.dueDate = null }) {
                Icon(Icons.Default.Clear, contentDescription = "Clear date")
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            currentDate = formState.dueDate,
            onDismiss = { showDatePicker = false },
            onDateSelected = { date ->
                formState.dueDate = date
                showDatePicker = false
            }
        )
    }
}

@Composable
private fun RecurrenceSection(
    formState: TaskFormState,
    filterTasksForSelection: suspend (String) -> List<Task>,
    getTaskById: suspend (String) -> Task?
) {
    var showRecurrenceDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showRecurrenceDialog = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = if (formState.recurrenceRule != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Repeat", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = formState.recurrenceRule.toFullString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit recurrence",
                modifier = Modifier.size(16.dp)
            )
        }
    }

    if (showRecurrenceDialog) {
        RecurrenceDialog(
            currentRule = formState.recurrenceRule,
            filterTasks = filterTasksForSelection,
            getTaskById = getTaskById,
            onDismiss = { showRecurrenceDialog = false },
            onRecurrenceSelected = { rule ->
                formState.recurrenceRule = rule
                showRecurrenceDialog = false
            }
        )
    }
}

@Composable
private fun ColumnScope.NotificationsSection(formState: TaskFormState) {
    AnimatedVisibility(visible = formState.dueDate != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(
                        start = 16.dp,
                        bottom = if (formState.notifications.isEmpty()) 4.dp else 16.dp, top = 4.dp, end = 4.dp
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Notifications before due time:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    AnimatedVisibility(formState.notifications.isEmpty(), enter = fadeIn(), exit = fadeOut()) {
                        Text(
                            text = "No notifications",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { formState.notifications += "" }) {
                        Icon(Icons.Default.Add, contentDescription = "Add notification")
                    }
                }

                AnimatedContent(
                    targetState = formState.notifications,
                    transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                ) { notifications ->
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(formState.notifications) { index, notification ->
                            Row(
                                modifier = Modifier.fillMaxWidth().animateItem(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = notification,
                                    onValueChange = { newValue ->
                                        formState.notifications = notifications.toMutableList().apply {
                                            set(index, newValue)
                                        }
                                    },
                                    label = { Text("Time before deadline") },
                                    placeholder = { Text("e.g., 1d, 2h 30m") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    isError = notification.isBlank() || parseCompactTimeToPeriod(notification) == null,
                                    supportingText = if (notification.isBlank()) {
                                        { Text("Notification time is required") }
                                    } else if (parseCompactTimeToPeriod(notification) == null) {
                                        { Text("Invalid format. Use: y, mo, w, d, h, m, s") }
                                    } else {
                                        null
                                    }
                                )
                                IconButton(onClick = {
                                    formState.notifications = formState.notifications.toMutableList().apply {
                                        removeAt(index)
                                    }
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Remove notification")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagsSection(
    formState: TaskFormState,
    filterTags: suspend (String, Set<String>) -> List<String>
) {
    var showTagDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Tags:",
                style = MaterialTheme.typography.titleSmall
            )

            if (formState.tags.isEmpty()) {
                Text(
                    text = "No tags selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            } else {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    formState.tags.forEach { tag ->
                        TagChip(
                            tag = tag,
                            onRemove = { formState.tags -= tag }
                        )
                    }
                }
            }

            IconButton(onClick = { showTagDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add tag")
            }
        }
    }

    if (showTagDialog) {
        TagSelectionDialog(
            selectedTags = formState.tags,
            filterTags = filterTags,
            onDismiss = { showTagDialog = false },
            onTagSelected = { tag ->
                formState.tags += tag
                showTagDialog = false
            }
        )
    }
}

@Composable
private fun ConnectionsSection(
    formState: TaskFormState,
    taskId: String,
    connectedTasks: Map<String, Task>,
    searchTasksForConnection: suspend (String, Set<String>, ConnectionType, Set<TaskConnection>) -> List<Task>,
    getCalculatedStatusFromSubtasks: suspend (String) -> TaskStatus?,
    onCreateNewTaskWithConnection: ((ConnectionType) -> Unit)?
) {
    val coroutineScope = rememberCoroutineScope()
    var showConnectionDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connections:",
                    style = MaterialTheme.typography.titleSmall
                )
                if (formState.connections.isEmpty()) {
                    Text(
                        text = "No connections",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                }
                IconButton(onClick = { showConnectionDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add connection")
                }
            }

            if (formState.connections.isNotEmpty()) {
                ConnectionType.entries.forEach { connectionType ->
                    val connectionsForType = formState.connections.filter { it.type == connectionType }
                    if (connectionsForType.isNotEmpty()) {
                        Text(
                            text = connectionType.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        connectionsForType.forEach { connection ->
                            val connectedTask = connectedTasks[connection.targetTaskId]
                            ConnectedTaskChip(
                                task = connectedTask,
                                taskId = connection.targetTaskId,
                                onRemove = { formState.connections -= connection },
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }

            // Auto-update status from subtasks toggle
            val hasSubtasks = formState.connections.any { it.type == ConnectionType.ParentOf }
            if (hasSubtasks) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-update status from subtasks",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Automatically sets status based on subtask statuses",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = formState.autoUpdateStatusFromSubtasks,
                        onCheckedChange = { enabled ->
                            formState.autoUpdateStatusFromSubtasks = enabled
                            // When enabled, recalculate status from subtasks
                            if (enabled) {
                                coroutineScope.launch {
                                    val calculatedStatus = getCalculatedStatusFromSubtasks(taskId)
                                    if (calculatedStatus != null) {
                                        formState.status = calculatedStatus
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showConnectionDialog) {
        ConnectionDialog(
            existingConnections = formState.connections,
            searchTasksForConnection = searchTasksForConnection,
            onDismiss = { showConnectionDialog = false },
            onConnectionAdded = { connection ->
                formState.connections += connection
            },
            onCreateNewTask = onCreateNewTaskWithConnection
        )
    }
}

@Composable
private fun DescriptionSection(
    formState: TaskFormState,
    currentSpaceIdPrefix: String?,
    allSpacePrefixes: List<String>,
    connectedTasks: Map<String, Task>,
    onTaskClick: (String) -> Unit
) {
    val descriptionLabel = currentSpaceIdPrefix
        ?.let { "Description (Markdown, use $it-# to reference)" }
        ?: "Description (Markdown)"

    OutlinedTextField(
        value = formState.description,
        onValueChange = { formState.description = it },
        label = { Text(descriptionLabel) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp),
        minLines = 5
    )

    if (formState.description.isNotEmpty()) {
        Text(
            text = "Preview:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SimpleMarkdownText(
            markdown = formState.description,
            allSpacePrefixes = allSpacePrefixes,
            getTaskById = { taskId -> connectedTasks[taskId] },
            onTaskClick = onTaskClick
        )
    }
}

/**
 * Shared task form content used by both TaskDetailScreen (edit mode) and NewTaskScreen
 */
@Composable
fun TaskFormContent(
    formState: TaskFormState,
    taskId: String,
    isNewTask: Boolean,
    prefilledTask: Task? = null,
    prefilledConnection: TaskConnection? = null,
    onTaskClick: (String) -> Unit,
    onCreateNewTaskWithConnection: ((ConnectionType) -> Unit)?,
    // Data providers (instead of repository)
    getTaskById: suspend (String) -> Task?,
    filterTags: suspend (String, Set<String>) -> List<String>,
    filterTasksForSelection: suspend (String) -> List<Task>,
    searchTasksForConnection: suspend (String, Set<String>, ConnectionType, Set<TaskConnection>) -> List<Task>,
    getCalculatedStatusFromSubtasks: suspend (String) -> TaskStatus?,
    currentSpaceIdPrefix: String?,
    allSpacePrefixes: List<String>
) {
    // Load connected tasks asynchronously
    var connectedTasks by remember { mutableStateOf<Map<String, Task>>(emptyMap()) }

    LaunchedEffect(formState.connections) {
        val tasks = mutableMapOf<String, Task>()
        formState.connections.forEach { connection ->
            getTaskById(connection.targetTaskId)?.let { task ->
                tasks[connection.targetTaskId] = task
            }
        }
        connectedTasks = tasks
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isNewTask && prefilledTask != null && prefilledConnection != null) {
            PrefilledConnectionInfo(prefilledTask, prefilledConnection)
        }

        TitleField(formState)
        StatusSection(formState, filterTasksForSelection, getTaskById)
        PriorityField(formState)
        EstimatedTimeField(formState)
        RecurrenceSection(formState, filterTasksForSelection, getTaskById)
        DueDatePicker(formState)
        NotificationsSection(formState)
        TagsSection(formState, filterTags)
        ConnectionsSection(formState, taskId, connectedTasks, searchTasksForConnection, getCalculatedStatusFromSubtasks, onCreateNewTaskWithConnection)
        DescriptionSection(formState, currentSpaceIdPrefix, allSpacePrefixes, connectedTasks, onTaskClick)
    }
}
