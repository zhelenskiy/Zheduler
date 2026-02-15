package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun TagSelectionDialog(
    existingTags: Set<String>,
    selectedTags: Set<String>,
    onDismiss: () -> Unit,
    onTagSelected: (String) -> Unit
) {
    var tagText by remember { mutableStateOf("") }

    val availableTags = existingTags.filter { it !in selectedTags }
    val filteredTags = remember(availableTags, tagText) {
        if (tagText.isBlank()) availableTags.sorted()
        else availableTags.filter { it.contains(tagText, ignoreCase = true) }.sorted()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Tag") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it },
                    label = { Text(if (availableTags.isNotEmpty()) "New tag or search" else "New tag") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (tagText.isNotEmpty()) {
                            IconButton(onClick = { tagText = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (tagText.isNotBlank()) {
                                onTagSelected(tagText.trim())
                            }
                        }
                    )
                )

                Column(
                    modifier = Modifier.animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (filteredTags.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            text = "Select existing tag:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        filteredTags.forEach { tag ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onTagSelected(tag) },
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.LocalOffer,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(tag)
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
                    if (tagText.isNotBlank()) {
                        onTagSelected(tagText.trim())
                    }
                },
                enabled = tagText.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
