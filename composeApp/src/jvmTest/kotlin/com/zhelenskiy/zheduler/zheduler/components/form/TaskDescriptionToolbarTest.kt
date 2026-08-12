package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * The block editor's formatting toolbar has to actually reach the screen inside the task
 * form. It is the last child of the editor's own Column, after a `weight(1f)` content
 * area, so it only survives if the form gives the editor a bounded height.
 */
@OptIn(ExperimentalTestApi::class)
class TaskDescriptionToolbarTest {

    @Test
    fun formattingToolbarIsVisibleInTheForm() = runComposeUiTest {
        setContent {
            Form {
                TaskDescriptionEditor(
                    markdown = "A description.\n",
                    onMarkdownChange = {},
                    label = "Description",
                )
            }
        }
        settle()

        onNodeWithContentDescription("Bold").assertExists()
        onNodeWithContentDescription("Italic").assertExists()
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
