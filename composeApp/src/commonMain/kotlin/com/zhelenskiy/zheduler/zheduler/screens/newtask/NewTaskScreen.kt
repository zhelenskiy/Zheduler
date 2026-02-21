@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.newtask

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.zhelenskiy.zheduler.zheduler.Task
import kotlinx.coroutines.launch
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DiscardChangesDialog
import com.zhelenskiy.zheduler.zheduler.components.form.TaskFormContent
import com.zhelenskiy.zheduler.zheduler.components.form.rememberTaskFormState
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.NewTaskViewModel
import kotlin.time.ExperimentalTime

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
    onDynamicColorsChange: (Boolean) -> Unit
) {
    // Load data asynchronously
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

    val initialConnections = viewModel.getInitialConnections()
    val formState = if (taskToCopy != null) {
        rememberTaskFormState(taskToCopy!!).apply {
            // Clear connections when copying to avoid duplicating relationships
            connections = initialConnections
        }
    } else {
        rememberTaskFormState(initialConnections = initialConnections)
    }
    var showDiscardChangesDialog by remember { mutableStateOf(false) }

    fun handleBackPress() {
        if (formState.hasAnyContent(initialConnections)) {
            showDiscardChangesDialog = true
        } else {
            onNavigateBack()
        }
    }

    // Discard changes confirmation dialog
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
                recurrenceRule = parsed.recurrenceRule,
                resetStatusOnRecurrence = parsed.resetStatusOnRecurrence,
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
            TopAppBar(
                title = {
                    Column {
                        Text("New Task")
                        if (nextId != null) {
                            Text(
                                text = "ID: $nextId",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { handleBackPress() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { saveTask() },
                        enabled = formState.isFormValid,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (formState.isFormValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            disabledContentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                    ThemeMenuButton(
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        useDynamicColors = useDynamicColors,
                        onDynamicColorsChange = onDynamicColorsChange
                    )
                },
                colors = appTopAppBarColors()
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (nextId != null) {
                TaskFormContent(
                    formState = formState,
                    taskId = nextId!!,
                    isNewTask = true,
                    prefilledTask = prefilledTask,
                    prefilledConnection = initialConnections.firstOrNull(),
                    onTaskClick = onTaskClick,
                    onCreateNewTaskWithConnection = null, // Can't create nested new tasks
                    getTaskById = viewModel::getTaskById,
                    filterTags = viewModel::filterTags,
                    filterTasksForSelection = viewModel::filterTasksForSelection,
                    searchTasksForConnection = viewModel::searchTasksForConnection,
                    getCalculatedStatusFromSubtasks = viewModel::getCalculatedStatusFromSubtasks,
                    currentSpaceIdPrefix = currentSpaceIdPrefix,
                    allSpacePrefixes = allSpacePrefixes
                )
            }
        }
    }
}
