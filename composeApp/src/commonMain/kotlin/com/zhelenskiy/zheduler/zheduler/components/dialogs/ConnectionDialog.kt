package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskConnection

@Composable
fun ConnectionDialog(
    currentTaskId: String,
    existingConnections: Set<TaskConnection>,
    availableTasks: List<Task>,
    wouldCreateCycle: suspend (String, String, ConnectionType, Set<TaskConnection>) -> Boolean,
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

    // Filter tasks asynchronously due to suspend wouldCreateCycle
    var filteredTasks by remember { mutableStateOf<List<Task>>(emptyList()) }

    LaunchedEffect(searchQuery, availableTasks, existingTargetIds, selectedConnectionType, existingConnections) {
        val base = availableTasks.filter { task ->
            task.id !in existingTargetIds &&
            !wouldCreateCycle(currentTaskId, task.id, selectedConnectionType, existingConnections)
        }
        filteredTasks = if (searchQuery.isBlank()) base
        else base.filter {
            it.id.contains(searchQuery, ignoreCase = true) ||
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Connection") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Connection type selector
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
                            selected = selectedConnectionType == type,
                            onClick = { selectedConnectionType = type },
                            label = { Text(type.displayName) }
                        )
                    }
                }

                HorizontalDivider()

                // Create new task button (only in edit mode)
                if (onCreateNewTask != null) {
                    OutlinedButton(
                        onClick = { onCreateNewTask(selectedConnectionType) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create new task with this connection")
                    }

                    HorizontalDivider()
                }

                // Search existing tasks
                Text(
                    text = if (onCreateNewTask != null) "Or connect to existing task" else "Connect to existing task",
                    style = MaterialTheme.typography.labelMedium
                )
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search by ID or title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
                
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredTasks, key = { it.id }) { task ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onConnectionAdded(TaskConnection(task.id, selectedConnectionType))
                                },
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
                    
                    if (filteredTasks.isEmpty()) {
                        item {
                            Text(
                                text = if (searchQuery.isBlank()) "No tasks available" else "No matching tasks",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {}
    )
}
