@file:OptIn(ExperimentalTestApi::class, ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.sync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.Task
import com.zhelenskiy.zheduler.zheduler.TaskWithTotals
import com.zhelenskiy.zheduler.zheduler.components.common.SettingsButton
import com.zhelenskiy.zheduler.zheduler.components.common.TaskCard
import com.zhelenskiy.zheduler.zheduler.components.dialogs.forgetServerTag
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * What a space kept on a server looks like when the server is out of reach.
 *
 * The rule these check is the one the user asked for out loud: a cloud space that cannot reach its
 * server can be read and cannot be changed. That has to be visible — a banner saying how old the
 * copy is — and it has to be true, which means the ways of writing are gone rather than disabled.
 */
class CloudSpaceUiTest {

    private val link = RemoteSpaceLink(
        spaceId = "space-1",
        account = AccountKey("https://sync.example.com", "ada"),
        remoteSpaceId = "remote-1",
        lastSyncedRevision = 4,
        lastSyncedAtEpochSeconds = 1_700_000_000,
    )

    private fun offline(): CloudSpaceStatus = CloudSpaceStatus.Offline(
        link = link,
        error = RemoteError.Unreachable("no route"),
        asOf = Instant.fromEpochSeconds(link.lastSyncedAtEpochSeconds),
    )

    private fun ComposeUiTest.withEditing(
        status: CloudSpaceStatus,
        onRetry: () -> Unit = {},
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        setContent {
            CompositionLocalProvider(
                LocalSpaceEditing provides SpaceEditing(status, retry = onRetry),
                content = content,
            )
        }
        waitForIdle()
    }

    // ------------------------------------------------------------------ the banner

    @Test
    fun anOfflineSpaceSaysSoAndOffersToTryAgain() = runComposeUiTest {
        var retries = 0
        withEditing(offline(), onRetry = { retries++ }) { CloudSpaceBanner() }

        onNodeWithTag(OFFLINE_TAG).assertIsDisplayed()
        onNodeWithText("This space is kept on https://sync.example.com. Editing is off until it can be reached again.")
            .assertIsDisplayed()

        onNodeWithTag(RETRY_TAG).performClick()
        waitForIdle()
        assertEquals(1, retries)
    }

    @Test
    fun aRefusalIsShownAsSomethingOtherThanBeingOffline() = runComposeUiTest {
        withEditing(CloudSpaceStatus.Blocked(link, RemoteError.NotFound)) { CloudSpaceBanner() }

        onNodeWithTag(BLOCKED_TAG).assertIsDisplayed()
        onNodeWithTag(OFFLINE_TAG).assertDoesNotExist()
    }

    @Test
    fun aSpaceInStepWithItsServerSaysNothingAtAll() = runComposeUiTest {
        withEditing(CloudSpaceStatus.Live(link)) { CloudSpaceBanner() }

        onNodeWithTag(OFFLINE_TAG).assertDoesNotExist()
        onNodeWithTag(BLOCKED_TAG).assertDoesNotExist()
        onNodeWithTag(CHECKING_TAG).assertDoesNotExist()
    }

    @Test
    fun aSpaceBeingCheckedSaysSoWithNothingToRetry() = runComposeUiTest {
        withEditing(CloudSpaceStatus.Checking(link)) { CloudSpaceBanner() }

        onNodeWithTag(CHECKING_TAG).assertIsDisplayed()
        // Nothing to press: the ask is already in flight, and a second one would not be faster.
        onNodeWithTag(RETRY_TAG).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ the ways of writing

    private fun sampleTask() = TaskWithTotals(
        task = Task(id = "WRK-1", spaceId = "space-1", title = "Something to do"),
        totalDueDate = null,
        totalPriority = null,
    )

    @Test
    fun aTaskInAnOfflineSpaceOffersNoWayToChangeIt() = runComposeUiTest {
        withEditing(offline()) {
            TaskCard(taskWithTotals = sampleTask(), onClick = {}, onDelete = {}, onCopy = {})
        }

        // Gone rather than greyed: the banner has already said why.
        onNodeWithContentDescription("Delete task").assertDoesNotExist()
        onNodeWithContentDescription("Duplicate task").assertDoesNotExist()
        // Still readable, which is the other half of the promise.
        onNodeWithText("Something to do").assertIsDisplayed()
    }

    @Test
    fun aTaskInASpaceThatCanBeReachedKeepsItsButtons() = runComposeUiTest {
        withEditing(CloudSpaceStatus.Live(link)) {
            TaskCard(taskWithTotals = sampleTask(), onClick = {}, onDelete = {}, onCopy = {})
        }

        onNodeWithContentDescription("Delete task").assertIsDisplayed()
        onNodeWithContentDescription("Duplicate task").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ the servers page

    private fun book(vararg entries: KnownServerEntry, onForget: (String) -> Unit = {}) =
        object : KnownServerBook {
            override val servers: List<KnownServerEntry> = entries.toList()
            override fun forget(url: String) = onForget(url)
        }

    private fun ComposeUiTest.openSettings(book: KnownServerBook) {
        setContent {
            CompositionLocalProvider(LocalKnownServers provides book) {
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
        waitForIdle()
        onNodeWithContentDescription("Settings").performClick()
        waitForIdle()
    }

    @Test
    fun aDeviceWithNoServersIsNotOfferedAServersPage() = runComposeUiTest {
        openSettings(book())

        onNodeWithText("Servers").assertDoesNotExist()
    }

    @Test
    fun aServerNothingDependsOnCanBeSignedOutOfAndForgotten() = runComposeUiTest {
        var forgotten: String? = null
        openSettings(
            book(
                KnownServerEntry(KnownServer("https://sync.example.com", "ada"), spacesHeld = 0),
                onForget = { forgotten = it },
            )
        )

        onNodeWithText("Servers").performClick()
        waitForIdle()
        onNodeWithText("Signed in as ada · nothing kept here").assertIsDisplayed()

        assertNull(forgotten)
        onNodeWithTag(forgetServerTag("https://sync.example.com")).performClick()
        waitForIdle()
        assertEquals("https://sync.example.com", forgotten)
    }

    @Test
    fun aServerHoldingASpaceCannotBeForgotten() = runComposeUiTest {
        openSettings(
            book(KnownServerEntry(KnownServer("https://sync.example.com", "ada"), spacesHeld = 2))
        )

        onNodeWithText("Servers").performClick()
        waitForIdle()

        // Said rather than hidden: a missing button with no reason reads as a broken one.
        onNodeWithText("Signed in as ada · 2 spaces kept here").assertIsDisplayed()
        onNodeWithTag(forgetServerTag("https://sync.example.com")).assertDoesNotExist()
    }
}
