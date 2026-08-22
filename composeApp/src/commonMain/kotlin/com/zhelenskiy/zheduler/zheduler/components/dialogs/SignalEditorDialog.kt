package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.components.common.jsonSaver
import com.zhelenskiy.zheduler.zheduler.components.common.nullableJsonSaver
import com.zhelenskiy.zheduler.zheduler.geo.LocationPermissionStatus
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import com.zhelenskiy.zheduler.zheduler.geo.SavedSignal
import com.zhelenskiy.zheduler.zheduler.geo.SignalKind
import com.zhelenskiy.zheduler.zheduler.geo.offerableSignals
import com.zhelenskiy.zheduler.zheduler.geo.rememberLocationPermission
import com.zhelenskiy.zheduler.zheduler.geo.rememberSignalPermission
import com.zhelenskiy.zheduler.zheduler.geo.signalTrouble
import com.zhelenskiy.zheduler.zheduler.geo.supportedSignalKinds

/**
 * Adds a network or a device to the book it belongs to, or renames one already there.
 *
 * What is picked here is picked once and then reached by name, which is the point of the book: a
 * bluetooth address is not something anyone recognises in a list of rules, and the network a rule
 * is about is rarely broadcasting the name its owner thinks of it by.
 *
 * [kind] comes from which book this was opened from rather than being chosen here. The two are
 * kept apart everywhere else — they are not picked the same way — and a chooser inside the editor
 * would have been the one place they were mixed again.
 *
 * Renaming only ever touches the name. The signal underneath is identity — change it and every
 * rule already written against the old one would silently be about something else — so an existing
 * entry shows what it matches and does not offer to repoint it.
 */
@Composable
fun SignalEditorDialog(
    existing: SavedSignal?,
    kind: SignalKind,
    newId: () -> String,
    onSave: (SavedSignal) -> Unit,
    onDismiss: () -> Unit,
) {
    val signalPermission = rememberSignalPermission()
    // Wifi is answered for by the location permission rather than one of its own.
    val locationPermission = rememberLocationPermission()

    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var typed by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable(stateSaver = nullableJsonSaver<NearbySignal>()) {
        mutableStateOf(existing?.signal)
    }
    var offered by rememberSaveable(stateSaver = jsonSaver { emptyList<NearbySignal>() }) {
        mutableStateOf(emptyList<NearbySignal>())
    }
    var here by remember { mutableStateOf(emptySet<String>()) }
    // Not saved: a fact about the machine, cheap to ask again, and a stale copy is worse than none.
    var trouble by remember { mutableStateOf<String?>(null) }

    // Asked again whenever a permission changes, because granting bluetooth is exactly what turns
    // an empty list into the user's paired devices.
    LaunchedEffect(kind, signalPermission.status, locationPermission.status) {
        val all = offerableSignals(kind)
        offered = all.map { it.signal }
        here = all.filter { it.present }.mapTo(mutableSetOf()) { it.signal.key }
        trouble = signalTrouble(kind)
    }

    // What the entry would match, which is either what was picked from the list or what was typed.
    val chosen: NearbySignal? = picked
        ?: typed.trim().takeIf { it.isNotEmpty() && kind == SignalKind.Wifi }?.let(NearbySignal::Wifi)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Save a network or device" else "Rename") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Call it") },
                    placeholder = { Text(chosen?.label.orEmpty()) },
                    singleLine = true,
                    supportingText = { Text("What you will see when writing a rule.") },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (existing != null) {
                    // Shown, never offered for change: see the note on this function.
                    HorizontalDivider()
                    Text(
                        text = when (val signal = existing.signal) {
                            is NearbySignal.Wifi -> "Matches the network \"${signal.ssid}\"."
                            is NearbySignal.Bluetooth ->
                                "Matches the device at ${signal.address}. Save it again as a new " +
                                    "entry to point at a different one — rules already written " +
                                    "keep watching this."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                HorizontalDivider()

                if (kind !in supportedSignalKinds) {
                    Text(
                        text = when (kind) {
                            SignalKind.Wifi -> "This device will not say which wifi network it is " +
                                "on, so nothing can be offered here. One typed by name still works."
                            SignalKind.Bluetooth -> "This device cannot say what it is paired with, " +
                                "so nothing can be offered here. Save it on your phone instead."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                trouble?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (offered.isNotEmpty()) {
                    Text(
                        text = when (kind) {
                            SignalKind.Wifi -> "The network you are on"
                            SignalKind.Bluetooth -> "Paired with this device"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(offered, key = { it.key }) { signal ->
                            OfferedRow(
                                signal = signal,
                                selected = picked?.key == signal.key,
                                present = signal.key in here,
                                onPick = {
                                    picked = signal
                                    typed = ""
                                },
                            )
                        }
                    }
                }

                // Only for wifi, for the reason the rule editor gives: a network has a name a
                // person knows and can write down from memory, and a bluetooth address does not.
                if (kind == SignalKind.Wifi) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = {
                            typed = it
                            // Typing is a different answer from the one that was ticked, and
                            // leaving both would save whichever this happened to read first.
                            if (it.isNotBlank()) picked = null
                        },
                        label = { Text("Or a network by name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (kind == SignalKind.Bluetooth && offered.isEmpty()) {
                    Text(
                        text = "Nothing is paired with this device. Pair the one you want in the " +
                            "system settings, and it will be offered here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (kind == SignalKind.Wifi &&
                    SignalKind.Wifi in supportedSignalKinds &&
                    locationPermission.status == LocationPermissionStatus.Denied
                ) {
                    PermissionNote(
                        text = "Knowing which wifi network you are on needs permission to read " +
                            "where you are.",
                        onAllow = locationPermission::request,
                    )
                }

                if (kind == SignalKind.Bluetooth &&
                    signalPermission.status == LocationPermissionStatus.Denied
                ) {
                    PermissionNote(
                        text = "Seeing which bluetooth devices are paired needs permission.",
                        onAllow = signalPermission::request,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = existing != null || chosen != null,
                onClick = {
                    val signal = existing?.signal ?: chosen ?: return@TextButton
                    onSave(
                        SavedSignal(
                            id = existing?.id ?: newId(),
                            name = name,
                            signal = signal,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PermissionNote(text: String, onAllow: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onAllow) { Text("Allow") }
    }
}

@Composable
private fun OfferedRow(
    signal: NearbySignal,
    selected: Boolean,
    present: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // One entry is one network or one device, so these are exclusive — unlike the rule editor,
        // where a rule may watch several at once.
        RadioButton(selected = selected, onClick = onPick)
        Icon(
            imageVector = when (signal.kind) {
                SignalKind.Wifi -> Icons.Default.Wifi
                SignalKind.Bluetooth -> Icons.Default.Bluetooth
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = signal.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    present && signal is NearbySignal.Wifi -> "Joined now"
                    present -> "Connected now"
                    signal is NearbySignal.Bluetooth -> "Paired · ${signal.address}"
                    else -> "Not here now"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (present) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
