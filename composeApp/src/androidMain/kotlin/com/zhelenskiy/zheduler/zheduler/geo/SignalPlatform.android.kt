package com.zhelenskiy.zheduler.zheduler.geo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
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
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

actual fun createSignalSource(): SignalSource = androidSignals

/**
 * One for the whole app.
 *
 * [AndroidSignalSource] remembers which bluetooth profiles would not answer and leaves them alone
 * for a while — and a fresh one per question has nothing to remember, so a wedged bluetooth service
 * would be waited on for ten seconds every time a picker opened.
 */
private val androidSignals: AndroidSignalSource by lazy { AndroidSignalSource(androidApplication()) }

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
internal class AndroidSignalSource(private val context: Context) : SignalSource {

    override suspend fun nearby(): NearbySignals {
        val kinds = mutableSetOf<SignalKind>()
        val present = mutableSetOf<String>()
        val definite = mutableSetOf<SignalKind>()

        joinedNetwork()?.let { ssid ->
            kinds += SignalKind.Wifi
            if (ssid.isNotEmpty()) present += NearbySignal.Wifi(ssid).key
            // A name means we are on *that* network, so every other one is settled: you cannot be
            // on two. An empty answer from a radio that is switched off is settled the same way.
            // What is left — the radio on and associated with nothing — is the flaky case the
            // grace exists for, and only that keeps it.
            if (ssid.isNotEmpty() || !wifiRadioOn()) definite += SignalKind.Wifi
        }
        connectedBluetooth()?.let { devices ->
            kinds += SignalKind.Bluetooth
            devices.forEach { present += it }
            // An adapter that is off has nothing connected to it, and says so plainly.
            if (!bluetoothAdapterOn()) definite += SignalKind.Bluetooth
        }
        return NearbySignals(kinds = kinds, present = present, definite = definite)
    }

