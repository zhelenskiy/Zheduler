package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import com.zhelenskiy.zheduler.zheduler.components.common.isEmptyAfterRefresh
import com.zhelenskiy.zheduler.zheduler.components.common.pagingLoadStatus

@Composable
fun ConnectionDialog(
    existingConnections: Set<TaskConnection>,
    searchedTasks: LazyPagingItems<Task>,
    onSearchTasks: (String, Set<String>, ConnectionType, Set<TaskConnection>) -> Unit,
    onDismiss: () -> Unit,
    onConnectionAdded: (TaskConnection) -> Unit,
    onCreateNewTask: ((ConnectionType) -> Unit)?
) {
    var selectedConnectionType by remember { mutableStateOf(ConnectionType.RelatesTo) }
    var searchQuery by remember { mutableStateOf("") }

    val existingTargetIds = existingConnections
        .filter { it.type == selectedConnectionType }
        .map { it.targetTaskId }
        .toSet()

    LaunchedEffect(searchQuery, existingTargetIds, selectedConnectionType, existingConnections) {
        onSearchTasks(
            searchQuery,
            existingTargetIds,
            selectedConnectionType,
            existingConnections
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Connection") },
        text = {
            ConnectionDialogContent(
                selectedConnectionType = selectedConnectionType,
                onConnectionTypeSelected = { selectedConnectionType = it },
                onCreateNewTask = onCreateNewTask,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                filteredTasks = searchedTasks,
                onTaskSelected = { task ->
                    onConnectionAdded(TaskConnection(task.id, selectedConnectionType))
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun ConnectionDialogContent(
    selectedConnectionType: ConnectionType,
    onConnectionTypeSelected: (ConnectionType) -> Unit,
    onCreateNewTask: ((ConnectionType) -> Unit)?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filteredTasks: LazyPagingItems<Task>,
    onTaskSelected: (Task) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ConnectionTypeSelector(
            selectedType = selectedConnectionType,
            onTypeSelected = onConnectionTypeSelected
        )

        HorizontalDivider()

        // Create nested task button (only in edit mode)
        if (onCreateNewTask != null) {
            CreateNestedNewTaskButton(onCreateNewTask, selectedConnectionType)
            HorizontalDivider()
        }

        TaskSearchSection(
            hasCreateNewOption = onCreateNewTask != null,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            filteredTasks = filteredTasks,
            onTaskSelected = onTaskSelected
        )
    }
}

@Composable
private fun ConnectionTypeSelector(
    selectedType: ConnectionType,
    onTypeSelected: (ConnectionType) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Connection Type",
            style = MaterialTheme.typography.labelMedium
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ConnectionType.entries.forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { onTypeSelected(type) },
                    label = { Text(type.displayName) }
                )
            }
        }
    }
}

@Composable
private fun TaskSearchSection(
    hasCreateNewOption: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filteredTasks: LazyPagingItems<Task>,
    onTaskSelected: (Task) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (hasCreateNewOption) "Or connect to existing task" else "Connect to existing task",
            style = MaterialTheme.typography.labelMedium
        )

        TaskSearchField(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange
        )

        TaskSearchResults(
            tasks = filteredTasks,
            searchQuery = searchQuery,
            onTaskSelected = onTaskSelected
        )
    }
}

@Composable
private fun TaskSearchField(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        label = { Text("Search by ID or title") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
    )
}

@Composable
private fun TaskSearchResults(
    tasks: LazyPagingItems<Task>,
    searchQuery: String,
    onTaskSelected: (Task) -> Unit
) {
    LazyColumn(
        modifier = Modifier.heightIn(max = 200.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(count = tasks.itemCount, key = tasks.itemKey { it.id }) { index ->
            val task = tasks[index] ?: return@items
            TaskListItem(
                task = task,
                onClick = { onTaskSelected(task) }
            )
        }

        pagingLoadStatus(tasks)

        if (tasks.isEmptyAfterRefresh) {
            item {
                EmptyTasksMessage(searchQuery = searchQuery)
            }
        }
    }
}

@Composable
private fun TaskListItem(
    task: Task,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.id,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.Add,
                contentDescription = "Add connection",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun EmptyTasksMessage(searchQuery: String) {
    Text(
        text = if (searchQuery.isBlank()) "No tasks available" else "No matching tasks",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun CreateNestedNewTaskButton(
    onCreateNewTask: (ConnectionType) -> Unit,
    selectedConnectionType: ConnectionType
) {
    OutlinedButton(
        onClick = { onCreateNewTask(selectedConnectionType) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Create new task with this connection")
    }
}
