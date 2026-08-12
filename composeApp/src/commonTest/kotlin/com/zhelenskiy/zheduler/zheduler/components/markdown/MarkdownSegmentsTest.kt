package com.zhelenskiy.zheduler.zheduler.components.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownSegmentsTest {

    @Test
    fun prosePassesThroughAsOneSegment() {
        val segments = parseMarkdownSegments("# Title\n\nSome *text*.\n\n- one\n- two\n")
        assertEquals(1, segments.size)
        assertEquals("# Title\n\nSome *text*.\n\n- one\n- two", segments.prose(0))
    }

    @Test
    fun fencedCodeKeepsLanguageAndContentVerbatim() {
        val segments = parseMarkdownSegments(
            "Before\n\n```kotlin\nfun main() {\n\n    println(\"a | b\")\n}\n```\n\nAfter\n"
        )

        assertEquals(3, segments.size)
        assertEquals("Before", segments.prose(0))
        val code = segments[1] as MarkdownSegment.Code
        assertEquals("kotlin", code.language)
        // Blank lines, indentation and pipes all survive.
        assertEquals("fun main() {\n\n    println(\"a | b\")\n}", code.code)
        assertEquals("After", segments.prose(2))
    }

    @Test
    fun fenceWithoutInfoStringHasNoLanguage() {
        val segments = parseMarkdownSegments("```\nplain\n```\n")
        val code = segments.single() as MarkdownSegment.Code
        assertEquals(null, code.language)
        assertEquals("plain", code.code)
    }

    @Test
    fun tildeFencesAndLongerRunsAreRecognized() {
        val segments = parseMarkdownSegments("~~~~ text\na ``` b\n~~~~\n")
        val code = segments.single() as MarkdownSegment.Code
        assertEquals("text", code.language)
        // A shorter run of a different marker does not close the fence.
        assertEquals("a ``` b", code.code)
    }

    @Test
    fun unclosedFenceRunsToTheEnd() {
        val segments = parseMarkdownSegments("```\nstill code\nand more\n")
        val code = segments.single() as MarkdownSegment.Code
        assertEquals("still code\nand more", code.code)
    }

    @Test
    fun inlineCodeSpanDoesNotOpenABlock() {
        val segments = parseMarkdownSegments("Use ```literal``` inline.\n")
        assertTrue(segments.single() is MarkdownSegment.Prose)
    }

    @Test
    fun pipeTableIsParsedWithAlignmentsAndRows() {
        val segments = parseMarkdownSegments(
            """
            | item | cost | note |
            | :--- | ---: | :--: |
            | infra | 42 | ok |
            | ci | 7 | fine |
            """.trimIndent() + "\n"
        )

        val table = segments.single() as MarkdownSegment.Table
        assertEquals(listOf("item", "cost", "note"), table.header)
        assertEquals(
            listOf(
                MarkdownColumnAlignment.Start,
                MarkdownColumnAlignment.End,
                MarkdownColumnAlignment.Center,
            ),
            table.alignments,
        )
        assertEquals(listOf(listOf("infra", "42", "ok"), listOf("ci", "7", "fine")), table.rows)
    }

    @Test
    fun tableWithoutOuterPipesIsParsed() {
        val segments = parseMarkdownSegments("a | b\n--- | ---\n1 | 2\n")
        val table = segments.single() as MarkdownSegment.Table
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun raggedRowsArePaddedToTheHeaderWidth() {
        val segments = parseMarkdownSegments("| a | b | c |\n| - | - | - |\n| 1 |\n")
        val table = segments.single() as MarkdownSegment.Table
        assertEquals(listOf(listOf("1", "", "")), table.rows)
    }

    @Test
    fun escapedPipeStaysInsideItsCell() {
        val segments = parseMarkdownSegments("| a | b |\n| - | - |\n| x \\| y | z |\n")
        val table = segments.single() as MarkdownSegment.Table
        assertEquals(listOf(listOf("x \\| y", "z")), table.rows)
    }

    @Test
    fun pipesWithoutADelimiterRowStayProse() {
        val segments = parseMarkdownSegments("this | that\nand | more\n")
        assertTrue(segments.single() is MarkdownSegment.Prose)
    }

    @Test
    fun delimiterRowMustMatchTheHeaderWidth() {
        val segments = parseMarkdownSegments("| a | b |\n| --- |\n")
        assertTrue(segments.single() is MarkdownSegment.Prose)
    }

    @Test
    fun tableEndsAtTheFirstNonRowLine() {
        val segments = parseMarkdownSegments("| a |\n| - |\n| 1 |\n\nAfter the table.\n")
        assertEquals(2, segments.size)
        assertEquals(listOf(listOf("1")), (segments[0] as MarkdownSegment.Table).rows)
        assertEquals("After the table.", segments.prose(1))
    }

    /** A fenced block wins over a table, so documented Markdown examples stay code. */
    @Test
    fun tableInsideAFenceStaysCode() {
        val segments = parseMarkdownSegments("```md\n| a | b |\n| - | - |\n| 1 | 2 |\n```\n")
        val code = segments.single() as MarkdownSegment.Code
        assertEquals("md", code.language)
        assertEquals("| a | b |\n| - | - |\n| 1 | 2 |", code.code)
    }

    @Test
    fun emptyInputProducesNoSegments() {
        assertEquals(emptyList(), parseMarkdownSegments(""))
        assertEquals(emptyList(), parseMarkdownSegments("\n\n"))
    }

    @Test
    fun tableSurvivesParseAndRender() {
        val source = "| item | cost | note |\n| :--- | ---: | :--: |\n| infra | 42 | ok |\n"
        val table = assertNotNull(parseMarkdownTable(source))

        val rendered = table.toMarkdown()
        // Canonical spacing, same content and alignments — and stable from then on.
        assertEquals(
            "| item | cost | note |\n| --- | ---: | :---: |\n| infra | 42 | ok |",
            rendered,
        )
        assertEquals(rendered, assertNotNull(parseMarkdownTable(rendered)).toMarkdown())
    }

    @Test
    fun parseMarkdownTableRejectsAnythingElse() {
        assertNull(parseMarkdownTable("Just prose.\n"))
        assertNull(parseMarkdownTable("| a |\n| - |\n| 1 |\n\nTrailing prose.\n"))
    }

    @Test
    fun cellEscapingRoundTrips() {
        assertEquals("x | y", unescapeTableCell("x \\| y"))
        assertEquals("x \\| y", escapeTableCell("x | y"))
        // A newline would break the row, so it collapses to a space.
        assertEquals("one two", escapeTableCell("one\ntwo"))
    }

    private fun List<MarkdownSegment>.prose(index: Int): String =
        (this[index] as MarkdownSegment.Prose).markdown
}
