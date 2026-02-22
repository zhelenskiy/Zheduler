package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.Task

@Composable
fun TagChip(tag: String, onRemove: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocalOffer,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (onRemove != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove tag",
                    modifier = Modifier
                        .size(14.dp)
                        .clickable(onClick = onRemove),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
fun ConnectedTaskChip(
    task: Task?,
    taskId: String,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    paddingValues: PaddingValues = PaddingValues(
        start = 12.dp,
        end = if (onRemove != null) 4.dp else 12.dp,
        top = if (onRemove != null) 4.dp else 8.dp,
        bottom = if (onRemove != null) 4.dp else 8.dp
    ),
) {
    val clipShape = MaterialTheme.shapes.small
    Surface(
        modifier = modifier
            .clip(clipShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = clipShape
    ) {
        Row(
            modifier = Modifier.padding(paddingValues),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = taskId,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            if (task != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
