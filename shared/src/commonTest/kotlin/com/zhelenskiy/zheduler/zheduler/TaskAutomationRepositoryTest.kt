@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentSetOf

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class InMemoryTaskAutomationRepositoryTest: TaskAutomationRepositoryTest(), InMemoryRepositoryTest
class DatabaseTaskAutomationRepositoryTest: TaskAutomationRepositoryTest(), DatabaseRepositoryTest

/**
 * Comprehensive tests for task automation features:
 * - Auto status updates from subtasks
 * - Automatic unblocking when blockers complete
 * - Status propagation through hierarchies
 * - Edge cases with different order of operations
 */
abstract class TaskAutomationRepositoryTest: AbstractRepositoryTest {

    // ==================== Auto Status Update - Order of Creation ====================

    @Test
    fun `auto status update works when parent created before subtasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // Create subtasks after parent
        val subtask1 = repo.addTask(spaceId, title = "Subtask 1")!!
        repo.addConnection(subtask1.id, parent.id, ConnectionType.SubtaskOf)

        // Update subtask status to trigger automation
        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Done))

        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `auto status update with second subtask changing parent state`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // Add first subtask already marked as done
        val subtask1 = repo.addTask(spaceId, title = "Subtask 1", status = TaskStatus.Done)!!
        repo.addConnection(subtask1.id, parent.id, ConnectionType.SubtaskOf)

        // Parent should become Done (only subtask is done)
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)

        // Add second subtask with Open status
        val subtask2 = repo.addTask(spaceId, title = "Subtask 2")!!
        repo.addConnection(subtask2.id, parent.id, ConnectionType.SubtaskOf)

        // Parent should change to Open (one subtask is Open)
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)

        // Complete second subtask - parent back to Done
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `auto status update works when subtasks created before parent`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create subtasks first
        val subtask1 = repo.addTask(spaceId, title = "Subtask 1")!!
        val subtask2 = repo.addTask(spaceId, title = "Subtask 2")!!

        // Create parent after
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // Link subtasks to parent
        repo.addConnection(subtask1.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(subtask2.id, parent.id, ConnectionType.SubtaskOf)

        // Update subtask status to trigger automation
        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Done))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Done))

        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `auto status update works when connection added after both tasks exist`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create both tasks first
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val subtask = repo.addTask(spaceId, title = "Subtask", status = TaskStatus.Done)!!

        // Add connection later
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        // Trigger update by changing subtask status
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Done))

        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    // ==================== Auto Status Update - State Change Sequences ====================

    @Test
    fun `auto status update handles rapid status changes`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val subtask = repo.addTask(spaceId, title = "Subtask")!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        // Rapidly change status multiple times
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.InProgress))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)

        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf())))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(parent.id)!!.status)

        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Open))
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)

        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `auto status update handles multiple subtasks changing in different orders`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val subtask1 = repo.addTask(spaceId, title = "Subtask 1")!!
        val subtask2 = repo.addTask(spaceId, title = "Subtask 2")!!
        val subtask3 = repo.addTask(spaceId, title = "Subtask 3")!!

        repo.addConnection(subtask1.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(subtask2.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(subtask3.id, parent.id, ConnectionType.SubtaskOf)

        // Update in non-sequential order
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status) // Still has open subtasks

        repo.updateTask(repo.getTaskById(subtask3.id)!!.copy(status = TaskStatus.InProgress))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status) // InProgress takes priority

        repo.updateTask(repo.getTaskById(subtask3.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status) // Still has subtask1 open

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status) // All done
    }

    @Test
    fun `auto status update toggles between states correctly`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            status = TaskStatus.Done,
            autoUpdateStatusFromSubtasks = true
        )!!
        val subtask = repo.addTask(spaceId, title = "Subtask")!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        // Toggle status back and forth
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)

        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Open))
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)

        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)

        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Open))
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)
    }

    // ==================== Blocked Task Unblocking - Order of Operations ====================

    @Test
    fun `blocked task unblocks when blocker created after blocked task`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create blocked task first (with non-existent blocker reference)
        val blocked = repo.addTask(spaceId, title = "Blocked")!!

        // Create blocker after
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        // Set blocked status
        repo.updateTask(repo.getTaskById(blocked.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id))))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)

        // Complete blocker
        repo.updateTask(repo.getTaskById(blocker.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(blocked.id)!!.status)
    }

    @Test
    fun `blocked task unblocks when blocker completed before blocked status set`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        val blocked = repo.addTask(spaceId, title = "Blocked")!!

        // Complete blocker first
        repo.updateTask(repo.getTaskById(blocker.id)!!.copy(status = TaskStatus.Done))

        // Then set blocked status (should immediately unblock)
        repo.updateTask(repo.getTaskById(blocked.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id))))

        // Should be unblocked immediately since blocker is already done
        assertEquals(TaskStatus.InProgress, repo.getTaskById(blocked.id)!!.status)
    }

    @Test
    fun `blocked task handles blockers completing in different orders`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        val blocker3 = repo.addTask(spaceId, title = "Blocker 3")!!
        val blocked = repo.addTask(
            spaceId,
            title = "Blocked",
            status = TaskStatus.Blocked(persistentSetOf(blocker1.id, blocker2.id, blocker3.id))
        )!!

        // Complete in non-sequential order
        repo.updateTask(repo.getTaskById(blocker2.id)!!.copy(status = TaskStatus.Done))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status) // Still blocked

        repo.updateTask(repo.getTaskById(blocker1.id)!!.copy(status = TaskStatus.Done))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status) // Still blocked

        repo.updateTask(repo.getTaskById(blocker3.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(blocked.id)!!.status) // Now unblocked
    }

    @Test
    fun `blocked task with 3 blockers stays blocked until all complete`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        val blocker3 = repo.addTask(spaceId, title = "Blocker 3")!!
        val blocked = repo.addTask(
            spaceId,
            title = "Blocked",
            status = TaskStatus.Blocked(persistentSetOf(blocker1.id, blocker2.id, blocker3.id))
        )!!

        // Initial state: all open, task blocked
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)

        // Cycle blocker1: done → undone (others stay Open)
        repo.updateTask(repo.getTaskById(blocker1.id)!!.copy(status = TaskStatus.Done))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)

        repo.updateTask(repo.getTaskById(blocker1.id)!!.copy(status = TaskStatus.Open))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)

        // Cycle blocker2: done → undone (all are Open now)
        repo.updateTask(repo.getTaskById(blocker2.id)!!.copy(status = TaskStatus.Done))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)

        repo.updateTask(repo.getTaskById(blocker2.id)!!.copy(status = TaskStatus.Open))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)

        // Cycle blocker3: done → undone (all are Open now)
        repo.updateTask(repo.getTaskById(blocker3.id)!!.copy(status = TaskStatus.Done))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)

        repo.updateTask(repo.getTaskById(blocker3.id)!!.copy(status = TaskStatus.Open))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)

        // Now complete all three at the same time
        repo.updateTask(repo.getTaskById(blocker1.id)!!.copy(status = TaskStatus.Done))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status) // Still blocked

        repo.updateTask(repo.getTaskById(blocker2.id)!!.copy(status = TaskStatus.Done))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status) // Still blocked

        repo.updateTask(repo.getTaskById(blocker3.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(blocked.id)!!.status) // NOW unblocked
    }

    @Test
    fun `blocked task handles blocker being un-completed after unblocking`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        val blocked = repo.addTask(
            spaceId,
            title = "Blocked",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id))
        )!!

        // Complete blocker - unblocks the task
        repo.updateTask(repo.getTaskById(blocker.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(blocked.id)!!.status)

        // Manually revert blocked task back to blocked state
        repo.updateTask(repo.getTaskById(blocked.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id))))

        // Since blocker is still Done, updateStatus checks and immediately sets to InProgress
        assertEquals(TaskStatus.InProgress, repo.getTaskById(blocked.id)!!.status)
    }

    // ==================== Combined Automation - Auto Status + Unblocking ====================

    @Test
    fun `parent auto-updates when subtask unblocks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val subtask = repo.addTask(
            spaceId,
            title = "Subtask",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id))
        )!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        // Parent should be blocked because subtask is blocked
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id))))
        assertIs<TaskStatus.Blocked>(repo.getTaskById(parent.id)!!.status)

        // Complete blocker - subtask unblocks, parent should auto-update
        repo.updateTask(repo.getTaskById(blocker.id)!!.copy(status = TaskStatus.Done))

        // Subtask should be unblocked
        assertEquals(TaskStatus.InProgress, repo.getTaskById(subtask.id)!!.status)

        // Parent should also update to InProgress
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `deep hierarchy handles cascading automation correctly`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create 4-level hierarchy
        val grandgrandparent = repo.addTask(
            spaceId,
            title = "GrandGrandparent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val grandparent = repo.addTask(
            spaceId,
            title = "Grandparent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val child = repo.addTask(spaceId, title = "Child")!!

        repo.addConnection(grandparent.id, grandgrandparent.id, ConnectionType.SubtaskOf)
        repo.addConnection(parent.id, grandparent.id, ConnectionType.SubtaskOf)
        repo.addConnection(child.id, parent.id, ConnectionType.SubtaskOf)

        // Update child - should cascade all the way up
        repo.updateTask(repo.getTaskById(child.id)!!.copy(status = TaskStatus.InProgress))

        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)
        assertEquals(TaskStatus.InProgress, repo.getTaskById(grandparent.id)!!.status)
        assertEquals(TaskStatus.InProgress, repo.getTaskById(grandgrandparent.id)!!.status)

        // Complete child - should cascade all the way up
        repo.updateTask(repo.getTaskById(child.id)!!.copy(status = TaskStatus.Done))

        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
        assertEquals(TaskStatus.Done, repo.getTaskById(grandparent.id)!!.status)
        assertEquals(TaskStatus.Done, repo.getTaskById(grandgrandparent.id)!!.status)
    }

    @Test
    fun `automation handles subtask status updates`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val subtask1 = repo.addTask(spaceId, title = "Subtask 1")!!
        val subtask2 = repo.addTask(spaceId, title = "Subtask 2")!!

        repo.addConnection(subtask1.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(subtask2.id, parent.id, ConnectionType.SubtaskOf)

        // Subtask1 done - parent should be Open (because subtask2 is open)
        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)

        // Subtask2 in progress - parent should auto-update to InProgress
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.InProgress))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)

        // Subtask2 done - parent should auto-update to Done
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    // ==================== Automation with Task Deletion ====================

    @Test
    fun `auto status update handles subtask deletion`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val subtask1 = repo.addTask(spaceId, title = "Subtask 1")!!
        val subtask2 = repo.addTask(spaceId, title = "Subtask 2", status = TaskStatus.Done)!!

        repo.addConnection(subtask1.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(subtask2.id, parent.id, ConnectionType.SubtaskOf)

        // Trigger update
        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Open))
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)

        // Delete open subtask
        repo.deleteTask(subtask1.id)

        // Trigger update on remaining subtask
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Done))

        // Parent should be Done (only remaining subtask is done)
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `blocked task handles blocker deletion`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        val blocked = repo.addTask(
            spaceId,
            title = "Blocked",
            status = TaskStatus.Blocked(persistentSetOf(blocker1.id, blocker2.id))
        )!!

        // Delete one blocker (connections should be cleaned up)
        repo.deleteTask(blocker1.id)

        // Blocked task should still be blocked by blocker2
        val blockedStatus = repo.getTaskById(blocked.id)!!.status
        // Deleting a blocker removes it from the blocker list
        assertIs<TaskStatus.Blocked>(blockedStatus)
        assertEquals(setOf(blocker2.id), (blockedStatus as TaskStatus.Blocked).blockerTaskIds)

        // Complete remaining blocker
        repo.updateTask(repo.getTaskById(blocker2.id)!!.copy(status = TaskStatus.Done))

        // Should unblock (deleted blocker was removed from the list)
        assertEquals(TaskStatus.InProgress, repo.getTaskById(blocked.id)!!.status)
    }

    @Test
    fun `deleting only subtask updates parent status`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val subtask = repo.addTask(spaceId, title = "Only Subtask", status = TaskStatus.InProgress)!!

        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        // Parent should be InProgress because of subtask
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.InProgress))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)

        // Delete the only subtask
        repo.deleteTask(subtask.id)

        // Parent has no subtasks now, so next status update won't change it
        // But status stays as it was (InProgress) since no trigger happens
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `deleting only blocker unblocks task immediately`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val blocker = repo.addTask(spaceId, title = "Only Blocker")!!
        val blocked = repo.addTask(
            spaceId,
            title = "Blocked Task",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id))
        )!!

        // Verify blocked
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)

        // Delete the only blocker
        repo.deleteTask(blocker.id)

        // Task should unblock immediately
        assertEquals(TaskStatus.InProgress, repo.getTaskById(blocked.id)!!.status)
    }

    // ==================== Automation Toggling ====================

    @Test
    fun `disabling auto update stops automation`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val subtask = repo.addTask(spaceId, title = "Subtask")!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        // Update subtask - parent auto-updates
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.InProgress))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)

        // Disable auto-update
        repo.updateTask(repo.getTaskById(parent.id)!!.copy(autoUpdateStatusFromSubtasks = false))

        // Update subtask again - parent should NOT auto-update
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status) // Still InProgress
    }

    @Test
    fun `enabling auto update applies current subtask states`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            status = TaskStatus.Open,
            autoUpdateStatusFromSubtasks = false  // Disabled initially
        )!!
        val subtask = repo.addTask(spaceId, title = "Subtask")!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        // Update subtask while auto-update is disabled
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status) // No auto-update

        // Enable auto-update
        repo.updateTask(repo.getTaskById(parent.id)!!.copy(autoUpdateStatusFromSubtasks = true))

        // Trigger an update by changing subtask status
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Done))

        // Now parent should update
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    // ==================== Performance and Edge Cases ====================

    @Test
    fun `automation handles large number of subtasks efficiently`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // Create 50 subtasks
        val subtasks = (1..50).map { i ->
            val subtask = repo.addTask(spaceId, title = "Subtask $i")!!
            repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)
            subtask
        }

        // Update all to InProgress
        subtasks.forEach { repo.updateTask(repo.getTaskById(it.id)!!.copy(status = TaskStatus.InProgress)) }
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)

        // Update all to Done one by one
        subtasks.dropLast(1).forEach { repo.updateTask(repo.getTaskById(it.id)!!.copy(status = TaskStatus.Done)) }
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status) // One still in progress

        // Complete last one
        repo.updateTask(repo.getTaskById(subtasks.last().id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `automation handles circular parent-child relationships gracefully`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val task1 = repo.addTask(spaceId, title = "Task 1", autoUpdateStatusFromSubtasks = true)!!
        val task2 = repo.addTask(spaceId, title = "Task 2", autoUpdateStatusFromSubtasks = true)!!

        // Create first connection: task2 is subtask of task1
        val firstConnection = repo.addConnection(task2.id, task1.id, ConnectionType.SubtaskOf)
        assertTrue(firstConnection)

        // Try to create cycle: task1 as subtask of task2 (should be prevented by wouldCreateCycle)
        val cycleCreated = repo.addConnection(task1.id, task2.id, ConnectionType.SubtaskOf)
        assertFalse(cycleCreated)

        // Ensure no cycle exists - task1 should NOT have SubtaskOf connection to task2
        val task1Connections = repo.getTaskById(task1.id)!!.connections
        assertFalse(task1Connections.any { it.targetTaskId == task2.id && it.type == ConnectionType.SubtaskOf })
    }

    @Test
    fun `cycle prevention with more than 2 nodes`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val task1 = repo.addTask(spaceId, title = "Task 1")!!
        val task2 = repo.addTask(spaceId, title = "Task 2")!!
        val task3 = repo.addTask(spaceId, title = "Task 3")!!
        val task4 = repo.addTask(spaceId, title = "Task 4")!!

        // Create chain: task4 -> task3 -> task2 -> task1
        assertTrue(repo.addConnection(task4.id, task3.id, ConnectionType.SubtaskOf))
        assertTrue(repo.addConnection(task3.id, task2.id, ConnectionType.SubtaskOf))
        assertTrue(repo.addConnection(task2.id, task1.id, ConnectionType.SubtaskOf))

        // Try to close the loop: task1 -> task4 (should be prevented)
        assertFalse(repo.addConnection(task1.id, task4.id, ConnectionType.SubtaskOf))

        // Try to create intermediate cycle: task2 -> task4 (should be prevented)
        assertFalse(repo.addConnection(task2.id, task4.id, ConnectionType.SubtaskOf))

        // Try to create different cycle: task1 -> task3 (should be prevented)
        assertFalse(repo.addConnection(task1.id, task3.id, ConnectionType.SubtaskOf))

        // Verify the valid chain still exists
        assertTrue(repo.getTaskById(task4.id)!!.connections.any {
            it.targetTaskId == task3.id && it.type == ConnectionType.SubtaskOf
        })
        assertTrue(repo.getTaskById(task3.id)!!.connections.any {
            it.targetTaskId == task2.id && it.type == ConnectionType.SubtaskOf
        })
        assertTrue(repo.getTaskById(task2.id)!!.connections.any {
            it.targetTaskId == task1.id && it.type == ConnectionType.SubtaskOf
        })

        // Adding a non-cycle connection should work
        val task5 = repo.addTask(spaceId, title = "Task 5")!!
        assertTrue(repo.addConnection(task5.id, task1.id, ConnectionType.SubtaskOf))
    }

    @Test
    fun `blocked status with non-existent blocker ID handles gracefully`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val blocked = repo.addTask(
            spaceId,
            title = "Blocked",
            status = TaskStatus.Blocked(persistentSetOf("NON-EXISTENT-1", "NON-EXISTENT-2"))
        )!!

        // Non-existent blockers are treated as if they don't block (or system should handle gracefully)
        // The exact behavior depends on implementation - documenting current behavior
        val status = repo.getTaskById(blocked.id)!!.status
        assertIs<TaskStatus.Blocked>(status)

        // Creating a real task and marking it done shouldn't affect this
        val realTask = repo.addTask(spaceId, title = "Real Task")!!
        repo.updateTask(repo.getTaskById(realTask.id)!!.copy(status = TaskStatus.Done))

        // Blocked task should remain in its blocked state (non-existent blockers)
        assertIs<TaskStatus.Blocked>(repo.getTaskById(blocked.id)!!.status)
    }

    // ==================== Regression Tests for Bug Fixes ====================

    @Test
    fun `addConnection with SubtaskOf immediately updates parent status`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create parent with auto-update enabled
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // Create a subtask that is already Done
        val subtask = repo.addTask(spaceId, title = "Subtask", status = TaskStatus.Done)!!

        // Parent should still be Open (no subtasks yet)
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)

        // Add the connection - parent should IMMEDIATELY update to Done
        // (without needing to call updateStatus on the subtask)
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        // Parent should now be Done because its only subtask is Done
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `addConnection with SubtaskOf immediately updates parent to InProgress when subtask is InProgress`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask = repo.addTask(spaceId, title = "Subtask", status = TaskStatus.InProgress)!!

        // Add connection - parent should immediately become InProgress
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `addConnection with SubtaskOf calculates status from multiple existing subtasks`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // First subtask is Done
        val subtask1 = repo.addTask(spaceId, title = "Subtask 1", status = TaskStatus.Done)!!
        repo.addConnection(subtask1.id, parent.id, ConnectionType.SubtaskOf)
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)

        // Second subtask is Open - parent should become Open
        val subtask2 = repo.addTask(spaceId, title = "Subtask 2")!!
        repo.addConnection(subtask2.id, parent.id, ConnectionType.SubtaskOf)
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)

        // Third subtask is InProgress - parent should become InProgress (takes priority over Open)
        val subtask3 = repo.addTask(spaceId, title = "Subtask 3", status = TaskStatus.InProgress)!!
        repo.addConnection(subtask3.id, parent.id, ConnectionType.SubtaskOf)
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `delete subtask immediately updates parent status`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // Add two subtasks - one Done, one Open
        val subtaskDone = repo.addTask(spaceId, title = "Subtask Done", status = TaskStatus.Done)!!
        val subtaskOpen = repo.addTask(spaceId, title = "Subtask Open")!!

        repo.addConnection(subtaskDone.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(subtaskOpen.id, parent.id, ConnectionType.SubtaskOf)

        // Parent should be Open (one subtask is Open)
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)

        // Delete the Open subtask - parent should IMMEDIATELY become Done
        // (without needing to call updateStatus)
        repo.deleteTask(subtaskOpen.id)

        // Parent should now be Done because its only remaining subtask is Done
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `delete subtask with InProgress status updates parent to Done when remaining subtasks are Done`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(spaceId, title = "Subtask 1", status = TaskStatus.Done)!!
        val subtask2 = repo.addTask(spaceId, title = "Subtask 2", status = TaskStatus.Done)!!
        val subtaskInProgress = repo.addTask(spaceId, title = "Subtask InProgress", status = TaskStatus.InProgress)!!

        repo.addConnection(subtask1.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(subtask2.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(subtaskInProgress.id, parent.id, ConnectionType.SubtaskOf)

        // Parent should be InProgress
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)

        // Delete the InProgress subtask
        repo.deleteTask(subtaskInProgress.id)

        // Parent should now be Done
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `unblocking task that is blocking another task triggers cascade`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create blocker that blocks taskA
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        // Create taskA that is blocked by blocker
        val taskA = repo.addTask(
            spaceId,
            title = "Task A",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id))
        )!!

        // Create taskB that is blocked by taskA
        val taskB = repo.addTask(
            spaceId,
            title = "Task B",
            status = TaskStatus.Blocked(persistentSetOf(taskA.id))
        )!!

        // Complete blocker - taskA should unblock
        repo.updateTask(repo.getTaskById(blocker.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(taskA.id)!!.status)

        // taskB is still blocked by taskA (taskA is not Done yet)
        assertIs<TaskStatus.Blocked>(repo.getTaskById(taskB.id)!!.status)

        // Complete taskA - taskB should unblock
        repo.updateTask(repo.getTaskById(taskA.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(taskB.id)!!.status)
    }

    @Test
    fun `unblocking parent with subtasks unblocks task that depends on parent`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create blocker
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        // Create parent with auto-status from subtasks enabled
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // Create subtask of parent that is blocked
        val subtask = repo.addTask(
            spaceId,
            title = "Subtask",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id))
        )!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        // Parent should be blocked because subtask is blocked
        assertIs<TaskStatus.Blocked>(repo.getTaskById(parent.id)!!.status)

        // Create task that DEPENDS ON the parent (not blocked by it)
        // This task can only start when parent is done
        val dependent = repo.addTask(
            spaceId,
            title = "Dependent",
            status = TaskStatus.Blocked(persistentSetOf(parent.id))
        )!!

        // Complete blocker - subtask should unblock
        repo.updateTask(repo.getTaskById(blocker.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.InProgress, repo.getTaskById(subtask.id)!!.status)

        // Parent should auto-update to InProgress
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)

        // Dependent is still blocked by parent (parent needs to be Done/Declined to unblock)
        assertIs<TaskStatus.Blocked>(repo.getTaskById(dependent.id)!!.status)

        // Complete subtask - parent should become Done
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)

        // Now dependent should unblock (parent status changed to Done)
        assertEquals(TaskStatus.InProgress, repo.getTaskById(dependent.id)!!.status)
    }

    @Test
    fun `deleting blocker of subtask unblocks parent and dependent task`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create blocker
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        // Create parent with auto-status from subtasks enabled
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // Create subtask of parent that is blocked
        val subtask = repo.addTask(
            spaceId,
            title = "Subtask",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id))
        )!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        // Parent should be blocked because subtask is blocked
        assertIs<TaskStatus.Blocked>(repo.getTaskById(parent.id)!!.status)

        // Create task that is blocked by parent
        val dependent = repo.addTask(
            spaceId,
            title = "Dependent",
            status = TaskStatus.Blocked(persistentSetOf(parent.id))
        )!!

        // Delete blocker - subtask should unblock immediately
        repo.deleteTask(blocker.id)
        assertEquals(TaskStatus.InProgress, repo.getTaskById(subtask.id)!!.status)

        // Parent should auto-update to InProgress
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)

        // Dependent is still blocked (parent needs to be Done/Declined)
        assertIs<TaskStatus.Blocked>(repo.getTaskById(dependent.id)!!.status)

        // Complete subtask - parent should become Done
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)

        // Now dependent should unblock
        assertEquals(TaskStatus.InProgress, repo.getTaskById(dependent.id)!!.status)
    }

    @Test
    fun `disabling auto-status and manually setting parent to Done unblocks dependent task`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create blocker
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        // Create parent with auto-status from subtasks enabled
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // Create subtask of parent that is blocked
        val subtask = repo.addTask(
            spaceId,
            title = "Subtask",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id))
        )!!
        repo.addConnection(subtask.id, parent.id, ConnectionType.SubtaskOf)

        // Parent should be blocked because subtask is blocked
        assertIs<TaskStatus.Blocked>(repo.getTaskById(parent.id)!!.status)

        // Create task that is blocked by parent
        val dependent = repo.addTask(
            spaceId,
            title = "Dependent",
            status = TaskStatus.Blocked(persistentSetOf(parent.id))
        )!!

        // Disable auto-status on parent
        val parentUpdated = repo.getTaskById(parent.id)!!
        repo.updateTask(parentUpdated.copy(autoUpdateStatusFromSubtasks = false))

        // Manually set parent to Done (overriding the blocked subtask)
        repo.updateTask(repo.getTaskById(parent.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)

        // Dependent should unblock (parent became Done)
        assertEquals(TaskStatus.InProgress, repo.getTaskById(dependent.id)!!.status)

        // Subtask should still be blocked (parent no longer auto-updates from it)
        assertIs<TaskStatus.Blocked>(repo.getTaskById(subtask.id)!!.status)
    }

    @Test
    fun `delete subtask updates grandparent status through hierarchy`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val grandparent = repo.addTask(
            spaceId,
            title = "Grandparent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!
        val subtask1 = repo.addTask(spaceId, title = "Subtask 1", status = TaskStatus.Done)!!
        val subtask2 = repo.addTask(spaceId, title = "Subtask 2")!!

        repo.addConnection(parent.id, grandparent.id, ConnectionType.SubtaskOf)
        repo.addConnection(subtask1.id, parent.id, ConnectionType.SubtaskOf)
        repo.addConnection(subtask2.id, parent.id, ConnectionType.SubtaskOf)

        // Hierarchy: grandparent -> parent -> subtask1 (Done), subtask2 (Open)
        // Parent should be Open, Grandparent should be Open
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)
        assertEquals(TaskStatus.Open, repo.getTaskById(grandparent.id)!!.status)

        // Delete the Open subtask
        repo.deleteTask(subtask2.id)

        // Parent should become Done (only Done subtask remains)
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
        // Grandparent should also become Done (its only subtask is now Done)
        assertEquals(TaskStatus.Done, repo.getTaskById(grandparent.id)!!.status)
    }

    // ==================== Timeline isAutomatic Flag Tests ====================

    @Test
    fun `update with autoUpdateStatusFromSubtasks marks timeline entry as automatic when status changes`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create parent with auto-update enabled
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // Create subtask
        val subtask = repo.addTask(
            spaceId,
            title = "Subtask",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        // Update subtask to Done - parent should auto-update to Done
        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Done))

        val timeline = repo.getStatusTimeline(parent.id)
        // Should have: initial Open, then auto-updated to Done
        assertTrue(timeline.size >= 2, "Expected at least 2 timeline entries")
        val lastEntry = timeline.last()
        assertEquals(TaskStatus.Done, lastEntry.newStatus)
        assertNotNull(lastEntry.automaticChangeReason, "Status change from auto-update should have automatic change reason")
    }

    @Test
    fun `manual status update when autoUpdateStatusFromSubtasks enabled throws exception`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        // Attempting to manually change status when autoUpdateStatusFromSubtasks is enabled should throw
        assertFailsWith<IllegalArgumentException> {
            repo.updateTask(repo.getTaskById(parent.id)!!.copy(status = TaskStatus.InProgress))
        }
    }

    @Test
    fun `update task via update enabling autoUpdateStatusFromSubtasks triggers automatic status calculation`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        // Create parent with auto-update DISABLED initially
        val parent = repo.addTask(
            spaceId,
            title = "Parent"
        )!!

        // Create a Done subtask
        val subtask = repo.addTask(
            spaceId,
            title = "Subtask",
            status = TaskStatus.Done,
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        // Parent should still be Open (auto-update disabled)
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)

        // Now enable auto-update via update() - this should trigger automatic status calculation
        val updatedParent = repo.getTaskById(parent.id)!!
        repo.updateTask(updatedParent.copy(autoUpdateStatusFromSubtasks = true))

        // Parent should now be Done
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)

        // The timeline entry for this change should have automatic change reason
        val timeline = repo.getStatusTimeline(parent.id)
        val lastDoneEntry = timeline.lastOrNull { it.newStatus == TaskStatus.Done }
        assertNotNull(lastDoneEntry, "Should have a Done entry in timeline")
        assertNotNull(lastDoneEntry.automaticChangeReason, "Status change from enabling auto-update should have automatic change reason")
    }

    // ==================== Status field changes (blockerTaskIds, comment, reason) ====================

    @Test
    fun `changing blocker IDs in Blocked status records timeline entry`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        val task = repo.addTask(spaceId, title = "Task", status = TaskStatus.Blocked(persistentSetOf(blocker1.id)))!!

        // Change blocker IDs via updateStatus
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker2.id))))

        val timeline = repo.getStatusTimeline(task.id)
        assertTrue(timeline.size >= 2, "Should have at least initial Blocked and updated Blocked")
        val lastEntry = timeline.last()
        assertIs<TaskStatus.Blocked>(lastEntry.newStatus)
        assertEquals(setOf(blocker2.id), lastEntry.newStatus.blockerTaskIds)
    }

    @Test
    fun `changing comment in Blocked status records timeline entry`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val blocker = repo.addTask(spaceId, title = "Blocker")!!
        val task = repo.addTask(
            spaceId,
            title = "Task",
            status = TaskStatus.Blocked(persistentSetOf(blocker.id), "Original comment")
        )!!

        // Change comment via update
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id), "Updated comment")))

        val timeline = repo.getStatusTimeline(task.id)
        assertTrue(timeline.size >= 2, "Should have at least initial Blocked and updated Blocked")
        val lastEntry = timeline.last()
        assertIs<TaskStatus.Blocked>(lastEntry.newStatus)
        assertEquals("Updated comment", lastEntry.newStatus.comment)
    }

    @Test
    fun `changing reason in Declined status records timeline entry`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val task = repo.addTask(spaceId, title = "Task", status = TaskStatus.Declined("Original reason"))!!

        // Change reason via updateStatus
        repo.updateTask(repo.getTaskById(task.id)!!.copy(status = TaskStatus.Declined("Updated reason")))

        val timeline = repo.getStatusTimeline(task.id)
        assertTrue(timeline.size >= 2, "Should have at least initial Declined and updated Declined")
        val lastEntry = timeline.last()
        assertIs<TaskStatus.Declined>(lastEntry.newStatus)
        assertEquals("Updated reason", lastEntry.newStatus.reason)
    }

    @Test
    fun `changing blocker IDs via update records timeline entry`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!
        val task = repo.addTask(spaceId, title = "Task", status = TaskStatus.Blocked(persistentSetOf(blocker1.id)))!!

        // Change blocker IDs via update() (not updateStatus)
        val updated = repo.getTaskById(task.id)!!
        repo.updateTask(updated.copy(status = TaskStatus.Blocked(persistentSetOf(blocker2.id))))

        val timeline = repo.getStatusTimeline(task.id)
        assertTrue(timeline.size >= 2, "Should have at least initial Blocked and updated Blocked")
        val lastEntry = timeline.last()
        assertIs<TaskStatus.Blocked>(lastEntry.newStatus)
        assertEquals(setOf(blocker2.id), lastEntry.newStatus.blockerTaskIds)
    }
}
