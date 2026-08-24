@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.sync

import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.ServerConfig
import com.zhelenskiy.zheduler.zheduler.StorageConfig
import com.zhelenskiy.zheduler.zheduler.store.InMemorySyncStore
import com.zhelenskiy.zheduler.zheduler.syncModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * Two devices, one account, one server — the situation the whole feature exists for.
 *
 * Everything else in this suite tests one client against a server. That cannot see the thing a
 * user actually cares about: that what they typed on one machine turns up on the other, and that
 * when both of them write, the app says so instead of quietly picking a winner.
 *
 * Both "devices" are genuinely separate — their own repository, their own credential and link
 * stores, their own [SpaceSyncService] and [CloudSpaces] — talking to one real Netty server over
 * real HTTP. Nothing is shared but the account and the server.
 */
class TwoDevicesOnOneServerTest {

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private var serverPort = 0
    private val serverStore = InMemorySyncStore()
    private val clients = mutableListOf<HttpClient>()
    private val scopes = mutableListOf<CoroutineScope>()

    private val serverUrl get() = "http://127.0.0.1:$serverPort"

    /** One device: everything a running app would have, except the screens. */
    private inner class Device(val name: String) {
        val repository = InMemoryTaskRepository()
        private val client = HttpClient(CIO) { installSyncClientDefaults() }.also { clients += it }
        private val scope = CoroutineScope(Dispatchers.Unconfined).also { scopes += it }

        val sync = SpaceSyncService(
            gateway = KtorRemoteSpaceGateway(client),
            repository = repository,
            links = inMemoryStore(SyncSettings()),
            credentials = inMemoryStore(StoredCredentials()),
            revocations = scope,
        )

        val cloud = CloudSpaces(
            sync = sync,
            repository = repository,
            scope = scope,
            // No debounce: this is about what the two devices agree on, not about timers.
            settle = 0.milliseconds,
        )

        suspend fun signIn(existing: Boolean): SignedInAccount {
            val address = assertIs<Outcome.Success<ServerAddress>>(ServerAddress.parse(serverUrl)).value
            val outcome = if (existing) {
                sync.signIn(address, ACCOUNT, PASSWORD)
            } else {
                sync.signUp(address, ACCOUNT, PASSWORD)
            }
            return assertIs<Outcome.Success<SignedInAccount>>(outcome).value
        }

        suspend fun titles(spaceId: String): List<String> =
            repository.getAllTasks(spaceId).map { it.title }.sorted()

        suspend fun add(spaceId: String, title: String) {
            assertNotNull(repository.addTask(spaceId = spaceId, title = title))
        }
    }

    // Not inside a coroutine: EmbeddedServer.start blocks on coroutines of its own and deadlocks
    // when called from one.
    @Before
    fun startTheServer() {
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
            syncModule(config, serverStore, Clock.System)
        }
        server.start(wait = false)
        serverPort = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @After
    fun stopEverything() {
        scopes.forEach { it.cancel() }
        clients.forEach { it.close() }
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        serverStore.close()
    }

    /**
     * Gets both devices onto the same space: the first uploads it, the second downloads it.
     *
     * Returns each device's own id for that space. They differ — a space id is local — which is
     * itself worth having in a test, since everything shared between the two is keyed on the
     * *remote* id instead.
     */
    private suspend fun sharedSpace(first: Device, second: Device): Pair<String, String> {
        val firstAccount = first.signIn(existing = false)
        val here = assertNotNull(first.repository.createSpace("Work", "WRK")).id
        first.add(here, "written before anybody else joined")
        assertIs<Outcome.Success<Uploaded>>(
            first.cloud.putOnServer(here, firstAccount, "remote-shared")
        )

        val secondAccount = second.signIn(existing = true)
        val downloaded = assertIs<Outcome.Success<Downloaded>>(
            second.sync.download(secondAccount.key, "remote-shared")
        ).value
        second.cloud.refresh(downloaded.space.id)
        return here to downloaded.space.id
    }

    // ------------------------------------------------------------------ the ordinary case

    @Test
    fun `what one device writes the other has as soon as it looks`() = runBlocking<Unit> {
        val one = Device("one")
        val two = Device("two")
        val (here, there) = sharedSpace(one, two)
        assertEquals(listOf("written before anybody else joined"), two.titles(there))

        one.add(here, "added on the first device")
        one.cloud.uploadNow(here)

        // What opening the space on the other device does.
        two.cloud.refresh(there)

        assertEquals(
            listOf("added on the first device", "written before anybody else joined"),
            two.titles(there),
            "the second device did not pick up the first device's edit",
        )
        assertIs<CloudSpaceStatus.Live>(two.cloud.statusOf(there))
    }

