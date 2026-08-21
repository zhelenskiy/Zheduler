package com.zhelenskiy.zheduler.zheduler.components.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.zhelenskiy.zheduler.zheduler.geo.SavedLocation
import com.zhelenskiy.zheduler.zheduler.viewmodels.SavedLocationContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.SavedLocationIntent
import pro.respawn.flowmvi.compose.dsl.subscribe

/**
 * The address book, as the rule editor needs it: somewhere to pick from, and somewhere to put a
 * place that was made while writing a rule.
 */
@Stable
interface PlaceBook {
    val places: List<SavedLocation>
    fun save(location: SavedLocation)
    fun newId(): String

    companion object {
        /** No places, and nowhere to put one. */
        val Empty: PlaceBook = object : PlaceBook {
            override val places: List<SavedLocation> = emptyList()
            override fun save(location: SavedLocation) = Unit
            override fun newId(): String = ""
        }
    }
}

/**
 * The book, reachable from anywhere in the composition.
 *
 * A composition local rather than a parameter because the rule editor sits five layers below any
 * screen that knows about the book, and every one of those layers would otherwise carry a
 * parameter it has nothing to do with. Not a *static* one, and with a working default, so that a
 * test can compose the rule dialog without standing up the object graph — which several already
 * do.
 */
val LocalPlaceBook = compositionLocalOf { PlaceBook.Empty }

/** Puts the real book into the composition, from the container that owns it. */
@Composable
fun PlaceBookProvider(container: SavedLocationContainer, content: @Composable () -> Unit) {
    val state by container.store.subscribe()
    val book = remember(state.locations, container) {
        object : PlaceBook {
            override val places: List<SavedLocation> = state.locations
            override fun save(location: SavedLocation) =
                container.store.intent(SavedLocationIntent.Save(location))

            override fun newId(): String = container.generateId()
        }
    }
    CompositionLocalProvider(LocalPlaceBook provides book, content = content)
}
