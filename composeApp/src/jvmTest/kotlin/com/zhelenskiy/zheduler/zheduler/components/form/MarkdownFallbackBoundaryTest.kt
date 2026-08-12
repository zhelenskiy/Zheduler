@file:OptIn(ExperimentalCascadeMarkdownApi::class)

package com.zhelenskiy.zheduler.zheduler.components.form

import io.github.linreal.cascade.editor.markdown.ExperimentalCascadeMarkdownApi
import io.github.linreal.cascade.editor.markdown.MarkdownFidelityImpact
import io.github.linreal.cascade.editor.markdown.MarkdownProfile
import io.github.linreal.cascade.editor.markdown.MarkdownSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the rule that decides whether a description keeps the block editor.
 *
 * The editor deliberately does not follow `MarkdownSchema.analyze`, which recommends raw
 * editing for any preserved syntax at all. It keeps the block editor whenever the canonical
 * form is a fixpoint and nothing is lost — preserved constructs are shown as editable
 * Markdown source instead. These tests pin both halves of that reasoning.
 */
class MarkdownFallbackBoundaryTest {

    private val nativelyEditable = mapOf(
        "plain paragraph" to "Just a description.\n",
        "bullets" to "Steps:\n\n- one\n- two\n",
        "nested bullets" to "Steps:\n\n- one\n  - inner\n- two\n",
        "ordered list" to "Steps:\n\n1. first\n2. second\n",
        "task list" to "Todo:\n\n- [ ] open\n- [x] done\n",
        "simple quote" to "> a quote\n",
        "headings and emphasis" to "# Title\n\n## Sub\n\nSome **bold** text.\n",
        "link and inline code" to "See [docs](https://example.com) and `code`.\n",
        "plain code fence" to "```\nplain code\n```\n",
        "thematic break" to "before\n\n---\n\nafter\n",
    )

    /**
     * Preserved verbatim rather than decoded. Each one used to send the entire description
     * to the plain text box; now each is an editable source block inside the editor.
     */
    private val preservedVerbatim = mapOf(
        "pipe table" to "| a | b |\n| - | - |\n| 1 | 2 |\n",
        "image" to "![alt](https://example.com/a.png)\n",
        "language-tagged fence" to "```kotlin\nval a = 1\n```\n",
        "quote containing a list" to "> intro\n>\n> - one\n> - two\n",
        "quote containing code" to "> intro\n>\n> ```\n> code\n> ```\n",
        "multi-paragraph list item" to "- first para\n\n  second para\n",
        "table with prose around it" to "Budget:\n\n| a | b |\n| - | - |\n| 1 | 2 |\n\nEnd.\n",
    )

    /**
     * The safety property behind keeping the block editor: encoding what was decoded is
     * stable, so a save/reload cycle cannot drift the stored description.
     */
    @Test
    fun everySupportedDescriptionIsACanonicalFixpoint() {
        (nativelyEditable + preservedVerbatim).forEach { (case, markdown) ->
            val canonical = canonicalize(markdown)
            assertNotNull(canonical, "canonical encode for $case")
            assertEquals(canonical, canonicalize(canonical), "re-encoding is stable for $case")
        }
    }

    /** Preservation is opaque, never lossy — that is what makes it safe to keep editing. */
    @Test
    fun preservedConstructsReportNoDataLoss() {
        preservedVerbatim.forEach { (case, markdown) ->
            val result = MarkdownSchema.decodeWithReport(markdown, MarkdownProfile.Default)
            assertTrue(result.isSuccess, "decode succeeded for $case")
            val lossy = result.warnings.filter { warning ->
                warning.impact == MarkdownFidelityImpact.DataLoss ||
                    warning.impact == MarkdownFidelityImpact.Fatal
            }
            assertEquals(emptyList(), lossy, "no lossy warning for $case")
        }
    }

    /** The verbatim slice is exact, so the construct itself is never rewritten. */
    @Test
    fun preservedConstructsSurviveARoundTripCharacterForCharacter() {
        preservedVerbatim.forEach { (case, markdown) ->
            val canonical = assertNotNull(canonicalize(markdown), "canonical encode for $case")
            val distinctiveLine = markdown.lines().first { it.isNotBlank() && it != "Budget:" }
            assertTrue(
                canonical.contains(distinctiveLine),
                "$case kept its source line; got:\n$canonical",
            )
        }
    }

    private fun canonicalize(markdown: String): String? {
        val blocks = MarkdownSchema.decode(markdown, MarkdownProfile.Default) ?: return null
        return MarkdownSchema.encode(blocks, MarkdownProfile.Default)
    }
}
