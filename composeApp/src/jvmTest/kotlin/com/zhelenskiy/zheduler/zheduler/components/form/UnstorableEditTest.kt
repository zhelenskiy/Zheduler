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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * The write-back gate refuses text the codec would lose on the round trip. Refusing is right —
 * the alternative is storing something that reads back differently — but the text stays on
 * screen, so without a notice the edit just disappears when the task is saved.
 */
@OptIn(ExperimentalTestApi::class)
class UnstorableEditTest {

    @Test
    fun anEditThatCannotBeStoredIsCalledOut() = runComposeUiTest {
        var description by mutableStateOf("start\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        // A leading space does not survive a Markdown round trip.
        onAllNodes(hasSetTextAction())[0].performTextInput(" ")
        settle()

        onNodeWithText("This change is not being saved").assertExists()
        assertFalse(description.startsWith(" "), "unstorable text reached storage: $description")
    }

    @Test
    fun theNoticeGoesAwayOnceTheTextCanBeStored() = runComposeUiTest {
        var description by mutableStateOf("start\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        onAllNodes(hasSetTextAction())[0].performTextInput(" ")
        settle()
        onNodeWithText("This change is not being saved").assertExists()

        onAllNodes(hasSetTextAction())[0].performTextReplacement("recovered")
        settle()

        onNodeWithText("This change is not being saved").assertDoesNotExist()
        assertContains(description, "recovered")
    }

    /**
     * Pressing Enter leaves an empty paragraph, which the codec reports as an unencodable
     * block. That is not data loss — a blank line has no Markdown spelling — and treating it
     * as such refused every save while the caret sat on a fresh line.
     */
    @Test
    fun aNewLineDoesNotBlockSaving() = runComposeUiTest {
        var description by mutableStateOf("head\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\ntail\n")
        setContent { Form { Editor(description) { description = it } } }
        settle()

        onNodeWithText("head", substring = true).performClick()
        settle()
        onNode(isFocused()).performKeyInput { pressKey(Key.MoveEnd) }
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        settle()

        onNodeWithText("This change is not being saved").assertDoesNotExist()

        // The new line is usable: what gets typed into it is stored.
        onNode(isFocused()).performTextInput("second")
        settle()
        assertContains(description, "second")
        assertContains(description, "| a | b |", message = "the table was disturbed: $description")
        assertContains(description, "tail")
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
