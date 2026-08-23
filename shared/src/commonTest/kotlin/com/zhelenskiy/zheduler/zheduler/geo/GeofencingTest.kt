package com.zhelenskiy.zheduler.zheduler.geo

import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.LocationChange
import kotlinx.collections.immutable.persistentSetOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The arithmetic a location rule stands on.
 *
 * Everything here is worth pinning because none of it can be checked by looking at the app: a
 * fence that is a few per cent too wide, or that reports a crossing every time a fix jitters, or
 * that thinks two points either side of the date line are half a world apart, all look exactly
 * like a fence that works until the day they do not.
 */
class GeofencingTest {

    private val eiffel = GeoPoint(48.858370, 2.294481)
    private val bigBen = GeoPoint(51.500729, -0.124625)

    private fun area(point: GeoPoint, radius: Double, name: String = "Somewhere") =
        GeoArea(name = name, point = point, radiusMeters = radius)

    @Test
    fun `distance between two known landmarks is right to within a kilometre`() {
        val measured = Geofencing.distanceMeters(eiffel, bigBen)
        // 340.5 km, worked out independently from the haversine formula on a mean-radius sphere.
        // A tight bound on purpose: it is what catches degrees fed in where radians were wanted,
        // which is off by a factor and not by a rounding.
        assertTrue(
            abs(measured - 340_545) < 500,
            "expected about 340.5 km between the Eiffel Tower and Big Ben, measured $measured m",
        )
    }

    @Test
    fun `a point either side of the date line is metres away rather than a world away`() {
        val west = GeoPoint(0.0, 179.9995)
        val east = GeoPoint(0.0, -179.9995)
        val measured = Geofencing.distanceMeters(west, east)
        assertTrue(measured < 200, "these are about 110 m apart, measured $measured m")
    }

    @Test
    fun `an area is identified by where it is rather than by what it is called`() {
        val point = GeoPoint(48.858370, 2.294481)
        assertEquals(
            area(point, 200.0, name = "Work").key,
            area(point, 200.0, name = "The office").key,
            "renaming a place must not read as having left one and entered another",
        )
    }

    @Test
    fun `a different radius over the same point is a different fence`() {
        val point = GeoPoint(48.858370, 2.294481)
        assertTrue(area(point, 200.0).key != area(point, 400.0).key)
    }

    @Test
    fun `a reading says how far the nearest edge was`() {
        // What lets the rate follow the user rather than the clock. Without it a phone in another
        // county is asked where it is as often as one at the corner of the fence.
        val here = GeoPoint(51.5, -0.12)
        // Outside it, or the distance to the edge is zero and the test proves only the clamp.
        val nearby = area(GeoPoint(51.5, -0.118), 100.0)
        val faraway = area(GeoPoint(52.5, -0.12), 100.0)

        val reading = Geofencing.read(
            areas = listOf(nearby, faraway),
            fix = GeoFix(here),
            wasInside = emptyMap(),
        )

        val edge = assertNotNull(reading.nearestEdgeMeters)
        assertTrue(edge in 1.0..200.0, "the near fence is tens of metres away, not $edge m")
    }

    @Test
    fun `the middle of a fence is a whole radius from its boundary`() {
        // Measured as the depth past the edge instead, this would be zero — the most urgent
        // reading there is — and someone at home all night, inside a fence they watch, would have
        // their phone asked where it is every fifteen seconds until morning.
        val here = GeoPoint(51.5, -0.12)
        val reading = Geofencing.read(
            areas = listOf(area(here, 100.0)),
            fix = GeoFix(here),
            wasInside = emptyMap(),
        )

        assertEquals(100.0, assertNotNull(reading.nearestEdgeMeters), absoluteTolerance = 1.0)
    }

