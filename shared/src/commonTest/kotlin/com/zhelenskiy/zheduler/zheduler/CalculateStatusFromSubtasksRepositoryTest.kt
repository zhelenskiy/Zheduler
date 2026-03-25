@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InMemoryCalculateStatusFromSubtasksRepositoryTest: CalculateStatusFromSubtasksRepositoryTest(), InMemoryRepositoryTest
class DatabaseCalculateStatusFromSubtasksRepositoryTest: CalculateStatusFromSubtasksRepositoryTest(), DatabaseRepositoryTest

/**
 * Tests for calculateStatusFromSubtasks via the public TaskRepository API.
 * The function is tested indirectly through autoUpdateStatusFromSubtasks behavior.
 *
 * Note: The parent status is only updated when updateStatus() is called on a subtask,
 * not when subtasks are initially added. This is the expected behavior.
 *
 * Rules being tested:
 * 1. If all subtasks have the same status class -> use that status
 * 2. If any subtask is InProgress -> InProgress
 * 3. If any subtask is Open -> Open
 * 4. If any subtask is Blocked -> Blocked (collect all blocker IDs)
 * 5. Otherwise (all Done or Declined) -> Done
 */
abstract class CalculateStatusFromSubtasksRepositoryTest: AbstractRepositoryTest {


    /**
     * Creates a parent with subtasks, then triggers status update via updateStatus on each subtask.
     * This is necessary because calculateStatusFromSubtasks is only called when updateStatus() is invoked.
     */
    private suspend fun TaskRepository.addParentWithSubtasksAndTriggerUpdate(
        spaceId: String,
        parentStatus: TaskStatus = TaskStatus.Open,
        subtaskStatuses: List<TaskStatus>
    ): Pair<Task, List<Task>> {
        val parent = addTask(
            spaceId,
            title = "Parent Task",
            status = parentStatus,
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtasks = subtaskStatuses.mapIndexed { index, status ->
            val subtask = addTask(
                spaceId,
                title = "Subtask $index",
                status = TaskStatus.Open, // Add as Open first
                connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
            )!!
            // Now update to desired status to trigger parent update
            updateTask(getTaskById(subtask.id)!!.copy(status = status))
            getTaskById(subtask.id)!!
        }

        return parent to subtasks
    }

    // ===== Corner case: Empty subtasks =====

    @Test
    fun `no subtasks - parent status unchanged`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            status = TaskStatus.InProgress,
            autoUpdateStatusFromSubtasks = true
        )!!

