package com.zhelenskiy.zheduler.zheduler.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How often the device is asked where it is.
 *
 * The whole cost of a location rule is here, and so is the whole reason one fails to fire: asked
 * every minute and only after ten metres of movement, a phone walked past the edge of a fence is
 * in and out again between two questions.
 */
class LocationCheckRateTest {

    @Test
    fun `the stored names are the ones already written to settings files`() {
        // Renaming one does not fail: it silently resets everyone who had chosen it back to the
        // default, on the next start, with nothing to say why the battery cost changed.
        val json = Json { encodeDefaults = true }
        assertEquals(
            listOf("Automatic", "Often", "Regular", "Sparing", "Rare"),
            LocationCheckRate.entries.map { json.encodeToString(it).trim('"') },
        )
    }

    @Test
    fun `a rate the user chose is the rate wherever they are`() {
        // The distance is only the automatic rate's business. Someone who asked for every fifteen
        // minutes has said what they want, and standing near a fence is not a reason to overrule it.
        LocationCheckRate.entries.filter { it.fixed != null }.forEach { rate ->
            assertEquals(rate.fixed, rate.intervalFor(nearest = 0.0), rate.name)
            assertEquals(rate.fixed, rate.intervalFor(nearest = 100_000.0), rate.name)
            assertEquals(rate.fixed, rate.intervalFor(nearest = null), rate.name)
        }
    }

    @Test
    fun `automatic asks more often the closer the edge is`() {
        val atTheEdge = LocationCheckRate.Automatic.intervalFor(nearest = 0.0)
        val downTheRoad = LocationCheckRate.Automatic.intervalFor(nearest = 300.0)
        val milesAway = LocationCheckRate.Automatic.intervalFor(nearest = 50_000.0)

        assertTrue(atTheEdge < downTheRoad, "$atTheEdge should be shorter than $downTheRoad")
        assertTrue(downTheRoad < milesAway, "$downTheRoad should be shorter than $milesAway")
    }

    @Test
    fun `automatic never asks faster than the floor or slower than the ceiling`() {
        // Both ends are what keep it sane: no distance is worth a fix every second, and no
        // distance is worth never looking again — the user may be on a train.
        assertEquals(15.seconds, LocationCheckRate.Automatic.intervalFor(nearest = 0.0))
        assertEquals(10.minutes, LocationCheckRate.Automatic.intervalFor(nearest = 1_000_000.0))
    }

    @Test
    fun `not knowing where the device is is not the same as it being far away`() {
        // A phone that could not get a fix has told us nothing about how urgent the next one is.
        // Read as "miles away" it would back off to the ceiling exactly when it had lost track.
        val unknown = LocationCheckRate.Automatic.intervalFor(nearest = null)
        val milesAway = LocationCheckRate.Automatic.intervalFor(nearest = 1_000_000.0)

        assertTrue(unknown < milesAway, "$unknown should be shorter than the cheapest rate")
    }

    @Test
    fun `nonsense from the platform does not become a nonsense rate`() {
        val ordinary = LocationCheckRate.Automatic.intervalFor(nearest = null)
        assertEquals(ordinary, LocationCheckRate.Automatic.intervalFor(nearest = Double.NaN))
        assertEquals(ordinary, LocationCheckRate.Automatic.intervalFor(nearest = -1.0))
        // Infinity is not "very far away", it is a platform that has said nothing usable — and
        // read as very far away it would back the rate right off on the strength of a bad number.
        assertEquals(
            ordinary,
            LocationCheckRate.Automatic.intervalFor(nearest = Double.POSITIVE_INFINITY),
        )
    }
}
