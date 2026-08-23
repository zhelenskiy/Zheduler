package com.zhelenskiy.zheduler.zheduler.geo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something the device can tell is near it, other than by its coordinates.
 *
 * A wifi network and a car's bluetooth are both, in practice, ways of knowing where you are — and
 * better ones than a fix on a rooftop: being on the office network says you are *in* the office in
 * a way that fifty metres of GPS never quite does, and it works indoors and underground where
 * positioning does not. So these stand alongside an area rather than apart from it, and are
 * matched by the same machinery: present or absent, appearing or going away.
 *
 * Carried inside the rule that watches for them, like an area, so a task exported to another device
 * still knows what it was waiting for.
 */
@Serializable
sealed class NearbySignal {

    /**
     * Identity, and what whereabouts are remembered against.
     *
     * Kept in the same namespace as `GeoArea.key` — both end up in one map of what the device was
     * last known to be near — so each kind carries a prefix that cannot collide with a coordinate.
     */
    abstract val key: String

    /** What kind of thing this is, which is what decides whether a platform can answer for it. */
    abstract val kind: SignalKind

    /** What to show for it. */
    abstract val label: String

    /**
     * A wifi network, by the name it broadcasts.
     *
     * By name rather than by the access point's address, because a network worth writing a rule
     * about — home, the office — is usually several access points under one name, and moving
     * between them is not leaving.
     */
    @SerialName("com.zhelenskiy.zheduler.zheduler.geo.NearbySignal.Wifi")
    @Serializable
    data class Wifi(val ssid: String) : NearbySignal() {
        override val key: String get() = "wifi:$ssid"
        override val kind: SignalKind get() = SignalKind.Wifi
        override val label: String get() = ssid.ifBlank { "Unnamed network" }
    }

    /**
     * A bluetooth device, by its address.
     *
     * The address rather than the name: names are not unique, and a user can rename a device
     * without meaning to rewrite their rules. [name] is carried alongside only to be shown.
     */
    @SerialName("com.zhelenskiy.zheduler.zheduler.geo.NearbySignal.Bluetooth")
    @Serializable
    data class Bluetooth(val address: String, val name: String = "") : NearbySignal() {
        // Upper case, because the platforms are not agreed on which they hand back and an address
        // that reads differently is a device the schedule thinks it has never seen before.
        override val key: String get() = "bt:${address.uppercase()}"
        override val kind: SignalKind get() = SignalKind.Bluetooth
        override val label: String get() = name.ifBlank { address.ifBlank { "Unknown device" } }
    }
}

/**
 * The kinds of thing a platform may or may not be able to answer for.
 *
 * The distinction matters because they fail separately: a desktop can often say which wifi network
 * it is on and has no idea about bluetooth, and a rule about a device it cannot see must be left
 * *unanswered* rather than answered "not there" — see [PlaceReading.measured].
 *
 * The names are written into every task's `recurrenceRulesJson`; see `StoredEnumNamesTest`.
 */
@Serializable
enum class SignalKind {
    Wifi,
    Bluetooth,
}

/**
 * Which way a rule is waiting for a signal to cross.
 *
 * Its own vocabulary rather than [GeofenceDirection]'s, because the words are what the user reads
 * and a wifi network does not arrive anywhere. The three cases are the same three.
 *
 * The names are written into every task's `recurrenceRulesJson`; see `StoredEnumNamesTest`.
 */
@Serializable
enum class SignalDirection {
    Appearing,
    Disappearing,
    EitherWay;

    val displayName: String
        get() = when (this) {
            Appearing -> "it is there"
            Disappearing -> "it is gone"
            EitherWay -> "it comes or goes"
        }

    val matchesAppearing: Boolean get() = this != Disappearing
    val matchesDisappearing: Boolean get() = this != Appearing
}

/**
 * What a platform can currently say about what is near the device.
 *
 * [kinds] is the honest part: only the kinds named here were actually looked at, and a signal of
 * any other kind is left unmeasured rather than assumed absent. Without it, a phone that cannot be
 * *asked* about bluetooth — the permission refused, the stack wedged — would report every paired
 * device as having just gone away.
 *
 * A radio switched off is a different thing and is a real answer: nothing is connected to a
 * bluetooth adapter that is off, so that reports the kind as measured and everything absent, and a
 * rule about the car going away does fire when the phone goes into airplane mode. That is what
 * happened, as far as the phone can tell.
 */
