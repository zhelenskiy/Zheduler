package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.ViewMode
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.generateId
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

data class ViewModeState(
    val viewModes: List<ViewMode> = emptyList(),
    val activeViewMode: ViewMode? = null,
    val allTags: Set<String> = emptySet(),
    val filteredTags: List<String> = emptyList(),
    val loadedViewMode: ViewMode? = null
) : MVIState

sealed interface ViewModeIntent : MVIIntent {
    data object LoadInitialData : ViewModeIntent
    data class SetActiveViewMode(val viewModeId: String) : ViewModeIntent
    data class SaveViewMode(val viewMode: ViewMode) : ViewModeIntent
    data class DeleteViewMode(val viewModeId: String) : ViewModeIntent
    data class LoadViewModeById(val viewModeId: String) : ViewModeIntent
    data class FilterTags(val searchQuery: String, val excludeTags: Set<String>) : ViewModeIntent
}

sealed interface ViewModeAction : MVIAction

private typealias ViewModePipelineContext = PipelineContext<ViewModeState, ViewModeIntent, ViewModeAction>

class ViewModeContainer(
    private val repository: TaskRepository,
    private val spaceId: String
) : Container<ViewModeState, ViewModeIntent, ViewModeAction> {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val store = store(ViewModeState(), scope) {
        configure {
            name = "ViewModeStore"
        }

        whileSubscribed {
            loadInitialData()
        }

        reduce { intent ->
            when (intent) {
                is ViewModeIntent.LoadInitialData -> loadInitialData()
                is ViewModeIntent.SetActiveViewMode -> setActiveViewMode(intent.viewModeId)
                is ViewModeIntent.SaveViewMode -> saveViewMode(intent.viewMode)
                is ViewModeIntent.DeleteViewMode -> deleteViewMode(intent.viewModeId)
                is ViewModeIntent.LoadViewModeById -> loadViewModeById(intent.viewModeId)
                is ViewModeIntent.FilterTags -> filterTags(intent.searchQuery, intent.excludeTags)
            }
        }
    }

    private suspend fun ViewModePipelineContext.loadInitialData() {
        val viewModes = repository.getAllViewModes(spaceId)
        val activeViewMode = repository.getActiveViewMode(spaceId)
        val allTags = repository.getAllTags(spaceId)

        updateState {
            copy(
                viewModes = viewModes,
                activeViewMode = activeViewMode,
                allTags = allTags
            )
        }
    }

    private suspend fun ViewModePipelineContext.setActiveViewMode(viewModeId: String) {
        repository.setActiveViewMode(spaceId, viewModeId)
        val activeViewMode = repository.getActiveViewMode(spaceId)
        updateState { copy(activeViewMode = activeViewMode) }
    }

    private suspend fun ViewModePipelineContext.saveViewMode(viewMode: ViewMode) {
        repository.saveViewMode(viewMode)
        val viewModes = repository.getAllViewModes(spaceId)
        val activeViewMode = repository.getActiveViewMode(spaceId)
        updateState { copy(viewModes = viewModes, activeViewMode = activeViewMode) }
    }

    private suspend fun ViewModePipelineContext.deleteViewMode(viewModeId: String) {
        repository.deleteViewMode(spaceId, viewModeId)
        val viewModes = repository.getAllViewModes(spaceId)
        val activeViewMode = repository.getActiveViewMode(spaceId)
        updateState { copy(viewModes = viewModes, activeViewMode = activeViewMode) }
    }

    private suspend fun ViewModePipelineContext.loadViewModeById(viewModeId: String) {
        val viewMode = repository.getViewModeById(spaceId, viewModeId)
        updateState { copy(loadedViewMode = viewMode) }
    }

    private suspend fun ViewModePipelineContext.filterTags(searchQuery: String, excludeTags: Set<String>) {
        val filteredTags = repository.filterTags(spaceId, searchQuery, excludeTags)
        updateState { copy(filteredTags = filteredTags) }
    }

    fun copyViewMode(viewMode: ViewMode): ViewMode {
        return viewMode.copy(
            id = generateId(),
            name = "${viewMode.name} (Copy)",
            isBuiltIn = false
        )
    }

    suspend fun getViewModeById(viewModeId: String): ViewMode? {
        return repository.getViewModeById(spaceId, viewModeId)
    }
}
