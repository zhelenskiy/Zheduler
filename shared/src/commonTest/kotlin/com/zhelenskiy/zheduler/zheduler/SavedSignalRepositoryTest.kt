@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import com.zhelenskiy.zheduler.zheduler.geo.SavedSignal
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

class InMemorySavedSignalRepositoryTest : SavedSignalRepositoryTest(), InMemoryRepositoryTest
class DatabaseSavedSignalRepositoryTest : SavedSignalRepositoryTest(), DatabaseRepositoryTest

/**
 * The book of networks and devices, checked against both repositories at once.
 *
 * Both for the reason the places are: they are two implementations of one contract, and a
 * divergence here is an entry that saves in a test and vanishes on a phone.
 */
abstract class SavedSignalRepositoryTest : AbstractRepositoryTest {

    private fun wifi(id: String, name: String, ssid: String = "acme-corp-5G") =
        SavedSignal(id = id, name = name, signal = NearbySignal.Wifi(ssid))

    private fun device(id: String, name: String, address: String = "AA:BB:CC:DD:EE:FF") =
        SavedSignal(id = id, name = name, signal = NearbySignal.Bluetooth(address, "Car audio"))

    @Test
    fun `a new book is empty`() = runTest {
        val repo = createEmptyRepository()
        assertTrue(repo.getAllSavedSignals().isEmpty())
    }

    @Test
    fun `a network survives being written and read back`() = runTest {
        val repo = createEmptyRepository()
        val saved = repo.saveSignal(wifi("office", "The office"))

        val read = assertNotNull(repo.getSavedSignalById("office"))
        assertEquals(saved, read)
        assertEquals("The office", read.name)
        assertEquals(NearbySignal.Wifi("acme-corp-5G"), read.signal)
    }

    @Test
    fun `a device keeps its address and the name it calls itself`() = runTest {
        val repo = createEmptyRepository()
        repo.saveSignal(device("car", "The car"))

        val read = assertNotNull(repo.getSavedSignalById("car"))
        assertEquals(NearbySignal.Bluetooth("AA:BB:CC:DD:EE:FF", "Car audio"), read.signal)
        assertEquals("The car", read.displayName)
    }

    @Test
    fun `an address is kept in the case a rule matches on`() = runTest {
        // A rule matches a device by `key`, which upper-cases the address. Kept as it arrived —
        // and the platforms disagree about that — the same device saved from a mac and watched by
        // a rule written on a phone would be offered twice and read as two different things.
        val repo = createEmptyRepository()
        repo.saveSignal(device("car", "The car", address = "aa:bb:cc:dd:ee:ff"))

        val read = assertNotNull(repo.getSavedSignalById("car"))
        assertEquals("AA:BB:CC:DD:EE:FF", (read.signal as NearbySignal.Bluetooth).address)
        assertEquals(NearbySignal.Bluetooth("AA:BB:CC:DD:EE:FF").key, read.signal.key)
    }

    @Test
    fun `an entry with no name of its own falls back to what the system calls it`() = runTest {
        val repo = createEmptyRepository()
        repo.saveSignal(wifi("office", "  "))

        assertEquals("acme-corp-5G", assertNotNull(repo.getSavedSignalById("office")).displayName)
    }

    @Test
    fun `saving over an id renames rather than adding`() = runTest {
        val repo = createEmptyRepository()
        repo.saveSignal(wifi("office", "The office"))
        repo.saveSignal(wifi("office", "Work"))

        assertEquals(listOf("Work"), repo.getAllSavedSignals().map { it.name })
    }

    @Test
    fun `the book is ordered by name whatever order it was filled in`() = runTest {
        val repo = createEmptyRepository()
        repo.saveSignal(wifi("c", "Zoo"))
        repo.saveSignal(wifi("a", "airport"))
        repo.saveSignal(wifi("b", "Home"))

        assertEquals(listOf("airport", "Home", "Zoo"), repo.getAllSavedSignals().map { it.name })
    }

    @Test
    fun `searching finds an entry by any of the three names it goes by`() = runTest {
        val repo = createEmptyRepository()
        repo.saveSignal(wifi("office", "The office", ssid = "acme-corp-5G"))
        repo.saveSignal(device("car", "Commute"))

        assertEquals(listOf("office"), repo.searchSavedSignals("office").map { it.id })
        assertEquals(listOf("office"), repo.searchSavedSignals("acme").map { it.id })
        assertEquals(listOf("car"), repo.searchSavedSignals("Car audio").map { it.id })
        assertEquals(listOf("car"), repo.searchSavedSignals("AA:BB").map { it.id })
    }

