@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.store.InMemorySyncStore
import com.zhelenskiy.zheduler.zheduler.sync.ApiError
import com.zhelenskiy.zheduler.zheduler.sync.ApiErrorCode
import com.zhelenskiy.zheduler.zheduler.sync.AuthRequest
import com.zhelenskiy.zheduler.zheduler.sync.AuthResponse
import com.zhelenskiy.zheduler.zheduler.sync.ServerInfo
import com.zhelenskiy.zheduler.zheduler.sync.SpacePushRequest
import com.zhelenskiy.zheduler.zheduler.sync.SpacePushResponse
import com.zhelenskiy.zheduler.zheduler.sync.SpaceSnapshot
import com.zhelenskiy.zheduler.zheduler.sync.SpaceSummary
import com.zhelenskiy.zheduler.zheduler.sync.SyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import io.ktor.utils.io.ByteReadChannel
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

/**
 * The API as a caller sees it: statuses, headers, error codes.
 *
 * Runs against [InMemorySyncStore], which `SyncStoreContractTest` holds to the same behaviour as
 * the database, so what is being tested here is the HTTP layer and not the storage underneath it.
 */
class SyncApiTest {

    private val clock = MutableClock()

    private fun testConfig() = ServerConfig(
        port = 0,
        host = "127.0.0.1",
        storage = StorageConfig.InMemory,
        tokenLifetime = 30.days,
        allowedOrigins = emptyList(),
        trustForwardedHeaders = false,
        strictTransportSecurity = false,
    )

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        val store = InMemorySyncStore()
        application { syncModule(testConfig(), store, clock) }
        val client = createClient {
            followRedirects = false
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        block(client)
    }

    // ------------------------------------------------------------------- helpers

    private suspend fun HttpClient.register(
        username: String,
        password: String = VALID_PASSWORD,
    ): HttpResponse = post("$BASE/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(AuthRequest(username, password))
    }

    private suspend fun HttpClient.logIn(
        username: String,
        password: String = VALID_PASSWORD,
    ): HttpResponse = post("$BASE/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(AuthRequest(username, password))
    }

    private suspend fun HttpClient.tokenFor(username: String): String =
        register(username).body<AuthResponse>().token

