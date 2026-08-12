package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BackspaceAndLinksTest {

    /**
     * Backspace at the start of a block deletes a preceding block that holds no text. The
     * editor's own handler only merges into text blocks, so it ignores the keystroke and a
     * divider in front of the caret can never be removed from the keyboard.
     */
    @Test
    fun backspaceAtStartDeletesPrecedingDivider() = runComposeUiTest {
        var description by mutableStateOf("First.\n\n---\n\nSecond.\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        caretToStart(1)
        pressKey(Key.Backspace)

        assertFalse("---" in description, "divider survived backspace: $description")
        assertTrue("First." in description && "Second." in description, "text lost: $description")
    }

    @Test
    fun backspaceAtStartDeletesPrecedingTable() = runComposeUiTest {
        var description by mutableStateOf("| a | b |\n| - | - |\n| 1 | 2 |\n\nAfter.\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        // Target the paragraph by its text: the table's own cells are text fields too, and
        // an arrow or backspace inside one belongs to the grid, not to block navigation.
        onNodeWithText("After.", substring = true).performClick()
        settle()
        pressKey(Key.MoveHome)
        pressKey(Key.Backspace)

        assertFalse("| a | b |" in description, "table survived backspace: $description")
        assertTrue("After." in description, "text lost: $description")
    }

    /** Backspace in an emptied source box removes the block itself. */
    @Test
    fun backspaceInEmptiedSourceBlockDeletesIt() = runComposeUiTest {
        var description by mutableStateOf("Before.\n\n```kotlin\nval a = 1\n```\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        onNodeWithText("val a = 1", substring = true).performTextReplacement("")
        settle()
        onNode(isFocused()).performKeyInput { pressKey(Key.Backspace) }
        settle()

        assertFalse("```" in description, "source block survived: $description")
        assertTrue("Before." in description, "surrounding text lost: $description")
    }

    /** Backspace mid-text is ordinary typing and must not remove anything structural. */
    @Test
    fun backspaceInsideTextDoesNotDeleteBlocks() = runComposeUiTest {
        var description by mutableStateOf("First.\n\n---\n\nSecond.\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        onAllNodes(hasSetTextAction())[1].performClick()
        settle()
        onNode(isFocused()).performKeyInput { pressKey(Key.Backspace) }
        settle()

        assertTrue("---" in description, "divider was deleted by an ordinary backspace")
    }

    /**
     * The link chrome forces `https://` onto bare targets, which turns a ticket reference
     * into a dead URL. Storage keeps the bare form so the reference still resolves.
     */
    @Test
    fun schemedTaskReferenceLinksAreStoredBare() = runComposeUiTest {
        var description by mutableStateOf("See [ZH-12](https://ZH-12) and [docs](https://example.com).\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        // Nudge the document so the editor re-encodes what it holds.
        onAllNodes(hasSetTextAction())[0].performClick()
        settle()
        onNode(isFocused()).performKeyInput { pressKey(Key.MoveEnd) }
        onNode(isFocused()).performTextReplacement("See [ZH-12](https://ZH-12) and [docs](https://example.com). Edited.")
        settle()

        assertTrue("(ZH-12)" in description, "task link kept its scheme: $description")
        // A real URL is left alone.
        assertTrue("https://example.com" in description, "web link was mangled: $description")
    }

    private fun ComposeUiTest.caretToStart(index: Int) {
        onAllNodes(hasSetTextAction())[index].performClick()
        settle()
        onNode(isFocused()).performKeyInput { pressKey(Key.MoveHome) }
        settle()
    }

    private fun ComposeUiTest.pressKey(key: Key) {
        onNode(isFocused()).performKeyInput { pressKey(key) }
        settle()
    }

    @Composable
    private fun Editor(markdown: String, onMarkdownChange: (String) -> Unit) {
        TaskDescriptionEditor(
            markdown = markdown,
            onMarkdownChange = onMarkdownChange,
            label = "Description",
        )
    }

    @Composable
    private fun Form(content: @Composable () -> Unit) {
        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                content()
            }
        }
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }
}
