package com.zhelenskiy.zheduler.zheduler.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What the gateway makes of every answer a server can give.
 *
 * A mock engine rather than a server: the point here is the mapping from status codes and headers
 * to [Outcome], which is where a 412 quietly becoming "something went wrong" would cost the user
 * their work. The server's half of the same contract is covered by the end-to-end suite.
 */
class KtorRemoteSpaceGatewayTest {

    private val address = (ServerAddress.parse("https://sync.example.com") as Outcome.Success).value
    private val token = AuthToken("a-token")

    private var lastRequest: HttpRequestData? = null

    private fun gateway(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorRemoteSpaceGateway {
        val engine = MockEngine { request ->
            lastRequest = request
            handler(request)
        }
        return KtorRemoteSpaceGateway(HttpClient(engine) { installSyncClientDefaults() })
    }

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun MockRequestHandleScope.jsonWithETag(
        status: HttpStatusCode,
        body: String,
        etag: String,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(
            HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
            HttpHeaders.ETag to listOf(etag),
        ),
    )

    private fun pushRequest(payload: String = "{}") = SpacePushRequest("Work", "WRK", payload)

    // ---------------------------------------------------------------- addressing

    @Test
    fun `requests go to the versioned api path`() = runTest {
        val gateway = gateway { json(HttpStatusCode.OK, """{"service":"zheduler-sync","apiVersion":1}""") }
        gateway.serverInfo(address)
        assertEquals("https://sync.example.com/api/v1/health", lastRequest?.url.toString())
    }

    @Test
    fun `a space id is escaped into its path segment`() = runTest {
        val gateway = gateway { json(HttpStatusCode.NotFound, """{"code":"not_found","message":"no"}""") }
        gateway.fetchSpace(address, token, "a b/c?d")
        val url = lastRequest?.url.toString()
        assertTrue(url.endsWith("/spaces/a%20b%2Fc%3Fd"), url)
    }

    @Test
    fun `the token travels as a bearer credential`() = runTest {
        val gateway = gateway { json(HttpStatusCode.OK, "[]") }
        gateway.listSpaces(address, token)
        assertEquals("Bearer a-token", lastRequest?.headers?.get(HttpHeaders.Authorization))
    }

    @Test
    fun `a token never appears in a token's own text`() {
        // Logging an AuthToken by accident must not print it.
        assertTrue("a-token" !in AuthToken("a-token").toString())
    }

    // ------------------------------------------------------------------- health

    @Test
    fun `a health answer from something else is refused as malformed`() = runTest {
        val gateway = gateway { json(HttpStatusCode.OK, """{"service":"some-other-app","apiVersion":1}""") }
        val error = assertIs<Outcome.Failure>(gateway.serverInfo(address)).error
        assertIs<RemoteError.Malformed>(error)
        assertTrue("not a Zheduler server" in error.message, error.message)
    }

    @Test
    fun `a server speaking another protocol version is refused and says which`() = runTest {
        val gateway = gateway { json(HttpStatusCode.OK, """{"service":"zheduler-sync","apiVersion":99}""") }
        val error = assertIs<Outcome.Failure>(gateway.serverInfo(address)).error
        assertIs<RemoteError.Malformed>(error)
        assertTrue("99" in error.message, error.message)
    }

    @Test
    fun `a body that is not json at all is malformed rather than a crash`() = runTest {
        val gateway = gateway { respond(ByteReadChannel("<html>hello</html>"), HttpStatusCode.OK) }
        assertIs<RemoteError.Malformed>(assertIs<Outcome.Failure>(gateway.serverInfo(address)).error)
    }

    // ---------------------------------------------------------- status mapping

    @Test
    fun `401 asks for a sign-in`() = runTest {
        val gateway = gateway {
            json(HttpStatusCode.Unauthorized, """{"code":"unauthenticated","message":"Session expired."}""")
        }
        val error = assertIs<Outcome.Failure>(gateway.listSpaces(address, token)).error
        assertIs<RemoteError.AuthenticationRequired>(error)
        assertEquals(RemoteRemedy.SignIn, error.remedy)
        assertEquals("Session expired.", error.message)
    }

    @Test
    fun `401 answering a sign-in is wrong credentials not an expired session`() = runTest {
        // Offering "sign in again" to somebody who is signing in is a loop with no exit.
        val gateway = gateway {
            json(
                HttpStatusCode.Unauthorized,
                """{"code":"invalid_credentials","message":"That username and password do not match."}""",
            )
        }
        val error = assertIs<Outcome.Failure>(gateway.logIn(address, "ada", "wrong")).error
        val rejected = assertIs<RemoteError.Rejected>(error)
        assertEquals(ApiErrorCode.InvalidCredentials, rejected.code)
        assertEquals(RemoteRemedy.None, rejected.remedy, "a wrong password is not something to retry")
    }

    @Test
    fun `403 is refused without offering a retry`() = runTest {
        val gateway = gateway { json(HttpStatusCode.Forbidden, """{"code":"not_found","message":"No."}""") }
        val error = assertIs<Outcome.Failure>(gateway.listSpaces(address, token)).error
        assertIs<RemoteError.NotAllowed>(error)
        assertEquals(RemoteRemedy.None, error.remedy)
    }

    @Test
    fun `404 is a missing space`() = runTest {
        val gateway = gateway { json(HttpStatusCode.NotFound, """{"code":"not_found","message":"gone"}""") }
        assertEquals(
            RemoteError.NotFound,
            assertIs<Outcome.Failure>(gateway.fetchSpace(address, token, "s1")).error,
        )
    }

    @Test
    fun `412 becomes a conflict carrying the revision to recover from`() = runTest {
        val gateway = gateway {
            json(
                HttpStatusCode.PreconditionFailed,
                """{"code":"revision_mismatch","message":"changed","currentRevision":7}""",
            )
        }
        val error = assertIs<Outcome.Failure>(
            gateway.updateSpace(address, token, "s1", 3L, pushRequest())
        ).error
        assertEquals(RemoteError.Conflict(7L), error)
        assertEquals(RemoteRemedy.ResolveConflict, error.remedy)
    }

    @Test
    fun `429 carries the wait the server asked for`() = runTest {
        val gateway = gateway {
            respond(
                ByteReadChannel("""{"code":"rate_limited","message":"slow down"}"""),
                HttpStatusCode.TooManyRequests,
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.RetryAfter to listOf("120"),
                ),
            )
        }
        val error = assertIs<Outcome.Failure>(gateway.logIn(address, "ada", "password")).error
        assertEquals(RemoteError.RateLimited(120.seconds), error)
        assertEquals(RemoteRemedy.RetryLater, error.remedy)
    }