    /** The same question for the other radio. */
    private fun bluetoothAdapterOn(): Boolean = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)

    /** Whether the radio is on at all, which is what tells a switched-off phone from a lost one. */
    private fun wifiRadioOn(): Boolean {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wifi?.isWifiEnabled == true
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
    internal suspend fun joinedNetwork(): String? {
        if (!context.hasWifiNamePermission()) return null
        // The permission is not enough: since API 27 the name is withheld whenever location is
        // switched off at the system level, permission or not.
        if (!locationServicesOn()) return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        if (!wifi.isWifiEnabled) return ""

        // The name, from whichever of the two ways of asking will actually give it up.
        //
        // From API 31 nothing *synchronous* will: `WifiManager.getConnectionInfo` is deprecated
        // and hands back a record with the name replaced by "<unknown ssid>", and so — this is the
        // part that is easy to get wrong — does `NetworkCapabilities.getTransportInfo`, whatever
        // permissions the caller holds. The one path that carries the real name is a network
        // callback registered with `FLAG_INCLUDE_LOCATION_INFO`, which is why one is kept running.
        // Read only the redacted way, the app never learns the name at all and every wifi rule
        // sits unanswered forever with nothing on screen to say why.
        watchNetwork()
        val info = try {
            @Suppress("DEPRECATION")
            wifi.connectionInfo
        } catch (_: SecurityException) {
            null
        }

        // Waited for, but only where waiting can pay. The system delivers the first callback on a
        // thread of its own a moment after registering, so the very first look of a process reads
        // an empty cache — and on a modern phone the other call is redacted, so that look reported
        // "cannot tell" and the picker offered nothing until it was closed and opened again.
        //
        // Only when the redacted record says we are plainly on *something*: a phone genuinely off
        // wifi has a real answer already and must not be made to wait for one.
        // Only where the callback is the *only* way to the name. Below API 31 the deprecated call
        // still answers plainly and the cache is never filled at all, so waiting there would be
        // 1.5 s of nothing on every sweep for ever, in front of an answer already in hand.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && joined == null && info?.isJoined() == true) {
            withTimeoutOrNull(FIRST_ANSWER) {
                while (joined == null) delay(BETWEEN_ASKS)
            }
        }
        val fromCallback = joined?.second

        val ssid = fromCallback?.takeIf { it.isNotEmpty() }
            ?: info?.ssid?.trim('"').orEmpty()
        // Whichever of them said anything about being connected at all. The supplicant state
        // survives redaction even where the name does not.
        val joined = info?.isJoined() == true || fromCallback != null
        if (info == null && fromCallback == null) return null
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

    /**
     * The joined network's name and which network it belongs to, or null for "not on one".
     *
     * The network is kept alongside the name because losses and gains overlap: a phone that can
     * hold two links at once joins the new network before dropping the old, so an unconditional
     * `onLost` wipes a name that had just been read from a *different* network — and the sweep
     * that follows falls back to the redacted call, reports "cannot tell", and stays there until
     * the live network happens to change capabilities again, which on a stable one is a long time.
     *
     * Empty string never appears here: a name that could not be read is left as null rather than
     * written down as nothing.
     */
    @Volatile
    private var joined: Pair<Network, String>? = null

    private val watching = AtomicBoolean(false)

    /** Writes down a name against the network it came from, leaving what is there if it has none. */
    private fun remember(network: Network, capabilities: NetworkCapabilities) {
        capabilities.wifiName()?.let { joined = network to it }
    }

    /** Forgets a name only where it belonged to the network that has gone. */
    private fun forget(network: Network) {
        if (joined?.first == network) joined = null
    }

    /**
     * Keeps a callback running for whatever wifi network is up.
     *
     * Registered once and left, because the answer wants to be ready before a sweep asks: the
     * system delivers the first one a moment later on a thread of its own. Costs nothing while
     * nothing changes — it is called on connect and disconnect, which are the moments a wifi rule
     * is about.
     *
     * Registered only once there is a permission to read the name with. Without one it would fire
     * and report "<unknown ssid>" for ever, which is not something to write down.
     */
    private fun watchNetwork() {
        if (!context.hasWifiNamePermission()) return
        // Compared and set as one, because a sweep and a picker can arrive together and two
        // permanent callbacks would be registered where one was wanted.
        if (!watching.compareAndSet(false, true)) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            watching.set(false)
            return
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The flag is the whole point: without it this record is redacted like the rest.
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    remember(network, capabilities)

                override fun onLost(network: Network) = forget(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    remember(network, capabilities)

                override fun onLost(network: Network) = forget(network)
            }
        }
        // Put back on failure, so the next sweep tries again — a permission granted since is
        // exactly the thing that makes this work where it did not a moment ago.
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onFailure { watching.set(false) }
    }

    /** Whether there is a connection at all, whatever the system is willing to say about it. */
    @Suppress("DEPRECATION")
    private fun android.net.wifi.WifiInfo.isJoined(): Boolean =
        supplicantState == SupplicantState.COMPLETED || networkId != -1

    private fun locationServicesOn(): Boolean = context.locationServicesOn()

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
 * What it takes to be told the name of the network this phone is on.
 *
 * Precise location, and nothing else will do. The one call that hands back an unredacted name is a
 * network callback flagged to include location info, and that flag is answered for by
 * `ACCESS_FINE_LOCATION` alone: approximate location — which the system offers as a choice on the
 * very prompt this app shows — does not unlock it, and neither does `NEARBY_WIFI_DEVICES` while
 * the app declines to promise it is not deriving location from it (which it plainly is).
 *
 * Getting this gate wrong is quiet in both directions. Too strict and a phone that could answer is
 * never asked. Too loose and the app keeps a foreground service, a notification and a radio running
 * for a wifi rule whose name can never be read.
 */
