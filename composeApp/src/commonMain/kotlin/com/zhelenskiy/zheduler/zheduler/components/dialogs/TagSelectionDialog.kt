package com.zhelenskiy.zheduler.zheduler.components.dialogs

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.zhelenskiy.zheduler.zheduler.components.common.pagingLoadStatus

@Composable
fun TagSelectionDialog(
    selectedTags: Set<String>,
    filteredTags: LazyPagingItems<String>,
    onFilterTags: (String, Set<String>) -> Unit,
    onDismiss: () -> Unit,
    onTagSelected: (String) -> Unit
) {
    var tagText by remember { mutableStateOf("") }

    LaunchedEffect(tagText, selectedTags) {
        onFilterTags(tagText, selectedTags)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Tag") },
        text = {
            TagSelectionContent(
                tagText = tagText,
                onTagTextChange = { tagText = it },
                filteredTags = filteredTags,
                onTagSelected = onTagSelected
            )
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

@Composable
private fun TagSelectionContent(
    tagText: String,
    onTagTextChange: (String) -> Unit,
    filteredTags: LazyPagingItems<String>,
    onTagSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TagSearchTextField(
            value = tagText,
            onValueChange = onTagTextChange,
            hasFilteredTags = filteredTags.itemCount > 0,
            onDone = {
                if (tagText.isNotBlank()) {
                    onTagSelected(tagText.trim())
                }
            }
        )

        FilteredTagsList(
            tags = filteredTags,
            onTagSelected = onTagSelected
        )
    }
}

@Composable
private fun TagSearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hasFilteredTags: Boolean,
    onDone: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(if (hasFilteredTags) "New tag or search" else "New tag")
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() })
    )
}

@Composable
private fun FilteredTagsList(
    tags: LazyPagingItems<String>,
    onTagSelected: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider()
        AnimatedContent(tags.itemCount > 0) {
            Text(
                text = if (it) "Select existing tag:" else "No existing tags found",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Tags stream in a page at a time, so the list animates per item rather than as a whole.
        LazyColumn(
            modifier = Modifier.sizeIn(maxHeight = 200.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(count = tags.itemCount, key = tags.itemKey { it }) { index ->
                val tag = tags[index]
                if (tag != null) {
                    TagListItem(
                        tag = tag,
                        modifier = Modifier.animateItem(),
                        onClick = { onTagSelected(tag) }
                    )
                }
            }
            pagingLoadStatus(tags)
        }
    }
}

@Composable
private fun TagListItem(
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
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
