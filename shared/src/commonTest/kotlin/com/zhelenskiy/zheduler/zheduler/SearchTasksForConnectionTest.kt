@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.ExperimentalTime

/**
 * Tests for searchTasksForConnection method which provides SQL-based filtering
 * for the connection dialog. This method filters tasks by:
 * - Space ID
 * - Search query (ID or title, case-insensitive)
 * - Excluded task IDs (current task and already connected tasks)
 */
class InMemorySearchTasksForConnectionTest : SearchTasksForConnectionTest(), InMemoryRepositoryTest
class DatabaseSearchTasksForConnectionTest : SearchTasksForConnectionTest(), DatabaseRepositoryTest

abstract class SearchTasksForConnectionTest : AbstractRepositoryTest {

    // ==================== Basic Search Tests ====================

    @Test
    fun `searchTasksForConnection returns all tasks except current when query is empty`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "First Task")!!
        val task2 = repo.addTask(space.id, "Second Task")!!
        val task3 = repo.addTask(space.id, "Third Task")!!

        val results = repo.searchTasksForConnection(space.id, task1.id, "", persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(2, results.size, "Should return 2 tasks (excluding current)")
        assertTrue(results.any { it.id == task2.id }, "Should include task2")
        assertTrue(results.any { it.id == task3.id }, "Should include task3")
        assertTrue(results.none { it.id == task1.id }, "Should not include current task")
    }

    @Test
    fun `searchTasksForConnection excludes current task from results`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "Task One")!!
        val task2 = repo.addTask(space.id, "Task Two")!!

        val results = repo.searchTasksForConnection(space.id, task1.id, "Task", persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task2.id, results[0].id, "Should only return task2")
    }

    // ==================== Search by ID Tests ====================

    @Test
    fun `searchTasksForConnection filters by task ID`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "First Task")!!
        val task2 = repo.addTask(space.id, "Second Task")!!
        repo.addTask(space.id, "Third Task")

        // Search by part of task1's ID
        val idPart = task1.id.substring(0, 5)
        val results = repo.searchTasksForConnection(space.id, task2.id, idPart, persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task1.id, results[0].id, "Should find task by ID")
    }

    @Test
    fun `searchTasksForConnection ID search is case-insensitive`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "First Task")!!
        val task2 = repo.addTask(space.id, "Second Task")!!

        // Search with lowercase version of ID
        val results = repo.searchTasksForConnection(space.id, task2.id, task1.id.lowercase(), persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task1.id, results[0].id, "Should find task with case-insensitive ID search")
    }

    // ==================== Search by Title Tests ====================

    @Test
    fun `searchTasksForConnection filters by task title`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "Important Meeting")!!
        repo.addTask(space.id, "Code Review")
        val task3 = repo.addTask(space.id, "Another Meeting")!!

        val results = repo.searchTasksForConnection(space.id, task3.id, "Meeting", persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task1.id, results[0].id, "Should find tasks with 'Meeting' in title")
    }

    @Test
    fun `searchTasksForConnection title search is case-insensitive`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "Important MEETING")!!
        val task2 = repo.addTask(space.id, "Code Review")!!

        val results = repo.searchTasksForConnection(space.id, task2.id, "meeting", persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task1.id, results[0].id, "Should find task with case-insensitive title search")
    }

    @Test
    fun `searchTasksForConnection finds partial title matches`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "Review Pull Request")!!
        val task2 = repo.addTask(space.id, "Write Documentation")!!

        val results = repo.searchTasksForConnection(space.id, task2.id, "Pull", persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task1.id, results[0].id, "Should find task with partial title match")
    }

    // ==================== Combined Search Tests ====================

    @Test
    fun `searchTasksForConnection returns tasks matching either ID or title`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "Review Code")!!
        val task2 = repo.addTask(space.id, "Fix Bug")!!
        val task3 = repo.addTask(space.id, "Other Task")!!

        // Search for "TST" which appears in IDs
        val results = repo.searchTasksForConnection(space.id, task3.id, "TST", persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        // Should find tasks by ID (all tasks have TST prefix)
        assertTrue(results.size >= 2, "Should find multiple tasks with TST in ID")
        assertTrue(results.any { it.id == task1.id }, "Should include task1")
        assertTrue(results.any { it.id == task2.id }, "Should include task2")
    }

    // ==================== Exclude Task IDs Tests ====================

    @Test
    fun `searchTasksForConnection excludes specified task IDs`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "First Task")!!
        val task2 = repo.addTask(space.id, "Second Task")!!
        val task3 = repo.addTask(space.id, "Third Task")!!
        val task4 = repo.addTask(space.id, "Fourth Task")!!

        // Exclude task2 and task3
        val excludeIds = persistentSetOf(task2.id, task3.id)
        val results = repo.searchTasksForConnection(space.id, task1.id, "", excludeIds, ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task4.id, results[0].id, "Should only return task4")
    }

    @Test
    fun `searchTasksForConnection with search query and exclusions works together`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "Important Meeting")!!
        val task2 = repo.addTask(space.id, "Another Meeting")!!
        val task3 = repo.addTask(space.id, "Code Review")!!

        // Search for "Meeting" but exclude task2
        val results = repo.searchTasksForConnection(space.id, task3.id, "Meeting", persistentSetOf(task2.id), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task1.id, results[0].id, "Should find task1 but not task2")
    }

    // ==================== Space Isolation Tests ====================

    @Test
    fun `searchTasksForConnection only returns tasks from specified space`() = runTest {
        val repo = createEmptyRepository()
        val space1 = repo.createSpace("Space One", "SPA")!!
        val space2 = repo.createSpace("Space Two", "SPB")!!

        val task1 = repo.addTask(space1.id, "Task in Space 1")!!
        val task2 = repo.addTask(space2.id, "Task in Space 2")!!
        val task3 = repo.addTask(space1.id, "Another Task in Space 1")!!

        val results = repo.searchTasksForConnection(space1.id, task1.id, "", persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task3.id, results[0].id, "Should only return tasks from space1")
        assertTrue(results.none { it.id == task2.id }, "Should not return tasks from space2")
    }

    // ==================== Empty Results Tests ====================

    @Test
    fun `searchTasksForConnection returns empty list when no matches found`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "First Task")!!
        repo.addTask(space.id, "Second Task")

        val results = repo.searchTasksForConnection(space.id, task1.id, "NonExistentQuery", persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertTrue(results.isEmpty(), "Should return empty list when no matches found")
    }

    @Test
    fun `searchTasksForConnection returns empty list when all tasks excluded`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "First Task")!!
        val task2 = repo.addTask(space.id, "Second Task")!!

        // Exclude task2, and task1 is the current task
        val results = repo.searchTasksForConnection(space.id, task1.id, "", persistentSetOf(task2.id), ConnectionType.RelatesTo, persistentSetOf())

        assertTrue(results.isEmpty(), "Should return empty list when all tasks are excluded")
    }

    // ==================== Special Characters Tests ====================

    @Test
    fun `searchTasksForConnection handles special characters in search query`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "Task: Important!")!!
        val task2 = repo.addTask(space.id, "Normal Task")!!

        val results = repo.searchTasksForConnection(space.id, task2.id, "Important!", persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task1.id, results[0].id, "Should find task with special characters")
    }

    // ==================== Performance/Edge Case Tests ====================

    @Test
    fun `searchTasksForConnection works with single character search`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "Apple Task")!!
        val task2 = repo.addTask(space.id, "Banana Task")!!

        val results = repo.searchTasksForConnection(space.id, task2.id, "A", persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task1.id, results[0].id, "Should find task starting with A")
    }

    @Test
    fun `searchTasksForConnection returns results with connections loaded`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TST")!!

        val task1 = repo.addTask(space.id, "Task One")!!
        val task2 = repo.addTask(
            space.id,
            "Task Two",
            connections = persistentSetOf(TaskConnection(task1.id, ConnectionType.RelatesTo))
        )!!

        val results = repo.searchTasksForConnection(space.id, task1.id, "", persistentSetOf(), ConnectionType.RelatesTo, persistentSetOf())

        assertEquals(1, results.size)
        assertEquals(task2.id, results[0].id)
        assertEquals(1, results[0].connections.size, "Returned task should have connections loaded")
    }
}
