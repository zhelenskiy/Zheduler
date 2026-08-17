package com.zhelenskiy.zheduler.zheduler.components.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A code block ends at a fence that is indented no further than an opening one may be.
 *
 * Showing fence syntax inside a code sample is done by indenting it, and a parser that closes on
 * any indentation cuts the block in two there: the rest of the sample renders as prose, and the
 * real closing fence opens a block that swallows everything after it.
 */
class FencedCodeBlockTest {

    @Test
    fun `an indented fence inside a block is content, not the end of it`() {
        val markdown = """
            ```
                ```
            still code
            ```
            after
        """.trimIndent()

        val segments = parseMarkdownSegments(markdown)
        val code = segments.filterIsInstance<MarkdownSegment.Code>().single()

        assertTrue("still code" in code.code, "the block ended early: ${code.code}")
        assertTrue("```" in code.code, "the indented fence should be part of the sample")
        assertEquals(
            "after",
            segments.filterIsInstance<MarkdownSegment.Prose>().joinToString("") { it.markdown }.trim(),
        )
    }

    @Test
    fun `a fence indented up to three spaces still closes the block`() {
        val markdown = "```\ncode\n   ```\nafter"

        val segments = parseMarkdownSegments(markdown)

        assertEquals("code", segments.filterIsInstance<MarkdownSegment.Code>().single().code.trim())
        assertEquals(
            "after",
            segments.filterIsInstance<MarkdownSegment.Prose>().joinToString("") { it.markdown }.trim(),
        )
    }
}
