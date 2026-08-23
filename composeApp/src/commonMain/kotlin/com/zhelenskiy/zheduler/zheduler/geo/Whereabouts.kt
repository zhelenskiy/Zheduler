package com.zhelenskiy.zheduler.zheduler.geo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where the device is, for as long as this is on screen.
 *
 * A convenience for the places screens: a list of saved places says a great deal more when each
 * one carries how far away it is, and the number a fence is being drawn against means most when
 * the person drawing it can see where they are standing relative to it.
 *
 * Asked in a loop, and *only* while the thing that wants it is composed. Positioning is the
 * expensive thing this app does — see `LocationCheckRate` for the pacing the rules themselves get
 * — so a dialog that is closed must cost nothing at all. Leaving the composition cancels the
 * effect and with it the asking.
 *
 * Null where there is no permission, no hardware, or no fix yet, and the callers show nothing at
 * all rather than a placeholder: a distance is a nicety here, never the point of the screen.
 */
@Composable
fun rememberWhereabouts(every: Duration = REFRESH): GeoPoint? {
    val permission = rememberLocationPermission()
    // Never where asking is itself an interruption — see [positioningPromptsOnUse]. A distance
    // beside a place is a nicety, and no nicety is worth a permission prompt the user did not ask
    // for, raised again every few seconds.
    val granted = permission.status == LocationPermissionStatus.Granted && !positioningPromptsOnUse
    var here by remember { mutableStateOf<GeoPoint?>(null) }

    LaunchedEffect(granted, every) {
        if (!granted) {
            // Cleared rather than left standing: a permission taken away mid-session must not
            // leave the last place the device was known to be on screen.
            here = null
            return@LaunchedEffect
        }
        val source = createLocationSource()
        while (true) {
            val fix = try {
                source.currentFix()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // A reading that failed says nothing about where the device is. The last one still
                // stands, and the next attempt is a few seconds away.
                null
            }
            // Kept where a reading came back empty: indoors a fix is missed as often as it is got,
            // and a distance that blinks out every other refresh is worse than one a moment old.
            fix?.point?.let { here = it }
            // After the reading rather than alongside it, so a slow fix spaces the next attempt
            // out rather than queueing another the moment it lands.
            delay(every)
        }
    }

    return here
}

/**
 * How often to ask while a screen is open.
 *
 * Often enough that walking towards somewhere shows it, seldom enough to be worth it: a fix takes
 * a second or two of radio on its own, and nobody is reading a list of places for long.
 */
private val REFRESH: Duration = 5.seconds

/**
 * How far [here] is from the edge of [area], in metres. Zero anywhere inside it.
 *
 * The *edge*, not the middle, because the edge is what a rule fires on. Told the distance to the
 * centre of a place a hundred metres wide, someone standing two hundred metres out would be given
 * a number that does not match anything they can observe — they are a hundred metres from arriving
 * and the screen says two hundred.
 *
 * Clamped rather than signed: inside is inside, and how deeply is not a thing anyone is asking.
 */
fun metresOutside(here: GeoPoint, area: GeoArea): Double =
    (Geofencing.distanceMeters(here, area.point) - area.radius()).coerceAtLeast(0.0)
