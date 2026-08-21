package com.zhelenskiy.zheduler.zheduler.geo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * A browser is not told what the machine is connected to, and that is deliberate on its part.
 *
 * There is no web API for the name of the wifi network — it would identify the user's home to
 * every page they open — and Web Bluetooth reaches only a device the user has picked out of a
 * chooser, in response to a click, one device at a time. Neither can answer "is the office network
 * here" on a schedule without anybody watching.
 *
 * So this answers "cannot tell", which fires nothing, and the screen where signals are chosen says
 * as much before a rule is written.
 */
actual fun createSignalSource(): SignalSource = NoSignalSource

actual val supportedSignalKinds: Set<SignalKind> = emptySet()

actual suspend fun offerableSignals(): List<NearbySignal> = emptyList()

/** Nothing to add: neither kind is offered here at all, which the picker already says. */
actual suspend fun signalTrouble(): String? = null

@Composable
actual fun rememberSignalPermission(): LocationPermissionState =
    remember { FixedLocationPermission(LocationPermissionStatus.Unavailable) }
