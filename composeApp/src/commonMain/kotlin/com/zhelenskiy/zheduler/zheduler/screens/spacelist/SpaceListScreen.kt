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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.FileKitType
import kotlinx.coroutines.launch
import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.components.common.EmptyState
import com.zhelenskiy.zheduler.zheduler.components.common.EmptySearchResults
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DeleteConfirmationDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.EditSpaceDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.NewSpaceDialog
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListViewModel
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceSearchOption
import io.github.vinceglb.filekit.dialogs.compose.SaverResultLauncher
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
                    Text("Search spaces by ${searchOptions.joinToString(", ") { it.displayName }}")
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
                contentPadding = PaddingValues(vertical = 16.dp)
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
    onDynamicColorsChange: (Boolean) -> Unit
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
                onDynamicColorsChange = onDynamicColorsChange
            )
        },
        colors = appTopAppBarColors(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceListScreen(
    viewModel: SpaceListViewModel,
    refreshTrigger: Int,
    onSpaceClick: (String) -> Unit,
    onRefresh: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit
) {
    val hasSpaces by viewModel.hasSpaces.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchOptions by viewModel.searchOptions.collectAsState()
    val showSearchOptions by viewModel.showSearchOptions.collectAsState()
    val filteredSpaces by viewModel.filteredSpaces.collectAsState()

    LaunchedEffect(refreshTrigger) {
        viewModel.loadSpaces()
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
                onDynamicColorsChange = onDynamicColorsChange
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
            if (hasSpaces == true) {
                SpaceSearchBar(
                    searchQuery = searchQuery,
                    searchOptions = searchOptions,
                    showSearchOptions = showSearchOptions,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onClearSearch = viewModel::clearSearchQuery,
                    onToggleSearchOptions = viewModel::toggleShowSearchOptions,
                    onToggleSearchOption = viewModel::toggleSearchOption
                )
            }

            SpaceListContent(
                hasSpaces = hasSpaces,
                filteredSpaces = filteredSpaces,
                onSpaceClick = onSpaceClick,
                onSpaceExport = { dialogState.spaceToExport = it },
                onSpaceEdit = { dialogState.spaceToEdit = it },
                onSpaceDelete = { dialogState.spaceToDelete = it },
                onClearSearch = viewModel::clearSearchQuery
            )
        }
    }

    SpaceListDialogs(
        dialogState = dialogState,
        viewModel = viewModel,
        allTags = allTags.toSet(),
        coroutineScope = coroutineScope,
        snackbarHostState = snackbarHostState,
        onRefresh = onRefresh,
        onImportedSpaceNameChange = { importedSpaceName = it }
    )
}

