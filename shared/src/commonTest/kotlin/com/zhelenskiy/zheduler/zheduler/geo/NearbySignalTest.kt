@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.geo

import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.LocationChange
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger.NearbyChange
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Wifi and bluetooth as conditions on a rule.
 *
 * These answer the same question a place does — is the device somewhere — so they are matched by
 * the same machinery, and what is worth pinning is where the two differ: there is no distance to
 * measure, so the margin that keeps a fix on a boundary from flapping is replaced by a grace
 * period; and a platform can be blind to one kind while seeing the other, which must read as
 * *unknown* rather than *absent*.
 */
class NearbySignalTest {

    private val office = NearbySignal.Wifi("Office")
    private val car = NearbySignal.Bluetooth("aa:bb:cc:dd:ee:ff", "Car")
    private val now = Instant.fromEpochMilliseconds(1_780_000_000_000)
    private val grace = 2.minutes

    private fun read(
        signals: List<NearbySignal> = listOf(office),
        present: Set<String> = emptySet(),
        kinds: Set<SignalKind> = setOf(SignalKind.Wifi, SignalKind.Bluetooth),
        wasInside: Map<String, Boolean> = emptyMap(),
        missingSince: Map<String, Long> = emptyMap(),
        at: Instant = now,
    ) = Geofencing.readSignals(
        signals = signals,
        nearby = NearbySignals(kinds = kinds, present = present),
        wasInside = wasInside,
        missingSince = missingSince,
        now = at,
        grace = { grace },
    ).reading

    /** The whole answer, for the tests that are about the bookkeeping rather than the crossings. */
    private fun readWhole(
        signals: List<NearbySignal> = listOf(office),
        present: Set<String> = emptySet(),
        kinds: Set<SignalKind> = setOf(SignalKind.Wifi, SignalKind.Bluetooth),
        wasInside: Map<String, Boolean> = emptyMap(),
        missingSince: Map<String, Long> = emptyMap(),
        at: Instant = now,
    ) = Geofencing.readSignals(
        signals = signals,
        nearby = NearbySignals(kinds = kinds, present = present),
        wasInside = wasInside,
        missingSince = missingSince,
        now = at,
        grace = { grace },
    )

    @Test
    fun `switching the radio off is a departure at once rather than in two minutes`() {
        // The bug this exists for, reported from a real phone: wifi off and on again inside the
        // grace produced no crossing at all — the departure was swallowed by the grace, and the
        // return was not an arrival because nothing had recorded it as gone. A rule that never
        // fires, and no amount of asking again shakes it loose.
        val settled = Geofencing.readSignals(
            signals = listOf(office),
            nearby = NearbySignals(
                kinds = setOf(SignalKind.Wifi),
                present = emptySet(),
                definite = setOf(SignalKind.Wifi),
            ),
            wasInside = mapOf(office.key to true),
            missingSince = emptyMap(),
            now = now,
        ).reading

        assertTrue(office.key in settled.left, "a radio switched off has settled the matter")
    }

    @Test
    fun `a network that merely dropped is still held for its grace`() {
        // The other half, and why the grace is not simply shorter: a router rebooting, or a lift,
        // is not leaving the building. The radio is on and associated with nothing, which is the
        // one wifi answer that is not settled.
        val blinked = Geofencing.readSignals(
            signals = listOf(office),
            nearby = NearbySignals(kinds = setOf(SignalKind.Wifi), present = emptySet()),
            wasInside = mapOf(office.key to true),
            missingSince = emptyMap(),
            now = now,
        ).reading

        assertTrue(office.key in blinked.inside, "a blink is not a departure")
        assertTrue(blinked.left.isEmpty())
    }

    @Test
    fun `being on another network settles the one left behind`() {
        // Which is what a name in the reading means: a device is on one network at a time, so
        // hearing about the office is hearing that home has gone.
        val elsewhere = NearbySignal.Wifi("Home")
        val moved = Geofencing.readSignals(
            signals = listOf(office, elsewhere),
            nearby = NearbySignals(
                kinds = setOf(SignalKind.Wifi),
                present = setOf(office.key),
                definite = setOf(SignalKind.Wifi),
            ),
            wasInside = mapOf(office.key to false, elsewhere.key to true),
            missingSince = emptyMap(),
            now = now,
        ).reading

        assertTrue(office.key in moved.entered)
        assertTrue(elsewhere.key in moved.left, "you cannot be on two networks at once")
    }

    @Test
    fun `a settled absence for one kind does not settle the other`() {
        // The flag is per kind, because the radios fail apart: a wifi radio switched off says
        // nothing about a car that is still connected and merely missed one sweep.
        val held = Geofencing.readSignals(
            signals = listOf(office, car),
            nearby = NearbySignals(
                kinds = setOf(SignalKind.Wifi, SignalKind.Bluetooth),
                present = emptySet(),
                definite = setOf(SignalKind.Wifi),
            ),
            wasInside = mapOf(office.key to true, car.key to true),
            missingSince = emptyMap(),
            now = now,
        ).reading

        assertTrue(office.key in held.left)
        assertTrue(car.key in held.inside, "the car is still inside its own grace")
    }

