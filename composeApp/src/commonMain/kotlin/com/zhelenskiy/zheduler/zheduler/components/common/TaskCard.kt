@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.RecurrencePeriod
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import com.zhelenskiy.zheduler.zheduler.TaskWithTotals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Composable
fun TaskCard(
    taskWithTotals: TaskWithTotals,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val task = taskWithTotals.task

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Main content column
            TaskOverview(task, taskWithTotals)

            // Action buttons on the right
            Row {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

@Composable
private fun RowScope.TaskOverview(
    task: Task,
    taskWithTotals: TaskWithTotals
) {
    val isDone = task.status is TaskStatus.Done
    val isDeclined = task.status is TaskStatus.Declined
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TaskTitle(task, isDone, isDeclined)

        Spacer(modifier = Modifier.height(4.dp))

        TaskStatusAndInfoRow(taskWithTotals, task)

        TagsRow(task)
    }
}

@Composable
private fun TaskStatusAndInfoRow(
    taskWithTotals: TaskWithTotals,
    task: Task
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (taskWithTotals.isMissed(Clock.System.now())) {
            MissedBadge()
        }
        StatusBadge(status = task.status)

        taskWithTotals.totalPriority?.let { priority ->
            PriorityBadge(
                priority = priority,
                isTotal = task.priority != priority
            )
        }

        taskWithTotals.totalDueDate?.let { dueDate ->
            DueDateBadge(
                dueDate = dueDate,
                isTotal = task.dueDate != dueDate
            )
        }

        task.estimatedTime?.let { period ->
            EstimatedTimeIndicator(period)
        }

        if (task.connections.isNotEmpty()) {
            ConnectionsIndicator(task)
        }

        if (task.notifications.isNotEmpty()) {
            NotificationIndicator(task)
        }

        if (task.isRecurring) {
            RecurringIndicator(task)
        }
    }
}

@Composable
private fun TaskTitle(task: Task, isDone: Boolean, isDeclined: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Task ID badge
        TaskIdBadge(task)
        Text(
            text = task.title,
            style = MaterialTheme.typography.titleMedium,
            textDecoration = if (isDone || isDeclined) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
private fun TaskIdBadge(task: Task) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = task.id,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun EstimatedTimeIndicator(period: RecurrencePeriod) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = "Estimated time",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = period.toBriefString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun ConnectionsIndicator(task: Task) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Link,
            contentDescription = "Connections",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${task.connections.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NotificationIndicator(task: Task) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Notifications,
            contentDescription = "Notifications",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (task.notifications.size > 1) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${task.notifications.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecurringIndicator(task: Task) {
    if (task.recurrenceRules.isNotEmpty()) {
        task.recurrenceRules.forEach { (rule, _) ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Recurring",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = rule.toBriefString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun TagsRow(task: Task) {
    if (task.tags.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            task.tags.forEach { tag ->
                TagChip(tag = tag)
            }
        }
    }
}
