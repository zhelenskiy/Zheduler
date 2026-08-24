@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.store.StoredAccount
import com.zhelenskiy.zheduler.zheduler.sync.ApiError
import com.zhelenskiy.zheduler.zheduler.sync.ApiErrorCode
import com.zhelenskiy.zheduler.zheduler.sync.AuthRequest
import com.zhelenskiy.zheduler.zheduler.sync.ServerInfo
import com.zhelenskiy.zheduler.zheduler.sync.SpacePushRequest
import com.zhelenskiy.zheduler.zheduler.sync.SpaceSnapshot
import com.zhelenskiy.zheduler.zheduler.sync.SyncProtocol
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlin.time.ExperimentalTime

/**
 * The API, one route per thing the app can ask for.
 *
 * Concurrency between devices is expressed with ordinary HTTP preconditions rather than a field in
 * a body: `If-None-Match: *` creates, `If-Match: "<revision>"` replaces, and a request that says
 * neither is refused with 428 instead of being guessed at. That refusal is the point — an
 * unconditional write is exactly the one that silently loses somebody else's work.
 */
fun Route.syncRoutes(service: SyncService) {
    route(SyncProtocol.BASE_PATH) {
        healthRoute()
        authRoutes(service)
        spaceRoutes(service)
    }
}

private fun Route.healthRoute() {
    get("/health") {
        // Deliberately says nothing else: not a version, not a build, not whether anyone has an
        // account here. It exists so the app can tell "wrong address" from "server is down".
        call.respond(ServerInfo(SyncProtocol.SERVICE_NAME, SyncProtocol.API_VERSION))
    }
}

private fun Route.authRoutes(service: SyncService) {
    route("/auth") {
        post("/register") {
            if (!call.withinContentLength(MAX_AUTH_BODY_BYTES)) return@post
            val request = call.receive<AuthRequest>()
            when (val result = service.register(request.username, request.password, call.clientKey())) {
                is ServiceResult.Ok -> call.respondNoStore(HttpStatusCode.Created, result.value)
                is ServiceResult.Failed -> call.respondFailure(result)
            }
        }

        post("/login") {
            if (!call.withinContentLength(MAX_AUTH_BODY_BYTES)) return@post
            val request = call.receive<AuthRequest>()
            when (val result = service.logIn(request.username, request.password, call.clientKey())) {
                is ServiceResult.Ok -> call.respondNoStore(HttpStatusCode.OK, result.value)
                is ServiceResult.Failed -> call.respondFailure(result)
            }
        }

        post("/logout") {
            // Always 204: whether the token was live, expired or never existed, the caller's wish
            // is granted, and saying which it was would confirm a guessed token.
            service.logOut(call.request.header(HttpHeaders.Authorization))
            call.respond(HttpStatusCode.NoContent)
        }

        get("/me") {
            val account = requireAccount(service) ?: return@get
            call.respond(service.accountInfo(account))
        }
    }
}

private fun Route.spaceRoutes(service: SyncService) {
    route("/spaces") {
        get {
            val account = requireAccount(service) ?: return@get
            call.respond(service.listSpaces(account))
        }

        get("/{remoteId}") {
            val account = requireAccount(service) ?: return@get
            val remoteId = call.remoteId() ?: return@get
            val known = call.ifNoneMatchRevision()

            when (val read = service.readSpace(account, remoteId, known)) {
                is ServiceResult.Failed -> call.respondFailure(read)
                is ServiceResult.Ok -> when (val value = read.value) {
                    is SpaceRead.Unchanged -> {
                        call.response.header(HttpHeaders.ETag, entityTag(value.revision))
                        call.respond(HttpStatusCode.NotModified)
                    }

                    is SpaceRead.Current -> {
                        val header = value.space.header
                        call.response.header(HttpHeaders.ETag, entityTag(header.revision))
                        call.respond(
                            SpaceSnapshot(
                                remoteId = header.remoteId,
                                name = header.name,
                                idPrefix = header.idPrefix,
                                revision = header.revision,
                                updatedAtEpochSeconds = header.updatedAt.epochSeconds,
                                payload = value.space.payload,
                            )
                        )
                    }
                }
            }
        }

        put("/{remoteId}") {
            val account = requireAccount(service) ?: return@put
            val remoteId = call.remoteId() ?: return@put
            val precondition = call.writePrecondition() ?: return@put
            if (!call.withinContentLength(MAX_PUSH_BODY_BYTES)) return@put
            val request = call.receive<SpacePushRequest>()

            val result = when (precondition) {
                is WritePrecondition.MustNotExist -> service.createSpace(account, remoteId, request)
                is WritePrecondition.AtRevision ->
                    service.updateSpace(account, remoteId, precondition.revision, request)
            }
            when (result) {
                is ServiceResult.Failed -> call.respondFailure(result)
                is ServiceResult.Ok -> {
                    call.response.header(HttpHeaders.ETag, entityTag(result.value.revision))
                    val status = if (precondition is WritePrecondition.MustNotExist) {
                        HttpStatusCode.Created
                    } else {
                        HttpStatusCode.OK
                    }
                    call.respond(status, result.value)
                }
            }
        }

        delete("/{remoteId}") {
            val account = requireAccount(service) ?: return@delete
            val remoteId = call.remoteId() ?: return@delete
            val precondition = call.writePrecondition() ?: return@delete
            if (precondition !is WritePrecondition.AtRevision) {
                call.respondFailure(
                    failure(
                        PreconditionRequiredStatus,
                        ApiErrorCode.PreconditionRequired,
                        "Send If-Match with the revision being deleted.",
                    )
                )
                return@delete
            }
            when (val result = service.deleteSpace(account, remoteId, precondition.revision)) {
                is ServiceResult.Ok -> call.respond(HttpStatusCode.NoContent)
                is ServiceResult.Failed -> call.respondFailure(result)
            }
        }
    }
}

