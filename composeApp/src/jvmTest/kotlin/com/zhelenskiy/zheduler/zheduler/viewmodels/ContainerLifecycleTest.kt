package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Containers subscribe to the repository, which outlives every screen. Without both closing on
 * demand and Compose closing them, each visit to a screen leaves a live subscriber behind.
 */
class ContainerLifecycleTest {

    /** Waits for [condition], returning whether it came true rather than hanging the suite. */
    private suspend fun eventually(condition: () -> Boolean): Boolean =
        withTimeoutOrNull(5_000) {
            while (!condition()) delay(10)
            true
        } == true

    @Test
    fun `closing a container stops it observing the repository`() = runBlocking {
        val repository = InMemoryTaskRepository()
        val space = repository.createSpace("Test", "TEST")!!
        val container = TaskListContainer(repository, space.id)

        repository.addTask(space.id, title = "first")
        assertTrue(
            eventually { container.dataVersion.value > 0 },
            "the container should see repository changes while it is open",
        )

        val seenWhileOpen = container.dataVersion.value
        container.close()

        repository.addTask(space.id, title = "second")
        // Give the collector the same chance to run it had above; it must not take it.
        assertTrue(
            withTimeoutOrNull(500) {
                while (container.dataVersion.value == seenWhileOpen) delay(10)
                true
            } != true,
            "a closed container should no longer react to repository changes",
        )
        assertEquals(seenWhileOpen, container.dataVersion.value)
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
