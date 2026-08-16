@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.newtask

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.paging.compose.collectAsLazyPagingItems
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import com.zhelenskiy.zheduler.zheduler.components.common.BackHandler
import com.zhelenskiy.zheduler.zheduler.components.common.TaskFormTopAppBar
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DiscardChangesDialog
import com.zhelenskiy.zheduler.zheduler.components.form.LocalPendingEdits
import com.zhelenskiy.zheduler.zheduler.components.form.PendingEdits
import com.zhelenskiy.zheduler.zheduler.components.form.TaskFormContent
import com.zhelenskiy.zheduler.zheduler.components.form.TaskFormState
import com.zhelenskiy.zheduler.zheduler.components.form.persistedIn
import com.zhelenskiy.zheduler.zheduler.components.form.taskFormState
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.NewTaskAction
import com.zhelenskiy.zheduler.zheduler.viewmodels.NewTaskContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.NewTaskIntent
import kotlinx.collections.immutable.PersistentSet
import pro.respawn.flowmvi.compose.dsl.subscribe
import kotlin.time.ExperimentalTime

@Composable
fun NewTaskScreen(
    container: NewTaskContainer,
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
    // Creating is not idempotent, and the button stays enabled until navigation takes the screen
    // away — long enough on a slow device for a second tap to queue a second identical task.
    var saving by remember { mutableStateOf(false) }

    val state by container.store.subscribe { action ->
        when (action) {
            is NewTaskAction.TaskCreated -> onTaskCreated(action.task.id)
            // The save did not happen, so the screen stays and the button must work again.
            is NewTaskAction.TaskCreationFailed -> saving = false
        }
    }

    // Anything the store threw leaves the screen up as well; the guard has to be released for
    // that too, or one failed attempt disables Save for as long as the form is open.
    LaunchedEffect(container) {
        container.failures.collect { saving = false }
    }

    val filteredTags = container.filteredTags.collectAsLazyPagingItems()
    val filteredTasksForSelection = container.filteredTasksForSelection.collectAsLazyPagingItems()
    val searchedTasksForConnection = container.searchedTasksForConnection.collectAsLazyPagingItems()

    val initialConnections = state.initialConnections
    val formState = rememberFormStateFromData(state.taskToCopy, initialConnections)
    formState.persistedIn(container.formPersistence)
    var showDiscardChangesDialog by remember { mutableStateOf(false) }
    // What the description editor is holding but has not reported yet; see PendingEdits.
    val pendingEdits = remember { PendingEdits() }

    fun handleBackPress() {
        // The rich editor reports on a pause, so the last keystrokes are still in it: without
        // this, leaving straight after typing found nothing to discard and discarded it.
        pendingEdits.flush()
        if (formState.hasAnyContent(initialConnections)) {
            showDiscardChangesDialog = true
        } else {
            onNavigateBack()
        }
    }

    // Same as the toolbar arrow: a system back gesture asks before throwing the draft away.
    BackHandler(enabled = !showDiscardChangesDialog) { handleBackPress() }

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

    fun saveTask() {
        if (saving) return
        // Before reading the form: see PendingEdits. The description otherwise stops at whatever
        // the user last paused after.
        pendingEdits.flush()
        val parsed = formState.toParsedValues() ?: return
        saving = true
        container.store.intent(
            NewTaskIntent.CreateTask(
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
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TaskFormTopAppBar(
                title = "New Task",
                taskId = state.nextId?.let { "ID: $it" },
                isFormValid = formState.isFormValid && !saving,
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
            if (state.nextId != null) {
                CompositionLocalProvider(LocalPendingEdits provides pendingEdits) {
                TaskFormContent(
                    formState = formState,
                    isNewTask = true,
                    prefilledTask = state.prefilledTask,
                    prefilledConnection = initialConnections.firstOrNull(),
                    onTaskClick = onTaskClick,
                    onCreateNewTaskWithConnection = null,
                    loadedTasks = state.loadedTasks,
                    filteredTags = filteredTags,
                    filteredTasksForSelection = filteredTasksForSelection,
                    searchedTasksForConnection = searchedTasksForConnection,
                    onLoadTask = { taskId -> container.store.intent(NewTaskIntent.LoadTask(taskId)) },
                    onFilterTags = { query, excludeTags -> container.store.intent(NewTaskIntent.FilterTags(query, excludeTags)) },
                    onFilterTasksForSelection = { query -> container.store.intent(NewTaskIntent.FilterTasksForSelection(query)) },
                    onSearchTasksForConnection = { query, excludeIds, type, existing ->
                        container.store.intent(NewTaskIntent.SearchTasksForConnection(query, excludeIds, type, existing))
                    },
                    currentSpaceIdPrefix = state.currentSpaceIdPrefix,
                    allSpacePrefixes = state.allSpacePrefixes,
                    // No id until it is created, so the choice applies to this form only.
                    taskId = null
                )
                }
            }
        }
    }
}

/**
 * The form to fill in, rebuilt when the data it starts from arrives.
 *
 * Both the task being copied and the connection to prefill are read from the database after the
 * screen is already on show, so the first form is always the empty one. Keyed on them, or opening
 * "new related task" would drop the very connection it was opened to create.
 */
@Composable
internal fun rememberFormStateFromData(
    taskToCopy: Task?,
    initialConnections: PersistentSet<TaskConnection>,
): TaskFormState = remember(taskToCopy, initialConnections) {
    if (taskToCopy != null) {
        taskFormState(taskToCopy).apply { connections = initialConnections }
    } else {
        taskFormState(initialConnections = initialConnections)
    }
}