    @Test
    fun `an empty search is the whole book`() = runTest {
        val repo = createEmptyRepository()
        repo.saveSignal(wifi("office", "The office"))
        repo.saveSignal(device("car", "The car"))

        assertEquals(2, repo.searchSavedSignals("").size)
        assertEquals(2, repo.searchSavedSignals("   ").size)
    }

    @Test
    fun `a wildcard typed into the search is a character and not a pattern`() = runTest {
        // The queries are LIKE, where `%` is "anything at all". Left unescaped, a search for
        // "50%" turns up every name beginning 50 rather than the one that says 50 per cent.
        val repo = createEmptyRepository()
        repo.saveSignal(wifi("half", "50% off"))
        repo.saveSignal(wifi("thousands", "5000 Network"))

        assertEquals(listOf("half"), repo.searchSavedSignals("50%").map { it.id })
    }

    @Test
    fun `keeping the same thing twice leaves one entry`() = runTest {
        // Asked from a list of what is around, where the caller has no id in mind and holds a
        // snapshot of the book that cannot see a save still in flight. Two taps on the same row
        // used to file the device twice, under two ids and one name.
        val repo = createEmptyRepository()

        repo.keepSignal(device("first", ""))
        repo.keepSignal(device("second", ""))

        assertEquals(1, repo.getAllSavedSignals().size)
        assertEquals("first", repo.getAllSavedSignals().single().id)
    }

    @Test
    fun `keeping something the book already holds leaves its name alone`() = runTest {
        val repo = createEmptyRepository()
        repo.saveSignal(device("car", "The car"))

        val kept = repo.keepSignal(device("another", ""))

        assertEquals("car", kept.id)
        assertEquals("The car", kept.name, "a name the user chose is not written over")
        assertEquals(1, repo.getAllSavedSignals().size)
    }

    @Test
    fun `keeping is matched by the thing a rule matches on`() = runTest {
        // Not by id and not by the name the device gives itself: the same headphones offered by a
        // mac and by a phone differ in the casing of the address and in what they call themselves.
        val repo = createEmptyRepository()
        repo.saveSignal(device("car", "The car", address = "AA:BB:CC:DD:EE:FF"))

        repo.keepSignal(
            SavedSignal(
                id = "other",
                name = "",
                signal = NearbySignal.Bluetooth("aa:bb:cc:dd:ee:ff", "WH-1000XM4"),
            )
        )

        assertEquals(listOf("The car"), repo.getAllSavedSignals().map { it.name })
    }

    @Test
    fun `two keeps at once still leave one entry`() = runTest {
        // The window the guard exists for. Decided anywhere but under the repository's own lock,
        // both would read a book without the device in it and both would file it.
        val repo = createEmptyRepository()

        coroutineScope {
            listOf(
                async { repo.keepSignal(device("first", "")) },
                async { repo.keepSignal(device("second", "")) },
            ).awaitAll()
        }

        assertEquals(1, repo.getAllSavedSignals().size)
    }

    @Test
    fun `keeping two different things keeps both`() = runTest {
        val repo = createEmptyRepository()

        repo.keepSignal(device("car", ""))
        repo.keepSignal(wifi("office", ""))

        assertEquals(2, repo.getAllSavedSignals().size)
    }

    @Test
    fun `deleting removes only that entry and says whether there was one`() = runTest {
        val repo = createEmptyRepository()
        repo.saveSignal(wifi("office", "The office"))
        repo.saveSignal(device("car", "The car"))

        assertTrue(repo.deleteSavedSignal("office"))
        assertNull(repo.getSavedSignalById("office"))
        assertEquals(listOf("car"), repo.getAllSavedSignals().map { it.id })
        assertTrue(!repo.deleteSavedSignal("office"), "there is nothing left to delete")
    }

    @Test
    fun `clearing everything takes the book with it`() = runTest {
        // It hangs off no space, so deleting every space — which is how the rest of the database
        // is cleared — would otherwise leave it standing.
        val repo = createEmptyRepository()
        repo.saveSignal(wifi("office", "The office"))

        repo.clearAllData()

        assertTrue(repo.getAllSavedSignals().isEmpty())
    }
}
