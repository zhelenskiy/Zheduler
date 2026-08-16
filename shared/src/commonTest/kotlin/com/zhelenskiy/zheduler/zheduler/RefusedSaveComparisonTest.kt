package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A save the repository refuses must leave the data exactly as it was.
 *
 * The cycle check can only run once the edit is far enough along to be judged, so both
 * repositories are part way through by the time they say no. The database is inside a transaction
 * and rolls back; the in-memory one had nothing to roll back, and kept the half-applied edit.
 */
@OptIn(ExperimentalTime::class)
class RefusedSaveComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun repositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    /** Every task in [spaceId] as (id, connections, status), for comparing whole-repository state. */
    private suspend fun TaskRepository.snapshot(spaceId: String) =
        getAllTasks(spaceId).sortedBy { it.id }.map { Triple(it.id, it.connections, it.status) }

    @Test
    fun `a save refused for a cycle changes nothing`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val parent = repository.addTask(space.id, title = "parent")!!
            val other = repository.addTask(space.id, title = "other")!!
            val child = repository.addTask(space.id, title = "child")!!
            repository.addConnection(child.id, parent.id, ConnectionType.SubtaskOf)

            val before = repository.snapshot(space.id)

            // The form hands over its whole connection set at once: this one drops the real link
            // to the parent and asks to be both parent and subtask of another task. The loop is
            // refused — and the link it dropped on the way must not go with it.
            assertFailsWith<IllegalArgumentException>("$repository: a cycle was accepted") {
                repository.updateTask(
                    repository.getTaskById(child.id)!!.copy(
                        connections = persistentSetOf(
                            TaskConnection(other.id, ConnectionType.SubtaskOf),
                            TaskConnection(other.id, ConnectionType.ParentOf),
                        )
                    )
                )
            }

            assertEquals(before, repository.snapshot(space.id), "$repository: the refused save left a mark")
        }
    }

    @Test
    fun `a task refused at creation is not created`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val other = repository.addTask(space.id, title = "other")!!
            val before = repository.snapshot(space.id)

            // Both halves of a loop at once, which the picker cannot catch: the task being created
            // has no id yet, so every candidate it offered looked safe.
            assertFailsWith<IllegalArgumentException>("$repository: a cycle was accepted") {
                repository.addTask(
                    space.id,
                    title = "impossible",
                    connections = persistentSetOf(
                        TaskConnection(other.id, ConnectionType.SubtaskOf),
                        TaskConnection(other.id, ConnectionType.ParentOf),
                    ),
                )
            }

            assertEquals(before, repository.snapshot(space.id), "$repository: the refused task is still there")
        }
    }

    @Test
    fun `a refused creation does not consume the next id`() = runTest {
        for (repository in repositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val other = repository.addTask(space.id, title = "other")!!

            runCatching {
                repository.addTask(
                    space.id,
                    title = "impossible",
                    connections = persistentSetOf(
                        TaskConnection(other.id, ConnectionType.SubtaskOf),
                        TaskConnection(other.id, ConnectionType.ParentOf),
                    ),
                )
            }

            val next = repository.addTask(space.id, title = "next")!!
            assertEquals("TEST-2", next.id, "$repository: the refused save took an id with it")
        }
    }
}
