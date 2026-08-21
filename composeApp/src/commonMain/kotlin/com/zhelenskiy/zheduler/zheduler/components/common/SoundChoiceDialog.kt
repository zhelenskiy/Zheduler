package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.events.ChosenSound
import com.zhelenskiy.zheduler.zheduler.events.CustomSound
import com.zhelenskiy.zheduler.zheduler.events.LocalNotificationPreferences
import com.zhelenskiy.zheduler.zheduler.events.NotificationSound
import com.zhelenskiy.zheduler.zheduler.events.previewNotificationSound
import com.zhelenskiy.zheduler.zheduler.events.rememberAppSounds
import com.zhelenskiy.zheduler.zheduler.events.soundExtensions
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch

/** What each of the shared sounds is called where a person has to choose one. */
val NotificationSound.displayName: String
    get() = when (this) {
        NotificationSound.Default -> "App default"
        NotificationSound.System -> "System sound"
        NotificationSound.Silent -> "Silent"
        NotificationSound.Alarm -> "Alarm"
        NotificationSound.Chime -> "Chime"
        NotificationSound.Bell -> "Bell"
    }

/** What a choice is called, whichever kind it is. */
val ChosenSound.displayName: String
    get() = custom?.label ?: builtin.displayName

/**
 * One icon for everything that makes a noise.
 *
 * Which noise is what the name is for; the icon says only whether there will be one, and a row of
 * different pictures for sounds that differ in no other way reads as a difference in kind.
 */
val ChosenSound.icon: ImageVector
    get() = if (custom == null && builtin == NotificationSound.Silent) {
        Icons.Default.NotificationsOff
    } else {
        Icons.Default.NotificationsActive
    }

/**
 * Chooses what something sounds like.
 *
 * A dialog rather than a menu because there is more here than a list of names: sounds the user has
 * added, a way to add another, and a way to be rid of one. Each is played as it is picked, and the
 * choice is not made until [onChosen] — so a sound can be tried without being taken. Adding and
 * removing are not choices about this one thing but about the library everything chooses from, and
 * they happen when they are asked for; Cancel does not put a removed sound back.
 *
 * @param deferring the option meaning "whatever the app is set to", where there is one to defer
 *   to. A reminder and a task's due time have one; the app's own settings are it, and pass null.
 */
@Composable
fun SoundChoiceDialog(
    title: String,
    sound: ChosenSound,
    deferring: ChosenSound?,
    onDismiss: () -> Unit,
    onChosen: (ChosenSound) -> Unit,
) {
    val preferences = LocalNotificationPreferences.current
    val appSounds by rememberAppSounds()
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(sound) }
    var failedToAdd by remember { mutableStateOf(false) }

    val addLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = soundExtensions)
    ) { file ->
        file ?: return@rememberFilePickerLauncher
        scope.launch {
            val added = preferences?.addCustomSound(file)
            failedToAdd = added == null
            if (added != null) {
                picked = ChosenSound.of(added)
                previewNotificationSound(picked)
            }
        }
    }

    fun choose(choice: ChosenSound) {
        picked = choice
        // Heard as it is picked. What "App default" will sound like is what the app is set to for
        // this kind of announcement — the sound the row is already named after, not another one.
        // Null only where there is nothing to defer to, in which case no row offers deferring.
        val played = if (choice.isDeferred) deferring else choice
        played?.let { scope.launch { previewNotificationSound(it) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                if (deferring != null) {
                    item {
                        SoundRow(
                            label = "App default",
                            detail = deferring.displayName,
                            sound = deferring,
                            selected = picked.isDeferred,
                            onClick = { choose(ChosenSound.Deferred) },
                        )
                    }
                }
                items(NotificationSound.appWide) { option ->
                    SoundRow(
                        label = option.displayName,
                        detail = null,
                        sound = ChosenSound.of(option),
                        selected = picked.custom == null && picked.builtin == option,
                        onClick = { choose(ChosenSound.of(option)) },
                    )
                }
                if (appSounds.library.isNotEmpty()) {
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                }
                items(appSounds.library, key = { it.id }) { custom ->
                    SoundRow(
                        label = custom.label,
                        detail = null,
                        sound = ChosenSound.of(custom),
                        selected = picked.custom == custom,
                        onClick = { choose(ChosenSound.of(custom)) },
                        onRemove = {
                            scope.launch {
                                // Onto whatever it was chosen alongside, which is the same place
                                // the preferences put anything else still asking for it. Falling
                                // back on deferral would leave nothing selected in a list that has
                                // no row for it.
                                if (picked.custom == custom) picked = ChosenSound(custom = null, builtin = picked.builtin)
                                preferences?.removeCustomSound(custom)
                            }
                        },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { failedToAdd = false; addLauncher.launch() },
                        enabled = preferences != null,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null)
                        Text("Add a sound…", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (failedToAdd) {
                    item {
                        Text(
                            "That file could not be added.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChosen(picked) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SoundRow(
    label: String,
    detail: String?,
    sound: ChosenSound,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            // Rounded before it is filled or pressed, so the tint and the ripple both stop at the
            // corners rather than running the width of the dialog.
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The colour that pairs with the fill under it, whatever seed the theme was built from —
        // and everything drawn on that fill takes it, not the text alone.
        val onFill =
            if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface

        RadioButton(selected = selected, onClick = onClick)
        Icon(
            sound.icon,
            contentDescription = null,
            tint = onFill,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = onFill)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = onFill.copy(alpha = 0.8f),
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove $label")
            }
        }
    }
}

/**
 * Opens [SoundChoiceDialog], showing what is chosen until it is.
 *
 * An icon rather than a spelled-out field: it sits beside the time in a row that is already narrow,
 * and the chosen sound is named in the dialog and read out by the content description.
 */
@Composable
fun NotificationSoundPicker(
    sound: ChosenSound,
    onSoundSelected: (ChosenSound) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Sound",
    deferring: ChosenSound? = null,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Drawn as what it will actually sound like: a reminder left at "App default" is silent
        // when the app is, and a bell on it would be the button saying otherwise.
        val shown = if (sound.isDeferred) deferring ?: sound else sound
        IconButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = "Sound: ${sound.displayName}" },
        ) {
            Icon(shown.icon, contentDescription = null)
        }
    }
    if (open) {
        SoundChoiceDialog(
            title = title,
            sound = sound,
            deferring = deferring,
            onDismiss = { open = false },
            onChosen = {
                onSoundSelected(it)
                open = false
            },
        )
    }
}
