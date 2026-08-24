package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.sync.AuthMode
import com.zhelenskiy.zheduler.zheduler.sync.KnownServer
import com.zhelenskiy.zheduler.zheduler.sync.Outcome
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetup
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupStage
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupState
import com.zhelenskiy.zheduler.zheduler.sync.ServerAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What is typed into the server fields, when the rest of the app is slow to agree.
 *
 * This is the suite for a bug the user reported: typing a password quickly moved the caret back a
 * character. Every keystroke was an intent, intents in this store run in parallel, and letters
 * therefore reached the state out of order — the box was redrawn from an older string, and the
 * caret went with it.
 *
 * So the harness here does the cruellest version of that on purpose: the hoisted state never
 * updates at all. A field that reads its value from up there shows nothing and submits nothing; a
 * field that owns what is in it behaves exactly as it does for a slow typist.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteSetupTypingTest {

    private val address = assertIs<Outcome.Success<ServerAddress>>(
        ServerAddress.parse("https://sync.example.com")
    ).value

    /** The dialog with a state that is never written back — the worst case of a lagging store. */
    @Composable
    private fun DeafDialog(
        initial: RemoteSetupState,
        onCheckServer: (String) -> Unit = {},
        onAuthenticate: (String, String) -> Unit = { _, _ -> },
        knownServers: List<KnownServer> = emptyList(),
        onUseKnownServer: (KnownServer) -> Unit = {},
    ) {
        NewSpaceDialog(
            onDismiss = {},
            onSpaceCreated = { _, _, _ -> },
            remoteSetup = initial,
            onRemoteSetupChange = { /* deliberately dropped */ },
            onCheckServer = onCheckServer,
            onAuthenticate = onAuthenticate,
            knownServers = knownServers,
            onUseKnownServer = onUseKnownServer,
        )
    }

    private fun answered() = RemoteSetup.checkSucceeded(
        RemoteSetup.turnedOn(RemoteSetupState()),
        address,
    )

    @Test
    fun theUsernameStaysWhatWasTypedEvenIfNothingUpstreamAgrees() = runComposeUiTest {
        setContent { DeafDialog(initial = answered()) }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.USERNAME).performTextInput("ada")
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.USERNAME).assertTextContains("ada")
    }

    @Test
    fun signingInSubmitsExactlyWhatIsInTheBoxes() = runComposeUiTest {
        var submitted: Pair<String, String>? = null
        setContent {
            DeafDialog(
                initial = answered(),
                onAuthenticate = { user, secret -> submitted = user to secret },
            )
        }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.USERNAME).performTextInput("ada")
        onNodeWithTag(RemoteSetupTags.PASSWORD).performTextInput("a long enough password")
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.SUBMIT).performClick()
        waitForIdle()

        assertEquals("ada" to "a long enough password", submitted)
    }

    @Test
    fun connectSubmitsTheAddressThatIsInTheBox() = runComposeUiTest {
        var checked: String? = null
        setContent {
            DeafDialog(
                initial = RemoteSetup.turnedOn(RemoteSetupState()),
                onCheckServer = { typed -> checked = typed },
            )
        }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput("https://sync.example.com")
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.CONNECT).performClick()
        waitForIdle()

        assertEquals("https://sync.example.com", checked)
    }

    // ------------------------------------------------------------------ filled from elsewhere

    /**
     * The one time the app is allowed to overwrite the boxes.
     *
     * The fields keep their own copy, so something has to tell them apart from an echo of what was
     * typed. That something is the seed token, and choosing a known server is what bumps it.
     */
    @Test
    fun aBumpedSeedTokenRefillsTheBoxes() = runComposeUiTest {
        setContent {
            var setup by remember { mutableStateOf(answered()) }
            NewSpaceDialog(
                onDismiss = {},
                onSpaceCreated = { _, _, _ -> },
                remoteSetup = setup,
                onRemoteSetupChange = { edit -> setup = edit(setup) },
                knownServers = listOf(KnownServer("https://sync.example.com", "ada")),
                onUseKnownServer = { server ->
                    setup = RemoteSetup.seeded(
                        state = setup.copy(
                            stage = RemoteSetupStage.Authenticating(address, AuthMode.SignIn),
                        ),
                        addressText = server.url,
                        username = server.lastUsername.orEmpty(),
                        password = "",
                    )
                },
            )
        }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.USERNAME).performTextInput("someone else")
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.knownServer("https://sync.example.com")).performClick()
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.USERNAME).assertTextContains("ada")
        onNodeWithTag(RemoteSetupTags.ADDRESS).assertTextContains("https://sync.example.com")
    }

    @Test
    fun aKnownServerIsOfferedAndChoosingOneIsReported() = runComposeUiTest {
        var chosen: KnownServer? = null
        setContent {
            DeafDialog(
                initial = RemoteSetup.turnedOn(RemoteSetupState()),
                knownServers = listOf(KnownServer("https://sync.example.com", "ada")),
                onUseKnownServer = { chosen = it },
            )
        }
        waitForIdle()

        assertNull(chosen)
        onNodeWithTag(RemoteSetupTags.knownServer("https://sync.example.com")).performClick()
        waitForIdle()

        assertEquals(KnownServer("https://sync.example.com", "ada"), chosen)
    }
}
