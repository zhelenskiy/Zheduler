@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.store.InMemorySyncStore
import com.zhelenskiy.zheduler.zheduler.store.SyncStore
import com.zhelenskiy.zheduler.zheduler.sync.ApiErrorCode
import com.zhelenskiy.zheduler.zheduler.sync.AuthToken
import com.zhelenskiy.zheduler.zheduler.sync.FetchedSpace
import com.zhelenskiy.zheduler.zheduler.sync.KtorRemoteSpaceGateway
import com.zhelenskiy.zheduler.zheduler.sync.Outcome
import com.zhelenskiy.zheduler.zheduler.sync.RemoteError
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSpaceGateway
import com.zhelenskiy.zheduler.zheduler.sync.ServerAddress
import com.zhelenskiy.zheduler.zheduler.sync.SpacePushRequest
import com.zhelenskiy.zheduler.zheduler.sync.SyncProtocol
import com.zhelenskiy.zheduler.zheduler.sync.installSyncClientDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * Both halves at once: the real client gateway, over real HTTP, against the real Netty server.
 *
 * The unit suites test each side against a stand-in for the other, which cannot catch a
 * disagreement between them — a header one side writes and the other never reads, a status the
 * client maps to the wrong remedy. This one can, because nothing here is mocked.
 *
 * It also exercises the loopback exemption in [ServerAddress]: the whole test talks plain `http`
 * to `127.0.0.1`, which is exactly the case that rule exists for.
 */
class SyncEndToEndTest {

    private lateinit var store: SyncStore
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var client: HttpClient
    private lateinit var gateway: RemoteSpaceGateway
    // Not lateinit: ServerAddress is a value class, which cannot carry the uninitialised marker.
    private var addressOrNull: ServerAddress? = null
    private val address: ServerAddress get() = requireNotNull(addressOrNull)

    private val clock = MutableClock()

