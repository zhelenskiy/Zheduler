package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.SavedFilter
import com.zhelenskiy.zheduler.zheduler.SavedFilterWithViewMode
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.ViewMode
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.generateId as generateIdImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

sealed interface SavedFilterAction : MVIAction

private typealias SavedFilterPipelineContext = PipelineContext<SavedFilterState, SavedFilterIntent, SavedFilterAction>

class SavedFilterContainer(
    private val repository: TaskRepository,
    private val spaceId: String
) : Container<SavedFilterState, SavedFilterIntent, SavedFilterAction> {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val store = store(SavedFilterState(), scope) {
        configure {
            name = "SavedFilterStore"
        }

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

    private suspend fun SavedFilterPipelineContext.saveFilter(filter: SavedFilter) {
        repository.saveSavedFilter(filter)
        val savedFilters = repository.getAllSavedFiltersWithViewModes(spaceId)
        updateState { copy(savedFilters = savedFilters) }
    }

    private suspend fun SavedFilterPipelineContext.deleteFilter(filterId: String) {
        repository.deleteSavedFilter(spaceId, filterId)
        val savedFilters = repository.getAllSavedFiltersWithViewModes(spaceId)
        updateState { copy(savedFilters = savedFilters) }
    }

    fun generateId(): String = generateIdImpl()
}
