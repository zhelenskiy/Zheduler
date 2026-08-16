package com.zhelenskiy.zheduler.zheduler

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * `addConnection` must refuse anything that closes a dependency or subtask cycle, whichever end of
 * the relationship it is stated from.
 *
 * The reverse types are the interesting ones. `DependsOn` is checked by walking out from the other
 * task, so the source task's own edges are read from the database; `IsDependencyOf` and `ParentOf`
 * start the walk *at* the source task, which the check describes with the set it is handed — and
 * being handed nothing made every committed edge invisible.
 */
@OptIn(ExperimentalTime::class)
class ConnectionCycleComparisonTest {

    @AfterTest
    fun cleanup() = cleanupDatabaseTest()

    private suspend fun bothRepositories(): List<TaskRepository> =
        listOf(InMemoryTaskRepository(Clock.System), createDatabaseRepository(Clock.System))

    private suspend fun TaskRepository.twoTasks(): Pair<String, String> {
        val space = createSpace("Test", "TEST")!!
        val a = addTask(space.id, title = "A")!!
        val b = addTask(space.id, title = "B")!!
        return a.id to b.id
    }

    @Test
    fun `IsDependencyOf cannot close a cycle against a committed DependsOn`() = runTest {
        for (repository in bothRepositories()) {
            val (a, b) = repository.twoTasks()

            assertTrue(repository.addConnection(a, b, ConnectionType.DependsOn), "$repository: setup")
            assertFalse(
                repository.addConnection(a, b, ConnectionType.IsDependencyOf),
                "$repository: A already depends on B, so B depending on A is a cycle",
            )

            val connections = repository.getTaskById(a)!!.connections
            assertFalse(
                connections.any { it.targetTaskId == b && it.type == ConnectionType.IsDependencyOf },
                "$repository: the cyclic edge must not have been stored",
            )
        }
    }

    @Test
    fun `ParentOf cannot close a cycle against a committed SubtaskOf`() = runTest {
        for (repository in bothRepositories()) {
            val (a, b) = repository.twoTasks()

            assertTrue(repository.addConnection(a, b, ConnectionType.SubtaskOf), "$repository: setup")
            assertFalse(
                repository.addConnection(a, b, ConnectionType.ParentOf),
                "$repository: A is already a subtask of B, so B being a subtask of A is a cycle",
            )
        }
    }

    @Test
    fun `a longer chain is still caught from the reverse direction`() = runTest {
        for (repository in bothRepositories()) {
            val space = repository.createSpace("Test", "TEST")!!
            val a = repository.addTask(space.id, title = "A")!!.id
            val b = repository.addTask(space.id, title = "B")!!.id
            val c = repository.addTask(space.id, title = "C")!!.id

            assertTrue(repository.addConnection(a, b, ConnectionType.DependsOn))
            assertTrue(repository.addConnection(b, c, ConnectionType.DependsOn))

            // A -> B -> C already; C depending on A would close the loop.
            assertFalse(
                repository.addConnection(a, c, ConnectionType.IsDependencyOf),
                "$repository: A -> B -> C -> A is a cycle",
            )
        }
    }

    @Test
    fun `an edge that closes nothing is still allowed`() = runTest {
        for (repository in bothRepositories()) {
            val (a, b) = repository.twoTasks()
            assertTrue(
                repository.addConnection(a, b, ConnectionType.IsDependencyOf),
                "$repository: with no other edges this is not a cycle",
            )
        }
    }
}
