@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.common.ConnectedTaskChip
import com.zhelenskiy.zheduler.zheduler.components.common.StatusBadge
import com.zhelenskiy.zheduler.zheduler.components.common.TagChip
import com.zhelenskiy.zheduler.zheduler.components.dialogs.ConnectionDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DatePickerDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.RecurrenceDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.StatusSelectionDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.TagSelectionDialog
import com.zhelenskiy.zheduler.zheduler.components.markdown.SimpleMarkdownText
import com.zhelenskiy.zheduler.zheduler.util.formatDueDate
import com.zhelenskiy.zheduler.zheduler.parseCompactTimeToPeriod
import kotlin.time.ExperimentalTime

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
    showTagDialog: Boolean,
    onShowTagDialog: (Boolean) -> Unit,
    showDatePicker: Boolean,
    onShowDatePicker: (Boolean) -> Unit,
    showStatusDialog: Boolean,
    onShowStatusDialog: (Boolean) -> Unit,
    showConnectionDialog: Boolean,
    onShowConnectionDialog: (Boolean) -> Unit,
    showRecurrenceDialog: Boolean,
    onShowRecurrenceDialog: (Boolean) -> Unit,
    onCreateNewTaskWithConnection: ((ConnectionType) -> Unit)?,
    // Data providers (instead of repository)
    getTaskById: suspend (String) -> Task?,
    getAllTags: suspend () -> Set<String>,
    filterTags: suspend (String, Set<String>) -> List<String>,
    filterTasksForSelection: suspend (String) -> List<Task>,
    searchTasksForConnection: suspend (String, Set<String>, ConnectionType, Set<TaskConnection>) -> List<Task>,
    getCalculatedStatusFromSubtasks: suspend (String) -> TaskStatus?,
    currentSpaceIdPrefix: String?,
    allSpacePrefixes: List<String>
) {
    val coroutineScope = rememberCoroutineScope()

    // Load connected tasks asynchronously
    var connectedTasks by remember { mutableStateOf<Map<String, Task>>(emptyMap()) }
    var allTags by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(formState.connections) {
        val tasks = mutableMapOf<String, Task>()
        formState.connections.forEach { connection ->
            getTaskById(connection.targetTaskId)?.let { task ->
                tasks[connection.targetTaskId] = task
            }
        }
        connectedTasks = tasks
    }

    LaunchedEffect(Unit) {
        allTags = getAllTags()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Show prefilled connection info (for new task creation)
        if (isNewTask && prefilledTask != null && prefilledConnection != null) {
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

        // Title field
        OutlinedTextField(
            value = formState.title,
            onValueChange = { formState.title = it },
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = formState.title.isBlank(),
            supportingText = if (formState.title.isBlank()) {
                { Text("Title is required") }
            } else null
        )

        // Status selection (hidden when autoUpdateStatusFromSubtasks is enabled)
        AnimatedVisibility(visible = !formState.autoUpdateStatusFromSubtasks) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.clickable { onShowStatusDialog(true) }.padding(16.dp),
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
                            StatusBadge(status = formState.status)
                        }

                        when (val s = formState.status) {
                            is TaskStatus.Blocked -> {
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (s.blockerTaskIds.isNotEmpty()) {
                                        Text(
                                            text = "Blocked by: ${s.blockerTaskIds.joinToString(", ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (s.comment.isNotBlank()) {
                                        Text(
                                            text = if (s.blockerTaskIds.isEmpty()) s.comment else "Comment: ${s.comment}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            is TaskStatus.Declined -> {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Reason: ${s.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            else -> {}
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

        // Priority field
        OutlinedTextField(
            value = formState.priority,
            onValueChange = { formState.priority = it },
            label = { Text("Priority (1-100)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            supportingText = {
                Text(
                    text = if (formState.priority.isNotEmpty() && formState.priority.toIntOrNull()
                            ?.let { it in 1..100 } != true
                    ) {
                        "Invalid priority. Must be between 1 and 100"
                    } else {
                        "Leave empty for no priority"
                    }
                )
            },
            isError = formState.priority.isNotEmpty() && formState.priority.toIntOrNull()
                ?.let { it in 1..100 } != true
        )

        // Estimated time field
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

        // Due date picker
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { onShowDatePicker(true) },
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

        // Recurrence section
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowRecurrenceDialog(true) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = if (formState.recurrenceRule != null)
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Repeat",
                        style = MaterialTheme.typography.titleSmall
                    )
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

        // Notifications section (only show if there's a due date)
        AnimatedVisibility(visible = formState.dueDate != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(start = 16.dp, bottom = if (formState.notifications.isEmpty()) 4.dp else 16.dp, top = 4.dp, end = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Notifications:",
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (formState.notifications.isEmpty()) {
                            Text(
                                text = "No notifications",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )
                        }
                        IconButton(onClick = {
                            formState.notifications = formState.notifications + ""
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add notification")
                        }
                    }

                    formState.notifications.forEachIndexed { index, notification ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = notification,
                                onValueChange = { newValue ->
                                    formState.notifications = formState.notifications.toMutableList().apply {
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
                                } else null
                            )
                            IconButton(onClick = {
                                formState.notifications = formState.notifications.toMutableList().apply {
                                    removeAt(index)
                                }
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Remove notification")
                            }
                        }
                        if (index != formState.notifications.lastIndex) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        // Tags section
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
                                onRemove = { formState.tags = formState.tags - tag }
                            )
                        }
                    }
                }

                IconButton(onClick = { onShowTagDialog(true) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add tag")
                }
            }
        }

        // Connections section
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
                    IconButton(onClick = { onShowConnectionDialog(true) }) {
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
                                    onRemove = { formState.connections = formState.connections - connection },
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

        // Description field with preview
        val descriptionLabel = currentSpaceIdPrefix?.let {
            "Description (Markdown, use $it-# to reference)"
        } ?: "Description (Markdown)"
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

    // Dialogs
    if (showTagDialog) {
        TagSelectionDialog(
            selectedTags = formState.tags,
            filterTags = filterTags,
            onDismiss = { onShowTagDialog(false) },
            onTagSelected = { tag ->
                formState.tags += tag
                onShowTagDialog(false)
            }
        )
    }

    if (showDatePicker) {
        DatePickerDialog(
            currentDate = formState.dueDate,
            onDismiss = { onShowDatePicker(false) },
            onDateSelected = { date ->
                formState.dueDate = date
                onShowDatePicker(false)
            }
        )
    }

    if (showStatusDialog) {
        StatusSelectionDialog(
            currentStatus = formState.status,
            filterTasks = filterTasksForSelection,
            getTaskById = getTaskById,
            onDismiss = { onShowStatusDialog(false) },
            onStatusSelected = { status ->
                formState.status = status
                onShowStatusDialog(false)
            }
        )
    }

    if (showConnectionDialog) {
        ConnectionDialog(
            existingConnections = formState.connections,
            searchTasksForConnection = searchTasksForConnection,
            onDismiss = { onShowConnectionDialog(false) },
            onConnectionAdded = { connection ->
                formState.connections += connection
            },
            onCreateNewTask = onCreateNewTaskWithConnection
        )
    }

    if (showRecurrenceDialog) {
        RecurrenceDialog(
            currentRule = formState.recurrenceRule,
            filterTasks = filterTasksForSelection,
            getTaskById = getTaskById,
            onDismiss = { onShowRecurrenceDialog(false) },
            onRecurrenceSelected = { rule ->
                formState.recurrenceRule = rule
                onShowRecurrenceDialog(false)
            }
        )
    }
}
