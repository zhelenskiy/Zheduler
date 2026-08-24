package com.zhelenskiy.zheduler.zheduler.viewmodels

import androidx.paging.PagingData
import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.paging.spacesPagingSource
import com.zhelenskiy.zheduler.zheduler.sync.ApiErrorCode
import com.zhelenskiy.zheduler.zheduler.sync.AuthMode
import com.zhelenskiy.zheduler.zheduler.sync.CloudSpaceStatus
import com.zhelenskiy.zheduler.zheduler.sync.CloudSpaces
import com.zhelenskiy.zheduler.zheduler.sync.KnownServer
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
import com.zhelenskiy.zheduler.zheduler.sync.onFailure
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    /**
     * The space waiting to be sent to a server, if the user is choosing one.
     *
     * Separate from [reauthSpaceId] because the two ask different questions of the same form:
     * signing in again is about a space that already belongs somewhere, and this is about one that
     * does not belong anywhere yet.
     */
    val putOnServer: PutOnServerPrompt? = null,
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
    /**
     * Where each cloud space stands with its server.
     *
     * A space absent from here belongs to this device alone. What the list shows for a cloud one
     * is this and not the link: a link says which server holds it, this says whether the app has
     * heard from that server.
     */
    val cloudStatus: PersistentMap<String, CloudSpaceStatus> = persistentMapOf(),
    /** The servers this device already knows, offered so an address is typed once. */
    val knownServers: PersistentList<KnownServer> = persistentListOf(),
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

/** A space waiting to be sent to a server, and the name to call it in the page that asks. */
data class PutOnServerPrompt(val spaceId: String, val spaceName: String)

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
    /**
     * Changes the server setup, by describing the change rather than the result.
     *
     * A whole replacement state was what this used to carry, and with intents running in parallel
     * a keystroke could land after something else had moved the setup on — putting the old stage
     * and the old seed token back, which reset the dialog's fields to what they held before. An
     * edit applied to whatever the state is when it arrives cannot do that: it only ever touches
     * what it was written to touch.
     */
    data class EditRemoteSetup(val edit: (RemoteSetupState) -> RemoteSetupState) : SpaceListIntent

    data class CheckRemoteServer(val addressText: String) : SpaceListIntent

    data class AuthenticateRemote(val username: String, val password: String) : SpaceListIntent

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

    /** Asks the server again what it has. What the offline banner's retry does. */
    data class RefreshCloudSpace(val spaceId: String) : SpaceListIntent

    /**
     * Deletes a space here *and* on its server.
     *
     * Separate from [DeleteSpace], which only ever removes the local copy: for a space whose truth
     * is on a server those are different acts, and one of them cannot be undone from another
     * device.
     */
    data class DeleteSpaceEverywhere(val spaceId: String) : SpaceListIntent

    /** Signs out of the account a space uses, leaving the space and the server's copy alone. */
    data class SignOutOfSpace(val spaceId: String) : SpaceListIntent

    /** Opens the page that sends a space this device already has to a server. */
    data class BeginPutOnServer(val spaceId: String, val spaceName: String) : SpaceListIntent

    /** Closes it without uploading anything. */
    data object CancelPutOnServer : SpaceListIntent

    /** Uploads a space that already exists, and hands it to [account]'s server from then on. */
    data class PutOnServer(val spaceId: String, val account: SignedInAccount) : SpaceListIntent

    /** Fills the new-space dialog in from a server this device already knows. */
    data class UseKnownServer(val server: KnownServer) : SpaceListIntent
}

sealed interface SpaceListAction : MVIAction {
    data class SpaceAdded(val space: Space?) : SpaceListAction
    data class SpaceUpdated(val success: Boolean) : SpaceListAction
    data class SpaceDeleted(val success: Boolean) : SpaceListAction
    data class SpaceExported(val json: String?) : SpaceListAction
    data class SpaceImported(val space: Space?) : SpaceListAction

    /**
     * Something worth a sentence and nothing more.
     *
     * For what an action *did*, as opposed to where a space now stands. A space's standing is
     * already written on its row, and repeating it there as a second red box gave the user two
     * "Sign in again" buttons for one problem — two things to do where there was one.
     */
    data class Announce(val message: String) : SpaceListAction
}

private typealias SpaceListPipelineContext = PipelineContext<SpaceListState, SpaceListIntent, SpaceListAction>

