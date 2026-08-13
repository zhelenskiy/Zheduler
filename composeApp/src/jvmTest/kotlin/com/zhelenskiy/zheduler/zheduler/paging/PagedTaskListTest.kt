package com.zhelenskiy.zheduler.zheduler.paging

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.zhelenskiy.zheduler.zheduler.GroupFilter
import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.OrderDirection
import com.zhelenskiy.zheduler.zheduler.OrderableField
import com.zhelenskiy.zheduler.zheduler.OrderingRule
import com.zhelenskiy.zheduler.zheduler.TaskFilterCriteria
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.TaskWithTotals
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The task list is fed by a [Pager]: it starts with one page and asks for the next as the user
 * scrolls. These tests cover that wiring end to end — repository, paging source, lazy list —
 * because no single layer can show it works.
 */
@OptIn(ExperimentalTestApi::class)
class PagedTaskListTest {

    private val ordering = persistentListOf(OrderingRule(OrderableField.Id, OrderDirection.Ascending))

    /** Records how far into the result set the list actually asked the repository to go. */
    private class RecordingRepository(private val delegate: TaskRepository) : TaskRepository by delegate {
        var furthestTaskLoaded = 0
            private set

        override suspend fun getTasksForGroupPage(
            spaceId: String,
            filters: PersistentList<GroupFilter>,
            orderingRules: PersistentList<OrderingRule>,
            filterCriteria: TaskFilterCriteria,
            offset: Int,
            limit: Int,
        ): Page<TaskWithTotals> {
            val page = delegate.getTasksForGroupPage(spaceId, filters, orderingRules, filterCriteria, offset, limit)
            furthestTaskLoaded = maxOf(furthestTaskLoaded, page.offset + page.items.size)
            return page
        }
    }

    /**
     * Pages load off the test's main clock, so every assertion waits for the data rather than for
     * composition alone. Scrolling retries too: a page has to arrive before the list can scroll on.
     */
    private fun ComposeUiTest.scrollToTask(title: String) {
        waitUntil(timeoutMillis = 15_000) {
            runCatching { onNodeWithTag("tasks").performScrollToNode(hasText(title)) }.isSuccess
        }
        waitForIdle()
    }

    private fun repositoryWithTasks(count: Int): Pair<RecordingRepository, String> = runBlocking {
        val repository = InMemoryTaskRepository()
        val space = repository.createSpace("Test", "TEST")!!
        repeat(count) { index -> repository.addTask(space.id, title = "Task $index") }
        RecordingRepository(repository) to space.id
    }

    @Composable
    private fun PagedTasks(repository: TaskRepository, spaceId: String, pageSize: Int) {
        val pages = remember(repository, spaceId, pageSize) {
            Pager(PagingConfig(pageSize = pageSize, initialLoadSize = pageSize, enablePlaceholders = false)) {
                repository.tasksForGroupPagingSource(spaceId, persistentListOf(), ordering, TaskFilterCriteria())
            }.flow
        }
        val tasks = pages.collectAsLazyPagingItems()
        LazyColumn(modifier = Modifier.fillMaxSize().testTag("tasks")) {
            items(count = tasks.itemCount, key = tasks.itemKey { it.task.id }) { index ->
                // Rows are given a card-like height so only a handful fit the test viewport:
                // otherwise the list never scrolls and never asks for a second page.
                Text(tasks[index]?.task?.title.orEmpty(), modifier = Modifier.height(200.dp))
            }
        }
    }

    @Test
    fun firstPageIsShownWithoutLoadingTheRest() = runComposeUiTest {
        val (repository, spaceId) = repositoryWithTasks(count = 40)

        setContent {
            MaterialTheme { PagedTasks(repository, spaceId, pageSize = 5) }
        }
        waitUntilAtLeastOneExists(hasText("Task 0"), timeoutMillis = 15_000)

        // Tasks far down the list are neither rendered nor even fetched.
        onNodeWithText("Task 20", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(
            repository.furthestTaskLoaded in 1..<40,
            "expected a bounded first load, got ${repository.furthestTaskLoaded} of 40 tasks",
        )
    }

    @Test
    fun scrollingLoadsFurtherPages() = runComposeUiTest {
        val (repository, spaceId) = repositoryWithTasks(count = 40)

        setContent {
            MaterialTheme { PagedTasks(repository, spaceId, pageSize = 5) }
        }
        waitUntilAtLeastOneExists(hasText("Task 0"), timeoutMillis = 15_000)
        val loadedBeforeScroll = repository.furthestTaskLoaded

        scrollToTask("Task 20")

        onNodeWithText("Task 20", useUnmergedTree = true).assertExists()
        assertTrue(
            repository.furthestTaskLoaded > loadedBeforeScroll,
            "scrolling should have pulled further pages",
        )
    }

    @Test
    fun everyTaskIsReachableByScrolling() = runComposeUiTest {
        val (repository, spaceId) = repositoryWithTasks(count = 25)

        setContent {
            MaterialTheme { PagedTasks(repository, spaceId, pageSize = 4) }
        }
        waitUntilAtLeastOneExists(hasText("Task 0"), timeoutMillis = 15_000)

        scrollToTask("Task 24")

        onNodeWithText("Task 24", useUnmergedTree = true).assertExists()
    }
}
