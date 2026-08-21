package com.zhelenskiy.zheduler.zheduler.components.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.zhelenskiy.zheduler.zheduler.geo.NominatimSearch
import com.zhelenskiy.zheduler.zheduler.geo.PlaceResult
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * A search box over the world's addresses, as a piece of screen state.
 *
 * Held here rather than in a container because two quite different screens want one — the dialog
 * that adds a place to the book, and the dialog that picks places for a rule — and neither of
 * their containers has anything else to do with geocoding.
 */
@Stable
class PlaceSearchState internal constructor(private val queryState: MutableState<String>) {
    /** Backed by saved state, so a recreation comes back to the words the user typed. */
    val query: String get() = queryState.value

    private val resultsState = mutableStateOf<List<PlaceResult>>(emptyList())
    private val searchingState = mutableStateOf(false)

    /** What the geocoder made of [query]; empty until it has answered. */
    val results: List<PlaceResult> get() = resultsState.value

    /** Whether an answer is still on its way — the difference between "none" and "not yet". */
    val searching: Boolean get() = searchingState.value

    internal fun report(results: List<PlaceResult>, searching: Boolean) {
        resultsState.value = results
        searchingState.value = searching
    }

    fun type(text: String) {
        queryState.value = text
        // Cleared with the query, not left standing: matches for what was typed a moment ago,
        // sitting under what is typed now, read as answers to the wrong question.
        report(results = emptyList(), searching = text.trim().length >= MIN_QUERY)
    }

    /** Puts a result away once it has been chosen, so the list does not sit over the map. */
    fun accept() {
        report(results = emptyList(), searching = false)
    }

    internal companion object {
        /** Long enough that a word typed at speed is one request rather than six. */
        val SETTLE = 400.milliseconds

        /** Below this a query matches half the world, and the geocoder is asked for nothing. */
        const val MIN_QUERY = 3
    }
}

/**
 * A [PlaceSearchState] that searches as typing settles.
 *
 * The wait is what keeps one request per word rather than one per keystroke — which matters twice
 * over here, since the geocoder is a free public service that asks to be used gently and holds
 * itself to a request a second regardless.
 *
 * The query is saved across a recreation but the results are not: they come back from the same
 * query being asked again, and carrying a list of addresses through saved state to save one
 * request is not a trade worth making.
 */
@Composable
fun rememberPlaceSearch(key: String = "place-search"): PlaceSearchState {
    val query = rememberSaveable(key = "$key:query") { mutableStateOf("") }
    val state = remember(query) { PlaceSearchState(query) }
    val search = remember { NominatimSearch() }

    LaunchedEffect(state.query) {
        val trimmed = state.query.trim()
        if (trimmed.length < PlaceSearchState.MIN_QUERY) {
            state.report(results = emptyList(), searching = false)
            return@LaunchedEffect
        }
        state.report(results = emptyList(), searching = true)
        delay(PlaceSearchState.SETTLE)
        state.report(results = search.search(trimmed, limit = 8), searching = false)
    }

    return state
}
