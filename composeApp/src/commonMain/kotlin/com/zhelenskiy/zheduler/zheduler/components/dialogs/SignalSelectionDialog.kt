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
import androidx.compose.material.icons.filled.BookmarkAdd
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
import com.zhelenskiy.zheduler.zheduler.geo.LocalSignalBook
import com.zhelenskiy.zheduler.zheduler.geo.LocationPermissionStatus
import com.zhelenskiy.zheduler.zheduler.geo.NearbySignal
import com.zhelenskiy.zheduler.zheduler.geo.OfferedSignal
import com.zhelenskiy.zheduler.zheduler.geo.SavedSignal
import com.zhelenskiy.zheduler.zheduler.geo.SignalDirection
import com.zhelenskiy.zheduler.zheduler.geo.SignalKind
import com.zhelenskiy.zheduler.zheduler.geo.offerableSignals
import com.zhelenskiy.zheduler.zheduler.geo.rememberLocationPermission
import com.zhelenskiy.zheduler.zheduler.geo.rememberSignalPermission
import com.zhelenskiy.zheduler.zheduler.geo.signalTrouble
import com.zhelenskiy.zheduler.zheduler.geo.supportedSignalKinds
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet

/**
 * Chooses the wifi networks, or the bluetooth devices, that a rule waits on.
 *
 * One dialog per kind rather than one for both. They are not picked the same way and cannot share
 * the controls: a network is chosen by *name* — the one you are on, or one typed from memory,
 * because a rule about the office wifi is written at home — while a device is chosen from the ones
 * this machine is already paired with, since nobody types a bluetooth address. Offering "a network
 * by name" next to a list of headphones was asking one question in two languages.
 *
 * The chosen signals are copied into the rule, exactly as areas are, so it keeps working on a
 * device that has never heard of them.
 */