internal fun Context.hasWifiNamePermission(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * From Android 12 reading anything about bluetooth needs a permission of its own; before that the
 * old blanket one was granted at install and there is nothing to ask for.
 */
internal fun Context.hasBluetoothPermission(): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
    else checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/**
 * Whether the system is switched off in a way the permission notices do not cover.
 *
 * Since API 27 the name of the network is withheld whenever location is switched off at the system
 * level, permission or not — so a phone that has been granted everything still answers "cannot
 * tell", and a wifi rule written here never fires with nothing on screen to say why. The permission
 * rows in the picker do not cover it, because there is nothing left to grant.
 */
actual suspend fun signalTrouble(kind: SignalKind): String? {
    if (kind != SignalKind.Wifi) return null
    val context = androidApplication()
    if (!context.hasWifiNamePermission()) {
        // Said here rather than left to the picker's permission row, because that row is shown
        // against the *location* permission and approximate location satisfies it — so a user who
        // tapped "Approximate" on the app's own prompt is holding a permission the app is happy
        // with and one the wifi APIs will not answer for. Nothing else in the app would say a word,
        // and every network a rule watches would be labelled "not here now": an absence measured by
        // a phone that was never allowed to look.
        return if (context.hasLocationPermission()) {
            "Wi-Fi rules need precise location. This app has only the approximate kind, so this " +
                "phone will not say which network it is on. Change it to Precise in the system " +
                "settings for this app."
        } else {
            // Nothing granted at all, which the picker's own permission row does cover.
            null
        }
    }
    if (!context.locationServicesOn()) {
        return "Location is switched off, so this phone will not say which wifi network it is " +
            "on and a rule about one cannot fire until it is switched back on."
    }
    // Everything granted and switched on, and the name still withheld. Rare now that the network's
    // own record is what is read, but it is the failure that hides best: the phone is plainly on a
    // network, the rule is plainly saved, and nothing happens. Said out loud rather than left as a
    // wifi list that is silently always empty.
    return if (androidSignals.nearby().let { SignalKind.Wifi !in it.kinds }) {
        "This phone would not say which wifi network it is on, so a rule about one cannot fire " +
            "until it does."
    } else {
        null
    }
}

/** Whether the system's location switch is on, which gates the name of the network. */
internal fun Context.locationServicesOn(): Boolean {
    val manager = getSystemService(LocationManager::class.java) ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled
    else runCatching {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)
}

actual suspend fun offerableSignals(kind: SignalKind): List<OfferedSignal> {
    val context = androidApplication()
    val offers = mutableListOf<OfferedSignal>()

    // The network already joined, which is nearly always the one a rule is about — and joined is
    // what present means, so there is nothing further to ask.
    //
    // Asked of the same reader the sweeps use rather than of `connectionInfo` directly: from API 31
    // that call hands back a redacted name, so a picker reading it offered nothing on any modern
    // phone and the user had to type the network by hand — where a typo is a rule that never fires.
    if (kind == SignalKind.Wifi && context.hasWifiNamePermission()) {
        androidSignals.joinedNetwork()?.takeIf { it.isNotEmpty() }?.let { ssid ->
            offers += OfferedSignal(NearbySignal.Wifi(ssid), present = true)
        }
    }

    // Everything already paired, whether or not it is switched on at this moment — a rule about
    // the car is written indoors.
    if (kind == SignalKind.Bluetooth && context.hasBluetoothPermission()) {
        // Asked once and only here, because it is what costs: it binds a system service per
        // profile. Worth it for this list, which is how a user picks the right pair of headphones
        // out of three, and pure waste for the wifi picker.
        val here = androidSignals.nearby().present
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        runCatching { adapter?.bondedDevices }.getOrNull()?.forEach { device ->
            val signal = NearbySignal.Bluetooth(
                address = device.address,
                name = runCatching { device.name }.getOrNull().orEmpty(),
            )
            offers += OfferedSignal(signal, present = signal.key in here)
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

/**
 * The network's name as this record carries it, or null where it carries none.
 *
 * Redacted records read as "<unknown ssid>", which is not a name and must not be written down as
 * one — a rule about the office wifi would then be a rule about a network called "<unknown ssid>".
 */
private fun NetworkCapabilities.wifiName(): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val info = transportInfo as? WifiInfo ?: return null
    val name = info.ssid?.trim('"').orEmpty()
    return name.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID.trim('"') }
}

/**
 * How long the first look will wait for the system to name the network it is plainly on.
 *
 * Short, because it is spent inside a sweep and a sweep has other things to read; long enough for
 * a binder round trip and the handler thread behind it. Paid once in the ordinary case — after the
 * first callback the answer is already there — and never at all on a phone that is off wifi, or
 * old enough that the plain call still answers.
 */
private val FIRST_ANSWER = 1.5.seconds

private val BETWEEN_ASKS = 50.milliseconds
