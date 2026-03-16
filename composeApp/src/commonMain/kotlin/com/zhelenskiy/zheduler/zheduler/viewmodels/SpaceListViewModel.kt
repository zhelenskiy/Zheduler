package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.TaskRepository
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

// Search options for space list
enum class SpaceSearchOption {
    Name, Prefix;

    val displayName: String get() = when (this) {
        Name -> "Name"
        Prefix -> "ID Prefix"
    }
}

data class SpaceListState(
    val hasSpaces: Boolean? = null,
    val searchQuery: String = "",
    val searchOptions: Set<SpaceSearchOption> = setOf(SpaceSearchOption.Name, SpaceSearchOption.Prefix),
    val showSearchOptions: Boolean = false,
    val filteredSpaces: List<Space>? = null
) : MVIState

sealed interface SpaceListIntent : MVIIntent {
    data object LoadSpaces : SpaceListIntent
    data object ClearAllData : SpaceListIntent
    data class UpdateSearchQuery(val query: String) : SpaceListIntent
    data object ClearSearchQuery : SpaceListIntent
    data class ToggleSearchOption(val option: SpaceSearchOption) : SpaceListIntent
    data object ToggleShowSearchOptions : SpaceListIntent
    data class AddSpace(val name: String, val idPrefix: String) : SpaceListIntent
    data class UpdateSpace(val spaceId: String, val newName: String) : SpaceListIntent
    data class DeleteSpace(val spaceId: String) : SpaceListIntent
    data class ExportSpaceToJson(val spaceId: String, val prettyPrint: Boolean) : SpaceListIntent
    data class ImportSpaceFromJson(val jsonString: String) : SpaceListIntent
}

sealed interface SpaceListAction : MVIAction {
    data class SpaceAdded(val space: Space?) : SpaceListAction
    data class SpaceUpdated(val success: Boolean) : SpaceListAction
    data class SpaceDeleted(val success: Boolean) : SpaceListAction
    data class SpaceExported(val json: String?) : SpaceListAction
    data class SpaceImported(val space: Space?) : SpaceListAction
}

class SpaceListContainer(
    private val repository: TaskRepository
) : Container<SpaceListState, SpaceListIntent, SpaceListAction> {

    // Direct repository access for operations that need return values
    suspend fun exportSpaceToJson(spaceId: String, prettyPrint: Boolean): String? =
        repository.exportSpaceToJson(spaceId, prettyPrint)

    suspend fun importSpaceFromJson(jsonString: String): Space? {
        val space = repository.importSpaceFromJson(jsonString)
        if (space != null) {
            store.intent(SpaceListIntent.LoadSpaces)
        }
        return space
    }

    // Tag management for specific spaces
    suspend fun getTagsForSpace(spaceId: String): Set<String> =
        repository.getAllTags(spaceId)

    suspend fun addTagToSpace(spaceId: String, tag: String): Boolean =
        repository.addTag(spaceId, tag)

    suspend fun deleteTagFromSpace(spaceId: String, tag: String): Boolean =
        repository.deleteTag(spaceId, tag)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val store = store(SpaceListState(), scope) {
        configure {
            name = "SpaceListStore"
        }

        whileSubscribed {
            loadSpaces()
        }

        reduce { intent ->
            when (intent) {
                is SpaceListIntent.LoadSpaces -> loadSpaces()
                is SpaceListIntent.ClearAllData -> clearAllData()
                is SpaceListIntent.UpdateSearchQuery -> updateState { copy(searchQuery = intent.query) }.also { filterSpaces() }
                is SpaceListIntent.ClearSearchQuery -> updateState { copy(searchQuery = "") }.also { filterSpaces() }
                is SpaceListIntent.ToggleSearchOption -> toggleSearchOption(intent.option)
                is SpaceListIntent.ToggleShowSearchOptions -> updateState { copy(showSearchOptions = !showSearchOptions) }
                is SpaceListIntent.AddSpace -> addSpace(intent.name, intent.idPrefix)
                is SpaceListIntent.UpdateSpace -> updateSpace(intent.spaceId, intent.newName)
                is SpaceListIntent.DeleteSpace -> deleteSpace(intent.spaceId)
                is SpaceListIntent.ExportSpaceToJson -> exportSpaceToJson(intent.spaceId, intent.prettyPrint)
                is SpaceListIntent.ImportSpaceFromJson -> importSpaceFromJson(intent.jsonString)
            }
        }
    }

    private suspend fun PipelineContext<SpaceListState, SpaceListIntent, SpaceListAction>.loadSpaces() {
        val hasSpaces = repository.hasSpaces()
        updateState { copy(hasSpaces = hasSpaces) }
        filterSpaces()
    }

    private suspend fun PipelineContext<SpaceListState, SpaceListIntent, SpaceListAction>.clearAllData() {
        repository.clearAllData()
        loadSpaces()
    }

    private suspend fun PipelineContext<SpaceListState, SpaceListIntent, SpaceListAction>.toggleSearchOption(option: SpaceSearchOption) {
        updateState {
            val newOptions = if (option in searchOptions && searchOptions.size > 1) {
                searchOptions - option
            } else {
                searchOptions + option
            }
            copy(searchOptions = newOptions)
        }
        filterSpaces()
    }

    private suspend fun PipelineContext<SpaceListState, SpaceListIntent, SpaceListAction>.filterSpaces() {
        withState {
            val filteredSpaces = repository.filterSpaces(
                query = searchQuery,
                searchInName = SpaceSearchOption.Name in searchOptions,
                searchInPrefix = SpaceSearchOption.Prefix in searchOptions
            )
            updateState { copy(filteredSpaces = filteredSpaces) }
        }
    }

    private suspend fun PipelineContext<SpaceListState, SpaceListIntent, SpaceListAction>.addSpace(name: String, idPrefix: String) {
        val space = repository.createSpace(name, idPrefix)
        if (space != null) {
            loadSpaces()
        }
        action(SpaceListAction.SpaceAdded(space))
    }

    private suspend fun PipelineContext<SpaceListState, SpaceListIntent, SpaceListAction>.updateSpace(oldPrefix: String, newName: String) {
        val result = repository.updateSpaceName(oldPrefix, newName)
        if (result) {
            loadSpaces()
        }
        action(SpaceListAction.SpaceUpdated(result))
    }

    private suspend fun PipelineContext<SpaceListState, SpaceListIntent, SpaceListAction>.deleteSpace(prefix: String) {
        val result = repository.deleteSpace(prefix)
        if (result) {
            loadSpaces()
        }
        action(SpaceListAction.SpaceDeleted(result))
    }

    private suspend fun PipelineContext<SpaceListState, SpaceListIntent, SpaceListAction>.exportSpaceToJson(spaceId: String, prettyPrint: Boolean) {
        val json = repository.exportSpaceToJson(spaceId, prettyPrint)
        action(SpaceListAction.SpaceExported(json))
    }

    private suspend fun PipelineContext<SpaceListState, SpaceListIntent, SpaceListAction>.importSpaceFromJson(jsonString: String) {
        val space = repository.importSpaceFromJson(jsonString)
        if (space != null) {
            loadSpaces()
        }
        action(SpaceListAction.SpaceImported(space))
    }
}
