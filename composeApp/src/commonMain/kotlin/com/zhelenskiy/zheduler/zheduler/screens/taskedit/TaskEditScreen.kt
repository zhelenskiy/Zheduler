@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.taskedit

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.paging.compose.collectAsLazyPagingItems
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.components.common.TaskFormTopAppBar
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DiscardChangesDialog
import com.zhelenskiy.zheduler.zheduler.components.form.TaskFormContent
import com.zhelenskiy.zheduler.zheduler.components.form.rememberTaskFormState
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskEditAction
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskEditContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskEditIntent
import pro.respawn.flowmvi.compose.dsl.subscribe
import kotlin.time.ExperimentalTime

@Composable
fun TaskEditScreen(
    container: TaskEditContainer,
    onNavigateBack: () -> Unit,
    onAddNewTaskWithConnection: (String, ConnectionType) -> Unit,
    onTaskClick: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    val state by container.store.subscribe { action ->
        when (action) {
            is TaskEditAction.TaskSaved -> onNavigateBack()
        }
    }

    val currentTask = state.task ?: return

    val formState = rememberTaskFormState(currentTask)

    // Apply persisted form state on initial load (from SavedStateHandle)
    LaunchedEffect(Unit) {
        val persistedState = container.getPersistedFormState()
        persistedState.title?.let { formState.title = it }
        persistedState.description?.let { formState.description = it }
        persistedState.priority?.let { formState.priority = it }
        persistedState.estimatedTime?.let { formState.estimatedTime = it }
        if (persistedState.tags.isNotEmpty()) {
            formState.tags = persistedState.tags
        }
        persistedState.dueDate?.let { formState.dueDate = it }
    }

    // Persist form state changes to SavedStateHandle
    LaunchedEffect(
        formState.title,
        formState.description,
        formState.priority,
        formState.estimatedTime,
        formState.tags,
        formState.dueDate
    ) {
        container.persistFormState(
            title = formState.title,
            description = formState.description,
            priority = formState.priority,
            estimatedTime = formState.estimatedTime,
            tags = formState.tags,
            dueDate = formState.dueDate
        )
    }

    // Update connections when task changes
    LaunchedEffect(state.task) {
        val freshTask = state.task
        if (freshTask != null) {
            formState.connections = freshTask.connections
        }
    }

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
        container.store.intent(TaskEditIntent.SaveTask(updatedTask))
    }

    fun handleBackPress() {
        if (formState.hasUnsavedChanges(currentTask)) {
            showDiscardChangesDialog = true
        } else {
            container.clearPersistedFormState()
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
                container.clearPersistedFormState()
                onNavigateBack()
            },
            onDismiss = { showDiscardChangesDialog = false }
        )
    }

    val filteredTags = container.filteredTags.collectAsLazyPagingItems()
    val filteredTasksForSelection = container.filteredTasksForSelection.collectAsLazyPagingItems()
    val searchedTasksForConnection = container.searchedTasksForConnection.collectAsLazyPagingItems()

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
                onDynamicColorsChange = onDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
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
                loadedTasks = state.loadedTasks,
                filteredTags = filteredTags,
                filteredTasksForSelection = filteredTasksForSelection,
                searchedTasksForConnection = searchedTasksForConnection,
                onLoadTask = { taskId -> container.store.intent(TaskEditIntent.LoadTaskById(taskId)) },
                onFilterTags = { query, excludeTags -> container.store.intent(TaskEditIntent.FilterTags(query, excludeTags)) },
                onFilterTasksForSelection = { query -> container.store.intent(TaskEditIntent.FilterTasksForSelection(query)) },
                onSearchTasksForConnection = { query, excludeIds, type, existing ->
                    container.store.intent(TaskEditIntent.SearchTasksForConnection(query, excludeIds, type, existing))
                },
                currentSpaceIdPrefix = state.currentSpaceIdPrefix,
                allSpacePrefixes = state.allSpacePrefixes,
                taskId = currentTask.id
            )
        }
    }
}
