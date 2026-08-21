package com.zhelenskiy.zheduler.zheduler.geo

import androidx.compose.runtime.Composable

/**
 * How this platform answers "what is near the device": the wifi it is on, the bluetooth it is
 * connected to.
 *
 * Made once and given to the engine, which asks it at most once a sweep and only when some rule is
 * watching for something. A platform that cannot answer for a kind says so per kind rather than
 * reporting everything of that kind as absent — see [NearbySignals.kinds].
 */
expect fun createSignalSource(): SignalSource

/**
 * What this platform can offer the user to choose from.
 *
 * Not the same question as [SignalSource.nearby]: this is for the picker, and wants everything
 * worth listing — the network currently joined, every bluetooth device already paired — rather
 * than only what is present this second. Empty where the platform cannot enumerate anything, in
 * which case the picker falls back to typing a network name by hand.
 */
expect suspend fun offerableSignals(): List<NearbySignal>

/**
 * Whether the app may look at all, and how to ask.
 *
 * Separate from the location permission because the platforms ask separately: Android wants
 * `BLUETOOTH_CONNECT` of its own from API 31, and reading which wifi network is joined has needed
 * the location permission since API 27 — so a user who has granted one may still be refused the
 * other.
 */
@Composable
expect fun rememberSignalPermission(): LocationPermissionState

/**
 * Which kinds this build can say anything about at all, whatever the user later permits.
 *
 * Shown on the screen where signals are chosen, so a rule that cannot work here says so before it
 * is written rather than by silently never firing.
 */
expect val supportedSignalKinds: Set<SignalKind>

/**
 * Something wrong that only trying will reveal, in words for the user, or null if all is well.
 *
 * [supportedSignalKinds] says what this build can do *in principle*. This says what it turns out
 * it can do here and now — a Mac that refuses to name the network it is on looks exactly like one
 * that is on none until you ask it, and a rule written in that state would never fire and never
 * say why. Asked when the picker opens, so the answer arrives before the rule is written.
 */
expect suspend fun signalTrouble(): String?
