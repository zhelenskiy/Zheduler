@file:OptIn(ExperimentalTestApi::class, ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.taskedit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.SavedStateHandle
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.TaskEditContainer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Save, pressed straight after typing, through the real screen.
 *
 * The rich editor reports what it holds only once typing pauses, so the last words live in the
 * editor and nowhere else; Save reads the form there and then. The screen has to ask the editor
 * for them first — and that asking is one line in `saveChanges`, which is exactly the sort of line
 * a test of the mechanism alone would not miss if it went.
 */
class SaveKeepsTheWholeDescriptionTest {

    @Test
    fun theTailOfTheDescriptionIsSaved() = runComposeUiTest {
        val repository = InMemoryTaskRepository(Clock.System)
        val (spaceId, taskId) = runBlocking {
            val space = repository.createSpace("Test", "TEST")!!
            val task = repository.addTask(space.id, title = "A task", description = "start\n")!!
            space.id to task.id
        }

        setContent {
            MaterialTheme {
                TaskEditScreen(
                    container = TaskEditContainer(repository, spaceId, taskId, SavedStateHandle()),
                    onNavigateBack = {},
                    onAddNewTaskWithConnection = { _, _ -> },
                    onTaskClick = {},
                    themeMode = ThemeMode.Light,
                    onThemeModeChange = {},
                    useDynamicColors = false,
                    onDynamicColorsChange = {},
                    colorSettings = ColorSettings(),
                    onColorSettingsChange = {},
                )
            }
        }
        // The task is read through the container's own scope, so the form appears a moment after
        // the first frame; the editor then needs a beat to build its document.
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        // The description's first block: the fields before it belong to the plain text inputs
        // above (title, priority, estimate), and this one is the block editor's own.
        val description = onAllNodes(hasSetTextAction()).fetchSemanticsNodes().indices.last()
        onAllNodes(hasSetTextAction())[description]
            .performTextReplacement("start and the rest of the sentence")
        waitForIdle()

        // Pressed with no pause after the typing, which is when the editor is still holding it.
        onNodeWithContentDescription("Save").performClick()
        // The write goes through the container's scope, so it lands a moment after the click.
        waitUntil(timeoutMillis = 10_000) {
            runBlocking { repository.getTaskById(taskId) }?.description?.trim() != "start"
        }

        assertEquals(
            "start and the rest of the sentence",
            runBlocking { repository.getTaskById(taskId) }?.description?.trim(),
            "what was typed just before Save was not saved",
        )
    }
}
