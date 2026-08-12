package com.zhelenskiy.zheduler.zheduler.components.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskReferencesTest {

    private val pattern = taskIdPattern(listOf("ZH", "A"))

    @Test
    fun bareReferenceBecomesALink() {
        assertEquals(
            "See [ZH-12](ZH-12) today.",
            linkifyTaskReferences("See ZH-12 today.", pattern),
        )
    }

    /**
     * The crash this prevents: rewriting inside an existing link nested it in itself, and the
     * renderer then asked the platform to open `[A-2](A-2)`, which is not a URL.
     */
    @Test
    fun anExistingLinkIsLeftAlone() {
        assertEquals("[A-2](A-2)", linkifyTaskReferences("[A-2](A-2)", pattern))
        assertEquals(
            "[ZH-12](https://tracker/ZH-12)",
            linkifyTaskReferences("[ZH-12](https://tracker/ZH-12)", pattern),
        )
        assertEquals(
            "Blocked by [ZH-12](ZH-12), see A-2 too.".replace("A-2 too", "[A-2](A-2) too"),
            linkifyTaskReferences("Blocked by [ZH-12](ZH-12), see A-2 too.", pattern),
        )
    }

    @Test
    fun codeSpansAndImagesAreLeftAlone() {
        assertEquals("`ZH-12`", linkifyTaskReferences("`ZH-12`", pattern))
        assertEquals("![ZH-12](a.png)", linkifyTaskReferences("![ZH-12](a.png)", pattern))
        assertEquals("<ZH-12>", linkifyTaskReferences("<ZH-12>", pattern))
    }

    @Test
    fun repeatedLinkifyingIsStable() {
        val once = linkifyTaskReferences("See ZH-12.", pattern)
        assertEquals(once, linkifyTaskReferences(once, pattern))
    }

    @Test
    fun schemedTaskLinksAreRewrittenBare() {
        assertEquals("[ZH-12](ZH-12)", withBareTaskReferenceLinks("[ZH-12](https://ZH-12)"))
        // Real URLs keep their scheme.
        assertEquals(
            "[docs](https://example.com)",
            withBareTaskReferenceLinks("[docs](https://example.com)"),
        )
    }
}
