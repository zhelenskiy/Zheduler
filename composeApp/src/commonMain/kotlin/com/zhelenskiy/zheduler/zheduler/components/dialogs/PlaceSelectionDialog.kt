package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.material3.AlertDialog
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger
import com.zhelenskiy.zheduler.zheduler.components.common.jsonSaver
import com.zhelenskiy.zheduler.zheduler.components.map.LocalPlaceBook
import com.zhelenskiy.zheduler.zheduler.components.map.LocationPermissionNotice
import com.zhelenskiy.zheduler.zheduler.components.map.OsmMap
import com.zhelenskiy.zheduler.zheduler.components.map.formatDistance
import com.zhelenskiy.zheduler.zheduler.components.map.rememberMapCamera
import com.zhelenskiy.zheduler.zheduler.geo.GeoArea
import com.zhelenskiy.zheduler.zheduler.geo.GeoPoint
import com.zhelenskiy.zheduler.zheduler.geo.GeofenceDirection
import com.zhelenskiy.zheduler.zheduler.geo.SavedLocation
import com.zhelenskiy.zheduler.zheduler.geo.TileMath
import com.zhelenskiy.zheduler.zheduler.geo.rememberLocationPermission
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/**
 * Chooses the places a rule watches, and which way across their edge it is waiting for.
 *
 * The areas chosen are *copied* into the rule. Renaming or deleting one in the address book
 * afterwards leaves the rule exactly as it was, which is what lets a task be exported to a device
 * that has never heard of this book — and is why a rule can hold a place that is no longer in it.
 */
@Composable
fun PlaceSelectionDialog(
    current: RecurrenceTrigger.LocationChange?,
    onDismiss: () -> Unit,
    onConfirm: (RecurrenceTrigger.LocationChange?) -> Unit,
) {
    val book = LocalPlaceBook.current
    val permission = rememberLocationPermission()

    // Kept as the whole trigger rather than as its two halves: it is the thing that is already
    // serializable, and the places inside it need the same set serializer the rule is stored with.
    var editing by rememberSaveable(
        key = "places:editing",
        stateSaver = jsonSaver { RecurrenceTrigger.LocationChange(persistentSetOf()) },
    ) { mutableStateOf(current ?: RecurrenceTrigger.LocationChange(persistentSetOf())) }

    val chosen = editing.areas
    val direction = editing.direction

    var adding by rememberSaveable(key = "places:adding") { mutableStateOf(false) }

    val camera = rememberMapCamera(
        center = chosen.firstOrNull()?.point ?: GeoPoint.Zero,
        zoom = if (chosen.isEmpty()) 2 else TileMath.DEFAULT_ZOOM,
        key = "places:camera",
    )

    // Anything the rule already watched that is no longer in the book — deleted since, or never
    // saved. Shown alongside, so a rule's own places cannot be lost by editing it.
    val loose = chosen.filterNot { area -> book.places.any { it.toArea().key == area.key } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Places") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Fire the rule when", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GeofenceDirection.entries.forEach { option ->
                        FilterChip(
                            selected = direction == option,
                            onClick = { editing = editing.copy(direction = option) },
                            label = { Text(option.displayName, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                HorizontalDivider()

                if (book.places.isEmpty() && loose.isEmpty()) {
                    Text(
                        text = "No places yet. Add one and this rule can wait for it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (book.places.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(book.places, key = { it.id }) { saved ->
                            val area = saved.toArea()
                            val isChosen = chosen.any { it.key == area.key }
                            PlaceRow(
                                location = saved,
                                selected = isChosen,
                                onToggle = {
                                    editing = if (isChosen) {
                                        editing.copy(areas = chosen.filterNot { it.key == area.key }.toPersistentSet())
                                    } else {
                                        camera.show(area.point, TileMath.DEFAULT_ZOOM)
                                        editing.copy(areas = chosen.add(area))
                                    }
                                },
                            )
                        }
                    }
                }

                if (loose.isNotEmpty()) {
                    Text(
                        text = "Watched by this rule but not in your places",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    loose.forEach { area ->
                        LooseAreaRow(
                            area = area,
                            onRemove = { editing = editing.copy(areas = chosen.filterNot { it.key == area.key }.toPersistentSet()) },
                            onKeep = {
                                book.save(
                                    SavedLocation(
                                        id = book.newId(),
                                        name = area.name,
                                        point = area.point,
                                        radiusMeters = area.radiusMeters,
                                    )
                                )
                            },
                        )
                    }
                }

                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Add a place")
                }

                OsmMap(
                    camera = camera,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    areas = chosen.toList(),
                )

                LocationPermissionNotice(permission)
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.isNotEmpty(),
                onClick = { onConfirm(editing) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (adding) {
        PlaceEditorDialog(
            existing = null,
            newId = book::newId,
            onSave = { saved ->
                book.save(saved)
                // Chosen as well as kept: someone who adds a place from inside a rule meant that
                // rule to watch it, and making them tick it afterwards is a step for nothing.
                editing = editing.copy(areas = chosen.add(saved.toArea()))
                camera.show(saved.point, TileMath.DEFAULT_ZOOM)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun PlaceRow(
    location: SavedLocation,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(location.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Within ${formatDistance(location.radiusMeters)}" +
                    location.address.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LooseAreaRow(
    area: GeoArea,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = true, onCheckedChange = { onRemove() })
        Column(modifier = Modifier.weight(1f)) {
            Text(area.name.ifBlank { "Unnamed place" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Within ${formatDistance(area.radiusMeters)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onKeep) {
            Icon(Icons.Default.BookmarkAdd, contentDescription = "Keep ${area.name} in my places")
        }
    }
}
