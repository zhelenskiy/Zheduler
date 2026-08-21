package com.zhelenskiy.zheduler.zheduler.geo

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The two OpenStreetMap services this app talks to, and the one client it talks to them with.
 *
 * Both are plain HTTPS and neither needs a key or an account, which is the whole reason a map can
 * be drawn here without taking on a native SDK: the tiles are PNGs and the search is JSON.
 *
 * Both also come with a usage policy that is a condition of using them rather than advice — an
 * app that identifies itself, does not hammer them, and does not bulk-download. [USER_AGENT] is
 * the identification and [NominatimSearch] holds itself to one request a second. Browsers refuse
 * to let a page set `User-Agent` at all, so there the browser's own is what arrives; that is the
 * ordinary case for a web app and is what the policy expects of one.
 */
internal object OpenStreetMap {

    const val USER_AGENT = "Zheduler/1.0 (task manager; https://github.com/zhelenskiy/Zheduler)"

    const val TILE_URL = "https://tile.openstreetmap.org"

    const val NOMINATIM_URL = "https://nominatim.openstreetmap.org"

    /** What has to be shown on any map drawn from these tiles. */
    const val ATTRIBUTION = "© OpenStreetMap contributors"

    /**
     * One client for the whole app, made on first use.
     *
     * Never closed: it lives as long as the process, and closing it would only matter if something
     * else were going to make another.
     */
    val client: HttpClient by lazy { HttpClient() }

    val json: Json = Json { ignoreUnknownKeys = true }
}

/**
 * The map's tiles, fetched as PNGs.
 *
 * Returns bytes rather than an image: decoding belongs to whatever is going to draw it, and
 * keeping this side free of Compose is what lets the tile arithmetic and the fetching be tested
 * apart from a screen.
 */
class OsmTileSource(private val client: HttpClient = OpenStreetMap.client) {

    /**
     * The tile's bytes, or `null` where there is no such tile or it could not be had.
     *
     * A tile that comes back as anything other than an image is null too — the servers answer a
     * request for a row that does not exist with an HTML error page, and handing that to an image
     * decoder is how a map turns into a crash.
     */
    suspend fun bytes(key: TileKey): ByteArray? {
        if (!key.isReal) return null
        return try {
            val response = client.get("${OpenStreetMap.TILE_URL}/${key.zoom}/${key.x}/${key.y}.png") {
                header("User-Agent", OpenStreetMap.USER_AGENT)
            }
            if (!response.status.isSuccess()) return null
            response.bodyAsBytes().takeIf { it.isPng() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    /** The eight bytes every PNG begins with. */
    private fun ByteArray.isPng(): Boolean =
        size > PNG_MAGIC.size && PNG_MAGIC.indices.all { this[it] == PNG_MAGIC[it] }

    private companion object {
        val PNG_MAGIC = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}

/** A place the search found. */
data class PlaceResult(
    /** The short name, where the service gave one — "Eiffel Tower" rather than the whole address. */
    val name: String,
    /** The whole address, for telling two results with the same name apart. */
    val address: String,
    val point: GeoPoint,
)

/** Somewhere to look up a written address, so a place can be found without knowing its numbers. */
fun interface PlaceSearch {
    /** At most [limit] matches for [query], or an empty list if there are none or it failed. */
    suspend fun search(query: String, limit: Int): List<PlaceResult>
}

/** How many matches are worth offering when nobody has said. */
suspend fun PlaceSearch.search(query: String): List<PlaceResult> = search(query, limit = 8)

/**
 * OpenStreetMap's own geocoder.
 *
 * Held to one request a second, which is what its usage policy asks of an application. The wait is
 * taken here rather than left to the caller, because a screen that searches as the user types has
 * no idea how fast they type; the field debounces as well, so in practice this only bites when
 * someone pastes one query straight after another.
 */
@OptIn(ExperimentalTime::class)
class NominatimSearch(
    private val client: HttpClient = OpenStreetMap.client,
    private val clock: Clock = Clock.System,
) : PlaceSearch {

    private val pace = Mutex()
    private var lastRequest: Instant? = null

    override suspend fun search(query: String, limit: Int): List<PlaceResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val body = request {
            client.get("${OpenStreetMap.NOMINATIM_URL}/search") {
                header("User-Agent", OpenStreetMap.USER_AGENT)
                parameter("q", trimmed)
                parameter("format", "jsonv2")
                parameter("limit", limit.coerceIn(1, 20))
            }
        } ?: return emptyList()
        return decode(body)
    }

    /**
     * What is at [point], as an address.
     *
     * For the other way round the search works: a place picked by tapping the map has coordinates
     * and no name, and a street is a better thing to offer as its name than two decimal numbers.
     */
    suspend fun describe(point: GeoPoint): PlaceResult? {
        val sane = point.sane()
        val body = request {
            client.get("${OpenStreetMap.NOMINATIM_URL}/reverse") {
                header("User-Agent", OpenStreetMap.USER_AGENT)
                parameter("lat", sane.latitude)
                parameter("lon", sane.longitude)
                parameter("format", "jsonv2")
            }
        } ?: return null
        // The reverse service answers with one object, not a list.
        return runCatching { OpenStreetMap.json.decodeFromString<NominatimPlace>(body).toResult() }
            .getOrNull()
    }

    private fun decode(body: String): List<PlaceResult> =
        runCatching { OpenStreetMap.json.decodeFromString<List<NominatimPlace>>(body) }
            .getOrNull()
            .orEmpty()
            .mapNotNull { it.toResult() }
            // One place can come back twice — as the point on the map and as the outline of the
            // building — with the same name, address and coordinates. Two rows the user cannot
            // tell apart is the smaller problem: a list keyed by what is shown crashes on the
            // duplicate rather than drawing it.
            .distinctBy { Triple(it.name, it.address, it.point) }

    /**
     * Runs [call] no sooner than a second after the last one, and turns every failure into null.
     *
     * A search that cannot be made is an empty result, not an error to show: the user is typing,
     * and the network being down is not something to interrupt them about.
     */
    private suspend fun request(call: suspend () -> io.ktor.client.statement.HttpResponse): String? = pace.withLock {
        lastRequest?.let { previous ->
            val since = clock.now() - previous
            if (since < MINIMUM_GAP) delay(MINIMUM_GAP - since)
        }
        lastRequest = clock.now()
        try {
            val response = call()
            // The service answers a refusal in words; decoding it as a place list would silently
            // produce nothing, which reads as "no such place" rather than "we are being throttled".
            if (response.status == HttpStatusCode.TooManyRequests) return@withLock null
            if (!response.status.isSuccess()) return@withLock null
            response.bodyAsText()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        val MINIMUM_GAP = 1.seconds
    }
}

/**
 * One entry of the geocoder's answer.
 *
 * The coordinates come as strings, which is the service's own format and not a mistake to correct
 * on the way in — an entry whose numbers will not parse is dropped rather than defaulted, because
 * a place at latitude zero is a place in the Atlantic.
 */
@Serializable
private data class NominatimPlace(
    val lat: String = "",
    val lon: String = "",
    val name: String? = null,
    @SerialName("display_name") val displayName: String = "",
) {
    fun toResult(): PlaceResult? {
        val latitude = lat.toDoubleOrNull() ?: return null
        val longitude = lon.toDoubleOrNull() ?: return null
        val short = name?.takeIf { it.isNotBlank() }
            ?: displayName.substringBefore(',').takeIf { it.isNotBlank() }
            ?: return null
        return PlaceResult(
            name = short,
            address = displayName,
            point = GeoPoint(latitude = latitude, longitude = longitude),
        )
    }
}
