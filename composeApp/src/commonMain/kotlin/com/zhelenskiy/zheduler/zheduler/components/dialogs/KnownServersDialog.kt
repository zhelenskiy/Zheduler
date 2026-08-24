package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.components.common.EmptyState
import com.zhelenskiy.zheduler.zheduler.sync.KnownServerEntry
import com.zhelenskiy.zheduler.zheduler.sync.LocalKnownServers

/**
 * The servers this device has signed in to, opened from the settings list.
 *
 * The list is written by signing in, not here: a server is remembered the first time the user
 * actually reaches one, so that the next space they create can be put on it by tapping its address
 * rather than typing it again. What this page adds is the way out — signing out of a server and
 * forgetting it, which nothing else in the app offers.
 *
 * A server holding spaces cannot be forgotten, and says so instead of hiding the button. Those
 * spaces keep their only copy of the truth there; dropping the address would leave them pointing
 * at somewhere the user can no longer name.
 */
@Composable
fun KnownServersDialog(onDismiss: () -> Unit) {
    val book = LocalKnownServers.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servers") },
        text = {
            if (book.servers.isEmpty()) {
                EmptyState("Sign in to a server while creating a space and it will be listed here.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(book.servers, key = { it.server.url }) { entry ->
                        KnownServerRow(entry, onForget = { book.forget(entry.server.url) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun KnownServerRow(entry: KnownServerEntry, onForget: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.server.url,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = describe(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.spacesHeld == 0) {
            IconButton(
                onClick = onForget,
                modifier = Modifier.testTag(forgetServerTag(entry.server.url)),
            ) {
                Icon(Icons.Default.CloudOff, contentDescription = "Sign out and forget ${entry.server.url}")
            }
        }
    }
}

private fun describe(entry: KnownServerEntry): String {
    val who = entry.server.lastUsername?.let { "Signed in as $it" } ?: "Signed in"
    return when (entry.spacesHeld) {
        0 -> "$who · nothing kept here"
        1 -> "$who · 1 space kept here"
        else -> "$who · ${entry.spacesHeld} spaces kept here"
    }
}

internal fun forgetServerTag(url: String) = "forget-server-$url"