    private suspend fun HttpClient.createSpace(
        token: String,
        remoteId: String,
        name: String = "Work",
        prefix: String = "WRK",
        payload: String = """{"tasks":[]}""",
    ): HttpResponse = put("$BASE/spaces/$remoteId") {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.IfNoneMatch, "*")
        contentType(ContentType.Application.Json)
        setBody(SpacePushRequest(name, prefix, payload))
    }

    private suspend fun HttpClient.updateSpace(
        token: String,
        remoteId: String,
        revision: Long,
        payload: String,
        name: String = "Work",
        prefix: String = "WRK",
    ): HttpResponse = put("$BASE/spaces/$remoteId") {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.IfMatch, "\"$revision\"")
        contentType(ContentType.Application.Json)
        setBody(SpacePushRequest(name, prefix, payload))
    }

    private suspend fun HttpResponse.apiError(): ApiError = body()

    // --------------------------------------------------------------------- health

    @Test
    fun `health identifies the service and the protocol version`() = api { client ->
        val response = client.get("$BASE/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val info = response.body<ServerInfo>()
        assertEquals(SyncProtocol.SERVICE_NAME, info.service)
        assertEquals(SyncProtocol.API_VERSION, info.apiVersion)
    }

    @Test
    fun `health needs no credentials and reveals nothing else`() = api { client ->
        val body = client.get("$BASE/health").bodyAsText()
        assertFalse("user" in body.lowercase(), body)
        assertFalse("version\":\"" in body, "no build string should be advertised: $body")
    }

    // -------------------------------------------------------------- registration

    @Test
    fun `registering returns a token that works`() = api { client ->
        val response = client.register("ada")
        assertEquals(HttpStatusCode.Created, response.status)
        val auth = response.body<AuthResponse>()
        assertEquals("ada", auth.username)
        assertTrue(auth.token.isNotEmpty())

        val me = client.get("$BASE/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
        }
        assertEquals(HttpStatusCode.OK, me.status)
    }

    @Test
    fun `a token is never cached`() = api { client ->
        val response = client.register("ada")
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `usernames are case-insensitive and trimmed`() = api { client ->
        assertEquals(HttpStatusCode.Created, client.register("Ada").status)
        val second = client.register("  ADA  ")
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals(ApiErrorCode.UsernameTaken, second.apiError().code)
        // And the trimmed, lower-cased form is the one that signs in.
        assertEquals(HttpStatusCode.OK, client.logIn("ada").status)
    }

    @Test
    fun `a short password is refused as weak, not as malformed`() = api { client ->
        val response = client.register("ada", "short")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ApiErrorCode.WeakPassword, response.apiError().code)
    }

    @Test
    fun `an absurdly long password is refused before it is hashed`() = api { client ->
        val response = client.register("ada", "x".repeat(SyncProtocol.MAX_PASSWORD_LENGTH + 1))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ApiErrorCode.WeakPassword, response.apiError().code)
    }

    @Test
    fun `a username that is not usable is refused`() = api { client ->
        listOf("ab", "-ada", "ada-", "ad a", "ada!", "a".repeat(65)).forEach { username ->
            val response = client.register(username)
            assertEquals(HttpStatusCode.BadRequest, response.status, "accepted \"$username\"")
            assertEquals(ApiErrorCode.InvalidRequest, response.apiError().code)
        }
    }

    // -------------------------------------------------------------------- sign-in

    @Test
    fun `signing in with the right password works`() = api { client ->
        client.register("ada")
        val response = client.logIn("ada")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ada", response.body<AuthResponse>().username)
    }

    @Test
    fun `a wrong password and an unknown account are refused identically`() = api { client ->
        client.register("ada")
        val wrongPassword = client.logIn("ada", OTHER_PASSWORD)
        val noSuchAccount = client.logIn("nobody", OTHER_PASSWORD)

        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
        assertEquals(HttpStatusCode.Unauthorized, noSuchAccount.status)
        // Byte for byte the same, so the answer never says which usernames exist.
        assertEquals(wrongPassword.bodyAsText(), noSuchAccount.bodyAsText())
        assertEquals(ApiErrorCode.InvalidCredentials, wrongPassword.apiError().code)
    }

    @Test
    fun `each sign-in issues its own token and signing out revokes only that one`() = api { client ->
        client.register("ada")
        val first = client.logIn("ada").body<AuthResponse>().token
        val second = client.logIn("ada").body<AuthResponse>().token
        assertNotEquals(first, second)

        val loggedOut = client.post("$BASE/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer $first")
        }
        assertEquals(HttpStatusCode.NoContent, loggedOut.status)

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("$BASE/auth/me") { header(HttpHeaders.Authorization, "Bearer $first") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("$BASE/auth/me") { header(HttpHeaders.Authorization, "Bearer $second") }.status,
            "signing out on one device must not sign the others out",
        )
    }

    @Test
    fun `signing out with a token that was never issued still succeeds`() = api { client ->
        // Otherwise the answer confirms whether a guessed token was real.
        val response = client.post("$BASE/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer not-a-real-token")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(HttpStatusCode.NoContent, client.post("$BASE/auth/logout").status)
    }

    @Test
    fun `a token stops working once it has expired`() = api { client ->
        val token = client.tokenFor("ada")
        clock.advanceBy(29.days)
        assertEquals(
            HttpStatusCode.OK,
            client.get("$BASE/auth/me") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
        )
        clock.advanceBy(2.days)
        val expired = client.get("$BASE/auth/me") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Unauthorized, expired.status)
        assertEquals(ApiErrorCode.Unauthenticated, expired.apiError().code)
    }

    @Test
    fun `repeated wrong passwords are eventually rate limited`() = api { client ->
        client.register("ada")
        repeat(ServerConfig.AUTH_ATTEMPTS_PER_WINDOW) {
            assertEquals(HttpStatusCode.Unauthorized, client.logIn("ada", OTHER_PASSWORD).status)
        }
        val limited = client.logIn("ada", OTHER_PASSWORD)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals(ApiErrorCode.RateLimited, limited.apiError().code)
        assertTrue((limited.headers[HttpHeaders.RetryAfter]?.toIntOrNull() ?: 0) > 0)

        // The lock-out applies to the right password too, or it would be trivially side-stepped.
        assertEquals(HttpStatusCode.TooManyRequests, client.logIn("ada").status)

        clock.advanceBy(ServerConfig.AUTH_RATE_WINDOW)
        assertEquals(HttpStatusCode.OK, client.logIn("ada").status)
    }

    @Test
    fun `a successful sign-in forgets that account's own failures`() = api { client ->
        client.register("ada")
        repeat(ServerConfig.AUTH_ATTEMPTS_PER_WINDOW - 1) { client.logIn("ada", OTHER_PASSWORD) }
        assertEquals(HttpStatusCode.OK, client.logIn("ada").status)
        repeat(ServerConfig.AUTH_ATTEMPTS_PER_WINDOW - 1) {
            assertEquals(HttpStatusCode.Unauthorized, client.logIn("ada", OTHER_PASSWORD).status)
        }
    }

    @Test
    fun `a success does not hand back the budget for spraying other accounts`() = api { client ->
        // The per-account counter never trips during a spray — each name is tried once — so the
        // per-address one is the only thing watching. Clearing it on a success made the whole
        // limit a formality: fail, fail, sign in once, repeat.
        client.register("ada")

        // One short of the address limit, each against a different account, so no account's own
        // counter ever goes above one.
        repeat(ServerConfig.AUTH_ATTEMPTS_PER_ADDRESS_PER_WINDOW - 1) { attempt ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.logIn("victim-$attempt", OTHER_PASSWORD).status,
                "attempt $attempt",
            )
        }
        // One good sign-in, which is what used to wipe the address counter.
        assertEquals(HttpStatusCode.OK, client.logIn("ada").status)

        // If the success had cleared it, this would be failure number one of sixty again.
        assertEquals(HttpStatusCode.Unauthorized, client.logIn("victim-last", OTHER_PASSWORD).status)
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.logIn("victim-fresh", OTHER_PASSWORD).status,
            "the address budget came back after a successful sign-in",
        )
    }

    @Test
    fun `an address may only create so many accounts in a window`() = api { client ->
        // Every attempt counts, success included: an account is free to ask for and costs the
        // server a deliberate fifth of a second of password hashing.
        repeat(ServerConfig.SIGN_UPS_PER_ADDRESS_PER_WINDOW) { attempt ->
            assertEquals(HttpStatusCode.Created, client.register("newcomer-$attempt").status)
        }
        val limited = client.register("one-too-many")
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals(ApiErrorCode.RateLimited, limited.apiError().code)

        clock.advanceBy(ServerConfig.AUTH_RATE_WINDOW)
        assertEquals(HttpStatusCode.Created, client.register("later-arrival").status)
    }

    @Test
    fun `signing up does not use up the sign-in budget`() = api { client ->
        // The two counters are separate; making an account must not lock the account out.
        assertEquals(HttpStatusCode.Created, client.register("ada").status)
        repeat(ServerConfig.AUTH_ATTEMPTS_PER_WINDOW - 1) {
            assertEquals(HttpStatusCode.Unauthorized, client.logIn("ada", OTHER_PASSWORD).status)
        }
        assertEquals(HttpStatusCode.OK, client.logIn("ada").status)
    }

    // ------------------------------------------------------------ authentication

    @Test
    fun `every space endpoint refuses an unauthenticated caller`() = api { client ->
        listOf(
            client.get("$BASE/spaces"),
            client.get("$BASE/spaces/s1"),
            client.put("$BASE/spaces/s1") {
                header(HttpHeaders.IfNoneMatch, "*")
                contentType(ContentType.Application.Json)
                setBody(SpacePushRequest("Work", "WRK", "{}"))
            },
            client.delete("$BASE/spaces/s1") { header(HttpHeaders.IfMatch, "\"1\"") },
        ).forEach { response ->
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `a malformed or invented token is refused`() = api { client ->
        listOf("Bearer", "Bearer ", "Basic abc", "Bearer made-up-token").forEach { header ->
            val response = client.get("$BASE/spaces") { header(HttpHeaders.Authorization, header) }
            assertEquals(HttpStatusCode.Unauthorized, response.status, "accepted \"$header\"")
        }
    }

    // ---------------------------------------------------------------- the spaces

    @Test
    fun `a space is created, listed, downloaded and updated`() = api { client ->
        val token = client.tokenFor("ada")

        val created = client.createSpace(token, "space-1", payload = """{"tasks":[1]}""")
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("\"1\"", created.headers[HttpHeaders.ETag])
        assertEquals(1L, created.body<SpacePushResponse>().revision)

        val listed = client.get("$BASE/spaces") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<List<SpaceSummary>>()
        assertEquals(1, listed.size)
        assertEquals("space-1", listed.single().remoteId)
        assertEquals(13L, listed.single().payloadBytes)

        val downloaded = client.get("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals("\"1\"", downloaded.headers[HttpHeaders.ETag])
        assertEquals("""{"tasks":[1]}""", downloaded.body<SpaceSnapshot>().payload)

        val updated = client.updateSpace(token, "space-1", 1L, """{"tasks":[1,2]}""")
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("\"2\"", updated.headers[HttpHeaders.ETag])
    }

    @Test
    fun `a payload is stored and returned byte for byte`() = api { client ->
        val token = client.tokenFor("ada")
        // The server is not supposed to parse this at all; anything it re-encoded would come
        // back changed.
        val payload = """{"b":1,"a":2,"unicode":"täsk 😀","raw":"</script>&amp;"}"""
        client.createSpace(token, "space-1", payload = payload)
        val snapshot = client.get("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<SpaceSnapshot>()
        assertEquals(payload, snapshot.payload)
    }

    @Test
    fun `a conditional download of an unchanged space transfers no payload`() = api { client ->
        val token = client.tokenFor("ada")
        client.createSpace(token, "space-1")

        val unchanged = client.get("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.IfNoneMatch, "\"1\"")
        }
        assertEquals(HttpStatusCode.NotModified, unchanged.status)
        assertEquals("\"1\"", unchanged.headers[HttpHeaders.ETag])
        assertEquals("", unchanged.bodyAsText())
    }

    @Test
    fun `a conditional download after a change sends the new copy`() = api { client ->
        val token = client.tokenFor("ada")
        client.createSpace(token, "space-1")
        client.updateSpace(token, "space-1", 1L, "changed")

        val fresh = client.get("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.IfNoneMatch, "\"1\"")
        }
        assertEquals(HttpStatusCode.OK, fresh.status)
        assertEquals("changed", fresh.body<SpaceSnapshot>().payload)
    }

    @Test
    fun `a weak entity tag is accepted, since a proxy may weaken one`() = api { client ->
        val token = client.tokenFor("ada")
        client.createSpace(token, "space-1")
        val unchanged = client.get("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.IfNoneMatch, "W/\"1\"")
        }
        assertEquals(HttpStatusCode.NotModified, unchanged.status)
    }

    @Test
    fun `creating a space that already exists is refused rather than overwriting it`() = api { client ->
        val token = client.tokenFor("ada")
        client.createSpace(token, "space-1", payload = "first")

        val second = client.createSpace(token, "space-1", payload = "second")
        assertEquals(HttpStatusCode.PreconditionFailed, second.status)
        val error = second.apiError()
        assertEquals(ApiErrorCode.RevisionMismatch, error.code)
        assertEquals(1L, error.currentRevision)

        val stored = client.get("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<SpaceSnapshot>()
        assertEquals("first", stored.payload)
    }

    @Test
    fun `an update from a stale revision is refused and says what the current one is`() = api { client ->
        val token = client.tokenFor("ada")
        client.createSpace(token, "space-1", payload = "v1")
        client.updateSpace(token, "space-1", 1L, "v2")

        val stale = client.updateSpace(token, "space-1", 1L, "v3-from-a-stale-device")
        assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
        assertEquals(2L, stale.apiError().currentRevision)

        val stored = client.get("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<SpaceSnapshot>()
        assertEquals("v2", stored.payload, "the stale device overwrote the newer copy")
    }

    @Test
    fun `a write with no precondition is refused outright`() = api { client ->
        val token = client.tokenFor("ada")
        val unconditional = client.put("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(SpacePushRequest("Work", "WRK", "{}"))
        }
        assertEquals(428, unconditional.status.value)
        assertEquals(ApiErrorCode.PreconditionRequired, unconditional.apiError().code)

        val undeleted = client.delete("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(428, undeleted.status.value)
    }

    @Test
    fun `a precondition this server did not issue is refused`() = api { client ->
        val token = client.tokenFor("ada")
        listOf("1", "\"abc\"", "\"\"", "*", "\"1", "1\"").forEach { tag ->
            val response = client.put("$BASE/spaces/space-1") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.IfMatch, tag)
                contentType(ContentType.Application.Json)
                setBody(SpacePushRequest("Work", "WRK", "{}"))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "accepted If-Match: $tag")
        }
    }

    @Test
    fun `sending both preconditions at once is refused rather than guessed at`() = api { client ->
        val token = client.tokenFor("ada")
        val response = client.put("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.IfMatch, "\"1\"")
            header(HttpHeaders.IfNoneMatch, "*")
            contentType(ContentType.Application.Json)
            setBody(SpacePushRequest("Work", "WRK", "{}"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `updating a space that does not exist is a not found`() = api { client ->
        val token = client.tokenFor("ada")
        val response = client.updateSpace(token, "never-existed", 1L, "{}")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ApiErrorCode.NotFound, response.apiError().code)
    }

    @Test
    fun `a delete is guarded by the revision`() = api { client ->
        val token = client.tokenFor("ada")
        client.createSpace(token, "space-1")
        client.updateSpace(token, "space-1", 1L, "v2")

        val stale = client.delete("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.IfMatch, "\"1\"")
        }
        assertEquals(HttpStatusCode.PreconditionFailed, stale.status)

        val deleted = client.delete("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.IfMatch, "\"2\"")
        }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("$BASE/spaces/space-1") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.status,
        )
    }

    // ------------------------------------------------------------ tenant isolation

    @Test
    fun `one account cannot see or touch another's space`() = api { client ->
        val ada = client.tokenFor("ada")
        val bob = client.tokenFor("bob")
        client.createSpace(ada, "shared-id", payload = "ada's data")

        assertTrue(
            client.get("$BASE/spaces") { header(HttpHeaders.Authorization, "Bearer $bob") }
                .body<List<SpaceSummary>>().isEmpty()
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("$BASE/spaces/shared-id") { header(HttpHeaders.Authorization, "Bearer $bob") }.status,
        )
        assertEquals(HttpStatusCode.NotFound, client.updateSpace(bob, "shared-id", 1L, "bob's data").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("$BASE/spaces/shared-id") {
                header(HttpHeaders.Authorization, "Bearer $bob")
                header(HttpHeaders.IfMatch, "\"1\"")
            }.status,
        )

        assertEquals(
            "ada's data",
            client.get("$BASE/spaces/shared-id") {
                header(HttpHeaders.Authorization, "Bearer $ada")
            }.body<SpaceSnapshot>().payload,
        )
    }

    @Test
    fun `two accounts can hold the same space id at once`() = api { client ->
        val ada = client.tokenFor("ada")
        val bob = client.tokenFor("bob")
        assertEquals(HttpStatusCode.Created, client.createSpace(ada, "same", payload = "ada").status)
        assertEquals(HttpStatusCode.Created, client.createSpace(bob, "same", payload = "bob").status)

        assertEquals(
            "ada",
            client.get("$BASE/spaces/same") {
                header(HttpHeaders.Authorization, "Bearer $ada")
            }.body<SpaceSnapshot>().payload,
        )
    }

    // ------------------------------------------------------------------- refusals

    @Test
    fun `a request with no declared length is refused`() = api { client ->
        val token = client.tokenFor("ada")
        // A channel body has no length to declare, so the engine sends it chunked. That is the
        // shape the size limit cannot check in advance, and so the one it has to refuse.
        val response = client.put("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.IfNoneMatch, "*")
            contentType(ContentType.Application.Json)
            setBody(ByteReadChannel("""{"name":"Work","idPrefix":"WRK","payload":"{}"}"""))
        }
        assertEquals(HttpStatusCode.LengthRequired, response.status)
        assertEquals(ApiErrorCode.InvalidRequest, response.apiError().code)
    }

    @Test
    fun `a space larger than the limit is refused`() = api { client ->
        val token = client.tokenFor("ada")
        val response = client.put("$BASE/spaces/space-1") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.IfNoneMatch, "*")
            contentType(ContentType.Application.Json)
            setBody(SpacePushRequest("Work", "WRK", "x".repeat(SyncProtocol.MAX_PAYLOAD_BYTES + 1)))
        }
        assertEquals(413, response.status.value)
        assertEquals(ApiErrorCode.PayloadTooLarge, response.apiError().code)
    }

    @Test
    fun `a space id that is not usable is refused`() = api { client ->
        val token = client.tokenFor("ada")
        listOf("has space", "has%2Fslash", "a".repeat(129), "quote\"").forEach { remoteId ->
            val response = client.createSpace(token, remoteId)
            assertTrue(
                response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.NotFound,
                "accepted id \"$remoteId\" with ${response.status}",
            )
        }
    }

    @Test
    fun `a space whose fields are not usable is refused`() = api { client ->
        val token = client.tokenFor("ada")
        listOf(
            SpacePushRequest("", "WRK", "{}"),
            SpacePushRequest("   ", "WRK", "{}"),
            SpacePushRequest("a".repeat(201), "WRK", "{}"),
            SpacePushRequest("Work", "wrk", "{}"),
            SpacePushRequest("Work", "", "{}"),
            SpacePushRequest("Work", "WRK1", "{}"),
        ).forEach { request ->
            val response = client.put("$BASE/spaces/space-1") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.IfNoneMatch, "*")
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "accepted $request")
        }
    }

    @Test
    fun `a space carrying a NUL character is refused rather than reaching the database`() = api { client ->
        // PostgreSQL cannot put one in a TEXT column, so a payload that gets this far is a 500
        // there and a cheerful 201 in memory — the two stores disagreeing about what can be
        // stored. Refused before either sees it.
        val token = client.tokenFor("ada")
        listOf(
            SpacePushRequest("Work", "WRK", "before\u0000after"),
            SpacePushRequest("Wo\u0000rk", "WRK", "{}"),
        ).forEach { request ->
            val response = client.put("$BASE/spaces/space-1") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.IfNoneMatch, "*")
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "accepted $request")
            assertEquals(ApiErrorCode.InvalidRequest, response.apiError().code)
        }
    }

    @Test
    fun `a body that is not the expected shape is a bad request, not a server error`() = api { client ->
        val response = client.post("$BASE/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"ada"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ApiErrorCode.InvalidRequest, response.apiError().code)
    }

    @Test
    fun `a refusal never carries a stack trace`() = api { client ->
        val response = client.post("$BASE/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("not json at all")
        }
        val body = response.bodyAsText()
        listOf("Exception", "kotlinx.serialization", "\tat ", "io.ktor").forEach { leak ->
            assertFalse(leak in body, "the body leaked \"$leak\": $body")
        }
    }

    // ------------------------------------------------------------------- headers

    @Test
    fun `responses carry the hardening headers and not the engine's name`() = api { client ->
        val response = client.get("$BASE/health")
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("zheduler", response.headers[HttpHeaders.Server])
        assertTrue(response.headers["Content-Security-Policy"]?.contains("frame-ancestors 'none'") == true)
        assertNull(response.headers["Strict-Transport-Security"], "HSTS is opt-in; there is no TLS here")
    }

    @Test
    fun `an unauthorised answer is not cacheable`() = api { client ->
        val response = client.get("$BASE/spaces")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    private companion object {
        const val BASE = SyncProtocol.BASE_PATH
    }
}
