@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.store

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The behaviour every [SyncStore] has to have, written once and run against each implementation.
 *
 * The in-memory store exists so the API tests can run in milliseconds, which is only worth
 * anything if it behaves the way the database does. That is what this suite is for: the moment
 * one of them drifts — a revision guard that is not atomic, an account boundary that is a filter
 * rather than a key — the other's copy of this suite fails.
 */
abstract class SyncStoreContractTest {

    protected abstract fun createStore(): SyncStore

    private lateinit var store: SyncStore

    private val now = Instant.fromEpochSeconds(1_700_000_000)

    @BeforeTest
    fun setUp() {
        store = createStore()
    }

    @AfterTest
    fun tearDown() {
        store.close()
    }

    private suspend fun account(username: String): StoredAccount =
        (store.createAccount(username, "hash-for-$username") as CreateAccountResult.Created).account

    // ------------------------------------------------------------------ accounts

    @Test
    fun `an account can be created and found again`() = runTest {
        val created = account("ada")
        val found = store.findAccount("ada")
        assertEquals(created, found)
        assertTrue(created.userId.isNotEmpty())
    }

    @Test
    fun `a username can only be taken once`() = runTest {
        account("ada")
        assertEquals(CreateAccountResult.UsernameTaken, store.createAccount("ada", "another hash"))
    }

    @Test
    fun `an account that does not exist is null rather than an error`() = runTest {
        assertNull(store.findAccount("nobody"))
    }

    @Test
    fun `a password hash can be replaced`() = runTest {
        val created = account("ada")
        store.updatePasswordHash(created.userId, "a stronger hash")
        assertEquals("a stronger hash", store.findAccount("ada")?.passwordHash)
    }

    // -------------------------------------------------------------------- tokens

    @Test
    fun `a stored token names its account until it expires`() = runTest {
        val created = account("ada")
        val fingerprint = byteArrayOf(1, 2, 3)
        store.storeToken(fingerprint, created.userId, now, now + 1.hours)

        assertEquals(created, store.accountForToken(fingerprint, now))
        assertEquals(created, store.accountForToken(fingerprint, now + 59.minutes))
        assertNull(store.accountForToken(fingerprint, now + 1.hours), "expiry is not exclusive")
        assertNull(store.accountForToken(fingerprint, now + 2.hours))
    }

    @Test
    fun `an unknown token names nobody`() = runTest {
        account("ada")
        assertNull(store.accountForToken(byteArrayOf(9, 9, 9), now))
    }

    @Test
    fun `revoking a token takes effect immediately`() = runTest {
        val created = account("ada")
        val fingerprint = byteArrayOf(4, 5, 6)
        store.storeToken(fingerprint, created.userId, now, now + 1.hours)
        store.revokeToken(fingerprint)
        assertNull(store.accountForToken(fingerprint, now))
    }

    @Test
    fun `purging removes expired tokens and leaves live ones`() = runTest {
        val created = account("ada")
        store.storeToken(byteArrayOf(1), created.userId, now, now + 1.hours)
        store.storeToken(byteArrayOf(2), created.userId, now, now + 3.hours)

        assertEquals(1, store.purgeExpiredTokens(now + 2.hours))
        assertNull(store.accountForToken(byteArrayOf(1), now + 2.hours))
        assertNotNull(store.accountForToken(byteArrayOf(2), now + 2.hours))
    }

    // -------------------------------------------------------------------- spaces

    @Test
    fun `a created space comes back with revision one`() = runTest {
        val ada = account("ada")
        val written = store.createSpace(ada.userId, "s1", "Work", "WRK", "{}", now)
        assertEquals(1L, (written as WriteResult.Written).receipt.revision)

        val loaded = assertNotNull(store.loadSpace(ada.userId, "s1"))
        assertEquals("Work", loaded.header.name)
        assertEquals("WRK", loaded.header.idPrefix)
        assertEquals("{}", loaded.payload)
        assertEquals(1L, loaded.header.revision)
    }

