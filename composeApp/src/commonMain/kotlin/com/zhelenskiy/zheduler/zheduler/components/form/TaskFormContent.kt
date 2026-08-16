@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.animation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.paging.compose.LazyPagingItems
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.components.common.ConnectedTaskChip
import com.zhelenskiy.zheduler.zheduler.components.common.TagChip
import com.zhelenskiy.zheduler.zheduler.components.dialogs.*
import com.zhelenskiy.zheduler.zheduler.components.markdown.SimpleMarkdownText
import com.zhelenskiy.zheduler.zheduler.util.TaskStatus
import com.zhelenskiy.zheduler.zheduler.util.formatDueDate
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
        supportingText = if (formState.title.isBlank()) {
            { Text("Title is required") }
        } else null
    )
}

@Composable
private fun StatusSection(
    formState: TaskFormState,
    filteredTasksForSelection: LazyPagingItems<Task>,
    loadedTasks: Map<String, Task>,
    onFilterTasksForSelection: (String) -> Unit,
    onLoadTask: (String) -> Unit
) {
    var showStatusDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .then(
                    if (formState.autoUpdateStatusFromSubtasks) Modifier
                    else Modifier.clickable { showStatusDialog = true }
                )
                .padding(16.dp),
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
                AnimatedVisibility(visible = formState.autoUpdateStatusFromSubtasks) {
                    Text(
                        text = "Automatically chosen based on subtasks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (!formState.autoUpdateStatusFromSubtasks) {
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
            filteredTasks = filteredTasksForSelection,
            loadedTasks = loadedTasks,
            onFilterTasks = onFilterTasksForSelection,
            onLoadTask = onLoadTask,
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
    filteredTasksForSelection: LazyPagingItems<Task>,
    loadedTasks: Map<String, Task>,
    onFilterTasksForSelection: (String) -> Unit,
    onLoadTask: (String) -> Unit
) {
    var editingRuleIndex by remember { mutableStateOf<Int?>(null) }
    val showWarning = formState.autoUpdateStatusFromSubtasks && formState.recurrenceRules.isNotEmpty()

    Column {
        // Warning message when auto-update status is enabled and recurrence rules exist
        AnimatedVisibility(visible = showWarning) {
            Column {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Recurrence rules will not work because auto-update status from subtasks is enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = if (formState.recurrenceRules.isNotEmpty()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = "Recurrence rules:",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { editingRuleIndex = formState.recurrenceRules.size }) {
                        Icon(Icons.Default.Add, contentDescription = "Add recurrence rule")
                    }
                }

                AnimatedContent(
                    targetState = formState.recurrenceRules.size,
                    transitionSpec = { EnterTransition.None togetherWith ExitTransition.None }
                ) {
                    if (it > 0) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(
                                formState.recurrenceRules,
                                key = { index, _ -> formState.recurrenceRuleIds[index] },
                            ) { index, (rule, _) ->
                                RecurrenceRuleItem(
                                    rule = rule,
                                    onEdit = { editingRuleIndex = index },
                                    onDelete = {
                                        formState.removeRecurrenceRule(index)
                                    },
                                    index = index,
                                    onTaskClick = null,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingRuleIndex != null) {
        val index = editingRuleIndex!!
        SingleRecurrenceRuleDialog(
            currentRule = formState.recurrenceRules.getOrNull(index)?.first,
            filteredTasks = filteredTasksForSelection,
            loadedTasks = loadedTasks,
            onFilterTasks = onFilterTasksForSelection,
            onLoadTask = onLoadTask,
            onDismiss = { editingRuleIndex = null },
            onRecurrenceSelected = { rule ->
                if (rule != null) {
                    val currentState = formState.recurrenceRules.getOrNull(index)?.second ?: RecurrenceState()
                    val nextOccurrence = RecurrenceCalculator.calculateNextOccurrence(rule, currentState)
                    val newEntry = rule to currentState.copy(nextOccurrenceDate = nextOccurrence)
                    formState.setRecurrenceRule(index, newEntry)
                }
                editingRuleIndex = null
            }
        )
    }
}

@Composable
fun RecurrenceRuleItem(
    rule: RecurrenceRule,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    index: Int,
    onTaskClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val isTerminated = rule.isTerminated()
    val titleStyle = MaterialTheme.typography.titleMedium.copy(
        textDecoration = if (isTerminated) TextDecoration.LineThrough else TextDecoration.None
    )
    val detailsTextDecoration = if (isTerminated) TextDecoration.LineThrough else TextDecoration.None
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Rule #${index + 1}",
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.primary
                )
                RecurrenceRuleDetails(
                    rule = rule,
                    onTaskClick = onTaskClick,
                    textDecoration = detailsTextDecoration
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onEdit != null) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun RecurrenceRuleDetails(
    rule: RecurrenceRule,
    onTaskClick: ((String) -> Unit)?,
    textDecoration: TextDecoration = TextDecoration.None,
) {
    val style = MaterialTheme.typography.bodySmall.copy(textDecoration = textDecoration)
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val timeRecurrenceTriggerString = rule.timeRecurrenceTrigger?.let { timeTrigger ->
            when (timeTrigger) {
                is RecurrenceTrigger.AfterTimeout -> timeTrigger.period
                    ?.let { "Every ${it.toFullString()} since ${formatDate(timeTrigger.firstOccurrence)}" }
                    ?: "At ${formatDate(timeTrigger.firstOccurrence)}"

                is RecurrenceTrigger.AtFixedPoints -> timeTrigger.pattern.toFullString() + timeTrigger.timezoneSuffix()
            }
        }
        val badgeModifier = Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.secondary,
            shape = MaterialTheme.shapes.small,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            if (timeRecurrenceTriggerString != null) {
                if (rule.statusChangeTrigger != null) {
                    Text("$timeRecurrenceTriggerString on ", style = style)
                } else {
                    Text(timeRecurrenceTriggerString, style = style)
                }
            } else if (rule.statusChangeTrigger != null) {
                Text("On ", style = style)
            }
            rule.statusChangeTrigger?.let { trigger ->
                Text(if (trigger.requiredStatuses.size == 1) "status " else "statuses ", style = style)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    trigger.requiredStatuses.forEach {
                        TaskStatus(
                            status = it,
                            blockerTasks = null,
                            onBlockerTaskClick = onTaskClick,
                            modifier = Modifier,
                            badgeModifier = badgeModifier,
                            blockerTaskModifier = badgeModifier,
                            textStyle = style,
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            Text("Reset to ", style = style)
            TaskStatus(
                status = rule.resetToStatus,
                blockerTasks = null,
                onBlockerTaskClick = onTaskClick,
                modifier = Modifier,
                badgeModifier = badgeModifier,
                blockerTaskModifier = badgeModifier,
                textStyle = style,
            )
        }
        Text(rule.termination.toFullString(), style = style)
    }
}

private fun RecurrenceTrigger.AtFixedPoints.timezoneSuffix(): String = when (val tz = timezone) {
    is RecurrenceTimeZone.SystemDefault -> ""
    is RecurrenceTimeZone.Specific -> " (${tz.zoneId})"
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
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { formState.addNotification() }) {
                        Icon(Icons.Default.Add, contentDescription = "Add notification")
                    }
                }

                // Keyed on how many there are, as the recurrence section above is, and not on the
                // list itself: every keystroke makes a new list, which tore down and rebuilt the
                // rows — disposing the field being typed into, so it lost focus each character.
                AnimatedContent(
                    targetState = formState.notifications.size,
                    transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                ) { _ ->
                    val notifications = formState.notifications
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            formState.notifications,
                            key = { index, _ -> formState.notificationIds[index] },
                        ) { index, notification ->
                            Row(
                                modifier = Modifier.fillMaxWidth().animateItem(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = notification,
                                    onValueChange = { newValue ->
                                        formState.updateNotification(index, newValue)
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
                                    formState.removeNotification(index)
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
    filteredTags: LazyPagingItems<String>,
    onFilterTags: (String, Set<String>) -> Unit
) {
    var showTagDialog by remember { mutableStateOf(false) }

    AnimatedContent(
        formState.tags,
        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None }) { tags ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Tags:",
                    style = MaterialTheme.typography.titleSmall
                )

                if (tags.isEmpty()) {
                    Text(
                        text = "No tags selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tags.forEach { tag ->
                            TagChip(
                                tag = tag,
                                onRemove = { formState.tags = formState.tags.removing(tag) }
                            )
                        }
                    }
                }

                IconButton(onClick = { showTagDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add tag")
                }
            }
        }
    }

    if (showTagDialog) {
        TagSelectionDialog(
            selectedTags = formState.tags,
            filteredTags = filteredTags,
            onFilterTags = onFilterTags,
            onDismiss = { showTagDialog = false },
            onTagSelected = { tag ->
                formState.tags = formState.tags.adding(tag)
                showTagDialog = false
            }
        )
    }
}

@Composable
private fun ConnectionsSection(
    formState: TaskFormState,
    loadedTasks: Map<String, Task>,
    searchedTasksForConnection: LazyPagingItems<Task>,
    onSearchTasksForConnection: (String, Set<String>, ConnectionType, Set<TaskConnection>) -> Unit,
    onCreateNewTaskWithConnection: ((ConnectionType) -> Unit)?
) {
    var showConnectionDialog by remember { mutableStateOf(false) }

    AnimatedContent(
        formState.connections,
        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None }) { connections ->
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
                    if (connections.isEmpty()) {
                        Text(
                            text = "No connections",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showConnectionDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add connection")
                    }
                }

                if (connections.isNotEmpty()) {
                    ConnectionType.entries.forEach { connectionType ->
                        val connectionsForType = connections.filter { it.type == connectionType }
                        if (connectionsForType.isNotEmpty()) {
                            Text(
                                text = connectionType.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            FlowRow(
                                itemVerticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                connectionsForType.forEach { connection ->
                                    val connectedTask = loadedTasks[connection.targetTaskId]
                                    ConnectedTaskChip(
                                        task = connectedTask,
                                        taskId = connection.targetTaskId,
                                        onRemove = { formState.connections = formState.connections.removing(connection) },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }

                // Auto-update status from subtasks toggle
                val hasSubtasks = connections.any { it.type == ConnectionType.ParentOf }
                if (hasSubtasks) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp).padding(end = 12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
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
                            }
                        )
                    }
                }
            }
        }
    }

    if (showConnectionDialog) {
        ConnectionDialog(
            existingConnections = formState.connections,
            searchedTasks = searchedTasksForConnection,
            onSearchTasks = onSearchTasksForConnection,
            onDismiss = { showConnectionDialog = false },
            onConnectionAdded = { connection ->
                formState.connections = formState.connections.adding(connection)
            },
            onCreateNewTask = onCreateNewTaskWithConnection?.let { callback ->
                { connectionType ->
                    showConnectionDialog = false
                    callback(connectionType)
                }
            }
        )
    }
}

@Composable
private fun DescriptionSection(
    formState: TaskFormState,
    taskId: String?,
    currentSpaceIdPrefix: String?,
    allSpacePrefixes: List<String>,
    connectedTasks: Map<String, Task>,
    onTaskClick: (String) -> Unit
) {
    // No longer says "Markdown": the format is named by the editor picker, and the rich
    // editor is WYSIWYG even though Markdown is still what gets stored.
    val descriptionLabel = currentSpaceIdPrefix
        ?.let { "Description (use $it-# to reference)" }
        ?: "Description"

    TaskDescriptionField(
        markdown = formState.description,
        onMarkdownChange = { formState.description = it },
        label = descriptionLabel,
        taskId = taskId,
        modifier = Modifier.fillMaxWidth(),
        preview = {
            DescriptionPreview(
                markdown = formState.description,
                allSpacePrefixes = allSpacePrefixes,
                connectedTasks = connectedTasks,
                onTaskClick = onTaskClick
            )
        }
    )
}

/**
 * How the description will render once saved — including `ZH-12` references as links,
 * which only the read-only renderer resolves.
 */
@Composable
private fun DescriptionPreview(
    markdown: String,
    allSpacePrefixes: List<String>,
    connectedTasks: Map<String, Task>,
    onTaskClick: (String) -> Unit
) {
    AnimatedVisibility(
        visible = markdown.isNotBlank(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Preview:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SimpleMarkdownText(
                markdown = markdown,
                allSpacePrefixes = allSpacePrefixes,
                getTaskById = { taskId -> connectedTasks[taskId] },
                onTaskClick = onTaskClick,
            )
        }
    }
}

/**
 * Shared task form content used by both TaskDetailScreen (edit mode) and NewTaskScreen
 */
@Composable
fun TaskFormContent(
    formState: TaskFormState,
    isNewTask: Boolean,
    prefilledTask: Task? = null,
    prefilledConnection: TaskConnection? = null,
    onTaskClick: (String) -> Unit,
    onCreateNewTaskWithConnection: ((ConnectionType) -> Unit)?,
    // State-based data from container
    loadedTasks: Map<String, Task>,
    filteredTags: LazyPagingItems<String>,
    filteredTasksForSelection: LazyPagingItems<Task>,
    searchedTasksForConnection: LazyPagingItems<Task>,
    // Callbacks to trigger intents
    onLoadTask: (String) -> Unit,
    onFilterTags: (String, Set<String>) -> Unit,
    onFilterTasksForSelection: (String) -> Unit,
    onSearchTasksForConnection: (String, Set<String>, ConnectionType, Set<TaskConnection>) -> Unit,
    currentSpaceIdPrefix: String?,
    allSpacePrefixes: List<String>,
    /** Null for a task that does not exist yet; its editor choice is not remembered. */
    taskId: String?
) {
    // Load connected tasks when connections change
    LaunchedEffect(formState.connections) {
        formState.connections.forEach { connection ->
            if (connection.targetTaskId !in loadedTasks) {
                onLoadTask(connection.targetTaskId)
            }
        }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isNewTask && prefilledTask != null && prefilledConnection != null) {
            PrefilledConnectionInfo(prefilledTask, prefilledConnection)
        }

        TitleField(formState)
        StatusSection(
            formState,
            filteredTasksForSelection,
            loadedTasks,
            onFilterTasksForSelection,
            onLoadTask
        )
        PriorityField(formState)
        EstimatedTimeField(formState)
        RecurrenceSection(
            formState,
            filteredTasksForSelection,
            loadedTasks,
            onFilterTasksForSelection,
            onLoadTask
        )
        DueDatePicker(formState)
        NotificationsSection(formState)
        TagsSection(formState, filteredTags, onFilterTags)
        ConnectionsSection(
            formState,
            loadedTasks,
            searchedTasksForConnection,
            onSearchTasksForConnection,
            onCreateNewTaskWithConnection
        )
        DescriptionSection(formState, taskId, currentSpaceIdPrefix, allSpacePrefixes, loadedTasks, onTaskClick)
    }
}
