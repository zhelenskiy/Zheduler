package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.components.common.EmptySearchResults
import com.zhelenskiy.zheduler.zheduler.components.common.EmptyState
import com.zhelenskiy.zheduler.zheduler.components.map.LocalPlaceBook
import com.zhelenskiy.zheduler.zheduler.components.map.LocationPermissionNotice
import com.zhelenskiy.zheduler.zheduler.components.map.formatCoordinates
import com.zhelenskiy.zheduler.zheduler.components.map.formatDistance
import com.zhelenskiy.zheduler.zheduler.geo.GeoPoint
import com.zhelenskiy.zheduler.zheduler.geo.rememberWhereabouts
import com.zhelenskiy.zheduler.zheduler.geo.SavedLocation
import com.zhelenskiy.zheduler.zheduler.geo.metresOutside
import com.zhelenskiy.zheduler.zheduler.geo.rememberLocationPermission

/**
 * The address book of places, opened from the settings list.
 *
 * A dialog and not a screen of its own: it is reached from a dialog, and sending the user off to
 * another page to add a place — then back through it to whatever they were doing — made a
 * two-second job feel like leaving the app.
 *
 * Deleting one here changes no rule. A rule that watches somewhere took its own copy of the area
 * when it was written, which is what lets a task be exported to a device that has never heard of
 * this book — so this list is a convenience for writing rules, not the rules' own storage.
 */
@Composable
fun PlacesDialog(onDismiss: () -> Unit) {
    val book = LocalPlaceBook.current
    val permission = rememberLocationPermission()
    // By id and saved, so a recreation does not close the editor and drop the edit — and so that
    // reopening it lands on the place that was being edited, not on whichever one the editor's own
    // saved fields happened to belong to.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    // Only while this is the screen in front. The editor stacks on top of this dialog without
    // closing it, and both want a distance — polled from both, a phone would be asked for a fix
    // twice as often for a list nobody can see behind the editor.
    val here = if (adding || editingId != null) null else rememberWhereabouts()

    val editing = editingId?.let { id -> book.places.firstOrNull { it.id == id } }
    val deleting = deletingId?.let { id -> book.places.firstOrNull { it.id == id } }
    val matching = book.places.filter { it.matches(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Places", modifier = Modifier.weight(1f))
                IconButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add a place")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LocationPermissionNotice(permission)

                if (book.places.isNotEmpty()) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        label = "Find a place you have saved",
                    )
                }

                when {
                    book.places.isEmpty() -> EmptyState(
                        message = "No places yet. Add one, and a task can come round when you " +
                            "arrive there or leave it.",
                    )

                    matching.isEmpty() -> EmptySearchResults(
                        message = "No saved place matches \"$query\".",
                        clearButtonText = "Clear the search",
                        onClearFilters = { query = "" },
                    )

                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(matching, key = { it.id }) { place ->
                            PlaceRow(
                                place = place,
                                here = here,
                                onEdit = { editingId = place.id },
                                onDelete = { deletingId = place.id },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    if (adding) {
        PlaceEditorDialog(
            existing = null,
            newId = book::newId,
            onSave = {
                book.save(it)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }

    editing?.let { place ->
        PlaceEditorDialog(
            existing = place,
            newId = book::newId,
            onSave = {
                book.save(it)
                editingId = null
            },
            onDismiss = { editingId = null },
        )
    }

    deleting?.let { place ->
        DeleteConfirmationDialog(
            title = "Delete place",
            message = "Delete \"${place.name}\"? Rules that already watch it keep working — " +
                "they hold their own copy.",
            onConfirm = {
                book.delete(place.id)
                deletingId = null
            },
            onDismiss = { deletingId = null },
        )
    }
}

@Composable
internal fun SearchField(query: String, onQueryChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(label) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear the search")
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlaceRow(
    place: SavedLocation,
    here: GeoPoint?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = place.address.ifBlank {
                    formatCoordinates(place.point.latitude, place.point.longitude)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The radius, and — where the device knows where it is — how far off that is from
                // here. Which is the question a list of places is really being read to answer.
                text = "Within ${formatDistance(place.radiusMeters)}" +
                    (here?.let { " · ${place.awayFrom(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit ${place.name}")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${place.name}")
        }
    }
}

/** How far this place is from [here], in the words a person would use. See [metresOutside]. */
private fun SavedLocation.awayFrom(here: GeoPoint): String {
    val toEdge = metresOutside(here, toArea())
    return if (toEdge <= 0) "you are here" else "${formatDistance(toEdge)} away"
}