@Composable
fun SignalSelectionDialog(
    kind: SignalKind,
    current: RecurrenceTrigger.NearbyChange?,
    onDismiss: () -> Unit,
    onConfirm: (RecurrenceTrigger.NearbyChange?) -> Unit,
) {
    val book = LocalSignalBook.current
    val permission = rememberSignalPermission()
    // Wifi is answered for by the location permission rather than by one of its own, so both are
    // needed here — and a user who has granted neither must be told which is missing.
    val locationPermission = rememberLocationPermission()

    var editing by rememberSaveable(
        key = "signals:editing:$kind",
        stateSaver = jsonSaver { RecurrenceTrigger.NearbyChange(persistentSetOf()) },
    ) { mutableStateOf(current ?: RecurrenceTrigger.NearbyChange(persistentSetOf())) }

    var typed by rememberSaveable(key = "signals:typed:$kind") { mutableStateOf("") }
    var offered by rememberSaveable(
        key = "signals:offered:$kind",
        stateSaver = jsonSaver { emptyList<NearbySignal>() },
    ) { mutableStateOf(emptyList<NearbySignal>()) }
    var connected by remember { mutableStateOf(emptySet<String>()) }

    // Not saved: it is a fact about the machine, cheap to ask again, and a stale copy of it would
    // be worse than none.
    var trouble by remember { mutableStateOf<String?>(null) }

    val chosen = editing.signals

    // Asked again whenever a permission changes, because granting bluetooth is exactly what turns
    // an empty list into the user's paired devices.
    LaunchedEffect(kind, permission.status, locationPermission.status) {
        val all = offerableSignals(kind)
        offered = all.map { it.signal }
        connected = all.filter { it.present }.mapTo(mutableSetOf()) { it.signal.key }
        trouble = signalTrouble(kind)
    }

    val saved = book.signals.filter { it.kind == kind }

    val measured = presenceIsMeasurable(
        kind = kind,
        supported = supportedSignalKinds,
        trouble = trouble,
        // Wifi is answered for by the location permission rather than one of its own.
        permission = if (kind == SignalKind.Wifi) locationPermission.status else permission.status,
    )

    // Anything already on the rule that neither the book nor this device can name — paired
    // elsewhere, or a network that is simply not in range now. Shown so it cannot be lost by
    // opening the dialog.
    val named = saved.mapTo(mutableSetOf()) { it.signal.key } + offered.mapTo(mutableSetOf()) { it.key }
    val alsoChosen = chosen.filterNot { it.key in named }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == SignalKind.Wifi) "Wifi networks" else "Bluetooth devices") },
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
                            label = {
                                Text(
                                    text = option.displayNameFor(kind),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }

                HorizontalDivider()

                if (kind !in supportedSignalKinds) {
                    Text(
                        text = when (kind) {
                            SignalKind.Wifi -> "This device will not say which wifi network it is " +
                                "on, so a rule about one cannot fire here. It still works on your phone."
                            SignalKind.Bluetooth -> "This device cannot say which bluetooth devices " +
                                "are near, so a rule about one cannot fire here. It still works on " +
                                "your phone."
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

                if (saved.isNotEmpty()) {
                    Text(
                        text = "Saved",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    saved.forEach { entry ->
                        val isChosen = chosen.any { it.key == entry.signal.key }
                        SignalRow(
                            signal = entry.signal,
                            // The name the user gave it, which is the whole point of keeping it:
                            // an address means nothing in a list of rules.
                            label = entry.displayName,
                            selected = isChosen,
                            connected = entry.signal.key in connected,
                            measured = measured,
                            onToggle = {
                                editing = editing.copy(
                                    signals = if (isChosen) {
                                        chosen.filterNot { it.key == entry.signal.key }.toPersistentSet()
                                    } else {
                                        chosen.add(entry.signal)
                                    }
                                )
                            },
                        )
                    }
                }

                // What the machine can see that is not in the book yet.
                val unsaved = offered.filterNot { signal -> saved.any { it.signal.key == signal.key } }
                if (unsaved.isNotEmpty()) {
                    Text(
                        text = when (kind) {
                            SignalKind.Wifi -> "The network you are on"
                            SignalKind.Bluetooth -> "Paired with this device"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(unsaved, key = { it.key }) { signal ->
                            val isChosen = chosen.any { it.key == signal.key }
                            SignalRow(
                                signal = signal,
                                selected = isChosen,
                                connected = signal.key in connected,
                                measured = measured,
                                onToggle = {
                                    editing = editing.copy(
                                        signals = if (isChosen) {
                                            chosen.filterNot { it.key == signal.key }.toPersistentSet()
                                        } else {
                                            chosen.add(signal)
                                        }
                                    )
                                },
                                // Kept under a name of its own, so the next rule can say "the car"
                                // rather than picking an address out of a list again. `keep` and
                                // not `save`: the row only leaves this list once the store has
                                // round-tripped, and nothing here can see a save still in flight.
                                onKeep = {
                                    book.keep(
                                        SavedSignal(id = book.newId(), name = "", signal = signal)
                                    )
                                },
                            )
                        }
                    }
                }

                if (alsoChosen.isNotEmpty()) {
                    Text(
                        // The heading makes the same claim the rows do, and has to be as careful.
                        text = if (measured) "Watched by this rule, but not here now"
                        else "Watched by this rule",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    alsoChosen.forEach { signal ->
                        SignalRow(
                            signal = signal,
                            selected = true,
                            connected = false,
                            measured = measured,
                            onToggle = {
                                editing = editing.copy(
                                    signals = chosen.filterNot { it.key == signal.key }.toPersistentSet()
                                )
                            },
                        )
                    }
                }

                // Only for wifi. A network has a name a person knows and can be written down from
                // memory — which is how a rule about the office is written at home. A bluetooth
                // device has an address nobody knows, and one typed wrongly is a rule that can
                // never fire, so the paired list is the only way in.
                if (kind == SignalKind.Wifi) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("Another network, by name") },
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

                if (kind == SignalKind.Bluetooth && offered.isEmpty() && alsoChosen.isEmpty()) {
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
                    PermissionRow(
                        // Not obvious, and invisible when it goes wrong: the system treats the
                        // network you are on as telling it where you are, so it will not name it
                        // without this. A rule written without it simply never fires.
                        text = "Knowing which wifi network you are on needs permission to read " +
                            "where you are.",
                        onAllow = locationPermission::request,
                    )
                }

                if (kind == SignalKind.Bluetooth &&
                    permission.status == LocationPermissionStatus.Denied
                ) {
                    PermissionRow(
                        text = "Seeing which bluetooth devices are near needs permission.",
                        onAllow = permission::request,
                    )
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
 * Whether "not here now" is something this machine is entitled to say about [kind].
 *
 * Three things have to hold: the build must be able to ask at all, the machine must have answered
 * when asked, and the app must have been allowed to ask. [supported] alone covers only the first —
 * a Mac that will not name the network it is on, and a phone with the permission refused, both
 * support wifi and measured nothing.
 *
 * [LocationPermissionStatus.Unavailable] is not a refusal: it is a desktop saying there is no such
 * permission to hold, on a platform that answers the question anyway.
 */
internal fun presenceIsMeasurable(
    kind: SignalKind,
    supported: Set<SignalKind>,
    trouble: String?,
    permission: LocationPermissionStatus,
): Boolean = kind in supported &&
    trouble == null &&
    permission != LocationPermissionStatus.Denied

/**
 * What a row says about whether the thing it names is here.
 *
 * Where presence was not measured it says so, rather than reporting an absence nothing
 * established: "not here now" beside a device sitting on the desk reads as a rule that has
 * stopped working, and this picker is where a user goes to find out why one never fires.
 */
internal fun presenceLine(signal: NearbySignal, connected: Boolean, measured: Boolean): String =
    when {
        connected && signal is NearbySignal.Wifi -> "Joined now"
        connected -> "Connected now"
        signal is NearbySignal.Bluetooth && !measured -> signal.address
        !measured -> "Not known on this device"
        signal is NearbySignal.Bluetooth -> "Paired · ${signal.address}"
        else -> "Not here now"
    }

/** The same three cases, in the words that fit what is being waited for. */
private fun SignalDirection.displayNameFor(kind: SignalKind): String = when (kind) {
    SignalKind.Wifi -> when (this) {
        SignalDirection.Appearing -> "I am on it"
        SignalDirection.Disappearing -> "I am off it"
        SignalDirection.EitherWay -> "I join or leave it"
    }

    SignalKind.Bluetooth -> when (this) {
        SignalDirection.Appearing -> "it connects"
        SignalDirection.Disappearing -> "it disconnects"
        SignalDirection.EitherWay -> "it connects or disconnects"
    }
}

@Composable
private fun PermissionRow(text: String, onAllow: () -> Unit) {
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
private fun SignalRow(
    signal: NearbySignal,
    selected: Boolean,
    connected: Boolean,
    measured: Boolean,
    onToggle: () -> Unit,
    label: String = signal.label,
    onKeep: (() -> Unit)? = null,
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
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // What tells one pair of headphones from the other two: which is switched on.
                text = presenceLine(signal, connected, measured),
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        onKeep?.let {
            IconButton(onClick = it) {
                Icon(
                    Icons.Default.BookmarkAdd,
                    contentDescription = "Keep ${signal.label}",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
