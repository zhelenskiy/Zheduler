package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.zhelenskiy.zheduler.zheduler.screens.newtask.NewTaskScreen
import com.zhelenskiy.zheduler.zheduler.screens.taskdetail.TaskDetailScreen
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.TaskListScreen
import com.zhelenskiy.zheduler.zheduler.screens.spacelist.SpaceListScreen

/**
 * Reusable confirmation dialog for discarding unsaved changes.
 * Used in [NewTaskScreen] and [TaskDetailScreen].
 */
@Composable
fun DiscardChangesDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        }
    )
}

/**
 * Reusable confirmation dialog for delete operations.
 * Used in [TaskListScreen] and [SpaceListScreen].
 */
@Composable
fun DeleteConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
