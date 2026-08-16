@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.ExperimentalTime

/**
 * Tests that compare the behavior of InMemory and SQL-based implementations
 * for grouped task queries (getTaskGroups and getTasksForGroup).
 *
 * These tests ensure that both implementations produce identical results
 * for various complex grouping scenarios.
 */
class GroupedTaskQueriesComparisonTest {

    /**
     * Test context that holds both repository implementations and their space IDs.
     */
    private class TestContext(
        val inMemoryRepo: TaskRepository,
        val inMemorySpaceId: String,
        val dbRepo: TaskRepository,
        val dbSpaceId: String
    ) {
        /** Execute task setup on both repositories */
        suspend fun setupTasks(setup: suspend TaskRepository.(spaceId: String) -> Unit) {
            inMemoryRepo.setup(inMemorySpaceId)
            dbRepo.setup(dbSpaceId)
        }

        /** Create a view mode (uses inMemorySpaceId but that's just for the data class) */
        fun viewMode(
            groupingLevels: PersistentList<GroupingLevel>,
            defaultOrderingRules: PersistentList<OrderingRule> = persistentListOf()
        ) = ViewMode(
            id = "test",
            name = "Test",
            spaceId = inMemorySpaceId,
            groupingLevels = groupingLevels,
            defaultOrderingRules = defaultOrderingRules
        )

        /** Compare getTaskGroups results between both implementations */
        suspend fun compareGroups(
            viewMode: ViewMode,
            levelIndex: Int = 0,
            parentFilters: PersistentList<GroupFilter> = persistentListOf(),
            filterCriteria: TaskFilterCriteria = TaskFilterCriteria()
        ) {
            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, viewMode, levelIndex, parentFilters, filterCriteria)
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, viewMode, levelIndex, parentFilters, filterCriteria)

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")

