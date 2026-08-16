@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TimeSource

class InMemoryTotalsDiamondGraphTest : TotalsDiamondGraphTest(), InMemoryRepositoryTest
class DatabaseTotalsDiamondGraphTest : TotalsDiamondGraphTest(), DatabaseRepositoryTest

/**
 * Totals are aggregated over everything that depends on a task, and a dependency graph is a graph:
 * two tasks can feed into the same third one. A chain of diamonds has two routes per level, so a
 * walk that re-explores shared subtrees doubles in cost per level while the graph grows by three.
 */
abstract class TotalsDiamondGraphTest : AbstractRepositoryTest {

    private val epoch = Instant.fromEpochMilliseconds(1_700_000_000_000)

    /**
     * `hub(0) -> {left, right} -> hub(1) -> ... -> hub(levels)`: 2^levels routes over
     * `3 * levels + 1` tasks. The deepest hub holds the earliest due date and highest priority, so
     * both totals have to travel the whole chain.
     */
    private suspend fun buildDiamondChain(repo: TaskRepository, spaceId: String, levels: Int): String {
        fun dependsOn(targetId: String) = persistentSetOf(TaskConnection(targetId, ConnectionType.IsDependencyOf))

        val last = repo.addTask(
            spaceId,
            title = "hub-$levels",
            dueDate = epoch,
            priority = Priority(100),
            customId = "TEST-hub$levels",
        )!!

        var next = last.id
        for (level in levels - 1 downTo 0) {
            val left = repo.addTask(
                spaceId, title = "left-$level", customId = "TEST-l$level", connections = dependsOn(next),
            )!!
            val right = repo.addTask(
                spaceId, title = "right-$level", customId = "TEST-r$level", connections = dependsOn(next),
            )!!
            val hub = repo.addTask(
                spaceId,
                title = "hub-$level",
                customId = "TEST-hub$level",
                connections = persistentSetOf(
                    TaskConnection(left.id, ConnectionType.IsDependencyOf),
                    TaskConnection(right.id, ConnectionType.IsDependencyOf),
                ),
                dueDate = epoch + (level + 1).toLong().let { kotlin.time.Duration.parse("${it}d") },
                priority = Priority(1),
            )!!
            next = hub.id
        }
        return next
    }

    @Test
    fun `totals over a chain of diamonds stay linear`() = runTest {
        val (repo, spaceId) = createRepositoryWithSpace()
        // 73 tasks, but 2^24 routes through them.
        val levels = 24
        val rootId = buildDiamondChain(repo, spaceId, levels)

        val started = TimeSource.Monotonic.markNow()
        val root = repo.getTasksByIdWithTotals(rootId)!!
        val elapsed = started.elapsedNow()

        // The deepest hub's values have to reach the root through the whole chain.
        assertEquals(epoch, root.totalDueDate, "earliest due date among everything depending on the root")
        assertEquals(Priority(100), root.totalPriority, "highest priority among everything depending on the root")

        // Visiting each task once is microseconds' work; walking the routes is millions of times more.
        assertTrue(elapsed.inWholeSeconds < 5, "totals took $elapsed for $levels levels")
    }
}