/** What a write is allowed to do, as its preconditions say. */
private sealed interface WritePrecondition {
    data object MustNotExist : WritePrecondition
    data class AtRevision(val revision: Long) : WritePrecondition
}

/**
 * The account behind the request, or null after the refusal has already been sent.
 *
 * Written this way so a route reads `?: return@get` and cannot continue past a failed check by
 * forgetting to look at a boolean.
 */
private suspend fun RoutingContext.requireAccount(service: SyncService): StoredAccount? =
    when (val result = service.authenticate(call.request.header(HttpHeaders.Authorization))) {
        is ServiceResult.Ok -> result.value
        is ServiceResult.Failed -> {
            call.respondFailure(result)
            null
        }
    }

/**
 * Whether the declared length is acceptable, having already refused the request if it is not.
 *
 * A missing `Content-Length` is refused too. Without one there is no bound to check until the body
 * has been read, and reading an unbounded body to discover it was too large is the denial of
 * service the limit exists to prevent.
 */
private suspend fun ApplicationCall.withinContentLength(limit: Long): Boolean {
    val declared = request.contentLength()
    if (declared == null) {
        respondFailure(
            failure(
                HttpStatusCode.LengthRequired,
                ApiErrorCode.InvalidRequest,
                "Send a Content-Length with the request.",
            )
        )
        return false
    }
    if (declared > limit) {
        respondFailure(
            failure(
                ContentTooLargeStatus,
                ApiErrorCode.PayloadTooLarge,
                "That request is larger than this server accepts.",
            )
        )
        return false
    }
    return true
}

private suspend fun ApplicationCall.remoteId(): String? {
    val raw = parameters["remoteId"]
    if (raw.isNullOrEmpty()) {
        respondFailure(badRequest("Name the space to work with."))
        return null
    }
    return raw
}

/** The revision in `If-None-Match`, or null when there is none this server can act on. */
private fun ApplicationCall.ifNoneMatchRevision(): Long? =
    request.header(HttpHeaders.IfNoneMatch)?.let(::revisionOfEntityTag)

private suspend fun ApplicationCall.writePrecondition(): WritePrecondition? {
    val ifMatch = request.header(HttpHeaders.IfMatch)?.trim()
    val ifNoneMatch = request.header(HttpHeaders.IfNoneMatch)?.trim()

    if (ifMatch != null && ifNoneMatch != null) {
        respondFailure(badRequest("Send either If-Match or If-None-Match, not both."))
        return null
    }
    if (ifNoneMatch != null) {
        if (ifNoneMatch != "*") {
            respondFailure(badRequest("If-None-Match on a write must be \"*\"."))
            return null
        }
        return WritePrecondition.MustNotExist
    }
    if (ifMatch != null) {
        val revision = revisionOfEntityTag(ifMatch)
            ?: run {
                respondFailure(badRequest("If-Match must carry a revision this server issued."))
                return null
            }
        return WritePrecondition.AtRevision(revision)
    }
    respondFailure(
        failure(
            PreconditionRequiredStatus,
            ApiErrorCode.PreconditionRequired,
            "Send If-Match with the revision being replaced, or If-None-Match: * to create.",
        )
    )
    return null
}

/**
 * The key the rate limiter counts against: the caller's address.
 *
 * `origin.remoteHost` is the socket's peer unless the ForwardedHeaders plugin has been installed,
 * which only happens when the deployer has said the proxy in front can be trusted. Believing the
 * header by default would let one caller mint a fresh identity per request and walk straight
 * through the limiter.
 */
private fun ApplicationCall.clientKey(): String = rateLimitKeyFor(request.origin.remoteHost)

