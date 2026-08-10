package com.zhelenskiy.zheduler.zheduler.screens.spacelist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.SaverResultLauncher
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.util.writeStringToFile
import com.zhelenskiy.zheduler.zheduler.components.common.EmptyState
import com.zhelenskiy.zheduler.zheduler.components.common.EmptySearchResults
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DeleteConfirmationDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.EditSpaceDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.NewSpaceDialog
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.ExportResult
import com.zhelenskiy.zheduler.zheduler.viewmodels.ImportResult
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListAction
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListIntent
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListState
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceSearchOption
import pro.respawn.flowmvi.compose.dsl.subscribe
import io.github.vinceglb.filekit.readString
import kotlinx.coroutines.CoroutineScope
import com.zhelenskiy.zheduler.zheduler.screens.calendar.AnimatedVisibility

@Composable
private fun SpaceSearchBar(
    searchQuery: String,
    searchOptions: Set<SpaceSearchOption>,
    showSearchOptions: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onToggleSearchOptions: () -> Unit,
    onToggleSearchOption: (SpaceSearchOption) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text("Search by ${searchOptions.joinToString(", ") { it.displayName }}")
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = onClearSearch) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                }
            )
            IconButton(onClick = onToggleSearchOptions) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Search options",
                    tint = if (showSearchOptions) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
        }

        AnimatedVisibility(visible = showSearchOptions) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Search in:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpaceSearchOption.entries.forEach { option ->
                        FilterChip(
                            selected = option in searchOptions,
                            onClick = { onToggleSearchOption(option) },
                            label = { Text(option.displayName) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SpaceCard(
    space: Space,
    onSpaceClick: (String) -> Unit,
    onExport: (Space) -> Unit,
    onEdit: (Space) -> Unit,
    onDelete: (Space) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onSpaceClick(space.id) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = space.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = space.idPrefix,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            IconButton(onClick = { onExport(space) }) {
                Icon(Icons.Default.Download, contentDescription = "Export")
            }
            IconButton(onClick = { onEdit(space) }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = { onDelete(space) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun SpaceListContent(
    hasSpaces: Boolean?,
    filteredSpaces: List<Space>?,
    onSpaceClick: (String) -> Unit,
    onSpaceExport: (Space) -> Unit,
    onSpaceEdit: (Space) -> Unit,
    onSpaceDelete: (Space) -> Unit,
    onClearSearch: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = hasSpaces == false,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            EmptyState(message = "No spaces yet. Create one to get started!")
        }

        AnimatedVisibility(
            visible = hasSpaces == true && filteredSpaces?.isEmpty() == true,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            EmptySearchResults(
                message = "No spaces match your search",
                clearButtonText = "Clear filter",
                onClearFilters = onClearSearch
            )
        }

        AnimatedVisibility(
            visible = filteredSpaces?.isNotEmpty() == true,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
            ) {
                items(filteredSpaces ?: emptyList(), key = { it.id }) { space ->
                    SpaceCard(
                        space = space,
                        onSpaceClick = onSpaceClick,
                        onExport = onSpaceExport,
                        onEdit = onSpaceEdit,
                        onDelete = onSpaceDelete,
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
        AnimatedVisibility(hasSpaces == null || filteredSpaces == null, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpaceListTopAppBar(
    onEraseAllData: () -> Unit,
    onImport: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    TopAppBar(
        title = { Text("Zheduler - Spaces") },
        actions = {
            IconButton(onClick = onEraseAllData) {
                Icon(Icons.Default.DeleteForever, contentDescription = "Erase All Data")
            }
            IconButton(onClick = onImport) {
                Icon(Icons.Default.Upload, contentDescription = "Import Space")
            }
            ThemeMenuButton(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceListScreen(
    container: SpaceListContainer,
    refreshTrigger: Int,
    onSpaceClick: (String) -> Unit,
    onRefresh: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    val state by container.store.subscribe { action ->
        // Handle actions if needed
        when (action) {
            is SpaceListAction.SpaceAdded,
            is SpaceListAction.SpaceUpdated,
            is SpaceListAction.SpaceDeleted -> onRefresh()
            else -> Unit
        }
    }

    LaunchedEffect(refreshTrigger) {
        container.store.intent(SpaceListIntent.LoadSpaces)
    }

    val dialogState = rememberDialogState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var importedSpaceName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(importedSpaceName) {
        importedSpaceName?.let { name ->
            snackbarHostState.showSnackbar("Space \"$name\" imported successfully")
            importedSpaceName = null
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionColor = MaterialTheme.colorScheme.primary
                )
            }
        },
        topBar = {
            SpaceListTopAppBar(
                onEraseAllData = { dialogState.showEraseAllData = true },
                onImport = { dialogState.showImport = true },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = useDynamicColors,
                onDynamicColorsChange = onDynamicColorsChange,
                colorSettings = colorSettings,
                onColorSettingsChange = onColorSettingsChange
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialogState.showNewSpace = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Space")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.hasSpaces == true) {
                SpaceSearchBar(
                    searchQuery = state.searchQuery,
                    searchOptions = state.searchOptions,
                    showSearchOptions = state.showSearchOptions,
                    onSearchQueryChange = { container.store.intent(SpaceListIntent.UpdateSearchQuery(it)) },
                    onClearSearch = { container.store.intent(SpaceListIntent.ClearSearchQuery) },
                    onToggleSearchOptions = { container.store.intent(SpaceListIntent.ToggleShowSearchOptions) },
                    onToggleSearchOption = { container.store.intent(SpaceListIntent.ToggleSearchOption(it)) }
                )
            }

            SpaceListContent(
                hasSpaces = state.hasSpaces,
                filteredSpaces = state.filteredSpaces,
                onSpaceClick = onSpaceClick,
                onSpaceExport = { dialogState.spaceToExport = it },
                onSpaceEdit = { dialogState.spaceToEdit = it },
                onSpaceDelete = { dialogState.spaceToDelete = it },
                onClearSearch = { container.store.intent(SpaceListIntent.ClearSearchQuery) }
            )
        }
    }

    SpaceListDialogs(
        dialogState = dialogState,
        coroutineScope = coroutineScope,
        snackbarHostState = snackbarHostState,
        state = state,
        onAddSpace = { name, idPrefix ->
            container.store.intent(SpaceListIntent.AddSpace(name, idPrefix))
        },
        onUpdateSpace = { spaceId, newName ->
            container.store.intent(SpaceListIntent.UpdateSpace(spaceId, newName))
        },
        onDeleteSpace = { spaceId ->
            container.store.intent(SpaceListIntent.DeleteSpace(spaceId))
        },
        onLoadTagsForSpace = { spaceId ->
            container.store.intent(SpaceListIntent.LoadTagsForSpace(spaceId))
        },
        onAddTagToSpace = { spaceId, tag ->
            container.store.intent(SpaceListIntent.AddTagToSpace(spaceId, tag))
        },
        onDeleteTagFromSpace = { spaceId, tag ->
            container.store.intent(SpaceListIntent.DeleteTagFromSpace(spaceId, tag))
        },
        onExportSpace = { spaceId, prettyPrint ->
            container.store.intent(SpaceListIntent.ExportSpaceToJson(spaceId, prettyPrint))
        },
        onImportSpace = { jsonString ->
            container.store.intent(SpaceListIntent.ImportSpaceFromJson(jsonString))
        },
        onClearAllData = {
            container.store.intent(SpaceListIntent.ClearAllData)
        }
    )
}

@Composable
private fun SpaceListDialogs(
    dialogState: DialogState,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    state: SpaceListState,
    onAddSpace: (name: String, idPrefix: String) -> Unit,
    onUpdateSpace: (spaceId: String, newName: String) -> Unit,
    onDeleteSpace: (spaceId: String) -> Unit,
    onLoadTagsForSpace: (spaceId: String) -> Unit,
    onAddTagToSpace: (spaceId: String, tag: String) -> Unit,
    onDeleteTagFromSpace: (spaceId: String, tag: String) -> Unit,
    onExportSpace: (spaceId: String, prettyPrint: Boolean) -> Unit,
    onImportSpace: (jsonString: String) -> Unit,
    onClearAllData: () -> Unit
) {
    if (dialogState.showNewSpace) {
        NewSpaceDialog(
            onDismiss = { dialogState.showNewSpace = false },
            onSpaceCreated = { name, prefix ->
                onAddSpace(name, prefix)
                dialogState.showNewSpace = false
            }
        )
    }

    dialogState.spaceToEdit?.let { space ->
        LaunchedEffect(space.id) {
            onLoadTagsForSpace(space.id)
        }

        val spaceTags = state.tagsBySpace[space.id] ?: emptySet()

        EditSpaceDialog(
            space = space,
            onDismiss = { dialogState.spaceToEdit = null },
            onSpaceUpdated = { newName ->
                onUpdateSpace(space.id, newName)
                dialogState.spaceToEdit = null
            },
            allTags = spaceTags,
            onAddTag = { tag ->
                onAddTagToSpace(space.id, tag)
            },
            onDeleteTag = { tag ->
                onDeleteTagFromSpace(space.id, tag)
            }
        )
    }

    dialogState.spaceToDelete?.let { space ->
        DeleteConfirmationDialog(
            title = "Delete Space",
            message = "Are you sure you want to delete space \"${space.name}\"? All tasks in this space will be permanently deleted.",
            onConfirm = {
                onDeleteSpace(space.id)
                dialogState.spaceToDelete = null
            },
            onDismiss = { dialogState.spaceToDelete = null }
        )
    }

    dialogState.spaceToExport?.let { space ->
        ExportSpaceDialog(
            space = space,
            exportResult = state.lastExportResult,
            onDismiss = { dialogState.spaceToExport = null },
            onExportSpace = onExportSpace,
            snackbarHostState = snackbarHostState,
            parentScope = coroutineScope
        )
    }

    if (dialogState.showImport) {
        ImportSpaceDialog(
            importResult = state.lastImportResult,
            onDismiss = { dialogState.showImport = false },
            onImportSpace = onImportSpace,
            snackbarHostState = snackbarHostState,
            parentScope = coroutineScope
        )
    }

    if (dialogState.showEraseAllData) {
        DeleteConfirmationDialog(
            title = "Erase All Data",
            message = "Are you sure you want to permanently erase ALL data? This will delete all spaces, tasks, and settings. This action cannot be undone!",
            onConfirm = {
                onClearAllData()
                dialogState.showEraseAllData = false
            },
            onDismiss = { dialogState.showEraseAllData = false }
        )
    }
}

@Composable
private fun rememberDialogState() = remember {
    DialogState(
        showNewSpace = false,
        spaceToDelete = null,
        spaceToEdit = null,
        spaceToExport = null,
        showImport = false,
        showEraseAllData = false
    )
}

@Stable
private class DialogState(
    showNewSpace: Boolean,
    spaceToDelete: Space?,
    spaceToEdit: Space?,
    spaceToExport: Space?,
    showImport: Boolean,
    showEraseAllData: Boolean
) {
    var showNewSpace by mutableStateOf(showNewSpace)
    var spaceToDelete by mutableStateOf(spaceToDelete)
    var spaceToEdit by mutableStateOf(spaceToEdit)
    var spaceToExport by mutableStateOf(spaceToExport)
    var showImport by mutableStateOf(showImport)
    var showEraseAllData by mutableStateOf(showEraseAllData)
}

private enum class ExportAction { Copy, Save, Download }

@Composable
private fun ExportSpaceDialog(
    space: Space,
    exportResult: ExportResult?,
    onDismiss: () -> Unit,
    onExportSpace: (spaceId: String, prettyPrint: Boolean) -> Unit,
    snackbarHostState: SnackbarHostState,
    parentScope: CoroutineScope
) {
    var prettyPrint by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    // Track which action to perform when export result arrives
    var pendingAction by remember { mutableStateOf<ExportAction?>(null) }

    // Track the selected file for save action
    var pendingFile by remember { mutableStateOf<PlatformFile?>(null) }

    val fileSaverLauncher = rememberFileSaverLauncher { pendingFile = it }

    // Handle when export completes via state
    LaunchedEffect(exportResult, pendingAction, pendingFile) {
        if (exportResult == null || exportResult.spaceId != space.id) return@LaunchedEffect
        val json = exportResult.json
        val action = pendingAction ?: return@LaunchedEffect

        when (action) {
            ExportAction.Copy -> {
                if (json != null) {
                    clipboardManager.setText(AnnotatedString(json))
                }
                onDismiss()
                parentScope.launch {
                    if (json != null) {
                        snackbarHostState.showSnackbar("Copied to clipboard")
                    } else {
                        snackbarHostState.showSnackbar("Failed to export space")
                    }
                }
            }
            ExportAction.Download -> {
                if (json != null) {
                    val fileName = space.name.replace(Regex("[^a-zA-Z0-9-_]"), "_")
                    write(json, fileName)
                }
                onDismiss()
                parentScope.launch {
                    if (json == null) {
                        snackbarHostState.showSnackbar("Failed to export space")
                    }
                }
            }
            ExportAction.Save -> {
                val file = pendingFile ?: return@LaunchedEffect
                val message = if (json != null) {
                    try {
                        file.writeStringToFile(json)
                        "Space exported to ${file.name}"
                    } catch (e: Exception) {
                        "Error saving file: ${e.message}"
                    }
                } else {
                    "Failed to export space"
                }
                onDismiss()
                parentScope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
        pendingAction = null
        pendingFile = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Space: ${space.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Choose how to export the space data:",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Pretty-print JSON", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = prettyPrint,
                        onCheckedChange = { prettyPrint = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            pendingAction = ExportAction.Copy
                            onExportSpace(space.id, prettyPrint)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }

                    val fileName = space.name.replace(Regex("[^a-zA-Z0-9-_]"), "_")
                    if (fileSaverLauncher != null) {
                        OutlinedButton(
                            onClick = {
                                pendingAction = ExportAction.Save
                                onExportSpace(space.id, prettyPrint)
                                fileSaverLauncher.launch(
                                    suggestedName = fileName,
                                    defaultExtension = "json"
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save")
                        }
                    }
                    if (supportsDownloading) {
                        OutlinedButton(
                            onClick = {
                                pendingAction = ExportAction.Download
                                onExportSpace(space.id, prettyPrint)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Download")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ImportSpaceDialog(
    importResult: ImportResult?,
    onDismiss: () -> Unit,
    onImportSpace: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    parentScope: CoroutineScope
) {
    val coroutineScope = rememberCoroutineScope()
    var jsonText by remember { mutableStateOf("") }
    var awaitingResult by remember { mutableStateOf(false) }

    // Handle import result from state
    LaunchedEffect(importResult, awaitingResult) {
        if (!awaitingResult || importResult == null) return@LaunchedEffect
        val space = importResult.space
        val message = if (space != null) {
            "Space \"${space.name}\" imported successfully"
        } else {
            "Invalid JSON data"
        }
        onDismiss()
        parentScope.launch {
            snackbarHostState.showSnackbar(message)
        }
        awaitingResult = false
    }

    val filePickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("json"))
    ) { file ->
        file?.let {
            coroutineScope.launch {
                try {
                    val jsonData = it.readString()
                    awaitingResult = true
                    onImportSpace(jsonData)
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Error reading file: ${e.message}")
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Space") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Paste JSON data to import:",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    placeholder = { Text("Paste JSON here...") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )

                OutlinedButton(
                    onClick = { filePickerLauncher.launch() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Or Load From File")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (jsonText.isBlank()) {
                        parentScope.launch {
                            snackbarHostState.showSnackbar("Please enter JSON data")
                        }
                        return@TextButton
                    }
                    awaitingResult = true
                    onImportSpace(jsonText.trim())
                },
                enabled = jsonText.isNotBlank()
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

expect val supportsDownloading: Boolean
expect suspend fun write(content: String, name: String)

@Composable
internal expect fun rememberFileSaverLauncher(onResult: (PlatformFile?) -> Unit): SaverResultLauncher?