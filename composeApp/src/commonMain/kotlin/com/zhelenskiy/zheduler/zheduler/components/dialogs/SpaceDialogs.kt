package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.sync.KnownServer
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupState
import com.zhelenskiy.zheduler.zheduler.sync.SignedInAccount

/**
 * @param remoteSetup where the user has got to with connecting this space to a server, or null in
 *   a build with no sync wired up — in which case the whole section is absent rather than shown
 *   disabled.
 * @param onSpaceCreated carries the account to upload to, or null when the space stays local.
 */
@Composable
fun NewSpaceDialog(
    onDismiss: () -> Unit,
    onSpaceCreated: (name: String, idPrefix: String, account: SignedInAccount?) -> Unit,
    remoteSetup: RemoteSetupState? = null,
    onRemoteSetupChange: (edit: (RemoteSetupState) -> RemoteSetupState) -> Unit = {},
    onCheckServer: (addressText: String) -> Unit = {},
    onAuthenticate: (username: String, password: String) -> Unit = { _, _ -> },
    knownServers: List<KnownServer> = emptyList(),
    onUseKnownServer: (KnownServer) -> Unit = {},
) {
    // Saved, not remembered: the space-list dialogs already reopen after an activity recreation,
    // so leaving these behind brought the dialog back with both fields blank — which reads as a
    // dialog the user never filled in rather than one that lost what they typed.
    var spaceName by rememberSaveable { mutableStateOf("") }
    var idPrefix by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val idPrefixFocusRequester = remember { FocusRequester() }

    // A server that was asked for but not signed in to blocks creation: creating the space anyway
    // would leave the user with a local space they had every reason to believe was backed up.
    val isValid = spaceName.isNotBlank() &&
        idPrefix.matches(Regex("^[A-Z]+$")) &&
        (remoteSetup?.canCreateSpace ?: true)
    val submit = { if (isValid) onSpaceCreated(spaceName, idPrefix, remoteSetup?.readyAccount) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Space") },
        text = {
            NewSpaceDialogContent(
                spaceName = spaceName,
                onSpaceNameChange = {
                    spaceName = it
                    errorMessage = null
                },
                idPrefix = idPrefix,
                onIdPrefixChange = {
                    idPrefix = it.uppercase()
                    errorMessage = null
                },
                errorMessage = errorMessage,
                idPrefixFocusRequester = idPrefixFocusRequester,
                onSubmit = submit,
                remoteSetup = remoteSetup,
                onRemoteSetupChange = onRemoteSetupChange,
                onCheckServer = onCheckServer,
                onAuthenticate = onAuthenticate,
                knownServers = knownServers,
                onUseKnownServer = onUseKnownServer,
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = isValid) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun NewSpaceDialogContent(
    spaceName: String,
    onSpaceNameChange: (String) -> Unit,
    idPrefix: String,
    onIdPrefixChange: (String) -> Unit,
    errorMessage: String?,
    idPrefixFocusRequester: FocusRequester,
    onSubmit: () -> Unit,
    remoteSetup: RemoteSetupState?,
    onRemoteSetupChange: (edit: (RemoteSetupState) -> RemoteSetupState) -> Unit,
    onCheckServer: (addressText: String) -> Unit,
    onAuthenticate: (username: String, password: String) -> Unit,
    knownServers: List<KnownServer>,
    onUseKnownServer: (KnownServer) -> Unit,
) {
    Column(
        // Scrollable: with the server section open this is taller than a phone dialog, and without
        // it the Create button ends up off screen with no way to reach it.
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SpaceNameTextField(
            spaceName = spaceName,
            onSpaceNameChange = onSpaceNameChange,
            onNext = { idPrefixFocusRequester.requestFocus() }
        )

        IdPrefixTextField(
            idPrefix = idPrefix,
            onIdPrefixChange = onIdPrefixChange,
            idPrefixFocusRequester = idPrefixFocusRequester,
            onDone = onSubmit
        )

        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        remoteSetup?.let { setup ->
            RemoteServerSection(
                state = setup,
                onEdit = onRemoteSetupChange,
                onCheckServer = onCheckServer,
                onAuthenticate = onAuthenticate,
                knownServers = knownServers,
                onUseKnownServer = onUseKnownServer,
            )
        }
    }
}

@Composable
private fun SpaceNameTextField(
    spaceName: String,
    onSpaceNameChange: (String) -> Unit,
    onNext: () -> Unit
) {
    OutlinedTextField(
        value = spaceName,
        onValueChange = onSpaceNameChange,
        label = { Text("Space Name") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = spaceName.isBlank(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onNext() })
    )
}

@Composable
private fun IdPrefixTextField(
    idPrefix: String,
    onIdPrefixChange: (String) -> Unit,
    idPrefixFocusRequester: FocusRequester,
    onDone: () -> Unit
) {
    OutlinedTextField(
        value = idPrefix,
        onValueChange = onIdPrefixChange,
        label = { Text("ID Prefix (e.g., WORK, HOME)") },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(idPrefixFocusRequester),
        singleLine = true,
        isError = idPrefix.isEmpty() || !idPrefix.matches(Regex("^[A-Z]+$")),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        supportingText = {
            Text(
                text = if (idPrefix.isEmpty()) "ID prefix is required" else "Only uppercase English letters",
                color = if (idPrefix.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
fun EditSpaceDialog(
    space: Space,
    onDismiss: () -> Unit,
    onSpaceUpdated: (newName: String) -> Unit,
    allTags: Set<String> = emptySet(),
    onAddTag: ((String) -> Unit)? = null,
    onDeleteTag: ((String) -> Unit)? = null
) {
    // Keyed on the space, and saved: see NewSpaceDialog. The key keeps a name typed for one space
    // from being handed to the next one opened, the way saved state otherwise follows the position
    // in the composition rather than what is being edited.
    var spaceName by rememberSaveable(key = "editSpace:name:${space.id}") { mutableStateOf(space.name) }
    var newTagText by rememberSaveable(key = "editSpace:newTag:${space.id}") { mutableStateOf("") }
    var tagToDelete by rememberSaveable(key = "editSpace:tagToDelete:${space.id}") {
        mutableStateOf<String?>(null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Space") },
        text = {
            EditSpaceDialogContent(
                spaceName = spaceName,
                onSpaceNameChange = { spaceName = it },
                idPrefix = space.idPrefix,
                newTagText = newTagText,
                onNewTagTextChange = { newTagText = it },
                allTags = allTags,
                onAddTag = onAddTag,
                onDeleteTag = { tagToDelete = it }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (spaceName.isNotBlank()) onSpaceUpdated(spaceName) },
                enabled = spaceName.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    tagToDelete?.let { tag ->
        DeleteTagConfirmationDialog(
            tag = tag,
            onConfirm = {
                onDeleteTag?.invoke(tag)
                tagToDelete = null
            },
            onDismiss = { tagToDelete = null }
        )
    }
}

@Composable
private fun EditSpaceDialogContent(
    spaceName: String,
    onSpaceNameChange: (String) -> Unit,
    idPrefix: String,
    newTagText: String,
    onNewTagTextChange: (String) -> Unit,
    allTags: Set<String>,
    onAddTag: ((String) -> Unit)?,
    onDeleteTag: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = 400.dp)
    ) {
        OutlinedTextField(
            value = spaceName,
            onValueChange = onSpaceNameChange,
            label = { Text("Space Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = spaceName.isBlank()
        )

        IdPrefixDisplay(idPrefix = idPrefix)

        Text(
            text = "ID prefix cannot be changed after creation",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (onAddTag != null) {
            TagManagementSection(
                newTagText = newTagText,
                onNewTagTextChange = onNewTagTextChange,
                allTags = allTags,
                onAddTag = onAddTag,
                onDeleteTag = onDeleteTag
            )
        }
    }
}

@Composable
private fun IdPrefixDisplay(idPrefix: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ID Prefix:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = idPrefix,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TagManagementSection(
    newTagText: String,
    onNewTagTextChange: (String) -> Unit,
    allTags: Set<String>,
    onAddTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()

        Text(
            text = "Tags",
            style = MaterialTheme.typography.titleSmall
        )

        AddTagInput(
            newTagText = newTagText,
            onNewTagTextChange = onNewTagTextChange,
            onAddTag = onAddTag
        )

        TagList(
            allTags = allTags,
            onDeleteTag = onDeleteTag
        )
    }
}

@Composable
private fun AddTagInput(
    newTagText: String,
    onNewTagTextChange: (String) -> Unit,
    onAddTag: (String) -> Unit
) {
    val submitTag = {
        if (newTagText.isNotBlank()) {
            onAddTag(newTagText.trim())
            onNewTagTextChange("")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = newTagText,
            onValueChange = onNewTagTextChange,
            label = { Text("New tag") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitTag() })
        )
        IconButton(
            onClick = submitTag,
            enabled = newTagText.isNotBlank()
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add tag")
        }
    }
}

@Composable
private fun TagList(
    allTags: Set<String>,
    onDeleteTag: (String) -> Unit
) {
    Box {
        AnimatedVisibility(
            visible = allTags.isEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                text = "No tags defined",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedContent(allTags, transitionSpec = { EnterTransition.None togetherWith ExitTransition.None }) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Keyed, or the item animation follows positions and a deletion slides the wrong rows.
                items(it.sorted(), key = { tag -> tag }) { tag ->
                    TagListItem(
                        tag = tag,
                        modifier = Modifier.animateItem(),
                        onDeleteTag = onDeleteTag
                    )
                }
            }
        }
    }
}


@Composable
private fun TagListItem(
    tag: String,
    modifier: Modifier = Modifier,
    onDeleteTag: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = tag,
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(
                onClick = { onDeleteTag(tag) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete tag",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun DeleteTagConfirmationDialog(
    tag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val confirmationMessage = """
        |Are you sure you want to delete the tag "$tag"?
        |This will only remove it from suggestions - existing tasks will keep this tag.
        |""".trimMargin()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Tag") },
        text = { Text(confirmationMessage) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
