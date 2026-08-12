package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.components.markdown.MarkdownColumnAlignment
import com.zhelenskiy.zheduler.zheduler.components.markdown.MarkdownSegment
import com.zhelenskiy.zheduler.zheduler.components.markdown.escapeTableCell
import com.zhelenskiy.zheduler.zheduler.components.markdown.parseMarkdownTable
import com.zhelenskiy.zheduler.zheduler.components.markdown.toMarkdown
import com.zhelenskiy.zheduler.zheduler.components.markdown.unescapeTableCell
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockId

/** Which way focus should leave the grid when an arrow runs off its edge. */
internal enum class TableExit { Before, After }

private val CellMinWidth = 120.dp
private val RowGutterWidth = 28.dp
private val GridRowHeight = 32.dp
private val AddStripThickness = 16.dp

/**
 * Editable grid for a preserved Markdown table.
 *
 * The block still stores GFM — the grid parses it on the way in and re-renders it on every
 * edit — so a table written by hand, by another client, or in the Markdown editor opens here
 * unchanged.
 */
@Composable
internal fun TableBlockEditor(
    block: Block,
    table: MarkdownSegment.Table,
    onTableChange: (MarkdownSegment.Table) -> Unit,
    onDelete: () -> Unit,
    onExit: (TableExit) -> Unit,
    onCellFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val rowCount = table.rows.size + 1
    val columnCount = table.header.size
    // Rebuilt when the grid changes shape so a removed cell's requester is not reused.
    val focusRequesters = remember(block.id, rowCount, columnCount) {
        List(rowCount) { List(columnCount) { FocusRequester() } }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        TableToolbar(onDelete = onDelete)

        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            ColumnDeleteRow(
                columnCount = columnCount,
                onDeleteColumn = { column -> onTableChange(table.withoutColumn(column)) },
            )
            // Intrinsic height lets the add-column strip run the full height of the grid
            // beside it, the way a spreadsheet's edge control does.
            Row(modifier = Modifier.padding(start = 8.dp).height(IntrinsicSize.Min)) {
                // Intrinsic width ties the add-row rail below to the cells' own width rather
                // than to whatever space the block happens to be given.
                Column(modifier = Modifier.width(IntrinsicSize.Min)) {
                    TableGridRow(
                        cells = table.header,
                        alignments = table.alignments,
                        isHeader = true,
                        rowIndex = 0,
                        focusRequesters = focusRequesters,
                        onCellChange = { column, value ->
                            onTableChange(table.withHeaderCell(column, value))
                        },
                        onExit = onExit,
                        onCellFocusChange = onCellFocusChange,
                    )
                    table.rows.forEachIndexed { rowIndex, cells ->
                        TableGridRow(
                            cells = cells,
                            alignments = table.alignments,
                            isHeader = false,
                            rowIndex = rowIndex + 1,
                            focusRequesters = focusRequesters,
                            onCellChange = { column, value ->
                                onTableChange(table.withBodyCell(rowIndex, column, value))
                            },
                            onExit = onExit,
                            onCellFocusChange = onCellFocusChange,
                        )
                    }
                    AddStrip(
                        contentDescription = "Add row",
                        modifier = Modifier.fillMaxWidth().height(AddStripThickness),
                        onClick = { onTableChange(table.withRowAppended()) },
                    )
                }
                // Grows the grid, so it sits against the grid itself rather than out beyond
                // the row controls.
                AddStrip(
                    contentDescription = "Add column",
                    modifier = Modifier.fillMaxHeight().width(AddStripThickness),
                    onClick = { onTableChange(table.withColumnAppended()) },
                )
                RowDeleteGutter(
                    rowCount = table.rows.size,
                    onDeleteRow = { row -> onTableChange(table.withoutRow(row)) },
                )
            }
        }
    }
}

/**
 * The thin `+` rail along the grid's right and bottom edges. Sitting where the new column
 * or row will appear says what it does without a label.
 */
