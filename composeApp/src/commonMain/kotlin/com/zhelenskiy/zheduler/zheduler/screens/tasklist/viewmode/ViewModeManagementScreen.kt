@file:OptIn(ExperimentalMaterial3Api::class)

package com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.ColorSettings
import com.zhelenskiy.zheduler.zheduler.GroupableField
import com.zhelenskiy.zheduler.zheduler.GroupDefinition
import com.zhelenskiy.zheduler.zheduler.OrderDirection
import com.zhelenskiy.zheduler.zheduler.ViewMode
import com.zhelenskiy.zheduler.zheduler.components.common.appTopAppBarColors
import com.zhelenskiy.zheduler.zheduler.components.dialogs.DeleteConfirmationDialog
import com.zhelenskiy.zheduler.zheduler.components.common.SettingsButton
import com.zhelenskiy.zheduler.zheduler.theme.ThemeMode
import com.zhelenskiy.zheduler.zheduler.viewmodels.ViewModeContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.ViewModeIntent
import pro.respawn.flowmvi.compose.dsl.subscribe
import com.zhelenskiy.zheduler.zheduler.sync.LocalSpaceEditing
import com.zhelenskiy.zheduler.zheduler.sync.CloudSpaceBanner

/**
 * A screen for selecting and managing view modes.
 * Combines selection, creation, editing, copying, and deletion.
 */
