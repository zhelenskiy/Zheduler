package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Containers subscribe to the repository, which outlives every screen. Without both closing on
 * demand and Compose closing them, each visit to a screen leaves a live subscriber behind.
 */
class ContainerLifecycleTest {

    @Test
    fun `closing a container stops it observing the repository`() = runBlocking {
        val repository = InMemoryTaskRepository()
        val space = repository.createSpace("Test", "TEST")!!
        // Unconfined so the collector resumes inline on each emission: no waiting to go flaky.
        val container = TaskListContainer(repository, space.id, Dispatchers.Unconfined)

        repository.addTask(space.id, title = "first")
        val seenWhileOpen = container.dataVersion.value
        assertTrue(seenWhileOpen > 0, "the container should see repository changes while it is open")

        container.close()
        repository.addTask(space.id, title = "second")

        assertEquals(
            seenWhileOpen,
            container.dataVersion.value,
            "a closed container should no longer react to repository changes",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `rememberContainer closes the container when the composable leaves`() = runComposeUiTest {
        var closes = 0
        var shown by mutableStateOf(true)

        setContent {
            if (shown) {
                rememberContainer(Unit) { AutoCloseable { closes++ } }
            }
        }

        assertEquals(0, closes, "the container is still on screen")

        shown = false
        waitForIdle()

        assertEquals(1, closes, "leaving the composition should close the container")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `rememberContainer closes the previous container when its key changes`() = runComposeUiTest {
        val closed = mutableListOf<String>()
        var spaceId by mutableStateOf("space-a")

        setContent {
            val current = spaceId
            rememberContainer(current) { AutoCloseable { closed += current } }
        }

        assertEquals(emptyList(), closed)

        spaceId = "space-b"
        waitForIdle()

        assertEquals(listOf("space-a"), closed, "switching space should close the container for the old one")
    }
}
