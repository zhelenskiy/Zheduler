package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.geo.SavedLocation
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.generateId as generateIdImpl
import pro.respawn.flowmvi.api.Container
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

/**
 * @param locations the address book, as it stands.
 * @param query what is in the screen's search box, which narrows the book. Searching the *world*
 *   is a different thing and belongs to the dialog that adds a place — see `PlaceSearchState`.
 */
data class SavedLocationState(
    val locations: List<SavedLocation> = emptyList(),
    val query: String = "",
) : MVIState {
    /** The kept places [query] names, matched the way the repository matches them. */
    val matching: List<SavedLocation>
        get() = query.trim().let { needle ->
            if (needle.isEmpty()) locations
            else locations.filter {
                it.name.contains(needle, ignoreCase = true) || it.address.contains(needle, ignoreCase = true)
            }
        }
}

sealed interface SavedLocationIntent : MVIIntent {
    data object Load : SavedLocationIntent
    data class Save(val location: SavedLocation) : SavedLocationIntent
    data class Delete(val id: String) : SavedLocationIntent
    data class Filter(val query: String) : SavedLocationIntent
}

sealed interface SavedLocationAction : MVIAction

private typealias SavedLocationPipelineContext =
    PipelineContext<SavedLocationState, SavedLocationIntent, SavedLocationAction>

/**
 * The address book.
 *
 * Not scoped to a space — a place is a place in every one of them — which is why this takes no
 * space id and why the screen showing it hangs off the space list rather than off one space.
 */
class SavedLocationContainer(
    private val repository: TaskRepository,
) : ScopedContainer(), Container<SavedLocationState, SavedLocationIntent, SavedLocationAction> {

    override val store = store(SavedLocationState(), scope) {
        reportingFailuresAs("SavedLocationStore")

        whileSubscribed {
            load()
        }

        reduce { intent ->
            when (intent) {
                is SavedLocationIntent.Load -> load()
                is SavedLocationIntent.Save -> save(intent.location)
                is SavedLocationIntent.Delete -> delete(intent.id)
                is SavedLocationIntent.Filter -> updateState { copy(query = intent.query) }
            }
        }
    }

    private suspend fun SavedLocationPipelineContext.load() {
        val locations = repository.getAllSavedLocations()
        updateState { copy(locations = locations) }
    }

    private suspend fun SavedLocationPipelineContext.save(location: SavedLocation) {
        repository.saveLocation(location)
        val locations = repository.getAllSavedLocations()
        updateState { copy(locations = locations) }
    }

    private suspend fun SavedLocationPipelineContext.delete(id: String) {
        repository.deleteSavedLocation(id)
        val locations = repository.getAllSavedLocations()
        updateState { copy(locations = locations) }
    }

    fun generateId(): String = generateIdImpl()
}
