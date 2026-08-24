package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.paging.spacesPagingSource
import com.zhelenskiy.zheduler.zheduler.sync.AuthMode
import com.zhelenskiy.zheduler.zheduler.sync.Outcome
import com.zhelenskiy.zheduler.zheduler.sync.RemoteError
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetup
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupStage
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSetupState
import com.zhelenskiy.zheduler.zheduler.sync.RemoteSpaceLink
import com.zhelenskiy.zheduler.zheduler.sync.ServerAddress
import com.zhelenskiy.zheduler.zheduler.sync.SignedInAccount
import com.zhelenskiy.zheduler.zheduler.sync.SpaceSummary
import com.zhelenskiy.zheduler.zheduler.sync.SpaceSyncService
import com.zhelenskiy.zheduler.zheduler.sync.getOrNull
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import pro.respawn.flowmvi.api.Container
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

/**
 * The name a space goes by on the server.
 *
 * Not the local space id: those are handed out as `space-<count>-<prefix>` and are reused after a
 * deletion, so two different spaces on one account would end up claiming the same remote name and
 * the second would be refused as a conflict it could never resolve.
 */
@OptIn(ExperimentalUuidApi::class)
fun randomRemoteSpaceId(): String = Uuid.random().toString()

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
data class ExportResult(
    val requestId: Long,
    val spaceId: String,
    val json: String?,
    val prettyPrint: Boolean,
    /**
     * Why there is no [json], when the reason is worth repeating.
     *
     * An export fails on a row this build cannot read, and that row is hidden from every list in
     * the app — so "failed" on its own leaves the user with nothing to act on and no way to find
     * what is in the way.
     */
    val failure: String? = null,
)

/** The answer to one import request; see [ExportResult] for why it is identified. */
data class ImportResult(val requestId: Long, val space: Space?)

data class SpaceListState(
    val hasSpaces: Boolean? = null,
    val searchQuery: String = "",
    val searchOptions: PersistentSet<SpaceSearchOption> = persistentSetOf(SpaceSearchOption.Name, SpaceSearchOption.Prefix),
    val showSearchOptions: Boolean = false,
    val tagsBySpace: PersistentMap<String, Set<String>> = persistentMapOf(),
    val lastExportResult: ExportResult? = null,
    val lastImportResult: ImportResult? = null,
    /**
     * Null where this build has no sync wired up, which is what makes the whole server section
     * absent from the new-space dialog rather than present and inert.
     */
    val remoteSetup: RemoteSetupState? = null,
    /** Which spaces have a copy on a server, and at which revision. */
    val remoteLinks: PersistentMap<String, RemoteSpaceLink> = persistentMapOf(),
    /** Spaces with an upload in flight, so the row can say so and the button can be disabled. */
    val uploading: PersistentSet<String> = persistentSetOf(),
    /**
     * The last upload failure per space, kept until the next attempt.
     *
     * Per space rather than one for the screen: two spaces can fail for different reasons, and a
     * single slot would show the second one's message against the first one's row.
     */
    val syncFailures: PersistentMap<String, RemoteError> = persistentMapOf(),
    /** The space whose sign-in-again dialog is open, if one is. */
    val reauthSpaceId: String? = null,
    /**
     * The space whose conflict is being resolved, if one is.
     *
     * A conflict is never resolved by a single button: whichever way it goes, one of the two
     * copies stops being the one on the server, and the user has to be the one who says which.
     * The name travels with the id because the list is paged, so the space in question may not be
     * on any page the screen currently holds.
     */
    val conflict: ConflictPrompt? = null,
) : MVIState

