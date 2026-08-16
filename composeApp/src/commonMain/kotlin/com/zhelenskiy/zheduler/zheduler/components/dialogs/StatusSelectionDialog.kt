package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.animation.AnimatedContent
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.serialization.json.Json
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.paging.compose.LazyPagingItems
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import com.zhelenskiy.zheduler.zheduler.TaskStatus.Blocked
import com.zhelenskiy.zheduler.zheduler.TaskStatus.Declined
import com.zhelenskiy.zheduler.zheduler.TaskStatus.Done
import com.zhelenskiy.zheduler.zheduler.TaskStatus.InProgress
import com.zhelenskiy.zheduler.zheduler.TaskStatus.Open
import com.zhelenskiy.zheduler.zheduler.components.common.ConnectedTaskChip
import com.zhelenskiy.zheduler.zheduler.components.common.StatusBadge

@Composable
fun StatusSelectionDialog(
    currentStatus: TaskStatus,
    filteredTasks: LazyPagingItems<Task>,
    loadedTasks: Map<String, Task>,
    onFilterTasks: (String) -> Unit,
    onLoadTask: (String) -> Unit,
    onDismiss: () -> Unit,
    onStatusSelected: (TaskStatus) -> Unit
) {
    // Saved: the status picked, and any blocker comment or decline reason typed alongside it, are
    // gone the moment the activity is recreated, and nothing has reached the form yet.
    var selectedStatusType by rememberSaveable(stateSaver = TaskStatusSaver) { mutableStateOf(currentStatus) }
    val lastSelectedForType = remember { SnapshotStateMap<String, TaskStatus>() }
    LaunchedEffect(selectedStatusType) {
        lastSelectedForType[selectedStatusType.displayName] = selectedStatusType
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Status") },
        text = {
            StatusSelectionDialogContent(
                selectedStatusType = selectedStatusType,
                onStatusTypeSelected = { selectedStatusType = it },
                filteredTasks = filteredTasks,
                loadedTasks = loadedTasks,
                onFilterTasks = onFilterTasks,
                onLoadTask = onLoadTask,
                lastSelectedForType = lastSelectedForType,
            )
        },
        confirmButton = {
            TextButton(onClick = { onStatusSelected(selectedStatusType) }) {
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

@Composable
private fun StatusSelectionDialogContent(
    selectedStatusType: TaskStatus,
    onStatusTypeSelected: (TaskStatus) -> Unit,
    filteredTasks: LazyPagingItems<Task>,
    loadedTasks: Map<String, Task>,
    onFilterTasks: (String) -> Unit,
    onLoadTask: (String) -> Unit,
    lastSelectedForType: Map<String, TaskStatus>,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SimpleStatusOptions(
            selectedStatusType = selectedStatusType,
            onStatusTypeSelected = onStatusTypeSelected,
        )

        HorizontalDivider()

        BlockedStatusOption(
            selectedStatusType = selectedStatusType,
            onStatusTypeSelected = onStatusTypeSelected,
            filteredTasks = filteredTasks,
            loadedTasks = loadedTasks,
            onFilterTasks = onFilterTasks,
            onLoadTask = onLoadTask,
            lastSelectedForType = lastSelectedForType,
        )

        HorizontalDivider()

        DeclinedStatusOption(
            selectedStatusType = selectedStatusType,
            onStatusTypeSelected = onStatusTypeSelected,
            lastSelectedForType = lastSelectedForType,
        )
    }
}

@Composable
private fun SimpleStatusOptions(
    selectedStatusType: TaskStatus,
    onStatusTypeSelected: (TaskStatus) -> Unit
) {
    for (status in listOf(Open, InProgress, Done)) {
        StatusRadioOption(
            status = status,
            isSelected = selectedStatusType::class == status::class,
            onSelected = { onStatusTypeSelected(status) }
        )
    }
}

@Composable
private fun StatusRadioOption(
    status: TaskStatus,
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .fillMaxWidth()
            .clickable(onClick = onSelected)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelected
        )
        Spacer(modifier = Modifier.width(8.dp))
        StatusBadge(status = status)
    }
}

@Composable
private fun ColumnScope.BlockedStatusOption(
    selectedStatusType: TaskStatus,
    onStatusTypeSelected: (TaskStatus) -> Unit,
    filteredTasks: LazyPagingItems<Task>,
    loadedTasks: Map<String, Task>,
    onFilterTasks: (String) -> Unit,
    onLoadTask: (String) -> Unit,
    lastSelectedForType: Map<String, TaskStatus>,
) {
    var showBlockerSelection by remember { mutableStateOf(false) }
    val status = selectedStatusType as? Blocked
        ?: lastSelectedForType[Blocked(persistentSetOf()).displayName] as? Blocked
        ?: Blocked(persistentSetOf())

    StatusRadioOption(
        status = status,
        isSelected = selectedStatusType is Blocked,
        onSelected = { onStatusTypeSelected(status) }
    )

    AnimatedVisibility(visible = selectedStatusType is Blocked) {
        BlockedStatusDetails(
            blockerTaskIds = status.blockerTaskIds,
            onBlockerTaskIdsChange = { onStatusTypeSelected(status.copy(blockerTaskIds = it)) },
            blockedComment = status.comment,
            onBlockedCommentChange = {
                onStatusTypeSelected(status.copy(comment = it))
            },
            loadedTasks = loadedTasks,
            onLoadTask = onLoadTask,
            onShowBlockerSelection = { showBlockerSelection = true }
        )
    }

    if (showBlockerSelection) {
        TaskSelectionDialog(
            title = "Select Blocking Tasks",
            filteredTasks = filteredTasks,
            onFilterTasks = onFilterTasks,
            selectedTaskIds = status.blockerTaskIds,
            onDismiss = { showBlockerSelection = false },
            onTasksSelected = { taskIds ->
                onStatusTypeSelected(status.copy(blockerTaskIds = taskIds))
                showBlockerSelection = false
            }
        )
    }
}

@Composable
private fun BlockedStatusDetails(
    blockerTaskIds: PersistentSet<String>,
    onBlockerTaskIdsChange: (PersistentSet<String>) -> Unit,
    blockedComment: String,
    onBlockedCommentChange: (String) -> Unit,
    loadedTasks: Map<String, Task>,
    onLoadTask: (String) -> Unit,
    onShowBlockerSelection: () -> Unit
) {
    Column(modifier = Modifier.padding(start = 40.dp)) {
        OutlinedButton(
            onClick = onShowBlockerSelection,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select blocking tasks (${blockerTaskIds.size})")
        }

        if (blockerTaskIds.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            BlockerTasksList(
                blockerTaskIds = blockerTaskIds,
                loadedTasks = loadedTasks,
                onLoadTask = onLoadTask,
                onRemoveTask = { taskId ->
                    onBlockerTaskIdsChange(blockerTaskIds.removing(taskId))
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = blockedComment,
            onValueChange = onBlockedCommentChange,
            label = { Text("Comment (optional)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            placeholder = { Text("Why is this task blocked?") }
        )
    }
}

@Composable
private fun BlockerTasksList(
    blockerTaskIds: PersistentSet<String>,
    loadedTasks: Map<String, Task>,
    onLoadTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit
) {
    val items = blockerTaskIds.toList()

    // Load tasks that aren't already loaded
    LaunchedEffect(blockerTaskIds) {
        blockerTaskIds.forEach { taskId ->
            if (taskId !in loadedTasks) {
                onLoadTask(taskId)
            }
        }
    }

    AnimatedContent(items, transitionSpec = { EnterTransition.None togetherWith ExitTransition.None }) {
        FlowRow(
            modifier = Modifier.heightIn(max = 200.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { taskId ->
                ConnectedTaskChip(
                    task = loadedTasks[taskId],
                    taskId = taskId,
                    onRemove = { onRemoveTask(taskId) },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.DeclinedStatusOption(
    selectedStatusType: TaskStatus,
    onStatusTypeSelected: (TaskStatus) -> Unit,
    lastSelectedForType: Map<String, TaskStatus>,
) {
    val status = selectedStatusType as? Declined
        ?: lastSelectedForType[Declined("").displayName] as? Declined
        ?: Declined("")

    StatusRadioOption(
        status = status,
        isSelected = selectedStatusType is Declined,
        onSelected = { onStatusTypeSelected(status) }
    )

    AnimatedVisibility(visible = selectedStatusType is Declined) {
        OutlinedTextField(
            value = status.reason,
            onValueChange = { onStatusTypeSelected(Declined(it)) },
            label = { Text("Reason for declining") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp),
            minLines = 2
        )
    }
}

/**
 * A [TaskStatus] as saved state, through the same JSON the database stores.
 *
 * A status carries structure — the tasks a Blocked one waits on, the reason a Declined one was
 * refused — so it is not one of the handful of types a platform can save on its own.
 */
private val TaskStatusSaver: Saver<TaskStatus, Any> = listSaver(
    save = { listOf(Json.encodeToString(it)) },
    restore = { saved -> runCatching { Json.decodeFromString<TaskStatus>(saved[0] as String) }.getOrNull() },
)
