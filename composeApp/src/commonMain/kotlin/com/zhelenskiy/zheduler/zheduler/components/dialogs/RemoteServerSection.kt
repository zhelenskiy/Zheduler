package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.components.common.RemoteContentBox
import com.zhelenskiy.zheduler.zheduler.components.common.RemoteContentState
import com.zhelenskiy.zheduler.zheduler.components.common.RemoteFailure
import com.zhelenskiy.zheduler.zheduler.sync.AuthMode
import com.zhelenskiy.zheduler.zheduler.sync.KnownServer
import com.zhelenskiy.zheduler.zheduler.sync.RemoteError
import com.zhelenskiy.zheduler.zheduler.sync.SpaceSummary
import com.zhelenskiy.zheduler.zheduler.sync.Outcome
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetup
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupStage
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupState
import com.zhelenskiy.zheduler.zheduler.sync.ServerAddress
import com.zhelenskiy.zheduler.zheduler.sync.SyncProtocol

/** Test tags, so the UI tests name the same widgets the user clicks. */
object RemoteSetupTags {
    const val TOGGLE = "remoteSetup:toggle"
    const val ADDRESS = "remoteSetup:address"
    const val CONNECT = "remoteSetup:connect"
    const val USERNAME = "remoteSetup:username"
    const val PASSWORD = "remoteSetup:password"
    const val SUBMIT = "remoteSetup:submit"
    const val SIGNED_IN = "remoteSetup:signedIn"

    fun knownServer(url: String) = "remoteSetup:known:$url"
}

/**
 * The part of the new-space dialog that puts a space on a server.
 *
 * Reveals itself one step at a time — address, then credentials, then confirmation — because each
 * step needs the one before it to have succeeded, and showing a password field next to an address
 * that has not answered invites the user to type a password at the wrong server.
 */
@Composable
fun RemoteServerSection(
    state: RemoteSetupState,
    onEdit: (edit: (RemoteSetupState) -> RemoteSetupState) -> Unit,
    onCheckServer: (addressText: String) -> Unit,
    onAuthenticate: (username: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
    knownServers: List<KnownServer> = emptyList(),
    onUseKnownServer: (KnownServer) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep this space on a server", style = MaterialTheme.typography.titleSmall)
                // Says what it actually is now. "So it can be restored on another device" described
                // a backup, and this is not one: the server holds the space, and this device shows
                // a copy of it that stops being editable the moment the server is out of reach.
                Text(
                    text = "The server keeps the space. This device shows a copy, and can only " +
                        "change it while the server can be reached.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                modifier = Modifier.testTag(RemoteSetupTags.TOGGLE),
                checked = state.stage !is RemoteSetupStage.Off,
                onCheckedChange = { on ->
                    onEdit { if (on) RemoteSetup.turnedOn(it) else RemoteSetup.turnedOff(it) }
                },
            )
        }

        AnimatedVisibility(visible = state.stage !is RemoteSetupStage.Off) {
            RemoteServerFields(
                state = state,
                onEdit = onEdit,
                onCheckServer = onCheckServer,
                onAuthenticate = onAuthenticate,
                knownServers = knownServers,
                onUseKnownServer = onUseKnownServer,
            )
        }
    }
}

/**
 * Choosing a server and signing in to it: address, then credentials, then confirmation.
 *
 * Its own composable because two things need it. A space being created asks for a server as one
 * part of a larger form, behind a switch; a space that already exists is sent to a server on its
 * own, where there is nothing to switch — the user opened the page to do exactly this.
 */
@Composable
fun RemoteServerFields(
    state: RemoteSetupState,
    onEdit: (edit: (RemoteSetupState) -> RemoteSetupState) -> Unit,
    onCheckServer: (addressText: String) -> Unit,
    onAuthenticate: (username: String, password: String) -> Unit,
    knownServers: List<KnownServer> = emptyList(),
    onUseKnownServer: (KnownServer) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KnownServerChoices(knownServers, state, onUseKnownServer)
        AddressField(state, onEdit, onCheckServer)
        RemoteCredentialsForm(state, onEdit, onAuthenticate)
        SignedInRow(state)
    }
}

/**
 * The servers this device has used before, offered instead of the address field.
 *
 * Somebody who runs a server puts every space on it, and typing the address again each time is
 * both tedious and the one step where a space ends up belonging to a server that does not exist.
 * Choosing one goes straight to the password: the address has answered before.
 */
