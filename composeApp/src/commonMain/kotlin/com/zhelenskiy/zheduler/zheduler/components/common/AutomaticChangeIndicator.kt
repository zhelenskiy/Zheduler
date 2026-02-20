package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.AutomaticChangeReason
import com.zhelenskiy.zheduler.zheduler.Task

/**
 * Display automatic change indicator with magic icon and reason
 */
@Composable
fun AutomaticChangeIndicator(
    reason: AutomaticChangeReason,
    getTaskById: suspend (String) -> Task?,
    onTaskClick: (String) -> Unit
) {
    var relatedTasks by remember { mutableStateOf<Map<String, Task>>(emptyMap()) }

    LaunchedEffect(reason) {
        val tasks = mutableMapOf<String, Task>()
        (reason as? AutomaticChangeReason.UpdatedFromSubtasks)?.relatedTaskIds?.forEach { taskId ->
            getTaskById(taskId)?.let { task ->
                tasks[taskId] = task
            }
        }
        relatedTasks = tasks
    }

    Icon(
        imageVector = Icons.Default.AutoAwesome,
        contentDescription = "Automatic",
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.size(16.dp)
    )

    Text(
        text = reason.text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary
    )

    (reason as? AutomaticChangeReason.UpdatedFromSubtasks)?.relatedTaskIds?.forEach { taskId ->
        val relatedTask = relatedTasks[taskId]
        ConnectedTaskChip(
            task = relatedTask,
            taskId = taskId,
            onClick = { relatedTask?.let { onTaskClick(it.id) } }
        )
    }
}
