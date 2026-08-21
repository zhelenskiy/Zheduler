@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import com.zhelenskiy.zheduler.zheduler.geo.GeoArea
import com.zhelenskiy.zheduler.zheduler.geo.GeoPoint
import com.zhelenskiy.zheduler.zheduler.geo.GeofenceDirection
import com.zhelenskiy.zheduler.zheduler.geo.SavedLocation
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

class InMemorySavedLocationRepositoryTest : SavedLocationRepositoryTest(), InMemoryRepositoryTest
class DatabaseSavedLocationRepositoryTest : SavedLocationRepositoryTest(), DatabaseRepositoryTest

/**
 * The address book, checked against both repositories at once.
 *
 * Both, because they are two implementations of one contract and the app runs on the Room one
 * while most tests exercise the in-memory one — a divergence here shows up as a place that saves
 * in a test and vanishes on a phone.
 */
abstract class SavedLocationRepositoryTest : AbstractRepositoryTest {

    private fun place(id: String, name: String, latitude: Double = 51.5, longitude: Double = -0.12) =
        SavedLocation(
            id = id,
            name = name,
            point = GeoPoint(latitude, longitude),
            radiusMeters = 300.0,
            address = "$name, London",
        )

    @Test
    fun `a new book is empty`() = runTest {
        val repo = createEmptyRepository()
        assertTrue(repo.getAllSavedLocations().isEmpty())
    }

    @Test
    fun `a place survives being written and read back`() = runTest {
        val repo = createEmptyRepository()
        val saved = repo.saveLocation(place("home", "Home"))

        val read = assertNotNull(repo.getSavedLocationById("home"))
        assertEquals(saved, read)
        assertEquals("Home", read.name)
        assertEquals(51.5, read.point.latitude)
        assertEquals(-0.12, read.point.longitude)
        assertEquals(300.0, read.radiusMeters)
        assertEquals("Home, London", read.address)
    }

    @Test
    fun `saving the same id again renames rather than duplicates`() = runTest {
        val repo = createEmptyRepository()
        repo.saveLocation(place("home", "Home"))
        repo.saveLocation(place("home", "Mum's"))

        assertEquals(listOf("Mum's"), repo.getAllSavedLocations().map { it.name })
    }

    @Test
    fun `a radius no fence can mean is brought into range on the way in`() = runTest {
        // Stored as it will be measured against, so the book cannot hold a fence the engine would
        // silently treat as a different size.
        val repo = createEmptyRepository()
        val saved = repo.saveLocation(place("tiny", "Tiny").copy(radiusMeters = 0.0))

        assertEquals(GeoArea.MIN_RADIUS_METERS, saved.radiusMeters)
        assertEquals(GeoArea.MIN_RADIUS_METERS, assertNotNull(repo.getSavedLocationById("tiny")).radiusMeters)
    }

    @Test
    fun `places come back in a settled order`() = runTest {
        val repo = createEmptyRepository()
        repo.saveLocation(place("c", "Work"))
        repo.saveLocation(place("a", "Allotment"))
        repo.saveLocation(place("b", "home"))

        assertEquals(
            listOf("Allotment", "home", "Work"),
            repo.getAllSavedLocations().map { it.name },
            "sorted by name and not by case, or the list jumps about as places are added",
        )
    }

    @Test
    fun `searching matches the name and the address and ignores case`() = runTest {
        val repo = createEmptyRepository()
        repo.saveLocation(place("home", "Home"))
        repo.saveLocation(place("work", "Work").copy(address = "5 Baker Street, London"))

        assertEquals(listOf("Work"), repo.searchSavedLocations("baker").map { it.name })
        assertEquals(listOf("Home"), repo.searchSavedLocations("HOM").map { it.name })
        assertEquals(2, repo.searchSavedLocations("").size, "a blank search is not a filter")
    }

    @Test
    fun `a wildcard typed into the search is a character rather than a pattern`() = runTest {
        // The SQL side matches with LIKE, where an unescaped % matches everything — so typing one
        // would list the whole book instead of nothing.
        val repo = createEmptyRepository()
        repo.saveLocation(place("home", "Home"))

        assertTrue(repo.searchSavedLocations("%").isEmpty())
        assertTrue(repo.searchSavedLocations("_ome").isEmpty())
    }

    @Test
    fun `deleting a place leaves the rules that copied it alone`() = runTest {
        // The whole reason a rule carries its own copy of the area: exporting a task to a device
        // that has never heard of this book still has to work.
        val (repo, spaceId) = createRepositoryWithSpace()
        val saved = repo.saveLocation(place("home", "Home"))
        val task = assertNotNull(
            repo.addTask(
                spaceId = spaceId,
                title = "Take the bins out",
                recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = null,
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open,
                        locationTrigger = RecurrenceTrigger.LocationChange(
                            areas = persistentSetOf(saved.toArea()),
                            direction = GeofenceDirection.Leaving,
                        ),
                    ) to RecurrenceState()
                ),
            )
        )

        assertTrue(repo.deleteSavedLocation("home"))
        assertNull(repo.getSavedLocationById("home"))

        val rule = assertNotNull(repo.getTaskById(task.id)).recurrenceRules.single().first
        val watched = assertNotNull(rule.locationTrigger).areas.single()
        assertEquals("Home", watched.name)
        assertEquals(GeofenceDirection.Leaving, assertNotNull(rule.locationTrigger).direction)
    }

    @Test
    fun `deleting something that is not there says so`() = runTest {
        val repo = createEmptyRepository()
        assertTrue(!repo.deleteSavedLocation("nobody"))
    }

    @Test
    fun `the book belongs to the user and not to a space`() = runTest {
        val repo = createEmptyRepository()
        val one = assertNotNull(repo.createSpace("One", "ONE"))
        assertNotNull(repo.createSpace("Two", "TWO"))
        repo.saveLocation(place("home", "Home"))

        assertTrue(repo.deleteSpace(one.id))

        assertEquals(
            listOf("Home"),
            repo.getAllSavedLocations().map { it.name },
            "deleting a space must not take the address book with it",
        )
    }

    @Test
    fun `clearing everything clears the book too`() = runTest {
        val repo = createEmptyRepository()
        repo.saveLocation(place("home", "Home"))

        repo.clearAllData()

        assertTrue(
            repo.getAllSavedLocations().isEmpty(),
            "the book hangs off no space, so deleting every space would leave it behind",
        )
    }
}
