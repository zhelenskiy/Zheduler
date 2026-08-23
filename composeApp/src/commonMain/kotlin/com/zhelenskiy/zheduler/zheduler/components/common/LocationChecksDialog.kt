package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.geo.LocationPermissionStatus
import com.zhelenskiy.zheduler.zheduler.geo.rememberLocationPermission
import com.zhelenskiy.zheduler.zheduler.geo.updateLocationCheckRate
import com.zhelenskiy.zheduler.zheduler.settings.LocationCheckRate
import com.zhelenskiy.zheduler.zheduler.settings.LocationSettings
import com.zhelenskiy.zheduler.zheduler.settings.createLocationSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * How often the device is asked where it is, and who decides.
 *
 * @param rate what is in force now.
 * @param onRateChange what to do about it. A no-op where nothing asks — see [Empty].
 */
@Stable
interface LocationChecks {
    val rate: LocationCheckRate
    val supported: Boolean
    fun onRateChange(rate: LocationCheckRate)

    companion object {
        /** For a platform that never asks where it is, and for a test composing a dialog alone. */
        val Empty: LocationChecks = object : LocationChecks {
            override val rate = LocationCheckRate.Automatic
            override val supported = false
            override fun onRateChange(rate: LocationCheckRate) = Unit
        }
    }
}

val LocalLocationChecks = compositionLocalOf { LocationChecks.Empty }

/**
 * The choice, as a page of the settings.
 *
 * Worth a setting at all because the cost of a location rule is almost entirely this, and what is
 * worth paying depends on what the rules are for: a reminder to buy milk when passing the shop has
 * to be asked often to work, and one for arriving home need not be.
 */
@Composable
fun LocationChecksDialog(onDismiss: () -> Unit) {
    val checks = LocalLocationChecks.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Location checks") },
        text = {
            Column {
                Text(
                    "How often this device is asked where it is, while a task is waiting for a " +
                        "place. Asking more often catches a short walk past an edge, and costs " +
                        "battery for it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LocationCheckRate.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Rounded before it is pressed, so the ripple stops at the corners.
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { checks.onRateChange(option) }
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                            .semantics { selected = checks.rate == option },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = checks.rate == option,
                            onClick = { checks.onRateChange(option) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                option.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (!checks.supported) {
                    Text(
                        // Said rather than hidden: the setting is real and will matter on the
                        // phone this account's tasks are shared with.
                        "This device never asks where it is, so nothing here changes what it does.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * The setting as it stands, read once and written straight through.
 *
 * Told to the platform as well as kept: the watch is a service that can outlive any composition,
 * so it holds its own copy and has to be handed a new one — see `updateLocationCheckRate`.
 */
@Composable
fun rememberLocationChecks(afterChange: suspend () -> Unit = {}): LocationChecks {
    val store = remember { createLocationSettingsStore() }
    // "Unavailable" is a build with no way of finding out where the device is at all — a desktop,
    // a browser tab — which is exactly the case where this setting changes nothing.
    val permission = rememberLocationPermission()
    val supported = permission.status != LocationPermissionStatus.Unavailable
    // Not the composition's scope: a recreation right after the tap would cancel the write and
    // silently put the choice back, while the watch carried on at the new rate until next start.
    // The write is short and the store is the app's own file, so nothing is left dangling.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    var current by remember { mutableStateOf(LocationCheckRate.Automatic) }

    LaunchedEffect(store) {
        current = (store.get() ?: LocationSettings()).checkRate
        updateLocationCheckRate(current)
    }

    return remember(current, supported, store, scope) {
        object : LocationChecks {
            override val rate = current
            override val supported = supported

            override fun onRateChange(chosen: LocationCheckRate) {
                // Shown first. The write is what makes the choice survive the next start, and it
                // is slow enough that a radio button waiting for it reads as a tap that missed.
                current = chosen
                updateLocationCheckRate(chosen)
                scope.launch {
                    try {
                        store.set(LocationSettings(checkRate = chosen))
                        // The new rate is registered by whatever sweeps next, and until then the
                        // old one decides when that is — so someone moving from the cheapest rate
                        // to the most responsive would have waited a quarter of an hour for it.
                        afterChange()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        // This scope has no handler above it, so anything thrown here reaches the
                        // platform's own and takes the process down — from a settings radio button.
                        // The rate is already in force: it was told to the watch before the write.
                        // What a failure costs is the choice surviving the next start, and — where
                        // the write is what threw — the sweep that would have re-registered it now.
                    }
                }
            }
        }
    }
}
