package com.zhelenskiy.zheduler.zheduler.components.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task references inside code are text, not links. A code span is a run of backticks closed by a
 * run of the same length, which is how a span holds a backtick of its own.
 */
class CodeSpanLinkifyTest {

    private val pattern = Regex("\\b[A-Z]+-\\d+\\b")

    private fun linkify(markdown: String) = linkifyTaskReferences(markdown, pattern)

    @Test
    fun `a bare reference becomes a link`() {
        assertEquals("see [ZH-12](ZH-12) now", linkify("see ZH-12 now"))
    }

    @Test
    fun `a reference in a single-backtick span is left alone`() {
        assertEquals("see `ZH-12` now", linkify("see `ZH-12` now"))
    }

    @Test
    fun `a reference in a double-backtick span is left alone`() {
        assertEquals("see ``ZH-12`` now", linkify("see ``ZH-12`` now"))
    }

    @Test
    fun `a double-backtick span containing a backtick is left alone`() {
        assertEquals("see `` `ZH-12` `` now", linkify("see `` `ZH-12` `` now"))
    }

    @Test
    fun `a reference outside the span is still linked`() {
        assertEquals(
            "``code`` and [ZH-12](ZH-12)",
            linkify("``code`` and ZH-12"),
        )
    }

    @Test
    fun `a reference already inside a link is left alone`() {
        assertEquals("[ZH-12](ZH-12)", linkify("[ZH-12](ZH-12)"))
    }
}
