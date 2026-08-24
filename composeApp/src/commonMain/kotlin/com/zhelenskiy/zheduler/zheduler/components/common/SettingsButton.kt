package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.components.dialogs.KnownServersDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.PlacesDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.SavedSignalsDialog
import com.zhelenskiy.zheduler.zheduler.geo.SignalKind
import com.zhelenskiy.zheduler.zheduler.sync.LocalKnownServers
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.theme.ThemeSettingsDialog
import com.zhelenskiy.zheduler.zheduler.theme.themeIcon

/**
 * What the app is set to, behind one button, and the same list on every screen.
 *
 * The header used to carry a row of pictures — a bell, a paintbrush, a pin — and which of them
 * held the thing you wanted differed from screen to screen. These four are what the *user* has
 * set rather than what one screen does, so they are the same four wherever the cog is pressed,
 * and someone who has learnt where a sound lives has learnt it everywhere.
 *
 * What belongs to a screen stays on that screen: view modes and saved filters are a space's, and
 * erasing everything is the space list's. Navigating stays in the header too — the calendar and
 * the spaces are places to go rather than things to set.
 *
 * Every row opens a page on top of the list and closes back to it. None of them navigates: these
 * are two-second jobs — name a network, pick a colour — and being sent off to another screen and
 * back through it made them feel like leaving the app.
 */
@Composable
fun SettingsButton(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit,
) {
    // Saved, both of them: what these open can be several minutes of work — a colour being picked,
    // a sound being auditioned — and a recreation part way through must come back to it rather
    // than to a shut cog.
    var open by rememberSaveable { mutableStateOf(false) }
    var showing by rememberSaveable { mutableStateOf<SettingsPane?>(null) }

    IconButton(onClick = { open = true }) {
        Icon(Icons.Default.Settings, contentDescription = "Settings")
    }

    if (!open) return

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Rows and not the settings themselves. Each is a page's worth — a book of
                // places, every role the app has, three themes and a colour picker — and laid out
                // flat here they buried the list this dialog exists to be.
                SettingsRow(
                    label = "Places",
                    description = "Where a task can come round when you arrive or leave",
                    icon = Icons.Default.LocationOn,
                    onClick = { showing = SettingsPane.Places },
                )
                // Apart, like the conditions they are picked for: a network is chosen by a name
                // read off a router and a device from what this machine is paired with, and one
                // list holding both asks a question nobody can answer in one language.
                SettingsRow(
                    label = "Wi-Fi networks",
                    description = "The networks a task can wait to be on, or off",
                    icon = Icons.Default.Wifi,
                    onClick = { showing = SettingsPane.Wifi },
                )
                SettingsRow(
                    label = "Bluetooth devices",
                    description = "The devices a task can wait to connect, or drop",
                    icon = Icons.Default.Bluetooth,
                    onClick = { showing = SettingsPane.Bluetooth },
                )
                SettingsRow(
                    label = "Location checks",
                    description = "How often this device is asked where it is",
                    icon = Icons.Default.MyLocation,
                    onClick = { showing = SettingsPane.LocationChecks },
                )
                if (notificationSoundsAvailable()) {
                    SettingsRow(
                        label = "Notification sounds",
                        description = "What the app sounds like, for each kind of reminder",
                        icon = Icons.Default.NotificationsActive,
                        onClick = { showing = SettingsPane.Sounds },
                    )
                }
                // Only once there is one to show: a device that has never used a server would be
                // offered a page that can only ever say "none", which reads as a feature that is
                // broken rather than one that is unused.
                if (LocalKnownServers.current.servers.isNotEmpty()) {
                    SettingsRow(
                        label = "Servers",
                        description = "The servers your cloud spaces live on",
                        icon = Icons.Default.Dns,
                        onClick = { showing = SettingsPane.Servers },
                    )
                }
                SettingsRow(
                    label = "Theme",
                    description = "Light or dark, and the colour it is built from",
                    icon = themeIcon(themeMode),
                    onClick = { showing = SettingsPane.Theme },
                )
            }
        },
        // "Close", where the pages it opens say "Done": two stacked dialogs both offering the
        // same word is a user pressing one and finding the other still there.
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )

    when (showing) {
        SettingsPane.Places -> PlacesDialog(onDismiss = { showing = null })
        SettingsPane.Wifi -> SavedSignalsDialog(SignalKind.Wifi, onDismiss = { showing = null })
        SettingsPane.Bluetooth ->
            SavedSignalsDialog(SignalKind.Bluetooth, onDismiss = { showing = null })

        SettingsPane.LocationChecks -> LocationChecksDialog(onDismiss = { showing = null })
        SettingsPane.Sounds -> NotificationSoundsDialog(onDismiss = { showing = null })
        SettingsPane.Servers -> KnownServersDialog(onDismiss = { showing = null })
        SettingsPane.Theme -> ThemeSettingsDialog(
            onDismiss = { showing = null },
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            useDynamicColors = useDynamicColors,
            onDynamicColorsChange = onDynamicColorsChange,
            colorSettings = colorSettings,
            onColorSettingsChange = onColorSettingsChange,
        )

        null -> Unit
    }
}

/** Which page is open over the list. Saved, so it survives a recreation. */
private enum class SettingsPane { Places, Wifi, Bluetooth, LocationChecks, Sounds, Servers, Theme }

@Composable
private fun SettingsRow(
    label: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Rounded before it is pressed, so the ripple stops at the corners rather than running
            // the width of the dialog.
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