/**
 * The address a limiter counts against, with an IPv6 client collapsed to its /64.
 *
 * One IPv6 subscriber is routinely handed a /64 — eighteen quintillion addresses — so counting the
 * full address means every request can arrive from a genuinely different one and no per-address
 * limit ever fires. The /64 is the smallest block that is allocated as a unit, so it is the
 * smallest thing that identifies a caller rather than a caller's choice. IPv4 is left whole.
 */
internal fun rateLimitKeyFor(remoteHost: String): String {
    val host = hostWithoutPort(remoteHost.trim().lowercase())
    // A colon is what tells the two families apart; an IPv4 address never contains one.
    if (':' !in host) return host

    // An IPv6 address ending in a dotted quad is an IPv4 caller wearing an IPv6 coat. The address
    // that identifies them is the IPv4 one; collapsing these to a shared /64 would put every
    // IPv4 caller behind such a stack into one bucket, where one abuser limits them all out.
    host.substringAfterLast(':').takeIf { '.' in it }?.let { return it }

    // Expanded before the prefix is taken. `2001:db8:abcd::1` and `2001:db8:abcd::2` are the same
    // /64 written with the zero run starting inside the prefix; reading the groups as written
    // would give them separate keys, and a subscriber whose fourth group is zero — the first
    // subnet of every allocation — would have an unlimited supply of them.
    val expanded = expandIpv6(host) ?: return host
    return expanded.take(IPV6_PREFIX_GROUPS).joinToString(":") + "::/64"
}

/**
 * The address with any port taken off, and an IPv6 literal unbracketed.
 *
 * A port belongs to one connection, not to a caller: `Forwarded: for="1.2.3.4:4711"` and the
 * IIS-style headers that carry one would otherwise give every TCP connection its own key, and the
 * limiter would never count two requests as the same caller.
 */
private fun hostWithoutPort(remoteHost: String): String {
    if (remoteHost.startsWith("[")) {
        val close = remoteHost.indexOf(']')
        return if (close < 0) remoteHost else remoteHost.substring(1, close)
    }
    val colon = remoteHost.indexOf(':')
    if (colon < 0) return remoteHost
    // One colon only: an IPv6 address has several, so a single one is a port separator.
    if (remoteHost.indexOf(':', colon + 1) < 0) return remoteHost.substring(0, colon)
    return remoteHost
}

/** The eight groups of an IPv6 address, zeros filled in and written the one way. */
private fun expandIpv6(host: String): List<String>? {
    val elision = host.indexOf("::")
    val groups = if (elision < 0) {
        host.split(':')
    } else {
        val head = host.substring(0, elision).split(':').filter { it.isNotEmpty() }
        val tail = host.substring(elision + 2).split(':').filter { it.isNotEmpty() }
        val zeros = IPV6_GROUPS - head.size - tail.size
        if (zeros < 0) return null
        head + List(zeros) { "0" } + tail
    }
    if (groups.size != IPV6_GROUPS) return null
    // A leading zero is not part of the identity: `0db8` and `db8` are the same group.
    return groups.map { group -> group.trimStart('0').ifEmpty { "0" } }
}

private const val IPV6_GROUPS = 8

/** How many of those groups are the routed prefix rather than the caller's choice. */
private const val IPV6_PREFIX_GROUPS = 4

/** Answers with something that must never be written to a cache — a token, or a refusal. */
private suspend inline fun <reified T : Any> ApplicationCall.respondNoStore(
    status: HttpStatusCode,
    body: T,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(status, body)
}

internal suspend fun ApplicationCall.respondFailure(failure: ServiceResult.Failed) {
    failure.retryAfter?.let { wait ->
        response.header(HttpHeaders.RetryAfter, wait.inWholeSeconds.coerceAtLeast(1).toString())
    }
    respondNoStore(failure.status, failure.error)
}

private fun failure(status: HttpStatusCode, code: ApiErrorCode, message: String) =
    ServiceResult.Failed(status, ApiError(code, message))

private fun badRequest(message: String) =
    failure(HttpStatusCode.BadRequest, ApiErrorCode.InvalidRequest, message)

private fun entityTag(revision: Long): String = "\"$revision\""

/**
 * The revision inside an entity tag, or null if it is not one of ours.
 *
 * Weak tags (`W/"3"`) are accepted on the way in because a proxy may weaken one, and a revision
 * compared for equality does not care about the distinction.
 */
private fun revisionOfEntityTag(header: String): Long? {
    val tag = header.trim().removePrefix("W/").trim()
    if (tag.length < 2 || !tag.startsWith('"') || !tag.endsWith('"')) return null
    return tag.substring(1, tag.length - 1).toLongOrNull()
}

private const val MAX_AUTH_BODY_BYTES = 4L * 1024

/** The payload cap plus room for the JSON around it and for escaping inside it. */
private const val MAX_PUSH_BODY_BYTES = SyncProtocol.MAX_PAYLOAD_BYTES * 2L
