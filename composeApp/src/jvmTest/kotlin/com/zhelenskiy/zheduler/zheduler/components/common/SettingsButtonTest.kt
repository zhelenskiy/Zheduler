@file:OptIn(ExperimentalTestApi::class)

package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cog reads the same on every screen, and holds only what the *user* has set.
 *
 * What one screen does — a space's view modes and saved filters, the space list's erase-everything
 * — stays on that screen. Mixed in here, the list would differ from screen to screen again, which
 * is the thing the one button was for.
 */
class SettingsButtonTest {

    private fun runSettings(
        onThemeModeChange: (ThemeMode) -> Unit = {},
        body: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            SettingsButton(
                themeMode = ThemeMode.System,
                onThemeModeChange = onThemeModeChange,
                useDynamicColors = false,
                onDynamicColorsChange = {},
                colorSettings = ColorSettings(),
                onColorSettingsChange = {},
            )
        }
        waitForIdle()
        onNodeWithContentDescription("Settings").performClick()
        waitForIdle()
        body()
    }

    @Test
    fun theListIsWhatTheUserHasSetAndNothingAScreenDoes() = runSettings {
        onNodeWithText("Places").assertIsDisplayed()
        onNodeWithText("Wi-Fi networks").assertIsDisplayed()
        onNodeWithText("Bluetooth devices").assertIsDisplayed()
        onNodeWithText("Theme").assertIsDisplayed()

        // A space's, and the space list's. They have buttons of their own.
        onNodeWithText("View modes").assertDoesNotExist()
        onNodeWithText("Saved filters").assertDoesNotExist()
        onNodeWithText("Erase all data").assertDoesNotExist()
    }

    @Test
    fun theSettingsThemselvesAreBehindTheirOwnRowNotLaidOutInTheList() = runSettings {
        // Flat in the list, a page of sound roles and three themes and a colour picker buried the
        // list this dialog exists to be.
        onNodeWithText("Light").assertDoesNotExist()
        onNodeWithText("Dark").assertDoesNotExist()

        onNodeWithText("Theme").performClick()
        waitForIdle()

        onNodeWithText("Light").assertIsDisplayed()
        onNodeWithText("Dark").assertIsDisplayed()
    }

    @Test
    fun closingAPageComesBackToTheListItWasOpenedFrom() = runSettings {
        // It is a page of the settings, not a place the settings sent you to: shutting it must
        // land back on the list rather than out on the screen behind everything.
        onNodeWithText("Theme").performClick()
        waitForIdle()
        onNodeWithText("Done").performClick()
        waitForIdle()

        onNodeWithText("Wi-Fi networks").assertIsDisplayed()
    }

    @Test
    fun aThemeChangedIsSaidAtOnce() {
        var chosen: ThemeMode? = null
        runSettings(onThemeModeChange = { chosen = it }) {
            onNodeWithText("Theme").performClick()
            waitForIdle()
            onNodeWithText("Dark").performClick()
            waitForIdle()
        }
        assertEquals(ThemeMode.Dark, chosen)
    }

    @Test
    fun wifiAndBluetoothAreTwoEntriesAndTwoBooks() = runSettings {
        // Apart here as they are apart everywhere else: a network is picked by a name read off a
        // router and a device from what this machine is paired with, and one list holding both
        // asks a question that cannot be answered in one language.
        onNodeWithText("Wi-Fi networks").assertIsDisplayed()
        onNodeWithText("Bluetooth devices").assertIsDisplayed()
        onNodeWithText("Networks & devices").assertDoesNotExist()

        onNodeWithText("Bluetooth devices").performClick()
        waitForIdle()

        // The book that opened is the bluetooth one, and it says so rather than offering a chooser.
        onNodeWithText("No devices yet.", substring = true).assertIsDisplayed()
    }

    @Test
    fun anAddressBookIsAPageOverTheListLikeTheRest() = runSettings {
        // It used to send the user off to a screen of its own, which for naming one network is
        // leaving the app and coming back through it.
        onNodeWithText("Places").performClick()
        waitForIdle()
        onNodeWithText("No places yet.", substring = true).assertIsDisplayed()

        onNodeWithText("Done").performClick()
        waitForIdle()

        onNodeWithText("Wi-Fi networks").assertIsDisplayed()
    }

    @Test
    fun thePageBeingReadSurvivesRecreation() = runComposeUiTest {
        // Choosing a colour or auditioning sounds is minutes of work behind two dialogs. A
        // rotation part way through used to put the user back on the screen with everything shut.
        var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
        // Composed at the same position both times: saved state is keyed on where it sits.
        var onScreen by mutableStateOf(true)

        setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                if (onScreen) {
                    SettingsButton(
                        themeMode = ThemeMode.System,
                        onThemeModeChange = {},
                        useDynamicColors = false,
                        onDynamicColorsChange = {},
                        colorSettings = ColorSettings(),
                        onColorSettingsChange = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithContentDescription("Settings").performClick()
        waitForIdle()
        onNodeWithText("Theme").performClick()
        waitForIdle()
        onNodeWithText("Light").assertIsDisplayed()

        val saved = registry.performSave()
        onScreen = false
        waitForIdle()
        registry = SaveableStateRegistry(restoredValues = saved) { true }
        onScreen = true
        waitForIdle()

        onNodeWithText("Light").assertIsDisplayed()
    }

    @Test
    fun aSettingWithNothingBehindItIsNotOffered() = runSettings {
        // There is no notification-preferences platform under a bare test composition, which is
        // the same shape as a platform that has none. A row leading to an empty page is worse
        // than no row.
        onNodeWithText("Notification sounds").assertDoesNotExist()
        onNodeWithText("Theme").assertIsDisplayed()
    }
}
