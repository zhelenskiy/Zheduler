package com.zhelenskiy.zheduler.zheduler.geo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * iOS will not say, and this build does not pretend otherwise.
 *
 * **Wifi.** The name of the joined network is behind an entitlement — `wifi-info`, or the hotspot
 * helper — that Apple grants per application and that has to be provisioned into the signing
 * profile. Without it the system returns nothing at all rather than an error, so an app that asked
 * anyway would report every network absent and fire every "when I leave the wifi" rule the moment
 * it ran. Answering "cannot tell" is the honest form of the same ignorance, and fires nothing.
 *
 * **Bluetooth.** Core Bluetooth sees low-energy peripherals by scanning, which needs its own
 * permission and a running scan; the classic devices a rule would be about — a car, a pair of
 * headphones — are not visible to it at all, only to MFi accessories. Nothing here would answer
 * the question a user was actually asking.
 *
 * A rule that names a place still works on iOS. The screen where signals are chosen says which
 * kinds this build can see, so a rule that could not work here is not written blind.
 */
actual fun createSignalSource(): SignalSource = NoSignalSource

actual val supportedSignalKinds: Set<SignalKind> = emptySet()

actual suspend fun offerableSignals(kind: SignalKind): List<OfferedSignal> = emptyList()

/** Nothing to add: neither kind is offered here at all, which the picker already says. */
actual suspend fun signalTrouble(kind: SignalKind): String? = null

@Composable
actual fun rememberSignalPermission(): LocationPermissionState =
    remember { FixedLocationPermission(LocationPermissionStatus.Unavailable) }