@Composable
fun ViewModeManagementScreen(
    container: ViewModeContainer,
    onCreateNew: () -> Unit,
    onEdit: (ViewMode) -> Unit,
    onCopy: (ViewMode) -> Unit,
    onBack: () -> Unit,
    onNavigateToSpaceList: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useDynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit,
    colorSettings: ColorSettings,
    onColorSettingsChange: (ColorSettings) -> Unit
) {
    val state by container.store.subscribe()
    val activeViewModeId = state.activeViewMode?.id ?: "priority"
    var viewModeToDelete by remember { mutableStateOf<ViewMode?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("View Modes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSpaceList) {
                        Icon(Icons.Default.Home, contentDescription = "Spaces")
                    }
                    SettingsButton(
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        useDynamicColors = useDynamicColors,
                        onDynamicColorsChange = onDynamicColorsChange,
                        colorSettings = colorSettings,
                        onColorSettingsChange = onColorSettingsChange
                    )
                },
                colors = appTopAppBarColors(),
            )
        },
        floatingActionButton = {
            // View modes belong to the space, so they travel to the server with it. One written
            // while the server is out of reach would be taken back at the next refresh.
            if (LocalSpaceEditing.current.isEditable) {
                FloatingActionButton(onClick = onCreateNew) {
                    Icon(Icons.Default.Add, contentDescription = "Create new view mode")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
        // Said here too. These are a space's own settings and go up with it, so the buttons vanish
        // when the server is out of reach — and a screen of missing buttons with nothing to
        // explain them reads as the app being broken.
        CloudSpaceBanner()
        AnimatedContent(
            targetState = state.viewModes,
            transitionSpec = {
                EnterTransition.None togetherWith ExitTransition.None
            },
            label = "view_modes_list"
        ) { targetViewModes ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
            ) {
                items(targetViewModes, key = { it.id }) { viewMode ->
                    ViewModeCard(
                        viewMode = viewMode,
                        isActive = viewMode.id == activeViewModeId,
                        onSelect = { container.store.intent(ViewModeIntent.SetActiveViewMode(viewMode.id)) },
                        onEdit = { onEdit(viewMode) },
                        onCopy = { onCopy(viewMode) },
                        onDelete = { viewModeToDelete = viewMode },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
        }
    }

    viewModeToDelete?.let { viewMode ->
        DeleteConfirmationDialog(
            title = "Delete View Mode",
            message = "Are you sure you want to delete \"${viewMode.name}\"?",
            onConfirm = {
                container.store.intent(ViewModeIntent.DeleteViewMode(viewMode.id))
                viewModeToDelete = null
            },
            onDismiss = { viewModeToDelete = null }
        )
    }
}

@Composable
private fun ViewModeCard(
    viewMode: ViewMode,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = viewMode.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (viewMode.isBuiltIn) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                text = "Built-in",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (LocalSpaceEditing.current.isEditable) {
                    Row {
                        IconButton(onClick = onCopy) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        if (!viewMode.isBuiltIn) {
                            IconButton(onClick = onEdit) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = onDelete) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // Grouping section
            Text(
                text = "Grouping",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            if (viewMode.groupingLevels.isEmpty()) {
                Text(
                    text = "No grouping",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                viewMode.groupingLevels.forEachIndexed { index, level ->
                    Column(modifier = Modifier.padding(start = (index * 12).dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (index > 0) {
                                Text(
                                    text = "→ ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = level.field.displayName,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (level.groups.isNotEmpty()) {
                            level.groups.forEach { group ->
                                Column(modifier = Modifier.padding(start = 16.dp, top = 2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "• ${group.label}: ",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = getGroupDescription(level.field, group),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (group.orderingRules.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.padding(start = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Order: ",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = group.orderingRules.joinToString(", ") { rule ->
                                                    val dir = if (rule.direction == OrderDirection.Ascending) "↑" else "↓"
                                                    "${rule.field.displayName} $dir"
                                                },
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Default order section
            Text(
                text = "Default Order",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            if (viewMode.defaultOrderingRules.isEmpty()) {
                Text(
                    text = "No ordering rules",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                viewMode.defaultOrderingRules.forEach { rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val directionIcon = if (rule.direction == OrderDirection.Ascending) "↑" else "↓"
                        val nullPosText = if (rule.nullPosition == com.zhelenskiy.zheduler.zheduler.NullPosition.First) "null first" else "null last"
                        Text(
                            text = "${rule.field.displayName} $directionIcon ($nullPosText)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * Returns a description string for a group based on its field type.
 */
private fun getGroupDescription(field: GroupableField, group: GroupDefinition): String {
    return when (field) {
        GroupableField.Priority -> {
            val parts = mutableListOf<String>()
            if (group.priorityMin != null || group.priorityMax != null) {
                parts.add(formatRange(group.priorityMin?.toString(), group.priorityMax?.toString()))
            }
            if (group.includeNoPriority) {
                parts.add("no priority")
            }
            parts.joinToString(", ").ifEmpty { group.values.joinToString(", ") }
        }
        GroupableField.EstimatedTime -> {
            val parts = mutableListOf<String>()
            if (group.estimatedTimeMin != null || group.estimatedTimeMax != null) {
                parts.add(formatRange(group.estimatedTimeMin?.toBriefString(), group.estimatedTimeMax?.toBriefString()))
            }
            if (group.includeNoEstimatedTime) {
                parts.add("no estimate")
            }
            parts.joinToString(", ").ifEmpty { group.values.joinToString(", ") }
        }
        GroupableField.DueDate -> {
            val parts = mutableListOf<String>()
            if (group.dueDateMinDays != null || group.dueDateMaxDays != null) {
                parts.add(formatRange(group.dueDateMinDays?.let { "${it}d" }, group.dueDateMaxDays?.let { "${it}d" }))
            }
            if (group.includeNoDueDate) {
                parts.add("no due date")
            }
            parts.joinToString(", ").ifEmpty { group.values.joinToString(", ") }
        }
        else -> group.values.joinToString(", ")
    }
}

/**
 * Formats a range with proper symbols: "min–max", "≥ min", "≤ max", or just the value if min == max.
 */
private fun formatRange(min: String?, max: String?): String {
    return when {
        min != null && max != null && min == max -> min
        min != null && max != null -> "$min–$max"
        min != null -> "≥ $min"
        max != null -> "≤ $max"
        else -> ""
    }
}
