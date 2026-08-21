package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.zhelenskiy.zheduler.zheduler.events.NotificationSound
import com.zhelenskiy.zheduler.zheduler.events.previewNotificationSound
import com.zhelenskiy.zheduler.zheduler.events.rememberDefaultNotificationSound
import kotlinx.coroutines.launch

/** What each sound is called where a person has to choose one. */
val NotificationSound.displayName: String
    get() = when (this) {
        NotificationSound.Default -> "Default"
        NotificationSound.Silent -> "Silent"
        NotificationSound.Alarm -> "Alarm"
        NotificationSound.Chime -> "Chime"
        NotificationSound.Bell -> "Bell"
    }

/**
 * What [NotificationSound.Default] is called on a reminder, where it means the sound the app is
 * set to rather than the platform's own — which is what it means in the app-wide menu.
 */
private val NotificationSound.nameOnAReminder: String
    get() = if (this == NotificationSound.Default) "App default" else displayName

private val NotificationSound.icon: ImageVector
    get() = when (this) {
        NotificationSound.Silent -> Icons.Default.NotificationsOff
        NotificationSound.Alarm -> Icons.Default.NotificationsActive
        NotificationSound.Default, NotificationSound.Chime, NotificationSound.Bell -> Icons.Default.MusicNote
    }

/**
 * Chooses what a notification sounds like.
 *
 * An icon rather than a spelled-out field: it sits beside the time in a row that is already narrow,
 * and the chosen sound is named in the menu and read out by the content description, so nothing is
 * carried by the icon alone.
 */
@Composable
fun NotificationSoundPicker(
    sound: NotificationSound,
    onSoundSelected: (NotificationSound) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val appDefault by rememberDefaultNotificationSound()

    Box(modifier = modifier) {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = "Sound: ${sound.nameOnAReminder}" },
        ) {
            Icon(sound.icon, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            NotificationSound.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.nameOnAReminder) },
                    leadingIcon = { Icon(option.icon, contentDescription = null) },
                    onClick = {
                        // Heard as well as read, where the platform has a sound to lend: the names
                        // say what the app means, not what this device will make of it. What
                        // "App default" will make of it is whatever the app is set to, so that is
                        // what gets played rather than the platform's own.
                        val played = if (option == NotificationSound.Default) appDefault else option
                        scope.launch { previewNotificationSound(played) }
                        onSoundSelected(option)
                        open = false
                    },
                )
            }
        }
    }
}
