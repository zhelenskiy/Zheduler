package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TaskDescriptionEditorTest {

    /**
     * The editor scrolls its blocks internally, so it has to survive being measured by the
     * task form's own vertical scroll — which offers infinite height.
     */
    @Test
    fun rendersInsideVerticallyScrollingForm() = runComposeUiTest {
        setContent {
            Form {
                TaskDescriptionEditor(
                    markdown = "# Heading\n\nBody *text*.\n\n- one\n- two\n",
                    onMarkdownChange = {},
                    label = TestLabel,
                )
            }
        }
        waitForIdle()

        onNodeWithText(TestLabel).assertExists()
    }

    /**
     * Opening a description must not report a change. The codec canonicalizes Markdown, so
     * without the baseline gate every task would look edited as soon as it was opened and
     * the form would offer to save a pure reformat.
     */
    @Test
    fun openingWithoutEditingKeepsSourceUnchanged() = runComposeUiTest {
        // `_x_` and `+` markers survive a round trip only as `*x*` and `-`, so a
        // canonicalizing encode is guaranteed to differ from this source.
        val original = "Plain _description_ text.\n\n+ first\n+ second\n"
        var description by mutableStateOf(original)

        setContent {
            Form {
                TaskDescriptionEditor(
                    markdown = description,
                    onMarkdownChange = { description = it },
                    label = TestLabel,
                )
            }
        }
        settle()

        assertEquals(original, description)
    }

    /** An actual edit does get reported, so the gate above is not simply muting writes. */
    @Test
    fun typingIsReportedUpwards() = runComposeUiTest {
        var description by mutableStateOf("start\n")
        setContent {
            Form {
                TaskDescriptionEditor(
                    markdown = description,
                    onMarkdownChange = { description = it },
                    label = TestLabel,
                )
            }
        }
        settle()

        onNodeWithText("start", substring = true).performTextInput("typed")
        settle()

        // Not an exact match: the caret lands wherever the editor put it, so only the fact
        // that the edit reached the caller is asserted.
        assertContains(description, "typed")
    }

    /**
     * A new task starts with an empty description, and decoding "" yields no blocks at
     * all — so the editor has to end up with something typeable or the New Task form would
     * show a dead box.
     */
    @Test
    fun emptyDescriptionIsTypeable() = runComposeUiTest {
        var description by mutableStateOf("")
        setContent {
            Form {
                TaskDescriptionEditor(
                    markdown = description,
                    onMarkdownChange = { description = it },
                    label = TestLabel,
                )
            }
        }
        settle()

        // Fails outright if the empty document produced no editable block.
        onAllNodes(hasSetTextAction())[0].performTextInput("first note")
        settle()

        assertContains(description, "first note")
    }

    /**
     * A plain fence decodes to a native code block, so it is edited in place — and the
     * content, which the codec must not reflow, comes back byte for byte.
     */
    @Test
    fun plainCodeBlockIsEditedInTheBlockEditor() = runComposeUiTest {
        val original = "Run it:\n\n```\ncd app && ./gradlew run\n```\n"
        var description by mutableStateOf(original)

        setContent {
            Form {
                TaskDescriptionEditor(
                    markdown = description,
                    onMarkdownChange = { description = it },
                    label = TestLabel,
                    preview = { Text(FallbackPreviewMarker) },
                )
            }
        }
        settle()

        onNodeWithText(FallbackPreviewMarker).assertDoesNotExist()
        assertEquals(original, description)
    }

    /**
     * A table cannot be decoded into editable blocks, but it is preserved verbatim, so the
     * description keeps the block editor instead of collapsing to a plain text box.
     * The grid the table itself becomes is covered by [TableGridAndArrowNavTest].
     */
    @Test
    fun tableKeepsTheBlockEditor() = runComposeUiTest {
        val original = "Budget:\n\n| item | usd |\n| --- | ---: |\n| infra | 42 |\n"
        var description by mutableStateOf(original)

        setContent {
            Form {
                TaskDescriptionEditor(
                    markdown = description,
                    onMarkdownChange = { description = it },
                    label = TestLabel,
                    preview = { Text(FallbackPreviewMarker) },
                )
            }
        }
        settle()

        // Formatting controls are still there, and so is the prose around the table.
        onNodeWithContentDescription("Bold").assertExists()
        onNodeWithText(FallbackPreviewMarker).assertDoesNotExist()
        onNodeWithText("Budget:", substring = true).assertExists()
        assertEquals(original, description)
    }

    @Test
    fun languageTaggedCodeBlockKeepsItsTagAndTheBlockEditor() = runComposeUiTest {
        val original = "Example:\n\n```kotlin\nval a = 1\n```\n"
        var description by mutableStateOf(original)

        setContent {
            Form {
                TaskDescriptionEditor(
                    markdown = description,
                    onMarkdownChange = { description = it },
                    label = TestLabel,
                    preview = { Text(FallbackPreviewMarker) },
                )
            }
        }
        settle()

        onNodeWithContentDescription("Bold").assertExists()
        onNodeWithText("```kotlin", substring = true).assertExists()
        // The language tag survives precisely because the block is re-emitted verbatim.
        assertEquals(original, description)
    }

    /**
     * Preserved constructs with no richer shape — here a language-tagged fence — stay an
     * editable source box, and edits to that source reach the stored value.
     */
    @Test
    fun editingPreservedSourceUpdatesTheDescription() = runComposeUiTest {
        var description by mutableStateOf("```kotlin\nval a = 1\n```\n")

        setContent {
            Form {
                TaskDescriptionEditor(
                    markdown = description,
                    onMarkdownChange = { description = it },
                    label = TestLabel,
                )
            }
        }
        settle()

        onNodeWithText("val a = 1", substring = true)
            .performTextReplacement("```kotlin\nval a = 2\n```")
        settle()

        assertContains(description, "val a = 2")
    }

    @Composable
    private fun Form(content: @Composable () -> Unit) {
        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                content()
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
        const val TestLabel = "Description"

        /** Rendered only on the Markdown-source path, so it doubles as a mode probe. */
        const val FallbackPreviewMarker = "rendered-preview"
    }
}
