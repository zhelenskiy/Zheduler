package com.zhelenskiy.zheduler.zheduler.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteSetupTest {

    private val address = testAddress()
    private val account = SignedInAccount(AccountKey(address.value, "ada"), "user-1")

    private fun signedIn(): RemoteSetupState {
        val checked = RemoteSetup.checkSucceeded(
            RemoteSetup.turnedOn(RemoteSetupState(addressText = address.value)),
            address,
        )
        return RemoteSetup.authenticated(checked.copy(username = "ada", password = "hunter2hunter2"), account)
    }

    @Test
    fun `a space with no server can be created`() {
        assertTrue(RemoteSetupState().canCreateSpace)
        assertNull(RemoteSetupState().readyAccount)
    }

    @Test
    fun `a server that has been asked for but not signed in to blocks creation`() {
        // Otherwise the user is handed a local space they had every reason to think was backed up.
        val on = RemoteSetup.turnedOn(RemoteSetupState())
        assertFalse(on.canCreateSpace)
        assertFalse(RemoteSetup.checking(on).canCreateSpace)
        assertFalse(RemoteSetup.checkSucceeded(on, address).canCreateSpace)
        assertFalse(RemoteSetup.checkFailed(on, RemoteError.TimedOut).canCreateSpace)
    }

    @Test
    fun `signing in unblocks creation and hands over the account`() {
        val ready = signedIn()
        assertTrue(ready.canCreateSpace)
        assertEquals(account, ready.readyAccount)
    }

    @Test
    fun `the password is dropped from state once it has been used`() {
        assertEquals("", signedIn().password)
    }

    @Test
    fun `turning the server off keeps what was typed`() {
        val typed = RemoteSetup.turnedOn(RemoteSetupState()).let {
            RemoteSetup.addressEdited(it, "https://sync.example.com")
        }
        val off = RemoteSetup.turnedOff(typed)
        assertEquals(RemoteSetupStage.Off, off.stage)
        assertEquals("https://sync.example.com", off.addressText)
        // ...and turning it back on does not ask for it again.
        assertEquals("https://sync.example.com", RemoteSetup.turnedOn(off).addressText)
    }

    @Test
    fun `editing the address drops a connection made with the old one`() {
        // Otherwise the space could be created against a server the user has since typed over.
        val ready = signedIn()
        val edited = RemoteSetup.addressEdited(ready, "https://somewhere.else")
        assertIs<RemoteSetupStage.Addressing>(edited.stage)
        assertFalse(edited.canCreateSpace)
        assertNull(edited.readyAccount)
    }

    @Test
    fun `editing the address while the server is off leaves it off`() {
        val edited = RemoteSetup.addressEdited(RemoteSetupState(), "https://sync.example.com")
        assertEquals(RemoteSetupStage.Off, edited.stage)
    }

    @Test
    fun `a failed check leaves the error under the address field`() {
        val on = RemoteSetup.turnedOn(RemoteSetupState())
        val failed = RemoteSetup.checkFailed(on, RemoteError.Unreachable("refused"))
        assertEquals(RemoteError.Unreachable("refused"), assertIs<RemoteSetupStage.Addressing>(failed.stage).error)
    }

    @Test
    fun `a successful check offers signing in first`() {
        // Somebody adding a second device already has an account; offering to make another one is
        // how duplicate accounts happen.
        val checked = RemoteSetup.checkSucceeded(RemoteSetup.turnedOn(RemoteSetupState()), address)
        assertEquals(AuthMode.SignIn, assertIs<RemoteSetupStage.Authenticating>(checked.stage).mode)
    }

    @Test
    fun `typing after a rejected password clears the complaint`() {
        val checked = RemoteSetup.checkSucceeded(RemoteSetup.turnedOn(RemoteSetupState()), address)
        val rejected = RemoteSetup.authenticationFailed(
            checked,
            RemoteError.Rejected(ApiErrorCode.InvalidCredentials, "no"),
        )
        assertIs<RemoteSetupStage.Authenticating>(rejected.stage).let { assertTrue(it.error != null) }

        val retyped = RemoteSetup.passwordEdited(rejected, "another try")
        assertNull(assertIs<RemoteSetupStage.Authenticating>(retyped.stage).error)
        assertNull(
            assertIs<RemoteSetupStage.Authenticating>(
                RemoteSetup.usernameEdited(rejected, "adam").stage
            ).error
        )
    }

    @Test
    fun `switching between sign in and sign up clears the complaint but keeps the address`() {
        val checked = RemoteSetup.checkSucceeded(RemoteSetup.turnedOn(RemoteSetupState()), address)
        val rejected = RemoteSetup.authenticationFailed(checked, RemoteError.NotFound)
        val switched = RemoteSetup.modeChanged(rejected, AuthMode.SignUp)
        val stage = assertIs<RemoteSetupStage.Authenticating>(switched.stage)
        assertEquals(AuthMode.SignUp, stage.mode)
        assertNull(stage.error)
        assertEquals(address, stage.address)
    }

    @Test
    fun `authenticating marks the form busy and clears the last complaint`() {
        val checked = RemoteSetup.checkSucceeded(RemoteSetup.turnedOn(RemoteSetupState()), address)
        val rejected = RemoteSetup.authenticationFailed(checked, RemoteError.NotFound)
        val busy = assertIs<RemoteSetupStage.Authenticating>(RemoteSetup.authenticating(rejected).stage)
        assertTrue(busy.busy)
        assertNull(busy.error)
    }

    @Test
    fun `authenticating before an address has been checked does nothing`() {
        // The transitions are only ever applied to the stage they belong to; applied to another
        // they must leave it alone rather than inventing one.
        val off = RemoteSetupState()
        assertEquals(off, RemoteSetup.authenticating(off))
        assertEquals(off, RemoteSetup.authenticationFailed(off, RemoteError.NotFound))
        assertEquals(off, RemoteSetup.authenticated(off, account))
        assertEquals(off, RemoteSetup.modeChanged(off, AuthMode.SignUp))
    }

    @Test
    fun `the address is read by the same rules everywhere`() {
        assertIs<Outcome.Failure>(RemoteSetup.parseAddress(RemoteSetupState(addressText = "http://example.com")))
        assertIs<Outcome.Success<ServerAddress>>(
            RemoteSetup.parseAddress(RemoteSetupState(addressText = "https://example.com"))
        )
    }

    @Test
    fun `the other mode is the other one`() {
        assertEquals(AuthMode.SignUp, AuthMode.SignIn.other)
        assertEquals(AuthMode.SignIn, AuthMode.SignUp.other)
    }
}