class SpaceListContainer(
    private val repository: TaskRepository,
    /** Null in a build with no sync; every remote intent then does nothing rather than crashing. */
    private val sync: SpaceSyncService? = null,
    /** Keeps cloud spaces in step with their servers. Null wherever [sync] is. */
    private val cloud: CloudSpaces? = null,
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
            if (sync == null) return@whileSubscribed

            // Scoped to the subscription: three things to follow at once, all of which stop
            // mattering the moment nobody is looking at the list.
            coroutineScope {
                // Asks every server what it has, before anything is shown as up to date. A space
                // whose server has moved on is out of step from the moment this screen opens, and
                // the list is where that has to be visible.
                launch { cloud?.refreshAll() }
                launch {
                    cloud?.all?.collect { statuses ->
                        updateState { copy(cloudStatus = statuses.toPersistentMap()) }
                    }
                }
                launch {
                    sync.knownServers.collect { servers ->
                        updateState { copy(knownServers = servers.toPersistentList()) }
                    }
                }
                launch {
                    sync.linksBySpaceId.collect { links ->
                        updateState { copy(remoteLinks = links.toPersistentMap()) }
                    }
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
                is SpaceListIntent.EditRemoteSetup ->
                    updateState { copy(remoteSetup = remoteSetup?.let(intent.edit)) }
                is SpaceListIntent.ClearRemoteSetup ->
                    updateState { copy(remoteSetup = sync?.let { RemoteSetupState() }) }
                is SpaceListIntent.CheckRemoteServer -> checkRemoteServer(intent.addressText)
                is SpaceListIntent.AuthenticateRemote ->
                    authenticateRemote(intent.username, intent.password)
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
                    // The status goes with the link. Left behind, a space that is now this
                    // device's own would keep reporting the server it no longer answers to.
                    cloud?.forget(intent.spaceId)
                    updateState { copy(syncFailures = syncFailures.remove(intent.spaceId)) }
                }
                is SpaceListIntent.DismissSyncFailure ->
                    updateState { copy(syncFailures = syncFailures.remove(intent.spaceId)) }
                is SpaceListIntent.RefreshCloudSpace -> cloud?.refresh(intent.spaceId)
                is SpaceListIntent.DeleteSpaceEverywhere -> deleteEverywhere(intent.spaceId)
                is SpaceListIntent.SignOutOfSpace -> signOutOfSpace(intent.spaceId)
                is SpaceListIntent.BeginPutOnServer -> updateState {
                    copy(
                        putOnServer = PutOnServerPrompt(intent.spaceId, intent.spaceName),
                        // Straight past the switch: opening this page is the decision it asks
                        // about, so the address field is what should be waiting.
                        remoteSetup = RemoteSetup.turnedOn(RemoteSetupState()),
                    )
                }
                is SpaceListIntent.CancelPutOnServer -> updateState {
                    copy(putOnServer = null, remoteSetup = sync?.let { RemoteSetupState() })
                }
                is SpaceListIntent.PutOnServer -> putOnServer(intent.spaceId, intent.account)
                is SpaceListIntent.UseKnownServer -> useKnownServer(intent.server)
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
        cloud?.forgetAll()
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
    ): Boolean {
        val service = sync ?: return false
        // Claimed and checked in one state transaction, as an ordinary upload is. This used to be
        // a bare claim, which was safe while the only caller was a dialog that closed itself after
        // one press. The page that sends an *existing* space stays open while the upload runs, and
        // a second press would mint a second remote id and leave two copies on the server — the
        // first of them orphaned, with this device attached to the second.
        var started = false
        updateState {
            if (spaceId in uploading) this else {
                started = true
                copy(uploading = uploading.adding(spaceId))
            }
        }
        if (!started) return false
        val remoteSpaceId = newRemoteId()
        // Through the cloud layer where there is one, so the space is claimed before a byte leaves
        // and nothing else can start a second upload against the same remote id while this one is
        // on the wire. A first upload can take half a minute, and opening the space during it used
        // to begin another — refused as a conflict, leaving the user asked to choose between two
        // identical copies of a space they had only just made.
        val outcome = cloud?.putOnServer(spaceId, account, remoteSpaceId)
            ?: service.linkAndUpload(spaceId, account, remoteSpaceId)
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
        return true
    }

    /**
     * Checks the address the field currently holds.
     *
     * The text is carried in rather than read out of state, because state is not where the field
     * keeps it: the box owns what is in it so that a fast typist's caret does not depend on how
     * quickly this store agrees, and the edits that do arrive here run in parallel and so may
     * arrive in any order. What was on screen when the button was pressed is the only text that
     * can be trusted to be the one the user meant.
     */
    private suspend fun SpaceListPipelineContext.checkRemoteServer(addressText: String) {
        val service = sync ?: return
        val setup = (peek { remoteSetup } ?: return).copy(addressText = addressText)
        when (val address = RemoteSetup.parseAddress(setup)) {
            is Outcome.Failure ->
                updateState { copy(remoteSetup = RemoteSetup.checkFailed(setup, address.error)) }

            is Outcome.Success -> {
                updateState { copy(remoteSetup = RemoteSetup.checking(setup)) }
                val checked = service.checkServer(address.value)
                updateState {
                    // Read back out of state rather than reusing `setup`: the stage can have moved
                    // on while the request was in flight, and writing the old one back would undo it.
                    val current = (remoteSetup ?: return@updateState this)
                        .copy(addressText = addressText)
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

    /** Signs in with what was in the boxes when the button was pressed. See [checkRemoteServer]. */
    private suspend fun SpaceListPipelineContext.authenticateRemote(
        username: String,
        password: String,
    ) {
        val service = sync ?: return
        val setup = (peek { remoteSetup } ?: return).copy(username = username, password = password)
        val stage = setup.stage as? RemoteSetupStage.Authenticating ?: return
        // Claimed the same way an upload is. Two taps before the button disables itself would
        // otherwise mint two tokens, and only the second would be filed — leaving the first live
        // on the server until it expires, with nothing here able to revoke it.
        if (stage.busy) return

        updateState { copy(remoteSetup = RemoteSetup.authenticating(setup)) }
        val result = when (stage.mode) {
            AuthMode.SignIn -> service.signIn(stage.address, username, password)
            AuthMode.SignUp -> service.signUp(stage.address, username, password)
        }
        updateState {
            val current = (remoteSetup ?: return@updateState this)
                .copy(username = username, password = password)
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
        // Unlinked on purpose: the space it is being downloaded beside already owns that link,
        // and the dialog promises this copy is one to keep and compare, not a second live one.
        val outcome = service.download(link.account, link.remoteSpaceId, link = false)
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

    /**
     * Removes a cloud space from its server and then from here.
     *
     * The server first, and only then the local copy: if the deletion is refused — a conflict, an
     * expired session — the space is still here to try again from. The other order would leave the
     * user with nothing locally and a copy on the server they can no longer reach from this app.
     */
    private suspend fun SpaceListPipelineContext.deleteEverywhere(spaceId: String) {
        val service = sync ?: return
        updateState { copy(uploading = uploading.adding(spaceId)) }
        val removed = service.deleteRemote(spaceId)
        updateState {
            copy(
                uploading = uploading.removing(spaceId),
                syncFailures = when (removed) {
                    is Outcome.Success -> syncFailures.remove(spaceId)
                    is Outcome.Failure -> syncFailures.putting(spaceId, removed.error)
                },
            )
        }
        if (removed is Outcome.Success) {
            cloud?.forget(spaceId)
            deleteSpace(spaceId)
        }
    }

    /**
     * Signs out of the account a space belongs to.
     *
     * The space and the server's copy both stay. What changes is that this device no longer holds
     * a token for it, so the space goes read-only until somebody signs in again — which is the
     * honest state, not a failure.
     */
    /**
     * Sends a space this device already has to a server.
     *
     * The same upload a space created on a server gets, and deliberately so: from here on there is
     * no difference between the two, and there should be no second way for one of them to behave.
     * The page closes only once the upload has landed — a page that shut on failure would leave
     * the user believing the space was up there.
     */
    private suspend fun SpaceListPipelineContext.putOnServer(
        spaceId: String,
        account: SignedInAccount,
    ) {
        val service = sync ?: return
        // Already on its way. Said nothing about, because the page is already saying it: a press
        // that beat the button disabling itself must not be answered with a complaint about a link
        // that its own upload wrote a moment ago.
        if (peek { spaceId in uploading }) return

        val existing = service.linkFor(spaceId)
        // Refused here and not only in the list: the icon that opens this page is drawn from a
        // status that starts out empty, so for the first frames after launch every space looks
        // like one that belongs to this device alone. Linking a second time would overwrite the
        // link, orphan the copy the user's other devices share, and leave this one attached to a
        // duplicate nobody else can see.
        //
        // Judged on whether the server ever took it, not on the link existing. A link is written
        // before the first upload is attempted and kept when it fails — which is precisely the
        // state this page stays open in — so refusing on the link alone would answer Try again
        // with "that space is already on a server", about a space that is not.
        if (existing?.isUploaded == true) {
            updateState { copy(putOnServer = null) }
            action(SpaceListAction.Announce("That space is already kept on a server."))
            return
        }
        // Read before the upload: the user can close this page and open another one for a
        // different space while this is in flight, and the name is wanted for *this* space.
        val name = peek { putOnServer }
            ?.takeIf { it.spaceId == spaceId }
            ?.spaceName
            ?: repository.getSpaceById(spaceId)?.name
        // Only where it is the *same* account. The page stays open on failure so the user can put
        // things right, and one of the things they can put right is the server: the address field
        // is still there, and signing in somewhere else is the obvious answer to "that one is not
        // answering". Retrying against the address the failed attempt pinned would then upload to
        // a server they had just navigated away from, and announce the one they are looking at.
        //
        // Starting again elsewhere abandons the old link. That is the right trade rather than a
        // free one: `isUploaded` being false means no acknowledgement was ever seen, not that the
        // old server certainly refused, so a reply lost in transit could leave a copy behind
        // there. Nothing can be done about it from here — that server is precisely the one not
        // answering — and the copy would sit in the user's own account on it, listed and
        // deletable from any device that signs in. A duplicate they can see beats a space that
        // will not go anywhere.
        val retryable = existing?.takeIf { it.account == account.key }
        if (retryable != null) {
            // A first upload that did not land, tried again. Through the ordinary upload so that
            // it reuses the remote id the link pinned: minting another would leave the first
            // attempt's copy behind on the server if its reply had merely gone missing.
            uploadSpace(spaceId, overwrite = false)
        } else if (!uploadNewSpace(spaceId, account)) {
            return
        }
        // Judged on whether the server has it, not on whether a failure was filed. Two presses can
        // both get past the check above, and the one whose claim is refused does nothing at all;
        // "no failure was recorded" would read as success to it and close the page over an upload
        // that is still in flight, or that is about to fail.
        if (service.linkFor(spaceId)?.isUploaded != true) return
        updateState {
            // Only if it is still this space's page. Another one may have been opened while this
            // upload was on the wire, and closing that would take away a question nobody answered.
            if (putOnServer?.spaceId != spaceId) this
            else copy(putOnServer = null, remoteSetup = sync?.let { RemoteSetupState() })
        }
        val called = name?.let { "\"$it\"" } ?: "That space"
        action(SpaceListAction.Announce("$called is now kept on ${account.key.serverUrl}."))
    }

    private suspend fun SpaceListPipelineContext.signOutOfSpace(spaceId: String) {
        val service = sync ?: return
        val link = peek { remoteLinks[spaceId] } ?: return
        val server = link.account.serverUrl
        val outcome = service.signOut(link.account)
        // Said in passing, not filed against the space. The token is gone from this device either
        // way — including when the server refuses because it had already expired, which is the
        // very case where a second red box under the row helped nobody.
        action(
            SpaceListAction.Announce(
                when (outcome) {
                    is Outcome.Success -> "Signed out of $server."
                    is Outcome.Failure ->
                        "Signed out of $server on this device. The server could not be told."
                }
            )
        )
        // Filed against the space only once it has none: what is left is not a failed sign-out
        // but a space whose server this device can no longer answer for.
        updateState { copy(syncFailures = syncFailures.remove(spaceId)) }
        cloud?.refresh(spaceId)
    }

    private suspend fun SpaceListPipelineContext.useKnownServer(server: KnownServer) {
        val address = ServerAddress.parse(server.url).getOrNull() ?: return
        updateState {
            copy(
                remoteSetup = RemoteSetup.seeded(
                    // Straight to the credentials: this address has answered before, and making
                    // the user press Connect again to be told what they already know is a step
                    // that only ever succeeds.
                    state = (remoteSetup ?: RemoteSetupState()).copy(
                        stage = RemoteSetupStage.Authenticating(address, AuthMode.SignIn),
                    ),
                    addressText = server.url,
                    username = server.lastUsername.orEmpty(),
                    password = "",
                )
            )
        }
    }

    private suspend fun SpaceListPipelineContext.beginReauth(spaceId: String) {
        val link = peek { remoteLinks[spaceId] } ?: return
        val address = ServerAddress.parse(link.account.serverUrl).getOrNull() ?: return
        updateState {
            copy(
                reauthSpaceId = spaceId,
                remoteSetup = RemoteSetup.seeded(
                    // The username is filled in and the address is fixed: signing in again is
                    // about the password, and letting either be retyped here would let a space
                    // change account or server without ever saying so.
                    state = (remoteSetup ?: RemoteSetupState()).copy(
                        stage = RemoteSetupStage.Authenticating(address, AuthMode.SignIn),
                    ),
                    addressText = link.account.serverUrl,
                    username = link.account.username,
                    password = "",
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
        // Told, not left to work it out. Nothing else clears a conflict, and after signing in
        // again the space would otherwise stay read-only under a message about a session that has
        // just been renewed.
        if (outcome is Outcome.Success) cloud?.conflictResolved(spaceId)
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
            // For the same reason the link goes: a status left against a deleted id would be
            // shown against whichever space next takes it.
            cloud?.forget(prefix)
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
