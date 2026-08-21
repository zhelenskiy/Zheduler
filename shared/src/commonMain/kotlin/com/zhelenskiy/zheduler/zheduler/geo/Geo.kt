package com.zhelenskiy.zheduler.zheduler.geo

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A point on the Earth, in degrees.
 *
 * Deliberately unvalidated. This is decoded out of a task's `recurrenceRulesJson` and out of the
 * schedule's own state, and a `require` there turns a nonsensical number into a task that cannot
 * be read at all, taking its space with it. [Geofencing] copes with whatever it is given instead,
 * and the editor is what stops a bad one being written — the same trade as `RecurrenceTimeZone`.
 */
@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    /** Latitude clamped and longitude wrapped into the ranges the maths below assumes. */
    fun sane(): GeoPoint = GeoPoint(
        latitude = if (latitude.isNaN()) 0.0 else latitude.coerceIn(-90.0, 90.0),
        longitude = longitude.wrapLongitude(),
    )

    private fun Double.wrapLongitude(): Double {
        if (isNaN() || isInfinite()) return 0.0
        if (this in -180.0..180.0) return this
        val shifted = (this + 180.0) % 360.0
        return (if (shifted < 0) shifted + 360.0 else shifted) - 180.0
    }

    companion object {
        val Zero = GeoPoint(0.0, 0.0)
    }
}

/**
 * A place, and how near counts as being there.
 *
 * Carried inside the rule that watches it rather than pointed at by id, so a task exported from
 * one device and imported on another still knows where it is waiting for. [SavedLocation] is the
 * address book these are copied from, and deleting an entry there leaves every rule intact.
 */
@Serializable
data class GeoArea(
    val name: String,
    val point: GeoPoint,
    val radiusMeters: Double,
) {
    /**
     * Identity as a *place*, which is what whereabouts are remembered against.
     *
     * Not the name: renaming "Work" to "Office" must not read as having left one area and entered
     * another. Not the raw doubles either — Kotlin/JS and Kotlin/JVM format them differently and
     * this string is written into the schedule's stored state. Microdegrees are about 11 cm, finer
     * than any fix ever compared against it.
     */
    val key: String
        get() {
            val sane = point.sane()
            fun micro(degrees: Double) = round(degrees * 1_000_000.0).toLong()
            return "${micro(sane.latitude)}:${micro(sane.longitude)}:${round(radius()).toLong()}"
        }

    /** The radius as something to measure against: never zero, never absurd. */
    fun radius(): Double =
        if (radiusMeters.isNaN()) MIN_RADIUS_METERS
        else radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)

    companion object {
        /**
         * Below this a fence is a coin toss rather than a place: consumer positioning is good to a
         * few tens of metres at best, so a smaller circle is entered and left by the noise alone.
         */
        const val MIN_RADIUS_METERS: Double = 50.0

        /** Half the distance from pole to equator; past it a circle is no longer a place. */
        const val MAX_RADIUS_METERS: Double = 5_000_000.0

        /** What a place is watched at before anyone has said otherwise. */
        const val DEFAULT_RADIUS_METERS: Double = 200.0
    }
}

/**
 * A place the user has kept, to pick from when writing a rule.
 *
 * [address] is whatever the search called it and is only ever shown; the point and the radius are
 * the whole of what a fence is made of.
 */
@Serializable
data class SavedLocation(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val radiusMeters: Double = GeoArea.DEFAULT_RADIUS_METERS,
    val address: String = "",
) {
    fun toArea(): GeoArea = GeoArea(name = name, point = point, radiusMeters = radiusMeters)
}

/**
 * Which way across a boundary a rule is waiting for.
 *
 * The names are written into every task's `recurrenceRulesJson`; see `StoredEnumNamesTest`.
 */
@Serializable
enum class GeofenceDirection {
    Entering,
    Leaving,
    EitherWay;

    val displayName: String
        get() = when (this) {
            Entering -> "I arrive"
            Leaving -> "I leave"
            EitherWay -> "I arrive or leave"
        }

    val matchesEntering: Boolean get() = this != Leaving
    val matchesLeaving: Boolean get() = this != Entering
}

/**
 * Where the device is, as well as it can be told.
 *
 * [accuracyMeters] is the platform's own estimate of how wrong the point may be, and is what the
 * boundary is widened by on the way out — see [Geofencing.isInside]. Null where nothing is said.
 */
data class GeoFix(
    val point: GeoPoint,
    val accuracyMeters: Double? = null,
)

/**
 * What the areas being watched make of the latest reading.
 *
 * [inside] is a state and [entered]/[left] are the crossings that reached it, all keyed by
 * [GeoArea.key]. Both are kept because a rule can want either: "when I get to the office" waits
 * for a crossing, "every Monday, if I am at the office" asks where the device is.
 *
 * [known] is not the same as all three being empty. A device that cannot say where it is — no
 * permission, no hardware, a platform with no notion of the question — must not read as one
 * provably outside every area, or every "when I leave" condition would hold on a desktop.
 */
