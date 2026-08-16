@file:OptIn(ExperimentalMaterial3Api::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist.savedfilter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.SavedFilter
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import com.zhelenskiy.zheduler.zheduler.ViewMode
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DiscardChangesDialog
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.FilterChip
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.TaskFilterPanel
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.TaskFilterState
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.buildFilterChipsFromCriteria

/**
 * Dialog for saving or editing a filter.
 *
 * @param existingFilter If provided, the dialog is in edit mode and will update this filter.
 *                       If null, the dialog creates a new filter.
 * @param criteria The filter criteria to save (used when creating new filter).
 * @param viewModes Available view modes to attach.
 * @param currentActiveViewModeId The currently active view mode (used as default for new filters).
 * @param spaceId The space ID for the filter.
 * @param allTags All available tags for the filter panel.
 * @param spaceIdPrefix The space ID prefix for task ID validation.
 * @param generateId Function to generate a unique ID for new filters.
 * @param onSave Called when the user saves the filter.
 * @param onDismiss Called when the dialog is dismissed.
 */
@Composable
fun SaveFilterDialog(
    existingFilter: SavedFilter? = null,
    criteria: TaskFilterCriteria,
    viewModes: List<ViewMode>,
    currentActiveViewModeId: String?,
    spaceId: String,
    allTags: Set<String>,
    spaceIdPrefix: String?,
    generateId: () -> String,
    onSave: (SavedFilter) -> Unit,
    onDismiss: () -> Unit
) {
    val isEditMode = existingFilter != null
    // Saved, not remembered: the dialog itself reopens after an activity recreation, and used to
    // come back with the name field blank. The criteria panel below still resets to what the
    // dialog was opened with — that at least stays on screen, where the name simply vanished.
    // The saved-state key names the filter being edited, not just this spot in the dialog. Saved
    // state belongs to a position in the composition, and this dialog is the same position for
    // every filter, so state saved while editing one was handed to the next filter opened after a
    // recreation — and saving renamed that one instead. The id in the key means a restored value
    // is only ever found by the filter it was written for. (Passing the id as an input would not
    // do: inputs re-run init within a composition, but a restored value is taken up on the slot's
    // first composition whatever they say.)
    val slot = existingFilter?.id ?: "new"
    var name by rememberSaveable(key = "saveFilter:name:$slot") {
        mutableStateOf(existingFilter?.name ?: "")
    }
    var selectedViewModeId by rememberSaveable(key = "saveFilter:viewMode:$slot") {
        mutableStateOf(existingFilter?.viewModeId ?: currentActiveViewModeId)
    }
    var showDiscardChangesDialog by rememberSaveable(key = "saveFilter:discard:$slot") {
        mutableStateOf(false)
    }

    val filterState = remember {
        TaskFilterState().apply {
            loadFromCriteria(existingFilter?.criteria ?: criteria)
        }
    }

    val isValid = name.isNotBlank()

    val hasUnsavedChanges = !isEditMode || name != existingFilter.name ||
            selectedViewModeId != existingFilter.viewModeId || filterState.toCriteria() != existingFilter.criteria

    val handleDismiss: () -> Unit = {
        if (hasUnsavedChanges) {
            showDiscardChangesDialog = true
        } else {
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = handleDismiss,
        title = { Text(if (isEditMode) "Edit Filter" else "Save Filter") },
        text = {
            SaveFilterDialogContent(
                name = name,
                onNameChange = { name = it },
                viewModes = viewModes,
                selectedViewModeId = selectedViewModeId,
                onViewModeSelected = { selectedViewModeId = it },
                filterState = filterState,
                allTags = allTags,
                spaceIdPrefix = spaceIdPrefix
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val filter = SavedFilter(
                        id = existingFilter?.id ?: generateId(),
                        name = name,
                        spaceId = spaceId,
                        criteria = filterState.toCriteria(),
                        viewModeId = selectedViewModeId
                    )
                    onSave(filter)
                },
                enabled = isValid
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = handleDismiss) {
                Text("Cancel")
            }
        }
    )

    if (showDiscardChangesDialog) {
        DiscardChangesDialog(
            title = "Discard Changes",
            message = "You have unsaved changes. Are you sure you want to discard them?",
            confirmText = "Discard",
            dismissText = "Keep Editing",
            onConfirm = {
                showDiscardChangesDialog = false
                onDismiss()
            },
            onDismiss = { showDiscardChangesDialog = false }
        )
    }
}

@Composable
private fun SaveFilterDialogContent(
    name: String,
    onNameChange: (String) -> Unit,
    viewModes: List<ViewMode>,
    selectedViewModeId: String?,
    onViewModeSelected: (String?) -> Unit,
    filterState: TaskFilterState,
    allTags: Set<String>,
    spaceIdPrefix: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FilterNameField(name = name, onNameChange = onNameChange)
        ViewModeSection(
            viewModes = viewModes,
            selectedViewModeId = selectedViewModeId,
            onViewModeSelected = onViewModeSelected
        )
        SearchQueryField(filterState = filterState)
        TaskFilterPanel(
            filterState = filterState,
            allTags = allTags,
            spaceIdPrefix = spaceIdPrefix
        )
        PreviewSection(criteria = filterState.toCriteria())
    }
}

