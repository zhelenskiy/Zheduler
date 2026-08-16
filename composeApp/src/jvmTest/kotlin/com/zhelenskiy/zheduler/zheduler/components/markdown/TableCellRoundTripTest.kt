package com.zhelenskiy.zheduler.zheduler.components.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The table editor holds cells as plain text, writes them out as GFM and reads them back. Anything
 * that does not survive that round trip is silently rewritten under the user as they type.
 */
class TableCellRoundTripTest {

    private fun roundTrip(cells: List<String>): List<String> {
        val table = MarkdownSegment.Table(
            header = cells.map(::escapeTableCell),
            alignments = List(cells.size) { MarkdownColumnAlignment.Start },
            rows = emptyList(),
        )
        val reparsed = requireNotNull(parseMarkdownTable(table.toMarkdown())) {
            "the rendered table no longer parses as a table:\n${table.toMarkdown()}"
        }
        return reparsed.header.map(::unescapeTableCell)
    }

    @Test
    fun `ordinary text survives`() {
        assertEquals(listOf("Name", "Due date"), roundTrip(listOf("Name", "Due date")))
    }

    @Test
    fun `a pipe survives`() {
        assertEquals(listOf("a|b"), roundTrip(listOf("a|b")))
    }

    @Test
    fun `a backslash survives`() {
        assertEquals(listOf("""C:\path"""), roundTrip(listOf("""C:\path""")))
    }

    @Test
    fun `a backslash before a pipe does not split the cell`() {
        assertEquals(listOf("""a\|b""", "second"), roundTrip(listOf("""a\|b""", "second")))
    }

    @Test
    fun `a trailing backslash does not swallow the next cell`() {
        assertEquals(listOf("""ends with\""", "second"), roundTrip(listOf("""ends with\""", "second")))
    }

    @Test
    fun `a markdown escape is left alone, so it still renders as the literal character`() {
        val literalAsterisks = """\*not emphasis\*"""
        assertEquals(literalAsterisks, escapeTableCell(unescapeTableCell(literalAsterisks)))
    }

    @Test
    fun `a space can be typed into a cell`() {
        // The field is handed back whatever escaping produces, so trimming here made a space
        // impossible to type: it vanished before the next character arrived.
        assertEquals("a ", escapeTableCell("a "))
        assertEquals(" leading", escapeTableCell(" leading"))
    }

    @Test
    fun `every escaped cell parses back to one cell`() {
        val awkward = listOf(
            "plain", "|", "\\", "\\|", "\\\\|", "||", "a|b|c", "\\|\\|", "  padded  ", "new\nline",
        )
        for (cell in awkward) {
            assertEquals(
                cell.replace('\n', ' '),
                unescapeTableCell(escapeTableCell(cell)),
                "round trip of ${cell.replace("\\", "\\\\")}",
            )
        }
    }
}
