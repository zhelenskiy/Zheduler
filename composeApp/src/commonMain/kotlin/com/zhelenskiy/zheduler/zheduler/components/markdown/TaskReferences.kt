package com.zhelenskiy.zheduler.zheduler.components.markdown

/** Matches a bare task reference such as `ZH-12`, the form used across descriptions. */
internal val TaskReferencePattern = Regex("[A-Za-z][A-Za-z0-9]*-\\d+")

/**
 * A Markdown link whose target is a task reference the link chrome has turned into a URL.
 *
 * The block editor's link entry prepends `https://` to anything without a scheme
 * (`LinkUrlPolicy.validate`), so linking to `ZH-12` stores `https://ZH-12` — which no longer
 * looks like a task to the viewer, and opens a dead URL instead of the ticket. Typing the
 * prefix away does not stick either, because the next link edit re-applies it.
 */
private val SchemedTaskLink =
    Regex("]\\(\\s*https?://(${TaskReferencePattern.pattern})\\s*\\)")

/** Rewrites `[ZH-12](https://ZH-12)` back to `[ZH-12](ZH-12)`. */
internal fun withBareTaskReferenceLinks(markdown: String): String =
    markdown.replace(SchemedTaskLink) { match -> "](${match.groupValues[1]})" }

/** Pattern for the spaces that exist, falling back to any uppercase prefix. */
internal fun taskIdPattern(allSpacePrefixes: List<String>): Regex =
    if (allSpacePrefixes.isNotEmpty()) {
        Regex("\\b(${allSpacePrefixes.joinToString("|")})-\\d+\\b")
    } else {
        Regex("\\b[A-Z]+-\\d+\\b")
    }

/**
 * Spans that already carry their own meaning and must be left alone: links, images,
 * inline code, and autolinks.
 */
private val NonLinkifiableSpan = Regex("!?\\[[^\\]]*]\\([^)]*\\)|`[^`]*`|<[^<>\\s]*>")

/**
 * Turns bare `ZH-12` references into Markdown links, skipping text that is already one.
 *
 * Rewriting inside an existing link is what produced targets like `[A-2](A-2)`: the
 * reference occurs twice in `[A-2](A-2)`, so a blind replace nested a link inside itself and
 * the renderer handed that whole string to the URI handler, which cannot parse it.
 */
internal fun linkifyTaskReferences(markdown: String, pattern: Regex): String {
    val protectedSpans = NonLinkifiableSpan.findAll(markdown).map { it.range }.toList()
    val builder = StringBuilder(markdown.length)
    var copiedUpTo = 0
    for (match in pattern.findAll(markdown)) {
        if (protectedSpans.any { match.range.first in it }) continue
        builder.append(markdown, copiedUpTo, match.range.first)
        builder.append("[").append(match.value).append("](").append(match.value).append(")")
        copiedUpTo = match.range.last + 1
    }
    builder.append(markdown, copiedUpTo, markdown.length)
    return builder.toString()
}
