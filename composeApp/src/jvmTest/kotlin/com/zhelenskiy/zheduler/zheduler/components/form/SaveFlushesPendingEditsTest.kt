package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rich editor encodes its document to Markdown only once typing pauses, so between the last
 * keystroke and that pause the newest text lives in the editor and nowhere else.
 *
 * Save reads the form there and then, while the editor is still on screen — its own flush happens
 * later, when the screen goes away, into a form nobody will read again. Everything typed since the
 * last pause was saved as though it had never been typed.
 */
@OptIn(ExperimentalTestApi::class)
class SaveFlushesPendingEditsTest {

    @Test
    fun savingRightAfterTypingKeepsWhatWasTyped() = runComposeUiTest {
        var description by mutableStateOf("start\n")
        lateinit var pendingEdits: PendingEdits

        setContent {
            pendingEdits = remember { PendingEdits() }
            MaterialTheme {
                CompositionLocalProvider(LocalPendingEdits provides pendingEdits) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        TaskDescriptionEditor(
                            markdown = description,
                            onMarkdownChange = { description = it },
                            label = "Description",
                        )
                    }
                }
            }
        }
        // Let the editor build its document from the markdown it was given.
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        // Typed, with no pause afterwards: exactly the state the Save button is pressed in.
        onAllNodes(hasSetTextAction())[0].performTextReplacement("start and the rest of the sentence")
        waitForIdle()

        // What the Save button does before reading the form.
        pendingEdits.flush()

        assertEquals(
            "start and the rest of the sentence\n",
            description,
            "the text typed since the last pause never reached the form",
        )
    }
}
