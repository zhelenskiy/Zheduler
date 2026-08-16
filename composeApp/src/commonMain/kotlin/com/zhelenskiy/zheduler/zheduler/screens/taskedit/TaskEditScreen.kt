@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.taskedit

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Alignment
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import kotlinx.collections.immutable.PersistentSet
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.paging.compose.collectAsLazyPagingItems
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.components.common.BackHandler
import com.zhelenskiy.zheduler.zheduler.components.common.TaskFormTopAppBar
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DiscardChangesDialog
import com.zhelenskiy.zheduler.zheduler.components.form.TaskFormContent
import com.zhelenskiy.zheduler.zheduler.components.form.persistedIn
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
    var saveFailed by remember { mutableStateOf(false) }

    val state by container.store.subscribe { action ->
        when (action) {
            is TaskEditAction.TaskSaved -> onNavigateBack()
            // Stay put and say so, rather than navigating away as though it had worked.
            is TaskEditAction.TaskSaveFailed -> saveFailed = true
        }
    }

    if (saveFailed) {
        AlertDialog(
            onDismissRequest = { saveFailed = false },
            title = { Text("Could not save") },
            text = { Text("This task no longer exists. Your changes are still here — copy anything you need before leaving.") },
            confirmButton = {
                TextButton(onClick = { saveFailed = false }) { Text("OK") }
            },
        )
    }

    val currentTask = state.task
    if (currentTask == null) {
        // Nothing at all was drawn here before: no bar, no message, no way out. A task deleted in
        // another window, or a link to one that never existed, left the user on an empty screen.
        TaskEditPlaceholder(loadAttempted = state.loadAttempted, onNavigateBack = onNavigateBack)
        return
    }

    val formState = rememberTaskFormState(currentTask)

    formState.persistedIn(container.formPersistence)

    // Connections can change underneath the form: creating a connected task from here writes the
    // other half of the link into the task being edited. Only that difference is taken, though —
    // replacing the set wholesale reverted the connections the user had just added or removed,
    // silently, on the way back from creating one.
    //
    // Where the difference is measured from is the whole of it. A restored form holds edits made
    // against the connections the task had when the user left, so that is the mark — reading it
    // off the freshly loaded task instead made the link just created look like no change at all,
    // and saving then deleted it.
    var syncedConnections by remember(currentTask.id) {
        mutableStateOf(container.formPersistence.read()?.connectionsBase ?: currentTask.connections)
    }
    LaunchedEffect(state.task) {
        val freshTask = state.task ?: return@LaunchedEffect
        val fresh = freshTask.connections
        if (fresh == syncedConnections) return@LaunchedEffect

        formState.connections = mergedConnections(formState.connections, syncedConnections, fresh)
        syncedConnections = fresh
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
            container.formPersistence.clear()
            onNavigateBack()
        }
    }

    // The toolbar arrow is not the only way out: Android's back gesture used to pop straight past
    // the check and take the edits with it. The browser's back button still does — see BackHandler,
    // which has nothing to bind to on web.
    BackHandler(enabled = !showDiscardChangesDialog) { handleBackPress() }

    if (showDiscardChangesDialog) {
        DiscardChangesDialog(
            title = "Discard changes?",
            message = "You have unsaved changes. Are you sure you want to discard them?",
            confirmText = "Discard",
            dismissText = "Keep editing",
            onConfirm = {
                showDiscardChangesDialog = false
                container.formPersistence.clear()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditPlaceholder(loadAttempted: Boolean, onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit task") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (loadAttempted) {
                Text("This task no longer exists.", textAlign = TextAlign.Center)
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * [current] with the changes made to the task's connections since [base] folded in.
 *
 * The form's own edits are kept: only what the database gained or lost relative to the set the
 * form was built from is applied on top.
 */
internal fun mergedConnections(
    current: PersistentSet<TaskConnection>,
    base: PersistentSet<TaskConnection>,
    fresh: PersistentSet<TaskConnection>,
): PersistentSet<TaskConnection> = current.addAll(fresh - base).removeAll(base - fresh)
