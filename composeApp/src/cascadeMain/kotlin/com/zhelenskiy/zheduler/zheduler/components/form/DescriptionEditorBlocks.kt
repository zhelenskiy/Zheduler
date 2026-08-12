package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockContent
import io.github.linreal.cascade.editor.core.BlockId
import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.core.UnknownBlockType
import io.github.linreal.cascade.editor.registry.BlockCallbacks
import io.github.linreal.cascade.editor.registry.BlockRegistry
import io.github.linreal.cascade.editor.registry.BlockRenderer
import io.github.linreal.cascade.editor.slash.SlashCommandAction
import io.github.linreal.cascade.editor.slash.SlashCommandId
import io.github.linreal.cascade.editor.slash.SlashCommandRegistry
import io.github.linreal.cascade.editor.slash.SlashCommandResult
import io.github.linreal.cascade.editor.ui.createEditorRegistry

/** Type IDs the Markdown codec uses for syntax it preserves verbatim instead of decoding. */
private const val PreservedTypeId = "md.preserved"
private const val PreservedHtmlTypeId = "md.preservedHtml"
private const val DividerTypeId = "divider"

private const val RawMarkdownKey = "rawMarkdown"
private const val KindKey = "kind"

/** Shape the codec gives an unknown block; matched so a hand-built block behaves the same. */
private fun rawTypeJson(typeId: String) = "{\"typeId\":\"$typeId\"}"

private val StarterTable = """
    | Column | Column |
    | --- | --- |
    |  |  |
""".trimIndent()

/**
 * Registry for the description editor.
 *
 * Two things the stock registry does not give us:
 * - preserved blocks (tables, images, tagged code fences) render as a muted "unsupported
 *   block" box, hiding the user's own content behind a label;
 * - blocks that hold no text — a divider, and those same preserved blocks — have no way to
 *   be removed at all. Backspace at the start of the following block only merges when the
 *   previous block supports text, and the built-in click/long-click callbacks are no-ops,
 *   so without an explicit affordance a divider is permanent.
 *
 * [deleteBlock] is supplied by the editor because removing the last remaining block would
 * otherwise leave a document with nothing to type into.
 */
internal fun createDescriptionEditorRegistry(
    deleteBlock: (BlockId) -> Unit,
    updateBlock: (BlockId, BlockContent) -> Unit,
    exitBlock: (BlockId, TableExit) -> Unit,
    onCellFocusChange: (Boolean) -> Unit,
): BlockRegistry = createEditorRegistry().apply {
    val preserved =
        PreservedMarkdownBlockRenderer(deleteBlock, updateBlock, exitBlock, onCellFocusChange)
    registerRenderer(PreservedTypeId, preserved)
    registerRenderer(PreservedHtmlTypeId, preserved)
    registerRenderer(DividerTypeId, DeletableDividerRenderer(deleteBlock))
}

/** Adds "Table" to the slash menu, since the codec cannot produce one from typed Markdown. */
internal fun createDescriptionSlashCommands(): SlashCommandRegistry =
    SlashCommandRegistry().apply {
        register(
            SlashCommandAction(
                id = SlashCommandId("zheduler.table"),
                title = "Table",
                description = "Insert a Markdown table",
                keywords = listOf("table", "grid", "row", "column"),
                onExecute = {
                    editor.replaceAnchorBlock(
                        block = newTableBlock(),
                        preserveAnchorId = false,
                        requestFocus = false,
                    )
                    SlashCommandResult.Done
                },
            ),
        )
    }

/**
 * A table as the Markdown codec itself would represent it: an opaque block holding the
 * source. Built this way it survives save and reload unchanged instead of being a shape only
 * this session understands.
 */
internal fun newTableBlock(rawMarkdown: String = StarterTable): Block = Block(
    id = BlockId.generate(),
    type = UnknownBlockType(PreservedTypeId, rawTypeJson(PreservedTypeId)),
    content = BlockContent.Custom(
        typeId = PreservedTypeId,
        data = mapOf(KindKey to "pipeTable", RawMarkdownKey to rawMarkdown),
    ),
)

