@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.ExperimentalTime

class InMemoryConcurrencyRepositoryTest : ConcurrencyRepositoryTest(), InMemoryRepositoryTest
class DatabaseConcurrencyRepositoryTest : ConcurrencyRepositoryTest(), DatabaseRepositoryTest

abstract class ConcurrencyRepositoryTest : AbstractRepositoryTest {

    @Test
    fun `concurrent task additions should generate unique IDs`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val numTasks = 100
        val dispatcher = Dispatchers.Default

        val taskIds = withContext(dispatcher) {
            (1..numTasks).map { i ->
                async {
                    repo.addTask(
                        spaceId = spaceId,
                        title = "Task $i",
                        description = "Description $i"
                    )?.id
                }
            }.awaitAll().filterNotNull()
        }

        // All tasks should have been created
        assertEquals(numTasks, taskIds.size, "Expected $numTasks tasks to be created")

        // All IDs should be unique
        assertEquals(numTasks, taskIds.toSet().size, "All task IDs should be unique")

        // Verify all tasks exist in repository
        val allTasks = repo.getAllTasks(spaceId)
        assertEquals(numTasks, allTasks.size, "Repository should contain all $numTasks tasks")
    }

    @Test
    fun `concurrent space creations should not create duplicates`() = runTest {
        val repo = createEmptyRepository()
        val numAttempts = 50
        val dispatcher = Dispatchers.Default

        // Try to create many spaces with the same prefix concurrently
        val results = withContext(dispatcher) {
            (1..numAttempts).map {
                async {
                    repo.createSpace("Test Space", "TEST")
                }
            }.awaitAll()
        }

        // Only one should succeed (prefix is unique)
        val successfulCreations = results.filterNotNull()
        assertEquals(1, successfulCreations.size, "Only one space should be created with same prefix")

        // Verify only one space exists
        val allSpaces = repo.getAllSpaces()
        assertEquals(1, allSpaces.size, "Repository should contain exactly one space")
    }

    @Test
    fun `concurrent updates to same task should not lose data`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(
            spaceId = spaceId,
            title = "Original",
            description = "Original Description"
        )!!

        val numUpdates = 50
        val dispatcher = Dispatchers.Default

        // Perform concurrent updates
        withContext(dispatcher) {
            (1..numUpdates).map { i ->
                async {
                    val currentTask = repo.getTaskById(task.id)!!
                    repo.updateTask(currentTask.copy(title = "Update $i"))
                }
            }.awaitAll()
        }

        // Task should still exist and have one of the titles
        val finalTask = repo.getTaskById(task.id)
        assertNotNull(finalTask)
        assertTrue(finalTask.title.startsWith("Update ") || finalTask.title == "Original")
    }

    @Test
    fun `concurrent status updates should maintain consistency`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(
            spaceId = spaceId,
            title = "Task",
            description = "Description"
        )!!

        val dispatcher = Dispatchers.Default
        val statuses = listOf(
            TaskStatus.Open,
            TaskStatus.InProgress,
            TaskStatus.Done
        )

        // Perform concurrent status updates
        withContext(dispatcher) {
            statuses.flatMap { status ->
                (1..10).map {
                    async {
                        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = status))
                    }
                }
            }.awaitAll()
        }

        // Task should still exist with a valid status
        val finalTask = repo.getTaskById(task.id)
        assertNotNull(finalTask)
        assertTrue(finalTask.status in statuses)
    }

    @Test
    fun `concurrent connection additions should not create duplicates`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId = spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId = spaceId, title = "Task 2")!!

        val numAttempts = 50
        val dispatcher = Dispatchers.Default

        // Try to add the same connection many times concurrently
        val results = withContext(dispatcher) {
            (1..numAttempts).map {
                async {
                    repo.addConnection(task1.id, task2.id, ConnectionType.DependsOn)
                }
            }.awaitAll()
        }

        // All should succeed (idempotent operation)
        assertTrue(results.all { it }, "All connection additions should succeed")

        // But only one connection should exist
        val task1Updated = repo.getTaskById(task1.id)!!
        val dependsOnConnections = task1Updated.connections.filter { it.type == ConnectionType.DependsOn }
        assertEquals(1, dependsOnConnections.size, "Only one DependsOn connection should exist")
    }

    @Test
    fun `concurrent deletes should not cause errors`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(
            spaceId = spaceId,
            title = "Task to delete"
        )!!

        val numAttempts = 50
        val dispatcher = Dispatchers.Default

        // Try to delete the same task many times concurrently
        val results = withContext(dispatcher) {
            (1..numAttempts).map {
                async {
                    repo.deleteTask(task.id)
                }
            }.awaitAll()
        }

        // Only one should succeed (first delete), rest should return false
        val successCount = results.count { it }
        assertEquals(1, successCount, "Only one delete should succeed")

        // Task should be gone
        assertNull(repo.getTaskById(task.id))
    }

    @Test
    fun `concurrent reads and writes should not corrupt data`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val dispatcher = Dispatchers.Default

        // Create some initial tasks
        repeat(10) { i ->
            repo.addTask(spaceId = spaceId, title = "Task $i")
        }

        // Perform concurrent reads and writes
        withContext(dispatcher) {
            val jobs = mutableListOf<Deferred<*>>()

            // Writers - add new tasks
            repeat(20) { i ->
                jobs.add(async {
                    repo.addTask(spaceId = spaceId, title = "New Task $i")
                })
            }

            // Readers - read all tasks
            repeat(30) {
                jobs.add(async {
                    repo.getAllTasks(spaceId)
                })
            }

            // Status updaters
            repeat(20) {
                jobs.add(async {
                    val tasks = repo.getAllTasks(spaceId)
                    if (tasks.isNotEmpty()) {
                        val randomTask = tasks.random()
                        repo.updateTask(repo.getTaskById(randomTask.id)!!.copy(status = TaskStatus.InProgress))
                    }
                })
            }

            jobs.awaitAll()
        }

        // Verify data integrity
        val allTasks = repo.getAllTasks(spaceId)
        assertTrue(allTasks.size >= 10, "Should have at least the initial 10 tasks")

        // All tasks should have valid data
        allTasks.forEach { task ->
            assertNotNull(task.id)
            assertNotNull(task.title)
            assertNotNull(task.status)
        }
    }

    @Test
    fun `concurrent space deletions should not cause errors`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TEST")!!

        // Add some tasks to the space
        repeat(5) { i ->
            repo.addTask(spaceId = space.id, title = "Task $i")
        }

        val numAttempts = 20
        val dispatcher = Dispatchers.Default

        // Try to delete the same space many times concurrently
        val results = withContext(dispatcher) {
            (1..numAttempts).map {
                async {
                    repo.deleteSpace(space.id)
                }
            }.awaitAll()
        }

        // Only one should succeed
        val successCount = results.count { it }
        assertEquals(1, successCount, "Only one space delete should succeed")

        // Space should be gone
        assertNull(repo.getSpaceById(space.id))
        assertEquals(0, repo.getAllSpaces().size)
    }
}