data class NearbySignals(
    val kinds: Set<SignalKind> = emptySet(),
    val present: Set<String> = emptySet(),
    /**
     * The kinds whose absences this reading has *settled*, rather than merely failed to see.
     *
     * The grace that holds a missing signal as still present exists for one thing: a network drops
     * for a moment — a router reboots, a lift takes the signal — and that is not leaving the
     * building. It is the wrong answer to a radio that has been *switched off*, or to a phone that
     * is plainly on a different network now: those are not a signal that failed to turn up, they
     * are the system saying it has gone.
     *
     * Held under the grace anyway, switching wifi off and on again inside two minutes produces no
     * crossing at all — the departure is swallowed by the grace and the return is not an arrival,
     * because nothing ever recorded it as gone. Which is exactly what a rule that never fires
     * looks like from the outside, and no amount of asking again will shake it loose.
     */
    val definite: Set<SignalKind> = emptySet(),
) {
    companion object {
        /** Nothing could be looked at. */
        val Unknown = NearbySignals()
    }
}

/**
 * What is near the device, asked of whatever this platform has.
 *
 * Answered by a phone's wifi and bluetooth, partly by a desktop, and not at all by a browser — a
 * page cannot be told which network it is on, and reaches a bluetooth device only through a
 * pairing dialog the user drives. Every one of those is [NearbySignals.Unknown] for the kinds it
 * cannot speak for, which fires nothing.
 */
fun interface SignalSource {
    /** What is near the device now, and which kinds that answer covers. */
    suspend fun nearby(): NearbySignals
}

/** For platforms with nothing to ask, and for tests that are not about this. */
object NoSignalSource : SignalSource {
    override suspend fun nearby(): NearbySignals = NearbySignals.Unknown
}

/**
 * A network or a device the user has kept, to pick from when writing a rule.
 *
 * The counterpart of `SavedLocation`, and kept for the same reason: what a rule watches is copied
 * into it, so the book is somewhere to pick from rather than the rules' own storage, and an entry
 * deleted here leaves every rule alone.
 *
 * [name] is the user's word for it and is only ever shown; [signal] is the whole of what is
 * matched. The two differ more often than they do for a place — a network broadcasts
 * "acme-corp-5G" and the user thinks of it as the office.
 */
@Serializable
data class SavedSignal(
    val id: String,
    val name: String,
    val signal: NearbySignal,
) {
    val kind: SignalKind get() = signal.kind

    /** The user's word for it, falling back to whatever the system calls it. */
    val displayName: String get() = name.ifBlank { signal.label }

    /** The SSID or the address: the column the book is matched and stored by. */
    val storedValue: String
        get() = when (signal) {
            is NearbySignal.Wifi -> signal.ssid
            is NearbySignal.Bluetooth -> signal.address
        }

    /** What the device calls itself, which only a bluetooth device has. */
    val storedDeviceName: String
        get() = when (signal) {
            is NearbySignal.Wifi -> ""
            is NearbySignal.Bluetooth -> signal.name
        }

    /**
     * As it is kept, whichever repository keeps it.
     *
     * The address in upper case because that is what [NearbySignal.Bluetooth.key] matches on, and
     * a book holding one casing while a rule holds another would offer the same device twice. The
     * SSID is left exactly as typed — trailing spaces and all are part of a network's name.
     */
    fun normalised(): SavedSignal = copy(
        name = name.trim(),
        signal = when (signal) {
            is NearbySignal.Wifi -> signal
            is NearbySignal.Bluetooth -> signal.copy(
                address = signal.address.uppercase(),
                name = signal.name.trim(),
            )
        },
    )

    /** Whether a search for [needle] should turn this up. Blank finds everything. */
    fun matches(needle: String): Boolean = needle.isEmpty() ||
        name.contains(needle, ignoreCase = true) ||
        storedDeviceName.contains(needle, ignoreCase = true) ||
        storedValue.contains(needle, ignoreCase = true)
}