    @Test
    fun `edits made in turn accumulate on both devices`() = runBlocking<Unit> {
        val one = Device("one")
        val two = Device("two")
        val (here, there) = sharedSpace(one, two)

        one.add(here, "first")
        one.cloud.uploadNow(here)
        two.cloud.refresh(there)

        two.add(there, "second")
        two.cloud.uploadNow(there)
        one.cloud.refresh(here)

        val expected = listOf("first", "second", "written before anybody else joined")
        assertEquals(expected, one.titles(here))
        assertEquals(expected, two.titles(there))
    }

    @Test
    fun `the device that is behind takes the server's copy rather than keeping its own`() = runBlocking<Unit> {
        val one = Device("one")
        val two = Device("two")
        val (here, there) = sharedSpace(one, two)

        // The first device removes something; the second still has it.
        val doomed = one.repository.getAllTasks(here).single()
        assertTrue(one.repository.deleteTask(doomed.id))
        one.cloud.uploadNow(here)
        assertEquals(listOf("written before anybody else joined"), two.titles(there))

        two.cloud.refresh(there)

        assertEquals(emptyList(), two.titles(there), "a deletion did not travel")
    }

    // ------------------------------------------------------------------ both at once

    @Test
    fun `when both devices write from the same revision the second is told rather than overruled`() = runBlocking<Unit> {
        val one = Device("one")
        val two = Device("two")
        val (here, there) = sharedSpace(one, two)

        // Both start from the same revision and both write. The first one up wins the race.
        one.add(here, "the first device's work")
        two.add(there, "the second device's work")
        one.cloud.uploadNow(here)
        two.cloud.uploadNow(there)

        // The second is not overruled and not silently merged: it is stopped and told.
        val stopped = assertIs<CloudSpaceStatus.Blocked>(two.cloud.statusOf(there))
        assertEquals(RemoteRemedy.ResolveConflict, stopped.error.remedy)
        assertFalse(stopped.isEditable)
        assertTrue(
            "the second device's work" in two.titles(there),
            "the copy the user is being asked about was thrown away",
        )

        // And checking again does not answer the question on the user's behalf.
        two.cloud.refresh(there)
        assertIs<CloudSpaceStatus.Blocked>(two.cloud.statusOf(there))
        assertTrue("the second device's work" in two.titles(there))
    }

    @Test
    fun `keeping this device's copy resolves the conflict and the other device catches up`() = runBlocking<Unit> {
        val one = Device("one")
        val two = Device("two")
        val (here, there) = sharedSpace(one, two)

        one.add(here, "the first device's work")
        two.add(there, "the second device's work")
        one.cloud.uploadNow(here)
        two.cloud.uploadNow(there)
        assertIs<CloudSpaceStatus.Blocked>(two.cloud.statusOf(there))

        // "Keep mine": the second device's copy becomes what the server holds.
        assertIs<Outcome.Success<Uploaded>>(two.sync.uploadOverwriting(there))
        two.cloud.conflictResolved(there)

        assertIs<CloudSpaceStatus.Live>(two.cloud.statusOf(there))
        one.cloud.refresh(here)

        assertEquals(
            two.titles(there),
            one.titles(here),
            "after a conflict was settled the two devices still disagree",
        )
        assertTrue("the second device's work" in one.titles(here))
    }

    /**
     * The other answer the app actually offers: bring the server's copy down beside this one.
     *
     * The dialog promises that nothing on this device is replaced, so this deliberately does *not*
     * settle the conflict — it gives the user both copies to compare, and the original is still
     * waiting for them to say which wins. The copy that arrives carries no link of its own: two
     * local spaces pointing at one remote space would fight over it, and whichever the user then
     * chose, the other would adopt the winner and quietly overwrite the copy they saved.
     */
    @Test
    fun `downloading the server's copy gives both copies and leaves the question open`() = runBlocking<Unit> {
        val one = Device("one")
        val two = Device("two")
        val (here, there) = sharedSpace(one, two)

        one.add(here, "the first device's work")
        two.add(there, "the second device's work")
        one.cloud.uploadNow(here)
        two.cloud.uploadNow(there)
        assertIs<CloudSpaceStatus.Blocked>(two.cloud.statusOf(there))

        val link = assertNotNull(two.sync.linkFor(there))
        val downloaded = assertIs<Outcome.Success<Downloaded>>(
            two.sync.download(link.account, link.remoteSpaceId, link = false)
        ).value

        // Both copies are here, and they are different copies.
        assertTrue("the first device's work" in two.titles(downloaded.space.id))
        assertTrue("the second device's work" in two.titles(there))
        assertNull(
            two.sync.linkFor(downloaded.space.id),
            "the copy kept for comparison must not claim the same remote space",
        )

        // And the original is still asking.
        assertIs<CloudSpaceStatus.Blocked>(two.cloud.statusOf(there))
        two.cloud.refresh(there)
        assertIs<CloudSpaceStatus.Blocked>(two.cloud.statusOf(there))
        assertTrue("the second device's work" in two.titles(there))
    }

    private companion object {
        const val ACCOUNT = "ada"
        const val PASSWORD = "a long enough password"
    }
}
