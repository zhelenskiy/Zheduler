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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.zhelenskiy.zheduler.zheduler.RecurrenceTrigger
import com.zhelenskiy.zheduler.zheduler.components.common.jsonSaver
import com.zhelenskiy.zheduler.zheduler.geo.LocationPermissionStatus
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import com.zhelenskiy.zheduler.zheduler.geo.SignalDirection
import com.zhelenskiy.zheduler.zheduler.geo.SignalKind
import com.zhelenskiy.zheduler.zheduler.geo.offerableSignals
import com.zhelenskiy.zheduler.zheduler.geo.signalTrouble
import com.zhelenskiy.zheduler.zheduler.geo.rememberLocationPermission
import com.zhelenskiy.zheduler.zheduler.geo.rememberSignalPermission
import com.zhelenskiy.zheduler.zheduler.geo.supportedSignalKinds
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/**
 * Chooses the wifi networks and bluetooth devices a rule waits on.
 *
 * What can be offered depends on the platform and on what the user has permitted, so the list is
 * whatever this device can enumerate — the network it is on, the devices it has paired — plus a box
 * for typing a network name that is not to hand. A rule written on a phone about the office wifi
 * should be writable at home.
 *
 * The signals are copied into the rule, exactly as areas are, so the rule keeps working on a device
 * that has never heard of them.
 */
@Composable
fun SignalSelectionDialog(
    current: RecurrenceTrigger.NearbyChange?,
    onDismiss: () -> Unit,
    onConfirm: (RecurrenceTrigger.NearbyChange?) -> Unit,
) {
    val permission = rememberSignalPermission()
    // Wifi is answered for by the location permission rather than by one of its own, so both are
    // needed here — and a user who has granted neither must be told which is missing.
    val locationPermission = rememberLocationPermission()

    var editing by rememberSaveable(
        key = "signals:editing",
        stateSaver = jsonSaver { RecurrenceTrigger.NearbyChange(persistentSetOf()) },
    ) { mutableStateOf(current ?: RecurrenceTrigger.NearbyChange(persistentSetOf())) }

    var typed by rememberSaveable(key = "signals:typed") { mutableStateOf("") }
    var offered by rememberSaveable(
        key = "signals:offered",
        stateSaver = jsonSaver { emptyList<NearbySignal>() },
    ) { mutableStateOf(emptyList<NearbySignal>()) }

    // Not saved: it is a fact about the machine, cheap to ask again, and a stale copy of it would
    // be worse than none.
    var trouble by remember { mutableStateOf<String?>(null) }

    val chosen = editing.signals

    // Asked again whenever the permission changes, because granting bluetooth is exactly what
    // turns an empty list into the user's paired devices.
    LaunchedEffect(permission.status, locationPermission.status) {
        offered = offerableSignals()
        trouble = signalTrouble()
    }

    // Anything already on the rule that this device cannot offer — paired elsewhere, or a network
    // that is simply not in range now. Shown so it cannot be lost by opening the dialog.
    val alsoChosen = chosen.filterNot { signal -> offered.any { it.key == signal.key } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wifi and bluetooth") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Fire the rule when", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SignalDirection.entries.forEach { option ->
                        FilterChip(
                            selected = editing.direction == option,
                            onClick = { editing = editing.copy(direction = option) },
                            label = { Text(option.displayName, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                HorizontalDivider()

                UnsupportedKindsNotice()

                trouble?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (offered.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(offered, key = { it.key }) { signal ->
                            val isChosen = chosen.any { it.key == signal.key }
                            SignalRow(
                                signal = signal,
                                selected = isChosen,
                                onToggle = {
                                    editing = editing.copy(
                                        signals = if (isChosen) {
                                            chosen.filterNot { it.key == signal.key }.toPersistentSet()
                                        } else {
                                            chosen.add(signal)
                                        }
                                    )
                                },
                            )
                        }
                    }
                }

                alsoChosen.forEach { signal ->
                    SignalRow(
                        signal = signal,
                        selected = true,
                        onToggle = {
                            editing = editing.copy(
                                signals = chosen.filterNot { it.key == signal.key }.toPersistentSet()
                            )
                        },
                    )
                }

                if (SignalKind.Wifi in supportedSignalKinds) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("A network by name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            enabled = typed.isNotBlank(),
                            onClick = {
                                editing = editing.copy(signals = chosen.add(NearbySignal.Wifi(typed.trim())))
                                typed = ""
                            },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add this network")
                        }
                    }
                }

                if (SignalKind.Wifi in supportedSignalKinds &&
                    locationPermission.status == LocationPermissionStatus.Denied
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            // Not obvious, and invisible when it goes wrong: the system treats the
                            // network you are on as telling it where you are, so it will not name
                            // it without this. A rule written without it simply never fires.
                            text = "Knowing which wifi network you are on needs permission to read " +
                                "where you are.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        TextButton(onClick = locationPermission::request) { Text("Allow") }
                    }
                }

                if (permission.status == LocationPermissionStatus.Denied) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Seeing which bluetooth devices are near needs permission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        TextButton(onClick = permission::request) { Text("Allow") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.isNotEmpty(),
                onClick = { onConfirm(editing) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Says plainly where a rule of this kind will not work.
 *
 * Written before it is saved rather than discovered by it never firing — which is the way a
 * condition on something the platform cannot see would otherwise fail.
 */
@Composable
private fun UnsupportedKindsNotice() {
    val missing = SignalKind.entries.filterNot { it in supportedSignalKinds }
    if (missing.isEmpty()) return
    val what = when {
        missing.size == SignalKind.entries.size -> "Wifi networks and bluetooth devices"
        missing.single() == SignalKind.Wifi -> "Wifi networks"
        else -> "Bluetooth devices"
    }
    Text(
        text = "$what cannot be seen on this device, so a rule about one will not fire here. " +
            "It still works on your phone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SignalRow(
    signal: NearbySignal,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Icon(
            imageVector = when (signal.kind) {
                SignalKind.Wifi -> Icons.Default.Wifi
                SignalKind.Bluetooth -> Icons.Default.Bluetooth
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(signal.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = when (signal) {
                    is NearbySignal.Wifi -> "Wifi network"
                    is NearbySignal.Bluetooth -> "Bluetooth · ${signal.address}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