    @Test
    fun `a device is given up on sooner than a network`() {
        // They fail differently. A network drops all the time — a router reboots, a lift takes the
        // signal — and none of that is leaving the building. A disconnect is *reported* by the
        // system the moment it happens, and a car that has been switched off is not coming back
        // inside the minute; held as long as a network it reads as a reminder that simply arrives
        // two minutes late, which is what it was reported as.
        assertTrue(
            Geofencing.graceFor(SignalKind.Bluetooth) < Geofencing.graceFor(SignalKind.Wifi),
            "a device disconnecting is a firmer answer than a network dropping",
        )
    }

    @Test
    fun `a device outlives its grace sooner than a network does`() {
        // The same absence, the same moment, read differently by kind — which is the whole point
        // of the two figures. Read with one grace for both, either the car is late or the router
        // blinking is a departure.
        val bothGone = Geofencing.readSignals(
            signals = listOf(office, car),
            nearby = NearbySignals(
                kinds = setOf(SignalKind.Wifi, SignalKind.Bluetooth),
                present = emptySet(),
            ),
            wasInside = mapOf(office.key to true, car.key to true),
            missingSince = mapOf(
                office.key to now.toEpochMilliseconds(),
                car.key to now.toEpochMilliseconds(),
            ),
            now = now + Geofencing.BLUETOOTH_GRACE + 1.minutes,
        ).reading

        assertTrue(car.key in bothGone.left, "the car has been gone longer than a car's grace")
        assertTrue(office.key in bothGone.inside, "the network is still inside its own grace")
    }

    @Test
    fun `a bluetooth device is known by its address whatever it has been renamed to`() {
        assertEquals(
            NearbySignal.Bluetooth("AA:BB:CC:DD:EE:FF", "Car").key,
            NearbySignal.Bluetooth("aa:bb:cc:dd:ee:ff", "The old banger").key,
            "renaming a device must not read as a different one",
        )
    }

    @Test
    fun `a network and a place cannot be confused for one another`() {
        val area = GeoArea("Office", GeoPoint(51.5, -0.12), 200.0)
        assertTrue(area.key != office.key, "both end up in one map of what is near the device")
    }

    @Test
    fun `a signal seen for the first time is recorded and reports no crossing`() {
        val reading = read(present = setOf(office.key))

        assertTrue(office.key in reading.inside)
        assertTrue(reading.entered.isEmpty(), "writing a rule on the office wifi is not joining it")
        assertFalse(reading.isCrossing)
        assertTrue(reading.known)
    }

    @Test
    fun `joining and leaving a network are each reported once`() {
        val away = read(present = emptySet(), wasInside = emptyMap())
        assertTrue(away.entered.isEmpty() && away.left.isEmpty())

        val joining = read(present = setOf(office.key), wasInside = mapOf(office.key to false))
        assertEquals(setOf(office.key), joining.entered)

        val staying = read(present = setOf(office.key), wasInside = mapOf(office.key to true))
        assertTrue(staying.entered.isEmpty(), "still being on it is not joining it again")
    }

    @Test
    fun `a network that drops for a moment has not been left`() {
        // The counterpart of the margin a geofence has: routers hiccup far more readily than
        // people walk out of buildings, and a rule that resets a task every time the wifi blinks
        // is worse than one that notices a minute late.
        val blink = read(
            present = emptySet(),
            wasInside = mapOf(office.key to true),
            missingSince = mapOf(office.key to (now - 1.minutes).toEpochMilliseconds()),
        )

        assertTrue(office.key in blink.inside, "still counted as there")
        assertTrue(blink.left.isEmpty(), "and so not reported as gone")
    }

    @Test
    fun `the grace runs from the first sweep that missed it rather than the last that saw it`() {
        // The difference is everything on a phone that is sitting still: it runs no sweeps for
        // hours, so "last seen" is hours old the instant the router blinks. Measured from that,
        // the very first sweep after a blink calls it a departure and the grace protects nothing.
        val firstMiss = readWhole(
            present = emptySet(),
            wasInside = mapOf(office.key to true),
            // Nothing written down yet: this is the first sweep that has noticed it gone.
            missingSince = emptyMap(),
        )

        assertTrue(office.key in firstMiss.reading.inside, "held, because it has only just gone")
        assertTrue(firstMiss.reading.left.isEmpty())
        assertEquals(
            now.toEpochMilliseconds(),
            firstMiss.missingSince[office.key],
            "and the clock starts now",
        )
    }

    @Test
    fun `the moment it went missing is not pushed forward by later sweeps`() {
        // Renewed on every sweep, the grace never runs out and a departure is never noticed.
        val wentAt = (now - 1.minutes).toEpochMilliseconds()
        val later = readWhole(
            present = emptySet(),
            wasInside = mapOf(office.key to true),
            missingSince = mapOf(office.key to wentAt),
        )

        assertEquals(wentAt, later.missingSince[office.key])
    }

