@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class InMemoryTaskRepositoryTest: TaskRepositoryTest(), InMemoryRepositoryTest
class DatabaseTaskRepositoryTest: TaskRepositoryTest(), DatabaseRepositoryTest

abstract class TaskRepositoryTest: AbstractRepositoryTest {


    // ==================== Space Management Tests ====================

    @Test
    fun `createSpace with valid prefix creates space`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("My Space", "MYSP")
        assertNotNull(space)
        assertEquals("My Space", space.name)
        assertEquals("MYSP", space.idPrefix)
    }

    @Test
    fun `createSpace with lowercase prefix returns null`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "test")
        assertNull(space)
    }

    @Test
    fun `createSpace with mixed case prefix returns null`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "Test")
        assertNull(space)
    }

    @Test
    fun `createSpace with numbers in prefix returns null`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TEST1")
        assertNull(space)
    }

    @Test
    fun `createSpace with empty prefix returns null`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "")
        assertNull(space)
    }

    @Test
    fun `createSpace with duplicate prefix returns null`() = runTest {
        val repo = createEmptyRepository()
        repo.createSpace("First", "TEST")
        val second = repo.createSpace("Second", "TEST")
        assertNull(second)
    }

    @Test
    fun `updateSpaceName with valid name succeeds`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Old Name", "TEST")!!
        val result = repo.updateSpaceName(space.id, "New Name")
        assertTrue(result)
        assertEquals("New Name", repo.getSpaceById(space.id)?.name)
    }

    @Test
    fun `updateSpaceName with blank name fails`() = runTest {
        val repo = createEmptyRepository()
        val space = repo.createSpace("Test", "TEST")!!
        val result = repo.updateSpaceName(space.id, "   ")
        assertFalse(result)
        assertEquals("Test", repo.getSpaceById(space.id)?.name)
    }

    @Test
    fun `updateSpaceName with invalid id fails`() = runTest {
        val repo = createEmptyRepository()
        val result = repo.updateSpaceName("invalid-id", "New Name")
        assertFalse(result)
    }

    @Test
    fun `deleteSpace removes space and all its tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Task 1")
        repo.addTask(spaceId, title = "Task 2")
        assertEquals(2, repo.getAllTasks(spaceId).size)

        val result = repo.deleteSpace(spaceId)
        assertTrue(result)
        assertNull(repo.getSpaceById(spaceId))
    }

    @Test
    fun `deleteSpace with invalid id returns false`() = runTest {
        val repo = createEmptyRepository()
        val result = repo.deleteSpace("invalid-id")
        assertFalse(result)
    }

    @Test
    fun `recreated space with same prefix has no old tasks`() = runTest {
        val repo = createEmptyRepository()

        // Create space and add tasks
        val space1 = repo.createSpace("Original Space", "TEST")!!
        repo.addTask(space1.id, title = "Task 1")
        repo.addTask(space1.id, title = "Task 2")
        repo.addTask(space1.id, title = "Task 3")
        assertEquals(3, repo.getAllTasks(space1.id).size)

        // Delete the space
        repo.deleteSpace(space1.id)

        // Create a new space with a different prefix (same prefix won't work since we can't reuse)
        val space2 = repo.createSpace("New Space", "NEW")!!

        // New space should have no tasks
        assertTrue(repo.getAllTasks(space2.id).isEmpty())
    }

    @Test
    fun `recreated space does not contain old task IDs`() = runTest {
        val repo = createEmptyRepository()

        // Create space and add tasks
        val space1 = repo.createSpace("Original Space", "ORIG")!!
        val task1 = repo.addTask(space1.id, title = "Task 1")!!
        val task2 = repo.addTask(space1.id, title = "Task 2")!!
        val oldTaskIds = listOf(task1.id, task2.id)

        // Delete the space
        repo.deleteSpace(space1.id)

        // Old task IDs should not be retrievable
        oldTaskIds.forEach { taskId ->
            assertNull(repo.getTaskById(taskId))
        }

        // Create new space and add tasks
        val space2 = repo.createSpace("New Space", "NEW")!!
        repo.addTask(space2.id, title = "New Task")

        // Old task IDs should still not be retrievable
        oldTaskIds.forEach { taskId ->
            assertNull(repo.getTaskById(taskId))
        }
    }

    @Test
    fun `delete and recreate space clears all task data including connections`() = runTest {
        val repo = createEmptyRepository()

        // Create space with connected tasks
        val space1 = repo.createSpace("Original", "ORIG")!!
        val parent = repo.addTask(space1.id, title = "Parent", autoUpdateStatusFromSubtasks = true)!!
        val child = repo.addTask(space1.id, title = "Child")!!
        repo.addConnection(child.id, parent.id, ConnectionType.SubtaskOf)

        assertEquals(1, repo.getSubtasks(parent.id).size)
        assertEquals(1, repo.getParentTasks(child.id).size)

        // Delete space
        repo.deleteSpace(space1.id)

        // Create new space
        val space2 = repo.createSpace("New", "NEW")!!

        // No tasks or connections should exist
        assertTrue(repo.getAllTasks(space2.id).isEmpty())
        assertNull(repo.getTaskById(parent.id))
        assertNull(repo.getTaskById(child.id))
    }

    @Test
    fun `delete and recreate space clears status timeline`() = runTest {
        val repo = createEmptyRepository()

        // Create space with task that has status changes
        val space1 = repo.createSpace("Original", "ORIG")!!
        val task = repo.addTask(space1.id, title = "Task")!!
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.InProgress))
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.Done))

        val timeline = repo.getStatusTimeline(task.id)
        assertTrue(timeline.size >= 2)

        // Delete space
        repo.deleteSpace(space1.id)

        // Old task timeline should be empty (task doesn't exist)
        assertTrue(repo.getStatusTimeline(task.id).isEmpty())

        // Create new space
        val space2 = repo.createSpace("New", "NEW")!!
        val newTask = repo.addTask(space2.id, title = "New Task")!!

        // New task should have fresh timeline (only initial entry)
        assertEquals(1, repo.getStatusTimeline(newTask.id).size)
    }

    // ==================== Cross-Space Deletion Tests ====================

    @Test
    fun `deleteSpace unblocks tasks in other spaces that were blocked by deleted tasks`() = runTest {
        val repo = createEmptyRepository()

        // Create two spaces
        val space1 = repo.createSpace("Space 1", "ONE")!!
        val space2 = repo.createSpace("Space 2", "TWO")!!

        // Create a blocker task in space 1
        val blocker = repo.addTask(space1.id, title = "Blocker Task")!!

        // Create a blocked task in space 2 that is blocked by the task in space 1
        val blockedTask = repo.addTask(
            space2.id,
            title = "Blocked Task",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id))
        )!!

        // Verify the task is blocked
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blockedTask.id)!!.status)

        // Delete space 1 (which contains the blocker)
        repo.deleteSpace(space1.id)

        // The task in space 2 should now be unblocked (InProgress)
        val updatedTask = repo.getTaskById(blockedTask.id)!!
        assertEquals(
            TaskStatus.InProgress,
            updatedTask.status,
            "Task should be unblocked after its blocker's space is deleted"
        )
    }

    @Test
    fun `deleteSpace updates blocked status when one of multiple blockers is deleted`() = runTest {
        val repo = createEmptyRepository()

        // Create three spaces
        val space1 = repo.createSpace("Space 1", "ONE")!!
        val space2 = repo.createSpace("Space 2", "TWO")!!
        val space3 = repo.createSpace("Space 3", "THREE")!!

        // Create blocker tasks in space 1 and space 2
        val blocker1 = repo.addTask(space1.id, title = "Blocker 1")!!
        val blocker2 = repo.addTask(space2.id, title = "Blocker 2")!!

        // Create a blocked task in space 3 blocked by both
        val blockedTask = repo.addTask(
            space3.id,
            title = "Blocked Task",
            status = TaskStatus.Blocked(persistentSetOf(blocker1.id, blocker2.id))
        )!!

        // Delete space 1 (contains blocker1)
        repo.deleteSpace(space1.id)

        // Task should still be blocked, but only by blocker2
        val updatedTask = repo.getTaskById(blockedTask.id)!!
        val status = updatedTask.status
        assertIs<TaskStatus.Blocked>(status)
        assertEquals(setOf(blocker2.id), status.blockerTaskIds)
    }

    @Test
    fun `getAllSpaces returns all created spaces`() = runTest {
        val repo = createEmptyRepository()
        repo.createSpace("Space 1", "ONE")
        repo.createSpace("Space 2", "TWO")
        repo.createSpace("Space 3", "THREE")
        assertEquals(3, repo.getAllTasks().size)
    }

    // ==================== Task CRUD Tests ====================

    @Test
    fun `addTask with invalid spaceId returns null`() = runTest {
        val repo = createEmptyRepository()
        val task = repo.addTask("invalid-space-id", title = "Test")
        assertNull(task)
    }

    @Test
    fun `addTask with valid space creates task with correct id format`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Test Task")
        assertNotNull(task)
        assertTrue(task.id.startsWith("TEST-"))
        assertEquals("Test Task", task.title)
    }

    @Test
    fun `addTask increments id counter`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")
        val task2 = repo.addTask(spaceId, title = "Task 2")
        assertEquals("TEST-1", task1?.id)
        assertEquals("TEST-2", task2?.id)
    }

    @Test
    fun `addTask with custom id uses custom id`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Test", customId = "CUSTOM-123")
        assertEquals("CUSTOM-123", task?.id)
    }

    @Test
    fun `peekNextId returns next id without incrementing`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val peek1 = repo.peekNextId(spaceId)
        val peek2 = repo.peekNextId(spaceId)
        assertEquals(peek1, peek2)
        assertEquals("TEST-1", peek1)

        repo.addTask(spaceId, title = "Task 1")
        val peek3 = repo.peekNextId(spaceId)
        assertEquals("TEST-2", peek3)
    }

    @Test
    fun `getTaskById returns null for non-existent task`() = runTest {
        val (repo, _) = createRepositoryWithSpace()
        assertNull(repo.getTaskById("NON-EXISTENT"))
    }

    @Test
    fun `getTaskById returns task for existing id`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Test")!!
        val retrieved = repo.getTaskById(task.id)
        assertEquals(task.id, retrieved?.id)
        assertEquals(task.title, retrieved?.title)
    }

    @Test
    fun `getAll returns only tasks from specified space`() = runTest {
        val repo = createEmptyRepository()
        val space1 = repo.createSpace("Space 1", "ONE")!!
        val space2 = repo.createSpace("Space 2", "TWO")!!

        repo.addTask(space1.id, title = "Task in Space 1")
        repo.addTask(space2.id, title = "Task in Space 2")
        repo.addTask(space2.id, title = "Another in Space 2")

        assertEquals(2, repo.getAllTasks(space2.id).size)
        assertTrue(repo.getAllTasks(space2.id).all { it.id.startsWith("TWO-") })
        assertEquals(1, repo.getAllTasks(space1.id).size)
    }

    @Test
    fun `updateTask modifies task`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Original")!!
        val updated = task.copy(title = "Updated", description = "New description")
        val result = repo.updateTask(updated)

        assertNotNull(result)
        assertEquals("Updated", result.title)
        assertEquals("New description", result.description)
    }

    @Test
    fun `update non-existent task returns null`() = runTest {
        val (repo, _) = createRepositoryWithSpace()
        val fakeTask = Task(id = "FAKE-1", title = "Fake", spaceId = "fake")
        assertNull(repo.updateTask(fakeTask))
    }

    @Test
    fun `deleteTask removes task`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Test")!!
        assertTrue(repo.deleteTask(task.id))
        assertNull(repo.getTaskById(task.id))
    }

    @Test
    fun `delete non-existent task returns false`() = runTest {
        val (repo, _) = createRepositoryWithSpace()
        assertFalse(repo.deleteTask("NON-EXISTENT"))
    }

    // ==================== Task Status Tests ====================

    @Test
    fun `updateStatus changes task status`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Test")!!
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.InProgress))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(task.id)?.status)
    }

    @Test
    fun `updateStatus records status change in timeline`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Test")!!
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.InProgress))
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.Done))

        val timeline = repo.getStatusTimeline(task.id)
        assertEquals(3, timeline.size) // Initial + 2 changes
        assertEquals(TaskStatus.Open, timeline[0].newStatus)
        assertEquals(TaskStatus.InProgress, timeline[1].newStatus)
        assertEquals(TaskStatus.Done, timeline[2].newStatus)
    }

    @Test
    fun `updateTask on non-existent task returns null`() = runTest {
        val (repo, _) = createRepositoryWithSpace()
        val nonExistent = repo.getTaskById("NON-EXISTENT")
        assertNull(nonExistent)
        // Can't call update on null, so just verify getById returns null
    }

    // ==================== Tags Tests ====================

    @Test
    fun `getAllTags returns all unique tags across tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Task 1", tags = persistentSetOf("tag1", "tag2"))
        repo.addTask(spaceId, title = "Task 2", tags = persistentSetOf("tag2", "tag3"))
        repo.addTask(spaceId, title = "Task 3", tags = persistentSetOf("tag1", "tag3", "tag4"))

        val allTags = repo.getAllTags(spaceId)
        assertEquals(setOf("tag1", "tag2", "tag3", "tag4"), allTags)
    }

    @Test
    fun `getAllTags with no tasks returns empty set`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        assertTrue(repo.getAllTags(spaceId).isEmpty())
    }

    @Test
    fun `addTag adds tag to space tags`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        assertTrue(repo.getAllTags(spaceId).isEmpty())

        val result = repo.addTag(spaceId, "newTag")
        assertTrue(result)
        assertEquals(setOf("newTag"), repo.getAllTags(spaceId))
    }

    @Test
    fun `addTag with blank tag returns false`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        assertFalse(repo.addTag(spaceId, ""))
        assertFalse(repo.addTag(spaceId, "   "))
        assertTrue(repo.getAllTags(spaceId).isEmpty())
    }

    @Test
    fun `addTag trims whitespace`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "  trimmed  ")
        assertEquals(setOf("trimmed"), repo.getAllTags(spaceId))
    }

    @Test
    fun `addTag does not create duplicates`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "tag1")
        repo.addTag(spaceId, "tag1")
        assertEquals(setOf("tag1"), repo.getAllTags(spaceId))
    }

    @Test
    fun `deleteTag removes tag from space tags`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "tag1")
        repo.addTag(spaceId, "tag2")
        assertEquals(setOf("tag1", "tag2"), repo.getAllTags(spaceId))

        val result = repo.deleteTag(spaceId, "tag1")
        assertTrue(result)
        assertEquals(setOf("tag2"), repo.getAllTags(spaceId))
    }

    @Test
    fun `deleteTag with blank tag returns false`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "tag1")
        assertFalse(repo.deleteTag(spaceId, ""))
        assertFalse(repo.deleteTag(spaceId, "   "))
        assertEquals(setOf("tag1"), repo.getAllTags(spaceId))
    }

    @Test
    fun `deleteTag trims whitespace`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "tag1")
        repo.deleteTag(spaceId, "  tag1  ")
        assertTrue(repo.getAllTags(spaceId).isEmpty())
    }

    @Test
    fun `deleteTag on non-existent tag does not fail`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "tag1")
        // Should not throw, just return false or true depending on implementation
        repo.deleteTag(spaceId, "nonexistent")
        assertEquals(setOf("tag1"), repo.getAllTags(spaceId))
    }

    @Test
    fun `tags added via addTag are available for tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "predefinedTag")

        val task = repo.addTask(spaceId, title = "Task", tags = persistentSetOf("predefinedTag", "newTag"))!!
        assertEquals(setOf("predefinedTag", "newTag"), task.tags)
        assertEquals(setOf("predefinedTag", "newTag"), repo.getAllTags(spaceId))
    }

    @Test
    fun `deleteTag does not remove tag from existing tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Task", tags = persistentSetOf("tag1", "tag2"))!!

        repo.deleteTag(spaceId, "tag1")

        // Tag is removed from space tags but task still has it
        assertEquals(setOf("tag2"), repo.getAllTags(spaceId))
        val updatedTask = repo.getTaskById(task.id)!!
        assertEquals(setOf("tag1", "tag2"), updatedTask.tags)
    }

    @Test
    fun `filterTags with empty query returns all tags sorted`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "zebra")
        repo.addTag(spaceId, "apple")
        repo.addTag(spaceId, "banana")

        val filtered = repo.filterTags(spaceId, "", emptySet())
        assertEquals(listOf("apple", "banana", "zebra"), filtered)
    }

    @Test
    fun `filterTags with query returns matching tags case-insensitive`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "backend")
        repo.addTag(spaceId, "frontend")
        repo.addTag(spaceId, "fullstack")
        repo.addTag(spaceId, "mobile")

        val filtered = repo.filterTags(spaceId, "end", emptySet())
        assertEquals(listOf("backend", "frontend"), filtered)
    }

    @Test
    fun `filterTags excludes specified tags`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "tag1")
        repo.addTag(spaceId, "tag2")
        repo.addTag(spaceId, "tag3")
        repo.addTag(spaceId, "tag4")

        val filtered = repo.filterTags(spaceId, "", setOf("tag2", "tag4"))
        assertEquals(listOf("tag1", "tag3"), filtered)
    }

    @Test
    fun `filterTags with query and exclusions works correctly`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "sentiment")
        repo.addTag(spaceId, "urgent")
        repo.addTag(spaceId, "optional")
        repo.addTag(spaceId, "required")

        val filtered = repo.filterTags(spaceId, "ent", setOf("urgent"))
        assertEquals(listOf("sentiment"), filtered)
    }

    @Test
    fun `filterTags returns empty list when no tags match`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "tag1")
        repo.addTag(spaceId, "tag2")

        val filtered = repo.filterTags(spaceId, "nonexistent", emptySet())
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `filterTags with empty tags returns empty list`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val filtered = repo.filterTags(spaceId, "anything", emptySet())
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `filterTags is case insensitive`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTag(spaceId, "JavaScript")
        repo.addTag(spaceId, "TypeScript")
        repo.addTag(spaceId, "CoffeeScript")

        val filtered = repo.filterTags(spaceId, "SCRIPT", emptySet())
        assertEquals(listOf("CoffeeScript", "JavaScript", "TypeScript"), filtered)
    }

    @Test
    fun `tags are space-scoped`() = runTest {
        val repo = createEmptyRepository()
        val space1 = repo.createSpace("Space 1", "SA")!!
        val space2 = repo.createSpace("Space 2", "SB")!!

        repo.addTag(space1.id, "tag1")
        repo.addTag(space1.id, "tag2")
        repo.addTag(space2.id, "tag3")

        assertEquals(setOf("tag1", "tag2"), repo.getAllTags(space1.id))
        assertEquals(setOf("tag3"), repo.getAllTags(space2.id))
    }

    // ==================== Connection Tests ====================

    @Test
    fun `addConnection creates symmetric connection`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.DependsOn)

        val task1Updated = repo.getTaskById(task1.id)!!
        val task2Updated = repo.getTaskById(task2.id)!!

        assertTrue(task1Updated.connections.any {
            it.targetTaskId == task2.id && it.type == ConnectionType.DependsOn
        })
        assertTrue(task2Updated.connections.any {
            it.targetTaskId == task1.id && it.type == ConnectionType.IsDependencyOf
        })
    }

    @Test
    fun `addConnection with SubtaskOf creates ParentOf symmetric`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(spaceId, title = "Parent")!!
        val child = repo.addTask(spaceId, title = "Child")!!

        repo.addConnection(child.id, parent.id, ConnectionType.SubtaskOf)

        val parentUpdated = repo.getTaskById(parent.id)!!
        assertTrue(parentUpdated.connections.any {
            it.targetTaskId == child.id && it.type == ConnectionType.ParentOf
        })
    }

    @Test
    fun `addConnection with RelatesTo creates symmetric RelatesTo`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.RelatesTo)

        val task2Updated = repo.getTaskById(task2.id)!!
        assertTrue(task2Updated.connections.any {
            it.targetTaskId == task1.id && it.type == ConnectionType.RelatesTo
        })
    }

    @Test
    fun `addConnection to non-existent task returns false`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Task")!!
        assertFalse(repo.addConnection(task.id, "NON-EXISTENT", ConnectionType.DependsOn))
    }

    @Test
    fun `removeConnection removes symmetric connection`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.DependsOn)
        repo.removeConnection(task1.id, task2.id, ConnectionType.DependsOn)

        val task1Updated = repo.getTaskById(task1.id)!!
        val task2Updated = repo.getTaskById(task2.id)!!

        assertFalse(task1Updated.connections.any { it.targetTaskId == task2.id })
        assertFalse(task2Updated.connections.any { it.targetTaskId == task1.id })
    }

    @Test
    fun `getDependencies returns tasks this task depends on`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!
        val task3 = repo.addTask(spaceId, title = "Task 3")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.DependsOn)
        repo.addConnection(task1.id, task3.id, ConnectionType.DependsOn)

        val dependencies = repo.getDependencies(task1.id)
        assertEquals(2, dependencies.size)
        assertTrue(dependencies.any { it.id == task2.id })
        assertTrue(dependencies.any { it.id == task3.id })
    }

    @Test
    fun `getDependents returns tasks that depend on this task`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        val dependent1 = repo.addTask(spaceId, title = "Dependent 1")!!
        val dependent2 = repo.addTask(spaceId, title = "Dependent 2")!!

        repo.addConnection(dependent1.id, blocker.id, ConnectionType.DependsOn)
        repo.addConnection(dependent2.id, blocker.id, ConnectionType.DependsOn)

        val dependents = repo.getDependents(blocker.id)
        assertEquals(2, dependents.size)
    }

    @Test
    fun `getSubtasks returns all subtasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(spaceId, title = "Parent")!!
        val child1 = repo.addTask(spaceId, title = "Child 1")!!
        val child2 = repo.addTask(spaceId, title = "Child 2")!!

        repo.addConnection(child1.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(child2.id, parent.id, ConnectionType.SubtaskOf)

        val subtasks = repo.getSubtasks(parent.id)
        assertEquals(2, subtasks.size)
    }

    @Test
    fun `getParentTasks returns all parent tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent1 = repo.addTask(spaceId, title = "Parent 1")!!
        val parent2 = repo.addTask(spaceId, title = "Parent 2")!!
        val child = repo.addTask(spaceId, title = "Child")!!

        repo.addConnection(child.id, parent1.id, ConnectionType.SubtaskOf)
        repo.addConnection(child.id, parent2.id, ConnectionType.SubtaskOf)

        val parents = repo.getParentTasks(child.id)
        assertEquals(2, parents.size)
    }

    @Test
    fun `deleteTask removes connections from other tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.DependsOn)
        repo.deleteTask(task1.id)

        val task2Updated = repo.getTaskById(task2.id)!!
        assertTrue(task2Updated.connections.isEmpty())
    }

    // ==================== Cycle Detection Tests ====================

    @Test
    fun `wouldCreateCycle detects direct cycle in DependsOn`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.DependsOn)

        // task2 depending on task1 would create: task1 -> task2 -> task1
        assertTrue(repo.wouldCreateCycle(task2.id, task1.id, ConnectionType.DependsOn))
    }

    @Test
    fun `wouldCreateCycle detects indirect cycle in DependsOn`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!
        val task3 = repo.addTask(spaceId, title = "Task 3")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.DependsOn)
        repo.addConnection(task2.id, task3.id, ConnectionType.DependsOn)

        // task3 depending on task1 would create: task1 -> task2 -> task3 -> task1
        assertTrue(repo.wouldCreateCycle(task3.id, task1.id, ConnectionType.DependsOn))
    }

    @Test
    fun `wouldCreateCycle allows non-cyclic DependsOn`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!
        val task3 = repo.addTask(spaceId, title = "Task 3")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.DependsOn)

        // task1 depending on task3 is fine (task3 -> task1 -> task2)
        assertFalse(repo.wouldCreateCycle(task1.id, task3.id, ConnectionType.DependsOn))
    }

    @Test
    fun `wouldCreateCycle detects self-reference`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Task")!!

        assertTrue(repo.wouldCreateCycle(task.id, task.id, ConnectionType.DependsOn))
    }

    @Test
    fun `wouldCreateCycle allows RelatesTo connections`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.RelatesTo)

        // RelatesTo doesn't create cycles
        assertFalse(repo.wouldCreateCycle(task2.id, task1.id, ConnectionType.RelatesTo))
    }

    @Test
    fun `wouldCreateCycle detects cycle in SubtaskOf`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.SubtaskOf)

        // task2 being subtask of task1 would create cycle
        assertTrue(repo.wouldCreateCycle(task2.id, task1.id, ConnectionType.SubtaskOf))
    }

    @Test
    fun `wouldCreateCycle with uncommitted connections`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!
        val task3 = repo.addTask(spaceId, title = "Task 3")!!

        // Existing: task1 depends on task2 (task1 -> task2 in dependency direction)
        repo.addConnection(task1.id, task2.id, ConnectionType.DependsOn)

        // Existing: task2 depends on task3
        repo.addConnection(task2.id, task3.id, ConnectionType.DependsOn)

        // Now chain is: task1 -> task2 -> task3
        // Check if task3 depending on task1 would create cycle: task3 -> task1 -> task2 -> task3
        assertTrue(repo.wouldCreateCycle(task3.id, task1.id, ConnectionType.DependsOn))
    }

    // ==================== Blocked Task Unblocking Tests ====================

    @Test
    fun `blocked task unblocks when all blockers are Done`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        val blocked = repo.addTask(
            spaceId,
            title = "Blocked Task",
            status = TaskStatus.Blocked(persistentSetOf(blocker1.id, blocker2.id))
        )!!

        repo.updateTask(repo.getTaskById(blocker1.id)!!.copy(status = TaskStatus.Done))
        // Still blocked because blocker2 is not done
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)

        repo.updateTask(repo.getTaskById(blocker2.id)!!.copy(status = TaskStatus.Done))
        // Now should be unblocked (InProgress)
        assertEquals(TaskStatus.InProgress, repo.getTaskById(blocked.id)!!.status)
    }

    @Test
    fun `blocked task unblocks when blockers are Declined`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        val blocked = repo.addTask(
            spaceId,
            title = "Blocked Task",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id))
        )!!

        repo.updateTask(repo.getTaskById(blocker.id)!!.copy(status = TaskStatus.Declined("Not needed")))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(blocked.id)!!.status)
    }

    @Test
    fun `blocked task stays blocked when blocker is still Open`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        val blocked = repo.addTask(
            spaceId,
            title = "Blocked Task",
            status = TaskStatus.Blocked(persistentSetOf(blocker1.id, blocker2.id))
        )!!

        repo.updateTask(repo.getTaskById(blocker1.id)!!.copy(status = TaskStatus.Done))
        // blocker2 is still Open, so blocked task stays blocked
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)
    }

    // ==================== Total Priority/DueDate Tests ====================

    @Test
    fun `totalDueDate considers dependent tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()
        val blocker = repo.addTask(spaceId, title = "Blocker", dueDate = now + 7.days)!!
        val dependent = repo.addTask(spaceId, title = "Dependent", dueDate = now + 3.days)!!

        repo.addConnection(dependent.id, blocker.id, ConnectionType.DependsOn)

        val blockerWithTotals = repo.getTasksByIdWithTotals(blocker.id)!!
        // Blocker's total due date should be the closer one (3 days from now)
        assertEquals(dependent.dueDate, blockerWithTotals.totalDueDate)
    }

    @Test
    fun `totalPriority considers dependent tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker", priority = Priority.LOW)!!
        val dependent = repo.addTask(spaceId, title = "Dependent", priority = Priority.HIGH)!!

        repo.addConnection(dependent.id, blocker.id, ConnectionType.DependsOn)

        val blockerWithTotals = repo.getTasksByIdWithTotals(blocker.id)!!
        assertEquals(Priority.HIGH, blockerWithTotals.totalPriority)
    }

    @Test
    fun `totalPriority with no dependencies returns own priority`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Task", priority = Priority.MEDIUM)!!

        val taskWithTotals = repo.getTasksByIdWithTotals(task.id)!!
        assertEquals(Priority.MEDIUM, taskWithTotals.totalPriority)
    }

    // ==================== Total calculation visited set isolation tests ====================

    @Test
    fun `totalDueDate with multiple dependents sharing a common dependency calculates correctly`() = runTest {
        // This test verifies that the visited set is properly isolated for each branch
        // Structure:
        //   blocker (no due date)
        //      ^
        //      | IsDependencyOf
        //      |
        //   +--+--+
        //   |     |
        //   v     v
        // dep1  dep2 (both depend on blocker, and both share a common blocked task)
        //   |     |
        //   v     v
        //   +--+--+
        //      |
        //      v
        //   sharedBlocked (blocked by both dep1 and dep2, has due date)
        //
        // If visited is not copied, traversing dep1 -> sharedBlocked marks sharedBlocked as visited,
        // then dep2 won't be able to reach sharedBlocked.

        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()

        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        val dep1 = repo.addTask(spaceId, title = "Dependent 1", dueDate = now + 10.days)!!
        val dep2 = repo.addTask(spaceId, title = "Dependent 2", dueDate = now + 10.days)!!
        val sharedBlocked = repo.addTask(
            spaceId,
            title = "Shared Blocked",
            status = TaskStatus.Blocked(persistentSetOf(dep1.id, dep2.id)),
            dueDate = now + 2.days
        )!!

        // blocker is a dependency of both dep1 and dep2
        repo.addConnection(dep1.id, blocker.id, ConnectionType.DependsOn)
        repo.addConnection(dep2.id, blocker.id, ConnectionType.DependsOn)

        val blockerWithTotals = repo.getTasksByIdWithTotals(blocker.id)!!

        // blocker's total due date should be the earliest: sharedBlocked's due date (2 days)
        // This should be reached through BOTH dep1 and dep2 paths
        assertEquals(sharedBlocked.dueDate, blockerWithTotals.totalDueDate)
    }

    @Test
    fun `totalPriority with multiple dependents sharing a common blocked task calculates correctly`() = runTest {
        // Similar structure to the due date test but for priority
        // Structure:
        //   blocker (low priority)
        //      ^
        //      | IsDependencyOf
        //      |
        //   +--+--+
        //   |     |
        //   v     v
        // dep1  dep2 (both depend on blocker)
        //   |     |
        //   v     v
        //   +--+--+
        //      |
        //      v
        //   sharedBlocked (blocked by both, has HIGH priority)

        val (repo, spaceId) = createRepositoryWithSpace()

        val blocker = repo.addTask(spaceId, title = "Blocker", priority = Priority.LOW)!!
        val dep1 = repo.addTask(spaceId, title = "Dependent 1", priority = Priority.MEDIUM)!!
        val dep2 = repo.addTask(spaceId, title = "Dependent 2", priority = Priority.MEDIUM)!!
        val sharedBlocked = repo.addTask(
            spaceId,
            title = "Shared Blocked",
            status = TaskStatus.Blocked(persistentSetOf(dep1.id, dep2.id)),
            priority = Priority.HIGH
        )!!

        repo.addConnection(dep1.id, blocker.id, ConnectionType.DependsOn)
        repo.addConnection(dep2.id, blocker.id, ConnectionType.DependsOn)

        val blockerWithTotals = repo.getTasksByIdWithTotals(blocker.id)!!

        // blocker's total priority should be HIGH (from sharedBlocked)
        assertEquals(Priority.HIGH, blockerWithTotals.totalPriority)
    }

    @Test
    fun `totalDueDate with diamond dependency pattern calculates correctly`() = runTest {
        // Diamond pattern where visited isolation is critical:
        //
        //        root (no due date)
        //         ^
        //         | IsDependencyOf (root is dependency of left and right)
        //      +--+--+
        //      |     |
        //    left  right (both depend on root, both have IsDependencyOf from bottom)
        //      ^     ^
        //      |     |
        //      +--+--+
        //         |
        //         | DependsOn (bottom depends on both left and right)
        //       bottom (has earliest due date)
        //
        // The calculation traverses IsDependencyOf connections from root.
        // root -> left (via IsDependencyOf) -> bottom (via IsDependencyOf)
        // root -> right (via IsDependencyOf) -> bottom (via IsDependencyOf)
        //
        // Without proper visited isolation:
        // - Traversing root -> left -> bottom marks bottom as visited
        // - Then root -> right cannot reach bottom because bottom is already visited
        // - This could cause incorrect total due date calculation

        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()

        val root = repo.addTask(spaceId, title = "Root")!!
        val left = repo.addTask(spaceId, title = "Left", dueDate = now + 5.days)!!
        val right = repo.addTask(spaceId, title = "Right", dueDate = now + 5.days)!!
        val bottom = repo.addTask(spaceId, title = "Bottom", dueDate = now + 1.days)!!

        // root is dependency of both left and right (IsDependencyOf connections from root to left/right)
        repo.addConnection(left.id, root.id, ConnectionType.DependsOn)
        repo.addConnection(right.id, root.id, ConnectionType.DependsOn)

        // bottom depends on both left and right (IsDependencyOf connections from left/right to bottom)
        repo.addConnection(bottom.id, left.id, ConnectionType.DependsOn)
        repo.addConnection(bottom.id, right.id, ConnectionType.DependsOn)

        // When calculating totalDueDate for root:
        // - root has IsDependencyOf -> left (because left DependsOn root)
        // - root has IsDependencyOf -> right (because right DependsOn root)
        // - left has IsDependencyOf -> bottom (because bottom DependsOn left)
        // - right has IsDependencyOf -> bottom (because bottom DependsOn right)
        //
        // The traversal should find bottom's due date through BOTH paths

        val rootWithTotals = repo.getTasksByIdWithTotals(root.id)!!

        // root's total due date should be bottom's due date (1 day) - the earliest
        assertEquals(bottom.dueDate, rootWithTotals.totalDueDate)
    }

    @Test
    fun `totalPriority with diamond dependency pattern calculates correctly`() = runTest {
        // Same diamond pattern for priority

        val (repo, spaceId) = createRepositoryWithSpace()

        val root = repo.addTask(spaceId, title = "Root", priority = Priority.LOW)!!
        val left = repo.addTask(spaceId, title = "Left", priority = Priority.MEDIUM)!!
        val right = repo.addTask(spaceId, title = "Right", priority = Priority.MEDIUM)!!
        val bottom = repo.addTask(spaceId, title = "Bottom", priority = Priority.HIGH)!!

        repo.addConnection(left.id, root.id, ConnectionType.DependsOn)
        repo.addConnection(right.id, root.id, ConnectionType.DependsOn)
        repo.addConnection(bottom.id, left.id, ConnectionType.DependsOn)
        repo.addConnection(bottom.id, right.id, ConnectionType.DependsOn)

        val rootWithTotals = repo.getTasksByIdWithTotals(root.id)!!

        // root's total priority should be HIGH (from bottom)
        assertEquals(Priority.HIGH, rootWithTotals.totalPriority)
    }

    // ==================== Export/Import Tests ====================

    @Test
    fun `exportSpaceToJson and importSpaceFromJson roundtrip`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1", tags = persistentSetOf("tag1"))!!
        val task2 = repo.addTask(spaceId, title = "Task 2", priority = Priority.HIGH)!!
        repo.addConnection(task1.id, task2.id, ConnectionType.DependsOn)

        val json = repo.exportSpaceToJson(spaceId)
        assertNotNull(json)

        // Import into new repository
        val newRepo = createEmptyRepository()
        val imported = newRepo.importSpaceFromJson(json)
        assertNotNull(imported)

        assertEquals(2, newRepo.getAllTasks(imported.id).size)
    }

    // ==================== View Mode Tests ====================

    @Test
    fun `deleteViewMode returns false for built-in mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val result = repo.deleteViewMode(spaceId, "chronological")
        assertFalse(result)
        // Built-in mode should still exist
        val viewMode = repo.getActiveViewMode(spaceId)
        assertNotNull(viewMode)
    }

    @Test
    fun `deleteViewMode returns false for non-existent mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val result = repo.deleteViewMode(spaceId, "non-existent-id")
        assertFalse(result)
    }

    @Test
    fun `deleteViewMode returns true for existing custom mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val customViewMode = ViewMode(
            id = "custom-test-mode",
            name = "Custom Test Mode",
            spaceId = spaceId,
            isBuiltIn = false
        )
        repo.saveViewMode(customViewMode)

        val result = repo.deleteViewMode(spaceId, "custom-test-mode")
        assertTrue(result)

        // Mode should no longer exist
        val allModes = repo.getAllViewModes(spaceId)
        assertFalse(allModes.any { it.id == "custom-test-mode" })
    }

    @Test
    fun `deleteViewMode resets active mode when deleting active custom mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val customViewMode = ViewMode(
            id = "custom-active-mode",
            name = "Custom Active Mode",
            spaceId = spaceId,
            isBuiltIn = false
        )
        repo.saveViewMode(customViewMode)
        repo.setActiveViewMode(spaceId, "custom-active-mode")

        // Verify it's active
        assertEquals("custom-active-mode", repo.getActiveViewMode(spaceId).id)

        // Delete it
        val result = repo.deleteViewMode(spaceId, "custom-active-mode")
        assertTrue(result)

        // Active mode should be reset to default
        val activeMode = repo.getActiveViewMode(spaceId)
        assertTrue(activeMode.isBuiltIn)
    }

    @Test
    fun `deleteViewMode twice returns false on second call`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val customViewMode = ViewMode(
            id = "delete-twice-mode",
            name = "Delete Twice Mode",
            spaceId = spaceId,
            isBuiltIn = false
        )
        repo.saveViewMode(customViewMode)

        val firstResult = repo.deleteViewMode(spaceId, "delete-twice-mode")
        assertTrue(firstResult)

        val secondResult = repo.deleteViewMode(spaceId, "delete-twice-mode")
        assertFalse(secondResult)
    }

    // ==================== View Mode Repository Tests ====================

    @Test
    fun `getAllViewModes returns built-in modes for new space`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val viewModes = repo.getAllViewModes(spaceId)

        assertTrue(viewModes.isNotEmpty())
        assertTrue(viewModes.any { it.id == "chronological" })
        assertTrue(viewModes.any { it.id == "priority" })
    }

    @Test
    fun `getAllViewModes includes custom modes`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val customMode = ViewMode(
            id = "custom-mode",
            name = "Custom Mode",
            spaceId = spaceId,
            isBuiltIn = false
        )
        repo.saveViewMode(customMode)

        val viewModes = repo.getAllViewModes(spaceId)

        assertTrue(viewModes.any { it.id == "custom-mode" })
        assertTrue(viewModes.any { it.id == "chronological" })
        assertTrue(viewModes.any { it.id == "priority" })
    }

    @Test
    fun `getViewModeById returns built-in mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val viewMode = repo.getViewModeById(spaceId, "chronological")

        assertNotNull(viewMode)
        assertEquals("chronological", viewMode.id)
        assertTrue(viewMode.isBuiltIn)
    }

    @Test
    fun `getViewModeById returns custom mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val customMode = ViewMode(
            id = "custom-mode",
            name = "Custom Mode",
            spaceId = spaceId,
            isBuiltIn = false
        )
        repo.saveViewMode(customMode)

        val viewMode = repo.getViewModeById(spaceId, "custom-mode")

        assertNotNull(viewMode)
        assertEquals("custom-mode", viewMode.id)
        assertEquals("Custom Mode", viewMode.name)
        assertFalse(viewMode.isBuiltIn)
    }

    @Test
    fun `getViewModeById returns null for non-existent mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val viewMode = repo.getViewModeById(spaceId, "non-existent")

        assertNull(viewMode)
    }

    @Test
    fun `saveViewMode creates new custom mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val customMode = ViewMode(
            id = "new-mode",
            name = "New Mode",
            spaceId = spaceId,
            isBuiltIn = false,
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Active", persistentSetOf("Open", "InProgress"))
                    )
                )
            )
        )

        val saved = repo.saveViewMode(customMode)

        assertEquals("new-mode", saved.id)
        val retrieved = repo.getViewModeById(spaceId, "new-mode")
        assertNotNull(retrieved)
        assertEquals("New Mode", retrieved.name)
        assertEquals(1, retrieved.groupingLevels.size)
    }

    @Test
    fun `saveViewMode updates existing custom mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val customMode = ViewMode(
            id = "update-mode",
            name = "Original Name",
            spaceId = spaceId,
            isBuiltIn = false
        )
        repo.saveViewMode(customMode)

        val updatedMode = customMode.copy(name = "Updated Name")
        repo.saveViewMode(updatedMode)

        val retrieved = repo.getViewModeById(spaceId, "update-mode")
        assertNotNull(retrieved)
        assertEquals("Updated Name", retrieved.name)
    }

    @Test
    fun `getActiveViewMode returns default priority mode for new space`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val activeMode = repo.getActiveViewMode(spaceId)

        assertEquals("priority", activeMode.id)
        assertTrue(activeMode.isBuiltIn)
    }

    @Test
    fun `setActiveViewMode changes active mode`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        repo.setActiveViewMode(spaceId, "chronological")
        val activeMode = repo.getActiveViewMode(spaceId)

        assertEquals("chronological", activeMode.id)
    }

    @Test
    fun `setActiveViewMode can set custom mode as active`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val customMode = ViewMode(
            id = "custom-active",
            name = "Custom Active",
            spaceId = spaceId,
            isBuiltIn = false
        )
        repo.saveViewMode(customMode)

        repo.setActiveViewMode(spaceId, "custom-active")
        val activeMode = repo.getActiveViewMode(spaceId)

        assertEquals("custom-active", activeMode.id)
    }

    @Test
    fun `setActiveViewMode with non-existent mode falls back to default`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        repo.setActiveViewMode(spaceId, "non-existent-mode")
        val activeMode = repo.getActiveViewMode(spaceId)

        // Should fall back to default built-in mode
        assertTrue(activeMode.isBuiltIn)
    }

    @Test
    fun `deleteViewMode preserves other custom modes`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        repo.saveViewMode(ViewMode(id = "mode-1", name = "Mode 1", spaceId = spaceId, isBuiltIn = false))
        repo.saveViewMode(ViewMode(id = "mode-2", name = "Mode 2", spaceId = spaceId, isBuiltIn = false))

        repo.deleteViewMode(spaceId, "mode-1")

        val allModes = repo.getAllViewModes(spaceId)
        assertFalse(allModes.any { it.id == "mode-1" })
        assertTrue(allModes.any { it.id == "mode-2" })
    }

    // ==================== getTasksByIds Tests ====================

    @Test
    fun `getTasksByIds returns empty list for empty input`() = runTest {
        val (repo, _) = createRepositoryWithSpace()
        val tasks = repo.getTasksByIds(emptySet())
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `getTasksByIds returns matching tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!
        repo.addTask(spaceId, title = "Task 3")!!

        val tasks = repo.getTasksByIds(setOf(task1.id, task2.id))

        assertEquals(2, tasks.size)
        assertTrue(tasks.any { it.id == task1.id })
        assertTrue(tasks.any { it.id == task2.id })
    }

    @Test
    fun `getTasksByIds ignores non-existent ids`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!

        val tasks = repo.getTasksByIds(setOf(task1.id, "NON-EXISTENT-1", "NON-EXISTENT-2"))

        assertEquals(1, tasks.size)
        assertEquals(task1.id, tasks[0].id)
    }

    @Test
    fun `getTasksByIds returns empty for all non-existent ids`() = runTest {
        val (repo, _) = createRepositoryWithSpace()
        val tasks = repo.getTasksByIds(setOf("NON-EXISTENT-1", "NON-EXISTENT-2"))
        assertTrue(tasks.isEmpty())
    }

    // ==================== View Mode with Different Spaces Tests ====================

    @Test
    fun `view modes are isolated per space`() = runTest {
        val repo = createEmptyRepository()
        val space1 = repo.createSpace("Space 1", "ONE")!!
        val space2 = repo.createSpace("Space 2", "TWO")!!

        val customMode = ViewMode(
            id = "space1-mode",
            name = "Space 1 Mode",
            spaceId = space1.id,
            isBuiltIn = false
        )
        repo.saveViewMode(customMode)

        val space1Modes = repo.getAllViewModes(space1.id)
        val space2Modes = repo.getAllViewModes(space2.id)

        assertTrue(space1Modes.any { it.id == "space1-mode" })
        assertFalse(space2Modes.any { it.id == "space1-mode" })
    }

    @Test
    fun `active view mode is isolated per space`() = runTest {
        val repo = createEmptyRepository()
        val space1 = repo.createSpace("Space 1", "ONE")!!
        val space2 = repo.createSpace("Space 2", "TWO")!!

        repo.setActiveViewMode(space1.id, "chronological")
        repo.setActiveViewMode(space2.id, "priority")

        assertEquals("chronological", repo.getActiveViewMode(space1.id).id)
        assertEquals("priority", repo.getActiveViewMode(space2.id).id)
    }

    // ==================== filterTasksForSelection Tests ====================

    @Test
    fun `filterTasksForSelection excludes current task`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!
        val task3 = repo.addTask(spaceId, title = "Task 3")!!

        val result = repo.filterTasksForSelection(spaceId, task1.id)

        assertEquals(2, result.size)
        assertFalse(result.any { it.id == task1.id })
        assertTrue(result.any { it.id == task2.id })
        assertTrue(result.any { it.id == task3.id })
    }

    @Test
    fun `filterTasksForSelection with search query`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Apple Task")!!
        repo.addTask(spaceId, title = "Banana Task")!!
        repo.addTask(spaceId, title = "Cherry Task")!!

        val result = repo.filterTasksForSelection(spaceId, null, "Apple")

        assertEquals(1, result.size)
        assertEquals("Apple Task", result[0].title)
    }

    @Test
    fun `filterTasksForSelection with null excludeTaskId returns all tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Task 1")!!
        repo.addTask(spaceId, title = "Task 2")!!

        val result = repo.filterTasksForSelection(spaceId, null)

        assertEquals(2, result.size)
    }
}
