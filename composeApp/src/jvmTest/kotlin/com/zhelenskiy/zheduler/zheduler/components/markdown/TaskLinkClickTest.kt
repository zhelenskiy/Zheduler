package com.zhelenskiy.zheduler.zheduler.components.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Clicking a ticket reference has to navigate in both renderers. A reference is not gated on
 * the task being already loaded: the map the screen passes holds only *connected* tasks, so
 * absence from it says nothing about whether the ticket exists.
 */
@OptIn(ExperimentalTestApi::class)
class TaskLinkClickTest {

    @Test
    fun bareReferenceNavigatesInTheMarkdownRenderer() = runComposeUiTest {
        val clicked = mutableListOf<String>()
        setContent {
            Screen {
                SimpleMarkdownText(
                    markdown = "ZH-12\n",
                    allSpacePrefixes = listOf("ZH"),
                    getTaskById = { null },
                    onTaskClick = { clicked += it },
                )
            }
        }
        clickReference()

        assertEquals(listOf("ZH-12"), clicked)
    }

    @Test
    fun explicitLinkNavigatesInTheMarkdownRenderer() = runComposeUiTest {
        val clicked = mutableListOf<String>()
        setContent {
            Screen {
                SimpleMarkdownText(
                    markdown = "[ZH-12](ZH-12)\n",
                    allSpacePrefixes = listOf("ZH"),
                    getTaskById = { null },
                    onTaskClick = { clicked += it },
                )
            }
        }
        clickReference()

        assertEquals(listOf("ZH-12"), clicked)
    }

    @Test
    fun referenceNavigatesInTheRichRenderer() = runComposeUiTest {
        val clicked = mutableListOf<String>()
        setContent {
            Screen {
                RichMarkdownText(
                    markdown = "ZH-12\n",
                    allSpacePrefixes = listOf("ZH"),
                    getTaskById = { null },
                    onTaskClick = { clicked += it },
                )
            }
        }
        clickReference()

        assertEquals(listOf("ZH-12"), clicked)
    }

    /**
     * Clicks the link itself. The surrounding text node spans the full width, so its centre
     * lands well past the glyphs and activates nothing.
     */
    private fun ComposeUiTest.clickReference() {
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val links = onAllNodes(hasClickAction()).fetchSemanticsNodes()
        if (links.isEmpty()) {
            onNodeWithText("ZH-12", substring = true).performClick()
        } else {
            onAllNodes(hasClickAction())[0].performClick()
        }
        waitForIdle()
    }

    @Composable
    private fun Screen(content: @Composable () -> Unit) {
        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}
