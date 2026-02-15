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
    contentDescription: String = text
) {
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
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

@Composable
fun StatusBadge(status: TaskStatus, isMissed: Boolean = false) {
    val (color, icon, displayName) = when {
        isMissed -> Triple(MaterialTheme.colorScheme.error, Icons.Default.ErrorOutline, "Missed")
        else -> when (status) {
            is TaskStatus.Open -> Triple(MaterialTheme.colorScheme.primary, Icons.Default.RadioButtonUnchecked, status.displayName)
            is TaskStatus.Blocked -> Triple(MaterialTheme.colorScheme.tertiary, Icons.Default.Block, status.displayName)
            is TaskStatus.InProgress -> Triple(MaterialTheme.colorScheme.secondary, Icons.Default.PlayArrow, status.displayName)
            is TaskStatus.Done -> Triple(MaterialTheme.colorScheme.primary, Icons.Default.CheckCircle, status.displayName)
            is TaskStatus.Declined -> Triple(MaterialTheme.colorScheme.outline, Icons.Default.Cancel, status.displayName)
        }
    }

    Badge(color = color, icon = icon, text = displayName)
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