        // Parent status should remain unchanged with no subtasks
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)
    }

    // ===== Rule 1: All same status =====

    @Test
    fun `all subtasks Open - parent becomes Open`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.InProgress,
            subtaskStatuses = listOf(TaskStatus.Open, TaskStatus.Open, TaskStatus.Open)
        )

        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `all subtasks Done - parent becomes Done`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = listOf(TaskStatus.Done, TaskStatus.Done)
        )

        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `all subtasks InProgress - parent becomes InProgress`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = listOf(TaskStatus.InProgress, TaskStatus.InProgress)
        )

        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `all subtasks Blocked - parent becomes Blocked with combined blockers`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker1.id))))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker2.id))))

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Blocked>(parentStatus)
        assertEquals(setOf(blocker1.id, blocker2.id), parentStatus.blockerTaskIds)
    }

    @Test
    fun `all subtasks Blocked with comments - parent combines comments with blocker IDs`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker1.id), "Comment 1")))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker2.id), "Comment 2")))

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Blocked>(parentStatus)
        assertEquals(setOf(blocker1.id, blocker2.id), parentStatus.blockerTaskIds)
        assertEquals("${blocker1.id}: Comment 1\n${blocker2.id}: Comment 2", parentStatus.comment)
    }

    @Test
    fun `all subtasks Blocked with same blockers but different comments - combines comments`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id), "Comment 1")))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id), "Comment 2")))

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Blocked>(parentStatus)
        assertEquals(setOf(blocker.id), parentStatus.blockerTaskIds)
        // Comments should be combined even if blockers are the same
        assertEquals("${blocker.id}: Comment 1\n${blocker.id}: Comment 2", parentStatus.comment)
    }

    @Test
    fun `all subtasks Blocked with empty comments - parent has empty comment`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker1.id), "")))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker2.id), "")))

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Blocked>(parentStatus)
        assertEquals(setOf(blocker1.id, blocker2.id), parentStatus.blockerTaskIds)
        assertEquals("", parentStatus.comment)
    }

    @Test
    fun `all subtasks Blocked with mixed empty and non-empty comments - combines only non-empty`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker1 = repo.addTask(spaceId, title = "Blocker 1")!!
        val blocker2 = repo.addTask(spaceId, title = "Blocker 2")!!

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker1.id), "")))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker2.id), "Important comment")))

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Blocked>(parentStatus)
        assertEquals(setOf(blocker1.id, blocker2.id), parentStatus.blockerTaskIds)
        assertEquals("${blocker2.id}: Important comment", parentStatus.comment)
    }

    @Test
    fun `all subtasks Declined - parent becomes Declined with combined reasons`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = listOf(
                TaskStatus.Declined("Reason 1"),
                TaskStatus.Declined("Reason 2")
            )
        )

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Declined>(parentStatus)
        assertEquals("Subtasks declined with reasons:\nReason 1\nReason 2", parentStatus.reason)
    }

    // ===== Rule 2: Any InProgress =====

    @Test
    fun `mixed with InProgress - parent becomes InProgress`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = listOf(TaskStatus.Done, TaskStatus.InProgress, TaskStatus.Open)
        )

        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `InProgress takes priority over Blocked`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id))))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.InProgress))

        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)
    }

    // ===== Rule 3: Any Open (but no InProgress) =====

    @Test
    fun `Open takes priority over Blocked`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            status = TaskStatus.Done,
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id))))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Open))

        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `Open takes priority over Done`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Done,
            subtaskStatuses = listOf(TaskStatus.Done, TaskStatus.Open)
        )

        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `Open takes priority over Declined`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Done,
            subtaskStatuses = listOf(TaskStatus.Declined("reason"), TaskStatus.Open)
        )

        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)
    }

    // ===== Rule 4: Any Blocked (but no InProgress/Open) =====

    @Test
    fun `Blocked with Done - parent becomes Blocked`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id))))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Done))

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Blocked>(parentStatus)
        assertEquals(setOf(blocker.id), parentStatus.blockerTaskIds)
    }

    @Test
    fun `Blocked with Declined - parent becomes Blocked`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id))))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Declined("reason")))

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Blocked>(parentStatus)
    }

    // ===== Rule 5: All Done or Declined =====

    @Test
    fun `Done and Declined - parent becomes Done`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = listOf(TaskStatus.Done, TaskStatus.Declined("reason"))
        )

        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    // ===== Corner case: Single subtask =====

    @Test
    fun `single Open subtask - parent becomes Open`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Done,
            subtaskStatuses = listOf(TaskStatus.Open)
        )

        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `single Done subtask - parent becomes Done`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = listOf(TaskStatus.Done)
        )

        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `single Blocked subtask with empty blockers - parent becomes Blocked`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask = repo.addTask(
            spaceId,
            title = "Subtask",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf())))

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Blocked>(parentStatus)
        assertEquals(emptySet(), parentStatus.blockerTaskIds)
    }

    // ===== Corner case: Blocked with empty blocker set =====

    @Test
    fun `multiple Blocked subtasks with empty blockers - parent has empty blockers`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf())))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf())))

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Blocked>(parentStatus)
        assertEquals(emptySet(), parentStatus.blockerTaskIds)
    }

    @Test
    fun `Blocked with empty and non-empty blockers - combines correctly`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val blocker = repo.addTask(spaceId, title = "Blocker")!!

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf())))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Blocked(persistentSetOf(blocker.id))))

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Blocked>(parentStatus)
        assertEquals(setOf(blocker.id), parentStatus.blockerTaskIds)
    }

    // ===== Corner case: autoUpdateStatusFromSubtasks disabled =====

    @Test
    fun `autoUpdateStatusFromSubtasks disabled - parent status unchanged`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            status = TaskStatus.InProgress
        )!!

        val subtask = repo.addTask(
            spaceId,
            title = "Subtask",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask.id)!!.copy(status = TaskStatus.Done))

        // Parent should remain InProgress since auto-update is disabled
        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)
    }

    // ===== Corner case: Many subtasks =====

    @Test
    fun `many subtasks all Done - parent becomes Done`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = List(100) { TaskStatus.Done }
        )

        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
    }

    @Test
    fun `many subtasks with one Open among Done - parent becomes Open`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val statuses = List(99) { TaskStatus.Done } + TaskStatus.Open
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Done,
            subtaskStatuses = statuses
        )

        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)
    }

    // ===== Corner case: Status update propagation =====

    @Test
    fun `updating subtask status updates parent`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            status = TaskStatus.InProgress, // Start with different status
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        // Trigger initial update
        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Open))
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status)

        // Update first subtask to Done
        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Open, repo.getTaskById(parent.id)!!.status) // Still Open because one is Open

        // Update second subtask to Done
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Done))
        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status) // Now Done
    }

    // ===== Corner case: Declined reasons =====

    @Test
    fun `all Declined with different reasons - combines all reasons`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val subtask1 = repo.addTask(
            spaceId,
            title = "Subtask 1",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!
        val subtask2 = repo.addTask(
            spaceId,
            title = "Subtask 2",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        repo.updateTask(repo.getTaskById(subtask1.id)!!.copy(status = TaskStatus.Declined("First reason")))
        repo.updateTask(repo.getTaskById(subtask2.id)!!.copy(status = TaskStatus.Declined("Second reason")))

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Declined>(parentStatus)
        // Should combine all declined reasons
        assertEquals("Subtasks declined with reasons:\nFirst reason\nSecond reason", parentStatus.reason)
    }

    @Test
    fun `Declined with empty reason`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = listOf(TaskStatus.Declined(""))
        )

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Declined>(parentStatus)
        assertEquals("Subtask declined without a reason", parentStatus.reason)
    }

    @Test
    fun `multiple Declined with all empty reasons`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = listOf(TaskStatus.Declined(""), TaskStatus.Declined(""))
        )

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Declined>(parentStatus)
        assertEquals("Subtasks declined without a reason", parentStatus.reason)
    }

    @Test
    fun `multiple Declined with one reason`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = listOf(TaskStatus.Declined("Only reason"), TaskStatus.Declined(""))
        )

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Declined>(parentStatus)
        assertEquals("Subtasks declined with a reason:\n[Only reason]", parentStatus.reason)
    }

    @Test
    fun `single Declined with reason`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = listOf(TaskStatus.Declined("Single reason"))
        )

        val parentStatus = repo.getTaskById(parent.id)!!.status
        assertIs<TaskStatus.Declined>(parentStatus)
        assertEquals("Subtask declined with a reason:\n[Single reason]", parentStatus.reason)
    }

    // ===== Corner case: Hierarchical parent-child updates =====

    @Test
    fun `nested parent hierarchy - grandparent gets updated`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()

        val grandparent = repo.addTask(
            spaceId,
            title = "Grandparent",
            autoUpdateStatusFromSubtasks = true
        )!!

        val parent = repo.addTask(
            spaceId,
            title = "Parent",
            connections = persistentSetOf(TaskConnection(grandparent.id, ConnectionType.SubtaskOf)),
            autoUpdateStatusFromSubtasks = true
        )!!

        val child = repo.addTask(
            spaceId,
            title = "Child",
            connections = persistentSetOf(TaskConnection(parent.id, ConnectionType.SubtaskOf))
        )!!

        // Update child to Done - should propagate up
        repo.updateTask(repo.getTaskById(child.id)!!.copy(status = TaskStatus.Done))

        assertEquals(TaskStatus.Done, repo.getTaskById(parent.id)!!.status)
        assertEquals(TaskStatus.Done, repo.getTaskById(grandparent.id)!!.status)
    }

    // ===== Corner case: Same status class check =====

    @Test
    fun `all same status class triggers rule 1`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        val (parent, _) = repo.addParentWithSubtasksAndTriggerUpdate(
            spaceId,
            parentStatus = TaskStatus.Open,
            subtaskStatuses = listOf(TaskStatus.InProgress, TaskStatus.InProgress, TaskStatus.InProgress)
        )

        assertEquals(TaskStatus.InProgress, repo.getTaskById(parent.id)!!.status)
    }
}
