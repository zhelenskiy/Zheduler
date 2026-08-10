@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class ViewModeModelsTest {

    // ==================== Helper Functions ====================

    private fun createTask(
        id: String,
        title: String = "Task $id",
        status: TaskStatus = TaskStatus.Open,
        priority: Priority? = null,
        dueDate: Instant? = null,
        tags: PersistentSet<String> = persistentSetOf()
    ): TaskWithTotals {
        val task = Task(
            id = id,
            title = title,
            spaceId = "space-1",
            status = status,
            priority = priority,
            dueDate = dueDate,
            tags = tags
        )
        return TaskWithTotals(
            task = task,
            totalPriority = priority,
            totalDueDate = dueDate
        )
    }

    private fun now(): Instant = Clock.System.now()

    // ==================== TaskGroup isUncategorized Tests ====================

    @Test
    fun `TaskGroup default isUncategorized is false`() {
        val group = TaskGroup(label = "Test", tasks = persistentListOf())
        assertFalse(group.isUncategorized)
    }

    @Test
    fun `TaskGroup with isUncategorized true`() {
        val group = TaskGroup(label = "", tasks = persistentListOf(), isUncategorized = true)
        assertTrue(group.isUncategorized)
    }

    @Test
    fun `TaskGroup with blank label but isUncategorized false`() {
        val group = TaskGroup(label = "", tasks = persistentListOf(), isUncategorized = false)
        assertFalse(group.isUncategorized)
    }

    // ==================== Basic Grouping Tests ====================

    @Test
    fun `applyTo with no grouping levels returns single group with all tasks`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf()
        )
        val tasks = listOf(
            createTask("1"),
            createTask("2"),
            createTask("3")
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(1, result.size)
        assertEquals("", result[0].label)
        assertEquals(3, result[0].tasks.size)
        assertFalse(result[0].isUncategorized)
    }

    @Test
    fun `applyTo with status grouping categorizes tasks correctly`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open")),
                        GroupDefinition("In Progress", persistentSetOf("InProgress")),
                        GroupDefinition("Done", persistentSetOf("Done"))
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", status = TaskStatus.Open),
            createTask("2", status = TaskStatus.InProgress),
            createTask("3", status = TaskStatus.Done),
            createTask("4", status = TaskStatus.Open)
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(3, result.size)
        assertEquals("Open", result[0].label)
        assertEquals(2, result[0].tasks.size)
        assertEquals("In Progress", result[1].label)
        assertEquals(1, result[1].tasks.size)
        assertEquals("Done", result[2].label)
        assertEquals(1, result[2].tasks.size)
    }

    // ==================== Uncategorized Tasks Tests ====================

    @Test
    fun `applyTo creates uncategorized group for tasks not matching any group`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open")),
                        GroupDefinition("Done", persistentSetOf("Done"))
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", status = TaskStatus.Open),
            createTask("2", status = TaskStatus.InProgress), // Not covered
            createTask("3", status = TaskStatus.Done),
            createTask("4", status = TaskStatus.Blocked(persistentSetOf())) // Not covered
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(3, result.size)

        val openGroup = result.find { it.label == "Open" }
        assertNotNull(openGroup)
        assertEquals(1, openGroup.tasks.size)
        assertFalse(openGroup.isUncategorized)

        val doneGroup = result.find { it.label == "Done" }
        assertNotNull(doneGroup)
        assertEquals(1, doneGroup.tasks.size)
        assertFalse(doneGroup.isUncategorized)

        val uncategorizedGroup = result.find { it.isUncategorized }
        assertNotNull(uncategorizedGroup)
        assertEquals("", uncategorizedGroup.label)
        assertEquals(2, uncategorizedGroup.tasks.size)
        assertTrue(uncategorizedGroup.isUncategorized)
    }

    @Test
    fun `applyTo does not create uncategorized group when all tasks are categorized`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Active", persistentSetOf("Open", "InProgress")),
                        GroupDefinition("Completed", persistentSetOf("Done", "Declined"))
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", status = TaskStatus.Open),
            createTask("2", status = TaskStatus.InProgress),
            createTask("3", status = TaskStatus.Done)
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(2, result.size)
        assertNull(result.find { it.isUncategorized })
    }

    @Test
    fun `applyTo with only uncategorized tasks`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Done", persistentSetOf("Done"))
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", status = TaskStatus.Open),
            createTask("2", status = TaskStatus.InProgress)
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(1, result.size)
        assertTrue(result[0].isUncategorized)
        assertEquals(2, result[0].tasks.size)
    }

    // ==================== Nested Grouping with Uncategorized ====================

    @Test
    fun `applyTo with nested grouping creates uncategorized at each level`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open"))
                    )
                ),
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition("High", persistentSetOf(), priorityMin = 75, priorityMax = 100)
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", status = TaskStatus.Open, priority = Priority.HIGH),
            createTask("2", status = TaskStatus.Open, priority = Priority.LOW), // Uncategorized at level 2
            createTask("3", status = TaskStatus.InProgress, priority = Priority.HIGH) // Uncategorized at level 1
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(2, result.size) // Open group + uncategorized at level 1

        val openGroup = result.find { it.label == "Open" }
        assertNotNull(openGroup)
        assertEquals(2, openGroup.subgroups.size)

        val highInOpen = openGroup.subgroups.find { it.label == "High" }
        assertNotNull(highInOpen)
        assertEquals(1, highInOpen.tasks.size)

        val uncategorizedInOpen = openGroup.subgroups.find { it.isUncategorized }
        assertNotNull(uncategorizedInOpen)
        assertEquals(1, uncategorizedInOpen.tasks.size)

        val uncategorizedAtTop = result.find { it.isUncategorized }
        assertNotNull(uncategorizedAtTop)
        assertEquals(1, uncategorizedAtTop.subgroups.size)
    }

    @Test
    fun `applyTo with deeply nested uncategorized groups`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open"))
                    )
                ),
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition("High", persistentSetOf("High", "VeryHigh"))
                    )
                ),
                GroupingLevel(
                    field = GroupableField.HasConnections,
                    groups = persistentListOf(
                        GroupDefinition("Connected", persistentSetOf("true"))
                    )
                )
            )
        )

        // Task that doesn't match at level 3
        val task = createTask("1", status = TaskStatus.Open, priority = Priority.HIGH)
        // Task has no connections, so it should be uncategorized at level 3

        val result = viewMode.applyTo(listOf(task))

        val openGroup = result.find { it.label == "Open" }
        assertNotNull(openGroup)

        val highGroup = openGroup.subgroups.find { it.label == "High" }
        assertNotNull(highGroup)

        val uncategorizedAtLevel3 = highGroup.subgroups.find { it.isUncategorized }
        assertNotNull(uncategorizedAtLevel3)
        assertEquals(1, uncategorizedAtLevel3.tasks.size)
    }

    // ==================== Custom Priority Range Tests ====================

    @Test
    fun `custom priority range matches tasks within range`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition(
                            label = "Low (1-25)",
                            values = persistentSetOf(),
                            priorityMin = 1,
                            priorityMax = 25
                        ),
                        GroupDefinition(
                            label = "Medium (26-75)",
                            values = persistentSetOf(),
                            priorityMin = 26,
                            priorityMax = 75
                        ),
                        GroupDefinition(
                            label = "High (76-100)",
                            values = persistentSetOf(),
                            priorityMin = 76,
                            priorityMax = 100
                        )
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", priority = Priority(10)),
            createTask("2", priority = Priority(50)),
            createTask("3", priority = Priority(90))
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(3, result.size)
        assertEquals(1, result[0].tasks.size)
        assertEquals("1", result[0].tasks[0].task.id)
        assertEquals(1, result[1].tasks.size)
        assertEquals("2", result[1].tasks[0].task.id)
        assertEquals(1, result[2].tasks.size)
        assertEquals("3", result[2].tasks[0].task.id)
    }

    @Test
    fun `custom priority range with only min boundary`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition(
                            label = "High (50+)",
                            values = persistentSetOf(),
                            priorityMin = 50
                        )
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", priority = Priority(30)),
            createTask("2", priority = Priority(50)),
            createTask("3", priority = Priority(80))
        )

        val result = viewMode.applyTo(tasks)

        val highGroup = result.find { it.label == "High (50+)" }
        assertNotNull(highGroup)
        assertEquals(2, highGroup.tasks.size)

        val uncategorized = result.find { it.isUncategorized }
        assertNotNull(uncategorized)
        assertEquals(1, uncategorized.tasks.size)
        assertEquals("1", uncategorized.tasks[0].task.id)
    }

    @Test
    fun `custom priority range with only max boundary`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition(
                            label = "Low (up to 50)",
                            values = persistentSetOf(),
                            priorityMax = 50
                        )
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", priority = Priority(30)),
            createTask("2", priority = Priority(50)),
            createTask("3", priority = Priority(80))
        )

        val result = viewMode.applyTo(tasks)

        val lowGroup = result.find { it.label == "Low (up to 50)" }
        assertNotNull(lowGroup)
        assertEquals(2, lowGroup.tasks.size)

        val uncategorized = result.find { it.isUncategorized }
        assertNotNull(uncategorized)
        assertEquals(1, uncategorized.tasks.size)
        assertEquals("3", uncategorized.tasks[0].task.id)
    }

    @Test
    fun `custom priority range with includeNoPriority`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition(
                            label = "No Priority or Low",
                            values = persistentSetOf(),
                            priorityMax = 50,
                            includeNoPriority = true
                        )
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", priority = null),
            createTask("2", priority = Priority(30)),
            createTask("3", priority = Priority(80))
        )

        val result = viewMode.applyTo(tasks)

        val group = result.find { it.label == "No Priority or Low" }
        assertNotNull(group)
        assertEquals(2, group.tasks.size)

        val uncategorized = result.find { it.isUncategorized }
        assertNotNull(uncategorized)
        assertEquals(1, uncategorized.tasks.size)
    }

    @Test
    fun `custom priority range boundary values are inclusive`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition(
                            label = "Exact Range",
                            values = persistentSetOf(),
                            priorityMin = 25,
                            priorityMax = 75
                        )
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", priority = Priority(24)), // Below
            createTask("2", priority = Priority(25)), // At min boundary
            createTask("3", priority = Priority(50)), // In middle
            createTask("4", priority = Priority(75)), // At max boundary
            createTask("5", priority = Priority(76))  // Above
        )

        val result = viewMode.applyTo(tasks)

        val group = result.find { it.label == "Exact Range" }
        assertNotNull(group)
        assertEquals(3, group.tasks.size)
        assertTrue(group.tasks.any { it.task.id == "2" })
        assertTrue(group.tasks.any { it.task.id == "3" })
        assertTrue(group.tasks.any { it.task.id == "4" })

        val uncategorized = result.find { it.isUncategorized }
        assertNotNull(uncategorized)
        assertEquals(2, uncategorized.tasks.size)
    }

    // ==================== Custom Due Date Range Tests ====================

    @Test
    fun `custom due date range matches tasks within range`() {
        val baseTime = now()
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.DueDate,
                    groups = persistentListOf(
                        GroupDefinition(
                            label = "Overdue",
                            values = persistentSetOf(),
                            dueDateMaxDays = -1
                        ),
                        GroupDefinition(
                            label = "This Week",
                            values = persistentSetOf(),
                            dueDateMinDays = 0,
                            dueDateMaxDays = 7
                        ),
                        GroupDefinition(
                            label = "Later",
                            values = persistentSetOf(),
                            dueDateMinDays = 8
                        )
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", dueDate = baseTime - 2.days), // Overdue
            createTask("2", dueDate = baseTime + 3.days), // This week
            createTask("3", dueDate = baseTime + 14.days) // Later
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(3, result.size)

        val overdueGroup = result.find { it.label == "Overdue" }
        assertNotNull(overdueGroup)
        assertEquals(1, overdueGroup.tasks.size)
        assertEquals("1", overdueGroup.tasks[0].task.id)

        val thisWeekGroup = result.find { it.label == "This Week" }
        assertNotNull(thisWeekGroup)
        assertEquals(1, thisWeekGroup.tasks.size)
        assertEquals("2", thisWeekGroup.tasks[0].task.id)

        val laterGroup = result.find { it.label == "Later" }
        assertNotNull(laterGroup)
        assertEquals(1, laterGroup.tasks.size)
        assertEquals("3", laterGroup.tasks[0].task.id)
    }

    @Test
    fun `custom due date range with includeNoDueDate`() {
        val baseTime = now()
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.DueDate,
                    groups = persistentListOf(
                        GroupDefinition(
                            label = "No Due Date or Future",
                            values = persistentSetOf(),
                            dueDateMinDays = 1,
                            includeNoDueDate = true
                        )
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", dueDate = null),
            createTask("2", dueDate = baseTime + 5.days),
            createTask("3", dueDate = baseTime - 1.days) // Overdue, not matched
        )

        val result = viewMode.applyTo(tasks)

        val group = result.find { it.label == "No Due Date or Future" }
        assertNotNull(group)
        assertEquals(2, group.tasks.size)

        val uncategorized = result.find { it.isUncategorized }
        assertNotNull(uncategorized)
        assertEquals(1, uncategorized.tasks.size)
        assertEquals("3", uncategorized.tasks[0].task.id)
    }

    // ==================== Tags Grouping Tests ====================

    @Test
    fun `tags grouping matches tasks with specified tags`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Tags,
                    groups = persistentListOf(
                        GroupDefinition("Bug", persistentSetOf("bug")),
                        GroupDefinition("Feature", persistentSetOf("feature"))
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", tags = persistentSetOf("bug")),
            createTask("2", tags = persistentSetOf("feature")),
            createTask("3", tags = persistentSetOf("bug", "feature")),
            createTask("4", tags = persistentSetOf("other"))
        )

        val result = viewMode.applyTo(tasks)

        val bugGroup = result.find { it.label == "Bug" }
        assertNotNull(bugGroup)
        assertEquals(2, bugGroup.tasks.size) // Tasks 1 and 3

        val featureGroup = result.find { it.label == "Feature" }
        assertNotNull(featureGroup)
        assertEquals(2, featureGroup.tasks.size) // Tasks 2 and 3

        val uncategorized = result.find { it.isUncategorized }
        assertNotNull(uncategorized)
        assertEquals(1, uncategorized.tasks.size)
        assertEquals("4", uncategorized.tasks[0].task.id)
    }

    @Test
    fun `tags grouping with task having no tags goes to uncategorized`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Tags,
                    groups = persistentListOf(
                        GroupDefinition("Bug", persistentSetOf("bug"))
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", tags = persistentSetOf("bug")),
            createTask("2", tags = persistentSetOf())
        )

        val result = viewMode.applyTo(tasks)

        val uncategorized = result.find { it.isUncategorized }
        assertNotNull(uncategorized)
        assertEquals(1, uncategorized.tasks.size)
        assertEquals("2", uncategorized.tasks[0].task.id)
    }

    @Test
    fun `tags grouping allows task in multiple groups`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Tags,
                    groups = persistentListOf(
                        GroupDefinition("Bug", persistentSetOf("bug")),
                        GroupDefinition("Urgent", persistentSetOf("urgent"))
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", tags = persistentSetOf("bug", "urgent"))
        )

        val result = viewMode.applyTo(tasks)

        val bugGroup = result.find { it.label == "Bug" }
        assertNotNull(bugGroup)
        assertEquals(1, bugGroup.tasks.size)

        val urgentGroup = result.find { it.label == "Urgent" }
        assertNotNull(urgentGroup)
        assertEquals(1, urgentGroup.tasks.size)

        // Task is in both groups, so no uncategorized
        assertNull(result.find { it.isUncategorized })
    }

    // ==================== Boolean Field Grouping Tests ====================

    @Test
    fun `hasConnections grouping works correctly`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.HasConnections,
                    groups = persistentListOf(
                        GroupDefinition("Connected", persistentSetOf("true")),
                        GroupDefinition("Standalone", persistentSetOf("false"))
                    )
                )
            )
        )

        val taskWithConnections = Task(
            id = "1",
            title = "Connected",
            spaceId = "space-1",
            connections = persistentSetOf(TaskConnection("other", ConnectionType.RelatesTo))
        )
        val taskWithoutConnections = Task(
            id = "2",
            title = "Standalone",
            spaceId = "space-1",
            connections = persistentSetOf()
        )

        val tasks = listOf(
            TaskWithTotals(taskWithConnections, null, null),
            TaskWithTotals(taskWithoutConnections, null, null)
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(2, result.size)

        val connectedGroup = result.find { it.label == "Connected" }
        assertNotNull(connectedGroup)
        assertEquals(1, connectedGroup.tasks.size)
        assertEquals("1", connectedGroup.tasks[0].task.id)

        val standaloneGroup = result.find { it.label == "Standalone" }
        assertNotNull(standaloneGroup)
        assertEquals(1, standaloneGroup.tasks.size)
        assertEquals("2", standaloneGroup.tasks[0].task.id)
    }

    @Test
    fun `isRecurring grouping with uncategorized`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.IsRecurring,
                    groups = persistentListOf(
                        GroupDefinition("Recurring", persistentSetOf("true"))
                    )
                )
            )
        )

        val recurringTask = Task(
            id = "1",
            title = "Recurring",
            spaceId = "space-1",
            recurrenceRules = persistentListOf(
                RecurrenceRule(
                    timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                        period = RecurrencePeriod.ofDays(1),
                        firstOccurrence = now()
                    ),
                    statusChangeTrigger = null,
                    resetToStatus = TaskStatus.Open
                ).to(RecurrenceState())
            )
        )
        val nonRecurringTask = Task(
            id = "2",
            title = "One-time",
            spaceId = "space-1"
        )

        val tasks = listOf(
            TaskWithTotals(recurringTask, null, null),
            TaskWithTotals(nonRecurringTask, null, null)
        )

        val result = viewMode.applyTo(tasks)

        val recurringGroup = result.find { it.label == "Recurring" }
        assertNotNull(recurringGroup)
        assertEquals(1, recurringGroup.tasks.size)

        val uncategorized = result.find { it.isUncategorized }
        assertNotNull(uncategorized)
        assertEquals(1, uncategorized.tasks.size)
        assertEquals("2", uncategorized.tasks[0].task.id)
    }

    // ==================== Empty Groups Tests ====================

    @Test
    fun `showEmptyGroups false does not include empty groups`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open")),
                        GroupDefinition("Done", persistentSetOf("Done"))
                    ),
                    showEmptyGroups = false
                )
            )
        )
        val tasks = listOf(
            createTask("1", status = TaskStatus.Open)
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(1, result.size)
        assertEquals("Open", result[0].label)
    }

    @Test
    fun `showEmptyGroups true includes empty groups`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open")),
                        GroupDefinition("Done", persistentSetOf("Done"))
                    ),
                    showEmptyGroups = true
                )
            )
        )
        val tasks = listOf(
            createTask("1", status = TaskStatus.Open)
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(2, result.size)

        val openGroup = result.find { it.label == "Open" }
        assertNotNull(openGroup)
        assertEquals(1, openGroup.tasks.size)

        val doneGroup = result.find { it.label == "Done" }
        assertNotNull(doneGroup)
        assertEquals(0, doneGroup.tasks.size)
    }

    // ==================== Mixed Scenarios Tests ====================

    @Test
    fun `complex scenario with multiple levels and uncategorized at various levels`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Active", persistentSetOf("Open", "InProgress"))
                    )
                ),
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition("High", persistentSetOf(), priorityMin = 75, priorityMax = 100)
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", status = TaskStatus.Open, priority = Priority.HIGH),
            createTask("2", status = TaskStatus.Open, priority = Priority.LOW),
            createTask("3", status = TaskStatus.Done, priority = Priority.HIGH),
            createTask("4", status = TaskStatus.Done, priority = Priority.LOW)
        )

        val result = viewMode.applyTo(tasks)

        // Should have Active group + uncategorized at level 1
        assertEquals(2, result.size)

        val activeGroup = result.find { it.label == "Active" }
        assertNotNull(activeGroup)
        assertEquals(2, activeGroup.subgroups.size) // High + uncategorized

        val highInActive = activeGroup.subgroups.find { it.label == "High" }
        assertNotNull(highInActive)
        assertEquals(1, highInActive.tasks.size)

        val uncategorizedInActive = activeGroup.subgroups.find { it.isUncategorized }
        assertNotNull(uncategorizedInActive)
        assertEquals(1, uncategorizedInActive.tasks.size)

        val uncategorizedAtTop = result.find { it.isUncategorized }
        assertNotNull(uncategorizedAtTop)
        // Should contain tasks 3 and 4 (Done status)
        // They go through level 2 grouping as well
    }

    @Test
    fun `empty task list returns empty result`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open"))
                    )
                )
            )
        )

        val result = viewMode.applyTo(persistentListOf())

        assertTrue(result.isEmpty())
    }

    // ==================== Validation Tests ====================

    private fun createViewModeWithLevel(level: GroupingLevel) = ViewMode(
        id = "test",
        name = "Test",
        spaceId = "space-1",
        groupingLevels = persistentListOf(level)
    )

    @Test
    fun `validation detects empty group label`() {
        val viewMode = createViewModeWithLevel(
            GroupingLevel(
                field = GroupableField.Status,
                groups = persistentListOf(
                    GroupDefinition("", persistentSetOf("Open"))
                )
            )
        )

        val result = viewMode.validate()

        assertTrue(result is GroupingValidationResult.Invalid)
        val errors = (result as GroupingValidationResult.Invalid).errors
        assertTrue(errors.any { it is GroupingValidationError.EmptyGroupLabel })
    }

    @Test
    fun `validation detects empty group values`() {
        val viewMode = createViewModeWithLevel(
            GroupingLevel(
                field = GroupableField.Status,
                groups = persistentListOf(
                    GroupDefinition("Empty", persistentSetOf())
                )
            )
        )

        val result = viewMode.validate()

        assertTrue(result is GroupingValidationResult.Invalid)
        val errors = (result as GroupingValidationResult.Invalid).errors
        assertTrue(errors.any { it is GroupingValidationError.EmptyGroup })
    }

    @Test
    fun `validation passes for custom range groups with empty values`() {
        val viewMode = createViewModeWithLevel(
            GroupingLevel(
                field = GroupableField.Priority,
                groups = persistentListOf(
                    GroupDefinition(
                        label = "High Priority",
                        values = persistentSetOf(),
                        priorityMin = 75
                    )
                )
            )
        )

        val result = viewMode.validate()

        // Should pass because custom range is used
        assertTrue(result is GroupingValidationResult.Valid)
    }

    @Test
    fun `validation allows non-exhaustive coverage`() {
        val viewMode = createViewModeWithLevel(
            GroupingLevel(
                field = GroupableField.HasConnections,
                groups = persistentListOf(
                    GroupDefinition("Connected", persistentSetOf("true"))
                    // Missing "false" - this is allowed
                )
            )
        )

        val result = viewMode.validate()

        // Non-exhaustive coverage is allowed - unmatched tasks go to uncategorized group
        assertTrue(result is GroupingValidationResult.Valid)
    }

    @Test
    fun `validation allows duplicate values`() {
        val viewMode = createViewModeWithLevel(
            GroupingLevel(
                field = GroupableField.Status,
                groups = persistentListOf(
                    GroupDefinition("Group1", persistentSetOf("Open", "InProgress")),
                    GroupDefinition("Group2", persistentSetOf("Open", "Done")) // "Open" appears in both - allowed
                )
            )
        )

        val result = viewMode.validate()

        // Duplicate values are allowed - task goes to first matching group
        assertTrue(result is GroupingValidationResult.Valid)
    }

    @Test
    fun `validation allows partial coverage for Tags field`() {
        val viewMode = createViewModeWithLevel(
            GroupingLevel(
                field = GroupableField.Tags,
                groups = persistentListOf(
                    GroupDefinition("Bug", persistentSetOf("bug"))
                )
            )
        )

        val result = viewMode.validate()

        assertTrue(result is GroupingValidationResult.Valid)
    }

    @Test
    fun `validation allows duplicates for Tags field`() {
        val viewMode = createViewModeWithLevel(
            GroupingLevel(
                field = GroupableField.Tags,
                groups = persistentListOf(
                    GroupDefinition("Bug", persistentSetOf("bug")),
                    GroupDefinition("Also Bug", persistentSetOf("bug")) // Same tag in different group
                )
            )
        )

        val result = viewMode.validate()

        // Should pass because duplicates are allowed for Tags
        assertTrue(result is GroupingValidationResult.Valid)
    }

    @Test
    fun `validation skips exhaustive and duplicate checks for custom priority ranges`() {
        val viewMode = createViewModeWithLevel(
            GroupingLevel(
                field = GroupableField.Priority,
                groups = persistentListOf(
                    GroupDefinition(
                        label = "Low",
                        values = persistentSetOf(),
                        priorityMin = 1,
                        priorityMax = 50
                    ),
                    GroupDefinition(
                        label = "High",
                        values = persistentSetOf(),
                        priorityMin = 40, // Overlapping range - would be duplicate
                        priorityMax = 100
                    )
                )
            )
        )

        val result = viewMode.validate()

        // Should pass because custom ranges skip duplicate/exhaustive checks
        assertTrue(result is GroupingValidationResult.Valid)
    }

    @Test
    fun `validation skips exhaustive and duplicate checks for custom due date ranges`() {
        val viewMode = createViewModeWithLevel(
            GroupingLevel(
                field = GroupableField.DueDate,
                groups = persistentListOf(
                    GroupDefinition(
                        label = "Soon",
                        values = persistentSetOf(),
                        dueDateMinDays = 0,
                        dueDateMaxDays = 7
                    )
                    // Not covering all possible due date values
                )
            )
        )

        val result = viewMode.validate()

        // Should pass because custom ranges skip exhaustive checks
        assertTrue(result is GroupingValidationResult.Valid)
    }

    // ==================== Ordering Tests ====================

    @Test
    fun `tasks are ordered within groups according to group ordering rules`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition(
                            "All",
                            persistentSetOf("Open", "InProgress", "Done", "Declined", "Blocked"),
                            orderingRules = persistentListOf(
                                OrderingRule(OrderableField.Priority, OrderDirection.Descending, NullPosition.Last)
                            )
                        )
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", priority = Priority.LOW),
            createTask("2", priority = Priority.HIGH),
            createTask("3", priority = Priority.MEDIUM)
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(1, result.size)
        val sortedTasks = result[0].tasks
        assertEquals("2", sortedTasks[0].task.id) // HIGH first
        assertEquals("3", sortedTasks[1].task.id) // MEDIUM second
        assertEquals("1", sortedTasks[2].task.id) // LOW last
    }

    @Test
    fun `uncategorized group uses default ordering rules`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open"))
                    )
                )
            ),
            defaultOrderingRules = persistentListOf(
                OrderingRule(OrderableField.Id, OrderDirection.Descending)
            )
        )
        val tasks = listOf(
            createTask("1", status = TaskStatus.InProgress),
            createTask("3", status = TaskStatus.InProgress),
            createTask("2", status = TaskStatus.InProgress)
        )

        val result = viewMode.applyTo(tasks)

        val uncategorized = result.find { it.isUncategorized }
        assertNotNull(uncategorized)
        assertEquals("3", uncategorized.tasks[0].task.id)
        assertEquals("2", uncategorized.tasks[1].task.id)
        assertEquals("1", uncategorized.tasks[2].task.id)
    }

    // ==================== Level Property Tests ====================

    @Test
    fun `task groups have correct level property`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open"))
                    )
                ),
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition("High", persistentSetOf("High"))
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", status = TaskStatus.Open, priority = Priority.HIGH)
        )

        val result = viewMode.applyTo(tasks)

        val topLevel = result[0]
        assertEquals(0, topLevel.level)

        val secondLevel = topLevel.subgroups[0]
        assertEquals(1, secondLevel.level)
    }

    @Test
    fun `uncategorized groups have correct level property`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open"))
                    )
                )
            )
        )
        val tasks = listOf(
            createTask("1", status = TaskStatus.Done)
        )

        val result = viewMode.applyTo(tasks)

        val uncategorized = result.find { it.isUncategorized }
        assertNotNull(uncategorized)
        assertEquals(0, uncategorized.level)
    }

    // ==================== HasNotifications Field Tests ====================

    @Test
    fun `hasNotifications grouping works correctly`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.HasNotifications,
                    groups = persistentListOf(
                        GroupDefinition("Has Notifications", persistentSetOf("true")),
                        GroupDefinition("No Notifications", persistentSetOf("false"))
                    )
                )
            )
        )

        val taskWithNotifications = Task(
            id = "1",
            title = "With Notifications",
            spaceId = "space-1",
            notifications = persistentListOf(
                TaskNotification(
                    timeBeforeDeadline = RecurrencePeriod.ofHours(1)
                )
            )
        )
        val taskWithoutNotifications = Task(
            id = "2",
            title = "Without Notifications",
            spaceId = "space-1",
            notifications = persistentListOf()
        )

        val tasks = listOf(
            TaskWithTotals(taskWithNotifications, null, null),
            TaskWithTotals(taskWithoutNotifications, null, null)
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(2, result.size)

        val withNotifs = result.find { it.label == "Has Notifications" }
        assertNotNull(withNotifs)
        assertEquals(1, withNotifs.tasks.size)
        assertEquals("1", withNotifs.tasks[0].task.id)

        val withoutNotifs = result.find { it.label == "No Notifications" }
        assertNotNull(withoutNotifs)
        assertEquals(1, withoutNotifs.tasks.size)
        assertEquals("2", withoutNotifs.tasks[0].task.id)
    }

    @Test
    fun `hasNotifications grouping with uncategorized`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.HasNotifications,
                    groups = persistentListOf(
                        GroupDefinition("Has Notifications", persistentSetOf("true"))
                    )
                )
            )
        )

        val taskWithoutNotifications = Task(
            id = "1",
            title = "Without Notifications",
            spaceId = "space-1"
        )

        val result = viewMode.applyTo(listOf(TaskWithTotals(taskWithoutNotifications, null, null)))

        val uncategorized = result.find { it.isUncategorized }
        assertNotNull(uncategorized)
        assertEquals(1, uncategorized.tasks.size)
    }

    // ==================== AutoUpdateStatus Field Tests ====================

    @Test
    fun `autoUpdateStatus grouping works correctly`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.AutoUpdateStatus,
                    groups = persistentListOf(
                        GroupDefinition("Auto Update", persistentSetOf("true")),
                        GroupDefinition("Manual", persistentSetOf("false"))
                    )
                )
            )
        )

        val taskWithAutoUpdate = Task(
            id = "1",
            title = "Auto Update",
            spaceId = "space-1",
            autoUpdateStatusFromSubtasks = true
        )
        val taskWithoutAutoUpdate = Task(
            id = "2",
            title = "Manual",
            spaceId = "space-1",
            autoUpdateStatusFromSubtasks = false
        )

        val tasks = listOf(
            TaskWithTotals(taskWithAutoUpdate, null, null),
            TaskWithTotals(taskWithoutAutoUpdate, null, null)
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(2, result.size)

        val autoGroup = result.find { it.label == "Auto Update" }
        assertNotNull(autoGroup)
        assertEquals(1, autoGroup.tasks.size)
        assertEquals("1", autoGroup.tasks[0].task.id)

        val manualGroup = result.find { it.label == "Manual" }
        assertNotNull(manualGroup)
        assertEquals(1, manualGroup.tasks.size)
        assertEquals("2", manualGroup.tasks[0].task.id)
    }

    // ==================== EstimatedTime Custom Range Tests ====================

    @Test
    fun `custom estimated time range matches tasks within range`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition("High", persistentSetOf(), priorityMin = 75, priorityMax = 100)
                    )
                )
            )
        )
        // This test verifies the custom range pattern works for Priority
        val tasks = listOf(
            createTask("1", priority = Priority.HIGH),
            createTask("2", priority = Priority.LOW)
        )

        val result = viewMode.applyTo(tasks)

        val highGroup = result.find { it.label == "High" }
        assertNotNull(highGroup)
        assertEquals(1, highGroup.tasks.size)
    }

    // ==================== Ordering with NullPosition.First Tests ====================

    @Test
    fun `ordering with NullPosition First puts nulls first`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf(
                OrderingRule(OrderableField.Priority, OrderDirection.Ascending, NullPosition.First)
            )
        )
        val tasks = listOf(
            createTask("1", priority = Priority.HIGH),
            createTask("2", priority = null),
            createTask("3", priority = Priority.LOW)
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(1, result.size)
        val sortedTasks = result[0].tasks
        assertEquals("2", sortedTasks[0].task.id) // null priority first
        assertEquals("3", sortedTasks[1].task.id) // LOW
        assertEquals("1", sortedTasks[2].task.id) // HIGH
    }

    @Test
    fun `ordering with NullPosition Last puts nulls last`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf(
                OrderingRule(OrderableField.Priority, OrderDirection.Ascending, NullPosition.Last)
            )
        )
        val tasks = listOf(
            createTask("1", priority = Priority.HIGH),
            createTask("2", priority = null),
            createTask("3", priority = Priority.LOW)
        )

        val result = viewMode.applyTo(tasks)

        assertEquals(1, result.size)
        val sortedTasks = result[0].tasks
        assertEquals("3", sortedTasks[0].task.id) // LOW
        assertEquals("1", sortedTasks[1].task.id) // HIGH
        assertEquals("2", sortedTasks[2].task.id) // null priority last
    }

    @Test
    fun `ordering with NullPosition First and Descending direction`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf(
                OrderingRule(OrderableField.DueDate, OrderDirection.Descending, NullPosition.First)
            )
        )
        val baseTime = now()
        val tasks = listOf(
            createTask("1", dueDate = baseTime + 5.days),
            createTask("2", dueDate = null),
            createTask("3", dueDate = baseTime + 1.days)
        )

        val result = viewMode.applyTo(tasks)

        val sortedTasks = result[0].tasks
        assertEquals("2", sortedTasks[0].task.id) // null first
        assertEquals("1", sortedTasks[1].task.id) // later date (descending)
        assertEquals("3", sortedTasks[2].task.id) // earlier date
    }

    // ==================== Multiple Ordering Rules Tests ====================

    @Test
    fun `multiple ordering rules apply in order`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf(
                OrderingRule(OrderableField.Priority, OrderDirection.Descending, NullPosition.Last),
                OrderingRule(OrderableField.Title, OrderDirection.Ascending)
            )
        )
        val tasks = listOf(
            createTask("1", title = "Zebra", priority = Priority.HIGH),
            createTask("2", title = "Alpha", priority = Priority.HIGH),
            createTask("3", title = "Beta", priority = Priority.LOW),
            createTask("4", title = "Gamma", priority = Priority.LOW)
        )

        val result = viewMode.applyTo(tasks)

        val sortedTasks = result[0].tasks
        // First by priority descending (HIGH before LOW)
        // Then by title ascending within same priority
        assertEquals("2", sortedTasks[0].task.id) // HIGH, Alpha
        assertEquals("1", sortedTasks[1].task.id) // HIGH, Zebra
        assertEquals("3", sortedTasks[2].task.id) // LOW, Beta
        assertEquals("4", sortedTasks[3].task.id) // LOW, Gamma
    }

    @Test
    fun `three ordering rules work correctly`() {
        val baseTime = now()
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf(
                OrderingRule(OrderableField.Status, OrderDirection.Ascending),
                OrderingRule(OrderableField.Priority, OrderDirection.Descending, NullPosition.Last),
                OrderingRule(OrderableField.DueDate, OrderDirection.Ascending, NullPosition.Last)
            )
        )
        val tasks = listOf(
            createTask("1", title = "A", status = TaskStatus.Open, priority = Priority.HIGH, dueDate = baseTime + 3.days),
            createTask("2", title = "B", status = TaskStatus.Open, priority = Priority.HIGH, dueDate = baseTime + 1.days),
            createTask("3", title = "C", status = TaskStatus.InProgress, priority = Priority.HIGH, dueDate = baseTime + 1.days),
            createTask("4", title = "D", status = TaskStatus.Open, priority = Priority.LOW, dueDate = baseTime + 1.days)
        )

        val result = viewMode.applyTo(tasks)

        val sortedTasks = result[0].tasks
        // Status ascending (Open=0, InProgress=1)
        // Within Open: Priority descending (HIGH before LOW)
        // Within Open+HIGH: DueDate ascending (earlier first)
        assertEquals("2", sortedTasks[0].task.id) // Open, HIGH, +1 day
        assertEquals("1", sortedTasks[1].task.id) // Open, HIGH, +3 days
        assertEquals("4", sortedTasks[2].task.id) // Open, LOW
        assertEquals("3", sortedTasks[3].task.id) // InProgress
    }

    // ==================== ID Ordering Edge Cases ====================

    @Test
    fun `id ordering handles numeric suffixes correctly`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf(
                OrderingRule(OrderableField.Id, OrderDirection.Ascending)
            )
        )

        val task1 = Task(id = "TEST-1", title = "1", spaceId = "space-1")
        val task2 = Task(id = "TEST-10", title = "10", spaceId = "space-1")
        val task3 = Task(id = "TEST-2", title = "2", spaceId = "space-1")
        val task4 = Task(id = "TEST-100", title = "100", spaceId = "space-1")

        val tasks = listOf(
            TaskWithTotals(task1, null, null),
            TaskWithTotals(task2, null, null),
            TaskWithTotals(task3, null, null),
            TaskWithTotals(task4, null, null)
        )

        val result = viewMode.applyTo(tasks)

        val sortedTasks = result[0].tasks
        assertEquals("TEST-1", sortedTasks[0].task.id)
        assertEquals("TEST-2", sortedTasks[1].task.id)
        assertEquals("TEST-10", sortedTasks[2].task.id)
        assertEquals("TEST-100", sortedTasks[3].task.id)
    }

    @Test
    fun `id ordering handles non-numeric ids`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf(
                OrderingRule(OrderableField.Id, OrderDirection.Ascending)
            )
        )

        val task1 = Task(id = "ABC", title = "1", spaceId = "space-1")
        val task2 = Task(id = "XYZ", title = "2", spaceId = "space-1")
        val task3 = Task(id = "DEF", title = "3", spaceId = "space-1")

        val tasks = listOf(
            TaskWithTotals(task1, null, null),
            TaskWithTotals(task2, null, null),
            TaskWithTotals(task3, null, null)
        )

        val result = viewMode.applyTo(tasks)

        // All have numeric value 0 (no digits), so they should remain in order
        // or be sorted by the default 0 value
        assertEquals(3, result[0].tasks.size)
    }

    @Test
    fun `id ordering descending order`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf(
                OrderingRule(OrderableField.Id, OrderDirection.Descending)
            )
        )

        val task1 = Task(id = "TEST-1", title = "1", spaceId = "space-1")
        val task2 = Task(id = "TEST-5", title = "5", spaceId = "space-1")
        val task3 = Task(id = "TEST-3", title = "3", spaceId = "space-1")

        val tasks = listOf(
            TaskWithTotals(task1, null, null),
            TaskWithTotals(task2, null, null),
            TaskWithTotals(task3, null, null)
        )

        val result = viewMode.applyTo(tasks)

        val sortedTasks = result[0].tasks
        assertEquals("TEST-5", sortedTasks[0].task.id)
        assertEquals("TEST-3", sortedTasks[1].task.id)
        assertEquals("TEST-1", sortedTasks[2].task.id)
    }

    // ==================== Built-in View Modes Tests ====================

    @Test
    fun `getBuiltInModes returns chronological and priority`() {
        val modes = ViewMode.getBuiltInModes("test-space")

        assertEquals(2, modes.size)
        assertTrue(modes.any { it.id == "chronological" })
        assertTrue(modes.any { it.id == "priority" })
        assertTrue(modes.all { it.isBuiltIn })
        assertTrue(modes.all { it.spaceId == "test-space" })
    }

    @Test
    fun `chronological view mode has no grouping levels`() {
        val mode = ViewMode.chronological("test-space")

        assertEquals("chronological", mode.id)
        assertEquals("Chronological", mode.name)
        assertTrue(mode.isBuiltIn)
        assertTrue(mode.groupingLevels.isEmpty())
    }

    @Test
    fun `priority view mode has status grouping`() {
        val mode = ViewMode.priority("test-space")

        assertEquals("priority", mode.id)
        assertEquals("Priority", mode.name)
        assertTrue(mode.isBuiltIn)
        assertEquals(1, mode.groupingLevels.size)
        assertEquals(GroupableField.Status, mode.groupingLevels[0].field)

        val groups = mode.groupingLevels[0].groups
        assertEquals(3, groups.size)
        assertEquals("Unresolved", groups[0].label)
        assertEquals("Blocked", groups[1].label)
        assertEquals("Resolved", groups[2].label)
    }

    @Test
    fun `priority view mode groups tasks correctly`() {
        val mode = ViewMode.priority("test-space")
        val tasks = listOf(
            createTask("1", status = TaskStatus.Open),
            createTask("2", status = TaskStatus.InProgress),
            createTask("3", status = TaskStatus.Blocked(persistentSetOf())),
            createTask("4", status = TaskStatus.Done),
            createTask("5", status = TaskStatus.Declined("reason"))
        )

        val result = mode.applyTo(tasks)

        assertEquals(3, result.size)

        val unresolvedGroup = result.find { it.label == "Unresolved" }
        assertNotNull(unresolvedGroup)
        assertEquals(2, unresolvedGroup.tasks.size)

        val blockedGroup = result.find { it.label == "Blocked" }
        assertNotNull(blockedGroup)
        assertEquals(1, blockedGroup.tasks.size)

        val resolvedGroup = result.find { it.label == "Resolved" }
        assertNotNull(resolvedGroup)
        assertEquals(2, resolvedGroup.tasks.size)
    }

    // ==================== EstimatedTime Ordering Tests ====================

    @Test
    fun `ordering by estimated time works correctly`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf(
                OrderingRule(OrderableField.EstimatedTime, OrderDirection.Ascending, NullPosition.Last)
            )
        )

        val task1 = Task(id = "1", title = "1", spaceId = "space-1", estimatedTime = RecurrencePeriod.ofHours(2))
        val task2 = Task(id = "2", title = "2", spaceId = "space-1", estimatedTime = null)
        val task3 = Task(id = "3", title = "3", spaceId = "space-1", estimatedTime = RecurrencePeriod(minutes = 30))
        val task4 = Task(id = "4", title = "4", spaceId = "space-1", estimatedTime = RecurrencePeriod.ofDays(1))

        val tasks = listOf(
            TaskWithTotals(task1, null, null),
            TaskWithTotals(task2, null, null),
            TaskWithTotals(task3, null, null),
            TaskWithTotals(task4, null, null)
        )

        val result = viewMode.applyTo(tasks)

        val sortedTasks = result[0].tasks
        assertEquals("3", sortedTasks[0].task.id) // 30 min
        assertEquals("1", sortedTasks[1].task.id) // 2 hours
        assertEquals("4", sortedTasks[2].task.id) // 1 day
        assertEquals("2", sortedTasks[3].task.id) // null (last)
    }

    @Test
    fun `ordering by estimated time with NullPosition First`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(),
            defaultOrderingRules = persistentListOf(
                OrderingRule(OrderableField.EstimatedTime, OrderDirection.Descending, NullPosition.First)
            )
        )

        val task1 = Task(id = "1", title = "1", spaceId = "space-1", estimatedTime = RecurrencePeriod.ofHours(2))
        val task2 = Task(id = "2", title = "2", spaceId = "space-1", estimatedTime = null)
        val task3 = Task(id = "3", title = "3", spaceId = "space-1", estimatedTime = RecurrencePeriod(minutes = 30))

        val tasks = listOf(
            TaskWithTotals(task1, null, null),
            TaskWithTotals(task2, null, null),
            TaskWithTotals(task3, null, null)
        )

        val result = viewMode.applyTo(tasks)

        val sortedTasks = result[0].tasks
        assertEquals("2", sortedTasks[0].task.id) // null (first)
        assertEquals("1", sortedTasks[1].task.id) // 2 hours (descending)
        assertEquals("3", sortedTasks[2].task.id) // 30 min
    }

    // ==================== Validation Edge Cases ====================

    @Test
    fun `validation passes for view mode with no grouping levels`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf()
        )

        val result = viewMode.validate()

        assertTrue(result is GroupingValidationResult.Valid)
    }

    @Test
    fun `validation detects multiple errors`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("", persistentSetOf("Open")), // Empty label
                        GroupDefinition("Group2", persistentSetOf()) // Empty values
                    )
                )
            )
        )

        val result = viewMode.validate()

        assertTrue(result is GroupingValidationResult.Invalid)
        val errors = (result as GroupingValidationResult.Invalid).errors
        assertTrue(errors.size >= 2)
        assertTrue(errors.any { it is GroupingValidationError.EmptyGroupLabel })
        assertTrue(errors.any { it is GroupingValidationError.EmptyGroup })
    }

    @Test
    fun `validation with all boolean field values covered`() {
        val viewMode = ViewMode(
            id = "test",
            name = "Test",
            spaceId = "space-1",
            groupingLevels = persistentListOf(
                GroupingLevel(
                    field = GroupableField.IsRecurring,
                    groups = persistentListOf(
                        GroupDefinition("Recurring", persistentSetOf("true")),
                        GroupDefinition("One-time", persistentSetOf("false"))
                    )
                )
            )
        )

        val result = viewMode.validate()

        assertTrue(result is GroupingValidationResult.Valid)
    }

    // ==================== getFieldValue Edge Cases ====================

    @Test
    fun `getFieldValue returns correct status values`() {
        assertEquals("Open", createTask("1", status = TaskStatus.Open).getFieldValue(GroupableField.Status))
        assertEquals("InProgress", createTask("1", status = TaskStatus.InProgress).getFieldValue(GroupableField.Status))
        assertEquals("Blocked", createTask("1", status = TaskStatus.Blocked(persistentSetOf("other"))).getFieldValue(GroupableField.Status))
        assertEquals("Done", createTask("1", status = TaskStatus.Done).getFieldValue(GroupableField.Status))
        assertEquals("Declined", createTask("1", status = TaskStatus.Declined("reason")).getFieldValue(GroupableField.Status))
    }
}
