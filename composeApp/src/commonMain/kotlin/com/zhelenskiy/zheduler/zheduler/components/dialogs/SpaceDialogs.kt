package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.Space

@Composable
fun NewSpaceDialog(
    onDismiss: () -> Unit,
    onSpaceCreated: (name: String, idPrefix: String) -> Unit
) {
    var spaceName by remember { mutableStateOf("") }
    var idPrefix by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val idPrefixFocusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Space") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = spaceName,
                    onValueChange = {
                        spaceName = it
                        errorMessage = null
                    },
                    label = { Text("Space Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = spaceName.isBlank(),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = {
                            idPrefixFocusRequester.requestFocus()
                        }
                    )
                )

                OutlinedTextField(
                    value = idPrefix,
                    onValueChange = {
                        idPrefix = it.uppercase()
                        errorMessage = null
                    },
                    label = { Text("ID Prefix (e.g., BUG, FEAT)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(idPrefixFocusRequester),
                    singleLine = true,
                    isError = idPrefix.isEmpty() || !idPrefix.matches(Regex("^[A-Z]+$")),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val isValid = spaceName.isNotBlank() && idPrefix.matches(Regex("^[A-Z]+$"))
                            if (isValid) {
                                onSpaceCreated(spaceName, idPrefix)
                            }
                        }
                    ),
                    supportingText = {
                        Text(
                            text = if (idPrefix.isEmpty()) "ID prefix is required" else "Only uppercase English letters",
                            color = if (idPrefix.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            val isValid = spaceName.isNotBlank() && idPrefix.matches(Regex("^[A-Z]+$"))
            TextButton(
                onClick = {
                    if (isValid) {
                        onSpaceCreated(spaceName, idPrefix)
                    }
                },
                enabled = isValid
            ) {
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
fun EditSpaceDialog(
    space: Space,
    onDismiss: () -> Unit,
    onSpaceUpdated: (newName: String) -> Unit,
    allTags: Set<String> = emptySet(),
    onAddTag: ((String) -> Unit)? = null,
    onDeleteTag: ((String) -> Unit)? = null
) {
    var spaceName by remember { mutableStateOf(space.name) }
    var newTagText by remember { mutableStateOf("") }
    var tagToDelete by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Space") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.widthIn(max = 400.dp)
            ) {
                OutlinedTextField(
                    value = spaceName,
                    onValueChange = { spaceName = it },
                    label = { Text("Space Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = spaceName.isBlank()
                )

                // Display ID prefix as read-only
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
                            text = space.idPrefix,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "ID prefix cannot be changed after creation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Tag management section
                if (onAddTag != null && onDeleteTag != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "Tags",
                        style = MaterialTheme.typography.titleSmall
                    )

                    // Add new tag
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newTagText,
                            onValueChange = { newTagText = it },
                            label = { Text("New tag") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (newTagText.isNotBlank()) {
                                        onAddTag(newTagText.trim())
                                        newTagText = ""
                                    }
                                }
                            )
                        )
                        IconButton(
                            onClick = {
                                if (newTagText.isNotBlank()) {
                                    onAddTag(newTagText.trim())
                                    newTagText = ""
                                }
                            },
                            enabled = newTagText.isNotBlank()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add tag")
                        }
                    }

                    // List of existing tags
                    if (allTags.isEmpty()) {
                        Text(
                            text = "No tags defined",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(allTags.sorted()) { tag ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small
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
                                            onClick = { tagToDelete = tag },
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
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (spaceName.isNotBlank()) {
                        onSpaceUpdated(spaceName)
                    }
                },
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

    // Delete tag confirmation dialog
    tagToDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagToDelete = null },
            title = { Text("Delete Tag") },
            text = { Text("Are you sure you want to delete the tag \"$tag\"? This will only remove it from suggestions - existing tasks will keep this tag.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTag?.invoke(tag)
                        tagToDelete = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { tagToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
