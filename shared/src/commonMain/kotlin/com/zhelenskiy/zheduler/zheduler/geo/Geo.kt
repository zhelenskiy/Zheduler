@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.geo

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
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
         * The smallest fence a rule can watch.
         *
         * A metre, because it is a reasonable thing to ask for beside a condition that *is* exact
         * — a rule wanting a point on the map and the office wifi is not relying on the metre. It
         * is not a reasonable thing to ask of positioning alone: see [RELIABLE_RADIUS_METERS],
         * which is what the editor warns below.
         */
        const val MIN_RADIUS_METERS: Double = 1.0

        /**
         * The smallest fence positioning alone can be trusted to cross reliably.
         *
         * Roughly what a phone knows itself to indoors, and the figure the editor warns below. Not
         * a limit — a smaller fence is allowed and is genuinely useful beside a wifi or bluetooth
         * condition — but below it a fence stops reporting crossings tightly and starts reporting
         * them at random: the margin a fence must be left by before it counts as departed grows to
         * whatever the fix says its own error is, which indoors is tens of metres.
         */
        const val RELIABLE_RADIUS_METERS: Double = 20.0

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

    /**
     * Whether a search for [needle] should turn this up. Blank finds everything.
     *
     * Beside the thing it matches so that the list a user narrows and the list the repository
     * returns cannot drift apart.
     */
    fun matches(needle: String): Boolean = needle.trim().let { query ->
        query.isEmpty() ||
            name.contains(query, ignoreCase = true) ||
            address.contains(query, ignoreCase = true)
    }
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
    /**
     * How far the device was from the nearest watched *boundary*, in metres, or null where nothing
     * was measured.
     *
     * The distance to the line, from whichever side: a crossing happens at the boundary, so the
     * middle of a fence is as far from one as the same distance outside it. Measured as the depth
     * past the edge instead, someone sitting at home all night — inside a fence they watch, and so
     * at depth zero — would be the most urgent case there is, and asked where they are every
     * fifteen seconds until morning.
     *
     * Carried so a platform can decide how soon to look again. Not part of what a rule matches on:
     * it is a cost decision, not an answer.
     */
    val nearestEdgeMeters: Double? = null,
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

/**
 * A rule's condition on what is around the device — an area it is in, a network it is on.
 *
 * Split in two because a condition can be read two ways, and which one applies depends on what
 * else the rule is waiting for. A rule with nothing but this is fired *by* the crossing; one that
 * also has a moment or a status is armed by that and merely asks whether the condition holds.
 */
interface PresenceTrigger {
    /** What this watches, by [GeoArea.key] or [NearbySignal.key]. */
    val watchedKeys: Set<String>

    /** Whether the crossings in [reading] are the ones this was waiting for. */
    fun firedBy(reading: PlaceReading): Boolean

    /** Whether this holds as a standing question about where the device is now. */
    fun holdsIn(reading: PlaceReading): Boolean
}

/**
 * Whether [reading] satisfies every one of these conditions at once.
 *
 * A rule may name both a place and a network — "at the office, on the office wifi" — and both have
 * to be true. On a reading that is a crossing, at least one of them must be the thing that just
 * happened and the rest must hold; asking every condition to have crossed at the same instant
 * would mean a rule that could only fire if two things happened in the same second.
 */
fun List<PresenceTrigger>.satisfiedBy(reading: PlaceReading): Boolean {
    if (isEmpty()) return true
    // Nothing is satisfied by a device that cannot say what is around it: firing then would fire
    // everywhere at once.
    if (!reading.known) return false
    return if (reading.isCrossing) {
        any { it.firedBy(reading) } && all { it.firedBy(reading) || it.holdsIn(reading) }
    } else {
        all { it.holdsIn(reading) }
    }
}

/** Whether any of [keys] just crossed the way this condition wanted. */
internal fun crossingMatches(
    keys: Set<String>,
    reading: PlaceReading,
    wantsArriving: Boolean,
    wantsLeaving: Boolean,
): Boolean = (wantsArriving && keys.any { it in reading.entered }) ||
    (wantsLeaving && keys.any { it in reading.left })

/**
 * Whether the standing state of [keys] is what this condition wants.
 *
 * "Either way" names no state at all, so it holds wherever the device is. Absence has to be
 * *measured* absence: a device on a phone that will not answer about bluetooth at all — the
 * permission refused, the stack wedged — or an area added while the sweep was already under way,
 * is not known to be missing, and treating unknown as absent is how "when I leave" holds true for
 * somewhere nobody has ever looked. (A radio switched *off* is a different thing and a real
 * answer: nothing is connected to an adapter that is off.)
 */
internal fun standingMatches(
    keys: Set<String>,
    reading: PlaceReading,
    wantsArriving: Boolean,
    wantsLeaving: Boolean,
): Boolean = when {
    wantsArriving && wantsLeaving -> true
    wantsArriving -> keys.any { it in reading.inside }
    else -> keys.any { it in reading.measured } && keys.none { it in reading.inside }
}

/**
 * What one look at the radios came to.
 *
 * [missingSince] is when each absent signal was first noticed missing, which is what the grace is
 * measured from. When the grace *ends* is worked out by the engine from what it has written down
 * rather than reported here: a sweep that could not look at the radios still has to re-book the
 * one that can, and a reading knows nothing about the sweeps that went before it.
 */