    @Test
    fun `a signal that is present is not counting down to anything`() {
        val here = readWhole(present = setOf(office.key), wasInside = mapOf(office.key to true))

        assertTrue(office.key !in here.missingSince, "it is not missing, so nothing is counting")
    }

    @Test
    fun `a network gone for longer than the grace has been left`() {
        val gone = read(
            present = emptySet(),
            wasInside = mapOf(office.key to true),
            missingSince = mapOf(office.key to (now - grace - 1.minutes).toEpochMilliseconds()),
        )

        assertEquals(setOf(office.key), gone.left)
        assertTrue(office.key !in gone.inside)
    }

    @Test
    fun `a kind the platform cannot see is left unanswered rather than called absent`() {
        // A phone that cannot be *asked* about bluetooth — permission refused, stack wedged —
        // has not watched every paired device drive away. (A radio switched off is different: it
        // is a real answer, and the platform reports the kind as measured and empty.)
        val reading = read(
            signals = listOf(office, car),
            present = setOf(office.key),
            kinds = setOf(SignalKind.Wifi),
            wasInside = mapOf(office.key to true, car.key to true),
        )

        assertTrue(car.key !in reading.measured, "nothing was looked at for bluetooth")
        assertTrue(car.key !in reading.left, "and so nothing may be claimed about it")
        assertTrue(office.key in reading.measured)
    }

    @Test
    fun `a rule about something unmeasured does not hold as a standing condition`() {
        val reading = read(
            signals = listOf(car),
            present = emptySet(),
            kinds = setOf(SignalKind.Wifi),
        )
        val gone = listOf(NearbyChange(persistentSetOf(car), SignalDirection.Disappearing))

        assertFalse(
            gone.satisfiedBy(reading),
            "'the car is gone' must not hold on a device that cannot see bluetooth at all",
        )
    }

    @Test
    fun `a rule wanting a place and a network wants both`() {
        val area = GeoArea("Office", GeoPoint(0.0, 0.0), 200.0)
        val conditions = listOf(
            LocationChange(persistentSetOf(area), GeofenceDirection.Entering),
            NearbyChange(persistentSetOf(office), SignalDirection.Appearing),
        )

        // Arrived at the office and already on its wifi: the arrival fires it, the wifi holds.
        assertTrue(
            conditions.satisfiedBy(
                PlaceReading(
                    inside = setOf(area.key, office.key),
                    entered = setOf(area.key),
                    known = true,
                    measured = setOf(area.key, office.key),
                )
            )
        )

        // Arrived at the office but on somebody else's network: not what was asked for.
        assertFalse(
            conditions.satisfiedBy(
                PlaceReading(
                    inside = setOf(area.key),
                    entered = setOf(area.key),
                    known = true,
                    measured = setOf(area.key, office.key),
                )
            )
        )
    }

    @Test
    fun `two conditions need not both cross at the same instant`() {
        // Joining the wifi is the crossing; being at the office is a standing fact that was
        // already true. Requiring both to happen in one moment would be a rule that never fires.
        val area = GeoArea("Office", GeoPoint(0.0, 0.0), 200.0)
        val conditions = listOf(
            LocationChange(persistentSetOf(area), GeofenceDirection.Entering),
            NearbyChange(persistentSetOf(office), SignalDirection.Appearing),
        )

        assertTrue(
            conditions.satisfiedBy(
                PlaceReading(
                    inside = setOf(area.key, office.key),
                    entered = setOf(office.key),
                    known = true,
                    measured = setOf(area.key, office.key),
                )
            )
        )
    }

    @Test
    fun `nothing is satisfied by a reading that knows nothing`() {
        val conditions = listOf(NearbyChange(persistentSetOf(office), SignalDirection.Disappearing))
        assertFalse(conditions.satisfiedBy(PlaceReading.Unknown))
    }

    @Test
    fun `a reading that looked at nothing at all knows nothing`() {
        assertFalse(read(kinds = emptySet()).known)
    }

    @Test
    fun `the two halves of a reading are joined without losing either`() {
        val places = PlaceReading(inside = setOf("a"), entered = setOf("a"), known = true, measured = setOf("a"))
        val signals = PlaceReading(inside = setOf("b"), left = setOf("c"), known = true, measured = setOf("b", "c"))

        val both = Geofencing.combine(places, signals)

        assertEquals(setOf("a", "b"), both.inside)
        assertEquals(setOf("a"), both.entered)
        assertEquals(setOf("c"), both.left)
        assertEquals(setOf("a", "b", "c"), both.measured)
        assertTrue(both.known)
    }

    @Test
    fun `a half that knows nothing does not silence the half that does`() {
        // A phone in a basement has no fix and can still see the office wifi.
        val signals = PlaceReading(inside = setOf("b"), known = true, measured = setOf("b"))

        val both = Geofencing.combine(PlaceReading.Unknown, signals)

        assertTrue(both.known)
        assertEquals(setOf("b"), both.inside)
    }
}
