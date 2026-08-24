package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.sync.AccountKey
import com.zhelenskiy.zheduler.zheduler.sync.ApiErrorCode
import com.zhelenskiy.zheduler.zheduler.sync.AuthMode
import com.zhelenskiy.zheduler.zheduler.sync.Outcome
import com.zhelenskiy.zheduler.zheduler.sync.RemoteError
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetup
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupState
import com.zhelenskiy.zheduler.zheduler.sync.ServerAddress
import com.zhelenskiy.zheduler.zheduler.sync.SignedInAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server section of the new-space dialog, driven the way a user drives it.
 *
 * The state machine has its own suite; what this one is for is the wiring — that the toggle
 * reveals the address field, that the password field only appears once an address has answered,
 * and above all that "Create" stays disabled while a server has been asked for and not signed in
 * to. That last one is the difference between a space the user knows is local and one they wrongly
 * believe is backed up.
 */
@OptIn(ExperimentalTestApi::class)
class NewSpaceRemoteSetupTest {

    private val address = assertIs<Outcome.Success<ServerAddress>>(
        ServerAddress.parse("https://sync.example.com")
    ).value

    private val account = SignedInAccount(AccountKey(address.value, "ada"), "user-1")

    private val nameField = "Space Name"
    private val prefixField = "ID Prefix (e.g., WORK, HOME)"

    /** The dialog with its setup state hoisted, so what the user types actually sticks. */
    @Composable
    private fun DialogUnderTest(
        initial: RemoteSetupState?,
        onCreated: (String, String, SignedInAccount?) -> Unit = { _, _, _ -> },
        onCheckServer: () -> Unit = {},
        onAuthenticate: () -> Unit = {},
    ) {
        var setup by remember { mutableStateOf(initial) }
        NewSpaceDialog(
            onDismiss = {},
            onSpaceCreated = onCreated,
            remoteSetup = setup,
            onRemoteSetupChange = { setup = it },
            onCheckServer = onCheckServer,
            onAuthenticate = onAuthenticate,
        )
    }

    private fun turnedOn() = RemoteSetup.turnedOn(RemoteSetupState())

    private fun answered() = RemoteSetup.checkSucceeded(turnedOn(), address)

    @Test
    fun aSpaceWithNoServerCanBeCreatedStraightAway() = runComposeUiTest {
        var created: Triple<String, String, SignedInAccount?>? = null
        setContent {
            DialogUnderTest(
                initial = RemoteSetupState(),
                onCreated = { name, prefix, acc -> created = Triple(name, prefix, acc) },
            )
        }
        waitForIdle()

        onNodeWithText(nameField).performTextInput("Work")
        onNodeWithText(prefixField).performTextInput("WRK")
        waitForIdle()

        onNodeWithText("Create").assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(Triple("Work", "WRK", null), created)
    }

    @Test
    fun theServerFieldsOnlyAppearOnceTheToggleIsOn() = runComposeUiTest {
        setContent { DialogUnderTest(initial = RemoteSetupState()) }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.ADDRESS).assertDoesNotExist()

