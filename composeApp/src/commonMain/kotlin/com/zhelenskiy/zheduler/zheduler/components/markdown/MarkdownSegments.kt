package com.zhelenskiy.zheduler.zheduler.components.markdown

/**
 * A top-level run of Markdown that needs its own renderer.
 *
 * The rich-text library used for prose supports neither fenced code nor tables, so those
 * two constructs are split out and drawn directly. Splitting also keeps code verbatim:
 * task-reference linkification runs per [Prose] segment, so a `ZH-12` inside a code block
 * is no longer rewritten into a link.
 */
internal sealed interface MarkdownSegment {
    /** Anything the rich-text renderer handles: paragraphs, headings, lists, quotes. */
    data class Prose(val markdown: String) : MarkdownSegment

    /** A fenced code block. [language] is the info string's first word, if any. */
    data class Code(val language: String?, val code: String) : MarkdownSegment

    /**
     * A GFM pipe table. Every row is padded or truncated to [header]'s width, and
     * [alignments] always has one entry per column.
     */
    data class Table(
        val header: List<String>,
        val alignments: List<MarkdownColumnAlignment>,
        val rows: List<List<String>>,
    ) : MarkdownSegment
}

internal enum class MarkdownColumnAlignment { Start, Center, End }

/** The single table [markdown] consists of, or null when it is anything else. */
internal fun parseMarkdownTable(markdown: String): MarkdownSegment.Table? =
    parseMarkdownSegments(markdown).singleOrNull() as? MarkdownSegment.Table

/** Renders back to GFM. Paired with [parseMarkdownTable] so a table survives editing. */
internal fun MarkdownSegment.Table.toMarkdown(): String {
    val delimiters = alignments.map { alignment ->
        when (alignment) {
            MarkdownColumnAlignment.Start -> "---"
            MarkdownColumnAlignment.Center -> ":---:"
            MarkdownColumnAlignment.End -> "---:"
        }
    }
    return (listOf(header, delimiters) + rows).joinToString("\n") { cells ->
        cells.joinToString(separator = " | ", prefix = "| ", postfix = " |")
    }
}

/**
 * Cell text as a person should see it. The parser keeps `\|` as written, because prose
 * renderers want the escape; a grid of text fields wants the pipe.
 */
internal fun unescapeTableCell(cell: String): String = cell.replace("\\|", "|")

/** Inverse of [unescapeTableCell]; also flattens newlines, which a table row cannot hold. */
internal fun escapeTableCell(cell: String): String =
    cell.replace("|", "\\|").replace("\n", " ").trim()

/**
 * Splits [markdown] into renderable segments. Never fails: anything unrecognized stays
 * prose, so the worst case is the previous rendering rather than lost text.
 */
internal fun parseMarkdownSegments(markdown: String): List<MarkdownSegment> {
    // The document's own trailing newline is not content: left in place it would become a
    // blank final line inside an unclosed fence.
    val lines = markdown.removeSuffix("\n").removeSuffix("\r").lines()
    val segments = mutableListOf<MarkdownSegment>()
    val prose = StringBuilder()

    fun flushProse() {
        if (prose.isNotBlank()) {
            segments += MarkdownSegment.Prose(prose.toString().trim('\n'))
        }
        prose.clear()
    }

    var index = 0
    while (index < lines.size) {
        val fence = FenceStart.parse(lines[index])
        // Fences win over tables, so a table inside a code block stays code.
        val table = if (fence == null) parseTable(lines, index) else null
        when {
            fence != null -> {
                flushProse()
                index++
                val body = mutableListOf<String>()
                while (index < lines.size && !fence.closes(lines[index])) {
                    body += lines[index]
                    index++
                }
                // An unclosed fence runs to the end of the input, as in CommonMark.
                index++
                segments += MarkdownSegment.Code(fence.language, body.joinToString("\n"))
            }

            table != null -> {
                flushProse()
                segments += table.segment
                index = table.endExclusive
            }

            else -> {
                prose.appendLine(lines[index])
                index++
            }
        }
    }
    flushProse()
    return segments
}

/** An opening ``` or ~~~ run, and what closes it. */
private class FenceStart(
    private val marker: Char,
    private val length: Int,
    val language: String?,
) {
    fun closes(line: String): Boolean {
        val trimmed = line.trimStart(' ')
        val run = trimmed.takeWhile { it == marker }.length
        return run >= length && trimmed.drop(run).isBlank()
    }

    companion object {
        fun parse(line: String): FenceStart? {
            val indent = line.takeWhile { it == ' ' }.length
            if (indent > 3) return null
            val trimmed = line.substring(indent)
            val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
            val run = trimmed.takeWhile { it == marker }.length
            if (run < 3) return null
            val info = trimmed.drop(run).trim()
            // A backtick fence's info string may not contain a backtick, which is what
            // keeps an inline `` `code` `` span from opening a block.
            if (marker == '`' && '`' in info) return null
            return FenceStart(marker, run, info.substringBefore(' ').takeIf { it.isNotEmpty() })
        }
    }
}

private class ParsedTable(val segment: MarkdownSegment.Table, val endExclusive: Int)

/**
 * A table is a row of cells followed by a delimiter row with the same number of columns —
 * the delimiter row is what distinguishes a table from a line that merely contains pipes.
 */
private fun parseTable(lines: List<String>, start: Int): ParsedTable? {
    val header = splitTableRow(lines.getOrNull(start)) ?: return null
    val alignments = parseAlignmentRow(lines.getOrNull(start + 1)) ?: return null
    if (alignments.size != header.size) return null

    var index = start + 2
    val rows = mutableListOf<List<String>>()
    while (index < lines.size) {
        val cells = splitTableRow(lines[index]) ?: break
        rows += List(header.size) { column -> cells.getOrElse(column) { "" } }
        index++
    }
    return ParsedTable(MarkdownSegment.Table(header, alignments, rows), index)
}

/** Cells of one row, or null when [line] is not a table row. */
private fun splitTableRow(line: String?): List<String>? {
    if (line == null || line.isBlank() || '|' !in line) return null
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var escaped = false
    for (char in line.trim()) {
        when {
            escaped -> {
                cell.append(char)
                escaped = false
            }
            // The backslash is kept: splitting is done here, but rendering the escape is
            // still the inline renderer's job.
            char == '\\' -> {
                cell.append(char)
                escaped = true
            }

            char == '|' -> {
                cells += cell.toString()
                cell.clear()
            }

            else -> cell.append(char)
        }
    }
    cells += cell.toString()

    // Outer pipes leave empty cells on the edges that are not columns.
    if (cells.first().isBlank()) cells.removeAt(0)
    if (cells.isNotEmpty() && cells.last().isBlank()) cells.removeAt(cells.lastIndex)
    return cells.map { it.trim() }.takeIf { it.isNotEmpty() }
}

private fun parseAlignmentRow(line: String?): List<MarkdownColumnAlignment>? {
    val cells = splitTableRow(line) ?: return null
    return cells.map { cell ->
        val dashes = cell.trim(':')
        if (dashes.isEmpty() || dashes.any { it != '-' }) return null
        when {
            cell.startsWith(':') && cell.endsWith(':') -> MarkdownColumnAlignment.Center
            cell.endsWith(':') -> MarkdownColumnAlignment.End
            else -> MarkdownColumnAlignment.Start
        }
    }
}