    // Deliberately not a `runBlocking` block. `EmbeddedServer.start` blocks on coroutines of its
    // own, and calling it from inside one deadlocks: the thread it needs is the thread waiting
    // for it.
    @Before
    fun startServer() {
        store = InMemorySyncStore()
        val config = ServerConfig(
            port = 0,
            host = "127.0.0.1",
            storage = StorageConfig.InMemory,
            tokenLifetime = 30.days,
            allowedOrigins = emptyList(),
            trustForwardedHeaders = false,
            strictTransportSecurity = false,
        )
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            syncModule(config, store, clock)
        }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }

        client = HttpClient(CIO) { installSyncClientDefaults() }
        gateway = KtorRemoteSpaceGateway(client)
        addressOrNull = ServerAddress.parse("http://127.0.0.1:$port").orFail()
    }

    @After
    fun stopServer() {
        client.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        store.close()
    }

    private suspend fun signUp(username: String): AuthToken =
        AuthToken(gateway.register(address, username, VALID_PASSWORD).orFail().token)

    private suspend fun signIn(username: String): AuthToken =
        AuthToken(gateway.logIn(address, username, VALID_PASSWORD).orFail().token)

    private suspend fun payloadOf(token: AuthToken, remoteId: String): String =
        assertIs<FetchedSpace.Fresh>(gateway.fetchSpace(address, token, remoteId).orFail()).snapshot.payload

    private fun push(payload: String) = SpacePushRequest("Work", "WRK", payload)

    @Test
    fun `the app can find a server, sign up, upload a space and get it back`() = runBlocking<Unit> {
        assertEquals(SyncProtocol.SERVICE_NAME, gateway.serverInfo(address).orFail().service)

        val token = signUp("ada")

        assertEquals(1L, gateway.createSpace(address, token, "space-1", push("""{"tasks":[]}""")).orFail().revision)

        val summaries = gateway.listSpaces(address, token).orFail()
        assertEquals(listOf("space-1"), summaries.map { it.remoteId })
        assertEquals(12L, summaries.single().payloadBytes)

        assertEquals("""{"tasks":[]}""", payloadOf(token, "space-1"))
    }

    @Test
    fun `signing in again on another device reaches the same spaces`() = runBlocking<Unit> {
        val first = signUp("ada")
        gateway.createSpace(address, first, "space-1", push("shared"))

        val second = signIn("ada")
        assertNotEquals(first.value, second.value)
        assertEquals("shared", payloadOf(second, "space-1"))
    }

    @Test
    fun `a second download of an unchanged space moves no payload`() = runBlocking<Unit> {
        val token = signUp("ada")
        gateway.createSpace(address, token, "space-1", push("x".repeat(50_000)))

        assertEquals(
            FetchedSpace.Unchanged(1L),
            gateway.fetchSpace(address, token, "space-1", knownRevision = 1L).orFail(),
        )

        gateway.updateSpace(address, token, "space-1", 1L, push("changed"))
        val changed = gateway.fetchSpace(address, token, "space-1", knownRevision = 1L).orFail()
        assertEquals("changed", assertIs<FetchedSpace.Fresh>(changed).snapshot.payload)
    }

    @Test
    fun `a device working from a stale copy is refused and told what it lost to`() = runBlocking<Unit> {
        val token = signUp("ada")
        gateway.createSpace(address, token, "space-1", push("v1"))
        gateway.updateSpace(address, token, "space-1", 1L, push("v2-from-the-phone"))

        val stale = gateway.updateSpace(address, token, "space-1", 1L, push("v2-from-the-laptop"))
        val conflict = assertIs<RemoteError.Conflict>(assertIs<Outcome.Failure>(stale).error)
        assertEquals(2L, conflict.remoteRevision)

        // And the recovery the conflict makes possible: take the current copy, then push over it.
        val current = assertIs<FetchedSpace.Fresh>(gateway.fetchSpace(address, token, "space-1").orFail())
        assertEquals("v2-from-the-phone", current.snapshot.payload)
        assertEquals(
            3L,
            gateway.updateSpace(address, token, "space-1", current.snapshot.revision, push("v3-merged"))
                .orFail().revision,
        )
    }

    @Test
    fun `two accounts on one server never see each other's spaces`() = runBlocking<Unit> {
        val ada = signUp("ada")
        val bob = signUp("bob")
        gateway.createSpace(address, ada, "same-id", push("ada's tasks"))
        gateway.createSpace(address, bob, "same-id", push("bob's tasks"))

        assertEquals("ada's tasks", payloadOf(ada, "same-id"))
        assertEquals("bob's tasks", payloadOf(bob, "same-id"))
        assertEquals(1, gateway.listSpaces(address, ada).orFail().size)
    }

    @Test
    fun `a token that has expired asks the user to sign in again`() = runBlocking<Unit> {
        val token = signUp("ada")
        clock.advanceBy(31.days)
        assertIs<RemoteError.AuthenticationRequired>(
            assertIs<Outcome.Failure>(gateway.listSpaces(address, token)).error
        )
    }

    @Test
    fun `signing out on one device leaves the other signed in`() = runBlocking<Unit> {
        val phone = signUp("ada")
        val laptop = signIn("ada")
        assertIs<Outcome.Success<Unit>>(gateway.logOut(address, phone))

        assertIs<RemoteError.AuthenticationRequired>(
            assertIs<Outcome.Failure>(gateway.listSpaces(address, phone)).error
        )
        assertTrue(gateway.listSpaces(address, laptop).orFail().isEmpty())
    }

    @Test
    fun `a wrong password is reported as something the user can fix`() = runBlocking<Unit> {
        signUp("ada")
        val error = assertIs<Outcome.Failure>(gateway.logIn(address, "ada", OTHER_PASSWORD)).error
        assertEquals(ApiErrorCode.InvalidCredentials, assertIs<RemoteError.Rejected>(error).code)
    }

    @Test
    fun `nothing that reaches the client carries a credential back`() = runBlocking<Unit> {
        val token = signUp("ada")
        gateway.createSpace(address, token, "space-1", push("data"))
        val error = assertIs<Outcome.Failure>(
            gateway.updateSpace(address, token, "space-1", 99L, push("data"))
        ).error
        assertTrue(token.value !in error.message, error.message)
    }

    @Test
    fun `a real multi-megabyte space survives the round trip`() = runBlocking<Unit> {
        val token = signUp("ada")
        // Repetitive on purpose: this is also the case gzip is installed for, so a payload that
        // came back mangled by the encoder would show up here.
        val payload = buildString {
            append("""{"tasks":[""")
            repeat(40_000) { index ->
                if (index > 0) append(',')
                append("""{"id":"TASK-$index","title":"Täsk $index 😀"}""")
            }
            append("]}")
        }
        assertTrue(payload.length > 1_000_000, "payload was only ${payload.length} characters")

        gateway.createSpace(address, token, "space-1", push(payload)).orFail()
        assertEquals(payload, payloadOf(token, "space-1"))
    }

    @Test
    fun `a space larger than the server accepts is refused, not half-stored`() = runBlocking<Unit> {
        val token = signUp("ada")
        val huge = "x".repeat(SyncProtocol.MAX_PAYLOAD_BYTES + 1024)
        val error = assertIs<Outcome.Failure>(
            gateway.createSpace(address, token, "space-1", push(huge))
        ).error
        assertEquals(ApiErrorCode.PayloadTooLarge, assertIs<RemoteError.Rejected>(error).code)
        assertIs<RemoteError.NotFound>(
            assertIs<Outcome.Failure>(gateway.fetchSpace(address, token, "space-1")).error
        )
    }

    @Test
    fun `a deleted space is gone for good`() = runBlocking<Unit> {
        val token = signUp("ada")
        gateway.createSpace(address, token, "space-1", push("data"))
        assertIs<Outcome.Success<Unit>>(gateway.deleteSpace(address, token, "space-1", 1L))
        assertIs<RemoteError.NotFound>(
            assertIs<Outcome.Failure>(gateway.fetchSpace(address, token, "space-1")).error
        )
        assertTrue(gateway.listSpaces(address, token).orFail().isEmpty())
    }

    @Test
    fun `an address that is not a server at all is reported as unreachable`() = runBlocking<Unit> {
        // Port 1 on loopback: nothing listens there, so the connection is refused outright.
        val nowhere = ServerAddress.parse("http://127.0.0.1:1").orFail()
        val error = assertIs<Outcome.Failure>(gateway.serverInfo(nowhere)).error
        assertTrue(
            error is RemoteError.Unreachable || error is RemoteError.TimedOut,
            "expected a transport failure, got $error",
        )
        assertTrue(error.remedy.name.startsWith("Retry"))
    }
}

/** The value, or an assertion failure naming what went wrong instead of a cast exception. */
private fun <T> Outcome<T>.orFail(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> throw AssertionError("expected a success, but the call failed: ${error.message}")
}
