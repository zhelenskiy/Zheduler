@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.db

import com.zhelenskiy.zheduler.zheduler.cleanupDatabaseTest
import com.zhelenskiy.zheduler.zheduler.createDatabaseRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.time.ExperimentalTime

/**
 * An import writes a space, its tasks, their history, their tags and their links as a series of
 * statements. Either all of it lands or none does — a space holding half its tasks and none of
 * their links is worse than no space at all, and there is nothing in the UI to clean it up.
 */
class ImportAtomicityTest {

    @AfterTest
    fun tearDown() = cleanupDatabaseTest()

    @Test
    fun `a failed import leaves no space behind`() = runTest {
        val source = createDatabaseRepository()
        val sourceSpace = source.createSpace("Source", "SRC")!!
        source.addTask(sourceSpace.id, title = "First")
        source.addTask(sourceSpace.id, title = "Second")

        // Two tasks carrying the same id. Remapping gives them one new id between them, so the
        // second insert is rejected part-way through the import. (Ids that merely share a
        // trailing number no longer collide — they are handed distinct new ids — so that is no
        // longer a way to make this fail.)
        val corrupted = source.exportSpaceToJson(sourceSpace.id)!!.replace("SRC-2", "SRC-1")

        val target = createDatabaseRepository()
        assertFails { target.importSpaceFromJson(corrupted) }

        assertFalse(target.hasSpaces(), "the half-written space should have been rolled back")
        assertEquals(emptyList(), target.getAllSpaces())
    }

    @Test
    fun `a clean import is committed`() = runTest {
        val source = createDatabaseRepository()
        val sourceSpace = source.createSpace("Source", "SRC")!!
        source.addTask(sourceSpace.id, title = "First")
        source.addTask(sourceSpace.id, title = "Second")
        val json = source.exportSpaceToJson(sourceSpace.id)!!

        val target = createDatabaseRepository()
        val imported = target.importSpaceFromJson(json)!!

        assertEquals(2, target.getAllTasks(imported.id).size)
    }
}
