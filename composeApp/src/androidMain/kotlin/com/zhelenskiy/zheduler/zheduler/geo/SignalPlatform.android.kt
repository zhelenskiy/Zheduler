package com.zhelenskiy.zheduler.zheduler.geo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zhelenskiy.zheduler.zheduler.di.androidApplication
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

actual fun createSignalSource(): SignalSource = AndroidSignalSource(androidApplication())

actual val supportedSignalKinds: Set<SignalKind> = setOf(SignalKind.Wifi, SignalKind.Bluetooth)

/**
 * The wifi the device is joined to, and the bluetooth devices it is connected to.
 *
 * *Joined* and *connected*, not merely within earshot. Scanning for networks in range is throttled
 * to a handful of scans an hour on modern Android and drains the radio; discovering bluetooth
 * devices takes twelve seconds and interrupts whatever audio is playing. Neither is a thing to do
 * every sweep — and neither is what people mean. "The office wifi" means the one being used, and
 * "the car" means the one the phone is talking to.
 */
private class AndroidSignalSource(private val context: Context) : SignalSource {

    override suspend fun nearby(): NearbySignals {
        val kinds = mutableSetOf<SignalKind>()
        val present = mutableSetOf<String>()

        joinedNetwork()?.let { ssid ->
            kinds += SignalKind.Wifi
            if (ssid.isNotEmpty()) present += NearbySignal.Wifi(ssid).key
        }
        connectedBluetooth()?.let { devices ->
            kinds += SignalKind.Bluetooth
            devices.forEach { present += it }
        }
        return NearbySignals(kinds = kinds, present = present)
    }

    /**
     * The name of the network currently joined, `""` for "none", or null for "cannot tell".
     *
     * The three answers matter. Not joined to anything is a real answer and is what makes a "when
     * I leave the home wifi" rule fire; not being *allowed* to look is not, and must leave every
     * wifi rule unanswered instead. Since API 27 the name is only given to an app holding the
     * location permission with location switched on, which is why that is checked here rather than
     * trusting the blank the system hands back.
     */
    private fun joinedNetwork(): String? {
        if (!context.hasLocationPermission()) return null
        // The permission is not enough: since API 27 the name is withheld whenever location is
        // switched off at the system level, permission or not.
        if (!locationServicesOn()) return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        if (!wifi.isWifiEnabled) return ""
        val info = try {
            @Suppress("DEPRECATION")
            wifi.connectionInfo
        } catch (_: SecurityException) {
            return null
        } ?: return null
        val ssid = info.ssid?.trim('"').orEmpty()
        val joined = info.isJoined()
        return when {
            ssid.isNotEmpty() && ssid != WifiManager.UNKNOWN_SSID.trim('"') -> ssid
            // Withheld while plainly connected to something. Not "on no network" — reported as
            // that, a rule about leaving the home wifi goes off while the user sits in their
            // kitchen, and reports arriving again the moment the name comes back.
            joined -> null
            // Really not on anything, which is a proper answer and is what fires a leaving rule.
            else -> ""
        }
    }

    /** Whether there is a connection at all, whatever the system is willing to say about it. */
    @Suppress("DEPRECATION")
    private fun android.net.wifi.WifiInfo.isJoined(): Boolean =
        supplicantState == SupplicantState.COMPLETED || networkId != -1

    private fun locationServicesOn(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled
        else runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    /**
     * The addresses of the bluetooth devices currently connected, or null for "cannot tell".
     *
     * Gathered from the profiles a device connects *on* — headset, audio, and the low-energy
     * link — because Android has no public "is this device connected" of its own. A device paired
     * but switched off is not connected and is rightly absent.
     *
     * All or nothing. A profile that would not answer is not an empty profile: headset and audio
     * are exactly what a car and a pair of headphones connect on, so a half-answer that dropped
     * them would report the car absent while the user was driving it, and a rule about the car
     * going away would fire on the motorway. Partial knowledge is reported as none.
     */
    private suspend fun connectedBluetooth(): Set<String>? {
        if (!context.hasBluetoothPermission()) return null
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        if (!adapter.isEnabled) return emptySet()

        val connected = mutableSetOf<String>()
        // The low-energy links, which the manager can answer for directly.
        listOf(BluetoothProfile.GATT, BluetoothProfile.GATT_SERVER).forEach { profile ->
            val devices = runCatching { manager.getConnectedDevices(profile) }.getOrNull() ?: return null
            devices.forEach { connected += NearbySignal.Bluetooth(it.address).key }
        }
        // The classic ones need a proxy, which arrives on a callback.
        listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP).forEach { profile ->
            connected += devicesOn(adapter, profile) ?: return null
        }
        return connected
    }

