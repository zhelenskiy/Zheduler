package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.components.common.jsonSaver
import com.zhelenskiy.zheduler.zheduler.components.map.LocationPermissionNotice
import com.zhelenskiy.zheduler.zheduler.components.map.OsmMap
import com.zhelenskiy.zheduler.zheduler.components.map.PlaceSearchField
import com.zhelenskiy.zheduler.zheduler.components.map.RadiusSlider
import com.zhelenskiy.zheduler.zheduler.components.map.UseMyLocationButton
import com.zhelenskiy.zheduler.zheduler.components.map.formatCoordinates
import com.zhelenskiy.zheduler.zheduler.components.map.rememberMapCamera
import com.zhelenskiy.zheduler.zheduler.components.map.rememberPlaceSearch
import com.zhelenskiy.zheduler.zheduler.geo.GeoArea
import com.zhelenskiy.zheduler.zheduler.geo.GeoPoint
import com.zhelenskiy.zheduler.zheduler.geo.LocationPermissionStatus
import com.zhelenskiy.zheduler.zheduler.geo.SavedLocation
import com.zhelenskiy.zheduler.zheduler.geo.TileMath
import com.zhelenskiy.zheduler.zheduler.geo.createLocationSource
import com.zhelenskiy.zheduler.zheduler.geo.rememberLocationPermission
import kotlinx.coroutines.launch

/**
 * Adds a place to the address book, or changes one that is already in it.
 *
 * Three ways in, because none of them is enough on its own: search for it by name, tap it on the
 * map, or take where the device is now. The map is always shown, so whichever way was used the
 * user can see what they have actually chosen and how wide it is.
 *
 * @param existing the place being changed, or null when one is being added.
 * @param onSave given the place as it should be stored; the caller decides where it goes.
 */
@Composable
fun PlaceEditorDialog(
    existing: SavedLocation?,
    newId: () -> String,
    onSave: (SavedLocation) -> Unit,
    onDismiss: () -> Unit,
) {
    // Saved rather than remembered: nothing here reaches the book until Save, so a recreation
    // used to take the whole edit with it. Keyed by which place is being edited, because inputs
    // do not gate a restore — only the key does — and the next place opened would otherwise come
    // up holding the last one's name.
    val slot = existing?.id ?: "new"
    var name by rememberSaveable(key = "place:name:$slot") { mutableStateOf(existing?.name.orEmpty()) }
    var address by rememberSaveable(key = "place:address:$slot") { mutableStateOf(existing?.address.orEmpty()) }
    var point by rememberSaveable(
        key = "place:point:$slot",
        stateSaver = jsonSaver { existing?.point ?: GeoPoint.Zero },
    ) { mutableStateOf(existing?.point ?: GeoPoint.Zero) }
    var radius by rememberSaveable(key = "place:radius:$slot") {
        mutableStateOf(existing?.radiusMeters ?: GeoArea.DEFAULT_RADIUS_METERS)
    }
    var placed by rememberSaveable(key = "place:placed:$slot") { mutableStateOf(existing != null) }
    var finding by remember { mutableStateOf(false) }

    val search = rememberPlaceSearch(key = "place:search:$slot")
    val permission = rememberLocationPermission()
    // The map's own height in pixels, which is what decides the zoom a given radius fits at. Taken
    // from the size it is given below rather than measured, so it is known before the first frame.
    val viewportPixels = with(LocalDensity.current) { MAP_HEIGHT.toPx() }
    val camera = rememberMapCamera(
        center = existing?.point ?: GeoPoint.Zero,
        zoom = if (existing == null) WORLD_ZOOM
        else TileMath.zoomFor(existing.radiusMeters, existing.point.latitude, viewportPixels.toDouble()),
        key = "place:camera:$slot",
    )
    val scope = rememberCoroutineScope()
    val source = remember { createLocationSource() }

    // A place that has never been put anywhere is not one that is in the Atlantic: until the user
    // has chosen a spot, there is nothing to save and nothing to draw a circle around.
    val area = if (placed) GeoArea(name = name, point = point, radiusMeters = radius) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add a place" else "Edit place") },
        text = {
            // Stopped while a finger is on the map. A pinch and a pan are mostly vertical
            // movement, and a scrolling column claims that the moment it passes touch slop — so on
            // a phone the map moved only when the fingers happened to travel level, which reads as
            // a map whose gestures do not work. The scroll comes back the instant the map is let
            // go, so the rest of the form still scrolls.
            var mapHeld by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState(), enabled = !mapHeld),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                PlaceSearchField(
                    query = search.query,
                    onQueryChange = search::type,
                    results = search.results,
                    searching = search.searching,
                    onPick = { result ->
                        point = result.point
                        address = result.address
                        // Only where the user has not written one: a place they have named "Mum's"
                        // should not become "14 Elm Street" because they searched for it again.
                        if (name.isBlank()) name = result.name
                        placed = true
                        camera.show(result.point, TileMath.zoomFor(radius, result.point.latitude, viewportPixels.toDouble()))
                        search.accept()
                    },
                )

                OsmMap(
                    camera = camera,
                    modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT),
                    areas = listOfNotNull(area),
                    highlights = if (placed) emptyList() else search.results.map { it.point },
                    onTap = { tapped ->
                        point = tapped
                        address = ""
                        placed = true
                    },
                    onHeldChange = { mapHeld = it },
                )

                Text(
                    text = if (placed) formatCoordinates(point.latitude, point.longitude)
                    else "Tap the map, search above, or take where you are now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                UseMyLocationButton(
                    enabled = permission.status != LocationPermissionStatus.Unavailable,
                    finding = finding,
                    onClick = {
                        if (permission.status != LocationPermissionStatus.Granted) {
                            permission.request()
                            return@UseMyLocationButton
                        }
                        finding = true
                        scope.launch {
                            try {
                                source.currentFix()?.let { fix ->
                                    point = fix.point
                                    address = ""
                                    placed = true
                                    camera.show(
                                        fix.point,
                                        TileMath.zoomFor(radius, fix.point.latitude, viewportPixels.toDouble()),
                                    )
                                }
                            } finally {
                                // In a `finally` so a refusal or a timeout still stops the spinner;
                                // left spinning, the button can never be pressed a second time.
                                finding = false
                            }
                        }
                    },
                )

                RadiusSlider(radiusMeters = radius, onRadiusChange = { radius = it })

                LocationPermissionNotice(permission)
            }
        },
        confirmButton = {
            TextButton(
                enabled = placed && name.isNotBlank(),
                onClick = {
                    onSave(
                        SavedLocation(
                            id = existing?.id ?: newId(),
                            name = name.trim(),
                            point = point,
                            radiusMeters = radius,
                            address = address,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Far enough out that a map with no place on it yet shows continents rather than a field. */
private const val WORLD_ZOOM = 2

/** Tall enough to see a street around the pin, and known here so a radius can be framed in it. */
private val MAP_HEIGHT = 240.dp