@Composable
private fun KnownServerChoices(
    servers: List<KnownServer>,
    state: RemoteSetupState,
    onUseKnownServer: (KnownServer) -> Unit,
) {
    if (servers.isEmpty()) return
    val chosen = (state.stage as? RemoteSetupStage.Authenticating)?.address?.value
        ?: (state.stage as? RemoteSetupStage.Ready)?.address?.value

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Your servers",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            servers.forEach { server ->
                FilterChip(
                    selected = server.url == chosen,
                    onClick = { onUseKnownServer(server) },
                    modifier = Modifier.testTag(RemoteSetupTags.knownServer(server.url)),
                    label = {
                        Text(
                            server.lastUsername
                                ?.let { "${server.url} · $it" }
                                ?: server.url
                        )
                    },
                )
            }
        }
        Text(
            text = "…or type another address below.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddressField(
    state: RemoteSetupState,
    onEdit: (edit: (RemoteSetupState) -> RemoteSetupState) -> Unit,
    onCheckServer: (String) -> Unit,
) {
    val stage = state.stage
    // What is in the box, which is not the same thing as what the store has caught up with. See
    // [RemoteSetupState.seedToken] for why the box keeps its own copy.
    var addressText by rememberSaveable(state.seedToken) { mutableStateOf(state.addressText) }
    val parsed = ServerAddress.parse(addressText)
    // Only complained about once something has been typed, so the field does not open in red.
    val addressProblem = (parsed as? Outcome.Failure)
        ?.error
        ?.takeIf { addressText.isNotBlank() }

    OutlinedTextField(
        value = addressText,
        onValueChange = {
            addressText = it
            // Still told, because editing the address has to drop a connection made with the old
            // one. What comes back is not read here, so its timing no longer matters.
            onEdit { current -> RemoteSetup.addressEdited(current, it) }
        },
        label = { Text("Server address") },
        placeholder = { Text("https://sync.example.com") },
        modifier = Modifier.fillMaxWidth().testTag(RemoteSetupTags.ADDRESS),
        singleLine = true,
        enabled = stage !is RemoteSetupStage.Checking,
        isError = addressProblem != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        supportingText = addressProblem?.let { problem -> { Text(problem.message) } },
    )

    when (stage) {
        is RemoteSetupStage.Checking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Checking the address…", style = MaterialTheme.typography.bodySmall)
        }

        is RemoteSetupStage.Addressing -> {
            stage.error?.let { error ->
                RemoteFailure(error = error, onRetry = { onCheckServer(addressText) })
            }
            Button(
                onClick = { onCheckServer(addressText) },
                enabled = parsed is Outcome.Success,
                modifier = Modifier.testTag(RemoteSetupTags.CONNECT),
            ) {
                Text("Connect")
            }
        }

        else -> Unit
    }
}

/**
 * Username, password and the sign-in button.
 *
 * Shared between the new-space dialog and the one that appears when a token has expired, so the
 * two cannot drift — the password rule, the reveal button and the "New here?" switch are written
 * once.
 *
 * Draws nothing unless the state is at [RemoteSetupStage.Authenticating], which is what keeps a
 * password field from appearing next to an address that has not answered.
 */
