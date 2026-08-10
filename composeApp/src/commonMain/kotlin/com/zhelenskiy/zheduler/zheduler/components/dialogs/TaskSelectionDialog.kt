package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.Task
import kotlinx.collections.immutable.PersistentSet

@Composable
fun TaskSelectionDialog(
    title: String,
    filteredTasks: List<Task>,
    onFilterTasks: (String) -> Unit,
    selectedTaskIds: PersistentSet<String>,
    onDismiss: () -> Unit,
    onTasksSelected: (PersistentSet<String>) -> Unit
) {
    var currentSelection by remember { mutableStateOf(selectedTaskIds) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        onFilterTasks(searchQuery)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search by ID or title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )

                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredTasks, key = { it.id }) { task ->
                        Row(
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.medium)
                                .fillMaxWidth()
                                .clickable {
                                    currentSelection = if (task.id in currentSelection) {
                                        currentSelection.removing(task.id)
                                    } else {
                                        currentSelection.adding(task.id)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = task.id in currentSelection,
                                onCheckedChange = {
                                    currentSelection = if (it) {
                                        currentSelection.adding(task.id)
                                    } else {
                                        currentSelection.removing(task.id)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
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
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onTasksSelected(currentSelection) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
