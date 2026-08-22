@file:OptIn(ExperimentalMaterial3Api::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.*
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.dialogs.TagSelectionDialog
import com.zhelenskiy.zheduler.zheduler.components.common.SettingsButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.zhelenskiy.zheduler.zheduler.components.common.BackHandler
import com.zhelenskiy.zheduler.zheduler.components.common.ReorderControls
import com.zhelenskiy.zheduler.zheduler.viewmodels.ViewModeAction
import com.zhelenskiy.zheduler.zheduler.viewmodels.ViewModeContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.reportingFailure
import com.zhelenskiy.zheduler.zheduler.viewmodels.ViewModeIntent
import org.jetbrains.compose.resources.painterResource
import pro.respawn.flowmvi.compose.dsl.subscribe
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import zheduler.composeapp.generated.resources.Res
import zheduler.composeapp.generated.resources.ic_align_end
import zheduler.composeapp.generated.resources.ic_align_start

@Composable
fun ViewModeEditorScreen(
    container: ViewModeContainer,
    viewModeId: String?,
    copyFromViewModeId: String?,
    spaceId: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by container.store.subscribe { action ->
        when (action) {
            is ViewModeAction.ViewModeSaved -> onSave()
        }
    }
    val isCopy = copyFromViewModeId != null

    // One editor for the life of this screen, kept across activity recreation, rather than one
    // rebuilt from the stored view mode whenever the read finishes — which is what used to throw
    // away everything arranged before a rotation.
    val editorState = rememberSaveable(spaceId, isCopy, saver = ViewModeEditorState.saver(spaceId, isCopy)) {
        ViewModeEditorState(spaceId = spaceId, isCopy = isCopy)
    }
    // The read happens once. A restored editor already holds the answer, and loading over it would
    // undo the edits the restore just brought back.
    var loadAttempted by rememberSaveable { mutableStateOf(false) }
    // A mode was asked for and did not arrive — deleted meanwhile, or the read failed. Recorded so
    // that the screen does not present itself as "new": the editor would then be empty, and Save
    // would mint a fresh id and add a second view mode instead of changing the one asked for.
    var loadFailed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModeId, copyFromViewModeId) {
        if (loadAttempted) return@LaunchedEffect
        // Awaited straight from an effect, so a database error is reported rather than thrown into
        // the composition, where nothing would catch it.
        val loaded = container.reportingFailure<ViewMode?>(null) {
            when {
                viewModeId != null -> container.getViewModeById(viewModeId)
                copyFromViewModeId != null -> container.getViewModeById(copyFromViewModeId)?.let {
                    container.copyViewMode(it)
                }
                else -> null
            }
        }
        loadFailed = loaded == null && (viewModeId != null || copyFromViewModeId != null)
        loaded?.let(editorState::startFrom)
        loadAttempted = true
    }

    if (loadFailed) {
        ViewModeEditorPlaceholder(onCancel)
        return
    }
    val validationResult by rememberViewModeValidation(editorState)
    val isValid = validationResult is GroupingValidationResult.Valid
    val isNewMode = editorState.baseline == null
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    /** The one way out, wherever it was asked for. */
    fun leave() {
        if (editorState.hasChanges()) showDiscardDialog = true else onCancel()
    }

    // The toolbar arrow was the only way out that asked. Android's back gesture popped the screen
    // straight away, and popping it discards the saved state the editor is kept in — so every
    // level, group and rule arranged went with it, silently.
    BackHandler(enabled = !showDiscardDialog) { leave() }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes. Are you sure you want to discard them?") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onCancel()
                }) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep editing")
                }
            }
        )
    }

    val filteredTags = container.filteredTags.collectAsLazyPagingItems()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (isNewMode) "New View Mode" else "Edit View Mode") },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            container.store.intent(ViewModeIntent.SaveViewMode(editorState.toViewMode()))
                        },
                        enabled = isValid && editorState.name.isNotBlank()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
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
                colors = appTopAppBarColors()
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Name field
            item {
                OutlinedTextField(
                    value = editorState.name,
                    onValueChange = { editorState.name = it },
                    label = { Text("View Mode Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = editorState.name.isBlank()
                )
            }

            item {
                ValidationStatusCard(validationResult, editorState.name.isBlank())
            }

            item {
                GroupingLevelsSection(
                    state = editorState,
                    filteredTags = filteredTags,
                    onFilterTags = { query, excludeTags ->
                        container.store.intent(ViewModeIntent.FilterTags(query, excludeTags))
                    }
                )
            }

            item {
                DefaultOrderingRulesSection(editorState)
            }
        }
    }
}

