@file:OptIn(ExperimentalTestApi::class, ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.ViewMode
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.ViewModeContainer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The editor screen itself, across an activity recreation.
 *
 * Two halves have to hold together: the editor state is kept, and the read that fills it does not
 * run a second time. Either one alone leaves the original bug — the reloaded view mode written
 * straight over everything the user had arranged, without so much as a prompt.
 */
class ViewModeEditorScreenRestoreTest {

    /** Counts the reads the screen makes, so "only once" can be asserted rather than assumed. */
    private class CountingRepository(
        private val delegate: TaskRepository,
    ) : TaskRepository by delegate {
        var viewModeReads = 0
            private set

        override suspend fun getViewModeById(spaceId: String, viewModeId: String): ViewMode? {
            viewModeReads++
            return delegate.getViewModeById(spaceId, viewModeId)
        }
    }

    private class Fixture {
        val delegate = InMemoryTaskRepository(Clock.System)
        val repository = CountingRepository(delegate)
        val spaceId: String = runBlocking { delegate.createSpace("Test", "TEST")!!.id }
        val stored: ViewMode = runBlocking {
            delegate.saveViewMode(ViewMode(id = "vm-1", name = "Stored", spaceId = spaceId))
        }
    }

    @Test
    fun theEditorAndItsUnsavedNameSurviveRecreation() = runComposeUiTest {
        val fixture = Fixture()

        // What an activity recreation does, minus the serialization step: the subtree is disposed
        // and built again, and everything held in `rememberSaveable` comes back through a registry
        // seeded with what the old one saved. (StateRestorationTester itself is not implemented on
        // this platform — its encode/decode step is a TODO in Compose Multiplatform 1.11.)
        var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
        // Composed at the same position both times: `rememberSaveable` keys itself on where it
        // sits in the composition, so wrapping this in a changing `key` would look up nothing.
        var onScreen by mutableStateOf(true)

        setContent {
            if (onScreen) {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    ViewModeEditorScreen(
                        container = ViewModeContainer(fixture.repository, fixture.spaceId),
                        viewModeId = fixture.stored.id,
                        copyFromViewModeId = null,
                        spaceId = fixture.spaceId,
                        onSave = {},
                        onCancel = {},
                        themeMode = ThemeMode.Light,
                        onThemeModeChange = {},
                        useDynamicColors = false,
                        onDynamicColorsChange = {},
                        colorSettings = ColorSettings(),
                        onColorSettingsChange = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Stored").assertExists()

        onNodeWithText("Stored").performTextClearance()
        onNodeWithText("View Mode Name").performTextInput("Renamed but not saved")
        waitForIdle()

        onNodeWithText("Renamed but not saved").assertExists()

        val saved = registry.performSave()
        onScreen = false
        waitForIdle()
        registry = SaveableStateRegistry(restoredValues = saved) { true }
        onScreen = true
        waitForIdle()

        onNodeWithText("Renamed but not saved").assertExists()
        assertEquals(1, fixture.repository.viewModeReads, "the stored mode was read again over the edit")
    }
}