    @Test
    fun `429 without a Retry-After is still a rate limit`() = runTest {
        val gateway = gateway {
            json(HttpStatusCode.TooManyRequests, """{"code":"rate_limited","message":"slow down"}""")
        }
        val error = assertIs<Outcome.Failure>(gateway.logIn(address, "ada", "password")).error
        assertEquals(RemoteError.RateLimited(null), error)
    }

    @Test
    fun `5xx offers a retry because the request itself was fine`() = runTest {
        listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable)
            .forEach { status ->
                val gateway = gateway { respondError(status) }
                val error = assertIs<Outcome.Failure>(gateway.listSpaces(address, token)).error
                assertIs<RemoteError.ServerFault>(error)
                assertEquals(status.value, error.status)
                assertEquals(RemoteRemedy.Retry, error.remedy)
            }
    }

    @Test
    fun `a 4xx the server explained keeps its code and its wording`() = runTest {
        val gateway = gateway {
            json(HttpStatusCode.BadRequest, """{"code":"weak_password","message":"Too short."}""")
        }
        val error = assertIs<Outcome.Failure>(gateway.register(address, "ada", "short")).error
        assertEquals(RemoteError.Rejected(ApiErrorCode.WeakPassword, "Too short."), error)
    }

    @Test
    fun `a 4xx with no body still says something usable`() = runTest {
        val gateway = gateway { respondError(HttpStatusCode.BadRequest) }
        val error = assertIs<Outcome.Failure>(gateway.register(address, "ada", "password"))
            .error as RemoteError.Rejected
        assertEquals(ApiErrorCode.InvalidRequest, error.code)
        assertTrue(error.message.isNotBlank())
    }

    @Test
    fun `a redirect is not followed because it would carry the token elsewhere`() = runTest {
        val gateway = gateway {
            respond(
                ByteReadChannel(""),
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://attacker.example.com/api/v1/spaces"),
            )
        }
        val error = assertIs<Outcome.Failure>(gateway.listSpaces(address, token)).error
        assertIs<RemoteError.Malformed>(error)
        assertEquals("https://sync.example.com/api/v1/spaces", lastRequest?.url.toString())
    }

    @Test
    fun `a connection that never opens is unreachable and retryable`() = runTest {
        val gateway = gateway { throw kotlinx.io.IOException("Connection refused") }
        val error = assertIs<Outcome.Failure>(gateway.serverInfo(address)).error
        assertIs<RemoteError.Unreachable>(error)
        assertEquals(RemoteRemedy.Retry, error.remedy)
        assertTrue("refused" in error.message, error.message)
    }

    // ------------------------------------------------------------ conditionals

    @Test
    fun `a download offers the revision it already holds`() = runTest {
        val gateway = gateway { respond(ByteReadChannel(""), HttpStatusCode.NotModified) }
        val result = gateway.fetchSpace(address, token, "s1", knownRevision = 5L)
        assertEquals(FetchedSpace.Unchanged(5L), assertIs<Outcome.Success<FetchedSpace>>(result).value)
        assertEquals("\"5\"", lastRequest?.headers?.get(HttpHeaders.IfNoneMatch))
    }

    @Test
    fun `a download with no held revision is unconditional`() = runTest {
        val gateway = gateway {
            json(
                HttpStatusCode.OK,
                """{"remoteId":"s1","name":"Work","idPrefix":"WRK","revision":2,
                   |"updatedAtEpochSeconds":100,"payload":"{}"}""".trimMargin(),
            )
        }
        val result = gateway.fetchSpace(address, token, "s1")
        assertNull(lastRequest?.headers?.get(HttpHeaders.IfNoneMatch))
        val fresh = assertIs<FetchedSpace.Fresh>(assertIs<Outcome.Success<FetchedSpace>>(result).value)
        assertEquals(2L, fresh.snapshot.revision)
        assertEquals("{}", fresh.snapshot.payload)
    }

    @Test
    fun `a 304 with nothing having been asked about is malformed not an empty success`() = runTest {
        val gateway = gateway { respond(ByteReadChannel(""), HttpStatusCode.NotModified) }
        val error = assertIs<Outcome.Failure>(gateway.fetchSpace(address, token, "s1")).error
        assertIs<RemoteError.Malformed>(error)
    }

    @Test
    fun `creating a space says it must not exist yet`() = runTest {
        val gateway = gateway {
            jsonWithETag(
                HttpStatusCode.Created,
                """{"remoteId":"s1","revision":1,"updatedAtEpochSeconds":100}""",
                "\"1\"",
            )
        }
        val result = gateway.createSpace(address, token, "s1", pushRequest())
        assertEquals(1L, assertIs<Outcome.Success<SpacePushResponse>>(result).value.revision)
        assertEquals(HttpMethod.Put, lastRequest?.method)
        assertEquals("*", lastRequest?.headers?.get(HttpHeaders.IfNoneMatch))
        assertNull(lastRequest?.headers?.get(HttpHeaders.IfMatch))
    }

    @Test
    fun `updating a space names the revision it is replacing`() = runTest {
        val gateway = gateway {
            json(HttpStatusCode.OK, """{"remoteId":"s1","revision":4,"updatedAtEpochSeconds":100}""")
        }
        val result = gateway.updateSpace(address, token, "s1", 3L, pushRequest())
        assertEquals(4L, assertIs<Outcome.Success<SpacePushResponse>>(result).value.revision)
        assertEquals("\"3\"", lastRequest?.headers?.get(HttpHeaders.IfMatch))
        assertNull(lastRequest?.headers?.get(HttpHeaders.IfNoneMatch))
    }

    @Test
    fun `deleting a space names the revision it is deleting`() = runTest {
        val gateway = gateway { respond(ByteReadChannel(""), HttpStatusCode.NoContent) }
        assertIs<Outcome.Success<Unit>>(gateway.deleteSpace(address, token, "s1", 9L))
        assertEquals(HttpMethod.Delete, lastRequest?.method)
        assertEquals("\"9\"", lastRequest?.headers?.get(HttpHeaders.IfMatch))
    }

    // ------------------------------------------------------------------ limits

    @Test
    fun `a space too large for the server is refused before it is uploaded`() = runTest {
        var reached = false
        val gateway = gateway {
            reached = true
            json(HttpStatusCode.Created, """{"remoteId":"s1","revision":1,"updatedAtEpochSeconds":1}""")
        }
        val huge = "x".repeat(SyncProtocol.MAX_PAYLOAD_BYTES + 1)
        val error = assertIs<Outcome.Failure>(
            gateway.createSpace(address, token, "s1", pushRequest(huge))
        ).error
        assertEquals(ApiErrorCode.PayloadTooLarge, assertIs<RemoteError.Rejected>(error).code)
        assertTrue(!reached, "eight megabytes were put on the wire only to be refused")
    }

    @Test
    fun `a payload just inside the limit is still sent`() = runTest {
        var reached = false
        val gateway = gateway {
            reached = true
            json(HttpStatusCode.Created, """{"remoteId":"s1","revision":1,"updatedAtEpochSeconds":1}""")
        }
        val large = "x".repeat(SyncProtocol.MAX_PAYLOAD_BYTES)
        assertIs<Outcome.Success<SpacePushResponse>>(
            gateway.createSpace(address, token, "s1", pushRequest(large))
        )
        assertTrue(reached)
    }

    @Test
    fun `the size limit counts bytes not characters`() = runTest {
        val gateway = gateway {
            json(HttpStatusCode.Created, """{"remoteId":"s1","revision":1,"updatedAtEpochSeconds":1}""")
        }
        // Three bytes each, so two thirds of the limit in characters is over it in bytes.
        val multiByte = "€".repeat(SyncProtocol.MAX_PAYLOAD_BYTES / 2)
        val error = assertIs<Outcome.Failure>(
            gateway.createSpace(address, token, "s1", pushRequest(multiByte))
        ).error
        assertEquals(ApiErrorCode.PayloadTooLarge, assertIs<RemoteError.Rejected>(error).code)
    }

    // ------------------------------------------------------------------ signing out

    @Test
    fun `signing out with a token the server has forgotten still counts as signed out`() = runTest {
        val gateway = gateway {
            json(HttpStatusCode.Unauthorized, """{"code":"unauthenticated","message":"gone"}""")
        }
        assertIs<Outcome.Success<Unit>>(gateway.logOut(address, token))
    }

    @Test
    fun `signing out against a broken server is still reported as broken`() = runTest {
        val gateway = gateway { respondError(HttpStatusCode.InternalServerError) }
        assertIs<RemoteError.ServerFault>(assertIs<Outcome.Failure>(gateway.logOut(address, token)).error)
    }
}