@Composable
private fun ValidationStatusCard(validationResult: GroupingValidationResult, isNameBlank: Boolean) {
    val errors = buildList {
        if (isNameBlank) {
            add("Name is required")
        }
        when (validationResult) {
            is GroupingValidationResult.Valid -> {}
            is GroupingValidationResult.Invalid -> {
                validationResult.errors.forEach { error ->
                    when (error) {
                        is GroupingValidationError.EmptyGroup -> {
                            add("Group '${error.groupLabel.ifBlank { "(unnamed)" }}' in ${error.field.displayName} has no values")
                        }
                        is GroupingValidationError.EmptyGroupLabel -> {
                            add("A group in ${error.field.displayName} has no name")
                        }
                        is GroupingValidationError.InvalidRange -> {
                            add("Group '${error.groupLabel.ifBlank { "(unnamed)" }}' in ${error.field.displayName} has invalid range")
                        }
                        is GroupingValidationError.EmptyLevel -> {
                            add("The ${error.field.displayName} level has no groups")
                        }
                    }
                }
            }
        }
    }

    // Skip animation on initial composition to avoid flicker
    var initialComposition by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        initialComposition = false
    }

    if (initialComposition) {
        if (errors.isNotEmpty()) {
            ValidationErrorsCard(errors)
        }
    } else {
        AnimatedVisibility(
            visible = errors.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            ValidationErrorsCard(errors)
        }
    }
}

