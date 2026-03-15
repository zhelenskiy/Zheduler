@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.newtask

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import kotlinx.coroutines.launch
import com.zhelenskiy.zheduler.zheduler.components.common.TaskFormTopAppBar
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DiscardChangesDialog
import com.zhelenskiy.zheduler.zheduler.components.form.TaskFormContent
import com.zhelenskiy.zheduler.zheduler.components.form.rememberTaskFormState
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.NewTaskViewModel
import kotlin.time.ExperimentalTime

@Composable
private fun rememberNewTaskData(viewModel: NewTaskViewModel): NewTaskData {
    var taskToCopy by remember { mutableStateOf<Task?>(null) }
    var nextId by remember { mutableStateOf<String?>(null) }
    var prefilledTask by remember { mutableStateOf<Task?>(null) }
    var currentSpaceIdPrefix by remember { mutableStateOf<String?>(null) }
    var allSpacePrefixes by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        taskToCopy = viewModel.getTaskToCopy()
        nextId = viewModel.getNextId()
        prefilledTask = viewModel.getPrefilledTask()
        currentSpaceIdPrefix = viewModel.getCurrentSpaceIdPrefix()
        allSpacePrefixes = viewModel.getAllSpacePrefixes()
    }

    return NewTaskData(
        taskToCopy = taskToCopy,
        nextId = nextId,
        prefilledTask = prefilledTask,
        currentSpaceIdPrefix = currentSpaceIdPrefix,
        allSpacePrefixes = allSpacePrefixes
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskScreen(
    viewModel: NewTaskViewModel,
    onNavigateBack: () -> Unit,
    onTaskCreated: (String) -> Unit,
    onTaskClick: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    val taskData = rememberNewTaskData(viewModel)
    val initialConnections = viewModel.getInitialConnections()
    val formState = rememberFormStateFromData(taskData.taskToCopy, initialConnections)
    var showDiscardChangesDialog by remember { mutableStateOf(false) }

    fun handleBackPress() {
        if (formState.hasAnyContent(initialConnections)) {
            showDiscardChangesDialog = true
        } else {
            onNavigateBack()
        }
    }

    if (showDiscardChangesDialog) {
        DiscardChangesDialog(
            title = "Discard new task?",
            message = "You have unsaved changes. Are you sure you want to discard them?",
            confirmText = "Discard",
            dismissText = "Keep editing",
            onConfirm = {
                showDiscardChangesDialog = false
                onNavigateBack()
            },
            onDismiss = { showDiscardChangesDialog = false }
        )
    }

    val coroutineScope = rememberCoroutineScope()

    fun saveTask() {
        val parsed = formState.toParsedValues() ?: return

        coroutineScope.launch {
            val task = viewModel.createTask(
                title = parsed.title,
                description = parsed.description,
                status = parsed.status,
                dueDate = parsed.dueDate,
                priority = parsed.priority,
                estimatedTime = parsed.estimatedTime,
                tags = parsed.tags,
                connections = parsed.connections,
                notifications = parsed.notifications,
                recurrenceRules = parsed.recurrenceRules,
                autoUpdateStatusFromSubtasks = parsed.autoUpdateStatusFromSubtasks
            )
            if (task != null) {
                onTaskCreated(task.id)
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TaskFormTopAppBar(
                title = "New Task",
                taskId = taskData.nextId?.let { "ID: $it" },
                isFormValid = formState.isFormValid,
                onBackPress = { handleBackPress() },
                onSave = { saveTask() },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (taskData.nextId != null) {
                TaskFormContent(
                    formState = formState,
                    isNewTask = true,
                    prefilledTask = taskData.prefilledTask,
                    prefilledConnection = initialConnections.firstOrNull(),
                    onTaskClick = onTaskClick,
                    onCreateNewTaskWithConnection = null,
                    getTaskById = viewModel::getTaskById,
                    filterTags = viewModel::filterTags,
                    filterTasksForSelection = viewModel::filterTasksForSelection,
                    searchTasksForConnection = viewModel::searchTasksForConnection,
                    currentSpaceIdPrefix = taskData.currentSpaceIdPrefix,
                    allSpacePrefixes = taskData.allSpacePrefixes
                )
            }
        }
    }
}

@Composable
private fun rememberFormStateFromData(taskToCopy: Task?, initialConnections: Set<TaskConnection>) =
    if (taskToCopy != null) {
        rememberTaskFormState(taskToCopy).apply {
            connections = initialConnections
        }
    } else {
        rememberTaskFormState(initialConnections = initialConnections)
    }

private data class NewTaskData(
    val taskToCopy: Task?,
    val nextId: String?,
    val prefilledTask: Task?,
    val currentSpaceIdPrefix: String?,
    val allSpacePrefixes: List<String>,
)
