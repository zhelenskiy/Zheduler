@file:OptIn(ExperimentalTestApi::class)

package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import com.zhelenskiy.zheduler.zheduler.geo.SavedSignal
import com.zhelenskiy.zheduler.zheduler.geo.SignalKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The book is picked from by name, and what is underneath the name is identity.
 *
 * A rule copies the signal, not the entry, so repointing an entry cannot rewrite a rule — but it
 * would silently change what the *next* rule written against that name watches, which is the same
 * surprise one step later.
 */
class SignalEditorDialogTest {

    private val office = SavedSignal(
        id = "office",
        name = "The office",
        signal = NearbySignal.Wifi("acme-corp-5G"),
    )

    private val car = SavedSignal(
        id = "car",
        name = "The car",
        signal = NearbySignal.Bluetooth("AA:BB:CC:DD:EE:FF", "Car audio"),
    )

    @Test
    fun renamingAnEntryChangesNothingAboutWhatItMatches() = runComposeUiTest {
        var saved: SavedSignal? = null
        setContent {
            SignalEditorDialog(
                existing = car,
                kind = SignalKind.Bluetooth,
                newId = { "should not be asked for" },
                onSave = { saved = it },
                onDismiss = {},
            )
        }
        waitForIdle()

        onNodeWithText("The car").performTextClearance()
        onNodeWithText("Call it").performTextInput("Commute")
        waitForIdle()
        onNodeWithText("Save").performClick()
        waitForIdle()

        assertEquals("Commute", saved?.name)
        assertEquals(car.id, saved?.id, "renaming is not a new entry")
        assertEquals(car.signal, saved?.signal, "the address is what rules are written against")
    }

    @Test
    fun anExistingEntryIsNotOfferedSomethingElseToPointAt() = runComposeUiTest {
        // Picking belongs to adding, not to renaming: offered here, one keystroke would turn "The
        // office" into a different network under a name rules already use.
        //
        // A wifi entry and not the car, because what is asserted has to be something that *would*
        // be there otherwise: the type-a-network field renders from the kind alone and owes
        // nothing to what this machine can see, so its absence is the picking half being skipped
        // rather than a list that happens to be empty in a test.
        setContent {
            SignalEditorDialog(
                existing = office,
                kind = SignalKind.Wifi,
                newId = { "" },
                onSave = {},
                onDismiss = {},
            )
        }
        waitForIdle()

        onNodeWithText("Or a network by name").assertDoesNotExist()
        onNodeWithText("Matches the network \"acme-corp-5G\"", substring = true).assertExists()
    }

    @Test
    fun nothingCanBeSavedUntilThereIsSomethingToMatch() = runComposeUiTest {
        // A name on its own is an entry that watches nothing — offered in the rule editor, it
        // would be a rule that can never fire and nothing on screen to say why.
        setContent {
            SignalEditorDialog(
                existing = null,
                kind = SignalKind.Wifi,
                newId = { "new" },
                onSave = {},
                onDismiss = {},
            )
        }
        waitForIdle()

        onNodeWithText("Call it").performTextInput("The office")
        waitForIdle()

        onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun aNetworkTypedByNameIsEnoughToSaveOne() = runComposeUiTest {
        var saved: SavedSignal? = null
        setContent {
            SignalEditorDialog(
                existing = null,
                kind = SignalKind.Wifi,
                newId = { "new" },
                onSave = { saved = it },
                onDismiss = {},
            )
        }
        waitForIdle()

        onNodeWithText("Call it").performTextInput("The office")
        waitForIdle()
        onNodeWithText("Or a network by name").performTextInput("acme-corp-5G")
        waitForIdle()
        onNodeWithText("Save").performClick()
        waitForIdle()

        assertEquals(NearbySignal.Wifi("acme-corp-5G"), saved?.signal)
        assertEquals("The office", saved?.name)
    }
}
