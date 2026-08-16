@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhelenskiy.zheduler.zheduler.DueDateFilter
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import com.zhelenskiy.zheduler.zheduler.UNLIMITED
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours

/**
 * A ranked page is memoised until the data changes, which is what keeps scrolling from re-ranking
 * the whole space per page. But "due today", "this week" and "this month" are resolved against the
 * calendar day the ranking was built on, so the day has to count as a change too: an app left open
 * over midnight would otherwise go on answering with yesterday's groups.
 */
class CacheAcrossMidnightTest {

    private class MovableClock(var instant: Instant) : Clock {
        override fun now() = instant
    }

    private val zone = TimeZone.currentSystemDefault()
    private fun dayStart(year: Int, month: Int, day: Int) = LocalDate(year, month, day).atStartOfDayIn(zone)

    @Test
    fun `due-today stops being answered from yesterday's ranking`() = runTest {
        val clock = MovableClock(dayStart(2024, 3, 14) + 23.hours)
        val database = Room.inMemoryDatabaseBuilder<ZhedulerDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        val repository = RoomTaskRepository(database, clock)

        val space = repository.createSpace("Test", "T")!!
        // Due at 10:00 on the 15th: tomorrow while the clock says the 14th.
        repository.addTask(space.id, title = "due tomorrow", dueDate = dayStart(2024, 3, 15) + 10.hours)

        val dueToday = TaskFilterCriteria(dueDateFilter = DueDateFilter.Today)
        fun titles() = persistentListOf<com.zhelenskiy.zheduler.zheduler.GroupFilter>()

        assertEquals(
            emptyList(),
            repository.getTasksForGroupPage(space.id, titles(), persistentListOf(), dueToday, 0, UNLIMITED)
                .items.map { it.task.title },
            "the task is not due until tomorrow",
        )
        assertEquals(0, repository.countTasksForGroup(space.id, titles(), dueToday))

        // Midnight passes with nothing edited, so the data version has not moved.
        clock.instant = dayStart(2024, 3, 15) + 1.hours

        assertEquals(
            listOf("due tomorrow"),
            repository.getTasksForGroupPage(space.id, titles(), persistentListOf(), dueToday, 0, UNLIMITED)
                .items.map { it.task.title },
            "once the day rolls over the task is due today",
        )
        assertEquals(1, repository.countTasksForGroup(space.id, titles(), dueToday))
    }
}
