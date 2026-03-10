@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.taskedit

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.components.common.TaskFormTopAppBar
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DiscardChangesDialog
import com.zhelenskiy.zheduler.zheduler.components.form.TaskFormContent
import com.zhelenskiy.zheduler.zheduler.components.form.rememberTaskFormState
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskEditViewModel
import kotlin.time.ExperimentalTime

private data class TaskEditData(
    val currentSpaceIdPrefix: String?,
    val allSpacePrefixes: List<String>
)

@Composable
private fun rememberTaskEditData(viewModel: TaskEditViewModel): TaskEditData {
    var currentSpaceIdPrefix by remember { mutableStateOf<String?>(null) }
    var allSpacePrefixes by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        currentSpaceIdPrefix = viewModel.getCurrentSpaceIdPrefix()
        allSpacePrefixes = viewModel.getAllSpacePrefixes()
    }

    return TaskEditData(
        currentSpaceIdPrefix = currentSpaceIdPrefix,
        allSpacePrefixes = allSpacePrefixes
    )
}

@Composable
fun TaskEditScreen(
    viewModel: TaskEditViewModel,
    onNavigateBack: () -> Unit,
    onAddNewTaskWithConnection: (String, ConnectionType) -> Unit,
    onTaskClick: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit
) {
    val task by viewModel.task.collectAsState()

    val currentTask = task ?: return

    val formState = rememberTaskFormState(currentTask)

    LaunchedEffect(Unit) {
        val persistedState = viewModel.getPersistedFormState()
        persistedState.title?.let { formState.title = it }
        persistedState.description?.let { formState.description = it }
        persistedState.priority?.let { formState.priority = it }
        persistedState.estimatedTime?.let { formState.estimatedTime = it }
        if (persistedState.tags.isNotEmpty()) {
            formState.tags = persistedState.tags
        }
        persistedState.dueDate?.let { formState.dueDate = it }
    }

    LaunchedEffect(
        formState.title,
        formState.description,
        formState.priority,
        formState.estimatedTime,
        formState.tags,
        formState.dueDate
    ) {
        viewModel.persistFormState(
            title = formState.title,
            description = formState.description,
            priority = formState.priority,
            estimatedTime = formState.estimatedTime,
            tags = formState.tags,
            dueDate = formState.dueDate
        )
    }

    LaunchedEffect(task) {
        val freshTask = task
        if (freshTask != null) {
            formState.connections = freshTask.connections
        }
    }

    val editData = rememberTaskEditData(viewModel)

    var showDiscardChangesDialog by remember { mutableStateOf(false) }

    fun saveChanges() {
        val parsed = formState.toParsedValues() ?: return
        val updatedTask = currentTask.copy(
            title = parsed.title,
            description = parsed.description,
            priority = parsed.priority,
            estimatedTime = parsed.estimatedTime,
            tags = parsed.tags,
            dueDate = parsed.dueDate,
            status = parsed.status,
            connections = parsed.connections,
            notifications = parsed.notifications,
            recurrenceRules = parsed.recurrenceRules,
            autoUpdateStatusFromSubtasks = parsed.autoUpdateStatusFromSubtasks
        )
        viewModel.saveTask(updatedTask)
        onNavigateBack()
    }

    fun handleBackPress() {
        if (formState.hasUnsavedChanges(currentTask)) {
            showDiscardChangesDialog = true
        } else {
            viewModel.clearPersistedFormState()
            onNavigateBack()
        }
    }

    if (showDiscardChangesDialog) {
        DiscardChangesDialog(
            title = "Discard changes?",
            message = "You have unsaved changes. Are you sure you want to discard them?",
            confirmText = "Discard",
            dismissText = "Keep editing",
            onConfirm = {
                showDiscardChangesDialog = false
                viewModel.clearPersistedFormState()
                onNavigateBack()
            },
            onDismiss = { showDiscardChangesDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TaskFormTopAppBar(
                title = "Edit Task",
                taskId = currentTask.id,
                isFormValid = formState.isFormValid,
                onBackPress = { handleBackPress() },
                onSave = { saveChanges() },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onDynamicColorsChange
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            TaskFormContent(
                formState = formState,
                isNewTask = false,
                onTaskClick = onTaskClick,
                onCreateNewTaskWithConnection = { connectionType ->
                    onAddNewTaskWithConnection(currentTask.id, connectionType.symmetric)
                },
                getTaskById = viewModel::getTaskById,
                filterTags = viewModel::filterTags,
                filterTasksForSelection = viewModel::filterTasksForSelection,
                searchTasksForConnection = viewModel::searchTasksForConnection,
                currentSpaceIdPrefix = editData.currentSpaceIdPrefix,
                allSpacePrefixes = editData.allSpacePrefixes
            )
        }
    }
}
