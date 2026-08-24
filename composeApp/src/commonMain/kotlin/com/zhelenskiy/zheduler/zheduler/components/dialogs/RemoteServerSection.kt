package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.animation.AnimatedVisibility
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
import com.zhelenskiy.zheduler.zheduler.sync.RemoteError
import com.zhelenskiy.zheduler.zheduler.sync.SpaceSummary
import com.zhelenskiy.zheduler.zheduler.sync.Outcome
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetup
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupStage
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupState
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
    onStateChange: (RemoteSetupState) -> Unit,
    onCheckServer: () -> Unit,
    onAuthenticate: () -> Unit,
    modifier: Modifier = Modifier,
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
                Text(
                    text = "So it can be restored on another device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                modifier = Modifier.testTag(RemoteSetupTags.TOGGLE),
                checked = state.stage !is RemoteSetupStage.Off,
                onCheckedChange = { on ->
                    onStateChange(if (on) RemoteSetup.turnedOn(state) else RemoteSetup.turnedOff(state))
                },
            )
        }

        AnimatedVisibility(visible = state.stage !is RemoteSetupStage.Off) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AddressField(state, onStateChange, onCheckServer)
                RemoteCredentialsForm(state, onStateChange, onAuthenticate)
                SignedInRow(state)
            }
        }
    }
}

@Composable
private fun AddressField(
    state: RemoteSetupState,
    onStateChange: (RemoteSetupState) -> Unit,
    onCheckServer: () -> Unit,
) {
    val stage = state.stage
    val parsed = RemoteSetup.parseAddress(state)
    // Only complained about once something has been typed, so the field does not open in red.
    val addressProblem = (parsed as? Outcome.Failure)
        ?.error
        ?.takeIf { state.addressText.isNotBlank() }

    OutlinedTextField(
        value = state.addressText,
        onValueChange = { onStateChange(RemoteSetup.addressEdited(state, it)) },
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
                RemoteFailure(error = error, onRetry = onCheckServer)
            }
            Button(
                onClick = onCheckServer,
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
    onStateChange: (RemoteSetupState) -> Unit,
    onAuthenticate: () -> Unit,
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

    val passwordTooShort = stage.mode == AuthMode.SignUp &&
        state.password.isNotEmpty() &&
        state.password.length < SyncProtocol.MIN_PASSWORD_LENGTH
    val canSubmit = !stage.busy &&
        state.username.isNotBlank() &&
        state.password.isNotEmpty() &&
        !passwordTooShort

    // Choosing between an existing account and a new one only makes sense where the account is
    // still open; signing in again is always to the one the space already belongs to.
    if (!usernameLocked) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AuthMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = stage.mode == mode,
                    onClick = { onStateChange(RemoteSetup.modeChanged(state, mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, AuthMode.entries.size),
                    enabled = !stage.busy,
                ) {
                    Text(if (mode == AuthMode.SignIn) "I have an account" else "Create an account")
                }
            }
        }
    }

    OutlinedTextField(
        value = state.username,
        onValueChange = { onStateChange(RemoteSetup.usernameEdited(state, it)) },
        label = { Text("Username") },
        modifier = Modifier.fillMaxWidth().testTag(RemoteSetupTags.USERNAME),
        singleLine = true,
        enabled = !stage.busy,
        readOnly = usernameLocked,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )

    OutlinedTextField(
        value = state.password,
        onValueChange = { onStateChange(RemoteSetup.passwordEdited(state, it)) },
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
            onRetry = onAuthenticate,
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
            onClick = onAuthenticate,
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
    onStateChange: (RemoteSetupState) -> Unit,
    onAuthenticate: () -> Unit,
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
                RemoteCredentialsForm(state, onStateChange, onAuthenticate, usernameLocked = true)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
