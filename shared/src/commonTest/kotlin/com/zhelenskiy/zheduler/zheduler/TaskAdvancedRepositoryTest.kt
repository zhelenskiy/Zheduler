@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class InMemoryTaskAdvancedRepositoryTest: TaskAdvancedRepositoryTest(), InMemoryRepositoryTest
class DatabaseTaskAdvancedRepositoryTest: TaskAdvancedRepositoryTest(), DatabaseRepositoryTest

/**
 * Advanced tests for TaskRepository covering export/import, grouping, state management, and recurrence integration.
 */
abstract class TaskAdvancedRepositoryTest: AbstractRepositoryTest {


    // ==================== Export/Import Tests ====================

    @Test
    fun `exportSpaceToJson returns null for non-existent space`() = runTest {
        val (repo, _) = createRepositoryWithSpace()
        assertNull(repo.exportSpaceToJson("non-existent-id"))
    }

    @Test
    fun `exportSpaceToJson returns valid JSON`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Task 1", tags = setOf("tag1", "tag2"))
        repo.addTask(spaceId, title = "Task 2", priority = Priority.HIGH)

        val json = repo.exportSpaceToJson(spaceId)

        assertNotNull(json)
        assertTrue(json.contains("TEST"))
        assertTrue(json.contains("Task 1"))
        assertTrue(json.contains("Task 2"))
    }

    @Test
    fun `exportSpaceToJson with prettyPrint formats nicely`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Task")

        val compactJson = repo.exportSpaceToJson(spaceId, prettyPrint = false)
        val prettyJson = repo.exportSpaceToJson(spaceId, prettyPrint = true)

        assertNotNull(compactJson)
        assertNotNull(prettyJson)
        assertTrue(prettyJson!!.length > compactJson!!.length)
        assertTrue(prettyJson.contains("\n"))
    }

    @Test
    fun `importSpaceFromJson returns null for invalid JSON`() = runTest {
        val repo = createEmptyRepository()
        assertNull(repo.importSpaceFromJson("not valid json"))
        assertNull(repo.importSpaceFromJson("{}"))
        assertNull(repo.importSpaceFromJson("{\"space\": null}"))
    }

    @Test
    fun `importSpaceFromJson creates new space`() = runTest {
        // Create a fresh repository without the TEST prefix
        val repo = createEmptyRepository()
        val space = repo.createSpace("Other", "OTHER")!!
        val spaceId = space.id
        repo.addTask(spaceId, title = "Original Task")

        val json = repo.exportSpaceToJson(spaceId)!!

        // Import into repository that doesn't have this prefix
        val newRepo = createEmptyRepository()
        val imported = newRepo.importSpaceFromJson(json)

        assertNotNull(imported)
        assertEquals("OTHER", imported.idPrefix)
        assertEquals(1, newRepo.getAllTasks().size)
    }

    @Test
    fun `importSpaceFromJson preserves task connections`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Parent")!!
        val task2 = repo.addTask(spaceId, title = "Child")!!
        repo.addConnection(task2.id, task1.id, ConnectionType.SubtaskOf)

        val json = repo.exportSpaceToJson(spaceId)!!

        // Import to new repository
        val newRepo = createEmptyRepository()
        val imported = newRepo.importSpaceFromJson(json)
        assertNotNull(imported)

        val importedTasks = newRepo.getAllTasks(imported.id)
        assertEquals(2, importedTasks.size)

        val importedChild = importedTasks.find { it.title == "Child" }!!
        assertTrue(importedChild.connections.any { it.type == ConnectionType.SubtaskOf })
    }

    @Test
    fun `importSpaceFromJson preserves status timeline`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Task")!!
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.InProgress))
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.Done))

        val json = repo.exportSpaceToJson(spaceId)!!

        val newRepo = createEmptyRepository()
        val imported = newRepo.importSpaceFromJson(json)!!

        val importedTask = newRepo.getAllTasks(imported.id).first()
        val timeline = newRepo.getStatusTimeline(importedTask.id)

        assertTrue(timeline.size >= 2) // Should have status history
    }

    @Test
    fun `importSpaceFromJson remaps blocked status blocker IDs`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        repo.addTask(spaceId, title = "Blocked", status = TaskStatus.Blocked(setOf(blocker.id)))

        val json = repo.exportSpaceToJson(spaceId)!!

        val newRepo = createEmptyRepository()
        val imported = newRepo.importSpaceFromJson(json)!!

        val blockedTask = newRepo.getAllTasks(imported.id).find { it.title == "Blocked" }!!
        val status = blockedTask.status
        assertIs<TaskStatus.Blocked>(status)

        // Blocker ID should be remapped to new prefix
        assertTrue(status.blockerTaskIds.isNotEmpty())
        assertTrue(status.blockerTaskIds.first().startsWith(imported.idPrefix))
    }

    // ==================== State Management Tests ====================

    @Test
    fun `getFilterState returns default for new space`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val filterState = repo.getFilterState(spaceId)
        assertFalse(filterState.hasActiveFilters)
    }

    @Test
    fun `saveFilterState and getFilterState roundtrip`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val criteria = TaskFilterCriteria(
            searchQuery = "test",
            statusFilters = setOf(TaskStatus.Open),
            blockedByTaskIds = "TASK-1, TASK-2",
            blockedByComment = "waiting",
            declinedReason = "not needed"
        )
        repo.saveFilterState(spaceId, criteria)

        val retrieved = repo.getFilterState(spaceId)
        assertEquals("test", retrieved.searchQuery)
        assertEquals(setOf(TaskStatus.Open), retrieved.statusFilters)
        assertEquals("TASK-1, TASK-2", retrieved.blockedByTaskIds)
        assertEquals("waiting", retrieved.blockedByComment)
        assertEquals("not needed", retrieved.declinedReason)
    }

    @Test
    fun `getViewMode returns Priority by default`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        assertEquals("Priority", repo.getViewMode(spaceId))
    }

    @Test
    fun `saveViewMode and getViewMode roundtrip`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        repo.saveViewMode(spaceId, "Chronological")
        assertEquals("Chronological", repo.getViewMode(spaceId))
    }

    @Test
    fun `getFilterPanelOpen returns false by default`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        assertFalse(repo.getFilterPanelOpen(spaceId))
    }

    @Test
    fun `saveFilterPanelOpen and getFilterPanelOpen roundtrip`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        repo.saveFilterPanelOpen(spaceId, true)
        assertTrue(repo.getFilterPanelOpen(spaceId))
    }

    // ==================== Recurrence Integration Tests ====================

    @Test
    fun `add recurring task initializes recurrence state`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()

        val task = repo.addTask(
            spaceId,
            title = "Recurring",
            recurrenceRules = RecurrenceRule(
                timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                    period = RecurrencePeriod.ofDays(1),
                    firstOccurrence = now
                ),
                statusChangeTrigger = null,
                resetToStatus = TaskStatus.Done,
            ).to(RecurrenceState()).let(::listOf)
        )!!

        val recurrenceState = task.recurrenceRules.single().second
        assertNull(recurrenceState.nextOccurrenceDate)
        assertEquals(0, recurrenceState.occurrenceCount)
    }

    @Test
    fun `processRecurrenceTrigger advances recurrence`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val now = Clock.System.now()

        val task = repo.addTask(
            spaceId,
            title = "Recurring",
            status = TaskStatus.Done,
            recurrenceRules = RecurrenceRule(
                timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                    period = RecurrencePeriod.ofDays(1),
                    firstOccurrence = now,
                ),
                statusChangeTrigger = null,
                resetToStatus = TaskStatus.Open,
            ).to(RecurrenceState()).let(::listOf)
        )!!

        val updated = repo.processRecurrenceTrigger(
            task.id,
            RecurrenceTriggerEvent(TaskStatus.Done, now)
        )

        assertNotNull(updated)
        assertEquals(1, updated.recurrenceRules.single().second.occurrenceCount)
    }

    @Test
    fun `processDateBasedRecurrences updates due tasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val pastDue = Clock.System.now() - 1.hours

        repo.addTask(
            spaceId,
            title = "Past due recurring",
            dueDate = pastDue,
            recurrenceRules = RecurrenceRule(
                timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                    period = RecurrencePeriod.ofDays(1),
                    firstOccurrence = pastDue
                ),
                statusChangeTrigger = null,
                resetToStatus = TaskStatus.Done,
            ).to(RecurrenceState()).let(::listOf)
        )

        val updated = repo.processDateBasedRecurrences(Clock.System.now())
        // Should have processed the past due recurring task
        assertTrue(updated.isNotEmpty() || repo.getAllTasks(spaceId).any { it.recurrenceRules.single().second.occurrenceCount > 0 })
    }

    // ==================== Status Timeline Tests ====================

    @Test
    fun `getStatusChangesByDate returns changes grouped by date`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Task")!!

        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.InProgress))
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.Done))

        val now = Clock.System.now()
        val nowKotlinx = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilliseconds())
        val today = nowKotlinx.toLocalDateTime(TimeZone.currentSystemDefault()).date

        val changes = repo.getStatusChangesByDate(spaceId, today.year, today.monthNumber)

        // Should have at least today's changes
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.values.any { events -> events.any { it.task.id == task.id } })
    }

    @Test
    fun `getStatusChangesByDate returns empty for month with no changes`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        repo.addTask(spaceId, title = "Task") // Creates initial status entry

        // Request a month far in the future
        val changes = repo.getStatusChangesByDate(spaceId, 2099, 12)

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `getCalculatedStatusFromSubtasks returns null for task without subtasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Standalone task")!!

        assertNull(repo.getCalculatedStatusFromSubtasks(task.id))
    }

    @Test
    fun `getCalculatedStatusFromSubtasks returns calculated status for task with subtasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(spaceId, title = "Parent")!!
        val child1 = repo.addTask(
            spaceId,
            title = "Child 1",
            connections = setOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val child2 = repo.addTask(
            spaceId,
            title = "Child 2",
            connections = setOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(child1.id)!!.copy(status = TaskStatus.Done))
        repo.updateTask(repo.getTaskById(child2.id)!!.copy(status = TaskStatus.Open))

        val calculatedStatus = repo.getCalculatedStatusFromSubtasks(parent.id)
        // With one Done and one Open, should be Open (Open takes priority over Done)
        assertEquals(TaskStatus.Open, calculatedStatus)
    }

    // ==================== Connection Helper Tests ====================

    @Test
    fun `getConnectionsByType returns grouped connections`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!
        val task3 = repo.addTask(spaceId, title = "Task 3")!!
        val task4 = repo.addTask(spaceId, title = "Task 4")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.DependsOn)
        repo.addConnection(task1.id, task3.id, ConnectionType.RelatesTo)
        repo.addConnection(task4.id, task1.id, ConnectionType.SubtaskOf)

        val grouped = repo.getConnectionsByType(task1.id)

        assertTrue(grouped.containsKey(ConnectionType.DependsOn))
        assertTrue(grouped.containsKey(ConnectionType.RelatesTo))
        assertTrue(grouped.containsKey(ConnectionType.ParentOf)) // Symmetric of SubtaskOf

        assertEquals(1, grouped[ConnectionType.DependsOn]?.size)
        assertEquals(1, grouped[ConnectionType.RelatesTo]?.size)
        assertEquals(1, grouped[ConnectionType.ParentOf]?.size)
    }

    @Test
    fun `getRelatedTasks returns only RelatesTo connections`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!
        val task3 = repo.addTask(spaceId, title = "Task 3")!!

        repo.addConnection(task1.id, task2.id, ConnectionType.RelatesTo)
        repo.addConnection(task1.id, task3.id, ConnectionType.DependsOn)

        val related = repo.getRelatedTasks(task1.id)
        assertEquals(1, related.size)
        assertEquals("Task 2", related.first().title)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `getAllSpacePrefixes returns all prefixes`() = runTest {
        val repo = createEmptyRepository()
        repo.createSpace("Space 1", "ONE")
        repo.createSpace("Space 2", "TWO")
        repo.createSpace("Space 3", "THREE")

        val prefixes = repo.getAllSpacePrefixes()
        assertEquals(3, prefixes.size)
        assertTrue(prefixes.containsAll(listOf("ONE", "TWO", "THREE")))
    }

    @Test
    fun `getById returns task from any space`() = runTest {
        val repo = createEmptyRepository()
        val space1 = repo.createSpace("Space 1", "ONE")!!
        val space2 = repo.createSpace("Space 2", "TWO")!!

        val task1 = repo.addTask(space1.id, title = "Task in Space 1")!!

        // Should still be able to get task from other space by ID
        val retrieved = repo.getTaskById(task1.id)
        assertNotNull(retrieved)
        assertEquals("Task in Space 1", retrieved.title)
    }

    @Test
    fun `update preserves tags in allTags`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val task = repo.addTask(spaceId, title = "Task", tags = setOf("tag1"))!!

        val updated = task.copy(tags = setOf("tag1", "tag2", "tag3"))
        repo.updateTask(updated)

        val allTags = repo.getAllTags(spaceId)
        assertTrue(allTags.containsAll(setOf("tag1", "tag2", "tag3")))
    }

    @Test
    fun `recursive parent status update with deep hierarchy`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create 3-level hierarchy
        val grandparent = repo.addTask(spaceId, title = "Grandparent", autoUpdateStatusFromSubtasks = true)!!
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            connections = setOf(TaskConnection(grandparent.id, ConnectionType.SubtaskOf)),
            autoUpdateStatusFromSubtasks = true
        )!!
        val child = repo.addTask(
            spaceId,
            title = "Child",
            connections = setOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        // Update child to Done
        repo.updateTask(repo.getTaskById(child.id)!!.copy(status = TaskStatus.Done))

        // Both parent and grandparent should be Done
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
        assertEquals(TaskStatus.Done, repo.getTaskById(grandparent.id)!!.status)
    }

}