@Composable
private fun ValidationErrorsCard(errors: List<String>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            errors.forEach { error ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private sealed class LevelEditorMode {
    data class Edit(val index: Int) : LevelEditorMode()
    data object Create : LevelEditorMode()

    companion object {
        /** Which dialog was open, so a recreation reopens it rather than dropping the edit. */
        val Saver: Saver<LevelEditorMode?, Any> = listSaver(
            save = { mode ->
                when (mode) {
                    is Edit -> listOf(mode.index)
                    Create -> listOf(-1)
                    null -> emptyList()
                }
            },
            restore = { saved ->
                val index = saved.firstOrNull() as Int?
                when {
                    index == null -> null
                    index < 0 -> Create
                    else -> Edit(index)
                }
            },
        )
    }
}

@Composable
private fun GroupingLevelsSection(
    state: ViewModeEditorState,
    filteredTags: LazyPagingItems<String>,
    onFilterTags: (String, Set<String>) -> Unit
) {
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        state.moveGroupingLevel(from.index, to.index)
    }
    // Saved, like the editor behind it: a recreation used to close this dialog and discard every
    // group added, relabelled or given values since it opened, since nothing reaches the editor
    // until Done.
    var editorMode by rememberSaveable(stateSaver = LevelEditorMode.Saver) {
        mutableStateOf<LevelEditorMode?>(null)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Grouping Levels", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { editorMode = LevelEditorMode.Create }) {
                    Icon(Icons.Default.Add, contentDescription = "Add grouping level")
                }
            }

            if (state.groupingLevels.isEmpty()) {
                Text(
                    "No grouping - tasks will be shown in a flat list",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(state.groupingLevels.size, key = { state.groupingLevels[it].id }) { index ->
                        ReorderableItem(reorderableLazyListState, key = state.groupingLevels[index].id) { isDragging ->
                            val elevation by animateDpAsState(if (isDragging) 8.dp else 1.dp)

                            GroupingLevelSummaryCard(
                                level = state.groupingLevels[index],
                                index = index,
                                onEdit = { editorMode = LevelEditorMode.Edit(index) },
                                onRemove = { state.removeGroupingLevel(index) },
                                dragModifier = Modifier.draggableHandle(),
                                reorder = {
                                    ReorderControls(
                                        what = "level ${index + 1}",
                                        canMoveUp = index > 0,
                                        canMoveDown = index < state.groupingLevels.lastIndex,
                                        onMoveUp = { state.moveGroupingLevel(index, index - 1) },
                                        onMoveDown = { state.moveGroupingLevel(index, index + 1) },
                                    )
                                },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(vertical = 4.dp)
                                    .shadow(elevation, shape = MaterialTheme.shapes.medium)
                            )
                        }
                    }
                }
            }
        }
    }

    // Editor dialog - works on a copy, applies only on Done
    when (val mode = editorMode) {
        is LevelEditorMode.Edit -> {
            val index = mode.index
            if (index in state.groupingLevels.indices) {
                // Create a working copy from current state
                val initialSnapshot = remember(index) { state.groupingLevels[index].createSnapshot() }
                val workingCopy = rememberSaveable(index, saver = groupingLevelStateSaver()) {
                    GroupingLevelState().apply { restoreFromSnapshot(initialSnapshot) }
                }

                GroupingLevelEditorDialog(
                    level = workingCopy,
                    levelIndex = index,
                    filteredTags = filteredTags,
                    onFilterTags = onFilterTags,
                    onDone = {
                        // Apply changes from working copy to actual state
                        state.groupingLevels[index].restoreFromSnapshot(workingCopy.createSnapshot())
                        editorMode = null
                    },
                    onCancel = {
                        // Just close - original state is unchanged
                        editorMode = null
                    }
                )
            }
        }
        is LevelEditorMode.Create -> {
            // Create a fresh working copy for new level
            val workingCopy = rememberSaveable(saver = groupingLevelStateSaver()) { GroupingLevelState() }

            GroupingLevelEditorDialog(
                level = workingCopy,
                levelIndex = state.groupingLevels.size,
                filteredTags = filteredTags,
                onFilterTags = onFilterTags,
                onDone = {
                    // Add the new level from working copy
                    state.groupingLevels.add(
                        GroupingLevelState().apply { restoreFromSnapshot(workingCopy.createSnapshot()) }
                    )
                    editorMode = null
                },
                onCancel = {
                    // Just close - nothing added
                    editorMode = null
                }
            )
        }
        null -> {}
    }
}

