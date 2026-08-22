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
 * @param locations the address book, as it stands. Narrowing it is the dialog's own business —
 *   see `SavedLocation.matches` — because a search box is what the reader typed, not something
 *   the book knows about itself.
 */
data class SavedLocationState(
    val locations: List<SavedLocation> = emptyList(),
) : MVIState

sealed interface SavedLocationIntent : MVIIntent {
    data object Load : SavedLocationIntent
    data class Save(val location: SavedLocation) : SavedLocationIntent
    data class Delete(val id: String) : SavedLocationIntent
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
