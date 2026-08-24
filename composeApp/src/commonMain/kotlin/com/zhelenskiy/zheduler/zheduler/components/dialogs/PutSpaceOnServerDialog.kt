package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.components.common.RemoteFailure
import com.zhelenskiy.zheduler.zheduler.sync.KnownServer
import com.zhelenskiy.zheduler.zheduler.sync.RemoteError
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupState
import com.zhelenskiy.zheduler.zheduler.sync.SignedInAccount

/** Test handles for the page that sends a space that already exists to a server. */
object PutOnServerTags {
    const val DIALOG = "putOnServer:dialog"
    const val CONFIRM = "putOnServer:confirm"
}

/**
 * Sends a space that already exists to a server.
 *
 * The same three steps as creating a space on one — address, credentials, confirmation — without
 * the switch: opening this page *is* the decision, and a toggle inside it would only offer to
 * undo the thing the user just asked for.
 *
 * What it says out loud is what changes afterwards, because this is not a backup being turned on.
 * The space stops being this device's own: from here it belongs to the server, and this device
 * shows a copy that can only be edited while that server answers.
 */
@Composable
fun PutSpaceOnServerDialog(
    spaceName: String,
    state: RemoteSetupState,
    onEdit: (edit: (RemoteSetupState) -> RemoteSetupState) -> Unit,
    onCheckServer: (addressText: String) -> Unit,
    onAuthenticate: (username: String, password: String) -> Unit,
    onConfirm: (SignedInAccount) -> Unit,
    onDismiss: () -> Unit,
    knownServers: List<KnownServer> = emptyList(),
    onUseKnownServer: (KnownServer) -> Unit = {},
    /**
     * Why the upload did not happen, if it has been tried and did not.
     *
     * Shown here rather than only on the space's row behind this page: the row is under the scrim,
     * so without this a press against an unreachable server left the button enabled, the page open
     * and nothing said at all.
     */
    failure: RemoteError? = null,
    /** Whether the upload is on the wire. Keeps a second press from making a second copy. */
    isUploading: Boolean = false,
) {
    val account = state.readyAccount

    AlertDialog(
        modifier = Modifier.testTag(PutOnServerTags.DIALOG),
        onDismissRequest = onDismiss,
        title = { Text("Put \"$spaceName\" on a server") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Everything in this space is uploaded, and the server keeps it from " +
                        "then on. This device shows a copy, and can only change it while the " +
                        "server can be reached.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RemoteServerFields(
                    state = state,
                    onEdit = onEdit,
                    onCheckServer = onCheckServer,
                    onAuthenticate = onAuthenticate,
                    knownServers = knownServers,
                    onUseKnownServer = onUseKnownServer,
                )
                failure?.let { error ->
                    RemoteFailure(
                        error = error,
                        onRetry = { account?.let(onConfirm) },
                        isRetrying = isUploading,
                    )
                }
                if (isUploading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Uploading…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { account?.let(onConfirm) },
                // Signed in, and not already sending. The button cannot be the thing that finds
                // out whether the server is there, and a second press while the first is in
                // flight would put a second copy of the space on it.
                enabled = account != null && !isUploading,
                modifier = Modifier.testTag(PutOnServerTags.CONFIRM),
            ) {
                Text("Upload")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