@Composable
private fun AddStrip(contentDescription: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .padding(1.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TableToolbar(onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.TableChart,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Table",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove table",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColumnDeleteRow(columnCount: Int, onDeleteColumn: (Int) -> Unit) {
    Row(modifier = Modifier.padding(start = 8.dp)) {
        repeat(columnCount) { column ->
            Box(modifier = Modifier.width(CellMinWidth), contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = { onDeleteColumn(column) },
                    enabled = columnCount > 1,
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete column ${column + 1}",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableGridRow(
    cells: List<String>,
    alignments: List<MarkdownColumnAlignment>,
    isHeader: Boolean,
    rowIndex: Int,
    focusRequesters: List<List<FocusRequester>>,
    onCellChange: (column: Int, value: String) -> Unit,
    onExit: (TableExit) -> Unit,
    onCellFocusChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.height(GridRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { column, cell ->
            TableCell(
                value = unescapeTableCell(cell),
                isHeader = isHeader,
                alignment = alignments.getOrElse(column) { MarkdownColumnAlignment.Start },
                focusRequester = focusRequesters.getOrNull(rowIndex)?.getOrNull(column),
                onValueChange = { onCellChange(column, escapeTableCell(it)) },
                onFocusChange = onCellFocusChange,
                onMoveVertically = { delta ->
                    val target = focusRequesters.getOrNull(rowIndex + delta)?.getOrNull(column)
                    if (target != null) {
                        target.requestFocus()
                    } else {
                        // Off the top or bottom edge: leave the table entirely, which is the
                        // only way to reach the blocks around it with the keyboard.
                        onExit(if (delta < 0) TableExit.Before else TableExit.After)
                    }
                },
            )
        }
    }
}

/**
 * Row controls in their own column so the add-column rail can sit between them and the
 * grid. Fixed row heights are what keep each bin beside its row.
 */
@Composable
private fun RowDeleteGutter(rowCount: Int, onDeleteRow: (Int) -> Unit) {
    Column {
        Box(modifier = Modifier.height(GridRowHeight).width(RowGutterWidth))
        repeat(rowCount) { row ->
            Box(
                modifier = Modifier.height(GridRowHeight).width(RowGutterWidth),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { onDeleteRow(row) }, modifier = Modifier.size(20.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete row ${row + 1}",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    value: String,
    isHeader: Boolean,
    alignment: MarkdownColumnAlignment,
    focusRequester: FocusRequester?,
    onValueChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onMoveVertically: (Int) -> Unit,
) {
    val background = if (isHeader) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }
    // Set on the text style, not the Box: the field fills the cell, so container alignment
    // would leave the text itself hugging the left edge regardless of the column's Markdown
    // alignment.
    val textAlign = when (alignment) {
        MarkdownColumnAlignment.Start -> TextAlign.Start
        MarkdownColumnAlignment.Center -> TextAlign.Center
        MarkdownColumnAlignment.End -> TextAlign.End
    }

    Box(
        modifier = Modifier
            .width(CellMinWidth)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                textAlign = textAlign,
            ),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { onFocusChange(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> { onMoveVertically(-1); true }
                        Key.DirectionDown -> { onMoveVertically(1); true }
                        else -> false
                    }
                },
        )
    }
}

// --- Grid edits. All return a new table; the caller re-renders it to Markdown. ---

private fun MarkdownSegment.Table.withHeaderCell(column: Int, value: String) =
    copy(header = header.replacingAt(column, value))

private fun MarkdownSegment.Table.withBodyCell(row: Int, column: Int, value: String) =
    copy(rows = rows.replacingAt(row, rows[row].replacingAt(column, value)))

private fun MarkdownSegment.Table.withRowAppended() =
    copy(rows = rows + listOf(List(header.size) { "" }))

private fun MarkdownSegment.Table.withColumnAppended() = copy(
    header = header + "",
    alignments = alignments + MarkdownColumnAlignment.Start,
    rows = rows.map { it + "" },
)

private fun MarkdownSegment.Table.withoutRow(row: Int) =
    copy(rows = rows.filterIndexed { index, _ -> index != row })

private fun MarkdownSegment.Table.withoutColumn(column: Int): MarkdownSegment.Table {
    if (header.size <= 1) return this
    return copy(
        header = header.removingAt(column),
        alignments = alignments.removingAt(column),
        rows = rows.map { it.removingAt(column) },
    )
}

private fun <T> List<T>.replacingAt(index: Int, value: T): List<T> =
    mapIndexed { current, existing -> if (current == index) value else existing }

private fun <T> List<T>.removingAt(index: Int): List<T> =
    filterIndexed { current, _ -> current != index }

/** The table a preserved block holds, or null when its source is not a single clean table. */
internal fun tableOf(rawMarkdown: String): MarkdownSegment.Table? = parseMarkdownTable(rawMarkdown)

/** Markdown for a grid edit, ready to store back on the block. */
internal fun MarkdownSegment.Table.toBlockMarkdown(): String = toMarkdown()
