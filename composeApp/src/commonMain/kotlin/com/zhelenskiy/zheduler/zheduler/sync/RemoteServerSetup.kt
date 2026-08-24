package com.zhelenskiy.zheduler.zheduler.sync

/**
 * How far the user has got with connecting a new space to a server.
 *
 * A state machine rather than a handful of booleans, because the states genuinely exclude one
 * another and the dialog has to be able to say which one it is in: "checking" and "checked but not
 * signed in" look identical to a `serverReachable: Boolean`.
 */
sealed interface RemoteSetupStage {

    /** No server: the space will live on this device only. This is the default. */
    data object Off : RemoteSetupStage

    /** An address is being typed. [error] is what was wrong with the last attempt to use it. */
    data class Addressing(val error: RemoteError? = null) : RemoteSetupStage

    /** The address is being checked. */
    data object Checking : RemoteSetupStage

    /** The address answered as a Zheduler server; credentials are next. */
    data class Authenticating(
        val address: ServerAddress,
        val mode: AuthMode,
        val busy: Boolean = false,
        val error: RemoteError? = null,
    ) : RemoteSetupStage

    /** Signed in. Creating the space will also upload it. */
    data class Ready(val address: ServerAddress, val account: SignedInAccount) : RemoteSetupStage
}

/** Whether the credentials being entered are for a new account or an existing one. */
enum class AuthMode {
    SignIn,
    SignUp;

    val other: AuthMode get() = if (this == SignIn) SignUp else SignIn
}

/** Everything the server section of the new-space dialog holds. */
data class RemoteSetupState(
    val stage: RemoteSetupStage = RemoteSetupStage.Off,
    val addressText: String = "",
    val username: String = "",
    val password: String = "",
    /**
     * Bumped whenever something other than typing put text in these fields.
     *
     * The dialog's text boxes hold their own copy of what is in them — they have to, or a caret
     * would depend on how fast the rest of the app agrees — so they need to be told apart the one
     * time the app is entitled to overwrite what the user typed: choosing a known server, or being
     * asked to sign in again. Any change to this number is that moment.
     */
    val seedToken: Int = 0,
) {
    /**
     * Whether the dialog may be submitted.
     *
     * A server that has been asked for but not signed in to blocks creation on purpose: creating
     * the space anyway would leave the user with a local space they believed was backed up.
     */
    val canCreateSpace: Boolean
        get() = when (stage) {
            is RemoteSetupStage.Off -> true
            is RemoteSetupStage.Ready -> true
            else -> false
        }

    val readyAccount: SignedInAccount?
        get() = (stage as? RemoteSetupStage.Ready)?.account
}

/**
 * The rules of the server section, with no Compose and no coroutines in sight.
 *
 * Kept apart from the dialog so that "what happens when the address is edited after signing in"
 * is a function that can be called in a test, rather than something only reachable by driving a
 * dialog through five clicks.
 */
object RemoteSetup {

    fun turnedOn(state: RemoteSetupState): RemoteSetupState =
        state.copy(stage = RemoteSetupStage.Addressing())

    /**
     * Turning the server off keeps what was typed.
     *
     * Somebody who toggles it off and straight back on has not asked to retype their server
     * address; only the connection itself is dropped.
     */
    fun turnedOff(state: RemoteSetupState): RemoteSetupState =
        state.copy(stage = RemoteSetupStage.Off)

    /**
     * Editing the address drops any connection made with the old one.
     *
     * Otherwise a space could be created against a server the user has since typed over — signed
     * in to one address, uploading to whatever the field says now.
     */
    fun addressEdited(state: RemoteSetupState, text: String): RemoteSetupState = state.copy(
        addressText = text,
        stage = when (state.stage) {
            is RemoteSetupStage.Off -> RemoteSetupStage.Off
            else -> RemoteSetupStage.Addressing()
        },
    )

    fun usernameEdited(state: RemoteSetupState, text: String): RemoteSetupState =
        state.copy(username = text, stage = state.stage.clearingAuthError())

    fun passwordEdited(state: RemoteSetupState, text: String): RemoteSetupState =
        state.copy(password = text, stage = state.stage.clearingAuthError())

    fun modeChanged(state: RemoteSetupState, mode: AuthMode): RemoteSetupState = state.copy(
        stage = (state.stage as? RemoteSetupStage.Authenticating)
            ?.copy(mode = mode, error = null)
            ?: state.stage,
    )

    /**
     * Fills the fields in from somewhere other than the keyboard.
     *
     * The bump is the point: without it the dialog's own copy of the text would win, and tapping a
     * known server would leave the address box empty.
     */
    fun seeded(
        state: RemoteSetupState,
        addressText: String = state.addressText,
        username: String = state.username,
        password: String = state.password,
    ): RemoteSetupState = state.copy(
        addressText = addressText,
        username = username,
        password = password,
        seedToken = state.seedToken + 1,
    )

    /** The address as [ServerAddress] would read it, or the complaint to show under the field. */
    fun parseAddress(state: RemoteSetupState): Outcome<ServerAddress> =
        ServerAddress.parse(state.addressText)

    fun checking(state: RemoteSetupState): RemoteSetupState =
        state.copy(stage = RemoteSetupStage.Checking)

    fun checkFailed(state: RemoteSetupState, error: RemoteError): RemoteSetupState =
        state.copy(stage = RemoteSetupStage.Addressing(error))

    fun checkSucceeded(state: RemoteSetupState, address: ServerAddress): RemoteSetupState =
        state.copy(
            stage = RemoteSetupStage.Authenticating(
                address = address,
                // Signing in is offered first: somebody adding a second device has an account
                // already, and offering to make another one is how duplicate accounts happen.
                mode = AuthMode.SignIn,
            )
        )

    fun authenticating(state: RemoteSetupState): RemoteSetupState = state.copy(
        stage = (state.stage as? RemoteSetupStage.Authenticating)?.copy(busy = true, error = null)
            ?: state.stage,
    )

    fun authenticationFailed(state: RemoteSetupState, error: RemoteError): RemoteSetupState = state.copy(
        stage = (state.stage as? RemoteSetupStage.Authenticating)?.copy(busy = false, error = error)
            ?: state.stage,
    )

    /**
     * Signed in. The password is dropped from state at this point — it has done its job, and
     * nothing after this needs it.
     */
    fun authenticated(state: RemoteSetupState, account: SignedInAccount): RemoteSetupState {
        val address = (state.stage as? RemoteSetupStage.Authenticating)?.address ?: return state
        return state.copy(
            password = "",
            stage = RemoteSetupStage.Ready(address, account),
        )
    }

    private fun RemoteSetupStage.clearingAuthError(): RemoteSetupStage =
        (this as? RemoteSetupStage.Authenticating)?.copy(error = null) ?: this
}