            inMemoryGroups.sortedBy { it.label }.zip(dbGroups.sortedBy { it.label }).forEach { (inMemory, db) ->
                assertEquals(inMemory.label, db.label, "Group labels should match")
                assertEquals(inMemory.taskCount, db.taskCount, "Task counts should match for '${inMemory.label}'")
                assertEquals(inMemory.isUncategorized, db.isUncategorized, "Uncategorized flag should match for '${inMemory.label}'")
            }
        }

        /** Compare getTasksForGroup results between both implementations */
        suspend fun compareTasks(
            filters: PersistentList<GroupFilter>,
            orderingRules: PersistentList<OrderingRule> = persistentListOf(),
            filterCriteria: TaskFilterCriteria = TaskFilterCriteria()
        ): Pair<List<TaskWithTotals>, List<TaskWithTotals>> {
            val inMemoryTasks = inMemoryRepo.getTasksForGroup(inMemorySpaceId, filters, orderingRules, filterCriteria)
            val dbTasks = dbRepo.getTasksForGroup(dbSpaceId, filters, orderingRules, filterCriteria)

            assertEquals(inMemoryTasks.size, dbTasks.size, "Task count should match")
            if (orderingRules.isEmpty()) {
                // Nothing was asked for, so nothing is promised about the order.
                assertEquals(
                    inMemoryTasks.map { it.task.title }.toSet(),
                    dbTasks.map { it.task.title }.toSet(),
                    "Task titles should match"
                )
            } else {
                // Ordering is half of what these queries do, and comparing sets never saw it.
                assertEquals(
                    inMemoryTasks.map { it.task.title },
                    dbTasks.map { it.task.title },
                    "Task titles should match, in order"
                )
            }

            return inMemoryTasks to dbTasks
        }

        /**
         * Compare paged reads between both implementations: same windows, same totals, and the
         * same result as reading the group in one go.
         */
        suspend fun comparePagedTasks(
            filters: PersistentList<GroupFilter>,
            orderingRules: PersistentList<OrderingRule> = persistentListOf(),
            filterCriteria: TaskFilterCriteria = TaskFilterCriteria(),
            pageSize: Int = 2,
        ) {
            suspend fun TaskRepository.readPages(spaceId: String): List<String> {
                val ids = mutableListOf<String>()
                var offset = 0
                while (true) {
                    val page = getTasksForGroupPage(spaceId, filters, orderingRules, filterCriteria, offset, pageSize)
                    ids += page.items.map { it.task.id }
                    if (!page.hasMore || page.items.isEmpty()) break
                    offset += page.items.size
                }
                return ids
            }

            val inMemoryPaged = inMemoryRepo.readPages(inMemorySpaceId)
            val dbPaged = dbRepo.readPages(dbSpaceId)
            if (orderingRules.isEmpty()) {
                assertEquals(inMemoryPaged.toSet(), dbPaged.toSet(), "Paged tasks should match")
            } else {
                assertEquals(inMemoryPaged, dbPaged, "Paged tasks should match, in order")
            }

            val inMemoryWhole = inMemoryRepo.getTasksForGroup(inMemorySpaceId, filters, orderingRules, filterCriteria)
            val dbWhole = dbRepo.getTasksForGroup(dbSpaceId, filters, orderingRules, filterCriteria)
            assertEquals(inMemoryWhole.map { it.task.id }, inMemoryPaged, "In-memory pages should match the whole list")
            assertEquals(dbWhole.map { it.task.id }, dbPaged, "Database pages should match the whole list")

            assertEquals(
                inMemoryRepo.countTasksForGroup(inMemorySpaceId, filters, filterCriteria),
                dbRepo.countTasksForGroup(dbSpaceId, filters, filterCriteria),
                "Group counts should match"
            )
        }
    }

    private suspend fun withTestContext(
        clock: Clock = Clock.System,
        block: suspend TestContext.() -> Unit
    ) {
        val inMemoryRepo = InMemoryTaskRepository(clock)
        val inMemorySpace = inMemoryRepo.createSpace("Test", "TEST")!!
        val dbRepo = createDatabaseRepository(clock)
        val dbSpace = dbRepo.createSpace("Test", "TEST")!!

        TestContext(inMemoryRepo, inMemorySpace.id, dbRepo, dbSpace.id).block()
    }

    // Legacy helper functions for tests not yet migrated to withTestContext
    private suspend fun createInMemoryRepo(clock: Clock = Clock.System): Pair<TaskRepository, String> {
        val repo = InMemoryTaskRepository(clock)
        val space = repo.createSpace("Test", "TEST")!!
        return repo to space.id
    }

    private suspend fun createDatabaseRepo(clock: Clock = Clock.System): Pair<TaskRepository, String> {
        val repo = createDatabaseRepository(clock)
        val space = repo.createSpace("Test", "TEST")!!
        return repo to space.id
    }

    @AfterTest
    fun cleanup() {
        cleanupDatabaseTest()
    }

    // ==================== Basic Group Filter Tests ====================

    @Test
    fun `compare status grouping results`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Open task 1", status = TaskStatus.Open)
                addTask(spaceId, title = "Open task 2", status = TaskStatus.Open)
                addTask(spaceId, title = "In Progress task", status = TaskStatus.InProgress)
                addTask(spaceId, title = "Done task", status = TaskStatus.Done)
                addTask(spaceId, title = "Blocked task", status = TaskStatus.Blocked(persistentSetOf()))
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open")),
                        GroupDefinition("In Progress", persistentSetOf("InProgress")),
                        GroupDefinition("Done", persistentSetOf("Done"))
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    @Test
    fun `compare priority range grouping results`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "High priority", priority = Priority(90))
                addTask(spaceId, title = "Medium priority", priority = Priority(50))
                addTask(spaceId, title = "Low priority", priority = Priority(20))
                addTask(spaceId, title = "No priority", priority = null)
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition("High", persistentSetOf(), priorityMin = 75, priorityMax = 100),
                        GroupDefinition("Medium", persistentSetOf(), priorityMin = 40, priorityMax = 74),
                        GroupDefinition("Low", persistentSetOf(), priorityMin = 1, priorityMax = 39),
                        GroupDefinition("No Priority", persistentSetOf(), includeNoPriority = true)
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    @Test
    fun `compare due date range grouping results`() = runTest {
        val now = Clock.System.now()
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Overdue", dueDate = now - 2.days)
                addTask(spaceId, title = "Today", dueDate = now)
                addTask(spaceId, title = "This week", dueDate = now + 3.days)
                addTask(spaceId, title = "Next week", dueDate = now + 10.days)
                addTask(spaceId, title = "No due date", dueDate = null)
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.DueDate,
                    groups = persistentListOf(
                        GroupDefinition("Overdue", persistentSetOf(), dueDateMaxDays = -1),
                        GroupDefinition("This Week", persistentSetOf(), dueDateMinDays = 0, dueDateMaxDays = 7),
                        GroupDefinition("Later", persistentSetOf(), dueDateMinDays = 8),
                        GroupDefinition("No Due Date", persistentSetOf(), includeNoDueDate = true)
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    // ==================== Complex Multi-Level Grouping Tests ====================

    @Test
    fun `compare nested grouping with status and priority`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Open High", status = TaskStatus.Open, priority = Priority(90))
                addTask(spaceId, title = "Open Low", status = TaskStatus.Open, priority = Priority(20))
                addTask(spaceId, title = "Done High", status = TaskStatus.Done, priority = Priority(85))
                addTask(spaceId, title = "Done Low", status = TaskStatus.Done, priority = Priority(15))
                addTask(spaceId, title = "InProgress Medium", status = TaskStatus.InProgress, priority = Priority(50))
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open")),
                        GroupDefinition("Done", persistentSetOf("Done"))
                    )
                ),
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition("High", persistentSetOf(), priorityMin = 75, priorityMax = 100),
                        GroupDefinition("Low", persistentSetOf(), priorityMin = 1, priorityMax = 40)
                    )
                )
            ))

            // Test first level
            compareGroups(viewMode, levelIndex = 0)

            // Test second level for "Open" status
            val openFilter = GroupFilter.Values(GroupableField.Status, persistentSetOf("Open"))
            compareGroups(viewMode, levelIndex = 1, parentFilters = persistentListOf(openFilter))
        }
    }

    @Test
    fun `compare getTasksForGroup with complex filters`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Task 1", status = TaskStatus.Open, priority = Priority(90), tags = persistentSetOf("urgent"))
                addTask(spaceId, title = "Task 2", status = TaskStatus.Open, priority = Priority(20), tags = persistentSetOf("bug"))
                addTask(spaceId, title = "Task 3", status = TaskStatus.Done, priority = Priority(85), tags = persistentSetOf("feature"))
                addTask(spaceId, title = "Task 4", status = TaskStatus.InProgress, priority = Priority(50), tags = persistentSetOf("urgent", "bug"))
            }

            val statusFilter = GroupFilter.Values(GroupableField.Status, persistentSetOf("Open"))
            val orderingRules = persistentListOf(OrderingRule(OrderableField.Priority, OrderDirection.Descending, NullPosition.Last))

            compareTasks(persistentListOf(statusFilter), orderingRules)
        }
    }

    @Test
    fun `compare getTasksForGroup with priority range filter`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "High 1", priority = Priority(90))
                addTask(spaceId, title = "High 2", priority = Priority(80))
                addTask(spaceId, title = "Medium", priority = Priority(50))
                addTask(spaceId, title = "Low", priority = Priority(20))
                addTask(spaceId, title = "No Priority", priority = null)
            }

            val priorityFilter = GroupFilter.PriorityRange(min = 75, max = 100, includeNull = false)
            val orderingRules = persistentListOf(OrderingRule(OrderableField.Priority, OrderDirection.Descending, NullPosition.Last))

            val (_, dbTasks) = compareTasks(persistentListOf(priorityFilter), orderingRules)
            assertEquals(2, dbTasks.size, "Should have 2 high priority tasks")
        }
    }

    @Test
    fun `compare ordering where ties and nulls decide the order`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "same priority a", priority = Priority(50))
                addTask(spaceId, title = "same priority b", priority = Priority(50))
                addTask(spaceId, title = "same priority c", priority = Priority(50))
                addTask(spaceId, title = "higher", priority = Priority(90))
                addTask(spaceId, title = "no priority 1", priority = null)
                addTask(spaceId, title = "no priority 2", priority = null)
            }

            // Where the rule cannot decide, the id does — so the order is total and both
            // implementations have to produce it. Null placement is the half most likely to
            // differ: SQL sorts nulls by its own rule unless told otherwise.
            for (nulls in NullPosition.entries) {
                for (direction in OrderDirection.entries) {
                    compareTasks(
                        filters = persistentListOf(),
                        orderingRules = persistentListOf(
                            OrderingRule(OrderableField.Priority, direction, nulls),
                            OrderingRule(OrderableField.Id, OrderDirection.Ascending),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `compare paged reads against an order the pages have to preserve`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                repeat(7) { index ->
                    addTask(spaceId, title = "task $index", priority = Priority(10 + index * 10))
                }
                addTask(spaceId, title = "unprioritised", priority = null)
            }

            comparePagedTasks(
                filters = persistentListOf(),
                orderingRules = persistentListOf(
                    OrderingRule(OrderableField.Priority, OrderDirection.Descending, NullPosition.Last),
                    OrderingRule(OrderableField.Id, OrderDirection.Ascending),
                ),
                pageSize = 3,
            )
        }
    }

    // ==================== Filter Criteria Tests ====================

    @Test
    fun `compare grouping with status filter criteria`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Open 1", status = TaskStatus.Open, priority = Priority(90))
                addTask(spaceId, title = "Open 2", status = TaskStatus.Open, priority = Priority(20))
                addTask(spaceId, title = "Done 1", status = TaskStatus.Done, priority = Priority(85))
                addTask(spaceId, title = "InProgress 1", status = TaskStatus.InProgress, priority = Priority(50))
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition("High", persistentSetOf(), priorityMin = 75, priorityMax = 100),
                        GroupDefinition("Low", persistentSetOf(), priorityMin = 1, priorityMax = 40)
                    )
                )
            ))

            // Filter to only Open tasks
            val filterCriteria = TaskFilterCriteria(statusFilters = persistentSetOf(TaskStatus.Open))
            compareGroups(viewMode, filterCriteria = filterCriteria)
        }
    }

    @Test
    fun `compare grouping with tag filter criteria`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Bug 1", status = TaskStatus.Open, tags = persistentSetOf("bug"))
                addTask(spaceId, title = "Bug 2", status = TaskStatus.Done, tags = persistentSetOf("bug", "urgent"))
                addTask(spaceId, title = "Feature 1", status = TaskStatus.Open, tags = persistentSetOf("feature"))
                addTask(spaceId, title = "No tags", status = TaskStatus.Open, tags = persistentSetOf())
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open")),
                        GroupDefinition("Done", persistentSetOf("Done"))
                    )
                )
            ))

            val filterCriteria = TaskFilterCriteria(selectedTags = persistentSetOf("bug"), tagMatchMode = TagMatchMode.Any)
            compareGroups(viewMode, filterCriteria = filterCriteria)
        }
    }

    @Test
    fun `compare grouping with connection type filter criteria`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                val task1 = addTask(spaceId, title = "Parent task", status = TaskStatus.Open)!!
                val task2 = addTask(spaceId, title = "Child task", status = TaskStatus.Open)!!
                addTask(spaceId, title = "Standalone task", status = TaskStatus.Open)
                val task4 = addTask(spaceId, title = "Related task", status = TaskStatus.Done)!!

                updateTask(task2.copy(connections = persistentSetOf(TaskConnection(task1.id, ConnectionType.SubtaskOf))))
                updateTask(task4.copy(connections = persistentSetOf(TaskConnection(task1.id, ConnectionType.RelatesTo))))
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open")),
                        GroupDefinition("Done", persistentSetOf("Done"))
                    )
                )
            ))

            val filterCriteria = TaskFilterCriteria(connectionTypeFilters = persistentSetOf(ConnectionTypeOption.SubtaskOf))
            compareGroups(viewMode, filterCriteria = filterCriteria)
        }
    }

    // ==================== Boolean Field Grouping Tests ====================

    @Test
    fun `compare isRecurring grouping results`() = runTest {
        val now = Clock.System.now()
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Recurring task", recurrenceRules = persistentListOf(
                    RecurrenceRule(
                        timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                            period = RecurrencePeriod.ofDays(1),
                            firstOccurrence = now
                        ),
                        statusChangeTrigger = null,
                        resetToStatus = TaskStatus.Open
                    ).to(RecurrenceState())
                ))
                addTask(spaceId, title = "Non-recurring task 1")
                addTask(spaceId, title = "Non-recurring task 2")
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.IsRecurring,
                    groups = persistentListOf(
                        GroupDefinition("Recurring", persistentSetOf("true")),
                        GroupDefinition("One-time", persistentSetOf("false"))
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    @Test
    fun `compare hasNotifications grouping results`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "With notifications", notifications = persistentListOf(
                    TaskNotification(timeBeforeDeadline = RecurrencePeriod.ofHours(1))
                ))
                addTask(spaceId, title = "Without notifications 1")
                addTask(spaceId, title = "Without notifications 2")
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.HasNotifications,
                    groups = persistentListOf(
                        GroupDefinition("Has Notifications", persistentSetOf("true")),
                        GroupDefinition("No Notifications", persistentSetOf("false"))
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    @Test
    fun `compare hasConnections grouping results`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                val task1 = addTask(spaceId, title = "Connected task 1")!!
                val task2 = addTask(spaceId, title = "Connected task 2")!!
                addTask(spaceId, title = "Standalone task")

                updateTask(task1.copy(connections = persistentSetOf(TaskConnection(task2.id, ConnectionType.RelatesTo))))
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.HasConnections,
                    groups = persistentListOf(
                        GroupDefinition("Connected", persistentSetOf("true")),
                        GroupDefinition("Standalone", persistentSetOf("false"))
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    @Test
    fun `compare autoUpdateStatus grouping results`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Auto update", autoUpdateStatusFromSubtasks = true)
                addTask(spaceId, title = "Manual 1", autoUpdateStatusFromSubtasks = false)
                addTask(spaceId, title = "Manual 2", autoUpdateStatusFromSubtasks = false)
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.AutoUpdateStatus,
                    groups = persistentListOf(
                        GroupDefinition("Auto", persistentSetOf("true")),
                        GroupDefinition("Manual", persistentSetOf("false"))
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    // ==================== Complex Combined Tests ====================

    @Test
    fun `compare three-level nested grouping`() = runTest {
        val now = Clock.System.now()
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Open High Today", status = TaskStatus.Open, priority = Priority(90), dueDate = now)
                addTask(spaceId, title = "Open High Later", status = TaskStatus.Open, priority = Priority(85), dueDate = now + 10.days)
                addTask(spaceId, title = "Open Low Today", status = TaskStatus.Open, priority = Priority(20), dueDate = now + 1.days)
                addTask(spaceId, title = "Done High Today", status = TaskStatus.Done, priority = Priority(90), dueDate = now)
                addTask(spaceId, title = "InProgress Medium", status = TaskStatus.InProgress, priority = Priority(50), dueDate = now + 5.days)
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open")),
                        GroupDefinition("Done", persistentSetOf("Done"))
                    )
                ),
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition("High", persistentSetOf(), priorityMin = 75, priorityMax = 100),
                        GroupDefinition("Low", persistentSetOf(), priorityMin = 1, priorityMax = 40)
                    )
                ),
                GroupingLevel(
                    field = GroupableField.DueDate,
                    groups = persistentListOf(
                        GroupDefinition("This Week", persistentSetOf(), dueDateMinDays = 0, dueDateMaxDays = 7),
                        GroupDefinition("Later", persistentSetOf(), dueDateMinDays = 8)
                    )
                )
            ))

            // Test all three levels
            compareGroups(viewMode, levelIndex = 0)

            val openFilter = GroupFilter.Values(GroupableField.Status, persistentSetOf("Open"))
            compareGroups(viewMode, levelIndex = 1, parentFilters = persistentListOf(openFilter))

            val highFilter = GroupFilter.PriorityRange(min = 75, max = 100, includeNull = false)
            compareGroups(viewMode, levelIndex = 2, parentFilters = persistentListOf(openFilter, highFilter))
        }
    }

    @Test
    fun `compare getTasksForGroup with multiple filters and criteria`() = runTest {
        val now = Clock.System.now()
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Match all", status = TaskStatus.Open, priority = Priority(90), tags = persistentSetOf("urgent"), dueDate = now)
                addTask(spaceId, title = "Open high no tag", status = TaskStatus.Open, priority = Priority(85), dueDate = now + 1.days)
                addTask(spaceId, title = "Open low urgent", status = TaskStatus.Open, priority = Priority(20), tags = persistentSetOf("urgent"), dueDate = now)
                addTask(spaceId, title = "Done high urgent", status = TaskStatus.Done, priority = Priority(90), tags = persistentSetOf("urgent"), dueDate = now)
            }

            val filters = persistentListOf(
                GroupFilter.Values(GroupableField.Status, persistentSetOf("Open")),
                GroupFilter.PriorityRange(min = 75, max = 100, includeNull = false)
            )
            val filterCriteria = TaskFilterCriteria(selectedTags = persistentSetOf("urgent"), tagMatchMode = TagMatchMode.Any)
            val orderingRules = persistentListOf(OrderingRule(OrderableField.Priority, OrderDirection.Descending, NullPosition.Last))

            val (_, dbTasks) = compareTasks(filters, orderingRules, filterCriteria)
            assertEquals(1, dbTasks.size, "Should have 1 matching task")
        }
    }

    // ==================== EstimatedTime Grouping Tests ====================

    @Test
    fun `compare estimatedTime grouping results`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Quick task", estimatedTime = RecurrencePeriod(minutes = 15))
                addTask(spaceId, title = "Short task", estimatedTime = RecurrencePeriod(minutes = 45))
                addTask(spaceId, title = "Medium task", estimatedTime = RecurrencePeriod.ofHours(2))
                addTask(spaceId, title = "Long task", estimatedTime = RecurrencePeriod.ofHours(5))
                addTask(spaceId, title = "No estimate", estimatedTime = null)
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.EstimatedTime,
                    groups = persistentListOf(
                        GroupDefinition("Quick", persistentSetOf(), estimatedTimeMin = null, estimatedTimeMax = RecurrencePeriod(minutes = 30)),
                        GroupDefinition("Short", persistentSetOf(), estimatedTimeMin = RecurrencePeriod(minutes = 30), estimatedTimeMax = RecurrencePeriod.ofHours(1)),
                        GroupDefinition("Medium", persistentSetOf(), estimatedTimeMin = RecurrencePeriod.ofHours(1), estimatedTimeMax = RecurrencePeriod.ofHours(4)),
                        GroupDefinition("Long", persistentSetOf(), estimatedTimeMin = RecurrencePeriod.ofHours(4), estimatedTimeMax = null),
                        GroupDefinition("No Estimate", persistentSetOf(), includeNoEstimatedTime = true)
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    @Test
    fun `compare estimatedTime getTasksForGroup`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Task 1", estimatedTime = RecurrencePeriod(minutes = 30))
                addTask(spaceId, title = "Task 2", estimatedTime = RecurrencePeriod.ofHours(1))
                addTask(spaceId, title = "Task 3", estimatedTime = RecurrencePeriod.ofHours(3))
                addTask(spaceId, title = "Task 4", estimatedTime = null)
            }

            val estimatedTimeFilter = GroupFilter.EstimatedTimeRange(
                minSeconds = RecurrencePeriod(minutes = 30).toApproximateSeconds(),
                maxSeconds = RecurrencePeriod.ofHours(2).toApproximateSeconds(),
                includeNull = false
            )

            compareTasks(persistentListOf(estimatedTimeFilter))
        }
    }

    @Test
    fun `compare estimatedTime null only filter`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Has estimate", estimatedTime = RecurrencePeriod.ofHours(1))
                addTask(spaceId, title = "No estimate 1", estimatedTime = null)
                addTask(spaceId, title = "No estimate 2", estimatedTime = null)
            }

            val estimatedTimeFilter = GroupFilter.EstimatedTimeRange(
                minSeconds = null,
                maxSeconds = null,
                includeNull = true
            )

            val (_, dbTasks) = compareTasks(persistentListOf(estimatedTimeFilter))
            assertEquals(2, dbTasks.size, "Should have 2 tasks without estimated time")
        }
    }

    // ==================== Tags Grouping Tests ====================

    @Test
    fun `compare tags grouping results`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Bug task", tags = persistentSetOf("bug"))
                addTask(spaceId, title = "Feature task", tags = persistentSetOf("feature"))
                addTask(spaceId, title = "Bug and urgent", tags = persistentSetOf("bug", "urgent"))
                addTask(spaceId, title = "No tags", tags = persistentSetOf())
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Tags,
                    groups = persistentListOf(
                        GroupDefinition("Bugs", persistentSetOf("bug")),
                        GroupDefinition("Features", persistentSetOf("feature")),
                        GroupDefinition("Urgent", persistentSetOf("urgent"))
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    @Test
    fun `compare tags getTasksForGroup with HasTags filter`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Task with bug tag", tags = persistentSetOf("bug"))
                addTask(spaceId, title = "Task with both tags", tags = persistentSetOf("bug", "urgent"))
                addTask(spaceId, title = "Task with urgent only", tags = persistentSetOf("urgent"))
                addTask(spaceId, title = "Task without relevant tags", tags = persistentSetOf("feature"))
            }

            val hasBugTag = GroupFilter.HasTags(tags = persistentSetOf("bug"))
            val (_, dbTasks) = compareTasks(persistentListOf(hasBugTag))
            assertEquals(2, dbTasks.size, "Should have 2 tasks with bug tag")
        }
    }

    // ==================== Status Grouping Edge Cases ====================

    @Test
    fun `compare status grouping with all status types`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Open", status = TaskStatus.Open)
                addTask(spaceId, title = "InProgress", status = TaskStatus.InProgress)
                addTask(spaceId, title = "Done", status = TaskStatus.Done)
                addTask(spaceId, title = "Blocked", status = TaskStatus.Blocked(persistentSetOf(), "comment"))
                addTask(spaceId, title = "Declined", status = TaskStatus.Declined("not needed"))
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Open", persistentSetOf("Open")),
                        GroupDefinition("In Progress", persistentSetOf("InProgress")),
                        GroupDefinition("Done", persistentSetOf("Done")),
                        GroupDefinition("Blocked", persistentSetOf("Blocked")),
                        GroupDefinition("Declined", persistentSetOf("Declined"))
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    @Test
    fun `compare status grouping with combined groups`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Open 1", status = TaskStatus.Open)
                addTask(spaceId, title = "Open 2", status = TaskStatus.Open)
                addTask(spaceId, title = "InProgress", status = TaskStatus.InProgress)
                addTask(spaceId, title = "Done", status = TaskStatus.Done)
                addTask(spaceId, title = "Blocked", status = TaskStatus.Blocked(persistentSetOf()))
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(
                        GroupDefinition("Active", persistentSetOf("Open", "InProgress")),
                        GroupDefinition("Completed", persistentSetOf("Done")),
                        GroupDefinition("Blocked", persistentSetOf("Blocked"))
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    // ==================== Priority Grouping Edge Cases ====================

    @Test
    fun `compare priority grouping with overlapping ranges`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Priority 100", priority = Priority(100))
                addTask(spaceId, title = "Priority 75", priority = Priority(75))
                addTask(spaceId, title = "Priority 50", priority = Priority(50))
                addTask(spaceId, title = "Priority 25", priority = Priority(25))
                addTask(spaceId, title = "Priority 1", priority = Priority(1))
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition("Critical", persistentSetOf(), priorityMin = 90, priorityMax = 100),
                        GroupDefinition("High", persistentSetOf(), priorityMin = 70, priorityMax = 89),
                        GroupDefinition("Medium", persistentSetOf(), priorityMin = 40, priorityMax = 69),
                        GroupDefinition("Low", persistentSetOf(), priorityMin = 1, priorityMax = 39)
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    @Test
    fun `compare priority grouping with null and range combined`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "High", priority = Priority(90))
                addTask(spaceId, title = "Low", priority = Priority(20))
                addTask(spaceId, title = "No Priority 1", priority = null)
                addTask(spaceId, title = "No Priority 2", priority = null)
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Priority,
                    groups = persistentListOf(
                        GroupDefinition("High or Unset", persistentSetOf(), priorityMin = 75, priorityMax = 100, includeNoPriority = true),
                        GroupDefinition("Low", persistentSetOf(), priorityMin = 1, priorityMax = 40)
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    // ==================== DueDate Grouping Edge Cases ====================

    @Test
    fun `compare due date grouping with null and range combined`() = runTest {
        val now = Clock.System.now()
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Today", dueDate = now)
                addTask(spaceId, title = "Tomorrow", dueDate = now + 1.days)
                addTask(spaceId, title = "No Due Date 1", dueDate = null)
                addTask(spaceId, title = "No Due Date 2", dueDate = null)
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.DueDate,
                    groups = persistentListOf(
                        GroupDefinition("Soon or Unset", persistentSetOf(), dueDateMinDays = 0, dueDateMaxDays = 3, includeNoDueDate = true),
                        GroupDefinition("Later", persistentSetOf(), dueDateMinDays = 4)
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    @Test
    fun `compare due date grouping with overdue tasks`() = runTest {
        val now = Clock.System.now()
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Very overdue", dueDate = now - 10.days)
                addTask(spaceId, title = "Slightly overdue", dueDate = now - 1.days)
                addTask(spaceId, title = "Today", dueDate = now)
                addTask(spaceId, title = "Future", dueDate = now + 5.days)
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.DueDate,
                    groups = persistentListOf(
                        GroupDefinition("Overdue", persistentSetOf(), dueDateMaxDays = -1),
                        GroupDefinition("Today", persistentSetOf(), dueDateMinDays = 0, dueDateMaxDays = 0),
                        GroupDefinition("Future", persistentSetOf(), dueDateMinDays = 1)
                    )
                )
            ))

            compareGroups(viewMode)
        }
    }

    // ==================== Not Filter Tests ====================

    @Test
    fun `compare Not filter for uncategorized tasks`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Open", status = TaskStatus.Open)
                addTask(spaceId, title = "Done", status = TaskStatus.Done)
                addTask(spaceId, title = "Blocked", status = TaskStatus.Blocked(persistentSetOf()))
                addTask(spaceId, title = "Declined", status = TaskStatus.Declined("reason"))
            }

            val notFilter = GroupFilter.Not(
                field = GroupableField.Status,
                filters = persistentListOf(
                    GroupFilter.Values(GroupableField.Status, persistentSetOf("Open")),
                    GroupFilter.Values(GroupableField.Status, persistentSetOf("Done"))
                )
            )

            val (_, dbTasks) = compareTasks(persistentListOf(notFilter))
            assertEquals(2, dbTasks.size, "Should have 2 tasks (Blocked and Declined)")
        }
    }

    @Test
    fun `compare nested Not filter`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "High Open", status = TaskStatus.Open, priority = Priority(90))
                addTask(spaceId, title = "Low Open", status = TaskStatus.Open, priority = Priority(20))
                addTask(spaceId, title = "High Done", status = TaskStatus.Done, priority = Priority(85))
                addTask(spaceId, title = "Low Done", status = TaskStatus.Done, priority = Priority(15))
            }

            val openFilter = GroupFilter.Values(GroupableField.Status, persistentSetOf("Open"))
            val notHighPriority = GroupFilter.Not(
                field = GroupableField.Priority,
                filters = persistentListOf(GroupFilter.PriorityRange(min = 75, max = 100, includeNull = false))
            )

            val (_, dbTasks) = compareTasks(persistentListOf(openFilter, notHighPriority))
            assertEquals(1, dbTasks.size, "Should have 1 task (Low Open)")
            assertEquals("Low Open", dbTasks.first().task.title)
        }
    }

    // ==================== Combined Filter Tests ====================

    @Test
    fun `compare multiple boolean field grouping combined`() = runTest {
        val now = Clock.System.now()
        withTestContext {
            setupTasks { spaceId ->
                val task1 = addTask(spaceId, title = "All true",
                    autoUpdateStatusFromSubtasks = true,
                    notifications = persistentListOf(TaskNotification(timeBeforeDeadline = RecurrencePeriod.ofHours(1))),
                    recurrenceRules = persistentListOf(
                        RecurrenceRule(
                            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(RecurrencePeriod.ofDays(1), now),
                            statusChangeTrigger = null,
                            resetToStatus = TaskStatus.Open
                        ).to(RecurrenceState())
                    )
                )!!
                val task2 = addTask(spaceId, title = "Connected task")!!
                updateTask(task1.copy(connections = persistentSetOf(TaskConnection(task2.id, ConnectionType.RelatesTo))))

                addTask(spaceId, title = "All false", autoUpdateStatusFromSubtasks = false)
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.IsRecurring,
                    groups = persistentListOf(
                        GroupDefinition("Recurring", persistentSetOf("true")),
                        GroupDefinition("One-time", persistentSetOf("false"))
                    )
                ),
                GroupingLevel(
                    field = GroupableField.HasNotifications,
                    groups = persistentListOf(
                        GroupDefinition("With Notifications", persistentSetOf("true")),
                        GroupDefinition("Without Notifications", persistentSetOf("false"))
                    )
                )
            ))

            compareGroups(viewMode, levelIndex = 0)

            val recurringFilter = GroupFilter.Values(GroupableField.IsRecurring, persistentSetOf("true"))
            compareGroups(viewMode, levelIndex = 1, parentFilters = persistentListOf(recurringFilter))
        }
    }

    // ==================== Edge Cases ====================

    @Test
    fun `compare empty results`() = runTest {
        withTestContext {
            // Add no tasks
            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(GroupDefinition("Open", persistentSetOf("Open")))
                )
            ))

            compareGroups(viewMode)
        }
    }

    @Test
    fun `compare uncategorized tasks handling`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Open task", status = TaskStatus.Open)
                addTask(spaceId, title = "Blocked task", status = TaskStatus.Blocked(persistentSetOf()))
                addTask(spaceId, title = "Declined task", status = TaskStatus.Declined("reason"))
            }

            val viewMode = viewMode(persistentListOf(
                GroupingLevel(
                    field = GroupableField.Status,
                    groups = persistentListOf(GroupDefinition("Open", persistentSetOf("Open")))
                )
            ))

            compareGroups(viewMode)
        }
    }

    // ==================== Uncategorized Tasks Tests ====================

    @Test
    fun `compare uncategorized tasks with priority ranges`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "High priority", priority = Priority(90))
                addTask(spaceId, title = "Medium priority", priority = Priority(50))
                addTask(spaceId, title = "Low priority", priority = Priority(20))
                addTask(spaceId, title = "No priority", priority = null) // Not covered by groups
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.Priority,
                        groups = persistentListOf(
                            GroupDefinition("High", persistentSetOf(), priorityMin = 75, priorityMax = 100),
                            GroupDefinition("Low", persistentSetOf(), priorityMin = 1, priorityMax = 30)
                            // Medium (40-74) and null not covered - should be uncategorized
                        )
                    )
                )
            )

            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")

            val inMemoryUncategorized = inMemoryGroups.find { it.isUncategorized }
            val dbUncategorized = dbGroups.find { it.isUncategorized }

            assertNotNull(inMemoryUncategorized, "InMemory should have uncategorized group")
            assertNotNull(dbUncategorized, "DB should have uncategorized group")
            assertEquals(2, inMemoryUncategorized?.taskCount, "Uncategorized should have 2 tasks (medium + null)")
            assertEquals(inMemoryUncategorized?.taskCount, dbUncategorized?.taskCount, "Uncategorized task count should match")
        }
    }

    @Test
    fun `compare uncategorized tasks with due date ranges`() = runTest {
        val now = Clock.System.now()
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Today", dueDate = now)
                addTask(spaceId, title = "Next month", dueDate = now + 35.days) // Gap in ranges
                addTask(spaceId, title = "No due date", dueDate = null)
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.DueDate,
                        groups = persistentListOf(
                            GroupDefinition("This Week", persistentSetOf(), dueDateMinDays = 0, dueDateMaxDays = 7),
                            GroupDefinition("Next Week", persistentSetOf(), dueDateMinDays = 8, dueDateMaxDays = 14)
                            // Days 15-34 and null not covered
                        )
                    )
                )
            )

            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")

            val inMemoryUncategorized = inMemoryGroups.find { it.isUncategorized }
            val dbUncategorized = dbGroups.find { it.isUncategorized }

            assertNotNull(inMemoryUncategorized, "Should have uncategorized group")
            assertNotNull(dbUncategorized, "Should have uncategorized group")
            assertEquals(2, dbUncategorized?.taskCount, "Uncategorized should have 2 tasks (next month + null)")
            assertEquals(inMemoryUncategorized?.taskCount, dbUncategorized?.taskCount, "Uncategorized count should match")
        }
    }

    @Test
    fun `compare uncategorized tasks with estimated time`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Short task", estimatedTime = RecurrencePeriod(minutes = 30))
                addTask(spaceId, title = "Very long task", estimatedTime = RecurrencePeriod.ofDays(2)) // Not covered
                addTask(spaceId, title = "No estimate", estimatedTime = null)
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.EstimatedTime,
                        groups = persistentListOf(
                            GroupDefinition("Short", persistentSetOf(), estimatedTimeMin = null, estimatedTimeMax = RecurrencePeriod.ofHours(1)),
                            GroupDefinition("Medium", persistentSetOf(), estimatedTimeMin = RecurrencePeriod.ofHours(1), estimatedTimeMax = RecurrencePeriod.ofHours(4))
                            // Very long (>4h) and null not covered
                        )
                    )
                )
            )

            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")

            val inMemoryUncategorized = inMemoryGroups.find { it.isUncategorized }
            val dbUncategorized = dbGroups.find { it.isUncategorized }

            assertNotNull(dbUncategorized, "Should have uncategorized group")
            assertEquals(2, dbUncategorized?.taskCount, "Uncategorized should have 2 tasks (very long + null)")
            assertEquals(inMemoryUncategorized?.taskCount, dbUncategorized?.taskCount, "Uncategorized count should match")
        }
    }

    @Test
    fun `compare uncategorized tasks with tags grouping`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Bug task", tags = persistentSetOf("bug"))
                addTask(spaceId, title = "Unknown tag", tags = persistentSetOf("unknown"))
                addTask(spaceId, title = "No tags", tags = persistentSetOf())
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.Tags,
                        groups = persistentListOf(
                            GroupDefinition("Bugs", persistentSetOf("bug")),
                            GroupDefinition("Features", persistentSetOf("feature"))
                            // "unknown" tag and no tags not covered
                        )
                    )
                )
            )

            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")

            val inMemoryUncategorized = inMemoryGroups.find { it.isUncategorized }
            val dbUncategorized = dbGroups.find { it.isUncategorized }

            assertNotNull(dbUncategorized, "Should have uncategorized group")
            assertEquals(2, dbUncategorized?.taskCount, "Uncategorized should have 2 tasks (unknown + no tags)")
            assertEquals(inMemoryUncategorized?.taskCount, dbUncategorized?.taskCount, "Uncategorized count should match")
        }
    }

    @Test
    fun `compare all tasks uncategorized when no groups defined`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Task 1", status = TaskStatus.Open)
                addTask(spaceId, title = "Task 2", status = TaskStatus.Done)
                addTask(spaceId, title = "Task 3", status = TaskStatus.InProgress)
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.Status,
                        groups = persistentListOf() // No groups defined - all should be uncategorized
                    )
                )
            )

            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")
            assertEquals(1, dbGroups.size, "Should only have uncategorized group")

            val dbUncategorized = dbGroups.first()
            assertTrue(dbUncategorized.isUncategorized, "Only group should be uncategorized")
            assertEquals(3, dbUncategorized.taskCount, "All 3 tasks should be uncategorized")
        }
    }

    // ==================== Duplicate Tasks Tests (tasks matching multiple groups) ====================

    @Test
    fun `compare duplicate tasks with overlapping status groups`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Open task", status = TaskStatus.Open)
                addTask(spaceId, title = "Done task", status = TaskStatus.Done)
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.Status,
                        groups = persistentListOf(
                            // Both groups include "Open" - task should appear in both
                            GroupDefinition("Active", persistentSetOf("Open", "InProgress")),
                            GroupDefinition("Not Done", persistentSetOf("Open", "Blocked", "InProgress"))
                        )
                    )
                )
            )

            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")

            // Both "Active" and "Not Done" should contain the Open task
            val inMemoryActive = inMemoryGroups.find { it.label == "Active" }
            val dbActive = dbGroups.find { it.label == "Active" }
            val inMemoryNotDone = inMemoryGroups.find { it.label == "Not Done" }
            val dbNotDone = dbGroups.find { it.label == "Not Done" }

            assertEquals(1, inMemoryActive?.taskCount, "Active should have 1 task")
            assertEquals(1, dbActive?.taskCount, "Active should have 1 task")
            assertEquals(1, inMemoryNotDone?.taskCount, "Not Done should have 1 task")
            assertEquals(1, dbNotDone?.taskCount, "Not Done should have 1 task")

            // Total tasks counted across groups can exceed actual task count
            val totalInGroups = dbGroups.sumOf { it.taskCount }
            assertTrue(totalInGroups >= 2, "Total tasks in groups should be at least 2 (1 in each overlapping group + 1 in uncategorized)")
        }
    }

    @Test
    fun `compare duplicate tasks with overlapping priority ranges`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Priority 80", priority = Priority(80)) // Falls in both ranges
                addTask(spaceId, title = "Priority 50", priority = Priority(50)) // Only in Medium
                addTask(spaceId, title = "Priority 90", priority = Priority(90)) // Only in High
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.Priority,
                        groups = persistentListOf(
                            // Overlapping ranges: 75-100 and 70-85
                            GroupDefinition("High", persistentSetOf(), priorityMin = 75, priorityMax = 100),
                            GroupDefinition("Medium-High", persistentSetOf(), priorityMin = 70, priorityMax = 85)
                        )
                    )
                )
            )

            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")

            val inMemoryHigh = inMemoryGroups.find { it.label == "High" }
            val dbHigh = dbGroups.find { it.label == "High" }
            val inMemoryMediumHigh = inMemoryGroups.find { it.label == "Medium-High" }
            val dbMediumHigh = dbGroups.find { it.label == "Medium-High" }

            // Priority 80 should be in both groups, Priority 90 only in High
            assertEquals(2, inMemoryHigh?.taskCount, "High should have 2 tasks (80 and 90)")
            assertEquals(2, dbHigh?.taskCount, "High should have 2 tasks")
            assertEquals(1, inMemoryMediumHigh?.taskCount, "Medium-High should have 1 task (80)")
            assertEquals(1, dbMediumHigh?.taskCount, "Medium-High should have 1 task")

            assertEquals(inMemoryHigh?.taskCount, dbHigh?.taskCount)
            assertEquals(inMemoryMediumHigh?.taskCount, dbMediumHigh?.taskCount)
        }
    }

    @Test
    fun `compare duplicate tasks with overlapping tags`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Bug and urgent", tags = persistentSetOf("bug", "urgent")) // Matches both groups
                addTask(spaceId, title = "Only bug", tags = persistentSetOf("bug"))
                addTask(spaceId, title = "Only urgent", tags = persistentSetOf("urgent"))
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.Tags,
                        groups = persistentListOf(
                            GroupDefinition("Bugs", persistentSetOf("bug")),
                            GroupDefinition("Urgent", persistentSetOf("urgent"))
                        )
                    )
                )
            )

            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")

            val inMemoryBugs = inMemoryGroups.find { it.label == "Bugs" }
            val dbBugs = dbGroups.find { it.label == "Bugs" }
            val inMemoryUrgent = inMemoryGroups.find { it.label == "Urgent" }
            val dbUrgent = dbGroups.find { it.label == "Urgent" }

            // "Bug and urgent" appears in both groups
            assertEquals(2, inMemoryBugs?.taskCount, "Bugs should have 2 tasks")
            assertEquals(2, dbBugs?.taskCount, "Bugs should have 2 tasks")
            assertEquals(2, inMemoryUrgent?.taskCount, "Urgent should have 2 tasks")
            assertEquals(2, dbUrgent?.taskCount, "Urgent should have 2 tasks")
        }
    }

    @Test
    fun `compare duplicate tasks with overlapping due date ranges`() = runTest {
        val now = Clock.System.now()
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Day 5", dueDate = now + 5.days) // Falls in both ranges
                addTask(spaceId, title = "Day 2", dueDate = now + 2.days) // Only in This Week
                addTask(spaceId, title = "Day 10", dueDate = now + 10.days) // Only in Extended
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.DueDate,
                        groups = persistentListOf(
                            // Overlapping ranges: 0-7 and 3-10
                            GroupDefinition("This Week", persistentSetOf(), dueDateMinDays = 0, dueDateMaxDays = 7),
                            GroupDefinition("Extended", persistentSetOf(), dueDateMinDays = 3, dueDateMaxDays = 10)
                        )
                    )
                )
            )

            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")

            val inMemoryThisWeek = inMemoryGroups.find { it.label == "This Week" }
            val dbThisWeek = dbGroups.find { it.label == "This Week" }
            val inMemoryExtended = inMemoryGroups.find { it.label == "Extended" }
            val dbExtended = dbGroups.find { it.label == "Extended" }

            // Day 5 should be in both groups
            assertEquals(2, inMemoryThisWeek?.taskCount, "This Week should have 2 tasks (Day 2 and Day 5)")
            assertEquals(2, dbThisWeek?.taskCount, "This Week should have 2 tasks")
            assertEquals(2, inMemoryExtended?.taskCount, "Extended should have 2 tasks (Day 5 and Day 10)")
            assertEquals(2, dbExtended?.taskCount, "Extended should have 2 tasks")
        }
    }

    @Test
    fun `compare duplicate tasks with range including null`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "High priority", priority = Priority(90))
                addTask(spaceId, title = "No priority", priority = null)
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.Priority,
                        groups = persistentListOf(
                            // High priority range + null (will include null priority task)
                            GroupDefinition("High or Unset", persistentSetOf(), priorityMin = 75, priorityMax = 100, includeNoPriority = true),
                            // Only null (will also include null priority task)
                            GroupDefinition("Unset Only", persistentSetOf(), includeNoPriority = true)
                        )
                    )
                )
            )

            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")

            val inMemoryHighOrUnset = inMemoryGroups.find { it.label == "High or Unset" }
            val dbHighOrUnset = dbGroups.find { it.label == "High or Unset" }
            val inMemoryUnsetOnly = inMemoryGroups.find { it.label == "Unset Only" }
            val dbUnsetOnly = dbGroups.find { it.label == "Unset Only" }

            // "No priority" should appear in both groups
            assertEquals(2, inMemoryHighOrUnset?.taskCount, "High or Unset should have 2 tasks")
            assertEquals(2, dbHighOrUnset?.taskCount, "High or Unset should have 2 tasks")
            assertEquals(1, inMemoryUnsetOnly?.taskCount, "Unset Only should have 1 task")
            assertEquals(1, dbUnsetOnly?.taskCount, "Unset Only should have 1 task")
        }
    }

    @Test
    fun `compare getTasksForGroup returns correct tasks for overlapping groups`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Priority 80", priority = Priority(80))
                addTask(spaceId, title = "Priority 90", priority = Priority(90))
                addTask(spaceId, title = "Priority 50", priority = Priority(50))
            }

            // Get tasks in overlapping range 75-85 (should include Priority 80)
            val filter = GroupFilter.PriorityRange(min = 75, max = 85, includeNull = false)
            val (inMemoryTasks, dbTasks) = compareTasks(persistentListOf(filter))

            assertEquals(1, dbTasks.size, "Should have 1 task (Priority 80)")
            assertEquals("Priority 80", dbTasks.first().task.title)
        }
    }

    @Test
    fun `compare nested grouping with uncategorized at each level`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Open High", status = TaskStatus.Open, priority = Priority(90))
                addTask(spaceId, title = "Open Medium", status = TaskStatus.Open, priority = Priority(50)) // Uncategorized at level 1
                addTask(spaceId, title = "Blocked High", status = TaskStatus.Blocked(persistentSetOf()), priority = Priority(85)) // Uncategorized at level 0
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.Status,
                        groups = persistentListOf(
                            GroupDefinition("Open", persistentSetOf("Open"))
                            // Blocked not covered
                        )
                    ),
                    GroupingLevel(
                        field = GroupableField.Priority,
                        groups = persistentListOf(
                            GroupDefinition("High", persistentSetOf(), priorityMin = 75, priorityMax = 100)
                            // Medium (40-74) not covered
                        )
                    )
                )
            )

            // Test first level
            val inMemoryLevel0 = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbLevel0 = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryLevel0.size, dbLevel0.size, "Level 0 group count should match")

            val level0Uncategorized = dbLevel0.find { it.isUncategorized }
            assertNotNull(level0Uncategorized, "Level 0 should have uncategorized group")
            assertEquals(1, level0Uncategorized?.taskCount, "Level 0 uncategorized should have 1 task (Blocked)")

            // Test second level for Open status
            val openFilter = GroupFilter.Values(GroupableField.Status, persistentSetOf("Open"))
            val inMemoryLevel1 = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 1, persistentListOf(openFilter))
            val dbLevel1 = dbRepo.getTaskGroups(dbSpaceId, vm, 1, persistentListOf(openFilter))

            assertEquals(inMemoryLevel1.size, dbLevel1.size, "Level 1 group count should match")

            val level1Uncategorized = dbLevel1.find { it.isUncategorized }
            assertNotNull(level1Uncategorized, "Level 1 should have uncategorized group")
            assertEquals(1, level1Uncategorized?.taskCount, "Level 1 uncategorized should have 1 task (Open Medium)")
        }
    }

    @Test
    fun `compare grouping by tags with more than 5 tags`() = runTest {
        withTestContext {
            // Create tasks with various tag combinations
            setupTasks { spaceId ->
                // Task with all 7 tags
                addTask(spaceId, title = "All tags", tags = persistentSetOf("tag1", "tag2", "tag3", "tag4", "tag5", "tag6", "tag7"))
                // Task with only first 5 tags
                addTask(spaceId, title = "First 5 tags", tags = persistentSetOf("tag1", "tag2", "tag3", "tag4", "tag5"))
                // Task with only last 2 tags (tag6, tag7)
                addTask(spaceId, title = "Last 2 tags", tags = persistentSetOf("tag6", "tag7"))
                // Task with tag6 only
                addTask(spaceId, title = "Tag6 only", tags = persistentSetOf("tag6"))
                // Task with tag7 only
                addTask(spaceId, title = "Tag7 only", tags = persistentSetOf("tag7"))
                // Task with no matching tags
                addTask(spaceId, title = "Other tags", tags = persistentSetOf("other", "unrelated"))
            }

            val vm = viewMode(
                persistentListOf(
                    GroupingLevel(
                        field = GroupableField.Tags,
                        groups = persistentListOf(
                            // Group with 7 tags - more than the SQL query's 5 tag limit
                            GroupDefinition("All Seven Tags", persistentSetOf("tag1", "tag2", "tag3", "tag4", "tag5", "tag6", "tag7"))
                        )
                    )
                )
            )

            val inMemoryGroups = inMemoryRepo.getTaskGroups(inMemorySpaceId, vm, 0, persistentListOf())
            val dbGroups = dbRepo.getTaskGroups(dbSpaceId, vm, 0, persistentListOf())

            assertEquals(inMemoryGroups.size, dbGroups.size, "Number of groups should match")

            val inMemoryAllTags = inMemoryGroups.find { it.label == "All Seven Tags" }
            val dbAllTags = dbGroups.find { it.label == "All Seven Tags" }

            assertNotNull(inMemoryAllTags, "InMemory should have All Seven Tags group")
            assertNotNull(dbAllTags, "DB should have All Seven Tags group")

            // Tasks matching any of the 7 tags:
            // - "All tags" (has all 7)
            // - "First 5 tags" (has tag1-5)
            // - "Last 2 tags" (has tag6, tag7)
            // - "Tag6 only" (has tag6)
            // - "Tag7 only" (has tag7)
            // Total: 5 tasks
            assertEquals(5, inMemoryAllTags?.taskCount, "InMemory All Seven Tags should have 5 tasks")
            assertEquals(5, dbAllTags?.taskCount, "DB All Seven Tags should have 5 tasks")

            // Also verify getTasksForGroup works correctly
            val filter = GroupFilter.HasTags(tags = persistentSetOf("tag1", "tag2", "tag3", "tag4", "tag5", "tag6", "tag7"))
            val (inMemoryTasks, dbTasks) = compareTasks(persistentListOf(filter))

            assertEquals(5, dbTasks.size, "Should have 5 tasks matching any of the 7 tags")

            val dbTitles = dbTasks.map { it.task.title }.toSet()
            assertTrue("All tags" in dbTitles)
            assertTrue("First 5 tags" in dbTitles)
            assertTrue("Last 2 tags" in dbTitles)
            assertTrue("Tag6 only" in dbTitles)
            assertTrue("Tag7 only" in dbTitles)
            assertFalse("Other tags" in dbTitles)
        }
    }

    // ==================== Paged reads ====================

    @Test
    fun `compare paged tasks with ordering`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                val now = Clock.System.now()
                repeat(9) { index ->
                    addTask(
                        spaceId,
                        title = "Task $index",
                        status = if (index % 3 == 0) TaskStatus.Done else TaskStatus.Open,
                        dueDate = if (index % 4 == 0) null else now + index.days,
                        priority = if (index % 5 == 0) null else Priority(index * 10),
                    )
                }
            }

            val ordering = persistentListOf(
                OrderingRule(OrderableField.TotalDueDate, OrderDirection.Ascending, NullPosition.Last),
                OrderingRule(OrderableField.TotalPriority, OrderDirection.Descending, NullPosition.Last),
                OrderingRule(OrderableField.Id, OrderDirection.Ascending),
            )

            comparePagedTasks(persistentListOf(), ordering, pageSize = 2)
            comparePagedTasks(persistentListOf(), ordering, pageSize = 4)
            comparePagedTasks(
                filters = persistentListOf(GroupFilter.Values(GroupableField.Status, persistentSetOf("Open"))),
                orderingRules = ordering,
                pageSize = 3,
            )
        }
    }

    @Test
    fun `compare paged tasks with filter criteria`() = runTest {
        withTestContext {
            setupTasks { spaceId ->
                addTask(spaceId, title = "Alpha one", priority = Priority(80))
                addTask(spaceId, title = "Alpha two", priority = Priority(60))
                addTask(spaceId, title = "Alpha three")
                addTask(spaceId, title = "Beta one", priority = Priority(90))
            }

            comparePagedTasks(
                filters = persistentListOf(),
                orderingRules = persistentListOf(OrderingRule(OrderableField.Id, OrderDirection.Ascending)),
                filterCriteria = TaskFilterCriteria(
                    searchQuery = "alpha",
                    textSearchFields = persistentSetOf(TaskTextSearchField.Title),
                ),
                pageSize = 1,
            )
        }
    }

    @Test
    fun `compare dueDate grouping against an injected clock`() = runTest {
        // Deliberately not "now": grouping by due date is relative to today, and both repositories
        // have to read today from the clock they were given rather than from the wall clock.
        val today = LocalDate(2024, 3, 14)
        val clock = object : Clock {
            override fun now() = today.atStartOfDayIn(TimeZone.currentSystemDefault()) + 9.hours
        }

        withTestContext(clock) {
            setupTasks { spaceId ->
                addTask(spaceId, title = "yesterday", dueDate = clock.now() - 1.days)
                addTask(spaceId, title = "today", dueDate = clock.now() + 1.hours)
                addTask(spaceId, title = "tomorrow", dueDate = clock.now() + 1.days)
                addTask(spaceId, title = "no due date", dueDate = null)
            }

            val (_, todayOnly) = compareTasks(
                persistentListOf(GroupFilter.DueDateRange(minDays = 0, maxDays = 0))
            )
            assertEquals(setOf("today"), todayOnly.map { it.task.title }.toSet())

            val (_, overdue) = compareTasks(
                persistentListOf(GroupFilter.DueDateRange(maxDays = -1))
            )
            assertEquals(setOf("yesterday"), overdue.map { it.task.title }.toSet())
        }
    }

    // ==================== TaskFilterCriteria parity tests ====================
    //
    // The filter panel's criteria are matched in Kotlin by the in-memory repository and in SQL by
    // the database one. These pin the two together: a criterion the SQL cannot express has to fail
    // here rather than silently return a different set to the task list.

    /**
     * Tasks around every boundary the relative due-date filters have, against a fixed clock.
     *
     * Mid-month on purpose: a rolling month from the 14th and the calendar month of March pick out
     * visibly different sets, which is what let the two implementations disagree unnoticed.
     */
    private suspend fun TestContext.setupDueDateSpread(clock: Clock) = setupTasks { spaceId ->
        val now = clock.now()
        addTask(spaceId, title = "last week", dueDate = now - 7.days)
        addTask(spaceId, title = "earlier this month", dueDate = now - 3.days)
        addTask(spaceId, title = "yesterday", dueDate = now - 1.days)
        addTask(spaceId, title = "today", dueDate = now + 1.hours)
        addTask(spaceId, title = "tomorrow", dueDate = now + 1.days)
        addTask(spaceId, title = "in six days", dueDate = now + 6.days)
        addTask(spaceId, title = "in eight days", dueDate = now + 8.days)
        addTask(spaceId, title = "next month", dueDate = now + 40.days)
        addTask(spaceId, title = "no due date", dueDate = null)
    }

    /** 14 March 2024, 09:00 local — far enough into the month for either reading to differ. */
    private val midMonthClock = object : Clock {
        override fun now(): Instant =
            LocalDate(2024, 3, 14).atStartOfDayIn(TimeZone.currentSystemDefault()) + 9.hours
    }

    @Test
    fun `compare every relative dueDate criterion`() = runTest {
        withTestContext(midMonthClock) {
            setupDueDateSpread(midMonthClock)

            for (filter in DueDateFilter.entries) {
                if (filter == DueDateFilter.Custom) continue
                compareTasks(
                    filters = persistentListOf(),
                    filterCriteria = TaskFilterCriteria(dueDateFilter = filter),
                )
            }
        }
    }

    @Test
    fun `relative dueDate criteria select the expected tasks`() = runTest {
        withTestContext(midMonthClock) {
            setupDueDateSpread(midMonthClock)

            suspend fun titlesFor(filter: DueDateFilter): Set<String> =
                compareTasks(persistentListOf(), filterCriteria = TaskFilterCriteria(dueDateFilter = filter))
                    .second.map { it.task.title }.toSet()

            assertEquals(
                setOf("last week", "earlier this month", "yesterday"),
                titlesFor(DueDateFilter.Overdue),
            )
            assertEquals(setOf("today"), titlesFor(DueDateFilter.Today))
            assertEquals(setOf("today", "tomorrow", "in six days"), titlesFor(DueDateFilter.ThisWeek))
            // The month ahead, not the calendar month: nothing already past, and nothing 40 days out.
            assertEquals(
                setOf("today", "tomorrow", "in six days", "in eight days"),
                titlesFor(DueDateFilter.ThisMonth),
            )
            assertEquals(setOf("no due date"), titlesFor(DueDateFilter.NoDueDate))
        }
    }

    /** Tasks spanning every [EstimatedTimeFilter] bucket, including each boundary value. */
    private suspend fun TestContext.setupEstimatedTimeSpread() = setupTasks { spaceId ->
        addTask(spaceId, title = "10m", estimatedTime = RecurrencePeriod(minutes = 10))
        addTask(spaceId, title = "15m", estimatedTime = RecurrencePeriod(minutes = 15))
        addTask(spaceId, title = "29m", estimatedTime = RecurrencePeriod(minutes = 29))
        addTask(spaceId, title = "30m", estimatedTime = RecurrencePeriod(minutes = 30))
        addTask(spaceId, title = "59m", estimatedTime = RecurrencePeriod(minutes = 59))
        addTask(spaceId, title = "1h", estimatedTime = RecurrencePeriod(hours = 1))
        addTask(spaceId, title = "3h59m", estimatedTime = RecurrencePeriod(hours = 3, minutes = 59))
        addTask(spaceId, title = "4h", estimatedTime = RecurrencePeriod(hours = 4))
        addTask(spaceId, title = "5h", estimatedTime = RecurrencePeriod(hours = 5))
        addTask(spaceId, title = "none", estimatedTime = null)
    }

    @Test
    fun `compare estimatedTime criteria buckets`() = runTest {
        withTestContext {
            setupEstimatedTimeSpread()

            for (filter in EstimatedTimeFilter.entries) {
                if (filter == EstimatedTimeFilter.Custom) continue
                compareTasks(
                    filters = persistentListOf(),
                    filterCriteria = TaskFilterCriteria(estimatedTimeFilter = filter),
                )
            }
        }
    }

    @Test
    fun `compare estimatedTime criteria buckets select the expected tasks`() = runTest {
        withTestContext {
            setupEstimatedTimeSpread()

            suspend fun titlesFor(filter: EstimatedTimeFilter): Set<String> =
                compareTasks(persistentListOf(), filterCriteria = TaskFilterCriteria(estimatedTimeFilter = filter))
                    .second.map { it.task.title }.toSet()

            assertEquals(setOf("10m"), titlesFor(EstimatedTimeFilter.Quick))
            assertEquals(setOf("15m", "29m"), titlesFor(EstimatedTimeFilter.Short))
            assertEquals(setOf("30m", "59m"), titlesFor(EstimatedTimeFilter.Medium))
            assertEquals(setOf("1h", "3h59m"), titlesFor(EstimatedTimeFilter.Long))
            assertEquals(setOf("4h", "5h"), titlesFor(EstimatedTimeFilter.VeryLong))
            assertEquals(setOf("none"), titlesFor(EstimatedTimeFilter.NoEstimate))
        }
    }

    @Test
    fun `compare custom estimatedTime criteria`() = runTest {
        withTestContext {
            setupEstimatedTimeSpread()

            // The filter panel stores these as compact time strings, not numbers.
            val bounded = TaskFilterCriteria(
                estimatedTimeFilter = EstimatedTimeFilter.Custom,
                customEstimatedTimeMin = "30m",
                customEstimatedTimeMax = "1h",
            )
            val (_, boundedDb) = compareTasks(persistentListOf(), filterCriteria = bounded)
            // Custom bounds are inclusive on both ends, unlike the fixed buckets.
            assertEquals(setOf("30m", "59m", "1h"), boundedDb.map { it.task.title }.toSet())

            compareTasks(
                persistentListOf(),
                filterCriteria = TaskFilterCriteria(
                    estimatedTimeFilter = EstimatedTimeFilter.Custom,
                    customEstimatedTimeMin = "4h",
                ),
            )
            compareTasks(
                persistentListOf(),
                filterCriteria = TaskFilterCriteria(
                    estimatedTimeFilter = EstimatedTimeFilter.Custom,
                    customEstimatedTimeMax = "15m",
                ),
            )
            // Unparseable bounds degrade to "has an estimate", as the in-memory path does.
            compareTasks(
                persistentListOf(),
                filterCriteria = TaskFilterCriteria(
                    estimatedTimeFilter = EstimatedTimeFilter.Custom,
                    customEstimatedTimeMin = "not a period",
                ),
            )
        }
    }

    @Test
    fun `compare custom dueDate criteria`() = runTest {
        val clock = object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(1_700_000_000_000)
        }
        withTestContext(clock) {
            setupTasks { spaceId ->
                addTask(spaceId, title = "before", dueDate = clock.now() - 2.days)
                addTask(spaceId, title = "after", dueDate = clock.now() + 2.days)
                addTask(spaceId, title = "no due date", dueDate = null)
            }

            // A custom range with an end, with a start, and with neither.
            compareTasks(
                persistentListOf(),
                filterCriteria = TaskFilterCriteria(
                    dueDateFilter = DueDateFilter.Custom,
                    customDueDateBefore = clock.now(),
                ),
            )
            compareTasks(
                persistentListOf(),
                filterCriteria = TaskFilterCriteria(
                    dueDateFilter = DueDateFilter.Custom,
                    customDueDateAfter = clock.now(),
                ),
            )
            compareTasks(
                persistentListOf(),
                filterCriteria = TaskFilterCriteria(dueDateFilter = DueDateFilter.Custom),
            )
        }
    }

    @Test
    fun `compare recurrence criteria kinds`() = runTest {
        withTestContext {
            val start = Clock.System.now()
            setupTasks { spaceId ->
                addTask(
                    spaceId, title = "timeout",
                    recurrenceRules = persistentListOf(
                        RecurrenceRule(
                            timeRecurrenceTrigger = RecurrenceTrigger.AfterTimeout(
                                period = RecurrencePeriod.ofWeeks(1),
                                firstOccurrence = start,
                            ),
                            statusChangeTrigger = null,
                            resetToStatus = TaskStatus.Open,
                        ) to RecurrenceState()
                    ),
                )
                addTask(
                    spaceId, title = "weekly",
                    recurrenceRules = persistentListOf(
                        RecurrenceRule(
                            timeRecurrenceTrigger = RecurrenceTrigger.AtFixedPoints(
                                pattern = FixedPointPattern.DaysOfWeek(persistentSetOf(RecurrenceDayOfWeek.MONDAY)),
                                startFrom = start,
                            ),
                            statusChangeTrigger = null,
                            resetToStatus = TaskStatus.Open,
                        ) to RecurrenceState()
                    ),
                )
                addTask(
                    spaceId, title = "monthly",
                    recurrenceRules = persistentListOf(
                        RecurrenceRule(
                            timeRecurrenceTrigger = RecurrenceTrigger.AtFixedPoints(
                                pattern = FixedPointPattern.DayOfMonth(15),
                                startFrom = start,
                            ),
                            statusChangeTrigger = null,
                            resetToStatus = TaskStatus.Open,
                        ) to RecurrenceState()
                    ),
                )
                addTask(spaceId, title = "plain")
            }

            for (filter in RecurrenceFilter.entries) {
                compareTasks(
                    filters = persistentListOf(),
                    filterCriteria = TaskFilterCriteria(recurrenceFilter = filter),
                )
            }

            val (_, timeoutOnly) = compareTasks(
                persistentListOf(),
                filterCriteria = TaskFilterCriteria(recurrenceFilter = RecurrenceFilter.AfterTimeout),
            )
            assertEquals(setOf("timeout"), timeoutOnly.map { it.task.title }.toSet())

            val (_, weeklyOnly) = compareTasks(
                persistentListOf(),
                filterCriteria = TaskFilterCriteria(recurrenceFilter = RecurrenceFilter.FixedDaysOfWeek),
            )
            assertEquals(setOf("weekly"), weeklyOnly.map { it.task.title }.toSet())

            val (_, monthlyOnly) = compareTasks(
                persistentListOf(),
                filterCriteria = TaskFilterCriteria(recurrenceFilter = RecurrenceFilter.FixedDayOfMonth),
            )
            assertEquals(setOf("monthly"), monthlyOnly.map { it.task.title }.toSet())
        }
    }
}
