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
import com.zhelenskiy.zheduler.zheduler.AutomaticChangeReason.UpdatedFromSubtasks
import com.zhelenskiy.zheduler.zheduler.Task

/**
 * Display automatic change indicator with magic icon and reason
 */
@Composable
fun AutomaticChangeIndicator(
    reason: AutomaticChangeReason,
    loadedTasks: Map<String, Task>,
    loadTask: (String) -> Unit,
    onTaskClick: (String) -> Unit
) {
    val relatedTaskIds = (reason as? UpdatedFromSubtasks)?.relatedTaskIds ?: emptyList()

    LaunchedEffect(relatedTaskIds) {
        relatedTaskIds.forEach { loadTask(it) }
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

    relatedTaskIds.forEach { taskId ->
        ConnectedTaskChip(
            task = loadedTasks[taskId],
            taskId = taskId,
            // Opened whether or not the task could be loaded. A history entry outlives the task it
            // names — that is rather the point of a history — and a chip that answers a tap with
            // nothing at all reads as a broken app. The detail screen says "This task no longer
            // exists", which is the truth and is what the blocker chips and the references in a
            // description already do.
            onClick = { onTaskClick(taskId) }
        )
    }
}