@Composable
private fun FilterNameField(
    name: String,
    onNameChange: (String) -> Unit
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Filter Name") },
        placeholder = { Text("Enter a name for this filter") },
        singleLine = true,
        isError = name.isBlank(),
        supportingText = if (name.isBlank()) {
            { Text("Name is required") }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ViewModeSection(
    viewModes: List<ViewMode>,
    selectedViewModeId: String?,
    onViewModeSelected: (String?) -> Unit
) {
    Text(
        text = "Attached View Mode (optional)",
        style = MaterialTheme.typography.titleSmall
    )
    Text(
        text = "When loading this filter, the selected view mode will also be applied",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ViewModeSelectorDropdown(
        viewModes = viewModes,
        selectedViewModeId = selectedViewModeId,
        onViewModeSelected = onViewModeSelected
    )
}

@Composable
private fun SearchQueryField(filterState: TaskFilterState) {
    OutlinedTextField(
        value = filterState.searchQuery,
        onValueChange = { filterState.searchQuery = it },
        label = { Text("Search Query") },
        placeholder = {
            Text(
                text = "Search in ${filterState.textSearchFields.joinToString(", ") { it.displayName }}"
            )
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (filterState.searchQuery.isNotBlank()) {
                IconButton(onClick = { filterState.searchQuery = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        }
    )
}

@Composable
private fun PreviewSection(criteria: TaskFilterCriteria) {
    Text(
        text = "Preview",
        style = MaterialTheme.typography.titleSmall
    )
    FilterCriteriaChips(criteria = criteria)
}

@Composable
private fun ViewModeSelectorDropdown(
    viewModes: List<ViewMode>,
    selectedViewModeId: String?,
    onViewModeSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedViewMode = viewModes.find { it.id == selectedViewModeId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedViewMode?.name ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text("View Mode") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    onViewModeSelected(null)
                    expanded = false
                }
            )
            viewModes.forEach { viewMode ->
                DropdownMenuItem(
                    text = { Text(viewMode.name) },
                    onClick = {
                        onViewModeSelected(viewMode.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Displays filter criteria as a FlowRow of suggestion chips.
 * Reusable component for showing filter criteria in dialogs and cards.
 *
 * @param criteria The filter criteria to display.
 * @param showEmptyState If true, shows "No filters applied" when there are no active filters.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterCriteriaChips(criteria: TaskFilterCriteria, showEmptyState: Boolean = true) {
    val chips = buildFilterChipsFromCriteria(criteria)

    if (chips.isEmpty()) {
        if (showEmptyState) {
            Text(
                text = "No filters applied",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            chips.values.forEach { chip ->
                FilterChip(text = chip)
            }
        }
    }
}