data class SignalReading(
    val reading: PlaceReading,
    val missingSince: Map<String, Long>,
)

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
        var nearestEdge: Double? = null
        // Two areas can be the same place under different names; they share one answer.
        areas.distinctBy { it.key }.forEach { area ->
            measured += area.key
            val before = wasInside[area.key]
            val edge = abs(distanceMeters(area.point, fix.point) - area.radius())
            nearestEdge = nearestEdge?.let { min(it, edge) } ?: edge
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
            nearestEdgeMeters = nearestEdge,
        )
    }

    /**
     * What [signals] make of [nearby], given what was there last time.
     *
     * The counterpart of [read] for the things that are simply present or not — there is no
     * distance to measure and so no boundary to widen. What takes the place of that margin is
     * [grace], which differs by kind — see [graceFor]. A signal that has gone is held as still
     * present until it has been missing that long; the next sweep after that reports the
     * departure.
     *
     * Unless the reading has settled it: an absence the platform is *sure* of — the radio switched
     * off, another network joined — is reported at once. See [NearbySignals.definite].
     *
     * Only the kinds [nearby] covers are looked at. The rest are left unmeasured — a phone that
     * cannot be asked about bluetooth has not watched every paired device drive away. A radio
     * switched off is not that: it is a real answer, and is reported as measured and empty.
     */
    fun readSignals(
        signals: Collection<NearbySignal>,
        nearby: NearbySignals,
        wasInside: Map<String, Boolean>,
        missingSince: Map<String, Long>,
        now: Instant,
        grace: (SignalKind) -> Duration = ::graceFor,
    ): SignalReading {
        if (nearby.kinds.isEmpty()) return SignalReading(PlaceReading.Unknown, emptyMap())
        val inside = mutableSetOf<String>()
        val entered = mutableSetOf<String>()
        val left = mutableSetOf<String>()
        val measured = mutableSetOf<String>()
        val missing = mutableMapOf<String, Long>()

        signals.distinctBy { it.key }.filter { it.kind in nearby.kinds }.forEach { signal ->
            val key = signal.key
            measured += key
            val before = wasInside[key]
            val here = key in nearby.present

            // Measured from the moment it was first *noticed missing*, not from the last time it
            // was seen. Those differ by everything: a phone sitting still runs no sweeps for hours,
            // so "last seen" is hours old the instant the router blinks, and the very first sweep
            // after the blink would call it a departure — which is precisely what the grace exists
            // to prevent. First noticed missing is always now, so a blink is always inside it.
            val missingFrom = if (here) null else missingSince[key] ?: now.toEpochMilliseconds()
            missingFrom?.let { missing[key] = it }

            // Not held where the platform has settled the matter — see [NearbySignals.definite].
            val heldByGrace = before == true && missingFrom != null &&
                signal.kind !in nearby.definite &&
                now - Instant.fromEpochMilliseconds(missingFrom) < grace(signal.kind)
            val nowPresent = here || heldByGrace
            if (nowPresent) inside += key
            when {
                before == null -> Unit
                !before && nowPresent -> entered += key
                before && !nowPresent -> left += key
            }
        }
        return SignalReading(
            reading = PlaceReading(
                inside = inside,
                entered = entered,
                left = left,
                known = true,
                measured = measured,
            ),
            missingSince = missing,
        )
    }

    /**
     * The two readings as one.
     *
     * A sweep asks about places and about signals separately — they come from different hardware
     * and either can fail on its own — and the rules are then matched against the pair as though it
     * were a single answer about where the device is. Known if either half is: a phone that cannot
     * get a fix but can see the office wifi knows something.
     */
    fun combine(places: PlaceReading, signals: PlaceReading): PlaceReading = PlaceReading(
        inside = places.inside + signals.inside,
        entered = places.entered + signals.entered,
        left = places.left + signals.left,
        known = places.known || signals.known,
        measured = places.measured + signals.measured,
        // Only the places half has a distance to report; signals are simply present or not.
        nearestEdgeMeters = places.nearestEdgeMeters,
    )

    /**
     * How long a signal has to be missing before it counts as gone.
     *
     * Not one figure, because the two kinds fail differently. A network drops for a moment all the
     * time — a router reboots, a phone hands over between bands, a lift takes the signal for
     * fifteen seconds — and none of those is leaving the building, so a wifi rule waits two
     * minutes before believing it. A bluetooth link is not like that: the phone is *told* the
     * device disconnected, by a system broadcast that arrives the moment it happens, and a car
     * that has been switched off does not come back inside the minute. Held for two minutes it
     * simply reads as broken — "I got out of the car and the reminder came two minutes later" is
     * the complaint, and it is a fair one.
     *
     * Not zero, because a link does flap: a headset at the edge of range can drop and rejoin
     * within a second or two, and every one of those would be a task reset.
     */
    fun graceFor(kind: SignalKind): Duration = when (kind) {
        SignalKind.Wifi -> WIFI_GRACE
        SignalKind.Bluetooth -> BLUETOOTH_GRACE
    }

    val WIFI_GRACE: Duration = 2.minutes
    val BLUETOOTH_GRACE: Duration = 20.seconds

    /**
     * Whereabouts as they should be remembered after [reading], for whatever is still watched —
     * areas and signals alike, both named by their key.
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
        stillWatched: Collection<String>,
    ): Map<String, Boolean> {
        val watched = stillWatched.toSet()
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
