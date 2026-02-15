package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import com.zhelenskiy.zheduler.zheduler.components.common.ConnectedTaskChip
import com.zhelenskiy.zheduler.zheduler.components.common.StatusBadge

@Composable
fun StatusSelectionDialog(
    currentStatus: TaskStatus,
    availableTasks: List<Task>,
    onDismiss: () -> Unit,
    onStatusSelected: (TaskStatus) -> Unit
) {
    var selectedStatusType by remember { mutableStateOf(currentStatus) }
    var blockerTaskIds by remember {
        mutableStateOf(
            (currentStatus as? TaskStatus.Blocked)?.blockerTaskIds ?: emptySet()
        )
    }
    var blockedComment by remember {
        mutableStateOf(
            (currentStatus as? TaskStatus.Blocked)?.comment ?: ""
        )
    }
    var declinedReason by remember {
        mutableStateOf(
            (currentStatus as? TaskStatus.Declined)?.reason ?: ""
        )
    }
    var showBlockerSelection by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Status") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Simple statuses
                listOf(
                    TaskStatus.Open to "Open",
                    TaskStatus.InProgress to "In Progress",
                    TaskStatus.Done to "Done"
                ).forEach { (status, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedStatusType = status }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedStatusType::class == status::class,
                            onClick = { selectedStatusType = status }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(status = status)
                    }
                }

                HorizontalDivider()

                // Blocked status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedStatusType = TaskStatus.Blocked(blockerTaskIds, blockedComment) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedStatusType is TaskStatus.Blocked,
                        onClick = { selectedStatusType = TaskStatus.Blocked(blockerTaskIds, blockedComment) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusBadge(status = TaskStatus.Blocked(emptySet()))
                }

                AnimatedVisibility(visible = selectedStatusType is TaskStatus.Blocked) {
                    Column(modifier = Modifier.padding(start = 40.dp)) {
                        OutlinedButton(
                            onClick = { showBlockerSelection = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select blocking tasks (${blockerTaskIds.size})")
                        }

                        if (blockerTaskIds.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            blockerTaskIds.forEach { taskId ->
                                val task = availableTasks.find { it.id == taskId }
                                ConnectedTaskChip(
                                    task = task,
                                    taskId = taskId,
                                    onRemove = { blockerTaskIds = blockerTaskIds - taskId },
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = blockedComment,
                            onValueChange = {
                                blockedComment = it
                                selectedStatusType = TaskStatus.Blocked(blockerTaskIds, it)
                            },
                            label = { Text("Comment (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            placeholder = { Text("Why is this task blocked?") }
                        )
                    }
                }

                HorizontalDivider()

                // Declined status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedStatusType = TaskStatus.Declined(declinedReason) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedStatusType is TaskStatus.Declined,
                        onClick = { selectedStatusType = TaskStatus.Declined(declinedReason) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusBadge(status = TaskStatus.Declined(""))
                }

                AnimatedVisibility(visible = selectedStatusType is TaskStatus.Declined) {
                    OutlinedTextField(
                        value = declinedReason,
                        onValueChange = {
                            declinedReason = it
                            selectedStatusType = TaskStatus.Declined(it)
                        },
                        label = { Text("Reason for declining") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp),
                        minLines = 2
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalStatus = when (selectedStatusType) {
                        is TaskStatus.Blocked -> TaskStatus.Blocked(blockerTaskIds, blockedComment)
                        is TaskStatus.Declined -> TaskStatus.Declined(declinedReason)
                        else -> selectedStatusType
                    }
                    onStatusSelected(finalStatus)
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    // Blocker task selection dialog
    if (showBlockerSelection) {
        TaskSelectionDialog(
            title = "Select Blocking Tasks",
            availableTasks = availableTasks,
            selectedTaskIds = blockerTaskIds,
            onDismiss = { showBlockerSelection = false },
            onTasksSelected = { taskIds ->
                blockerTaskIds = taskIds
                selectedStatusType = TaskStatus.Blocked(taskIds, blockedComment)
                showBlockerSelection = false
            }
        )
    }
}
