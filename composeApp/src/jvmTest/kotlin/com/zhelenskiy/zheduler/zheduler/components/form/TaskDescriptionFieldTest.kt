package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.settings.DescriptionEditorKind
import com.zhelenskiy.zheduler.zheduler.settings.EditorSettingsState
import com.zhelenskiy.zheduler.zheduler.settings.LocalEditorSettings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TaskDescriptionFieldTest {

    /**
     * Both editors are offered, and the preview follows the choice: Markdown source needs
     * one, the WYSIWYG editor already is one.
     */
    @Test
    fun offersBothEditorsAndPreviewsOnlyMarkdown() = runComposeUiTest {
        setContent {
            Field(markdown = "Some *description*.\n", onMarkdownChange = {})
        }
        settle()

        onNodeWithText(DescriptionEditorKind.Rich.label).assertIsSelected()
        onNodeWithText(PreviewMarker).assertDoesNotExist()

        onNodeWithText(DescriptionEditorKind.Markdown.label).performClick()
        settle()

        onNodeWithText(DescriptionEditorKind.Markdown.label).assertIsSelected()
        onNodeWithText(PreviewMarker).assertExists()
    }

    /**
     * Switching editors is not an edit. The block editor canonicalizes Markdown, so without
     * its baseline gate a round trip through it would rewrite the stored description and
     * the form would report unsaved changes nobody made.
     */
    @Test
    fun switchingEditorsLeavesTheDescriptionAlone() = runComposeUiTest {
        val original = "Kept _verbatim_.\n\n+ one\n+ two\n"
        var description by mutableStateOf(original)

        setContent {
            Field(markdown = description, onMarkdownChange = { description = it })
        }
        settle()

        onNodeWithText(DescriptionEditorKind.Markdown.label).performClick()
        settle()
        onNodeWithText(DescriptionEditorKind.Rich.label).performClick()
        settle()

        assertEquals(original, description)
    }

    /**
     * Switching editors is remembered against the task, so returning to it opens the same
     * way — and the task next door is unaffected.
     */
    @Test
    fun theEditorChoiceIsKeptPerTask() = runComposeUiTest {
        val settings = EditorSettingsState()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalEditorSettings provides settings) {
                    Column {
                        TaskDescriptionField(
                            markdown = "one\n",
                            onMarkdownChange = {},
                            label = "Description",
                            taskId = "ZH-1",
                        )
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithText(DescriptionEditorKind.Markdown.label).performClick()
        waitForIdle()

        assertEquals(DescriptionEditorKind.Markdown, settings.descriptionEditorFor("ZH-1"))
        assertEquals(DescriptionEditorKind.Rich, settings.descriptionEditorFor("ZH-2"))
    }

    @Composable
    private fun Field(markdown: String, onMarkdownChange: (String) -> Unit) {
        // Provided per test: the composition local's default is a single shared holder, so
        // a preference set by one test would otherwise leak into the next.
        val editorSettings = remember { EditorSettingsState() }
        MaterialTheme {
            CompositionLocalProvider(LocalEditorSettings provides editorSettings) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TaskDescriptionField(
                        taskId = "ZH-1",
                        markdown = markdown,
                        onMarkdownChange = onMarkdownChange,
                        label = "Description",
                        preview = { Text(PreviewMarker) },
                    )
                }
            }
        }
    }

    /** Push past the editor's sync debounce and let the resulting recomposition land. */
    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    private companion object {
        const val PreviewMarker = "rendered-preview"
    }
}
