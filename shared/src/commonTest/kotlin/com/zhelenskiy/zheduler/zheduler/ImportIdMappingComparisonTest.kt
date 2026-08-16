package com.zhelenskiy.zheduler.zheduler

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Importing a space renames its tasks under the new prefix. Two source tasks must never be given
 * the same new id: one would overwrite the other in memory and collide on the primary key in the
 * database, so the same file either lost a task quietly or failed to import at all.
 */
@OptIn(ExperimentalTime::class)
class ImportIdMappingComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    /** Exports a space whose tasks carry [customIds], then imports it back. */
    private suspend fun TaskRepository.roundTrip(customIds: List<String>): List<Task> {
        val source = createSpace("Source", "SRC")!!
        customIds.forEach { id ->
            assertNotNull(addTask(source.id, title = "task $id", customId = id), "could not create $id")
        }
        val json = assertNotNull(exportSpaceToJson(source.id, prettyPrint = false))
        val imported = assertNotNull(importSpaceFromJson(json), "import returned null")
        return getAllTasks(imported.id)
    }

    @Test
    fun `ids differing only by a leading zero stay distinct`() = runTest {
        for (repository in repositories()) {
            val tasks = repository.roundTrip(listOf("SRC-1", "SRC-01"))
            assertEquals(2, tasks.size, "$repository: one task was lost to an id collision")
            assertEquals(2, tasks.mapTo(mutableSetOf()) { it.id }.size, "$repository: ids repeat")
        }
    }

    @Test
    fun `ids with no number keep one each`() = runTest {
        for (repository in repositories()) {
            val tasks = repository.roundTrip(listOf("SRC-alpha", "SRC-beta", "SRC-gamma"))
            assertEquals(3, tasks.size, "$repository")
            assertEquals(3, tasks.mapTo(mutableSetOf()) { it.id }.size, "$repository: ids repeat")
        }
    }

    @Test
    fun `an imported space can still create tasks`() = runTest {
        for (repository in repositories()) {
            // Ids that cannot keep their own number are given the next free one, which may land at
            // or above the counter the export restores. The next generated id then repeated an
            // existing one; the insert failed and rolled back the counter with it, so the space
            // could never gain another task.
            val source = repository.createSpace("Source", "SRC")!!
            assertNotNull(repository.addTask(source.id, title = "one", customId = "SRC-1"))
            assertNotNull(repository.addTask(source.id, title = "two", customId = "SRC-01"))
            val json = assertNotNull(repository.exportSpaceToJson(source.id, prettyPrint = false))
            val imported = assertNotNull(repository.importSpaceFromJson(json))

            val added = repository.addTask(imported.id, title = "added after import")
            assertNotNull(added, "$repository: the imported space cannot create tasks")
            assertEquals(
                3,
                repository.getAllTasks(imported.id).size,
                "$repository: the new task should sit alongside the two imported ones",
            )
        }
    }

    @Test
    fun `an ordinary export keeps its numbering`() = runTest {
        for (repository in repositories()) {
            val tasks = repository.roundTrip(listOf("SRC-1", "SRC-2", "SRC-3"))
            assertEquals(
                setOf(1, 2, 3),
                tasks.mapTo(mutableSetOf()) { it.id.substringAfterLast("-").toInt() },
                "$repository: plain numeric ids should be preserved",
            )
        }
    }
}
