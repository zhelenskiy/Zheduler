package com.zhelenskiy.zheduler.zheduler.components.form

import com.zhelenskiy.zheduler.zheduler.components.markdown.MarkdownColumnAlignment
import com.zhelenskiy.zheduler.zheduler.components.markdown.MarkdownSegment
import com.zhelenskiy.zheduler.zheduler.components.markdown.escapeTableCell
import com.zhelenskiy.zheduler.zheduler.components.markdown.parseMarkdownTable
import com.zhelenskiy.zheduler.zheduler.components.markdown.toMarkdown
import com.zhelenskiy.zheduler.zheduler.components.markdown.unescapeTableCell
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The table grid writes each edit into the document and reads the whole table back, so what a cell
 * shows is whatever survives that round trip.
 *
 * A pipe table has no way to hold surrounding spaces, and the parser is right to drop them — which
 * is exactly why a cell cannot simply display what the document returns while it is being typed
 * into. This pins the round trip's shape; the field's own handling of it lives in TableCell.
 */
class TableCellTypingTest {

    /** What the grid gets back after writing [typed] into a one-cell table. */
    private fun roundTrip(typed: String): String {
        val table = MarkdownSegment.Table(
            header = listOf(escapeTableCell(typed)),
            alignments = listOf(MarkdownColumnAlignment.Start),
            rows = emptyList(),
        )
        val reparsed = requireNotNull(parseMarkdownTable(table.toMarkdown()))
        return unescapeTableCell(reparsed.header.single())
    }

    @Test
    fun `the document drops the spaces around a cell`() {
        // Not a defect — it is what the format means. The consequence is that the field cannot be
        // driven straight from the document, or a space could never be typed.
        assertEquals("hello", roundTrip("hello "))
        assertEquals("hello", roundTrip(" hello"))
    }

    @Test
    fun `spaces inside a cell survive`() {
        assertEquals("hello world", roundTrip("hello world"))
    }

    @Test
    fun `what the field would show equals its own text trimmed`() {
        // The rule TableCell uses to tell its own normalisation from someone else's edit.
        for (typed in listOf("hello ", " hello", "hello world", "a|b", """a\|b""", "")) {
            assertEquals(typed.trim(), roundTrip(typed), "round trip of '$typed'")
        }
    }
}