@Composable
private fun GroupingLevelSummaryCard(
    level: GroupingLevelState,
    index: Int,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    dragModifier: Modifier = Modifier,
    reorder: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        onClick = onEdit
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Drag to reorder",
                        modifier = dragModifier
                    )
                    reorder()
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "Level ${index + 1}: ${level.field.displayName}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "${level.groups.size} groups" + if (level.showEmptyGroups) " (show empty)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (level.groups.isNotEmpty()) {
                            Text(
                                level.groups.joinToString(", ") { it.label.ifBlank { "(unnamed)" } },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupingLevelEditorDialog(
    level: GroupingLevelState,
    levelIndex: Int,
    filteredTags: LazyPagingItems<String>,
    onFilterTags: (String, Set<String>) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Four header items always precede the groups: field selector, checkbox, validation
        // errors, groups header. The errors card collapses to nothing when the level is valid but
        // its item stays in the list, so counting three then shifted every drag by one — the
        // group below the dragged one moved instead, and dragging the last one did nothing.
        val headerCount = 4
        level.moveGroup(from.index - headerCount, to.index - headerCount)
    }

    val validationErrors by remember(level) { derivedStateOf { level.validate() } }

    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
        title = { Text("Edit Level ${levelIndex + 1}") },
        text = {
            LazyColumn(
                state = lazyListState,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "field_selector") {
                    GroupableFieldSelector(
                        selected = level.field,
                        onSelect = {
                            if (level.field != it) {
                                level.field = it
                                level.initializeDefaultGroups()
                            }
                        }
                    )
                }

                item(key = "show_empty") {
                    ShowEmptyGroupsCheckbox(level)
                }

                item(key = "validation_errors") {
                    LevelValidationErrorsCard(validationErrors)
                }

                item(key = "groups_header") {
                    GroupsHeader(level)
                }

                items(level.groups.size, key = { level.groups[it].id }) { groupIndex ->
                    ReorderableItem(reorderableLazyListState, key = level.groups[groupIndex].id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)

                        GroupDefinitionCard(
                            group = level.groups[groupIndex],
                            field = level.field,
                            filteredTags = filteredTags,
                            onFilterTags = onFilterTags,
                            onRemove = { level.removeGroup(groupIndex) },
                            dragModifier = Modifier.draggableHandle(),
                            reorder = {
                                ReorderControls(
                                    what = "group ${groupIndex + 1}",
                                    canMoveUp = groupIndex > 0,
                                    canMoveDown = groupIndex < level.groups.lastIndex,
                                    onMoveUp = { level.moveGroup(groupIndex, groupIndex - 1) },
                                    onMoveDown = { level.moveGroup(groupIndex, groupIndex + 1) },
                                )
                            },
                            modifier = Modifier
                                .animateItem()
                                .shadow(elevation)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDone,
                enabled = validationErrors.isEmpty()
            ) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ShowEmptyGroupsCheckbox(level: GroupingLevelState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = level.showEmptyGroups,
            onCheckedChange = { level.showEmptyGroups = it }
        )
        Text("Show empty groups")
    }
}

@Composable
private fun LevelValidationErrorsCard(validationErrors: List<GroupingValidationError>) {
    AnimatedVisibility(
        visible = validationErrors.isNotEmpty(),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                validationErrors.forEach { error ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (error) {
                                is GroupingValidationError.EmptyGroup ->
                                    "Group '${error.groupLabel.ifBlank { "(unnamed)" }}' has no values"
                                is GroupingValidationError.EmptyGroupLabel ->
                                    "A group has no name"
                                is GroupingValidationError.InvalidRange ->
                                    "Group '${error.groupLabel.ifBlank { "(unnamed)" }}' has invalid range"
                                is GroupingValidationError.EmptyLevel ->
                                    "Add at least one group"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupsHeader(level: GroupingLevelState) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Groups", style = MaterialTheme.typography.labelLarge)
            IconButton(
                onClick = { level.addGroup() },
                enabled = level.canAddGroup()
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add group")
            }
        }

        AvailableValuesHint(level)
    }
}

@Composable
private fun AvailableValuesHint(level: GroupingLevelState) {
    if (level.field.getAllPossibleValues().isNotEmpty()) {
        val allValues = level.field.getAllPossibleValues()
        val usedValues = level.groups.flatMap { it.values }.toSet()
        val availableValues = allValues.removingAll(usedValues)
        if (availableValues.isNotEmpty()) {
            Text(
                "Available values: ${availableValues.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GroupableFieldSelector(
    selected: GroupableField,
    onSelect: (GroupableField) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Group by") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            GroupableField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(field.displayName) },
                    onClick = {
                        onSelect(field)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun GroupDefinitionCard(
    group: GroupDefinitionState,
    field: GroupableField,
    filteredTags: LazyPagingItems<String>,
    onFilterTags: (String, Set<String>) -> Unit,
    onRemove: () -> Unit,
    dragModifier: Modifier = Modifier,
    reorder: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            GroupDefinitionCardHeader(
                group = group,
                onRemove = onRemove,
                reorder = reorder,
                dragModifier = dragModifier
            )

            Spacer(Modifier.height(8.dp))

            GroupDefinitionCardFieldEditor(
                group = group,
                field = field,
                filteredTags = filteredTags,
                onFilterTags = onFilterTags
            )

            GroupDefinitionCardOrderingSection(
                group = group,
                expanded = expanded,
                onExpandedChange = { expanded = it }
            )
        }
    }
}

@Composable
private fun GroupDefinitionCardHeader(
    group: GroupDefinitionState,
    onRemove: () -> Unit,
    dragModifier: Modifier,
    reorder: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Drag to reorder",
            modifier = dragModifier
        )
        reorder()
        OutlinedTextField(
            value = group.label,
            onValueChange = { group.label = it },
            label = { Text("Group label") },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            singleLine = true,
            isError = group.label.isBlank(),
            supportingText = if (group.label.isBlank()) {{ Text("Label is required") }} else null
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove")
        }
    }
}

@Composable
private fun GroupDefinitionCardFieldEditor(
    group: GroupDefinitionState,
    field: GroupableField,
    filteredTags: LazyPagingItems<String>,
    onFilterTags: (String, Set<String>) -> Unit
) {
    when (field) {
        GroupableField.Priority -> PriorityRangeEditor(group)
        GroupableField.EstimatedTime -> EstimatedTimeRangeEditor(group)
        GroupableField.DueDate -> DueDateRangeEditor(group)
        else -> PredefinedValuesEditor(group, field, filteredTags, onFilterTags)
    }
}

@Composable
private fun PriorityRangeEditor(group: GroupDefinitionState) {
    val priorityError = group.validatePriorityRange()
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = group.priorityMinText,
                onValueChange = { group.priorityMinText = it },
                label = { Text("Min") },
                modifier = Modifier.width(80.dp),
                singleLine = true,
                isError = priorityError != null
            )
            Text("to")
            OutlinedTextField(
                value = group.priorityMaxText,
                onValueChange = { group.priorityMaxText = it },
                label = { Text("Max") },
                modifier = Modifier.width(80.dp),
                singleLine = true,
                isError = priorityError != null
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = group.includeNoPriority,
                onCheckedChange = { group.includeNoPriority = it }
            )
            Text("Include tasks without priority", style = MaterialTheme.typography.bodySmall)
        }
        AnimatedVisibility(
            visible = priorityError != null,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Text(
                priorityError ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun EstimatedTimeRangeEditor(group: GroupDefinitionState) {
    val timeError = group.validateEstimatedTimeRange()
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = group.estimatedTimeMinText,
                onValueChange = { group.estimatedTimeMinText = it },
                label = { Text("Min") },
                modifier = Modifier.width(100.dp),
                singleLine = true,
                isError = timeError != null
            )
            Text("to")
            OutlinedTextField(
                value = group.estimatedTimeMaxText,
                onValueChange = { group.estimatedTimeMaxText = it },
                label = { Text("Max") },
                modifier = Modifier.width(100.dp),
                singleLine = true,
                isError = timeError != null
            )
        }
        Text(
            "Format: 2h 30m, 1d, 1w 2d",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = group.includeNoEstimatedTime,
                onCheckedChange = { group.includeNoEstimatedTime = it }
            )
            Text("Include tasks without estimate", style = MaterialTheme.typography.bodySmall)
        }
        AnimatedVisibility(
            visible = timeError != null,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Text(
                timeError ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun DueDateRangeEditor(group: GroupDefinitionState) {
    val dueDateError = group.validateDueDateRange()
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = group.dueDateMinDaysText,
                onValueChange = { group.dueDateMinDaysText = it },
                label = { Text("From") },
                modifier = Modifier.width(80.dp),
                singleLine = true,
                isError = dueDateError != null
            )
            Text("to")
            OutlinedTextField(
                value = group.dueDateMaxDaysText,
                onValueChange = { group.dueDateMaxDaysText = it },
                label = { Text("To") },
                modifier = Modifier.width(80.dp),
                singleLine = true,
                isError = dueDateError != null
            )
            Text("days")
        }
        Text(
            "Negative = past, 0 = today, positive = future",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = group.includeNoDueDate,
                onCheckedChange = { group.includeNoDueDate = it }
            )
            Text("Include tasks without due date", style = MaterialTheme.typography.bodySmall)
        }
        AnimatedVisibility(
            visible = dueDateError != null,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Text(
                dueDateError ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PredefinedValuesEditor(
    group: GroupDefinitionState,
    field: GroupableField,
    filteredTags: LazyPagingItems<String>,
    onFilterTags: (String, Set<String>) -> Unit
) {
    Text("Values:", style = MaterialTheme.typography.labelMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        group.values.forEach { value ->
            InputChip(
                selected = true,
                onClick = { group.removeValue(value) },
                label = { Text(value) },
                trailingIcon = {
                    Icon(Icons.Default.Close, contentDescription = "Remove", Modifier.size(16.dp))
                }
            )
        }

        AddValueButton(group, field, filteredTags, onFilterTags)
    }
}

@Composable
private fun AddValueButton(
    group: GroupDefinitionState,
    field: GroupableField,
    filteredTags: LazyPagingItems<String>,
    onFilterTags: (String, Set<String>) -> Unit
) {
    // Whether the field has a listable set of values. Gating this on "must cover every value"
    // instead left Status and the boolean fields with no way to add one back once removed.
    if (field.getAllPossibleValues().isNotEmpty()) {
        val availableValues = field.getAllPossibleValues().removingAll(group.values)
        if (availableValues.isNotEmpty()) {
            var showMenu by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { showMenu = true },
                    label = { Text("+") }
                )
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    availableValues.forEach { value ->
                        DropdownMenuItem(
                            text = { Text(value) },
                            onClick = {
                                group.addValue(value)
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }
    } else if (field == GroupableField.Tags) {
        var showTagDialog by remember { mutableStateOf(false) }

        AssistChip(
            onClick = { showTagDialog = true },
            label = { Text("Add tag") },
            leadingIcon = {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
            }
        )
        if (showTagDialog) {
            TagSelectionDialog(
                selectedTags = group.values.toSet(),
                filteredTags = filteredTags,
                onFilterTags = onFilterTags,
                onDismiss = { showTagDialog = false },
                onTagSelected = { tag ->
                    group.addValue(tag)
                    showTagDialog = false
                }
            )
        }
    }
}

@Composable
private fun GroupDefinitionCardOrderingSection(
    group: GroupDefinitionState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onExpandedChange(!expanded) }) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (group.orderingRules.isEmpty()) "Order (uses default)"
                else "Order (${group.orderingRules.size} rules)"
            )
        }
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        GroupOrderingRulesSection(group)
    }
}

@Composable
private fun GroupOrderingRulesSection(group: GroupDefinitionState) {
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        group.moveOrderingRule(from.index, to.index)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Order rules", style = MaterialTheme.typography.labelMedium)
            Row {
                if (group.orderingRules.isNotEmpty()) {
                    TextButton(onClick = { group.orderingRules.clear() }) {
                        Text("Clear")
                    }
                }
                IconButton(onClick = { group.addOrderingRule() }) {
                    Icon(Icons.Default.Add, contentDescription = "Add ordering rule")
                }
            }
        }

        if (group.orderingRules.isEmpty()) {
            Text(
                "Using default ordering",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(group.orderingRules.size, key = { group.orderingRules[it].id }) { index ->
                    ReorderableItem(reorderableLazyListState, key = group.orderingRules[index].id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 1.dp)

                        OrderingRuleRow(
                            rule = group.orderingRules[index],
                            onRemove = { group.removeOrderingRule(index) },
                            dragModifier = Modifier.draggableHandle(),
                            reorder = {
                                ReorderControls(
                                    what = "rule ${index + 1}",
                                    canMoveUp = index > 0,
                                    canMoveDown = index < group.orderingRules.lastIndex,
                                    onMoveUp = { group.moveOrderingRule(index, index - 1) },
                                    onMoveDown = { group.moveOrderingRule(index, index + 1) },
                                )
                            },
                            modifier = Modifier.shadow(elevation, shape = MaterialTheme.shapes.small)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultOrderingRulesSection(state: ViewModeEditorState) {
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        state.moveOrderingRule(from.index, to.index)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Default Order", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { state.addOrderingRule() }) {
                    Icon(Icons.Default.Add, contentDescription = "Add ordering rule")
                }
            }

            Text(
                "Applied when groups don't have custom order",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                state = lazyListState,
                modifier = Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.defaultOrderingRules.size, key = { state.defaultOrderingRules[it].id }) { index ->
                    ReorderableItem(reorderableLazyListState, key = state.defaultOrderingRules[index].id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 1.dp)

                        OrderingRuleRow(
                            rule = state.defaultOrderingRules[index],
                            onRemove = { state.removeOrderingRule(index) },
                            canRemove = state.defaultOrderingRules.size > 1,
                            dragModifier = Modifier.draggableHandle(),
                            reorder = {
                                ReorderControls(
                                    what = "rule ${index + 1}",
                                    canMoveUp = index > 0,
                                    canMoveDown = index < state.defaultOrderingRules.lastIndex,
                                    onMoveUp = { state.moveOrderingRule(index, index - 1) },
                                    onMoveDown = { state.moveOrderingRule(index, index + 1) },
                                )
                            },
                            modifier = Modifier.shadow(elevation, shape = MaterialTheme.shapes.small)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderingRuleRow(
    rule: OrderingRuleState,
    onRemove: () -> Unit,
    canRemove: Boolean = true,
    dragModifier: Modifier = Modifier,
    reorder: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            reorder()
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                modifier = dragModifier
            )

            // Field selector
            var fieldExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = fieldExpanded,
                onExpandedChange = { fieldExpanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = rule.field.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Field") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fieldExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                )
                ExposedDropdownMenu(
                    expanded = fieldExpanded,
                    onDismissRequest = { fieldExpanded = false }
                ) {
                    OrderableField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = { Text(field.displayName) },
                            onClick = {
                                rule.field = field
                                fieldExpanded = false
                            }
                        )
                    }
                }
            }

            // Null position toggle (only for nullable fields)
            if (rule.field.canBeNull()) {
                IconButton(
                    onClick = {
                        rule.nullPosition = if (rule.nullPosition == NullPosition.First)
                            NullPosition.Last else NullPosition.First
                    }
                ) {
                    NullPositionIcon(rule.nullPosition)
                }
            }

            // Direction toggle
            IconButton(
                onClick = {
                    rule.direction = if (rule.direction == OrderDirection.Ascending)
                        OrderDirection.Descending else OrderDirection.Ascending
                }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = rule.direction.displayName,
                    modifier = if (rule.direction == OrderDirection.Ascending)
                        Modifier.scale(scaleX = 1f, scaleY = -1f) else Modifier
                )
            }

            // Remove button
            IconButton(onClick = onRemove, enabled = canRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove")
            }
        }
    }
}

@Composable
private fun NullPositionIcon(nullPosition: NullPosition) {
    val density = LocalDensity.current
    // Use fixed pixel size independent of density
    val textSize = with(density) { 8.dp.toSp() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (nullPosition == NullPosition.First) {
            Text(
                text = "null",
                fontSize = textSize,
                fontWeight = FontWeight.Bold,
                lineHeight = textSize
            )
            Icon(
                painterResource(Res.drawable.ic_align_start),
                contentDescription = "Nulls first",
                modifier = Modifier.size(16.dp)
            )
        } else {
            Icon(
                painterResource(Res.drawable.ic_align_end),
                contentDescription = "Nulls last",
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "null",
                fontSize = textSize,
                fontWeight = FontWeight.Bold,
                lineHeight = textSize
            )
        }
    }
}

/**
 * Shown when the view mode to edit could not be read.
 *
 * Not an empty editor: that one offers Save, and saving would create a second view mode rather
 * than change the one the user opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewModeEditorPlaceholder(onCancel: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit View Mode") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
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
            Text("This view mode could not be opened.", textAlign = TextAlign.Center)
        }
    }
}
