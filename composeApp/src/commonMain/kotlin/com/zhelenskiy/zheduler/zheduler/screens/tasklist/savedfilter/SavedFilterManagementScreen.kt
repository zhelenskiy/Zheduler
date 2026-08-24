@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist.savedfilter

import kotlin.time.ExperimentalTime

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.SavedFilter
import com.zhelenskiy.zheduler.zheduler.SavedFilterWithViewMode
import com.zhelenskiy.zheduler.zheduler.ViewMode
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DeleteConfirmationDialog
import com.zhelenskiy.zheduler.zheduler.components.common.SettingsButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.SavedFilterContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.SavedFilterIntent
import pro.respawn.flowmvi.compose.dsl.subscribe
import com.zhelenskiy.zheduler.zheduler.sync.LocalSpaceEditing
import com.zhelenskiy.zheduler.zheduler.sync.CloudSpaceBanner
import com.zhelenskiy.zheduler.zheduler.components.common.LocalFailureSnackbar
import com.zhelenskiy.zheduler.zheduler.viewmodels.SavedFilterAction

/**
 * A screen for managing saved filters.
 * Allows viewing, creating, editing, and deleting saved filters.
 */
@Composable
fun SavedFilterManagementScreen(
    container: SavedFilterContainer,
    spaceId: String,
    onLoad: (SavedFilter) -> Unit,
    onBack: () -> Unit,
    onNavigateToSpaceList: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    // Reported rather than left to be noticed. A filter the server would not take is removed from
    // the space a moment after it appears, and without a word that reads as the app losing it.
    val saidSo = LocalFailureSnackbar.current
    val state by container.store.subscribe { action ->
        when (action) {
            is SavedFilterAction.FilterNotAccepted -> saidSo?.showSnackbar(
                "That filter could not be saved — the server could not be reached."
            )
        }
    }

    var filterToDelete by remember { mutableStateOf<SavedFilter?>(null) }
    // By id, and saved: an activity recreation used to close the editing dialog and drop the edit
    // without a word. Worse, the dialog's own saved fields outlived it, and the next filter opened
    // for editing came up holding them — Save then wrote that name onto the wrong filter.
    var filterIdToEdit by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    val filterToEdit = filterIdToEdit?.let { id ->
        state.savedFilters.firstOrNull { it.filter.id == id }?.filter
    }

    Scaffold(
        topBar = {
            SavedFilterManagementTopAppBar(
                onBack = onBack,
                onNavigateToSpaceList = onNavigateToSpaceList,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        },
        floatingActionButton = {
            if (LocalSpaceEditing.current.isEditable) FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create new saved filter")
            }
        }
    ) { padding ->
        // The inset belongs to the column, not to the banner: the banner draws nothing at all
        // for a healthy space, and hanging the top padding on it took the padding away too —
        // leaving the first card behind the app bar on every ordinary visit.
        Column(modifier = Modifier.padding(padding)) {
        // See the view modes screen: saved filters travel with the space, so the same explanation
        // has to be here rather than only on the list this was reached from.
        CloudSpaceBanner()
        SavedFilterList(
            savedFilters = state.savedFilters,
            padding = PaddingValues(),
            onLoad = onLoad,
            onEdit = { filterIdToEdit = it.id },
            onDelete = { filterToDelete = it }
        )
        }
    }

    DeleteFilterDialog(
        filter = filterToDelete,
        onConfirm = {
            filterToDelete?.let { container.store.intent(SavedFilterIntent.DeleteFilter(it.id)) }
            filterToDelete = null
        },
        onDismiss = { filterToDelete = null }
    )

    EditFilterDialog(
        filter = filterToEdit,
        viewModes = state.viewModes,
        spaceId = spaceId,
        allTags = state.allTags,
        spaceIdPrefix = state.spaceIdPrefix,
        generateId = container::generateId,
        onSave = { updatedFilter ->
            container.store.intent(SavedFilterIntent.SaveFilter(updatedFilter))
            filterIdToEdit = null
        },
        onDismiss = { filterIdToEdit = null }
    )

    CreateFilterDialog(
        showDialog = showCreateDialog,
        viewModes = state.viewModes,
        spaceId = spaceId,
        allTags = state.allTags,
        spaceIdPrefix = state.spaceIdPrefix,
        generateId = container::generateId,
        onSave = { newFilter ->
            container.store.intent(SavedFilterIntent.SaveFilter(newFilter))
            showCreateDialog = false
        },
        onDismiss = { showCreateDialog = false }
    )
}

