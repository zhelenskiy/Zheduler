package com.zhelenskiy.zheduler.zheduler.components.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import com.zhelenskiy.zheduler.zheduler.geo.OsmTileSource
import com.zhelenskiy.zheduler.zheduler.geo.TileKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The tiles that have arrived, and the fetching of the ones that have not.
 *
 * Held apart from the map so that panning is a matter of reading a map of images rather than of
 * starting work: a drag crosses a tile boundary many times a second, and each crossing asks for
 * tiles that are usually already here.
 *
 * A tile is asked for once, and a failure is remembered too — a request that comes back empty
 * would otherwise be made again on every frame for as long as it stayed on screen, which is the
 * shape of accidentally hammering a public service. But only remembered for a while: a tile that
 * failed because the network was down for a moment must not be a grey square for as long as the
 * screen is open, with nothing that would ever try it again.
 */
class TileCache(
    private val scope: CoroutineScope,
    private val source: OsmTileSource = OsmTileSource(),
) {
    /** What can be drawn right now. Read from the draw phase, so it has to be snapshot state. */
    val tiles = mutableStateMapOf<TileKey, ImageBitmap>()

    private val asked = mutableSetOf<TileKey>()

    /** The fetches still in flight, so that panning away can call them off. */
    private val fetching = mutableMapOf<TileKey, Job>()

    /** When each failed request happened, so it can be tried once more but not at once. */
    private val failed = mutableMapOf<TileKey, TimeMark>()

    /**
     * How many tiles are waiting out their retry, as something the screen can watch.
     *
     * Snapshot state, because a retry has to be *driven*: [request] runs from the draw phase, and a
     * failure touches nothing the draw depends on, so once every visible tile had arrived or failed
     * nothing would ever draw again and the retry would never come. This is what [rememberTileCache]
     * watches to wake it. See [retryFailed].
     */
    var waitingToRetry: Int by mutableIntStateOf(0)
        private set

    /**
     * Bumped when something has changed that only the draw can act on.
     *
     * Read while drawing, so that clearing the failures actually re-runs it.
     */
    var revision: Int by mutableIntStateOf(0)
        private set

    /**
     * At most this many fetches at once, across every map in the app.
     *
     * A viewport can want two dozen tiles the moment it opens, and asking a public tile server for
     * all of them at the same time is both rude and slower than asking for a few. Shared rather
     * than one budget per map, because two maps can be on screen at once — the picker with the
     * editor open over it — and the tile server is owed a limit on the app, not on the screen.
     */
    private val inFlight get() = sharedFetchLimit

    /** Starts fetching [key] unless it is here already, or was asked for too recently. */
    fun request(key: TileKey) {
        if (!key.isReal || key in asked) return
        // A tile that failed is left alone until the wait is up, and then treated as new.
        val since = failed[key]
        if (since != null && since.elapsedNow() < RETRY_AFTER) return
        if (failed.remove(key) != null) waitingToRetry = failed.size
        asked += key
        fetching[key] = scope.launch {
            val bytes = inFlight.withPermit { source.bytes(key) }
            // Decoding can fail on bytes that passed for a PNG; a missing tile is a grey square,
            // not a screen that will not draw.
            val image = bytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
            fetching -= key
            if (image == null) {
                asked -= key
                failed[key] = TimeSource.Monotonic.markNow()
                waitingToRetry = failed.size
                return@launch
            }
            tiles[key] = image
        }
    }

    /**
     * Lets every failure that has waited long enough be asked for again.
     *
     * Called by the screen rather than by a timer of this class's own: nothing here is running
     * between draws, and a retry nobody draws is a retry nobody sees.
     */
    fun retryFailed() {
        val expired = failed.filterValues { it.elapsedNow() >= RETRY_AFTER }.keys
        if (expired.isEmpty()) return
        expired.forEach { failed -= it }
        waitingToRetry = failed.size
        revision++
    }

    /**
     * Drops tiles that are no longer on screen, once there are more than worth keeping.
     *
     * Kept generously: a tile just scrolled off is very often about to come back, and an image is
     * a quarter of a megabyte at most. What this prevents is an afternoon of panning ending with
     * every tile of a city in memory.
     *
     * Anything dropped is forgotten as well as freed, so it can be asked for again.
     */
    fun keepOnly(visible: Set<TileKey>) {
        // Called off first, before anything else. A fling across a city queues hundreds of
        // fetches, and the semaphore hands out its four permits in the order they were asked for —
        // so the tiles where the user actually stops sit behind every tile they flew over, grey
        // for as long as it takes to download a map nobody is going to look at.
        if (fetching.isNotEmpty()) {
            fetching.entries.filterNot { (key, _) -> key in visible }.forEach { (key, job) ->
                job.cancel()
                fetching -= key
                asked -= key
            }
        }
        // A tile off screen whose wait is up is a note about a request nobody is going to make
        // again; left alone, panning across a country with no signal fills this with every tile
        // that was asked for on the way.
        if (failed.size > MAX_TILES) {
            failed.keys.filterNot { it in visible }
                .filter { failed.getValue(it).elapsedNow() >= RETRY_AFTER }
                .forEach { failed -= it }
            waitingToRetry = failed.size
        }
        if (tiles.size <= MAX_TILES) return
        val evictable = tiles.keys.filterNot { it in visible }
        evictable.take(tiles.size - MAX_TILES).forEach {
            tiles.remove(it)
            asked.remove(it)
        }
    }

    internal companion object {
        const val MAX_CONCURRENT_FETCHES = 4

        private val sharedFetchLimit = Semaphore(MAX_CONCURRENT_FETCHES)
        const val MAX_TILES = 160

        /**
         * How long a tile that would not come is left alone.
         *
         * Long enough that a dead tile is not asked for on every frame, short enough that a map
         * left open through a lift ride fills itself in rather than staying grey until it is
         * closed and opened again.
         */
        val RETRY_AFTER = 15.seconds
    }
}

@Composable
fun rememberTileCache(): TileCache {
    val scope = rememberCoroutineScope()
    val cache = remember(scope) { TileCache(scope) }
    // The retry's clock. Nothing inside the cache runs between draws, so without this a map whose
    // tiles all failed — the lift, the tunnel, the moment the connection dropped — would stay grey
    // until something else happened to redraw it, whatever the cache's own retry rule said.
    LaunchedEffect(cache, cache.waitingToRetry > 0) {
        while (cache.waitingToRetry > 0) {
            delay(TileCache.RETRY_AFTER)
            cache.retryFailed()
        }
    }
    return cache
}