    @Test
    fun `creating the same space twice is a conflict carrying the revision that won`() = runTest {
        val ada = account("ada")
        store.createSpace(ada.userId, "s1", "Work", "WRK", "{}", now)
        val second = store.createSpace(ada.userId, "s1", "Other", "OTH", "{\"a\":1}", now)
        assertEquals(WriteResult.Conflict(1L), second)
        // And the loser changed nothing.
        assertEquals("Work", store.loadSpace(ada.userId, "s1")?.header?.name)
    }

    @Test
    fun `an update at the current revision advances it`() = runTest {
        val ada = account("ada")
        store.createSpace(ada.userId, "s1", "Work", "WRK", "{}", now)
        val updated = store.updateSpace(ada.userId, "s1", 1L, "Work v2", "WRK", "{\"b\":2}", now + 1.hours)
        assertEquals(2L, (updated as WriteResult.Written).receipt.revision)

        val loaded = assertNotNull(store.loadSpace(ada.userId, "s1"))
        assertEquals("Work v2", loaded.header.name)
        assertEquals("{\"b\":2}", loaded.payload)
        assertEquals(now + 1.hours, loaded.header.updatedAt)
    }

    @Test
    fun `an update at a stale revision changes nothing and reports the current one`() = runTest {
        val ada = account("ada")
        store.createSpace(ada.userId, "s1", "Work", "WRK", "{}", now)
        store.updateSpace(ada.userId, "s1", 1L, "Work v2", "WRK", "second", now)

        val stale = store.updateSpace(ada.userId, "s1", 1L, "Work v3", "WRK", "third", now)
        assertEquals(WriteResult.Conflict(2L), stale)
        assertEquals("second", store.loadSpace(ada.userId, "s1")?.payload)
    }

    @Test
    fun `updating a space that does not exist is not found rather than a conflict`() = runTest {
        val ada = account("ada")
        assertEquals(WriteResult.NotFound, store.updateSpace(ada.userId, "gone", 1L, "n", "N", "{}", now))
    }

    @Test
    fun `a delete is guarded by the revision too`() = runTest {
        val ada = account("ada")
        store.createSpace(ada.userId, "s1", "Work", "WRK", "{}", now)
        store.updateSpace(ada.userId, "s1", 1L, "Work", "WRK", "v2", now)

        assertEquals(WriteResult.Conflict(2L), store.deleteSpace(ada.userId, "s1", 1L))
        assertNotNull(store.loadSpace(ada.userId, "s1"))

        assertTrue(store.deleteSpace(ada.userId, "s1", 2L) is WriteResult.Written)
        assertNull(store.loadSpace(ada.userId, "s1"))
        assertEquals(WriteResult.NotFound, store.deleteSpace(ada.userId, "s1", 2L))
    }

    @Test
    fun `the listing describes spaces without their payloads`() = runTest {
        val ada = account("ada")
        store.createSpace(ada.userId, "s2", "Second", "SEC", "0123456789", now)
        store.createSpace(ada.userId, "s1", "First", "FST", "abc", now)

        val listed = store.listSpaces(ada.userId)
        assertEquals(listOf("s1", "s2"), listed.map { it.remoteId })
        assertEquals(3L, listed.first { it.remoteId == "s1" }.payloadBytes)
        assertEquals(10L, listed.first { it.remoteId == "s2" }.payloadBytes)
    }

    @Test
    fun `payload size is counted in bytes, not characters`() = runTest {
        val ada = account("ada")
        // Four characters, ten bytes: one ASCII, one two-byte, one three-byte, one four-byte
        // (which is two UTF-16 units, so the string's length is five).
        store.createSpace(ada.userId, "s1", "Unicode", "UNI", "aé€😀", now)
        assertEquals(10L, store.listSpaces(ada.userId).single().payloadBytes)
    }

