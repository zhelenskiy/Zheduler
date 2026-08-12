package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.settings.DescriptionEditorKind
import com.zhelenskiy.zheduler.zheduler.settings.EditorSettingsState
import com.zhelenskiy.zheduler.zheduler.settings.LocalEditorSettings
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UndoRedoTest {

    @Test
    fun undoStartsDisabledAndEnablesAfterAnEdit() = runComposeUiTest {
        var description by mutableStateOf("start\n")
        setContent { Field(DescriptionEditorKind.Rich, description) { description = it } }
        settle()

        onNodeWithContentDescription("Undo").assertIsNotEnabled()
        onNodeWithContentDescription("Redo").assertIsNotEnabled()

        onAllNodes(hasSetTextAction())[0].performTextInput("typed")
        settle()

        onNodeWithContentDescription("Undo").assertIsEnabled()
    }

    @Test
    fun typingIsUndoneAndRedone() = runComposeUiTest {
        var description by mutableStateOf("start\n")
        setContent { Field(DescriptionEditorKind.Rich, description) { description = it } }
        settle()

        onAllNodes(hasSetTextAction())[0].performTextInput("typed")
        settle()
        assertContains(description, "typed")

        onNodeWithContentDescription("Undo").performClick()
        settle()
        assertFalse("typed" in description, "undo left the text in: $description")

        onNodeWithContentDescription("Redo").performClick()
        settle()
        assertContains(description, "typed", message = "redo did not restore the text")
    }

    /**
     * Custom blocks dispatch their own updates, and a plain dispatch is documented as
     * bypassing history — so without routing them through the structural path these edits
     * would look undoable and quietly do nothing.
     */
    @Test
    fun tableCellEditIsUndoable() = runComposeUiTest {
        var description by mutableStateOf("| item | usd |\n| --- | ---: |\n| infra | 42 |\n")
        setContent { Field(DescriptionEditorKind.Rich, description) { description = it } }
        settle()

        onNodeWithText("42").performTextReplacement("99")
        settle()
        assertContains(description, "99")

        onNodeWithContentDescription("Undo").performClick()
        settle()

        assertContains(description, "42", message = "cell edit was not undone: $description")
        assertFalse("99" in description, "cell edit survived undo: $description")
    }

    @Test
    fun deletingABlockIsUndoable() = runComposeUiTest {
        var description by mutableStateOf("First.\n\n---\n\nSecond.\n")
        setContent { Field(DescriptionEditorKind.Rich, description) { description = it } }
        settle()

        onNodeWithContentDescription("Remove divider").performClick()
        settle()
        assertFalse("---" in description, "divider was not removed")

        onNodeWithContentDescription("Undo").performClick()
        settle()

        assertTrue("---" in description, "divider did not come back: $description")
    }

    /** The same controls drive the Markdown editor: the history belongs to the field. */
    @Test
    fun markdownEditorUndoesTyping() = runComposeUiTest {
        var description by mutableStateOf("start\n")
        setContent { Field(DescriptionEditorKind.Markdown, description) { description = it } }
        settle()

        onAllNodes(hasSetTextAction())[0].performTextInput("typed")
        settle()
        assertContains(description, "typed")

        onNodeWithContentDescription("Undo").performClick()
        settle()

        assertEquals("start\n", description, "undo did not restore the source")
    }

    /**
     * The point of a shared history: an edit made in one editor is still undoable after
     * switching to the other. Per-editor stacks are discarded when their editor unmounts.
     */
    @Test
    fun historySurvivesSwitchingEditors() = runComposeUiTest {
        var description by mutableStateOf("start\n")
        setContent { Field(DescriptionEditorKind.Rich, description) { description = it } }
        settle()

        onAllNodes(hasSetTextAction())[0].performTextInput("typed")
        settle()
        assertContains(description, "typed")

        onNodeWithText(DescriptionEditorKind.Markdown.label).performClick()
        settle()

        onNodeWithContentDescription("Undo").assertIsEnabled()
        onNodeWithContentDescription("Undo").performClick()
        settle()

        assertFalse(
            "typed" in description,
            "an edit made before switching was not undoable after: $description",
        )
    }

    /** Redo likewise crosses a switch. */
    @Test
    fun redoSurvivesSwitchingEditors() = runComposeUiTest {
        var description by mutableStateOf("start\n")
        setContent { Field(DescriptionEditorKind.Rich, description) { description = it } }
        settle()

        onAllNodes(hasSetTextAction())[0].performTextInput("typed")
        settle()
        onNodeWithContentDescription("Undo").performClick()
        settle()

        onNodeWithText(DescriptionEditorKind.Markdown.label).performClick()
        settle()

        onNodeWithContentDescription("Redo").assertIsEnabled()
        onNodeWithContentDescription("Redo").performClick()
        settle()

        assertContains(description, "typed", message = "redo was lost across the switch")
    }

    /**
     * A new line leaves an empty paragraph, and a blank line has no Markdown spelling, so the
     * stored text is unchanged. The description-level stack cannot see such a step at all —
     * the editor's own history is what covers it.
     */
    @Test
    fun aNewLineCanBeUndone() = runComposeUiTest {
        var description by mutableStateOf("head\n")
        setContent { Field(DescriptionEditorKind.Rich, description) { description = it } }
        settle()

        val before = onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
        onNodeWithText("head", substring = true).performClick()
        settle()
        onNode(isFocused()).performKeyInput { pressKey(Key.MoveEnd) }
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        settle()
        assertEquals(
            before + 1,
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size,
            "the new line was not added",
        )

        onNodeWithContentDescription("Undo").assertIsEnabled()
        onNodeWithContentDescription("Undo").performClick()
        settle()

        assertEquals(
            before,
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size,
            "undo did not remove the new line",
        )
    }

    /** Undoing through the editor keeps the shared stack in step rather than stacking onto it. */
    @Test
    fun editorUndoDoesNotConfuseTheSharedStack() = runComposeUiTest {
        var description by mutableStateOf("head\n")
        setContent { Field(DescriptionEditorKind.Rich, description) { description = it } }
        settle()

        onAllNodes(hasSetTextAction())[0].performTextInput("one")
        settle()
        assertContains(description, "one")

        // Handled by the block editor's own history.
        onNodeWithContentDescription("Undo").performClick()
        settle()
        assertFalse("one" in description, "editor undo did not reach storage: $description")

        // Redo must return it rather than being treated as a brand new edit.
        onNodeWithContentDescription("Redo").assertIsEnabled()
        onNodeWithContentDescription("Redo").performClick()
        settle()
        assertContains(description, "one", message = "redo lost the edit: $description")
    }

    @Composable
    private fun Field(
        kind: DescriptionEditorKind,
        markdown: String,
        onChange: (String) -> Unit,
    ) {
        val settings = remember { EditorSettingsState(mapOf(TaskId to kind)) }
        MaterialTheme {
            CompositionLocalProvider(LocalEditorSettings provides settings) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TaskDescriptionField(
                        markdown = markdown,
                        onMarkdownChange = onChange,
                        label = "Description",
                        taskId = TaskId,
                    )
                }
            }
        }
    }

    private companion object {
        const val TaskId = "ZH-1"
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }
}