data class PlaceReading(
    val inside: Set<String> = emptySet(),
    val entered: Set<String> = emptySet(),
    val left: Set<String> = emptySet(),
    val known: Boolean = false,
    /**
     * The areas this reading actually looked at.
     *
     * Not the same as the areas being watched. A sweep can take twenty seconds to get a fix, and
     * the user can save a rule in that time; the areas are read once at the start and the tasks
     * again at the end, so the two lists differ. An area that was never measured must be left with
     * nothing written down — recorded as "outside" on the strength of a reading that never looked
     * at it, the very next sweep calls it an arrival and fires a rule nobody moved for.
     */
    val measured: Set<String> = emptySet(),
) {
    /** Whether this reading is a crossing rather than a standing answer about where the device is. */
    val isCrossing: Boolean get() = entered.isNotEmpty() || left.isNotEmpty()

    /**
     * The same whereabouts with the crossings dropped.
     *
     * What a rule fired by something else — a moment coming round, a status being set — is asked
     * about. Handed the crossings as well it would read as being fired *by* the crossing, and
     * every rule that was not watching a place would be refused for the rest of the sweep.
     */
    fun standing(): PlaceReading = copy(entered = emptySet(), left = emptySet())

    companion object {
        val Unknown = PlaceReading()
    }
}

/** Distances, and whether a fix counts as being inside an area. */
object Geofencing {

    /**
     * Metres between two points along the surface, by the haversine formula.
     *
     * Haversine rather than the law of cosines, which loses precision over short distances — the
     * only kind a fence of a few hundred metres ever measures.
     */
    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val a = from.sane()
        val b = to.sane()
        val phi1 = a.latitude.toRadians()
        val phi2 = b.latitude.toRadians()
        val halfDPhi = ((b.latitude - a.latitude).toRadians()) / 2
        val halfDLambda = (shortestLongitudeDelta(a.longitude, b.longitude).toRadians()) / 2
        val h = sin(halfDPhi) * sin(halfDPhi) + cos(phi1) * cos(phi2) * sin(halfDLambda) * sin(halfDLambda)
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    /**
     * Whether [fix] counts as being in [area], given whether it counted last time.
     *
     * Asymmetric on purpose. Getting in means being within the radius; getting out means being
     * clear of it by a margin. Without the margin a fix sitting on the boundary — which is what a
     * fix on the boundary does — reads as arriving and leaving over and over, and every crossing
     * is a rule firing and a task reset.
     */
    fun isInside(area: GeoArea, fix: GeoFix, wasInside: Boolean): Boolean {
        val distance = distanceMeters(area.point, fix.point)
        val radius = area.radius()
        return if (wasInside) distance <= radius + slackFor(area, fix) else distance <= radius
    }

    /**
     * How far past the edge the device must get before it has left.
     *
     * At least a tenth of the radius, so every fence has some margin, and as much as the fix's own
     * stated error where that is larger — a reading known to be 300 m out says nothing about a
     * 200 m circle. Capped at the radius, so the margin never makes a fence more than twice the
     * size that was asked for.
     */
    private fun slackFor(area: GeoArea, fix: GeoFix): Double {
        val radius = area.radius()
        val stated = fix.accuracyMeters?.takeIf { it.isFinite() && it > 0 } ?: 0.0
        return max(radius * BOUNDARY_MARGIN, min(stated, radius))
    }

    /**
     * What [areas] make of [fix], given [wasInside] from the last reading.
     *
     * An area with no entry in [wasInside] has never been looked at: it is recorded as it now
     * stands and reports no crossing. Standing at home when a rule about home is written is not
     * arriving home, and counting it as one fires every new rule the moment it is saved.
     */
    fun read(areas: Collection<GeoArea>, fix: GeoFix, wasInside: Map<String, Boolean>): PlaceReading {
        val inside = mutableSetOf<String>()
        val entered = mutableSetOf<String>()
        val left = mutableSetOf<String>()
        val measured = mutableSetOf<String>()
        // Two areas can be the same place under different names; they share one answer.
        areas.distinctBy { it.key }.forEach { area ->
            measured += area.key
            val before = wasInside[area.key]
            val now = isInside(area, fix, wasInside = before == true)
            if (now) inside += area.key
            when {
                before == null -> Unit
                !before && now -> entered += area.key
                before && !now -> left += area.key
            }
        }
        return PlaceReading(
            inside = inside,
            entered = entered,
            left = left,
            known = true,
            measured = measured,
        )
    }

    /**
     * Whereabouts as they should be remembered after [reading], for the areas still watched.
     *
     * A reading that knows nothing changes nothing: forgetting on a run that could not get a fix
     * would make the next successful one a first sighting, and first sightings never fire.
     *
     * Only what the reading actually looked at is written down. An area that appeared while the
     * sweep was busy is still watched but was never measured, and recording it as "outside" on the
     * strength of a reading that never saw it makes the next sweep call it an arrival.
     */
    fun remember(
        previous: Map<String, Boolean>,
        reading: PlaceReading,
        areas: Collection<GeoArea>,
    ): Map<String, Boolean> {
        val watched = areas.mapTo(mutableSetOf()) { it.key }
        // Pruned to what is still watched, the way delivered events are pruned to what is still
        // planned: an area nobody references should not be remembered for ever.
        val kept = previous.filterKeys { it in watched }
        if (!reading.known) return kept
        return kept + reading.measured.filter { it in watched }.associateWith { it in reading.inside }
    }

    /**
     * The signed difference in longitude by the short way round.
     *
     * Two points either side of the antimeridian are a few metres apart and 359 degrees apart, and
     * only the first of those is a distance.
     */
    private fun shortestLongitudeDelta(from: Double, to: Double): Double {
        val raw = to - from
        return if (abs(raw) <= 180.0) raw else raw - 360.0 * round(raw / 360.0)
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    /** The mean radius, as the IUGG has it. */
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    private const val BOUNDARY_MARGIN = 0.1
}
