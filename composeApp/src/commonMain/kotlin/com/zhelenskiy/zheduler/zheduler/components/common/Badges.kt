@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.Priority
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import com.zhelenskiy.zheduler.zheduler.util.formatDueDate
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Composable
private fun Badge(
    color: Color,
    icon: ImageVector,
    text: String,
    contentDescription: String = text,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(14.dp),
                tint = color
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

val TaskStatus.icon
        get() = when (this) {
            is TaskStatus.Open -> Icons.Default.RadioButtonUnchecked
            is TaskStatus.Blocked -> Icons.Default.Block
            is TaskStatus.InProgress -> Icons.Default.PlayArrow
            is TaskStatus.Done -> Icons.Default.CheckCircle
            is TaskStatus.Declined -> Icons.Default.Cancel
        }

@Composable
fun StatusBadge(status: TaskStatus, modifier: Modifier = Modifier) {
    Badge(color = status.color, icon = status.icon, text = status.displayName, modifier = modifier)
}

val TaskStatus.color: Color
    @Composable
    get() {
        val color = when (this) {
            is TaskStatus.Open -> MaterialTheme.colorScheme.primary
            is TaskStatus.Blocked -> MaterialTheme.colorScheme.tertiary
            is TaskStatus.InProgress -> MaterialTheme.colorScheme.secondary
            is TaskStatus.Done -> MaterialTheme.colorScheme.primary
            is TaskStatus.Declined -> MaterialTheme.colorScheme.outline
        }
        return color
    }

@Composable
fun MissedBadge() {
    Badge(
        color = MaterialTheme.colorScheme.error,
        icon = Icons.Default.ErrorOutline,
        text = "Missed"
    )
}

@Composable
fun PriorityBadge(priority: Priority, isTotal: Boolean) {
    val color = when {
        priority.value >= 75 -> MaterialTheme.colorScheme.error
        priority.value >= 50 -> MaterialTheme.colorScheme.tertiary
        priority.value >= 25 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }

    Badge(
        color = color,
        icon = Icons.Default.Flag,
        text = if (isTotal) "${priority.value}*" else "${priority.value}",
        contentDescription = "Priority"
    )
}

@Composable
fun DueDateBadge(dueDate: Instant, isTotal: Boolean) {
    val now = Clock.System.now()
    val isOverdue = dueDate < now
    val color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    val dateText = formatDueDate(dueDate)

    Badge(
        color = color,
        icon = Icons.Default.Schedule,
        text = if (isTotal) "$dateText*" else dateText,
        contentDescription = "Due date"
    )
}
