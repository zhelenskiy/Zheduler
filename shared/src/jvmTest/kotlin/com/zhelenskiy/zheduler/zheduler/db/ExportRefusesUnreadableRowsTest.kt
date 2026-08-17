package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertNotNull

/**
 * A file is supposed to be everything the space has.
 *
 * A row this build cannot read — one written by a later build, met again after going back to this
 * one — costs the user that one view mode everywhere else: it is missing from the list and the app
 * carries on. An export cannot afford the same leniency. Quietly leaving the row out writes a file
 * that looks complete, is restored months later once the original is gone, and only then turns out
 * to be short. Refusing to write it at all says so while the original is still there.
 */
class ExportRefusesUnreadableRowsTest {

    private fun database() = Room.inMemoryDatabaseBuilder<ZhedulerDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()

    @Test
    fun `a view mode this build cannot read stops the export`() {
        val database = database()
        try {
            runBlocking {
                val repository = RoomTaskRepository(database)
                val space = assertNotNull(repository.createSpace("Test", "TEST"))
                repository.addTask(space.id, title = "a task")

                assertNotNull(repository.exportSpaceToJson(space.id), "the space exports before this")

                // What a later build's view mode looks like to this one.
                database.dao().insertOrUpdateCustomViewMode(
                    id = "vm-from-the-future",
                    spaceId = space.id,
                    name = "From the future",
                    configJson = """{"groupingLevels":[{"field":"SomethingNewer","groups":[]}]}""",
                )

                assertFails("the export left the unreadable view mode out and said nothing") {
                    repository.exportSpaceToJson(space.id)
                }
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `a saved filter this build cannot read stops the export`() {
        val database = database()
        try {
            runBlocking {
                val repository = RoomTaskRepository(database)
                val space = assertNotNull(repository.createSpace("Test", "TEST"))

                database.dao().insertOrUpdateSavedFilter(
                    id = "filter-from-the-future",
                    spaceId = space.id,
                    name = "From the future",
                    criteriaJson = """{"priorityFilter":"SomethingNewer"}""",
                    viewModeId = null,
                )

                assertFails("the export left the unreadable filter out and said nothing") {
                    repository.exportSpaceToJson(space.id)
                }
            }
        } finally {
            database.close()
        }
    }
}