@Composable
private fun SavedFilterManagementTopAppBar(
    onBack: () -> Unit,
    onNavigateToSpaceList: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    TopAppBar(
        title = { Text("Saved Filters") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onNavigateToSpaceList) {
                Icon(Icons.Default.Home, contentDescription = "Spaces")
            }
            SettingsButton(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        },
        colors = appTopAppBarColors(),
    )
}

@Composable
private fun SavedFilterList(
    savedFilters: List<SavedFilterWithViewMode>,
    padding: PaddingValues,
    onLoad: (SavedFilter) -> Unit,
    onEdit: (SavedFilter) -> Unit,
    onDelete: (SavedFilter) -> Unit
) {
    AnimatedContent(
        targetState = savedFilters,
        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
        label = "saved_filters_list"
    ) { targetFilters ->
        if (targetFilters.isEmpty()) {
            EmptyFiltersState(padding = padding)
        } else {
            SavedFiltersLazyColumn(
                filters = targetFilters,
                padding = padding,
                onLoad = onLoad,
                onEdit = onEdit,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun EmptyFiltersState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.FilterList,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "No saved filters yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Tap + to save your current filter",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SavedFiltersLazyColumn(
    filters: List<SavedFilterWithViewMode>,
    padding: PaddingValues,
    onLoad: (SavedFilter) -> Unit,
    onEdit: (SavedFilter) -> Unit,
    onDelete: (SavedFilter) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
    ) {
        items(filters, key = { it.filter.id }) { filterWithViewMode ->
            SavedFilterCard(
                filter = filterWithViewMode.filter,
                attachedViewMode = filterWithViewMode.attachedViewMode,
                onLoad = { onLoad(filterWithViewMode.filter) },
                onEdit = { onEdit(filterWithViewMode.filter) },
                onDelete = { onDelete(filterWithViewMode.filter) },
                modifier = Modifier.animateItem()
            )
        }
    }
}

@Composable
private fun DeleteFilterDialog(
    filter: SavedFilter?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (filter != null) {
        DeleteConfirmationDialog(
            title = "Delete Saved Filter",
            message = "Are you sure you want to delete \"${filter.name}\"?",
            onConfirm = onConfirm,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun EditFilterDialog(
    filter: SavedFilter?,
    viewModes: List<ViewMode>,
    spaceId: String,
    allTags: Set<String>,
    spaceIdPrefix: String?,
    generateId: () -> String,
    onSave: (SavedFilter) -> Unit,
    onDismiss: () -> Unit
) {
    if (filter != null) {
        SaveFilterDialog(
            existingFilter = filter,
            criteria = filter.criteria,
            viewModes = viewModes,
            currentActiveViewModeId = null,
            spaceId = spaceId,
            allTags = allTags,
            spaceIdPrefix = spaceIdPrefix,
            generateId = generateId,
            onSave = onSave,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun CreateFilterDialog(
    showDialog: Boolean,
    viewModes: List<ViewMode>,
    spaceId: String,
    allTags: Set<String>,
    spaceIdPrefix: String?,
    generateId: () -> String,
    onSave: (SavedFilter) -> Unit,
    onDismiss: () -> Unit
) {
    if (showDialog) {
        SaveFilterDialog(
            criteria = TaskFilterCriteria(),
            viewModes = viewModes,
            currentActiveViewModeId = null,
            spaceId = spaceId,
            allTags = allTags,
            spaceIdPrefix = spaceIdPrefix,
            generateId = generateId,
            onSave = onSave,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun SavedFilterCard(
    filter: SavedFilter,
    attachedViewMode: ViewMode?,
    onLoad: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onLoad),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SavedFilterCardContent(
                filter = filter,
                attachedViewMode = attachedViewMode,
                modifier = Modifier.weight(1f)
            )
            SavedFilterCardActions(onEdit = onEdit, onDelete = onDelete)
        }
    }
}

@Composable
private fun SavedFilterCardContent(
    filter: SavedFilter,
    attachedViewMode: ViewMode?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.FilterList,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = filter.name,
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (attachedViewMode != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.ViewList,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "View mode: ${attachedViewMode.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        FilterCriteriaChips(criteria = filter.criteria, showEmptyState = false)
    }
}

@Composable
private fun SavedFilterCardActions(
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    // Saved filters are the space's too, and go up with it.
    if (LocalSpaceEditing.current.isEditable) {
        Row {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}
