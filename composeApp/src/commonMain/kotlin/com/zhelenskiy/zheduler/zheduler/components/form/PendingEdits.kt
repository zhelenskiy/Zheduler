package com.zhelenskiy.zheduler.zheduler.components.form

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Editors that report their changes on a delay, and the way to make those changes land now.
 *
 * The rich description editor encodes its document to Markdown only once typing pauses, because
 * encoding on every keystroke is expensive. Everything typed since the last pause therefore lives
 * in the editor and nowhere else. Anything that reads the form and acts on what it finds — Save,
 * and the check for unsaved work on the way out — has to ask for those edits first, or it will
 * read a description that stops at the last pause: the tail of a sentence lost on Save, and a back
 * press that reports nothing to discard and discards it.
 *
 * An editor also flushes when it goes away, which covers being navigated off or switched for the
 * other editor; this is for the cases where the form is read while the editor is still on screen.
 */
@Stable
class PendingEdits {
    private val flushes = mutableListOf<() -> Unit>()

    /** Registers [flush], and answers with the way to take it off again. */
    fun register(flush: () -> Unit): () -> Unit {
        flushes += flush
        return { flushes -= flush }
    }

    /** Makes every registered editor report what it is holding. */
    fun flush() {
        // Copied first: a flush is free to unregister itself.
        flushes.toList().forEach { it() }
    }
}

/** The [PendingEdits] of the form being edited, if any. */
val LocalPendingEdits = staticCompositionLocalOf { PendingEdits() }
