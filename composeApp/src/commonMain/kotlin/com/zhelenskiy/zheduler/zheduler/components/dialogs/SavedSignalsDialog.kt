package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.components.common.EmptySearchResults
import com.zhelenskiy.zheduler.zheduler.components.common.EmptyState
import com.zhelenskiy.zheduler.zheduler.geo.LocalSignalBook
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import com.zhelenskiy.zheduler.zheduler.geo.SavedSignal
import com.zhelenskiy.zheduler.zheduler.geo.SignalKind

/**
 * The address book of networks, or the one of devices — one [kind] at a time.
 *
 * Separated for the reason the conditions are: a network is picked by a name its owner reads off a
 * router, a device from what this machine is already paired with, and a list holding both asks one
 * question in two languages. Kept apart, each list says only what it can say.
 *
 * A dialog rather than a screen for the same reason [PlacesDialog] is one, and deleting means as
 * little here: a rule watching the office wifi took its own copy of it when it was written.
 */
@Composable
fun SavedSignalsDialog(kind: SignalKind, onDismiss: () -> Unit) {
    val book = LocalSignalBook.current

    var editingId by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var adding by rememberSaveable(kind) { mutableStateOf(false) }
    var deletingId by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(kind) { mutableStateOf("") }

    val mine = book.signals.filter { it.kind == kind }
    val editing = editingId?.let { id -> mine.firstOrNull { it.id == id } }
    val deleting = deletingId?.let { id -> mine.firstOrNull { it.id == id } }
    val matching = mine.filter { it.matches(query.trim()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kind.bookTitle, modifier = Modifier.weight(1f))
                IconButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add ${kind.oneOfThem}")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mine.isNotEmpty()) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        label = "Find one you have saved",
                    )
                }

                when {
                    mine.isEmpty() -> EmptyState(message = kind.nothingYet)

                    matching.isEmpty() -> EmptySearchResults(
                        message = "Nothing saved matches \"$query\".",
                        clearButtonText = "Clear the search",
                        onClearFilters = { query = "" },
                    )

                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(matching, key = { it.id }) { saved ->
                            SavedSignalRow(
                                saved = saved,
                                onEdit = { editingId = saved.id },
                                onDelete = { deletingId = saved.id },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    if (adding) {
        SignalEditorDialog(
            existing = null,
            kind = kind,
            newId = book::newId,
            onSave = {
                book.save(it)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }

    editing?.let { saved ->
        SignalEditorDialog(
            existing = saved,
            kind = kind,
            newId = book::newId,
            onSave = {
                book.save(it)
                editingId = null
            },
            onDismiss = { editingId = null },
        )
    }

    deleting?.let { saved ->
        DeleteConfirmationDialog(
            title = "Delete ${kind.oneOfThem}",
            message = "Delete \"${saved.displayName}\"? Rules that already watch it keep " +
                "working — they hold their own copy.",
            onConfirm = {
                book.delete(saved.id)
                deletingId = null
            },
            onDismiss = { deletingId = null },
        )
    }
}

private val SignalKind.bookTitle: String
    get() = when (this) {
        SignalKind.Wifi -> "Wi-Fi networks"
        SignalKind.Bluetooth -> "Bluetooth devices"
    }

private val SignalKind.oneOfThem: String
    get() = when (this) {
        SignalKind.Wifi -> "a network"
        SignalKind.Bluetooth -> "a device"
    }

private val SignalKind.nothingYet: String
    get() = when (this) {
        SignalKind.Wifi -> "No networks yet. Keep one here, and a task can come round when you " +
            "join it or leave it."
        SignalKind.Bluetooth -> "No devices yet. Keep one here, and a task can come round when it " +
            "connects or drops."
    }

@Composable
private fun SavedSignalRow(saved: SavedSignal, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = when (saved.kind) {
                SignalKind.Wifi -> Icons.Default.Wifi
                SignalKind.Bluetooth -> Icons.Default.Bluetooth
            },
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(saved.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                // What is actually matched, which is not always what it is called: a network named
                // "Office" here broadcasts something else, and the rule follows the SSID.
                text = when (val signal = saved.signal) {
                    is NearbySignal.Wifi -> signal.ssid
                    is NearbySignal.Bluetooth -> signal.address
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit ${saved.displayName}")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${saved.displayName}")
        }
    }
}
