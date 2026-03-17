package com.zhelenskiy.zheduler.zheduler.util

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.StatusChange
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import com.zhelenskiy.zheduler.zheduler.components.common.AutomaticChangeIndicator
import com.zhelenskiy.zheduler.zheduler.components.common.ConnectedTaskChip
import com.zhelenskiy.zheduler.zheduler.components.common.StatusBadge

@Composable
private fun BlockedStatusDetails(
    status: TaskStatus.Blocked,
    blockerTasks: Map<String, Task>?,
    onTaskClick: ((String) -> Unit)?,
    blockerTaskModifier: Modifier,
    textStyle: TextStyle,
) {
    if (status.blockerTaskIds.isNotEmpty()) {
        Text(
            text = "by",
            style = textStyle
        )
        status.blockerTaskIds.forEach { blockerId ->
            val blockerTask = blockerTasks?.get(blockerId)
            ConnectedTaskChip(
                task = blockerTask,
                taskId = blockerId,
                modifier = blockerTaskModifier.height(24.dp),
                onClick = if (onTaskClick != null) {
                    { onTaskClick(blockerId) }
                } else null,
                paddingValues = PaddingValues(horizontal = 8.dp)
            )
        }
    }
    if (status.comment.isNotEmpty()) {
        Text(
            text = status.comment,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TaskStatus(
    status: TaskStatus,
    blockerTasks: Map<String, Task>?,
    badgeModifier: Modifier = Modifier,
    isRow: Boolean = true,
    modifier: Modifier =
        if (isRow) Modifier.horizontalScroll(rememberScrollState())
        else Modifier.verticalScroll(rememberScrollState()),
    blockerTaskModifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    onBlockerTaskClick: ((String) -> Unit)?
) = Group(isRow = isRow, modifier = modifier) {
    StatusBadge(status = status, modifier = badgeModifier)
    TaskStatusDetails(status, blockerTasks, onBlockerTaskClick, blockerTaskModifier, textStyle)
}

@Composable
fun TaskStatusChange(
    change: StatusChange,
    blockerTasks: Map<String, Task>?,
    badgeModifier: Modifier = Modifier,
    isRow: Boolean = true,
    modifier: Modifier =
        if (isRow) Modifier.horizontalScroll(rememberScrollState())
        else Modifier.verticalScroll(rememberScrollState()),
    onBlockerTaskClick: ((String) -> Unit)?,
    loadedTasks: Map<String, Task> = emptyMap(),
    loadTask: (String) -> Unit = {},
    onTaskClick: (String) -> Unit = {}
) = Group(
    isRow = isRow,
    modifier = modifier,
) {
    change.previousStatus?.let { prev ->
        TaskStatus(
            status = prev,
            blockerTasks = blockerTasks,
            badgeModifier = badgeModifier,
            modifier = Modifier,
            isRow = isRow,
            onBlockerTaskClick = onBlockerTaskClick
        )
        Text(if (isRow) "→" else "↓", style = MaterialTheme.typography.bodySmall)
    }
    TaskStatus(
        status = change.newStatus,
        blockerTasks = blockerTasks,
        badgeModifier = badgeModifier,
        modifier = Modifier,
        isRow = isRow,
        onBlockerTaskClick = onBlockerTaskClick
    )
    change.automaticChangeReason?.let {
        AutomaticChangeIndicator(
            reason = it,
            loadedTasks = loadedTasks,
            loadTask = loadTask,
            onTaskClick = onTaskClick
        )
    }
}

@Composable
private fun Group(isRow: Boolean, modifier: Modifier = Modifier, body: @Composable () -> Unit) = if (isRow) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) { body() }
} else {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) { body() }
}

@Composable
private fun TaskStatusDetails(
    status: TaskStatus,
    blockerTasks: Map<String, Task>?,
    onBlockerTaskClick: ((String) -> Unit)?,
    blockerTaskModifier: Modifier,
    textStyle: TextStyle
) {
    when (status) {
        is TaskStatus.Blocked -> BlockedStatusDetails(status, blockerTasks, onBlockerTaskClick, blockerTaskModifier, textStyle)
        is TaskStatus.Declined -> DeclinedStatusDetails(status, textStyle)
        else -> {}
    }
}

@Composable
private fun DeclinedStatusDetails(newStatus: TaskStatus.Declined, textStyle: TextStyle) {
    if (newStatus.reason.isEmpty()) return
    Text(
        text = newStatus.reason,
        style = textStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}