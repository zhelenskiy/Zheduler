@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.store.InMemorySyncStore
import com.zhelenskiy.zheduler.zheduler.store.SyncStore
import com.zhelenskiy.zheduler.zheduler.store.postgresSyncStore
import com.zhelenskiy.zheduler.zheduler.sync.ApiError
import com.zhelenskiy.zheduler.zheduler.sync.ApiErrorCode
import com.zhelenskiy.zheduler.zheduler.sync.SyncProtocol
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.condition
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

fun main() {
    val config = ServerConfig.fromEnvironment(System::getenv).getOrElse { failure ->
        System.err.println("Zheduler sync server cannot start: ${failure.message}")
        return
    }

    val store = config.storage.open()
    if (config.storage is StorageConfig.InMemory) {
        System.err.println(
            "Zheduler sync server: ZHEDULER_STORAGE=memory — every account and space is lost on exit."
        )
    }

    embeddedServer(Netty, port = config.port, host = config.host) {
        syncModule(config, store)
    }.start(wait = true)
}

/** Below this a gzip header costs more than it saves. */
private const val MIN_COMPRESSED_BYTES = 1024L

internal fun StorageConfig.open(): SyncStore = when (this) {
    is StorageConfig.InMemory -> InMemorySyncStore()
    is StorageConfig.Postgres -> postgresSyncStore(this)
}

/**
 * The whole server: plugins, then routes.
 *
 * Takes its store rather than making one, so the test host can drive exactly this module against
 * an in-memory store and a clock it controls, instead of a near-copy that drifts from it.
 */
fun Application.syncModule(
    config: ServerConfig,
    store: SyncStore,
    clock: Clock = Clock.System,
) {
    val service = SyncService(
        store = store,
        clock = clock,
        tokenLifetime = config.tokenLifetime,
        limits = RateLimits.standard(),
    )

    if (config.trustForwardedHeaders) {
        // Only ever installed on the deployer's word that there is a proxy in front. And then the
        // *last* entry, not the default first one: an ordinary proxy appends the peer it saw to
        // whatever `X-Forwarded-For` already said, so the first entry is a string the client chose
        // and trusting it would let one caller mint a fresh rate-limit identity per request —
        // undoing the limiter entirely. The last entry is the one the trusted proxy wrote itself.
        install(XForwardedHeaders) { useLastProxy() }
    }

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
        // This API answers only JSON to a native client; nothing it returns should ever be run
        // as a page if a browser is talked into rendering it.
        header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        header(HttpHeaders.Server, "zheduler")
        if (config.strictTransportSecurity) {
            header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
    }

    if (config.allowedOrigins.isNotEmpty()) {
        install(CORS) {
            config.allowedOrigins.forEach { origin -> allowHost(origin, schemes = listOf("https")) }
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.IfMatch)
            allowHeader(HttpHeaders.IfNoneMatch)
            // Without this a browser cannot read the ETag, and the client would push blind.
            exposeHeader(HttpHeaders.ETag)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
        }
    }

    install(ContentNegotiation) {
        // Unknown keys are ignored so a newer client can add a field without every older server
        // rejecting its requests outright.
        json(Json { ignoreUnknownKeys = true })
    }

    install(Compression) {
        gzip {
            minimumSize(MIN_COMPRESSED_BYTES)
            // Space payloads are JSON and compress to a fraction of their size, which is the
            // whole point. Credentials are excluded: a token in a compressed body shares its
            // length with everything else in that body, and that is the shape BREACH exploits.
            condition { _ -> request.uri.startsWith("${SyncProtocol.BASE_PATH}/spaces") }
        }
    }

    install(CallLogging) {
        level = Level.INFO
        // The path is logged, never the query or the headers: an Authorization header in a log
        // file is a credential in a log file.
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()}" }
    }

    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError(ApiErrorCode.InvalidRequest, "The request could not be read."),
            )
        }
        exception<Throwable> { call, cause ->
            // The reference is the only thing the caller gets: a stack trace tells whoever
            // triggered it which library versions are installed and where the code branches.
            val reference = UUID.randomUUID().toString()
            call.application.log.error("Unhandled failure [$reference]", cause)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(ApiErrorCode.Internal, "Something went wrong on the server (reference $reference)."),
            )
        }
    }

    routing {
        syncRoutes(service)
    }

    launch(Dispatchers.Default) {
        // Expired tokens are already unusable — the lookup filters on expiry — so this is
        // housekeeping, not enforcement, and an hour's granularity is plenty.
        while (isActive) {
            delay(1.hours)
            runCatching { service.purgeExpiredTokens() }
                .onFailure { failure -> log.warn("Could not purge expired tokens", failure) }
        }
    }
}
