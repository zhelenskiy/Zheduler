package com.zhelenskiy.zheduler.zheduler.components.map

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.geo.GeoArea
import com.zhelenskiy.zheduler.zheduler.geo.LocationPermissionState
import com.zhelenskiy.zheduler.zheduler.geo.LocationPermissionStatus
import com.zhelenskiy.zheduler.zheduler.geo.PlaceResult
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The search box over OpenStreetMap's geocoder, and whatever it has found.
 *
 * The results below it are the found places only; the kept ones are shown by whoever uses this,
 * because a screen listing the address book wants them in its own list and a picker wants them
 * above the search. Keeping the two apart is what stops a place appearing twice.
 */
@Composable
fun PlaceSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<PlaceResult>,
    searching: Boolean,
    onPick: (PlaceResult) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Search for a place",
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(label) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                when {
                    searching -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    query.isNotEmpty() -> IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear the search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (results.isEmpty()) return@Column
        LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
            items(results, key = { "${it.name}|${it.address}|${it.point.latitude},${it.point.longitude}" }) { result ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(result) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Column {
                        Text(result.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = result.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * How near counts as being there.
 *
 * The slider moves by ratio rather than by metres. The useful range runs from a doorstep to a
 * county, and a linear slider over that spends nine tenths of its travel between "half a country"
 * and "a whole one" while the first pixel jumps past every radius anyone actually wants.
 */
@Composable
fun RadiusSlider(
    radiusMeters: Double,
    onRadiusChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Within", style = MaterialTheme.typography.labelLarge)
            Text(formatDistance(radiusMeters), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = radiusToSlider(radiusMeters),
            onValueChange = { onRadiusChange(sliderToRadius(it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Where [radiusMeters] sits on a slider that runs from the smallest fence to the largest. */
internal fun radiusToSlider(radiusMeters: Double): Float {
    val clamped = radiusMeters.coerceIn(GeoArea.MIN_RADIUS_METERS, GeoArea.MAX_RADIUS_METERS)
    return (ln(clamped / GeoArea.MIN_RADIUS_METERS) / RADIUS_SPAN).toFloat().coerceIn(0f, 1f)
}

/** The radius a slider at [position] means, rounded to something a person would say. */
internal fun sliderToRadius(position: Float): Double {
    val raw = GeoArea.MIN_RADIUS_METERS * exp(position.toDouble().coerceIn(0.0, 1.0) * RADIUS_SPAN)
    // Rounded by size, so dragging gives 250 m and 3 km rather than 247 m and 2,981 m — and,
    // down at the bottom of the range, 2 m and 7 m rather than a jump from 1 m straight to 10.
    val step = when {
        raw < 10 -> 1.0
        raw < 100 -> 5.0
        raw < 1_000 -> 10.0
        raw < 10_000 -> 100.0
        raw < 100_000 -> 1_000.0
        else -> 10_000.0
    }
    return ((raw / step).roundToInt() * step).coerceIn(GeoArea.MIN_RADIUS_METERS, GeoArea.MAX_RADIUS_METERS)
}

private val RADIUS_SPAN = ln(GeoArea.MAX_RADIUS_METERS / GeoArea.MIN_RADIUS_METERS)

/** A distance as a person would say it. */
fun formatDistance(meters: Double): String = when {
    meters >= 100_000 -> "${(meters / 1_000).roundToInt()} km"
    meters >= 1_000 -> "${((meters / 100).roundToInt() / 10.0)} km"
    else -> "${meters.roundToInt()} m"
}

/** A point as coordinates, which is what is shown when nothing has named it. */
fun formatCoordinates(latitude: Double, longitude: Double): String =
    "${latitude.toFixed(5)}, ${longitude.toFixed(5)}"

private fun Double.toFixed(decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    val rounded = (this * factor).roundToInt() / factor
    val text = rounded.toString()
    // Kotlin/JS drops a trailing ".0" that every other target keeps; a coordinate that reads
    // "52, 13" on the web and "52.0, 13.0" everywhere else is the same number twice over.
    return if ('.' in text) text else "$text.0"
}

/**
 * What the platform says about being allowed to look, and the way to change its mind.
 *
 * Shown wherever a place is being set up rather than only once at the start: a rule that quietly
 * never fires because the permission was refused months ago is the failure this exists to prevent.
 */
@Composable
fun LocationPermissionNotice(
    permission: LocationPermissionState,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (permission.status) {
            LocationPermissionStatus.Unavailable -> Text(
                text = "This device cannot tell where it is, so rules about places will not fire here. " +
                    "They still work on your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            LocationPermissionStatus.Denied -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Rules about places need permission to read where you are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = permission::request) { Text("Allow") }
            }

            LocationPermissionStatus.Granted if !permission.worksWhileAway -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Where you are is only read while the app is open, so an arrival is " +
                        "noticed the next time you open it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                permission.requestWhileAway?.let { ask ->
                    TextButton(onClick = ask) { Text("Allow always") }
                }
            }

            LocationPermissionStatus.Granted -> Unit
        }
    }
}

/** The button that drops the pin where the device currently is. */
@Composable
fun UseMyLocationButton(
    onClick: () -> Unit,
    enabled: Boolean,
    finding: Boolean,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, enabled = enabled && !finding, modifier = modifier) {
        if (finding) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text("  Use where I am")
    }
}
