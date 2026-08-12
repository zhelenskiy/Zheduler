package com.zhelenskiy.zheduler.zheduler.components.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SimpleMarkdownTextTest {

    /**
     * Code blocks used to be stripped: the renderer deleted every ``` fence, so a code
     * block reached the screen as mangled prose.
     */
    @Test
    fun codeBlockIsRenderedWithItsLanguage() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SimpleMarkdownText(markdown = "Try:\n\n```kotlin\nval answer = 42\n```\n")
            }
        }
        waitForIdle()

        onNodeWithText("val answer = 42", substring = true).assertExists()
        onNodeWithText("kotlin").assertExists()
        onNodeWithText("Try:", substring = true).assertExists()
    }

    @Test
    fun tableIsRenderedAsCells() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SimpleMarkdownText(
                    markdown = "| item | spend |\n| :--- | ----: |\n| infra | high |\n"
                )
            }
        }
        waitForIdle()

        onNodeWithText("item").assertExists()
        onNodeWithText("spend").assertExists()
        onNodeWithText("infra").assertExists()
        onNodeWithText("high").assertExists()
    }

    /** A reference inside code is code, not a link to a task. */
    @Test
    fun taskReferencesInsideCodeAreLeftAlone() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SimpleMarkdownText(
                    markdown = "See ZH-12.\n\n```\ngit commit -m \"ZH-13\"\n```\n",
                    allSpacePrefixes = listOf("ZH"),
                    getTaskById = { null },
                    onTaskClick = {},
                )
            }
        }
        waitForIdle()

        // Rendered verbatim: linkification would have rewritten it to [ZH-13](ZH-13).
        onNodeWithText("git commit -m \"ZH-13\"", substring = true).assertExists()
    }
}
