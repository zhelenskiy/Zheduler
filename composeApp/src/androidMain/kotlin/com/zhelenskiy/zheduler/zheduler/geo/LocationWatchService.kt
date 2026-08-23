package com.zhelenskiy.zheduler.zheduler.geo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.zhelenskiy.zheduler.zheduler.di.androidApplication
import com.zhelenskiy.zheduler.zheduler.di.obtainAppGraph
import com.zhelenskiy.zheduler.zheduler.events.ScheduledEventEngine
import com.zhelenskiy.zheduler.zheduler.settings.LocationCheckRate
import com.zhelenskiy.zheduler.zheduler.settings.createLocationSettingsStore
import com.zhelenskiy.zheduler.zheduler.settings.intervalFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches where the device goes for as long as something is waiting on a place.
 *
 * A foreground service because nothing less survives. The app's process is killed as soon as it
 * leaves the screen, and the sweeps that WorkManager books are *samples*: they compare where the
 * device is now against where it was last seen, so a user who leaves the office and comes back
 * between two of them has crossed the boundary twice and neither crossing is anywhere to be found.
 * A boundary is a moment, and only something that is running at that moment can catch it.
 *
 * What the system gives a foreground service in return for the notification is that it does not
 * kill it: it is not swapped out with the rest of the process, it keeps receiving location updates
 * while dozing, and [android.app.Service.START_STICKY] brings it back if it is ever killed for
 * memory after all.
 *
 * Nothing here needs a network. Positioning is the device's own, so a crossing is noticed with the
 * radio off; and where only the network provider can answer and there is no connectivity, the
 * reading is *unknown* rather than wrong — whereabouts are left as they were, and the crossing is
 * found the moment a real fix arrives. See `Geofencing.remember`.
 */
class LocationWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob())

    /** Everything that starts or stops a sweep is decided here, so the flag needs no lock. */
    private val main = Handler(Looper.getMainLooper())
    private var sweeping: Job? = null

    /** An update that arrived while a sweep was running, and is owed one of its own. */
    @Volatile
    private var pendingSweep = false

    /**
     * Whether this instance is still up.
     *
     * The trailing sweep below is asked for from a job's completion handler, and a job completes
     * when it is *cancelled* as well as when it finishes — which is what `onDestroy` does. Without
     * this, a service stopped while an update was owed a sweep would post itself a sweep, launch
     * it on a cancelled scope where the body that clears the flag never runs, and post again: a
     * loop on the main thread for the rest of the process's life.
     */
    private var alive = false

    private var listening = false

    /** The cadence the current registration was made with, so a change to it takes effect. */
    private var listeningWith: Pair<Long, Float>? = null

    /** Whether the rate has been read off disk yet in this process. */
    private var rateLoaded = false

    private val manager: LocationManager? by lazy {
        getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    /**
     * Every update is a sweep, which is what turns a crossing into a fired rule.
     *
     * The engine asks the platform where the device is for itself rather than being handed this
     * fix. One reading per sweep is its own rule and the two would otherwise disagree; and the one
     * it takes is a moment newer than this one anyway.
     */
    private val listener = LocationListener { _: Location -> sweepSoon() }

    /**
     * The radios changing, which is the other way a rule's condition can come true.
     *
     * Joining a network or a car's bluetooth connecting is a crossing exactly as walking into a
     * building is, and it is announced rather than having to be watched for — so a sweep here
     * costs nothing between events and catches the moment it happens.
     */
    private val radios = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = sweepSoon()
    }

    private var listeningToRadios = false

    override fun onCreate() {
        super.onCreate()
        running = true
        alive = true
        // Deliberately not promoted to the foreground here. From Android 14 a location foreground
        // service started without the permission is refused with an exception, and onCreate runs
        // before onStartCommand can check — so a sticky restart arriving after the user revoked
        // the permission would take the process down before the guard below was ever reached.
        // onStartCommand follows immediately in the same transaction, well inside the few seconds
        // the system allows between starting and promoting.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canWatch()) {
            // Revoked since this was started, which a sticky restart can well arrive after.
            // Stopping now rather than promoting is what keeps that from being a crash loop.
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground()
        startedWith = 1 or (if (hasLocationPermission()) 2 else 0) or
            (if (hasBluetoothPermission()) 4 else 0) or
            (cadenceBucket(intervalFor(checkRate, nearestMeters)) shl 3) or
            (displacementFor(tightestMeters).toInt() shl 8)
        listen()
        listenToRadios()
        // Also what stops the service again: the sweep reports whether anything is still waiting
        // on a place, and nothing being left is what turns the watch off. That is why this is safe
        // to start hopefully — at boot, say, without first reading the database.
        sweepSoon()
        // Sticky, so a service killed for memory is started again — with a null intent, which is
        // why nothing here reads one.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        startedWith = 0
        alive = false
        if (listeningToRadios) {
            runCatching { unregisterReceiver(radios) }
            listeningToRadios = false
        }
        // Nothing is owed a sweep by a service that has stopped, and anything already posted is
        // dropped rather than left to run against a cancelled scope.
        pendingSweep = false
        main.removeCallbacksAndMessages(null)
        if (listening) {
            runCatching { manager?.removeUpdates(listener) }
            listening = false
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun listen() {
        val manager = manager ?: return
        if (!hasLocationPermission()) return
        val wanted = intervalFor(checkRate, nearestMeters) to displacementFor(tightestMeters)
        // Already asking for exactly this. Registering again would be harmless but pointless.
        if (listening && listeningWith == wanted) return
        // The cadence has changed — a tighter fence has been saved, or the last one deleted. The
        // old registration has to go first: `requestLocationUpdates` with the same listener
        // replaces it per provider, but a provider that has since been switched off would keep the
        // stale one forever.
        if (listening) runCatching { manager.removeUpdates(listener) }

        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        listening = false
        // Both, where both are on. The network provider answers indoors and costs almost nothing;
        // GPS is what has any hope of a boundary a hundred metres across. Whichever speaks first
        // is a sweep, and a sweep is idempotent.
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { it in providers }
            .forEach { provider ->
                runCatching {
                    manager.requestLocationUpdates(provider, wanted.first, wanted.second, listener)
                    listening = true
                }
            }
        listeningWith = wanted.takeIf { listening }
    }

    /**
     * Listens for the network or a bluetooth device changing.
     *
     * Registered on the service rather than in the manifest: from Android 8 most of these are no
     * longer delivered to a manifest receiver at all, and there is nothing to listen for anyway
     * while the service is not running.
     */
    private fun listenToRadios() {
        if (listeningToRadios) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // From Android 13 a receiver has to say whether it expects anything from outside
                // the app. These are all the system's own broadcasts.
                registerReceiver(radios, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(radios, filter)
            }
            listeningToRadios = true
        }
    }

    /**
     * One sweep at a time, with anything that arrived during it owed a trailing one.
     *
     * Updates can arrive from two providers within a second of each other; the engine serialises
     * sweeps internally, but queueing a dozen of them behind one another is work for nothing.
     */
    private fun sweepSoon() {
        if (!alive) return
        if (sweeping?.isActive == true) {
            // Noted rather than dropped. A sweep can take twenty seconds to get its own fix, and
            // the update that arrived meanwhile may be the crossing — while further updates only
            // come if the device keeps moving a hundred metres at a time, which someone who has
            // just arrived and sat down will not. Dropped, that crossing waits for the next
            // periodic sweep, which is exactly the delay this service exists to remove.
            pendingSweep = true
            return
        }
        sweeping = scope.launch {
            do {
                pendingSweep = false
                try {
                    loadCheckRate()
                    val graph = obtainAppGraph()
                    graph.notificationPreferences.load()
                    graph.scheduledEventEngine.sweep()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // The next update is another chance. Throwing here would take the service
                    // down, and with it every rule that was waiting on a place.
                }
            } while (pendingSweep)
        }.apply {
            // The loop above can read the flag as false a moment before the job finishes, leaving
            // an update that arrived in that gap owed a sweep that nothing is going to run — and
            // the next update only comes if the device keeps moving, which someone who has arrived
            // and sat down does not. Asked again once the job is really over, on the thread every
            // other decision here is made on.
            invokeOnCompletion { if (pendingSweep && alive) main.post(::sweepSoon) }
        }
    }

    /**
     * Reads the user's chosen rate off disk, once per process.
     *
     * The service can be up before any of the app is — a boot starts it directly — and the rate is
     * a static that came back as the default with the process. Left at that, someone who chose the
     * cheapest rate for their battery got the automatic one until they next opened the app.
     */
    private suspend fun loadCheckRate() {
        if (rateLoaded) return
        rateLoaded = true
        val stored = runCatching { createLocationSettingsStore().get() }.getOrNull() ?: return
        if (stored.checkRate != checkRate) {
            checkRate = stored.checkRate
            // Registered with the old rate; clearing what identifies the watch is what makes the
            // sweep about to run register it again.
            forget()
        }
    }

    /** Whether there is anything this service could still watch with. */
    // No third clause for wifi: reading the name of a network needs *precise* location, which is
    // already one of the two this accepts. Adding it would be a longer way of saying the same thing.
    private fun canWatch(): Boolean = hasLocationPermission() || hasBluetoothPermission()

    private fun startForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Watching for places", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Shown while a task is waiting for you to arrive somewhere." }
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Watching for places")
            .setContentText("A task is waiting for you to arrive somewhere or leave it.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // From Android 10 the type has to be declared at the moment of starting as well as in
            // the manifest, or the system refuses the service outright — and from 14 it refuses a
            // location service outright unless the location permission is held. A rule that only
            // watches a bluetooth device needs neither, so it runs as what it actually is.
            val type = if (hasLocationPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    internal companion object {
        /**
         * Whether the service is up.
         *
         * Without it the watch restarts itself for ever: every sweep reports that a place is being
         * watched, that report starts the service, and starting it runs another sweep.
         */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * What the running service was started to do, as a small bitmask.
         *
         * Compared rather than [running] alone, so that a permission granted while the service is
         * already up reaches `onStartCommand` — which is the only place that registers for
         * location updates and decides what kind of foreground service this is.
         */
        @Volatile
        var startedWith: Int = 0
            private set

        /**
         * The radius of the smallest area being watched, as the last sweep saw it.
         *
         * Static for the reason [startedWith] is: a service restarted for memory comes back with a
         * null intent, so there is nowhere to have put it. Lost with the process, which leaves the
         * ordinary cadence until the next sweep says otherwise — slower than asked for, never
         * faster, and corrected within one sweep.
         */
        @Volatile
        var tightestMeters: Double? = null

        /** How far the device was from the nearest watched edge, as the last sweep saw it. */
        @Volatile
        var nearestMeters: Double? = null

        /** What the user asked for. Lost with the process, and re-told the moment the app starts. */
        @Volatile
        var checkRate: LocationCheckRate = LocationCheckRate.Automatic

        /** Says the service is on its way out, before it has got there. See [updatePlaceWatch]. */
        internal fun forget() {
            startedWith = 0
        }

        const val CHANNEL = "zheduler.places"
        const val NOTIFICATION_ID = 0x21E0

        /**
         * How far the device must move before a fix is worth delivering, for a fence of ordinary
         * size.
         *
         * Both bounds have to be satisfied, so this one can silence the other: asking every few
         * seconds while still demanding ten metres of movement delivers nothing at all. Which is
         * why it moves with the tightest fence — see [displacementFor].
         */
        const val MIN_DISTANCE_M = 10f

        /**
         * The unit the cadence is counted in when deciding whether a watch has really changed.
         *
         * Not a rate anything is asked at — what the fixes are asked at is the user's setting, see
         * `LocationCheckRate`. This is only the base of the doubling buckets in [cadenceBucket].
         */
        const val FAST_INTERVAL_MS = 5_000L

        /**
         * How much of the smallest radius the device may cross between fixes.
         *
         * A third, so a fence is not walked through between two updates. Both bounds move together
         * — asking often but only after ten metres of movement is the same silence, since it is
         * the displacement filter that suppresses the delivery.
         */
        const val TIGHT_FRACTION = 1.0 / 3.0

        /**
         * How far a fix is worth reporting at all, however tight the fence.
         *
         * Zero would deliver the noise of a phone sitting on a table as movement, and every one of
         * those is a sweep.
         */
        const val MIN_TIGHT_DISTANCE_M = 2f

        /**
         * How often to ask, which is the user's setting applied to what the last look found.
         *
         * The distance does the work on [LocationCheckRate.Automatic] and is ignored on the rest —
         * someone who has asked for every fifteen minutes has said what they want.
         */
        fun intervalFor(rate: LocationCheckRate, nearestMeters: Double?): Long =
            rate.intervalFor(nearestMeters).inWholeMilliseconds

        /**
         * The interval as a handful of buckets, for deciding whether the watch has really changed.
         *
         * On [LocationCheckRate.Automatic] the interval moves with every step the user takes, and
         * compared exactly it would restart the foreground service on every sweep. Doubling
         * buckets: the watch is re-registered when the rate has changed by a factor of two, which
         * is the point at which it is worth paying for.
         */
        fun cadenceBucket(intervalMs: Long): Int {
            var bucket = 0
            var value = intervalMs / FAST_INTERVAL_MS
            while (value > 1) {
                value /= 2
                bucket++
            }
            return bucket
        }

        /** How far the device must move before a fix is delivered, given the smallest fence. */
        fun displacementFor(tightestMeters: Double?): Float {
            val radius = tightestMeters?.takeIf { it.isFinite() && it > 0 } ?: return MIN_DISTANCE_M
            val scaled = (radius * TIGHT_FRACTION).toFloat()
            return scaled.coerceIn(MIN_TIGHT_DISTANCE_M, MIN_DISTANCE_M)
        }


    }
}

actual fun updateLocationCheckRate(rate: LocationCheckRate) {
    if (LocationWatchService.checkRate == rate) return
    LocationWatchService.checkRate = rate
    // The watch is registered with the old rate and will not notice on its own: what identifies a
    // running watch includes its cadence, so clearing that is what makes the next sweep re-register.
    LocationWatchService.forget()
}

/**
 * Starts or stops the watch.
 *
 * Started only once the permission is held: from Android 10 a location foreground service without
 * it is refused at the moment it tries to start, which crashes the process. There is nothing lost
 * by waiting — without the permission the service would have nothing to listen to — and the sweeps
 * still catch what they can as soon as it is granted.
 */
actual fun updatePlaceWatch(needs: ScheduledEventEngine.WatchNeeds) {
    val context = androidApplication()
    val intent = Intent(context, LocationWatchService::class.java)
    // Only what this device can honour. A place needs the location permission and a signal needs
    // whichever radio it is about; a watch running for something it will never be told about is a
    // permanent notification and nothing else.
    // Each kind against the permission that actually answers for it. Reading which wifi network
    // is joined needs the location permission; bluetooth needs its own. Lumped together, a phone
    // holding only one of them keeps a permanent notification for a watch that can never fire.
    val wanted = (needs.places && context.hasLocationPermission()) ||
        (SignalKind.Wifi in needs.signals && context.hasWifiNamePermission()) ||
        (SignalKind.Bluetooth in needs.signals && context.hasBluetoothPermission())
    // Compared on the *capabilities* as well as the answer: granting location to a service already
    // running for bluetooth has to reach onStartCommand, which is the only place that registers
    // for location updates and settles what kind of foreground service this is.
    LocationWatchService.tightestMeters = needs.tightestMeters
    LocationWatchService.nearestMeters = needs.nearestMeters
    // The cadence is part of what identifies a running watch, not just the permissions: saving a
    // fence tighter than any before it has to reach onStartCommand, which is where the location
    // updates are registered. Compared as the cadence rather than as the radius, so redrawing a
    // fence by a metre does not restart the service.
    // Both halves of what the watch was registered with. The interval is bucketed because on the
    // automatic rate it moves with every step the user takes; the filter is not, because it only
    // changes when a fence is drawn or deleted — and it was left out entirely at first, so saving
    // a fence tighter than any before it kept the old filter and the third-of-a-radius promise
    // quietly went unhonoured until something unrelated restarted the service.
    val cadence = LocationWatchService.cadenceBucket(
        LocationWatchService.intervalFor(LocationWatchService.checkRate, needs.nearestMeters)
    )
    val filter = LocationWatchService.displacementFor(needs.tightestMeters).toInt()
    val signature = if (!wanted) 0
    else 1 or (if (context.hasLocationPermission()) 2 else 0) or
        (if (context.hasBluetoothPermission()) 4 else 0) or (cadence shl 3) or (filter shl 8)
    when (
        watchAction(
            wanted = wanted,
            running = LocationWatchService.running,
            signature = signature,
            startedWith = LocationWatchService.startedWith,
        )
    ) {
        WatchAction.LeaveAlone -> return
        WatchAction.Start, WatchAction.Stop -> Unit
    }
    runCatching {
        // From Android 12 a foreground service cannot be started from the background at all, and
        // the refusal is an exception. Most sweeps that turn the watch on are the app's own, made
        // while it is on the screen, where this is allowed; a restart is covered by
        // [startPlaceWatchAfterBoot], and the rest of the time the service is already up.
        if (wanted) {
            context.startForegroundService(intent)
        } else {
            // Forgotten here rather than in onDestroy, which runs on the main thread some moments
            // later: a sweep in that gap deciding the watch is wanted again would compare against
            // a signature the service is about to abandon, skip the start, and leave it stopped.
            LocationWatchService.forget()
            context.stopService(intent)
        }
    }
}

/**
 * Puts the watch back after a restart, from the one moment the system allows it.
 *
 * Starting a foreground service from the background is refused from Android 12 onwards, with an
 * exemption while an app is handling `BOOT_COMPLETED` — so this is that chance, and missing it
 * means no watch until the user next opens the app. Started without first asking whether any rule
 * still needs one: reading the database is not something to do inside a broadcast, and the
 * service's own first sweep stops it again within seconds if nothing is waiting.
 */
internal fun startPlaceWatchAfterBoot(context: Context) {
    // The standing permission, not the ordinary one: from Android 11 a location service started
    // with nothing of the app on the screen is given no location at all without it, so starting
    // one here would be a notification the user pays for and hears nothing from. Bluetooth has no
    // such split — a rule that only watches a device is served by the permission it already has.
    // The standing bluetooth permission is only a real answer from Android 12, where it has to be
    // granted; before that it is implicit and says nothing about whether a watch is wanted, so the
    // location one remains the gate there.
    val bluetoothIsMeaningful = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.hasBluetoothPermission()
    if (!context.hasBackgroundLocationPermission() && !bluetoothIsMeaningful) return
    runCatching {
        context.startForegroundService(Intent(context, LocationWatchService::class.java))
    }
}
