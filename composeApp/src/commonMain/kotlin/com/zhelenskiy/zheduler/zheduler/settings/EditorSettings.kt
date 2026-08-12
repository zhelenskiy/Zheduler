package com.zhelenskiy.zheduler.zheduler.settings

import kotlinx.serialization.Serializable

/**
 * Which editor a task description is edited with. Both write the same Markdown, so a
 * description stays readable and editable whichever one produced it.
 */
enum class DescriptionEditorKind(val label: String) {
    /** Block-based WYSIWYG editor (cascade-editor). Unavailable on the JS target. */
    Rich("Rich text"),

    /** Markdown source in a text field, with a rendered preview below it. */
    Markdown("Markdown"),
}

/** What a task uses unless its own choice says otherwise. */
val DefaultDescriptionEditor: DescriptionEditorKind = DescriptionEditorKind.Rich

/**
 * Serializable data class for persisting editor settings.
 *
 * The editor is chosen per task rather than app-wide: dropping to Markdown to hand-edit one
 * task's table should not change how every other task is edited and rendered. Only tasks
 * that differ from [DefaultDescriptionEditor] are listed, so the map stays small.
 */
@Serializable
data class EditorSettings(
    val descriptionEditorByTask: Map<String, DescriptionEditorKind> = emptyMap()
)
