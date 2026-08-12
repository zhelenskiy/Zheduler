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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class TableGridAndArrowNavTest {

    private val tableSource = "| item | usd |\n| :--- | ---: |\n| infra | 42 |\n"

    /** Cells are real fields, not Markdown source: the pipes never reach the screen. */
    @Test
    fun tableRendersAsAGridOfCells() = runComposeUiTest {
        var description by mutableStateOf(tableSource)
        setContent { Form { Editor(description) { description = it } } }
        settle()

        onNodeWithText("item").assertExists()
        onNodeWithText("usd").assertExists()
        onNodeWithText("infra").assertExists()
        onNodeWithText("42").assertExists()
        onNodeWithText("| item | usd |", substring = true).assertDoesNotExist()
    }

    @Test
    fun editingACellRewritesTheMarkdown() = runComposeUiTest {
        var description by mutableStateOf(tableSource)
        setContent { Form { Editor(description) { description = it } } }
        settle()

        onNodeWithText("42").performTextReplacement("99")
        settle()

        assertContains(description, "| infra | 99 |")
        // Alignment from the original source is not lost by the round trip.
        assertContains(description, "---:")
    }

    @Test
    fun rowsAndColumnsCanBeAddedAndRemoved() = runComposeUiTest {
        var description by mutableStateOf(tableSource)
        setContent { Form { Editor(description) { description = it } } }
        settle()

        onNodeWithContentDescription("Add row").performClick()
        settle()
        assertEquals(2, description.bodyRowCount(), "row not added: $description")

        onNodeWithContentDescription("Add column").performClick()
        settle()
        assertEquals(3, description.columnCount(), "column not added: $description")

        onNodeWithContentDescription("Delete row 1").performClick()
        settle()
        assertEquals(1, description.bodyRowCount(), "row not deleted: $description")

        onNodeWithContentDescription("Delete column 3").performClick()
        settle()
        assertEquals(2, description.columnCount(), "column not deleted: $description")
    }

    /**
     * The editor has no arrow navigation of its own, so without ours a divider is a wall:
     * the caret cannot reach the text on the far side of it.
     */
    @Test
    fun downArrowCrossesADivider() = runComposeUiTest {
        var description by mutableStateOf("First.\n\n---\n\nSecond.\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        caretToEndOfField(0)
        pressArrow(Key.DirectionDown)
        typeMarker()

        // Moving forward lands at the start of the next block, so the marker leads it.
        assertContains(description, "XSecond.", message = "caret never crossed the divider")
        assertFalse("First.X" in description, "caret stayed put: $description")
    }

    @Test
    fun upArrowCrossesADivider() = runComposeUiTest {
        var description by mutableStateOf("First.\n\n---\n\nSecond.\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        // Start at the very beginning of the block below the divider, then walk back over it.
        caretToStartOfField(1)
        pressArrow(Key.DirectionUp)
        typeMarker()

        // Moving back lands at the end of the previous block.
        assertContains(description, "First.X", message = "caret never crossed back over the divider")
    }

    @Test
    fun downArrowCrossesATable() = runComposeUiTest {
        var description by mutableStateOf("First.\n\n$tableSource\nSecond.\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        caretToEndOfField(0)
        pressArrow(Key.DirectionDown)
        typeMarker()

        assertContains(description, "XSecond.", message = "caret never crossed the table")
    }

    /** Arrows that still have somewhere to go inside a block must not be hijacked. */
    @Test
    fun arrowInsideTextDoesNotJumpBlocks() = runComposeUiTest {
        var description by mutableStateOf("First.\n\n---\n\nSecond.\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        // Caret at the end, pressing Up: there is still text behind it, so this arrow
        // belongs to the text field and must not be turned into a block jump.
        caretToEndOfField(0)
        pressArrow(Key.DirectionUp)
        typeMarker()

        assertEquals(
            "Second.",
            description.substringAfter("---").trim(),
            "the arrow escaped into another block: $description",
        )
    }

    private fun ComposeUiTest.caretToEndOfField(index: Int) = placeCaret(index, Key.MoveEnd)

    private fun ComposeUiTest.caretToStartOfField(index: Int) = placeCaret(index, Key.MoveHome)

    /** A click alone lands wherever the field is widest, so the edge is set explicitly. */
    private fun ComposeUiTest.placeCaret(index: Int, key: Key) {
        onAllNodes(hasSetTextAction())[index].performClick()
        settle()
        onNode(isFocused()).performKeyInput { pressKey(key) }
        settle()
    }

    private fun ComposeUiTest.pressArrow(key: Key) {
        onNode(isFocused()).performKeyInput { pressKey(key) }
        settle()
    }

    /** Types into whichever field now holds the caret, revealing where focus landed. */
    private fun ComposeUiTest.typeMarker() {
        onNode(isFocused()).performTextInput("X")
        settle()
    }

    private fun String.bodyRowCount(): Int =
        lines().filter { it.trimStart().startsWith("|") }.size - 2

    private fun String.columnCount(): Int =
        lines().first { it.trimStart().startsWith("|") }.split("|").size - 2

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
