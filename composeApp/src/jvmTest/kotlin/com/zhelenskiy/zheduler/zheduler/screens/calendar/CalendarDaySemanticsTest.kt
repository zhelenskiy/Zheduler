@file:OptIn(ExperimentalTestApi::class)

package com.zhelenskiy.zheduler.zheduler.screens.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * A calendar day says four things: its number, whether it is today, whether it is the day being
 * looked at, and how much happened on it. Three of those were said only in colour and dots, so a
 * screen reader read the month as a bare list of numbers — which is the whole of what the screen
 * is for.
 */
class CalendarDaySemanticsTest {

    @Test
    fun aDaySaysWhatItIsAndWhatHappenedOnIt() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CalendarDayCell(
                    dayNumber = 17,
                    changeCount = 3,
                    isSelected = true,
                    isToday = true,
                    onClick = {},
                )
            }
        }
        waitForIdle()

        onNodeWithContentDescription("17, today, 3 changes").assertIsSelected()
    }

    @Test
    fun aQuietDaySaysSo() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CalendarDayCell(
                    dayNumber = 2,
                    changeCount = 0,
                    isSelected = false,
                    isToday = false,
                    onClick = {},
                )
            }
        }
        waitForIdle()

        onNodeWithContentDescription("2, nothing happened").assertExists()
    }
}