/** The space a conflict dialog is open for. */
data class ConflictPrompt(val spaceId: String, val spaceName: String)

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
    data class AddSpace(
        val name: String,
        val idPrefix: String,
        /** The account to upload the new space to, or null to keep it on this device. */
        val account: SignedInAccount? = null,
    ) : SpaceListIntent
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

    /** The server section of the new-space dialog reporting what the user typed or toggled. */
    data class UpdateRemoteSetup(val setup: RemoteSetupState) : SpaceListIntent

    data object CheckRemoteServer : SpaceListIntent

    data object AuthenticateRemote : SpaceListIntent

    /** Resets the server section, so the next new-space dialog does not open half filled in. */
    data object ClearRemoteSetup : SpaceListIntent

    data class UploadSpace(val spaceId: String) : SpaceListIntent

    /** Opens the dialog that explains a conflict and asks which copy should win. */
    data class BeginConflictResolution(val spaceId: String) : SpaceListIntent

    data object CancelConflictResolution : SpaceListIntent

    /**
     * Uploads over a newer copy on the server, discarding it.
     *
     * Only ever reached from the conflict dialog, where what is being discarded is spelled out.
     */
    data class UploadSpaceOverwriting(val spaceId: String) : SpaceListIntent

    /**
     * Brings the server's copy down as a *new* local space, leaving this one untouched.
     *
     * The non-destructive way out of a conflict: nothing on this device is replaced, and the user
     * can compare the two and delete whichever they do not want.
     */
    data class DownloadRemoteCopy(val spaceId: String) : SpaceListIntent

    data class ForgetRemoteLink(val spaceId: String) : SpaceListIntent

    data class DismissSyncFailure(val spaceId: String) : SpaceListIntent

    /** Opens the sign-in-again dialog for a space whose token the server has stopped honouring. */
    data class BeginReauth(val spaceId: String) : SpaceListIntent

    data object CancelReauth : SpaceListIntent
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
    private val repository: TaskRepository,
    /** Null in a build with no sync; every remote intent then does nothing rather than crashing. */
    private val sync: SpaceSyncService? = null,
    /**
     * Where a new space's remote id comes from.
     *
     * Injected so a test can pin it. Generated on this device rather than by the server so that
     * retrying an upload whose reply was lost sends the same id again — and is refused as a
     * conflict — instead of leaving a second copy on the server.
     */
    private val newRemoteId: () -> String = { randomRemoteSpaceId() },
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ScopedContainer(dispatcher), Container<SpaceListState, SpaceListIntent, SpaceListAction> {

    override val store = store(SpaceListState(remoteSetup = sync?.let { RemoteSetupState() }), scope) {
        reportingFailuresAs("SpaceListStore")

        // Intents are handled one at a time by default, and the sync ones wait on the network for
        // up to thirty seconds. That put every other intent behind them: typing in the search
        // field did nothing until an upload to an unresponsive server gave up, and two spaces
        // could never upload at once however much the per-space `uploading` set implied they could.
        configure { parallelIntents = true }

        whileSubscribed {
            loadSpaces()
            sync?.let { service ->
                service.linksBySpaceId.collect { links ->
                    updateState { copy(remoteLinks = links.toPersistentMap()) }
                }
            }
        }

        reduce { intent ->
            when (intent) {
                is SpaceListIntent.LoadSpaces -> loadSpaces()
                is SpaceListIntent.ClearAllData -> clearAllData()
                is SpaceListIntent.UpdateSearchQuery -> updateState { copy(searchQuery = intent.query) }.also { filterSpaces() }
                is SpaceListIntent.ClearSearchQuery -> updateState { copy(searchQuery = "") }.also { filterSpaces() }
                is SpaceListIntent.ToggleSearchOption -> toggleSearchOption(intent.option)
                is SpaceListIntent.ToggleShowSearchOptions -> updateState { copy(showSearchOptions = !showSearchOptions) }
                is SpaceListIntent.AddSpace -> addSpace(intent.name, intent.idPrefix, intent.account)
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
                is SpaceListIntent.UpdateRemoteSetup -> updateState { copy(remoteSetup = intent.setup) }
                is SpaceListIntent.ClearRemoteSetup ->
                    updateState { copy(remoteSetup = sync?.let { RemoteSetupState() }) }
                is SpaceListIntent.CheckRemoteServer -> checkRemoteServer()
                is SpaceListIntent.AuthenticateRemote -> authenticateRemote()
                is SpaceListIntent.UploadSpace -> uploadSpace(intent.spaceId, overwrite = false)
                is SpaceListIntent.BeginConflictResolution -> {
                    val name = repository.getSpaceById(intent.spaceId)?.name
                    if (name != null) {
                        updateState { copy(conflict = ConflictPrompt(intent.spaceId, name)) }
                    }
                }
                is SpaceListIntent.CancelConflictResolution -> updateState { copy(conflict = null) }
                is SpaceListIntent.UploadSpaceOverwriting -> {
                    updateState { copy(conflict = null) }
                    uploadSpace(intent.spaceId, overwrite = true)
                }
                is SpaceListIntent.DownloadRemoteCopy -> downloadRemoteCopy(intent.spaceId)
                is SpaceListIntent.ForgetRemoteLink -> {
                    sync?.unlink(intent.spaceId)
                    updateState { copy(syncFailures = syncFailures.remove(intent.spaceId)) }
                }
                is SpaceListIntent.DismissSyncFailure ->
                    updateState { copy(syncFailures = syncFailures.remove(intent.spaceId)) }
                is SpaceListIntent.BeginReauth -> beginReauth(intent.spaceId)
                is SpaceListIntent.CancelReauth -> updateState {
                    copy(reauthSpaceId = null, remoteSetup = sync?.let { RemoteSetupState() })
                }
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
        // Every link and every token, not just the ones this screen's state happens to know about:
        // the links arrive by flow, so one written a moment ago may not have landed yet, and a
        // token left behind would still open the user's spaces on the server.
        sync?.forgetEverything()
        updateState {
            copy(
                tagsBySpace = persistentMapOf(),
                lastExportResult = null,
                lastImportResult = null,
                syncFailures = persistentMapOf(),
                remoteLinks = persistentMapOf(),
                conflict = null,
                reauthSpaceId = null,
                // As in deleteSpace: releases every claim, so nothing still in flight writes a
                // result back against a space that has just been erased.
                uploading = persistentSetOf(),
            )
        }
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

    private suspend fun SpaceListPipelineContext.addSpace(
        name: String,
        idPrefix: String,
        account: SignedInAccount?,
    ) {
        val space = repository.createSpace(name, idPrefix)
        if (space != null) {
            loadSpaces()
            // The space exists locally either way. A first upload that fails leaves it linked to
            // nothing and reported in `syncFailures`, which is recoverable from the list; refusing
            // to create it would instead throw away everything the user typed.
            if (account != null && sync != null) {
                uploadNewSpace(space.id, account)
            }
        }
        action(SpaceListAction.SpaceAdded(space))
    }

    private suspend fun SpaceListPipelineContext.uploadNewSpace(
        spaceId: String,
        account: SignedInAccount,
    ) {
        val service = sync ?: return
        updateState { copy(uploading = uploading.adding(spaceId)) }
        val outcome = service.linkAndUpload(spaceId, account, newRemoteId())
        updateState {
            // See uploadSpace: a claim that has been released means the space is gone.
            if (spaceId !in uploading) {
                this
            } else {
                copy(
                    uploading = uploading.removing(spaceId),
                    syncFailures = when (outcome) {
                        is Outcome.Success -> syncFailures.remove(spaceId)
                        is Outcome.Failure -> syncFailures.putting(spaceId, outcome.error)
                    },
                )
            }
        }
    }

    private suspend fun SpaceListPipelineContext.checkRemoteServer() {
        val service = sync ?: return
        val setup = peek { remoteSetup } ?: return
        when (val address = RemoteSetup.parseAddress(setup)) {
            is Outcome.Failure ->
                updateState { copy(remoteSetup = RemoteSetup.checkFailed(setup, address.error)) }

            is Outcome.Success -> {
                updateState { copy(remoteSetup = RemoteSetup.checking(setup)) }
                val checked = service.checkServer(address.value)
                updateState {
                    // Read back out of state rather than reusing `setup`: the user can keep typing
                    // while the request is in flight, and writing the old text back would undo it.
                    val current = remoteSetup ?: return@updateState this
                    copy(
                        remoteSetup = when (checked) {
                            is Outcome.Success -> RemoteSetup.checkSucceeded(current, address.value)
                            is Outcome.Failure -> RemoteSetup.checkFailed(current, checked.error)
                        }
                    )
                }
            }
        }
    }

    private suspend fun SpaceListPipelineContext.authenticateRemote() {
        val service = sync ?: return
        val setup = peek { remoteSetup } ?: return
        val stage = setup.stage as? RemoteSetupStage.Authenticating ?: return
        // Claimed the same way an upload is. Two taps before the button disables itself would
        // otherwise mint two tokens, and only the second would be filed — leaving the first live
        // on the server until it expires, with nothing here able to revoke it.
        if (stage.busy) return

        updateState { copy(remoteSetup = RemoteSetup.authenticating(setup)) }
        val result = when (stage.mode) {
            AuthMode.SignIn -> service.signIn(stage.address, setup.username, setup.password)
            AuthMode.SignUp -> service.signUp(stage.address, setup.username, setup.password)
        }
        updateState {
            val current = remoteSetup ?: return@updateState this
            copy(
                remoteSetup = when (result) {
                    is Outcome.Success -> RemoteSetup.authenticated(current, result.value)
                    is Outcome.Failure -> RemoteSetup.authenticationFailed(current, result.error)
                }
            )
        }

        // A sign-in that was opened to rescue a stuck space finishes the job: the dialog closes
        // and the upload that failed is tried again, rather than leaving the user to find the
        // button themselves and wonder whether signing in did anything.
        if (result is Outcome.Success) {
            val stuckSpace = peek { reauthSpaceId }
            if (stuckSpace != null) {
                updateState { copy(reauthSpaceId = null, remoteSetup = RemoteSetupState()) }
                uploadSpace(stuckSpace, overwrite = false)
            }
        }
    }

    /**
     * Brings the server's copy down beside the local one.
     *
     * The conflict is only cleared once the copy has arrived: if the download fails there are
     * still two versions to choose between, and closing the dialog would leave the user with no
     * way back to that choice.
     */
    private suspend fun SpaceListPipelineContext.downloadRemoteCopy(spaceId: String) {
        val service = sync ?: return
        val link = peek { remoteLinks[spaceId] } ?: return
        // Claimed the same way an upload is, and for the same reason: two taps before the button
        // disables itself would otherwise import the server's copy twice, leaving the user with
        // two identical new spaces out of one conflict.
        var started = false
        updateState {
            if (spaceId in uploading) this else {
                started = true
                copy(uploading = uploading.adding(spaceId))
            }
        }
        if (!started) return
        val outcome = service.download(link.account, link.remoteSpaceId)
        updateState {
            // See uploadSpace: a claim that has been released means the space is gone.
            if (spaceId !in uploading) {
                this
            } else {
                copy(
                    uploading = uploading.removing(spaceId),
                    conflict = if (outcome is Outcome.Success) null else conflict,
                    syncFailures = when (outcome) {
                        is Outcome.Success -> syncFailures.remove(spaceId)
                        is Outcome.Failure -> syncFailures.putting(spaceId, outcome.error)
                    },
                )
            }
        }
        if (outcome is Outcome.Success) loadSpaces()
    }

    private suspend fun SpaceListPipelineContext.beginReauth(spaceId: String) {
        val link = peek { remoteLinks[spaceId] } ?: return
        val address = ServerAddress.parse(link.account.serverUrl).getOrNull() ?: return
        updateState {
            copy(
                reauthSpaceId = spaceId,
                remoteSetup = RemoteSetupState(
                    // The username is filled in and the address is fixed: signing in again is
                    // about the password, and letting either be retyped here would let a space
                    // change account or server without ever saying so.
                    stage = RemoteSetupStage.Authenticating(address, AuthMode.SignIn),
                    addressText = link.account.serverUrl,
                    username = link.account.username,
                ),
            )
        }
    }

    private suspend fun SpaceListPipelineContext.uploadSpace(spaceId: String, overwrite: Boolean) {
        val service = sync ?: return
        // Claimed and checked in one state transaction, which the store serialises. Two taps
        // before the button had a frame to disable itself would otherwise run two uploads from
        // the same revision: the second loses to the first and reports a conflict against the
        // user's own copy, offering to discard the upload they had just made.
        var started = false
        updateState {
            if (spaceId in uploading) {
                this
            } else {
                started = true
                copy(uploading = uploading.adding(spaceId), syncFailures = syncFailures.remove(spaceId))
            }
        }
        if (!started) return
        val outcome = if (overwrite) service.uploadOverwriting(spaceId) else service.upload(spaceId)
        updateState {
            // The claim is gone if the space was deleted, or everything erased, while this was in
            // flight. Writing the result anyway would leave a failure filed against an id that is
            // back in circulation, to be shown against whichever space next takes it.
            if (spaceId !in uploading) {
                this
            } else {
                copy(
                    uploading = uploading.removing(spaceId),
                    syncFailures = when (outcome) {
                        is Outcome.Success -> syncFailures.remove(spaceId)
                        is Outcome.Failure -> syncFailures.putting(spaceId, outcome.error)
                    },
                )
            }
        }
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
            // See clearAllData: a deleted space's id can come back on a new one. That is also why
            // the link goes — left behind, it would reattach itself to whichever space next takes
            // the id, and that space's first upload would overwrite the deleted one's backup.
            sync?.unlink(prefix)
            updateState {
                copy(
                    tagsBySpace = tagsBySpace.remove(prefix),
                    lastExportResult = null,
                    syncFailures = syncFailures.remove(prefix),
                    // Releases the claim, so an upload still in flight for this space knows not
                    // to write its result against an id that is now back in circulation.
                    uploading = uploading.removing(prefix),
                    conflict = conflict?.takeUnless { it.spaceId == prefix },
                )
            }
            loadSpaces()
        }
        action(SpaceListAction.SpaceDeleted(result))
    }

    /**
     * Copies one value out of the state.
     *
     * `withState` hands the state to a block and returns nothing, and the property that would
     * return it directly is marked delicate because it skips the store's locking. Taking a copy
     * under the lock and acting on it afterwards is both safe and the shape wanted here: every
     * caller goes on to make a network request, which must not be made while the lock is held.
     */
    private suspend fun <T> SpaceListPipelineContext.peek(read: SpaceListState.() -> T): T {
        var captured: Any? = null
        withState { captured = read() }
        @Suppress("UNCHECKED_CAST")
        return captured as T
    }

    /**
     * How the server describes its copy of a space.
     *
     * Not an intent: this is a read the conflict dialog makes while it is on screen, and its
     * answer belongs to that dialog rather than to the list's state. It goes through
     * `rememberRemoteContent`, which is what gives it a spinner and a retry.
     */
    suspend fun describeRemoteCopy(spaceId: String): Outcome<SpaceSummary> =
        sync?.remoteSummaryOf(spaceId)
            ?: Outcome.Failure(RemoteError.Malformed("this build has no sync"))

    private var lastRequestId = 0L

    /** Identifies one export or import request, so its answer can be told from the last one's. */
    fun nextRequestId(): Long = ++lastRequestId

    private suspend fun SpaceListPipelineContext.exportSpaceToJson(
        requestId: Long,
        spaceId: String,
        prettyPrint: Boolean,
    ) {
        // As with import: a refusal has to reach the dialog waiting for it, not the snackbar
        // behind it. An unreadable row fails the export rather than being dropped from the file.
        val attempt = runCatching { repository.exportSpaceToJson(spaceId, prettyPrint) }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
        val json = attempt.getOrNull()
        val failure = attempt.exceptionOrNull()?.message
        updateState {
            copy(lastExportResult = ExportResult(requestId, spaceId, json, prettyPrint, failure))
        }
        action(SpaceListAction.SpaceExported(json))
    }

    private suspend fun SpaceListPipelineContext.importSpaceFromJson(requestId: Long, jsonString: String) {
        // A file is refused two ways: politely with null, and rudely by throwing — one naming a
        // task id twice reaches the database, whose primary key rolls the import back. Only the
        // first was reported; the throw went to the snackbar behind the dialog, which waited on a
        // result that never came.
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
