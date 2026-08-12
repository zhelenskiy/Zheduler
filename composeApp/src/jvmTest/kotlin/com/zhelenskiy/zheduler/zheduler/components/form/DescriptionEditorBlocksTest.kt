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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Blocks that hold no text — dividers and preserved Markdown — cannot be removed by any
 * built-in gesture: backspace only merges into a block that supports text, and the editor's
 * click callbacks are no-ops. They are only removable because each one carries its own
 * delete control.
 */
@OptIn(ExperimentalTestApi::class)
class DescriptionEditorBlocksTest {

    @Test
    fun dividerCanBeDeleted() = runComposeUiTest {
        var description by mutableStateOf("Above.\n\n---\n\nBelow.\n")

        setContent { Form { Editor(description) { description = it } } }
        settle()

        assertContains(description, "---")
        onNodeWithContentDescription("Remove divider").performClick()
        settle()

        assertFalse("---" in description, "divider still present in: $description")
        // The surrounding text is untouched.
        assertContains(description, "Above.")
        assertContains(description, "Below.")
    }

    @Test
    fun tableCanBeDeleted() = runComposeUiTest {
        var description by mutableStateOf("Before.\n\n| a | b |\n| - | - |\n| 1 | 2 |\n\nAfter.\n")

        setContent { Form { Editor(description) { description = it } } }
        settle()

        onNodeWithContentDescription("Remove table").performClick()
        settle()

        assertFalse("| a | b |" in description, "table still present in: $description")
        assertContains(description, "Before.")
        assertContains(description, "After.")
    }

    /** Deleting the only block must leave something to type into, not an empty document. */
    @Test
    fun deletingTheOnlyBlockLeavesAnEditableDocument() = runComposeUiTest {
        var description by mutableStateOf("---\n")

        setContent { Form { Editor(description) { description = it } } }
        settle()

        onNodeWithContentDescription("Remove divider").performClick()
        settle()

        onAllNodes(hasSetTextAction())[0].performTextInput("fresh start")
        settle()

        assertContains(description, "fresh start")
    }

    /**
     * The codec cannot build a table from typed Markdown, so the slash menu is the only way
     * to add one in the block editor.
     */
    @Test
    fun tableCanBeInsertedFromTheSlashMenu() = runComposeUiTest {
        var description by mutableStateOf("")

        setContent { Form { Editor(description) { description = it } } }
        settle()

        insertTableFromSlashMenu()

        assertTrue("| Column | Column |" in description, "no table inserted; got: $description")
        // Round-trips as a real Markdown table rather than an editor-only construct.
        assertContains(description, "| --- | --- |")
    }

    @Test
    fun insertedTableIsAlsoDeletable() = runComposeUiTest {
        var description by mutableStateOf("")

        setContent { Form { Editor(description) { description = it } } }
        settle()

        insertTableFromSlashMenu()
        assertContains(description, "| Column | Column |")

        onNodeWithContentDescription("Remove table").performClick()
        settle()

        assertEquals("", description.trim(), "table was not removed; got: $description")
    }

    /**
     * Types the way a person does. The slash session opens on the "/" keystroke and then
     * filters, so inserting the whole query at once never opens the menu at all.
     */
    private fun ComposeUiTest.insertTableFromSlashMenu() {
        onAllNodes(hasSetTextAction())[0].performTextInput("/")
        settle()
        onAllNodes(hasSetTextAction())[0].performTextInput("tab")
        settle()
        onNodeWithText("Table").performClick()
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
