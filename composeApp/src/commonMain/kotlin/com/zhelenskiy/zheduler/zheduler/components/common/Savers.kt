package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import kotlinx.serialization.json.Json

/**
 * Saved state for a value the platform cannot keep on its own, carried as the JSON the database
 * already stores it in.
 *
 * What a platform can save is a short list of primitive types, and most of what a dialog holds
 * while the user is filling it in is not on that list — a status with the tasks it waits on, a
 * recurrence trigger, a termination condition. Without a saver they are simply lost when the
 * activity is recreated, which for a dialog means everything typed into it since it opened.
 *
 * A value that will not decode restores as [fallback] rather than throwing: it can only come from
 * a build that wrote a shape this one does not understand, and losing the dialog's contents beats
 * failing to restore the screen at all.
 *
 * [fallback] rather than nothing, because these are used through `rememberSaveable(stateSaver =)`:
 * there, a restore answering null does not fall back to the caller's initial value the way the
 * whole-object form does. Compose puts the null in the state and hands it back typed as though it
 * were a value, and the screen dies on the first read of it.
 */
internal inline fun <reified T : Any> jsonSaver(crossinline fallback: () -> T): Saver<T, Any> = listSaver(
    save = { listOf(Json.encodeToString(it)) },
    restore = { saved ->
        runCatching { Json.decodeFromString<T>(saved[0] as String) }.getOrElse { fallback() }
    },
)

/**
 * As [jsonSaver], for a value that is itself allowed to be absent.
 *
 * No fallback needed: absent is a value here, so a restore answering null says something true.
 */
internal inline fun <reified T : Any> nullableJsonSaver(): Saver<T?, Any> = listSaver(
    save = { value -> listOf(value?.let { Json.encodeToString(it) }) },
    restore = { saved ->
        val encoded = saved.firstOrNull() as String?
        if (encoded == null) null else runCatching { Json.decodeFromString<T>(encoded) }.getOrNull()
    },
)
