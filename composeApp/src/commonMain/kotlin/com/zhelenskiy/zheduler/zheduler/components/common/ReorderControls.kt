package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Move up and move down, beside a list's drag handle.
 *
 * Dragging is the only way these lists could be rearranged, and dragging needs a pointer. Order is
 * not decoration here — it decides how groups nest and which sort rule wins — so without these a
 * keyboard-only user, or anyone working through a screen reader, could build a view mode and never
 * arrange it, short of deleting rows and adding them back in the order wanted.
 *
 * [what] names the row for a screen reader, which otherwise hears a column of identical arrows.
 */
@Composable
fun ReorderControls(
    what: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Move $what up",
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Move $what down",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