    @Test
    fun `the boundary is as near from inside as from out`() {
        // Which is the point of measuring to the line: a crossing happens there, and the two sides
        // of it are equally close to one.
        val centre = GeoPoint(51.5, -0.12)
        val fence = area(centre, 500.0)

        // Roughly 140 m east of the centre, so 360 m inside the boundary.
        val inside = Geofencing.read(
            areas = listOf(fence),
            fix = GeoFix(GeoPoint(51.5, -0.118)),
            wasInside = emptyMap(),
        )
        // Roughly 860 m east, so 360 m outside it.
        val outside = Geofencing.read(
            areas = listOf(fence),
            fix = GeoFix(GeoPoint(51.5, -0.1076)),
            wasInside = emptyMap(),
        )

        assertEquals(
            assertNotNull(inside.nearestEdgeMeters),
            assertNotNull(outside.nearestEdgeMeters),
            absoluteTolerance = 20.0,
        )
    }

    @Test
    fun `a reading that looked at nothing reports no distance`() {
        // Not zero, which would ask for the fastest rate there is on a device that has never been
        // able to say where it is.
        assertEquals(null, PlaceReading.Unknown.nearestEdgeMeters)
    }

    @Test
    fun `getting in means being inside the radius`() {
        val fence = area(GeoPoint(0.0, 0.0), 200.0)
        // A shade under 200 m north of the centre.
        val justInside = GeoFix(GeoPoint(0.0017, 0.0))
        assertTrue(Geofencing.distanceMeters(fence.point, justInside.point) < 200)
        assertTrue(Geofencing.isInside(fence, justInside, wasInside = false))
    }

    @Test
    fun `getting out takes more than stepping over the line`() {
        val fence = area(GeoPoint(0.0, 0.0), 1_000.0)
        // 1,050 m out: past the edge, but inside the margin that keeps a fix on the boundary from
        // reading as a departure. Without the margin this is a rule firing on GPS noise.
        val justOver = GeoFix(GeoPoint(0.00944, 0.0))
        val distance = Geofencing.distanceMeters(fence.point, justOver.point)
        assertTrue(distance in 1_000.0..1_100.0, "meant to be just over the edge, is $distance m")

        assertTrue(Geofencing.isInside(fence, justOver, wasInside = true), "still in, by the margin")
        assertFalse(Geofencing.isInside(fence, justOver, wasInside = false), "not a way back in")

        val wellOut = GeoFix(GeoPoint(0.0126, 0.0))
        assertFalse(Geofencing.isInside(fence, wellOut, wasInside = true), "1,400 m out is out")
    }

    @Test
    fun `a reading known to be wildly inaccurate does not report a departure`() {
        val fence = area(GeoPoint(0.0, 0.0), 200.0)
        // 300 m from the centre, but the platform says the reading may be 400 m out. That is not
        // evidence of having left a circle 200 m across.
        val vague = GeoFix(GeoPoint(0.0027, 0.0), accuracyMeters = 400.0)
        assertTrue(Geofencing.isInside(fence, vague, wasInside = true))
    }

    @Test
    fun `an area seen for the first time is recorded and reports no crossing`() {
        val fence = area(GeoPoint(0.0, 0.0), 200.0)
        val standingThere = GeoFix(GeoPoint(0.0, 0.0))

        val reading = Geofencing.read(listOf(fence), standingThere, wasInside = emptyMap())

        assertTrue(fence.key in reading.inside, "the device is plainly there")
        assertTrue(reading.entered.isEmpty(), "writing a rule about where you are is not arriving")
        assertTrue(reading.left.isEmpty())
        assertFalse(reading.isCrossing)
        assertTrue(reading.known)
    }

    @Test
    fun `arriving and leaving are reported once each`() {
        val fence = area(GeoPoint(0.0, 0.0), 200.0)
        val away = GeoFix(GeoPoint(1.0, 1.0))
        val there = GeoFix(GeoPoint(0.0, 0.0))

        val first = Geofencing.read(listOf(fence), away, wasInside = emptyMap())
        var remembered = Geofencing.remember(emptyMap(), first, listOf(fence.key))

        val arriving = Geofencing.read(listOf(fence), there, remembered)
        assertEquals(setOf(fence.key), arriving.entered)
        remembered = Geofencing.remember(remembered, arriving, listOf(fence.key))

        val staying = Geofencing.read(listOf(fence), there, remembered)
        assertTrue(staying.entered.isEmpty(), "still being there is not arriving again")
        remembered = Geofencing.remember(remembered, staying, listOf(fence.key))

        val leaving = Geofencing.read(listOf(fence), away, remembered)
        assertEquals(setOf(fence.key), leaving.left)
    }

