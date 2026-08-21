package com.zhelenskiy.zheduler.zheduler.geo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * A desktop has no notion of where it is.
 *
 * There are ways to guess — the address the machine reaches the internet from, the names of the
 * wireless networks it can see — and each of them is a request to a third party carrying either
 * the user's address or a list of their neighbours' routers, for an answer good to a city block at
 * best. A rule about arriving home is not worth that, so the desktop build simply never fires one
 * and says so on the screen where places are kept.
 */
actual fun createLocationSource(): LocationSource = NoLocationSource

@Composable
actual fun rememberLocationPermission(): LocationPermissionState =
    remember { FixedLocationPermission(LocationPermissionStatus.Unavailable) }