@Composable
private fun SpaceListDialogs(
    dialogState: DialogState,
    viewModel: SpaceListViewModel,
    allTags: Set<String>,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onImportedSpaceNameChange: (String?) -> Unit
) {
    if (dialogState.showNewSpace) {
        NewSpaceDialog(
            onDismiss = { dialogState.showNewSpace = false },
            onSpaceCreated = { name, prefix ->
                coroutineScope.launch {
                    viewModel.addSpace(name, prefix)
                    dialogState.showNewSpace = false
                    onRefresh()
                }
            }
        )
    }

    dialogState.spaceToEdit?.let { space ->
        EditSpaceDialog(
            space = space,
            onDismiss = { dialogState.spaceToEdit = null },
            onSpaceUpdated = { newName ->
                coroutineScope.launch {
                    viewModel.updateSpace(space.id, newName)
                    dialogState.spaceToEdit = null
                    onRefresh()
                }
            },
            allTags = allTags,
            onAddTag = { tag ->
                coroutineScope.launch {
                    viewModel.addTag(tag)
                }
            },
            onDeleteTag = { tag ->
                coroutineScope.launch {
                    viewModel.deleteTag(tag)
                }
            }
        )
    }

    dialogState.spaceToDelete?.let { space ->
        DeleteConfirmationDialog(
            title = "Delete Space",
            message = "Are you sure you want to delete space \"${space.name}\"? All tasks in this space will be permanently deleted.",
            onConfirm = {
                coroutineScope.launch {
                    viewModel.deleteSpace(space.id)
                    dialogState.spaceToDelete = null
                    onRefresh()
                }
            },
            onDismiss = { dialogState.spaceToDelete = null }
        )
    }

    dialogState.spaceToExport?.let { space ->
        ExportSpaceDialog(
            coroutineScope = coroutineScope,
            space = space,
            onDismiss = { dialogState.spaceToExport = null },
            exportSpaceToJson = viewModel::exportSpaceToJson,
            snackbarHostState = snackbarHostState
        )
    }

    if (dialogState.showImport) {
        ImportSpaceDialog(
            onDismiss = { dialogState.showImport = false },
            onImport = { jsonData ->
                val importedSpace = viewModel.importSpaceFromJson(jsonData)
                dialogState.showImport = false
                if (importedSpace != null) {
                    onRefresh()
                    onImportedSpaceNameChange(importedSpace.name)
                }
                importedSpace
            },
            snackbarHostState = snackbarHostState
        )
    }

    if (dialogState.showEraseAllData) {
        DeleteConfirmationDialog(
            title = "Erase All Data",
            message = "Are you sure you want to permanently erase ALL data? This will delete all spaces, tasks, and settings. This action cannot be undone!",
            onConfirm = {
                viewModel.clearAllData()
                dialogState.showEraseAllData = false
                onRefresh()
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

@Composable
private fun ExportSpaceDialog(
    coroutineScope: CoroutineScope,
    space: Space,
    onDismiss: () -> Unit,
    exportSpaceToJson: suspend (spaceId: String, prettyPrint: Boolean) -> String?,
    snackbarHostState: SnackbarHostState
) {
    var prettyPrint by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    val fileSaverLauncher = getFileSaverLauncher(coroutineScope, space, prettyPrint, snackbarHostState, onDismiss, exportSpaceToJson)

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
                            coroutineScope.launch {
                                val jsonData = exportSpaceToJson(space.id, prettyPrint)
                                if (jsonData != null) {
                                    clipboardManager.setText(AnnotatedString(jsonData))
                                }
                                onDismiss()
                                coroutineScope.launch {
                                    if (jsonData != null) {
                                        snackbarHostState.showSnackbar("Copied to clipboard")
                                    } else {
                                        snackbarHostState.showSnackbar("Failed to export space")
                                    }
                                }
                            }
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
                                fileSaverLauncher.launch(
                                    suggestedName = fileName,
                                    extension = "json"
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save")
                        }
                    }
                    if (supportsDownloading)  {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    val content = exportSpaceToJson(space.id, prettyPrint) ?: return@launch
                                    write(content, fileName)
                                }
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
internal expect fun getFileSaverLauncher(
    coroutineScope: CoroutineScope,
    space: Space,
    prettyPrint: Boolean,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    exportSpaceToJson: suspend (spaceId: String, prettyPrint: Boolean) -> String?
): SaverResultLauncher?

@Composable
private fun ImportSpaceDialog(
    onDismiss: () -> Unit,
    onImport: suspend (String) -> Space?,
    snackbarHostState: SnackbarHostState
) {
    val coroutineScope = rememberCoroutineScope()
    var jsonText by remember { mutableStateOf("") }

    val filePickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("json"))
    ) { file ->
        file?.let {
            coroutineScope.launch {
                try {
                    val jsonData = it.readString()
                    val result = onImport(jsonData)
                    if (result != null) {
                        snackbarHostState.showSnackbar("Space \"${result.name}\" imported successfully")
                        onDismiss()
                    } else {
                        snackbarHostState.showSnackbar("Invalid JSON data")
                    }
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
                    coroutineScope.launch {
                        try {
                            if (jsonText.isBlank()) {
                                snackbarHostState.showSnackbar("Please enter JSON data")
                                return@launch
                            }
                            val result = onImport(jsonText.trim())
                            if (result != null) {
                                snackbarHostState.showSnackbar("Space \"${result.name}\" imported successfully")
                                onDismiss()
                            } else {
                                snackbarHostState.showSnackbar("Invalid JSON data")
                            }
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Error importing: ${e.message}")
                        }
                    }
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