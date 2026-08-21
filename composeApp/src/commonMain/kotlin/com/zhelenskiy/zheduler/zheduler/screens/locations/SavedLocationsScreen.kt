@file:OptIn(ExperimentalMaterial3Api::class)

package com.zhelenskiy.zheduler.zheduler.screens.locations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.components.common.EmptySearchResults
import com.zhelenskiy.zheduler.zheduler.components.common.EmptyState
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DeleteConfirmationDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.PlaceEditorDialog
import com.zhelenskiy.zheduler.zheduler.components.map.LocationPermissionNotice
import com.zhelenskiy.zheduler.zheduler.components.map.formatCoordinates
import com.zhelenskiy.zheduler.zheduler.components.map.formatDistance
import com.zhelenskiy.zheduler.zheduler.geo.SavedLocation
import com.zhelenskiy.zheduler.zheduler.geo.rememberLocationPermission
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMenuButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.SavedLocationContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.SavedLocationIntent
import pro.respawn.flowmvi.compose.dsl.subscribe

/**
 * The address book of places, and where they are added, renamed and removed.
 *
 * Deleting one here changes no rule. A rule that watches somewhere took its own copy of the area
 * when it was written, which is what lets a task be exported to a device that has never heard of
 * this book — so this list is a convenience for writing rules, not the rules' own storage.
 */
@Composable
fun SavedLocationsScreen(
    container: SavedLocationContainer,
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit,
) {
    val state by container.store.subscribe()
    val permission = rememberLocationPermission()

    // By id and saved, so an activity recreation does not close the editor and drop the edit —
    // and so that reopening it lands on the place that was being edited, not on whichever one the
    // dialog's own saved fields happened to belong to.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }

    val editing = editingId?.let { id -> state.locations.firstOrNull { it.id == id } }
    val deleting = deletingId?.let { id -> state.locations.firstOrNull { it.id == id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Places") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    ThemeMenuButton(
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        useDynamicColors = useDynamicColors,
                        onDynamicColorsChange = onDynamicColorsChange,
                        colorSettings = colorSettings,
                        onColorSettingsChange = onColorSettingsChange,
                    )
                },
                colors = appTopAppBarColors(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add a place")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocationPermissionNotice(permission, modifier = Modifier.padding(top = 8.dp))

            if (state.locations.isNotEmpty()) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { container.store.intent(SavedLocationIntent.Filter(it)) },
                    label = { Text("Find a place you have saved") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { container.store.intent(SavedLocationIntent.Filter("")) }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear the search")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val matching = state.matching
            when {
                state.locations.isEmpty() -> EmptyState(
                    message = "No places yet. Add one, and a task can come round when you arrive " +
                        "there or leave it.",
                )

                matching.isEmpty() -> EmptySearchResults(
                    message = "No saved place matches \"${state.query}\".",
                    clearButtonText = "Clear the search",
                    onClearFilters = { container.store.intent(SavedLocationIntent.Filter("")) },
                )

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(matching, key = { it.id }) { location ->
                        SavedLocationCard(
                            location = location,
                            onEdit = { editingId = location.id },
                            onDelete = { deletingId = location.id },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        PlaceEditorDialog(
            existing = null,
            newId = container::generateId,
            onSave = {
                container.store.intent(SavedLocationIntent.Save(it))
                adding = false
            },
            onDismiss = { adding = false },
        )
    }

    editing?.let { location ->
        PlaceEditorDialog(
            existing = location,
            newId = container::generateId,
            onSave = {
                container.store.intent(SavedLocationIntent.Save(it))
                editingId = null
            },
            onDismiss = { editingId = null },
        )
    }

    deleting?.let { location ->
        DeleteConfirmationDialog(
            title = "Delete place",
            message = "Delete \"${location.name}\"? Rules that already watch it keep working — " +
                "they hold their own copy.",
            onConfirm = {
                container.store.intent(SavedLocationIntent.Delete(location.id))
                deletingId = null
            },
            onDismiss = { deletingId = null },
        )
    }
}

@Composable
private fun SavedLocationCard(
    location: SavedLocation,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(location.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = location.address.ifBlank {
                        formatCoordinates(location.point.latitude, location.point.longitude)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Within ${formatDistance(location.radiusMeters)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit ${location.name}")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${location.name}")
            }
        }
    }
}
