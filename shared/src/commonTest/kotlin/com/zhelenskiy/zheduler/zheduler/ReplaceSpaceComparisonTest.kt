package com.zhelenskiy.zheduler.zheduler

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Replacing a space's contents with a snapshot, in both repositories at once.
 *
 * This is what "the server holds the space" rests on: the copy that arrives takes the place of
 * what was here, keeping the id and prefix everything else is keyed on. A divergence between the
 * two implementations here would mean the app behaves one way and every fast test another.
 */
@OptIn(ExperimentalTime::class)
class ReplaceSpaceComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    /** Runs [block] against every implementation, naming which one failed. */
    private fun eachRepository(block: suspend (TaskRepository) -> Unit) = runTest {
        repositories().forEach { repository ->
            try {
                block(repository)
            } catch (failure: AssertionError) {
                throw AssertionError("${repository::class.simpleName}: ${failure.message}", failure)
            }
        }
    }

    private suspend fun TaskRepository.spaceWithTasks(
        name: String,
        prefix: String,
        titles: List<String>,
    ): Space {
        val space = assertNotNull(createSpace(name, prefix))
        titles.forEach { title -> addTask(space.id, title = title) }
        return space
    }

    @Test
    fun `a replaced space keeps its id and prefix and takes the snapshot's contents`() = eachRepository { repository ->
        val target = repository.spaceWithTasks("Work", "WRK", listOf("old one", "old two"))
        val source = repository.spaceWithTasks("From the server", "SRV", listOf("new one"))
        val snapshot = assertNotNull(repository.exportSpaceToJson(source.id))

        assertTrue(repository.replaceSpaceFromJson(target.id, snapshot))

        val replaced = assertNotNull(repository.getSpaceById(target.id))
        assertEquals(target.id, replaced.id, "the id changed, so everything keyed on it is orphaned")
        assertEquals("WRK", replaced.idPrefix, "the prefix changed, so every task id changed with it")
        assertEquals("From the server", replaced.name, "the snapshot's name should win")

        val titles = repository.getAllTasks(target.id).map { it.title }
        assertEquals(listOf("new one"), titles)
        // And the ids are the target's, not the snapshot's.
        assertTrue(repository.getAllTasks(target.id).all { it.id.startsWith("WRK-") })
    }

    @Test
    fun `replacing a space that does not exist changes nothing`() = eachRepository { repository ->
        val source = repository.spaceWithTasks("Work", "WRK", listOf("one"))
        val snapshot = assertNotNull(repository.exportSpaceToJson(source.id))

        assertFalse(repository.replaceSpaceFromJson("space-never-existed", snapshot))
        assertEquals(1, repository.getAllSpaces().size)
    }

    @Test
    fun `a snapshot this build cannot read leaves the space alone`() = eachRepository { repository ->
        val target = repository.spaceWithTasks("Work", "WRK", listOf("keep me"))

        assertFalse(repository.replaceSpaceFromJson(target.id, "not a snapshot at all"))

        assertEquals(listOf("keep me"), repository.getAllTasks(target.id).map { it.title })
    }

    @Test
    fun `replacing empties what was there rather than merging`() = eachRepository { repository ->
        val target = repository.spaceWithTasks("Work", "WRK", listOf("a", "b", "c"))
        val source = assertNotNull(repository.createSpace("Empty", "EMP"))
        val snapshot = assertNotNull(repository.exportSpaceToJson(source.id))

        assertTrue(repository.replaceSpaceFromJson(target.id, snapshot))

        assertTrue(
            repository.getAllTasks(target.id).isEmpty(),
            "the old tasks survived, so the local copy is not the server's copy",
        )
    }

    @Test
    fun `the space's tags are replaced too`() = eachRepository { repository ->
        val target = assertNotNull(repository.createSpace("Work", "WRK"))
        repository.addTag(target.id, "gone-after")
        val source = assertNotNull(repository.createSpace("Server", "SRV"))
        repository.addTag(source.id, "from-the-server")
        val snapshot = assertNotNull(repository.exportSpaceToJson(source.id))

        assertTrue(repository.replaceSpaceFromJson(target.id, snapshot))

        assertEquals(setOf("from-the-server"), repository.getAllTags(target.id))
    }

    @Test
    fun `a task in another space that pointed into this one is detached`() = eachRepository { repository ->
        // The ids it named are not coming back, exactly as if the space had been deleted.
        val other = repository.spaceWithTasks("Other", "OTH", listOf("watcher"))
        val target = repository.spaceWithTasks("Work", "WRK", listOf("watched"))
        val watcher = repository.getAllTasks(other.id).single()
        val watched = repository.getAllTasks(target.id).single()
        repository.addConnection(watcher.id, watched.id, ConnectionType.RelatesTo)
        assertTrue(assertNotNull(repository.getTaskById(watcher.id)).connections.isNotEmpty())

        val source = assertNotNull(repository.createSpace("Server", "SRV"))
        val snapshot = assertNotNull(repository.exportSpaceToJson(source.id))
        assertTrue(repository.replaceSpaceFromJson(target.id, snapshot))

        assertTrue(
            assertNotNull(repository.getTaskById(watcher.id)).connections.none {
                it.targetTaskId == watched.id
            },
            "a connection was left pointing at a task that no longer exists",
        )
    }


    /**
     * The other half of the rule above, and the one that costs something to get wrong.
     *
     * A cloud space is replaced from its server on every ordinary refresh, and nearly every task
     * comes back under the very id it had. A task in another space that points at one of those has
     * lost nothing and must not be quietly cut loose — least of all repeatedly, in a space nobody
     * has open.
     */
    @Test
    fun `a task in another space keeps its link to one the snapshot brings back`() = eachRepository { repository ->
        val other = repository.spaceWithTasks("Other", "OTH", listOf("watcher"))
        val target = repository.spaceWithTasks("Work", "WRK", listOf("watched"))
        val watcher = repository.getAllTasks(other.id).single()
        val watched = repository.getAllTasks(target.id).single()
        repository.addConnection(watcher.id, watched.id, ConnectionType.RelatesTo)

        // The server's copy of the same space: the same task, so the same id comes back.
        val snapshot = assertNotNull(repository.exportSpaceToJson(target.id))
        assertTrue(repository.replaceSpaceFromJson(target.id, snapshot))

        assertTrue(
            assertNotNull(repository.getTaskById(watcher.id)).connections.any {
                it.targetTaskId == watched.id
            },
            "a refresh cut a link to a task that is still there",
        )
        assertNotNull(repository.getTaskById(watched.id), "the task itself should have come back")
    }


    /**
     * The outgoing direction of the same rule, and the more damaging one.
     *
     * A snapshot carries this space's links to tasks elsewhere, but those ids are not re-issued
     * with the rest. Dropping them would cut every outgoing link on an ordinary refresh — and
     * since the refreshed space is then uploaded, the loss would become what the server holds.
     */
    @Test
    fun `a link out of the space survives being replaced by its own snapshot`() = eachRepository { repository ->
        val other = repository.spaceWithTasks("Other", "OTH", listOf("watched"))
        val target = repository.spaceWithTasks("Work", "WRK", listOf("watcher"))
        val watched = repository.getAllTasks(other.id).single()
        val watcher = repository.getAllTasks(target.id).single()
        repository.addConnection(watcher.id, watched.id, ConnectionType.RelatesTo)

        val snapshot = assertNotNull(repository.exportSpaceToJson(target.id))
        assertTrue(repository.replaceSpaceFromJson(target.id, snapshot))

        assertTrue(
            assertNotNull(repository.getTaskById(watcher.id)).connections.any {
                it.targetTaskId == watched.id
            },
            "a refresh cut this space's own link to a task elsewhere",
        )
    }

    /**
     * An id the snapshot names that belongs to nobody here is still dropped.
     *
     * This is what importing a file written on another device has always done, and it has to keep
     * doing it: the id names a task that simply does not exist on this machine.
     */
    @Test
    fun `a link to a task this device does not have is dropped`() = eachRepository { repository ->
        val other = repository.spaceWithTasks("Other", "OTH", listOf("watched"))
        val target = repository.spaceWithTasks("Work", "WRK", listOf("watcher"))
        val watched = repository.getAllTasks(other.id).single()
        val watcher = repository.getAllTasks(target.id).single()
        repository.addConnection(watcher.id, watched.id, ConnectionType.RelatesTo)
        val snapshot = assertNotNull(repository.exportSpaceToJson(target.id))

        // The task at the far end goes away, so the snapshot now names an id nothing answers to.
        assertTrue(repository.deleteSpace(other.id))
        assertTrue(repository.replaceSpaceFromJson(target.id, snapshot))

        assertTrue(
            assertNotNull(repository.getTaskById(watcher.id)).connections.none {
                it.targetTaskId == watched.id
            },
            "a connection was restored to a task that no longer exists",
        )
    }

    @Test
    fun `replacing a space twice is not cumulative`() = eachRepository { repository ->
        val target = repository.spaceWithTasks("Work", "WRK", listOf("original"))
        val source = repository.spaceWithTasks("Server", "SRV", listOf("from server"))
        val snapshot = assertNotNull(repository.exportSpaceToJson(source.id))

        assertTrue(repository.replaceSpaceFromJson(target.id, snapshot))
        assertTrue(repository.replaceSpaceFromJson(target.id, snapshot))

        assertEquals(listOf("from server"), repository.getAllTasks(target.id).map { it.title })
    }

    @Test
    fun `other spaces are untouched`() = eachRepository { repository ->
        val bystander = repository.spaceWithTasks("Home", "HOM", listOf("mine"))
        val target = repository.spaceWithTasks("Work", "WRK", listOf("theirs"))
        val source = assertNotNull(repository.createSpace("Server", "SRV"))
        val snapshot = assertNotNull(repository.exportSpaceToJson(source.id))

        assertTrue(repository.replaceSpaceFromJson(target.id, snapshot))

        assertEquals(listOf("mine"), repository.getAllTasks(bystander.id).map { it.title })
        assertNotNull(repository.getSpaceById(bystander.id))
    }

    @Test
    fun `the next id carries on from the snapshot rather than from what was here`() = eachRepository { repository ->
        val target = repository.spaceWithTasks("Work", "WRK", listOf("a", "b", "c", "d", "e"))
        val source = repository.spaceWithTasks("Server", "SRV", listOf("only one"))
        val snapshot = assertNotNull(repository.exportSpaceToJson(source.id))

        assertTrue(repository.replaceSpaceFromJson(target.id, snapshot))
        val added = assertNotNull(repository.addTask(target.id, title = "next"))

        // The snapshot had one task, so the next id follows that one — not the five that were here.
        assertEquals("WRK-2", added.id)
        assertNull(repository.getTaskById("WRK-6"))
    }
}
