package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.paging.spacesPagingSource
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CancellationException
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

/**
 * The answer to one export request.
 *
 * Carries the id of the request it answers, because the result of the previous export is still in
 * state when the next one is asked for: without matching them up, pressing Copy a second time
 * copies the JSON from the first press.
 */
data class ExportResult(val requestId: Long, val spaceId: String, val json: String?, val prettyPrint: Boolean)

/** The answer to one import request; see [ExportResult] for why it is identified. */
data class ImportResult(val requestId: Long, val space: Space?)

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
    data class ExportSpaceToJson(
        val requestId: Long,
        val spaceId: String,
        val prettyPrint: Boolean,
    ) : SpaceListIntent
    data class ImportSpaceFromJson(
        val requestId: Long,
        val jsonString: String,
    ) : SpaceListIntent
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
                is SpaceListIntent.ExportSpaceToJson ->
                    exportSpaceToJson(intent.requestId, intent.spaceId, intent.prettyPrint)
                is SpaceListIntent.ImportSpaceFromJson ->
                    importSpaceFromJson(intent.requestId, intent.jsonString)
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
        // This container is app-scoped and outlives every space, so anything cached per space has
        // to go with them. Space ids are handed out as "space-<count>-<prefix>" and are reused
        // after a deletion, so a stale entry does not merely linger — it can be found again under
        // a new space and shown as its own.
        updateState { copy(tagsBySpace = persistentMapOf(), lastExportResult = null, lastImportResult = null) }
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
            // See clearAllData: a deleted space's id can come back on a new one.
            updateState { copy(tagsBySpace = tagsBySpace.remove(prefix), lastExportResult = null) }
            loadSpaces()
        }
        action(SpaceListAction.SpaceDeleted(result))
    }

    private var lastRequestId = 0L

    /** Identifies one export or import request, so its answer can be told from the last one's. */
    fun nextRequestId(): Long = ++lastRequestId

    private suspend fun SpaceListPipelineContext.exportSpaceToJson(
        requestId: Long,
        spaceId: String,
        prettyPrint: Boolean,
    ) {
        val json = repository.exportSpaceToJson(spaceId, prettyPrint)
        updateState { copy(lastExportResult = ExportResult(requestId, spaceId, json, prettyPrint)) }
        action(SpaceListAction.SpaceExported(json))
    }

    private suspend fun SpaceListPipelineContext.importSpaceFromJson(requestId: Long, jsonString: String) {
        // A file can be refused two ways: politely, with null for something that will not decode,
        // and rudely, by throwing — a file naming one task id twice reaches the database, whose
        // primary key rejects it and rolls the import back. Both are the same thing to the user,
        // and only the first was being reported: the throw went to the failure snackbar behind the
        // dialog, while the dialog itself waited for a result that never came, looking as though
        // the button had done nothing at all.
        val space = runCatching { repository.importSpaceFromJson(jsonString) }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
            .getOrNull()
        if (space != null) {
            loadSpaces()
        }
        updateState { copy(lastImportResult = ImportResult(requestId, space)) }
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
