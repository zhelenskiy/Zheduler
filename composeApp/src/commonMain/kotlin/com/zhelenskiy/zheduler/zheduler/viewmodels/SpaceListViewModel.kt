package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.paging.spacesPagingSource
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.Flow
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

data class ExportResult(val spaceId: String, val json: String?, val prettyPrint: Boolean)
data class ImportResult(val space: Space?)

data class SpaceListState(
    val hasSpaces: Boolean? = null,
    val searchQuery: String = "",
    val searchOptions: PersistentSet<SpaceSearchOption> = persistentSetOf(SpaceSearchOption.Name, SpaceSearchOption.Prefix),
    val showSearchOptions: Boolean = false,
    val tagsBySpace: PersistentMap<String, Set<String>> = persistentMapOf(),
    val lastExportResult: ExportResult? = null,
    val lastImportResult: ImportResult? = null
) : MVIState

/** What the space list is currently searching for. */
private data class SpaceQuery(
    val query: String = "",
    val searchInName: Boolean = true,
    val searchInPrefix: Boolean = true,
)

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
    data class LoadTagsForSpace(val spaceId: String) : SpaceListIntent
    data class AddTagToSpace(val spaceId: String, val tag: String) : SpaceListIntent
    data class DeleteTagFromSpace(val spaceId: String, val tag: String) : SpaceListIntent
    data object ClearExportResult : SpaceListIntent
    data object ClearImportResult : SpaceListIntent
}

sealed interface SpaceListAction : MVIAction {
    data class SpaceAdded(val space: Space?) : SpaceListAction
    data class SpaceUpdated(val success: Boolean) : SpaceListAction
    data class SpaceDeleted(val success: Boolean) : SpaceListAction
    data class SpaceExported(val json: String?) : SpaceListAction
    data class SpaceImported(val space: Space?) : SpaceListAction
}

private typealias SpaceListPipelineContext = PipelineContext<SpaceListState, SpaceListIntent, SpaceListAction>

class SpaceListContainer(
    private val repository: TaskRepository
) : ScopedContainer(), Container<SpaceListState, SpaceListIntent, SpaceListAction> {

    override val store = store(SpaceListState(), scope) {
        reportingFailuresAs("SpaceListStore")

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
                is SpaceListIntent.LoadTagsForSpace -> loadTagsForSpace(intent.spaceId)
                is SpaceListIntent.AddTagToSpace -> addTagToSpace(intent.spaceId, intent.tag)
                is SpaceListIntent.DeleteTagFromSpace -> deleteTagFromSpace(intent.spaceId, intent.tag)
                is SpaceListIntent.ClearExportResult -> updateState { copy(lastExportResult = null) }
                is SpaceListIntent.ClearImportResult -> updateState { copy(lastImportResult = null) }
            }
        }
    }

    private val spaceSearch = PagedQuery(
        scope = scope,
        initialQuery = SpaceQuery(),
        changes = repository.changes,
    ) { query ->
        repository.spacesPagingSource(query.query, query.searchInName, query.searchInPrefix)
    }

    /** The space list itself, one page at a time. */
    val spaces: Flow<PagingData<Space>> get() = spaceSearch.pages

    private suspend fun SpaceListPipelineContext.loadSpaces() {
        val hasSpaces = repository.hasSpaces()
        updateState { copy(hasSpaces = hasSpaces) }
        filterSpaces()
    }

    private suspend fun SpaceListPipelineContext.clearAllData() {
        repository.clearAllData()
        loadSpaces()
    }

    private suspend fun SpaceListPipelineContext.toggleSearchOption(option: SpaceSearchOption) {
        updateState {
            val newOptions = if (option in searchOptions && searchOptions.size > 1) {
                searchOptions.removing(option)
            } else {
                searchOptions.adding(option)
            }
            copy(searchOptions = newOptions)
        }
        filterSpaces()
    }

    private suspend fun SpaceListPipelineContext.filterSpaces() {
        withState {
            spaceSearch.setQuery(
                SpaceQuery(
                    query = searchQuery,
                    searchInName = SpaceSearchOption.Name in searchOptions,
                    searchInPrefix = SpaceSearchOption.Prefix in searchOptions,
                )
            )
        }
    }

    private suspend fun SpaceListPipelineContext.addSpace(name: String, idPrefix: String) {
        val space = repository.createSpace(name, idPrefix)
        if (space != null) {
            loadSpaces()
        }
        action(SpaceListAction.SpaceAdded(space))
    }

    private suspend fun SpaceListPipelineContext.updateSpace(oldPrefix: String, newName: String) {
        val result = repository.updateSpaceName(oldPrefix, newName)
        if (result) {
            loadSpaces()
        }
        action(SpaceListAction.SpaceUpdated(result))
    }

    private suspend fun SpaceListPipelineContext.deleteSpace(prefix: String) {
        val result = repository.deleteSpace(prefix)
        if (result) {
            loadSpaces()
        }
        action(SpaceListAction.SpaceDeleted(result))
    }

    private suspend fun SpaceListPipelineContext.exportSpaceToJson(spaceId: String, prettyPrint: Boolean) {
        val json = repository.exportSpaceToJson(spaceId, prettyPrint)
        updateState { copy(lastExportResult = ExportResult(spaceId, json, prettyPrint)) }
        action(SpaceListAction.SpaceExported(json))
    }

    private suspend fun SpaceListPipelineContext.importSpaceFromJson(jsonString: String) {
        val space = repository.importSpaceFromJson(jsonString)
        if (space != null) {
            loadSpaces()
        }
        updateState { copy(lastImportResult = ImportResult(space)) }
        action(SpaceListAction.SpaceImported(space))
    }

    private suspend fun SpaceListPipelineContext.loadTagsForSpace(spaceId: String) {
        val tags = repository.getAllTags(spaceId)
        updateState { copy(tagsBySpace = tagsBySpace.putting(spaceId, tags)) }
    }

    private suspend fun SpaceListPipelineContext.addTagToSpace(spaceId: String, tag: String) {
        if (repository.addTag(spaceId, tag)) {
            loadTagsForSpace(spaceId)
        }
    }

    private suspend fun SpaceListPipelineContext.deleteTagFromSpace(spaceId: String, tag: String) {
        if (repository.deleteTag(spaceId, tag)) {
            loadTagsForSpace(spaceId)
        }
    }
}
