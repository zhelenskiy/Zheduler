package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhelenskiy.zheduler.zheduler.events.ChosenSound
import com.zhelenskiy.zheduler.zheduler.events.CustomSound
import com.zhelenskiy.zheduler.zheduler.events.NotificationSound
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * What a task's deadline sounds like is one column, and a column is only as good as every path
 * that writes it. The one that gets forgotten is import: it rebuilds a task field by field, so a
 * field nobody added there is dropped in silence — the restore succeeds and the setting is gone.
 */
class DueSoundSurvivesStorageTest {

    private fun database() = Room.inMemoryDatabaseBuilder<ZhedulerDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()

    private val bell = ChosenSound.of(NotificationSound.Bell)
    private val mine = ChosenSound.of(CustomSound("tone-1.wav", "My tone.wav"))

    @Test
    fun `a task keeps the sound it was created with`() {
        val database = database()
        try {
            runBlocking {
                val repository = RoomTaskRepository(database)
                val space = assertNotNull(repository.createSpace("Test", "TEST"))

                val created = assertNotNull(
                    repository.addTask(space.id, title = "Board meeting", dueSound = bell)
                )

                assertEquals(bell, created.dueSound, "as returned")
                assertEquals(
                    bell,
                    assertNotNull(repository.getTaskById(created.id)).dueSound,
                    "as read back out of the column",
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `a task keeps a sound of the user's own across a save`() {
        val database = database()
        try {
            runBlocking {
                val repository = RoomTaskRepository(database)
                val space = assertNotNull(repository.createSpace("Test", "TEST"))
                val created = assertNotNull(repository.addTask(space.id, title = "Board meeting"))

                repository.updateTask(created.copy(dueSound = mine))

                assertEquals(mine, assertNotNull(repository.getTaskById(created.id)).dueSound)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `a task that asks for nothing of its own says so`() {
        val database = database()
        try {
            runBlocking {
                val repository = RoomTaskRepository(database)
                val space = assertNotNull(repository.createSpace("Test", "TEST"))

                val created = assertNotNull(repository.addTask(space.id, title = "Ordinary"))

                assertEquals(ChosenSound.Deferred, created.dueSound)
                assertEquals(
                    ChosenSound.Deferred,
                    assertNotNull(repository.getTaskById(created.id)).dueSound,
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `exporting a space and importing it back keeps every due sound`() {
        val database = database()
        try {
            runBlocking {
                val repository = RoomTaskRepository(database)
                val from = assertNotNull(repository.createSpace("From", "FROM"))
                repository.addTask(from.id, title = "Board meeting", dueSound = bell)
                repository.addTask(from.id, title = "Rent", dueSound = mine)
                repository.addTask(from.id, title = "Ordinary")

                val exported = assertNotNull(repository.exportSpaceToJson(from.id))
                val restored = assertNotNull(repository.importSpaceFromJson(exported))

                assertEquals(
                    listOf(bell, ChosenSound.Deferred, mine), // by title: Board meeting, Ordinary, Rent
                    repository.getAllTasks(restored.id).sortedBy { it.title }.map { it.dueSound },
                    "a restored space is the space that was saved, down to this",
                )
            }
        } finally {
            database.close()
        }
    }
}