private class PreservedMarkdownBlockRenderer(
    private val deleteBlock: (BlockId) -> Unit,
    private val updateBlock: (BlockId, BlockContent) -> Unit,
    private val exitBlock: (BlockId, TableExit) -> Unit,
    private val onCellFocusChange: (Boolean) -> Unit,
) : BlockRenderer<UnknownBlockType> {

    @Composable
    override fun Render(
        block: Block,
        isSelected: Boolean,
        isFocused: Boolean,
        modifier: Modifier,
        callbacks: BlockCallbacks,
    ) {
        val content = block.content as? BlockContent.Custom
        val kind = content?.data?.get(KindKey) as? String
        val raw = content?.data?.get(RawMarkdownKey) as? String ?: ""

        // A table gets a real grid; everything else the codec preserves (images, footnotes,
        // tagged fences) stays a source box, since there is no better shape for it.
        val table = remember(raw) { tableOf(raw) }
        if (table != null) {
            TableBlockEditor(
                block = block,
                table = table,
                onTableChange = { updated ->
                    updateRawMarkdown(block, content, updated.toBlockMarkdown(), updateBlock)
                },
                onDelete = { deleteBlock(block.id) },
                onExit = { exit -> exitBlock(block.id, exit) },
                onCellFocusChange = onCellFocusChange,
                modifier = modifier,
            )
            return
        }

        // Keyed by block id: our own dispatches keep the id, so the caret survives typing,
        // while a document reload mints new ids and resets the text.
        var text by remember(block.id) { mutableStateOf(raw) }

        val shape = RoundedCornerShape(4.dp)
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preservedKindTag(kind, raw),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                DeleteBlockButton(
                    contentDescription = "Remove ${preservedKindName(kind)}",
                    onClick = { deleteBlock(block.id) },
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { updated ->
                    text = updated
                    updateRawMarkdown(block, content, updated, updateBlock)
                },
                // Backspace in an emptied source box removes the block, so a code fence or
                // image can be cleared away entirely from the keyboard.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 6.dp)
                    .onPreviewKeyEvent { event ->
                        val isBackspace = event.type == KeyEventType.KeyDown &&
                            event.key == Key.Backspace
                        if (isBackspace && text.isEmpty()) {
                            deleteBlock(block.id)
                            true
                        } else {
                            false
                        }
                    },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Stores edited source back on the block; `kind` is kept so the codec still recognises it.
 *
 * Routed through the editor's history-aware update rather than `callbacks.dispatch`, which
 * documents itself as bypassing history — edits made that way could never be undone.
 */
private fun updateRawMarkdown(
    block: Block,
    content: BlockContent.Custom?,
    rawMarkdown: String,
    updateBlock: (BlockId, BlockContent) -> Unit,
) {
    updateBlock(
        block.id,
        BlockContent.Custom(
            typeId = content?.typeId ?: PreservedTypeId,
            data = (content?.data ?: emptyMap()) + (RawMarkdownKey to rawMarkdown),
        ),
    )
}

private class DeletableDividerRenderer(
    private val deleteBlock: (BlockId) -> Unit,
) : BlockRenderer<BlockType.Divider> {

    @Composable
    override fun Render(
        block: Block,
        isSelected: Boolean,
        isFocused: Boolean,
        modifier: Modifier,
        callbacks: BlockCallbacks,
    ) {
        Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            DeleteBlockButton(
                contentDescription = "Remove divider",
                onClick = { deleteBlock(block.id) },
            )
        }
    }
}

@Composable
private fun DeleteBlockButton(contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(24.dp)) {
        Icon(
            Icons.Default.Delete,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Short marker for the source box, in place of a sentence describing the construct.
 *
 * A fenced code block shows `<>` with its language, which is both shorter and closer to what
 * the source actually is; everything else gets a one-word noun.
 */
internal fun preservedKindTag(kind: String?, raw: String): String = when (kind) {
    "fencedCode" -> fenceLanguage(raw)?.takeIf { it.isNotBlank() } ?: "<>"
    "html" -> "</>"
    else -> preservedKindName(kind).replaceFirstChar { it.uppercase() }
}

/** Language on the opening fence, if the author wrote one. */
private fun fenceLanguage(raw: String): String? = raw
    .lineSequence()
    .firstOrNull()
    ?.trimStart('`', '~', ' ')
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

/** Spoken name for a codec preservation kind; unknown kinds keep the codec's own word. */
internal fun preservedKindName(kind: String?): String = when (kind) {
    "pipeTable" -> "table"
    "blockImage", "inlineImage" -> "image"
    "fencedCode" -> "code block"
    "blockquote" -> "quote"
    "list", "listItem", "orderedList" -> "list"
    "html" -> "HTML"
    "frontMatter" -> "front matter"
    "footnoteDefinition" -> "footnote"
    "mathBlock" -> "maths block"
    "linkReferenceDefinition" -> "link reference"
    null -> "Markdown"
    else -> kind
}