    @Test
    fun `the revision can be read without the payload`() = runTest {
        val ada = account("ada")
        store.createSpace(ada.userId, "s1", "Work", "WRK", "{}", now)
        assertEquals(1L, store.spaceRevision(ada.userId, "s1"))
        assertNull(store.spaceRevision(ada.userId, "missing"))
    }

    @Test
    fun `a large payload survives the round trip intact`() = runTest {
        val ada = account("ada")
        // Big enough to be TOASTed out of line by PostgreSQL, which is where a truncating column
        // type or a mangled encoding would show up.
        val payload = buildString { repeat(200_000) { append("täsk-") } }
        store.createSpace(ada.userId, "s1", "Big", "BIG", payload, now)
        assertEquals(payload, store.loadSpace(ada.userId, "s1")?.payload)
    }

    // ------------------------------------------------------------ tenant isolation

    @Test
    fun `one account cannot read, write or delete another's space by naming its id`() = runTest {
        val ada = account("ada")
        val bob = account("bob")
        store.createSpace(ada.userId, "shared-id", "Ada's", "ADA", "ada's payload", now)

        assertNull(store.loadSpace(bob.userId, "shared-id"))
        assertNull(store.spaceRevision(bob.userId, "shared-id"))
        assertTrue(store.listSpaces(bob.userId).isEmpty())
        assertEquals(
            WriteResult.NotFound,
            store.updateSpace(bob.userId, "shared-id", 1L, "Bob's", "BOB", "bob's payload", now),
        )
        assertEquals(WriteResult.NotFound, store.deleteSpace(bob.userId, "shared-id", 1L))

        // And Ada's copy is exactly as she left it.
        assertEquals("ada's payload", store.loadSpace(ada.userId, "shared-id")?.payload)
        assertEquals(1L, store.spaceRevision(ada.userId, "shared-id"))
    }

    @Test
    fun `the same space id under two accounts is two independent spaces`() = runTest {
        val ada = account("ada")
        val bob = account("bob")
        store.createSpace(ada.userId, "same", "Ada's", "ADA", "ada", now)
        assertTrue(store.createSpace(bob.userId, "same", "Bob's", "BOB", "bob", now) is WriteResult.Written)

        assertEquals("ada", store.loadSpace(ada.userId, "same")?.payload)
        assertEquals("bob", store.loadSpace(bob.userId, "same")?.payload)

        store.updateSpace(bob.userId, "same", 1L, "Bob's", "BOB", "bob v2", now)
        assertEquals("ada", store.loadSpace(ada.userId, "same")?.payload, "Bob's write reached Ada's row")
    }

    // ----------------------------------------------------------------- concurrency

    @Test
    fun `only one of many simultaneous creations of the same space wins`() = runTest {
        val ada = account("ada")
        val results = (1..8).map { attempt ->
            async { store.createSpace(ada.userId, "race", "Space", "SPC", "payload-$attempt", now) }
        }.awaitAll()

        assertEquals(1, results.count { it is WriteResult.Written }, "results were $results")
        assertTrue(results.filterIsInstance<WriteResult.Conflict>().all { it.currentRevision == 1L })
    }

    @Test
    fun `only one of many simultaneous updates from the same revision wins`() = runTest {
        val ada = account("ada")
        store.createSpace(ada.userId, "race", "Space", "SPC", "start", now)

        val results = (1..8).map { attempt ->
            async { store.updateSpace(ada.userId, "race", 1L, "Space", "SPC", "payload-$attempt", now) }
        }.awaitAll()

        val winners = results.filterIsInstance<WriteResult.Written>()
        assertEquals(1, winners.size, "results were $results")
        assertEquals(2L, winners.single().receipt.revision)
        // Whichever one won, the stored payload is one of the eight in full — never a blend.
        val stored = assertNotNull(store.loadSpace(ada.userId, "race")).payload
        assertTrue(stored in (1..8).map { "payload-$it" }, "stored \"$stored\"")
        assertEquals(2L, store.spaceRevision(ada.userId, "race"))
    }
}
