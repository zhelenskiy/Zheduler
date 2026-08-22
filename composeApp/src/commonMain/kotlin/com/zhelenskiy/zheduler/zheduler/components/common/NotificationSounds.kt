package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.events.ChosenSound
import com.zhelenskiy.zheduler.zheduler.events.LocalNotificationPreferences
import com.zhelenskiy.zheduler.zheduler.events.NotificationSound
import com.zhelenskiy.zheduler.zheduler.events.SoundRole
import com.zhelenskiy.zheduler.zheduler.events.rememberAppSounds
import kotlinx.coroutines.launch

/** Whether this platform has notification sounds to set at all. */
@Composable
fun notificationSoundsAvailable(): Boolean = LocalNotificationPreferences.current != null

/**
 * What the app sounds like, opened from the settings list rather than laid out inside it.
 *
 * A whole page of its own: every role the app has, each with a sound that itself opens a chooser.
 * Spread flat under the other settings it buried them, and the list a user opens the cog to read
 * became something to scroll past.
 */
@Composable
fun NotificationSoundsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notification sounds") },
        text = { NotificationSoundsSection() },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * The rows themselves, so that what the dialog holds is one thing and how it is framed another.
 *
 * Nothing at all where the platform has no notification preferences to set — a browser tab has
 * none — because a list of sounds that cannot be chosen is worse than no list.
 */
@Composable
private fun NotificationSoundsSection() {
    val preferences = LocalNotificationPreferences.current ?: return
    val settings by rememberAppSounds()
    val scope = rememberCoroutineScope()
    // Saved, like the page it sits on: auditioning sounds is minutes of work, and a recreation
    // used to close the chooser and lose which role was being changed.
    var editing by rememberSaveable { mutableStateOf<SoundRole?>(null) }

    Column {
        Text(
            "A single task or reminder can pick its own sound in the task's form; these are the " +
                "sounds used for everything that has not.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SoundRole.entries.forEach { role ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    // Rounded before it is pressed, so the ripple stops at the corners
                    // rather than running the width of the dialog.
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "Change sound") { editing = role }
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    settings.forRole(role).icon,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(role.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        role.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    settings.forRole(role).displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    // A sound of the user's own is named after their file, and holds
                    // half the row at most: an unbounded one would push what it is the
                    // sound *for* down to nothing. Aligned rather than shrunk to fit,
                    // because a weighted child keeps its share whether it fills it or
                    // not, and a short name would otherwise sit adrift of the edge.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
            }
        }
    }

    editing?.let { role ->
        SoundChoiceDialog(
            title = role.displayName,
            sound = settings.forRole(role),
            // Nothing to defer to: these are what everything else defers to.
            deferring = null,
            onDismiss = { editing = null },
            onChosen = { chosen ->
                scope.launch { preferences.setSound(role, chosen.settled()) }
                editing = null
            },
        )
    }
}

/**
 * The app's own sounds cannot defer to themselves, so a choice that says so is read as the sound
 * the app has when nobody has chosen one.
 */
private fun ChosenSound.settled(): ChosenSound =
    if (isDeferred) ChosenSound(NotificationSound.Unconfigured) else this
