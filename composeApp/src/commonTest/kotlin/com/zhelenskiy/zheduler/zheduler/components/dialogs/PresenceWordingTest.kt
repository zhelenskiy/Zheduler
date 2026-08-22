package com.zhelenskiy.zheduler.zheduler.components.dialogs

import com.zhelenskiy.zheduler.zheduler.geo.LocationPermissionStatus
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import com.zhelenskiy.zheduler.zheduler.geo.SignalKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Unknown is not absent" as the picker says it out loud.
 *
 * This is where a user goes to find out why a rule never fires, so a row claiming a thing is not
 * here — when nothing established that — is the picker telling them the opposite of the truth.
 */
class PresenceWordingTest {

    private val everything = setOf(SignalKind.Wifi, SignalKind.Bluetooth)
    private val car = NearbySignal.Bluetooth("AA:BB:CC:DD:EE:FF", "Car audio")
    private val office = NearbySignal.Wifi("acme-corp-5G")

    @Test
    fun `a kind this build cannot ask about was never measured`() {
        assertFalse(
            presenceIsMeasurable(
                kind = SignalKind.Bluetooth,
                supported = setOf(SignalKind.Wifi),
                trouble = null,
                permission = LocationPermissionStatus.Granted,
            )
        )
    }

    @Test
    fun `a machine that could not answer was never measured`() {
        // The Mac that will not name the network it is on supports wifi perfectly well; what it
        // does not do is answer. Read off the supported kinds alone, every saved network would be
        // reported absent directly beneath the banner saying the machine will not say.
        assertFalse(
            presenceIsMeasurable(
                kind = SignalKind.Wifi,
                supported = everything,
                trouble = "This Mac will not tell an app which wifi network it is on.",
                permission = LocationPermissionStatus.Granted,
            )
        )
    }

    @Test
    fun `a refused permission was never measured`() {
        assertFalse(
            presenceIsMeasurable(
                kind = SignalKind.Wifi,
                supported = everything,
                trouble = null,
                permission = LocationPermissionStatus.Denied,
            )
        )
    }

    @Test
    fun `no such permission to hold is not a refusal`() {
        // A desktop has no permission to grant for this and answers anyway. Read as a refusal, a
        // machine that can see its own network would say it cannot.
        assertTrue(
            presenceIsMeasurable(
                kind = SignalKind.Wifi,
                supported = everything,
                trouble = null,
                permission = LocationPermissionStatus.Unavailable,
            )
        )
    }

    @Test
    fun `what is here is said to be here whether or not the rest could be looked at`() {
        assertEquals("Connected now", presenceLine(car, connected = true, measured = true))
        assertEquals("Joined now", presenceLine(office, connected = true, measured = true))
    }

    @Test
    fun `nothing is called absent where absence was not established`() {
        assertEquals("Not known on this device", presenceLine(office, connected = false, measured = false))
        // A device says its address instead, which is all that is known about it here — and not
        // "paired", which is a claim about this machine that an entry saved on another one has no
        // business making.
        assertEquals("AA:BB:CC:DD:EE:FF", presenceLine(car, connected = false, measured = false))
    }

    @Test
    fun `absence is said plainly once it has been established`() {
        assertEquals("Not here now", presenceLine(office, connected = false, measured = true))
        assertEquals("Paired · AA:BB:CC:DD:EE:FF", presenceLine(car, connected = false, measured = true))
    }
}
