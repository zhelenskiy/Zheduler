package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.SavedFilter
import com.zhelenskiy.zheduler.zheduler.SavedFilterWithViewMode
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.sync.CloudSpaces
import com.zhelenskiy.zheduler.zheduler.sync.CommitOutcome
import com.zhelenskiy.zheduler.zheduler.ViewMode
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.generateId as generateIdImpl
import pro.respawn.flowmvi.api.Container
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

data class SavedFilterState(
    val savedFilters: List<SavedFilterWithViewMode> = emptyList(),
    val viewModes: List<ViewMode> = emptyList(),
    val allTags: Set<String> = emptySet(),
    val spaceIdPrefix: String? = null
) : MVIState

sealed interface SavedFilterIntent : MVIIntent {
    data object LoadInitialData : SavedFilterIntent
    data class SaveFilter(val filter: SavedFilter) : SavedFilterIntent
    data class DeleteFilter(val filterId: String) : SavedFilterIntent
}

sealed interface SavedFilterAction : MVIAction {

    /**
     * The server would not take it, so the filter is not there.
     *
     * Named rather than silent: without it a filter the user had just named would appear in the
     * list and then vanish, with the dialog long closed and nothing to say why.
     */
    data object FilterNotAccepted : SavedFilterAction
}

private typealias SavedFilterPipelineContext = PipelineContext<SavedFilterState, SavedFilterIntent, SavedFilterAction>

class SavedFilterContainer(
    private val repository: TaskRepository,
    private val spaceId: String,
    /** Where a cloud space's changes have to be agreed. Null in a build with no sync. */
    private val cloud: CloudSpaces? = null,
) : ScopedContainer(), Container<SavedFilterState, SavedFilterIntent, SavedFilterAction> {

    override val store = store(SavedFilterState(), scope) {
        reportingFailuresAs("SavedFilterStore")

        whileSubscribed {
            loadInitialData()
        }

        reduce { intent ->
            when (intent) {
                is SavedFilterIntent.LoadInitialData -> loadInitialData()
                is SavedFilterIntent.SaveFilter -> saveFilter(intent.filter)
                is SavedFilterIntent.DeleteFilter -> deleteFilter(intent.filterId)
            }
        }
    }

    private suspend fun SavedFilterPipelineContext.loadInitialData() {
        val savedFilters = repository.getAllSavedFiltersWithViewModes(spaceId)
        val viewModes = repository.getAllViewModes(spaceId)
        val allTags = repository.getAllTags(spaceId)
        val space = repository.getSpaceById(spaceId)

        updateState {
            copy(
                savedFilters = savedFilters,
                viewModes = viewModes,
                allTags = allTags,
                spaceIdPrefix = space?.idPrefix
            )
        }
    }

    /**
     * Saved filters travel with the space, so one the server will not take is removed again a
     * moment later. Written inside the commit and waited for, so the list that is about to be
     * shown is the truth rather than a filter that appears and then quietly goes.
     */
    private suspend fun SavedFilterPipelineContext.saveFilter(filter: SavedFilter) {
        val write: suspend () -> Boolean = { repository.saveSavedFilter(filter); true }
        val outcome = cloud?.commit(spaceId, write)
            ?: run { write(); CommitOutcome.Accepted }

        val savedFilters = repository.getAllSavedFiltersWithViewModes(spaceId)
        updateState { copy(savedFilters = savedFilters) }
        if (outcome != CommitOutcome.Accepted) action(SavedFilterAction.FilterNotAccepted)
    }

    private suspend fun SavedFilterPipelineContext.deleteFilter(filterId: String) {
        repository.deleteSavedFilter(spaceId, filterId)
        val savedFilters = repository.getAllSavedFiltersWithViewModes(spaceId)
        updateState { copy(savedFilters = savedFilters) }
    }

    fun generateId(): String = generateIdImpl()
}