        onNodeWithTag(RemoteSetupTags.TOGGLE).performClick()
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.ADDRESS).assertIsDisplayed()
        // Still no password field: nothing has answered at that address yet, and a password typed
        // at an unverified address is a password typed at whoever is on the other end.
        onNodeWithTag(RemoteSetupTags.PASSWORD).assertDoesNotExist()
    }

    @Test
    fun creationIsBlockedWhileAServerIsAskedForButNotSignedInTo() = runComposeUiTest {
        setContent { DialogUnderTest(initial = RemoteSetupState()) }
        waitForIdle()

        onNodeWithText(nameField).performTextInput("Work")
        onNodeWithText(prefixField).performTextInput("WRK")
        waitForIdle()
        onNodeWithText("Create").assertIsEnabled()

        onNodeWithTag(RemoteSetupTags.TOGGLE).performClick()
        waitForIdle()

        onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun connectIsRefusedUntilTheAddressIsOneThisAppWillUse() = runComposeUiTest {
        setContent { DialogUnderTest(initial = turnedOn()) }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.CONNECT).assertIsNotEnabled()

        // Plain http off loopback would put the password on the wire, so it is refused here
        // rather than at the moment it is sent.
        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput("http://sync.example.com")
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.CONNECT).assertIsNotEnabled()
        onNodeWithText(
            "http:// would send your password unencrypted. Use https://, " +
                "or run the server on this device."
        ).assertIsDisplayed()
    }

    @Test
    fun anHttpsAddressEnablesConnect() = runComposeUiTest {
        var checks = 0
        setContent { DialogUnderTest(initial = turnedOn(), onCheckServer = { checks++ }) }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput("https://sync.example.com")
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.CONNECT).assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(1, checks)
    }

    @Test
    fun aServerThatDidNotAnswerIsShownWithARetry() = runComposeUiTest {
        var checks = 0
        val failed = RemoteSetup.checkFailed(
            RemoteSetup.turnedOn(RemoteSetupState(addressText = "https://sync.example.com")),
            RemoteError.Unreachable("connection refused"),
        )
        setContent { DialogUnderTest(initial = failed, onCheckServer = { checks++ }) }
        waitForIdle()

        onNodeWithText("Could not reach the server: connection refused").assertIsDisplayed()
        onNodeWithText("Try again").performClick()
        waitForIdle()

        assertEquals(1, checks)
    }

    @Test
    fun theCredentialsAppearOnceTheServerHasAnswered() = runComposeUiTest {
        var authentications = 0
        setContent { DialogUnderTest(initial = answered(), onAuthenticate = { authentications++ }) }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.USERNAME).assertIsDisplayed()
        onNodeWithTag(RemoteSetupTags.SUBMIT).assertIsNotEnabled()

        onNodeWithTag(RemoteSetupTags.USERNAME).performTextInput("ada")
        onNodeWithTag(RemoteSetupTags.PASSWORD).performTextInput("a long enough password")
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.SUBMIT).assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(1, authentications)
    }

    @Test
    fun aNewAccountIsRefusedAShortPasswordBeforeItIsSent() = runComposeUiTest {
        setContent { DialogUnderTest(initial = RemoteSetup.modeChanged(answered(), AuthMode.SignUp)) }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.USERNAME).performTextInput("ada")
        onNodeWithTag(RemoteSetupTags.PASSWORD).performTextInput("short")
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun signingInHandsTheAccountToTheCreateButton() = runComposeUiTest {
        var created: Triple<String, String, SignedInAccount?>? = null
        setContent {
            DialogUnderTest(
                initial = RemoteSetup.authenticated(answered(), account),
                onCreated = { name, prefix, acc -> created = Triple(name, prefix, acc) },
            )
        }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.SIGNED_IN).assertIsDisplayed()
        onNodeWithText(nameField).performTextInput("Work")
        onNodeWithText(prefixField).performTextInput("WRK")
        waitForIdle()

        onNodeWithText("Create").assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(Triple("Work", "WRK", account), created)
    }

    @Test
    fun aRejectedPasswordIsShownWithoutOfferingARetryOfTheSameOne() = runComposeUiTest {
        val rejected = RemoteSetup.authenticationFailed(
            answered(),
            RemoteError.Rejected(
                ApiErrorCode.InvalidCredentials,
                "That username and password do not match.",
            ),
        )
        setContent { DialogUnderTest(initial = rejected) }
        waitForIdle()

        onNodeWithText("That username and password do not match.").assertIsDisplayed()
        onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun editingTheAddressAfterSigningInBlocksCreationAgain() = runComposeUiTest {
        // The space must not be created against a server the user has since typed over.
        setContent { DialogUnderTest(initial = RemoteSetup.authenticated(answered(), account)) }
        waitForIdle()

        onNodeWithText(nameField).performTextInput("Work")
        onNodeWithText(prefixField).performTextInput("WRK")
        waitForIdle()
        onNodeWithText("Create").assertIsEnabled()

        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput("x")
        waitForIdle()

        onNodeWithText("Create").assertIsNotEnabled()
        onNodeWithTag(RemoteSetupTags.SIGNED_IN).assertDoesNotExist()
    }

    @Test
    fun theWholeSectionIsAbsentWhenThereIsNoSyncAtAll() = runComposeUiTest {
        var created: Triple<String, String, SignedInAccount?>? = null
        setContent {
            DialogUnderTest(
                initial = null,
                onCreated = { name, prefix, acc -> created = Triple(name, prefix, acc) },
            )
        }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.TOGGLE).assertDoesNotExist()
        onNodeWithText("Keep this space on a server").assertDoesNotExist()

        onNodeWithText(nameField).performTextInput("Work")
        onNodeWithText(prefixField).performTextInput("WRK")
        waitForIdle()
        onNodeWithText("Create").assertIsEnabled().performClick()
        waitForIdle()

        assertTrue(created != null)
        assertNull(created?.third)
    }
}
