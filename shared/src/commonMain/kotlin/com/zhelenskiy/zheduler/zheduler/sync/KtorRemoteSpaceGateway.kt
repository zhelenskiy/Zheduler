package com.zhelenskiy.zheduler.zheduler.sync

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * The sync gateway as it is actually spoken: HTTPS with a bearer token and ETags.
 *
 * Conditional requests are the whole efficiency story. A download carries `If-None-Match` and
 * comes back empty when nothing changed; an upload carries `If-Match` and is refused rather than
 * clobbering a newer copy. Both are ordinary HTTP, so the rules hold through any proxy in between.
 */
class KtorRemoteSpaceGateway(
    private val client: HttpClient,
) : RemoteSpaceGateway {

    override suspend fun serverInfo(address: ServerAddress): Outcome<ServerInfo> =
        call {
            val response = client.get("${address.apiBase}/health")
            response.decodeOr { info: ServerInfo ->
                if (info.service != SyncProtocol.SERVICE_NAME) {
                    Outcome.Failure(
                        RemoteError.Malformed("that address answers, but it is not a Zheduler server")
                    )
                } else if (info.apiVersion != SyncProtocol.API_VERSION) {
                    Outcome.Failure(
                        RemoteError.Malformed(
                            "that server speaks version ${info.apiVersion}, this app speaks " +
                                "${SyncProtocol.API_VERSION}"
                        )
                    )
                } else {
                    Outcome.Success(info)
                }
            }
        }

    override suspend fun register(
        address: ServerAddress,
        username: String,
        password: String,
    ): Outcome<AuthResponse> = authenticate(address, "register", username, password)

    override suspend fun logIn(
        address: ServerAddress,
        username: String,
        password: String,
    ): Outcome<AuthResponse> = authenticate(address, "login", username, password)

    private suspend fun authenticate(
        address: ServerAddress,
        endpoint: String,
        username: String,
        password: String,
    ): Outcome<AuthResponse> = call {
        val response = client.post("${address.apiBase}/auth/$endpoint") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(username, password))
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            // Everywhere else a 401 means the session ended and the remedy is to sign in. Here the
            // caller *is* signing in, and answering "sign in" would be a loop with no exit; what
            // they need told is that what they typed was wrong.
            val api = apiErrorBody(response)
            return@call Outcome.Failure(
                RemoteError.Rejected(
                    api?.code ?: ApiErrorCode.InvalidCredentials,
                    api?.message ?: "That username and password do not match.",
                )
            )
        }
        response.decodeOr { auth: AuthResponse -> Outcome.Success(auth) }
    }

    override suspend fun logOut(address: ServerAddress, token: AuthToken): Outcome<Unit> = call {
        val response = client.post("${address.apiBase}/auth/logout") { bearer(token) }
        // A token the server has already forgotten is the state the caller wanted anyway; only a
        // server that is unwell should keep the user staring at a sign-out that will not finish.
        if (response.status == HttpStatusCode.Unauthorized) {
            Outcome.Success(Unit)
        } else {
            response.expectNoContent()
        }
    }

    override suspend fun account(address: ServerAddress, token: AuthToken): Outcome<AccountInfo> =
        call {
            val response = client.get("${address.apiBase}/auth/me") { bearer(token) }
            response.decodeOr { info: AccountInfo -> Outcome.Success(info) }
        }

    override suspend fun listSpaces(
        address: ServerAddress,
        token: AuthToken,
    ): Outcome<List<SpaceSummary>> = call {
        val response = client.get("${address.apiBase}/spaces") { bearer(token) }
        response.decodeOr { spaces: List<SpaceSummary> -> Outcome.Success(spaces) }
    }

    override suspend fun fetchSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        knownRevision: Long?,
    ): Outcome<FetchedSpace> = call {
        val response = client.get("${address.apiBase}/spaces/${remoteId.pathSegment()}") {
            bearer(token)
            if (knownRevision != null) header(HttpHeaders.IfNoneMatch, entityTag(knownRevision))
        }
        if (response.status == HttpStatusCode.NotModified) {
            // Only reachable when a revision was offered, so there is always one to report back.
            val held = knownRevision
                ?: return@call Outcome.Failure(
                    RemoteError.Malformed("the server said nothing had changed, but nothing was asked about")
                )
            Outcome.Success(FetchedSpace.Unchanged(held))
        } else {
            response.decodeOr { snapshot: SpaceSnapshot -> Outcome.Success(FetchedSpace.Fresh(snapshot)) }
        }
    }

    override suspend fun createSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        request: SpacePushRequest,
    ): Outcome<SpacePushResponse> = push(address, token, remoteId, request) {
        // "*" matches any existing representation, so this succeeds only when there is none.
        header(HttpHeaders.IfNoneMatch, "*")
    }

    override suspend fun updateSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        expectedRevision: Long,
        request: SpacePushRequest,
    ): Outcome<SpacePushResponse> = push(address, token, remoteId, request) {
        header(HttpHeaders.IfMatch, entityTag(expectedRevision))
    }

    private suspend fun push(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        request: SpacePushRequest,
        precondition: HttpRequestBuilder.() -> Unit,
    ): Outcome<SpacePushResponse> {
        oversizeError(request.payload)?.let { return Outcome.Failure(it) }
        return call {
            val response = client.put("${address.apiBase}/spaces/${remoteId.pathSegment()}") {
                bearer(token)
                precondition()
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            response.decodeOr { pushed: SpacePushResponse -> Outcome.Success(pushed) }
        }
    }

    override suspend fun deleteSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        expectedRevision: Long,
    ): Outcome<Unit> = call {
        val response = client.delete("${address.apiBase}/spaces/${remoteId.pathSegment()}") {
            bearer(token)
            header(HttpHeaders.IfMatch, entityTag(expectedRevision))
        }
        response.expectNoContent()
    }

    /**
     * Runs one request, turning anything thrown on the way into a [RemoteError].
     *
     * Cancellation is re-thrown rather than reported: a screen the user left is not a failure to
     * show them, and swallowing it here would leave the coroutine that was cancelled still running.
     */
    private suspend inline fun <T> call(block: () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Outcome.Failure(transportError(failure))
    }

    private fun transportError(failure: Throwable): RemoteError = when (failure) {
        is HttpRequestTimeoutException -> RemoteError.TimedOut
        else -> {
            val name = failure::class.simpleName.orEmpty()
            if (name.contains("Timeout")) {
                RemoteError.TimedOut
            } else {
                RemoteError.Unreachable(failure.message?.takeIf { it.isNotBlank() } ?: name.ifEmpty { null })
            }
        }
    }

    /**
     * Turns a response into either the decoded body or the error the status means.
     *
     * The success path is a lambda rather than a plain return so that a body which parses but does
     * not make sense — a health check from something that is not this server — can still be
     * refused without a second round of matching at every call site.
     */
    private suspend inline fun <reified T, R> HttpResponse.decodeOr(
        onSuccess: (T) -> Outcome<R>,
    ): Outcome<R> {
        if (!status.isSuccessForThisApi()) return Outcome.Failure(errorFor(this))
        val decoded = try {
            body<T>()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return Outcome.Failure(RemoteError.Malformed(failure.message))
        }
        return onSuccess(decoded)
    }

    private suspend fun HttpResponse.expectNoContent(): Outcome<Unit> =
        if (status.isSuccessForThisApi()) Outcome.Success(Unit) else Outcome.Failure(errorFor(this))

    private companion object {

        private fun HttpStatusCode.isSuccessForThisApi(): Boolean = value in 200..299

        private fun HttpRequestBuilder.bearer(token: AuthToken) {
            header(HttpHeaders.Authorization, "Bearer ${token.value}")
        }

        private fun entityTag(revision: Long): String = "\"$revision\""

        /**
         * A space id, escaped for the one path position it appears in.
         *
         * Ids are generated locally and are well-behaved today, but a `/` or a `?` slipping in
         * would silently retarget the request at a different endpoint.
         */
        private fun String.pathSegment(): String = buildString(length) {
            for (byte in this@pathSegment.encodeToByteArray()) {
                val char = byte.toInt().toChar()
                if (char.isLetterOrDigit() && char.code < 128 || char in "-._~") {
                    append(char)
                } else {
                    append('%')
                    append(HEX[(byte.toInt() shr 4) and 0xF])
                    append(HEX[byte.toInt() and 0xF])
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"

        /** 413, named here because Ktor renamed the constant between versions. */
        private const val CONTENT_TOO_LARGE = 413

        /**
         * Refuses a payload the server would refuse anyway, without sending it first.
         *
         * The exact byte length is only computed when the cheap bound cannot settle it: UTF-8
         * never spends more than three bytes on one UTF-16 unit, so anything under a third of the
         * limit is certainly small enough.
         */
        private fun oversizeError(payload: String): RemoteError? {
            val limit = SyncProtocol.MAX_PAYLOAD_BYTES
            if (payload.length <= limit / 3) return null
            val bytes = payload.encodeToByteArray().size
            if (bytes <= limit) return null
            return RemoteError.Rejected(
                ApiErrorCode.PayloadTooLarge,
                "This space is ${bytes / (1024 * 1024)} MB, larger than the ${limit / (1024 * 1024)} MB " +
                    "the server accepts.",
            )
        }

        /** The refusal the server described, or null when it did not describe one. */
        suspend fun apiErrorBody(response: HttpResponse): ApiError? {
            val body = runCatching { response.bodyAsText() }.getOrNull() ?: return null
            return runCatching { errorJson.decodeFromString<ApiError>(body) }.getOrNull()
        }

        private suspend fun errorFor(response: HttpResponse): RemoteError {
            val api = apiErrorBody(response)
            return when (response.status.value) {
                HttpStatusCode.Unauthorized.value ->
                    RemoteError.AuthenticationRequired(api?.message)

                HttpStatusCode.Forbidden.value -> RemoteError.NotAllowed(api?.message)

                HttpStatusCode.NotFound.value -> RemoteError.NotFound

                HttpStatusCode.PreconditionFailed.value ->
                    RemoteError.Conflict(api?.currentRevision)

                HttpStatusCode.TooManyRequests.value ->
                    RemoteError.RateLimited(
                        response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds
                    )

                CONTENT_TOO_LARGE -> RemoteError.Rejected(
                    ApiErrorCode.PayloadTooLarge,
                    api?.message ?: "That space is larger than the server accepts.",
                )

                in 500..599 -> RemoteError.ServerFault(response.status.value, api?.message)

                in 400..499 -> RemoteError.Rejected(
                    api?.code ?: ApiErrorCode.InvalidRequest,
                    api?.message ?: "The server refused the request (HTTP ${response.status.value}).",
                )

                // A 1xx or a 3xx reaching here is a redirect this client does not follow, or
                // something that is not the sync API at all.
                else -> RemoteError.Malformed("unexpected HTTP ${response.status.value}")
            }
        }

        private val errorJson = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The client configuration the gateway assumes, applied to whatever engine the platform has.
 *
 * Redirects are not followed on purpose. Ktor would replay the request — bearer token included —
 * at whatever `Location` said, so a server that has been taken over, or merely misconfigured,
 * could hand this app's token to another host. A sync server has no reason to redirect.
 */
fun HttpClientConfig<*>.installSyncClientDefaults() {
    followRedirects = false
    expectSuccess = false
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 30.seconds.inWholeMilliseconds
        connectTimeoutMillis = 15.seconds.inWholeMilliseconds
        socketTimeoutMillis = 30.seconds.inWholeMilliseconds
    }
}