    @Test
    fun `a reading that knows nothing leaves whereabouts alone`() {
        val fence = area(GeoPoint(0.0, 0.0), 200.0)
        val known = mapOf(fence.key to true)

        val kept = Geofencing.remember(known, PlaceReading.Unknown, listOf(fence.key))

        assertEquals(known, kept, "forgetting would make the next reading a first sighting")
    }

    @Test
    fun `an area that was never measured is not written down as outside`() {
        // A sweep reads the areas at the start and the tasks again at the end, and getting a fix
        // can take twenty seconds — long enough for the user to save a rule in between. The new
        // area is watched but was never looked at, and recording it as "outside" on the strength
        // of a reading that never saw it makes the very next sweep call it an arrival and fire a
        // rule nobody moved for.
        val looked = area(GeoPoint(0.0, 0.0), 200.0, name = "Office")
        val savedMeanwhile = area(GeoPoint(10.0, 10.0), 200.0, name = "Home")

        val reading = Geofencing.read(listOf(looked), GeoFix(GeoPoint(0.0, 0.0)), wasInside = emptyMap())
        val kept = Geofencing.remember(emptyMap(), reading, listOf(looked.key, savedMeanwhile.key))

        assertEquals(true, kept[looked.key], "this one was measured, and the device is there")
        assertTrue(
            savedMeanwhile.key !in kept,
            "nothing was ever measured for this one, so nothing may be claimed about it",
        )
    }

    @Test
    fun `whereabouts are forgotten once nothing watches the place`() {
        val fence = area(GeoPoint(0.0, 0.0), 200.0)
        val known = mapOf(fence.key to true)

        assertTrue(Geofencing.remember(known, PlaceReading.Unknown, stillWatched = emptyList()).isEmpty())
    }

    @Test
    fun `two names for one place share a single answer`() {
        val point = GeoPoint(0.0, 0.0)
        val home = area(point, 200.0, name = "Home")
        val alsoHome = area(point, 200.0, name = "Mum's")

        val reading = Geofencing.read(listOf(home, alsoHome), GeoFix(point), wasInside = emptyMap())

        assertEquals(1, reading.inside.size)
    }

    @Test
    fun `a rule waiting to arrive is not satisfied by a departure`() {
        val fence = area(GeoPoint(0.0, 0.0), 200.0)
        val leaving = PlaceReading(left = setOf(fence.key), known = true)

        assertFalse(
            listOf(
                LocationChange(persistentSetOf(fence), GeofenceDirection.Entering)
            ).satisfiedBy(leaving)
        )
        assertTrue(
            listOf(
                LocationChange(persistentSetOf(fence), GeofenceDirection.EitherWay)
            ).satisfiedBy(leaving)
        )
    }

    @Test
    fun `nothing is satisfied by a reading that cannot say where the device is`() {
        val fence = area(GeoPoint(0.0, 0.0), 200.0)
        GeofenceDirection.entries.forEach { direction ->
            assertFalse(
                listOf(
                    LocationChange(persistentSetOf(fence), direction)
                ).satisfiedBy(PlaceReading.Unknown),
                "$direction fired on a device that has no idea where it is",
            )
        }
    }

    @Test
    fun `a radius outside what a fence can mean is brought into range`() {
        assertEquals(GeoArea.MIN_RADIUS_METERS, area(GeoPoint(0.0, 0.0), 0.0).radius())
        assertEquals(GeoArea.MAX_RADIUS_METERS, area(GeoPoint(0.0, 0.0), 1e12).radius())
        assertEquals(GeoArea.MIN_RADIUS_METERS, area(GeoPoint(0.0, 0.0), Double.NaN).radius())
    }

    @Test
    fun `a nonsensical point is brought into range rather than thrown out`() {
        // These can only come from a file written by another build, and a task that will not
        // decode takes its whole space with it.
        assertEquals(90.0, GeoPoint(120.0, 0.0).sane().latitude)
        assertEquals(-170.0, GeoPoint(0.0, 190.0).sane().longitude)
        assertEquals(0.0, GeoPoint(Double.NaN, Double.NaN).sane().latitude)
    }
}
