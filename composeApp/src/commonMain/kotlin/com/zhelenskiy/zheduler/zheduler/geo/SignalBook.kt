package com.zhelenskiy.zheduler.zheduler.geo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.zhelenskiy.zheduler.zheduler.viewmodels.SavedSignalContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.SavedSignalIntent
import pro.respawn.flowmvi.compose.dsl.subscribe

/**
 * The book of networks and devices, as the rule editor needs it: somewhere to pick from, and
 * somewhere to put one that was named while writing a rule.
 *
 * The counterpart of `PlaceBook`, down to why it is a composition local — see there.
 */
@Stable
interface SignalBook {
    val signals: List<SavedSignal>

    /** Files one by id, which is how the book's own screen renames what is already there. */
    fun save(signal: SavedSignal)

    /**
     * Files one only if the book has not got it already.
     *
     * What the rule editor asks, where there is no id in mind and [signals] is a snapshot that
     * cannot see a save still in flight. The decision belongs to the repository; see
     * `TaskRepository.keepSignal`.
     */
    fun keep(signal: SavedSignal)

    /** Removes one. Rules that copied it keep working — they hold their own copy of the signal. */
    fun delete(id: String)

    fun newId(): String

    companion object {
        /** Nothing saved, and nowhere to put one. */
        val Empty: SignalBook = object : SignalBook {
            override val signals: List<SavedSignal> = emptyList()
            override fun save(signal: SavedSignal) = Unit
            override fun keep(signal: SavedSignal) = Unit
            override fun delete(id: String) = Unit
            override fun newId(): String = ""
        }
    }
}

val LocalSignalBook = compositionLocalOf { SignalBook.Empty }

/** Puts the real book into the composition, from the container that owns it. */
@Composable
fun SignalBookProvider(container: SavedSignalContainer, content: @Composable () -> Unit) {
    val state by container.store.subscribe()
    val book = remember(state.signals, container) {
        object : SignalBook {
            override val signals: List<SavedSignal> = state.signals
            override fun save(signal: SavedSignal) =
                container.store.intent(SavedSignalIntent.Save(signal))

            override fun keep(signal: SavedSignal) =
                container.store.intent(SavedSignalIntent.Keep(signal))

            override fun delete(id: String) =
                container.store.intent(SavedSignalIntent.Delete(id))

            override fun newId(): String = container.generateId()
        }
    }
    CompositionLocalProvider(LocalSignalBook provides book, content = content)
}