    /**
     * The devices connected on one classic profile.
     *
     * The proxy is closed again as soon as it has answered: each one holds a binding to a system
     * service, and a sweep that leaked one every fifteen minutes would eventually be refused any
     * more.
     */
    private suspend fun devicesOn(adapter: BluetoothAdapter, profile: Int): Set<String>? {
        // A profile that did not answer last time is left alone for a while. The binding a request
        // makes cannot be called off — there is no proxy to close until the callback that never
        // came — so asking again every sweep of a wedged bluetooth service just piles them up.
        sulking[profile]?.let { since ->
            if (SystemClock.elapsedRealtime() - since < PROXY_BACKOFF.inWholeMilliseconds) return null
            sulking -= profile
        }
        val answer = askProfile(adapter, profile)
        if (answer == null) sulking[profile] = SystemClock.elapsedRealtime()
        return answer
    }

    /** Profiles that timed out, and when — see [devicesOn]. */
    private val sulking = mutableMapOf<Int, Long>()

    private suspend fun askProfile(adapter: BluetoothAdapter, profile: Int): Set<String>? =
        withTimeoutOrNull(PROXY_TIMEOUT) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(which: Int, proxy: BluetoothProfile) {
                        val devices = runCatching {
                            proxy.connectedDevices.map { device: BluetoothDevice ->
                                NearbySignal.Bluetooth(device.address).key
                            }
                        }.getOrDefault(emptyList())
                        runCatching { adapter.closeProfileProxy(which, proxy) }
                        if (continuation.isActive) continuation.resume(devices.toSet())
                    }

                    override fun onServiceDisconnected(which: Int) {
                        if (continuation.isActive) continuation.resume(emptySet())
                    }
                }
                val asked = runCatching {
                    adapter.getProfileProxy(context, listener, profile)
                }.getOrDefault(false)
                if (!asked && continuation.isActive) continuation.resume(emptySet())
            }
        }

    private companion object {
        /** A binding to a system service that has not answered in this long is not going to. */
        val PROXY_TIMEOUT = 5.seconds

        /** How long a profile that would not answer is left alone before being asked again. */
        val PROXY_BACKOFF = 10.minutes
    }
}

/**
 * From Android 12 reading anything about bluetooth needs a permission of its own; before that the
 * old blanket one was granted at install and there is nothing to ask for.
 */
internal fun Context.hasBluetoothPermission(): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
    else checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/** Nothing that is not already said by the permission notices in the picker. */
actual suspend fun signalTrouble(): String? = null

actual suspend fun offerableSignals(): List<NearbySignal> {
    val context = androidApplication()
    val offers = mutableListOf<NearbySignal>()

    // The network already joined, which is nearly always the one a rule is about.
    if (context.hasLocationPermission()) {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val ssid = runCatching { wifi?.connectionInfo?.ssid?.trim('"') }.getOrNull().orEmpty()
        if (ssid.isNotEmpty() && ssid != WifiManager.UNKNOWN_SSID.trim('"')) {
            offers += NearbySignal.Wifi(ssid)
        }
    }

    // Everything already paired, whether or not it is switched on at this moment — a rule about
    // the car is written indoors.
    if (context.hasBluetoothPermission()) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        runCatching { adapter?.bondedDevices }.getOrNull()?.forEach { device ->
            offers += NearbySignal.Bluetooth(
                address = device.address,
                name = runCatching { device.name }.getOrNull().orEmpty(),
            )
        }
    }
    return offers
}

@Composable
actual fun rememberSignalPermission(): LocationPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasBluetoothPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = context.hasBluetoothPermission()
    }
    return remember(granted, launcher) {
        object : LocationPermissionState {
            override val status: LocationPermissionStatus
                get() = if (granted) LocationPermissionStatus.Granted else LocationPermissionStatus.Denied

            override fun request() {
                if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }

            // Nothing further to ask for: a phone that may read its radios may read them whenever
            // the app is running, and what keeps the app running is the location watch.
            override val worksWhileAway: Boolean get() = true
            override val requestWhileAway: (() -> Unit)? get() = null
        }
    }
}