@Composable
fun RemoteCredentialsForm(
    state: RemoteSetupState,
    onEdit: (edit: (RemoteSetupState) -> RemoteSetupState) -> Unit,
    onAuthenticate: (username: String, password: String) -> Unit,
    /**
     * Whether the username is fixed.
     *
     * True when signing in again for a space that already belongs to an account: signing in as
     * somebody else there would store a token for an account the space is not linked to, and the
     * upload that followed would ask to sign in again — a loop with no exit.
     */
    usernameLocked: Boolean = false,
) {
    val stage = state.stage as? RemoteSetupStage.Authenticating ?: return
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    // Held here rather than in the store, and this is the field the bug was reported against:
    // every keystroke used to be an intent, intents run in parallel, and a fast typist's letters
    // arrived out of order — the box jumped back a character and took the caret with it.
    var username by rememberSaveable(state.seedToken) { mutableStateOf(state.username) }
    // Remembered and not *saved*, unlike the username beside it. Saved state is written to disk by
    // the platform and outlives the dialog — a password does not belong there, and the cost of
    // keeping it out is that a recreation mid-typing clears this one box.
    var password by remember(state.seedToken) { mutableStateOf(state.password) }

    val passwordTooShort = stage.mode == AuthMode.SignUp &&
        password.isNotEmpty() &&
        password.length < SyncProtocol.MIN_PASSWORD_LENGTH
    val canSubmit = !stage.busy &&
        username.isNotBlank() &&
        password.isNotEmpty() &&
        !passwordTooShort
    val submit = { if (canSubmit) onAuthenticate(username, password) }

    // Choosing between an existing account and a new one only makes sense where the account is
    // still open; signing in again is always to the one the space already belongs to.
    if (!usernameLocked) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AuthMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = stage.mode == mode,
                    onClick = { onEdit { current -> RemoteSetup.modeChanged(current, mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index, AuthMode.entries.size),
                    enabled = !stage.busy,
                ) {
                    Text(if (mode == AuthMode.SignIn) "I have an account" else "Create an account")
                }
            }
        }
    }

    OutlinedTextField(
        value = username,
        onValueChange = {
            username = it
            onEdit { current -> RemoteSetup.usernameEdited(current, it) }
        },
        label = { Text("Username") },
        modifier = Modifier.fillMaxWidth().testTag(RemoteSetupTags.USERNAME),
        singleLine = true,
        enabled = !stage.busy,
        readOnly = usernameLocked,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )

    OutlinedTextField(
        value = password,
        onValueChange = {
            password = it
            onEdit { current -> RemoteSetup.passwordEdited(current, it) }
        },
        label = { Text("Password") },
        modifier = Modifier.fillMaxWidth().testTag(RemoteSetupTags.PASSWORD),
        singleLine = true,
        enabled = !stage.busy,
        isError = passwordTooShort,
        visualTransformation =
            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                )
            }
        },
        supportingText = {
            // Only stated where it is a rule the user has to meet. On sign-in the requirement
            // belongs to the password they already have, and repeating it reads as a complaint.
            if (stage.mode == AuthMode.SignUp) {
                Text("At least ${SyncProtocol.MIN_PASSWORD_LENGTH} characters.")
            }
        },
    )

    stage.error?.let { error ->
        RemoteFailure(
            error = error,
            onRetry = submit,
            isRetrying = stage.busy,
            onReviewSettings = null,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = submit,
            enabled = canSubmit,
            modifier = Modifier.testTag(RemoteSetupTags.SUBMIT),
        ) {
            Text(if (stage.mode == AuthMode.SignIn) "Sign in" else "Create account")
        }
        if (stage.busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun SignedInRow(state: RemoteSetupState) {
    val stage = state.stage as? RemoteSetupStage.Ready ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(RemoteSetupTags.SIGNED_IN),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Signed in as ${stage.account.key.username}. " +
                "The space will be uploaded when you create it.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The two ways out of a conflict, with what each one costs said out loud.
 *
 * There is no "Resolve" button anywhere: both answers make one of the two copies stop being the
 * one on the server, and a single button cannot ask which. Replacing is worded as the destructive
 * choice it is, and downloading is offered beside it because it is the answer that loses nothing.
 */
@Composable
fun SyncConflictDialog(
    spaceName: String,
    serverUrl: String,
    busy: Boolean,
    theirCopy: RemoteContentState<SpaceSummary>,
    failure: RemoteError?,
    onReplaceRemote: () -> Unit,
    onDownloadRemoteCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        // Not dismissible while something is running: every button is disabled, so a tap outside
        // would be the one way to leave — and the operation would finish against a dialog that is
        // no longer there to report to.
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("\"$spaceName\" was changed elsewhere") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Another device has uploaded a newer copy to $serverUrl since this one " +
                        "was last downloaded. Only one of them can be the copy on the server.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                // Nobody should be asked to discard a copy they have been told nothing about.
                RemoteContentBox(state = theirCopy) { summary ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Their copy: \"${summary.name}\"",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "revision ${summary.revision}, ${summary.payloadBytes / 1024} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Text(
                    text = "Downloading adds the server's copy as a separate space, so you can " +
                        "compare them and keep whichever you want. Nothing on this device is " +
                        "replaced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Shown here as well as on the card behind: a download that failed while this
                // dialog was open would otherwise just make the spinner vanish.
                failure?.let { error ->
                    RemoteFailure(error = error, onRetry = onDownloadRemoteCopy, isRetrying = busy)
                }

                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDownloadRemoteCopy,
                enabled = !busy,
                modifier = Modifier.testTag(SyncConflictTags.DOWNLOAD),
            ) {
                Text("Download the server's copy")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                TextButton(
                    onClick = onReplaceRemote,
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.testTag(SyncConflictTags.REPLACE),
                ) {
                    Text("Discard theirs, upload mine")
                }
            }
        },
    )
}

/** Test tags for the conflict dialog. */
object SyncConflictTags {
    const val REPLACE = "syncConflict:replace"
    const val DOWNLOAD = "syncConflict:download"
}

/**
 * Signing in again to a server this device already knows.
 *
 * Tokens expire, and without somewhere to type a password again a space would simply stop
 * uploading with a message and no way to act on it. The address is fixed here — it comes from the
 * space's own link, not from a field — so a re-authentication cannot quietly move a space to a
 * different server.
 */
@Composable
fun RemoteSignInDialog(
    serverUrl: String,
    state: RemoteSetupState,
    onEdit: (edit: (RemoteSetupState) -> RemoteSetupState) -> Unit,
    onAuthenticate: (username: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in again") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Your session on $serverUrl has ended.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                RemoteCredentialsForm(state, onEdit, onAuthenticate, usernameLocked = true)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
