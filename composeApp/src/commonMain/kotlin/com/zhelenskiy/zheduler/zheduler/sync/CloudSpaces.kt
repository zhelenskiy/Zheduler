@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.sync

import com.zhelenskiy.zheduler.zheduler.TaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Where a cloud space stands with its server.
 *
 * A space that lives on a server is only as good as the last time this device heard from it, and
 * the app has to say which of those it is showing. The states are exhaustive on purpose: a screen
 * that renders one of these has been made to answer "may this be edited" rather than assuming yes.
 */
sealed interface CloudSpaceStatus {

    /** Whether the user may change anything in this space right now. */
    val isEditable: Boolean

    /** A space that belongs to this device alone. Nothing about it can go wrong. */
    data object OnThisDevice : CloudSpaceStatus {
        override val isEditable: Boolean get() = true
    }

    /** Asking the server what it has. Brief, and read-only while it lasts. */
    data class Checking(val link: RemoteSpaceLink) : CloudSpaceStatus {
        override val isEditable: Boolean get() = false
    }

    /** In step with the server. */
    data class Live(val link: RemoteSpaceLink) : CloudSpaceStatus {
        override val isEditable: Boolean get() = true
    }

    /** Changes made here are on their way up. Still editable — this is the ordinary case. */
    data class Saving(val link: RemoteSpaceLink) : CloudSpaceStatus {
        override val isEditable: Boolean get() = true
    }

    /**
     * The server could not be reached, so what is on screen is the copy from [asOf].
     *
     * Read-only. The server holds this space; editing a copy the server has not agreed to would
     * be inventing a second truth, and this app has no way to merge two of them.
     */
    data class Offline(
        val link: RemoteSpaceLink,
        val error: RemoteError,
        val asOf: Instant?,
    ) : CloudSpaceStatus {
        override val isEditable: Boolean get() = false
    }

    /**
     * The server was reached and refused — a conflict, an expired session, a space that is gone.
     *
     * Read-only too, and for a sharper reason than [Offline]: something is wrong that carrying on
     * typing would only make larger.
     */
    data class Blocked(
        val link: RemoteSpaceLink,
        val error: RemoteError,
    ) : CloudSpaceStatus {
        override val isEditable: Boolean get() = false
    }

    /** The link this status is about, or null for a space that belongs to this device. */
    val linkOrNull: RemoteSpaceLink?
        get() = when (this) {
            is OnThisDevice -> null
            is Checking -> link
            is Live -> link
            is Saving -> link
            is Offline -> link
            is Blocked -> link
        }

    /** Whether anything is worth saying about this space's server. */
    val needsAttention: Boolean get() = this is Offline || this is Blocked
}

/**
 * What became of a change that was sent the moment it was made.
 *
 * The distinction a screen needs before it decides whether to keep what the user wrote.
 */
enum class CommitOutcome {
    /** The server has it. Whatever the screen was holding can go. */
    Accepted,

    /**
     * The change was never made: the thing being changed is gone, most likely deleted elsewhere.
     *
     * Nothing to do with the server, and a different sentence for the user.
     */
    NotWritten,

    /**
     * The server would not have it, so it was taken back out. Nothing in the space changed, and
     * the only remaining copy is whatever the screen still holds — which it must therefore keep.
     */
    Undone,

    /**
     * Somebody else changed the same space, so both copies are still here and the user has to say
     * which wins. The work is safe in the space; what it is waiting for is an answer, not retyping.
     */
    AwaitingYourChoice,
}

/**
 * The server's copy of each cloud space, kept in step with this device's.
 *
 * The rule this class exists to enforce is that for a cloud space the server is the space, and the
 * local database is a copy of it kept so that something can be shown when the network is not
 * there. Two things follow, and both are here:
 *
 * - **Opening one asks the server first.** If the server has moved on, its copy replaces the local
 *   one outright — no prompt, because there is nothing to choose between: one of them is the
 *   space and the other is a stale copy of it.
 * - **Every change is made on the server.** A local write is only the first half of an edit; the
 *   second half is the server accepting it, and a server that refuses or cannot be reached undoes
 *   the first half. Nothing is left in the database that the server has not agreed to, so there is
 *   never a local version of the space to reconcile later.
 *
 * When the server cannot be reached the space goes read-only rather than pretending. That is the
 * "issue" a disconnection is supposed to produce: a space whose truth is elsewhere and elsewhere
 * is unreachable is not a space you can safely write to.
 */
class CloudSpaces(
    private val sync: SpaceSyncService,
    private val repository: TaskRepository,

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * How long to wait before sending an edit up.
     *
     * Not a grace period — short enough to be part of the same gesture. One user action can be
     * several database writes (a task, its tags, its timeline), and without this each of them
     * would be a separate upload of the whole space.
     */
    private val settle: kotlin.time.Duration = SETTLE,
) {

    private val statuses = MutableStateFlow<Map<String, CloudSpaceStatus>>(emptyMap())

    private val undone = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Spaces whose last change was taken back because the server would not have it.
     *
     * A standing fact rather than an announcement, and deliberately so. A rollback happens a
     * network timeout after the edit, by which time the screen that made it may well be gone —
     * an event fired then would be heard by nobody, and the user would find their work missing
     * with nothing anywhere to say why. It is cleared when the space next agrees with the server.
     */
    val rolledBack: StateFlow<Set<String>> = undone.asStateFlow()

    /** What every space this device knows about is doing. Spaces absent from it are local. */
    val all: StateFlow<Map<String, CloudSpaceStatus>> = statuses.asStateFlow()

    /**
     * The payload the server last accepted, per space. The space, in other words.
     *
     * Two jobs. It makes an upload skippable — the repository announces a change without saying
     * what changed, so without this every write anywhere would re-upload every linked space. And
     * it is what a refused edit is rolled back to: the last state both sides agreed on.
     */
    private val lastUploaded = mutableMapOf<String, String>()

    /** Waiting debounce timers, one per space. Never an upload that has already begun. */
    private val waiting = mutableMapOf<String, Job>()

    /** Spaces whose upload is in flight, so a second one cannot start on top of it. */
    private val sending = mutableSetOf<String>()

    /** Spaces that changed again while their upload was in flight. */
    private val sendAgain = mutableSetOf<String>()

    /**
     * One lock per space, held across a whole check or a whole upload.
     *
     * [sending] keeps two uploads apart, but the damaging races are between *different*
     * operations: two first uploads both creating the same remote space, or a check that fetches
     * with one revision and adopts what comes back over an edit made since. Both read the space,
     * talk to the server, and write the space back, so both need the space to themselves.
     */
    private val perSpace = mutableMapOf<String, Mutex>()

    /**
     * Bumped when a space is forgotten — deleted, unlinked, or erased along with everything else.
     *
     * Space ids are handed out again. Without this, an upload still on the wire when its space was
     * deleted comes back and writes a status and an accepted payload under an id the next space
     * inherits: that space would show as somebody else's cloud space, and a failed upload would
     * "roll it back" to the deleted space's contents.
     */
    private val generations = mutableMapOf<String, Long>()

    private val lock = Mutex()

    private suspend fun lockFor(spaceId: String): Mutex =
        lock.withLock { perSpace.getOrPut(spaceId) { Mutex() } }

    private suspend fun generationOf(spaceId: String): Long =
        lock.withLock { generations[spaceId] ?: 0L }

    /** Whether [spaceId] still means what it meant when an operation started. */
    private suspend fun stillTheSameSpace(spaceId: String, startedIn: Long): Boolean =
        lock.withLock { (generations[spaceId] ?: 0L) == startedIn }

    private suspend fun setIfStill(spaceId: String, startedIn: Long, status: CloudSpaceStatus) {
        if (stillTheSameSpace(spaceId, startedIn)) set(spaceId, status)
    }

    private suspend fun rememberIfStill(spaceId: String, startedIn: Long, payload: String) {
        val recorded = lock.withLock {
            val still = (generations[spaceId] ?: 0L) == startedIn
            if (still) lastUploaded[spaceId] = payload
            still
        }
        // Written to the link as well as held here. This map does not survive the process, and the
        // question it answers — is what is in the database what the server took — is at its most
        // important on the first check after a restart.
        if (recorded) sync.noteAccepted(spaceId, fingerprintOf(payload))
    }

    fun statusOf(spaceId: String): CloudSpaceStatus =
        statuses.value[spaceId] ?: CloudSpaceStatus.OnThisDevice

    /**
     * Follows the repository, so every change finds its way up.
     *
     * Started once for the process rather than per screen: a recurrence rule firing or a due date
     * passing changes a space nobody is looking at, and for a space whose truth is on a server
     * that change is not finished until the server has it.
     */
    fun watch(repository: TaskRepository) {
        // Listening starts first and on its own. `changes` keeps no backlog, and the first
        // refresh is a round of network calls that can take half a minute — collecting after it
        // would drop every change made in the meantime, unnoticed and unsent.
        scope.launch { repository.changes.collect { onLocalChange() } }
        scope.launch {
            // Read-only before anything else happens. A linked space this device has not yet
            // checked is a space of unknown standing, and unknown must not read as fine: the
            // alternative is a cold start where every cloud space looks editable and the first
            // edits go nowhere.
            sync.allLinks().forEach { (spaceId, link) -> set(spaceId, CloudSpaceStatus.Checking(link)) }
            refreshAll()
        }
    }

    /**
     * Brings this device's copy of [spaceId] into step with the server's.
     *
     * Called when a space is opened and whenever the user asks again. Downloads and replaces the
     * local copy if the server is ahead; leaves it alone if it is not.
     */
    suspend fun refresh(spaceId: String) {
        // A conflict is a question put to the user, and fetching would answer it for them by
        // adopting the server's copy. That matters most in the one place the question can be
        // answered: the space list asks every space to check when it opens, so without this the
        // act of going to resolve a conflict destroyed the copy being chosen between.
        if (awaitingAChoice(spaceId)) return
        // Started on this class's own scope, then waited for. Every caller is a screen — a gate's
        // effect, a retry button, the space list's subscription — and a screen goes away when the
        // user navigates. Running the work in the caller's coroutine meant backing out of a space
        // could cancel a request the server had already acted on: the reply never landed, so the
        // link kept a revision the server had moved past, and the next attempt was refused as a
        // conflict against nobody. Cancelling the wait is fine; cancelling the work is not.
        scope.async { lockFor(spaceId).withLock { checkAgainst(spaceId) } }.await()
    }

    /**
     * Marks a space as on its way to a server, before it has a link to show for it.
     *
     * Held read-only for the length of that first upload. Without it the space is one this device
     * knows nothing about, which reads as local: editable, with every change skipped rather than
     * sent, and then replaced by the copy the server was given at the start.
     */
    suspend fun preparing(spaceId: String, account: AccountKey, remoteSpaceId: String) {
        set(
            spaceId,
            CloudSpaceStatus.Checking(
                RemoteSpaceLink(
                    spaceId = spaceId,
                    account = account,
                    remoteSpaceId = remoteSpaceId,
                    lastSyncedRevision = RemoteSpaceLink.NOT_UPLOADED,
                    lastSyncedAtEpochSeconds = 0,
                )
            ),
        )
    }

    /** Whether the user still owes an answer about which copy of [spaceId] wins. */
    private fun awaitingAChoice(spaceId: String): Boolean {
        val standing = statusOf(spaceId)
        return standing is CloudSpaceStatus.Blocked &&
            standing.error.remedy == RemoteRemedy.ResolveConflict
    }

    /**
     * The user has said which copy wins, so the space may be checked again.
     *
     * The only way out of [awaitingAChoice]: nothing else knows that the question was answered.
     */
    suspend fun conflictResolved(spaceId: String) {
        val link = sync.linkFor(spaceId) ?: return
        set(spaceId, CloudSpaceStatus.Checking(link))
        refresh(spaceId)
    }

    /** [refresh], with this space's lock already held. */
    private suspend fun checkAgainst(spaceId: String) {
        val startedIn = generationOf(spaceId)
        val link = sync.linkFor(spaceId) ?: run {
            set(spaceId, CloudSpaceStatus.OnThisDevice)
            return
        }
        // A space whose first upload never landed has nothing on the server to be behind, so
        // there is nothing to fetch — what it needs is the upload, tried again. Without this the
        // space is wedged for good: read-only, with every button it has leading back to here.
        if (!link.isUploaded) {
            firstUpload(spaceId, link, startedIn)
            return
        }

        // A space already in step with its server keeps saying so while it is asked again. Only
        // the first check of a space, or one that is already in trouble, goes to Checking: eight
        // screens in a space each ask on entry, and marking a healthy space read-only for the
        // length of every one of those would take the buttons away while nothing is wrong.
        if (statusOf(spaceId).let { it !is CloudSpaceStatus.Live && it !is CloudSpaceStatus.Saving }) {
            setIfStill(spaceId, startedIn, CloudSpaceStatus.Checking(link))
        }
        when (val fetched = sync.fetchSpaceIfChanged(link)) {
            is Outcome.Failure -> setIfStill(spaceId, startedIn, statusForFailure(link, fetched.error))
            is Outcome.Success -> when (val value = fetched.value) {
                is FetchedSpace.Unchanged -> settleUnchanged(spaceId, link, startedIn)
                is FetchedSpace.Fresh -> adoptRemote(spaceId, link, value.snapshot, startedIn)
            }
        }
    }

    /**
     * Puts a space on the server for the first time, or says why it is not there yet.
     *
     * Reached from [refresh] rather than from a button of its own, so "check the server now" and
     * opening the space both do the obvious thing for a space that never made it up.
     */
    private suspend fun firstUpload(spaceId: String, link: RemoteSpaceLink, startedIn: Long) {
        // Read before the upload, never after. An edit made while a first upload is on the wire is
        // in the later export but not in what the server received; recording the later one as
        // agreed would make that edit invisible to every comparison from then on.
        val payload = exportOf(spaceId)
        setIfStill(spaceId, startedIn, CloudSpaceStatus.Saving(link))
        when (val uploaded = sync.upload(spaceId)) {
            is Outcome.Success -> {
                if (payload != null) rememberIfStill(spaceId, startedIn, payload)
                setIfStill(spaceId, startedIn, CloudSpaceStatus.Live(sync.linkFor(spaceId) ?: link))
            }

            is Outcome.Failure -> setIfStill(spaceId, startedIn, statusForFailure(link, uploaded.error))
        }
    }

    /**
     * Refreshes every linked space. What the space list does when it opens.
     *
     * All at once, not one after another: each check against a server that is not there waits out
     * its own timeout, and in a row that is a couple of minutes during which the last space in the
     * list sits read-only behind a spinner. The per-space locks make running them together safe.
     */
    suspend fun refreshAll() = coroutineScope {
        sync.allLinks().keys.forEach { spaceId -> launch { refresh(spaceId) } }
    }

    /**
     * Takes the server's copy as this space's contents.
     *
     * The local copy is not consulted. That is the whole point of the server being the source of
     * truth, and it is why editing is refused whenever the server cannot be reached: anything
     * typed into a stale copy would be thrown away right here.
     */
    private suspend fun adoptRemote(
        spaceId: String,
        link: RemoteSpaceLink,
        snapshot: SpaceSnapshot,
        startedIn: Long,
    ) {
        if (!stillTheSameSpace(spaceId, startedIn)) return
        // A space in step stays editable while it is being checked, so the user may have typed
        // something between the fetch going out and this copy arriving. Adopting is still right —
        // the server is the space — but it is a change of theirs being dropped, and this class
        // does not drop one in silence.
        val accepted = lock.withLock { lastUploaded[spaceId] }
        val here = exportOf(spaceId)
        // Ordinarily: different from what the server last took means work of the user's.
        //
        // With nothing remembered — a cold start, or a space whose first upload is only now being
        // checked — there is no baseline, so the revision answers instead. A server still at the
        // revision this device last synced has not moved; anything the copy that comes back
        // disagrees with was therefore written here and never sent.
        val buriedLocalWork = here != null && when {
            accepted != null -> here != accepted
            // Nothing remembered in this run, but the link remembers across runs.
            link.lastAcceptedFingerprint != null ->
                fingerprintOf(here) != link.lastAcceptedFingerprint
            // Not even that: fall back to the revision, which can only answer when the server has
            // not moved. A link this old predates the fingerprint being recorded at all.
            else -> snapshot.revision == link.lastSyncedRevision
        }

        val replaced = runCatching { repository.replaceSpaceFromJson(spaceId, snapshot.payload) }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
            .getOrDefault(false)

        if (!replaced) {
            setIfStill(spaceId, startedIn, CloudSpaceStatus.Blocked(link, unreadableSnapshot()))
            return
        }
        sync.noteSynced(link, snapshot.revision, snapshot.updatedAtEpochSeconds)
        // What is in the database now, rather than the payload that arrived: the two differ
        // wherever the snapshot names things this device keeps under its own ids, and recording
        // the payload would make an unchanged space look changed on every single check.
        val nowHere = exportOf(spaceId)
        rememberIfStill(spaceId, startedIn, nowHere ?: snapshot.payload)
        val reallyBuried = buriedLocalWork && (accepted != null || here != nowHere)
        setIfStill(spaceId, startedIn, CloudSpaceStatus.Live(sync.linkFor(spaceId) ?: link))
        // Guarded like every other write-back: the id may have been handed to a new space while
        // this was in flight, and that space has lost nothing.
        if (reallyBuried && stillTheSameSpace(spaceId, startedIn)) {
            undone.update { it + spaceId }
        }
    }

    /**
     * What to make of a server that says it has not moved.
     *
     * "Not moved" is a statement about the *server's* revision, and on its own it says nothing
     * about this device. Something may have been written here that the server never took — a
     * recurrence rule firing while the network was down writes to the database whether or not a
     * screen is open, and there is no screen to make read-only. Blessing the local copy on the
     * strength of a matching revision number is how a device ends up quietly holding a version of
     * a space that nobody else can see, with that version then serving as the baseline every later
     * edit is measured against.
     *
     * So the local copy is compared against the last thing the server actually took:
     *
     * - Identical — in step, and this is the ordinary case.
     * - Different — a change that never went up. It goes up now, which is what "every update is
     *   made on the server" means for a write that could not be sent when it happened.
     * - Nothing known — this device has not spoken to the server about this space in this run, so
     *   there is nothing to compare with. The whole copy is fetched rather than guessed at.
     */
    private suspend fun settleUnchanged(spaceId: String, link: RemoteSpaceLink, startedIn: Long) {
        val accepted = lock.withLock { lastUploaded[spaceId] }
        val here = exportOf(spaceId)

        if (accepted == null) {
            when (val whole = sync.fetchSpace(link, sinceKnownRevision = false)) {
                is Outcome.Failure -> setIfStill(spaceId, startedIn, statusForFailure(link, whole.error))
                is Outcome.Success -> when (val value = whole.value) {
                    // Asked for unconditionally, so this cannot happen; treated as "in step"
                    // rather than as an error, which is the harmless reading of it.
                    is FetchedSpace.Unchanged -> {
                        if (here != null) rememberIfStill(spaceId, startedIn, here)
                        setIfStill(spaceId, startedIn, CloudSpaceStatus.Live(link))
                    }

                    is FetchedSpace.Fresh -> adoptRemote(spaceId, link, value.snapshot, startedIn)
                }
            }
            return
        }

        setIfStill(spaceId, startedIn, CloudSpaceStatus.Live(link))
        // Sent from here rather than through `uploadNow`: this space's lock is already held, and
        // taking it again would wait on itself for good.
        if (here != null && here != accepted) send(spaceId)
    }

    /**
     * Tells the cloud that something in the local database changed.
     *
     * Every linked space is looked at rather than one, because the repository says only that
     * *something* changed — and a change can come from somewhere no screen is open on, such as a
     * recurrence rule firing. Spaces whose contents did not actually change are skipped by
     * comparing against what the server last took.
     */
    fun onLocalChange() {
        scope.launch {
            sync.allLinks().values.forEach { link -> scheduleUpload(link.spaceId) }
        }
    }

    private suspend fun scheduleUpload(spaceId: String) {
        val current = statusOf(spaceId)
        // Nothing to send from a space that is not in step with the server; its own error is
        // showing and an upload would be built on a copy the server has not agreed to.
        if (!current.isEditable || current is CloudSpaceStatus.OnThisDevice) return

        lock.withLock {
            // An upload already on the wire is left alone and noted instead. Cancelling one
            // mid-request is how a change that the server had in fact accepted came back as a
            // conflict on the next try — and the conflict would then undo work the server held.
            if (spaceId in sending) {
                sendAgain += spaceId
                return@withLock
            }
            waiting.remove(spaceId)?.cancel()
            waiting[spaceId] = scope.launch {
                delay(settle)
                uploadNow(spaceId)
            }
        }
    }

    /**
     * Makes a pending change on the server, or unmakes it here.
     *
     * The whole of the source-of-truth rule is in the failure branch. An edit the server did not
     * take never happened: it is rolled back to the last state the server agreed to, and the space
     * says why it is now read-only. Keeping it instead would leave the device holding a version of
     * the space nobody else can see, which the next successful refresh would delete without ever
     * having shown the user that it existed.
     */
    suspend fun uploadNow(spaceId: String) {
        val claimed = lock.withLock {
            if (spaceId in sending) {
                sendAgain += spaceId
                false
            } else {
                sending += spaceId
                // Out of the timer map, so a later change cannot cancel what is now in flight.
                waiting.remove(spaceId)
                true
            }
        }
        if (!claimed) return
        try {
            lockFor(spaceId).withLock { send(spaceId) }
        } finally {
            val again = lock.withLock {
                sending -= spaceId
                sendAgain.remove(spaceId)
            }
            if (again) scheduleUpload(spaceId)
        }
    }

    /**
     * What one attempt to send actually did.
     *
     * Reported rather than inferred. Working it out afterwards by comparing the space against what
     * the server holds cannot tell "the server took it" from "it was taken back out again" — a
     * rollback leaves those two looking identical, because putting the space back to what the
     * server has is exactly what it does.
     */
    private enum class SendResult {
        /** The server took what was in the space. */
        Uploaded,

        /** The server already had exactly this. Nothing was sent and nothing changed. */
        AlreadyThere,

        /** Refused, and the space put back to what the server had. */
        Undone,

        /** Refused because somebody else was writing too. Both copies are still here. */
        Conflicted,

        /**
         * Nothing was sent, and nothing was taken back either.
         *
         * The space is not in a state to send from — offline, or never yet heard from. Whatever
         * was written is still in the space, and still only here.
         */
        NotSent,
    }

    private suspend fun send(spaceId: String): SendResult {
        val startedIn = generationOf(spaceId)
        val link = sync.linkFor(spaceId) ?: return SendResult.NotSent
        // Re-read rather than trusted from when this was scheduled: the space may have gone
        // offline during the wait, and sending from a copy the server has not agreed to is the
        // one thing this class exists to prevent.
        val standing = statusOf(spaceId)
        if (!standing.isEditable || standing is CloudSpaceStatus.OnThisDevice) return SendResult.NotSent
        val accepted = lock.withLock { lastUploaded[spaceId] }
        // Nothing agreed yet, so nothing may be sent: this device has not heard from the server
        // about this space, which means an upload could neither be skipped as unnecessary nor
        // taken back if it were refused. Opening the space asks the server, and that settles it.
        if (accepted == null) return SendResult.NotSent
        val payload = exportOf(spaceId) ?: return SendResult.NotSent
        if (accepted == payload) return SendResult.AlreadyThere

        setIfStill(spaceId, startedIn, CloudSpaceStatus.Saving(link))
        return when (val uploaded = sync.upload(spaceId)) {
            is Outcome.Success -> {
                rememberIfStill(spaceId, startedIn, payload)
                setIfStill(spaceId, startedIn, CloudSpaceStatus.Live(sync.linkFor(spaceId) ?: link))
                SendResult.Uploaded
            }

            is Outcome.Failure -> {
                // A conflict is the one refusal where this device's copy must survive. Both
                // versions are real work, and the user is about to be asked which one wins;
                // throwing one away first makes the question unanswerable, and "keep mine" would
                // then push the server's own content straight back at it.
                val conflicted = uploaded.error.remedy == RemoteRemedy.ResolveConflict
                if (!conflicted && stillTheSameSpace(spaceId, startedIn)) {
                    undo(spaceId, accepted, startedIn)
                }
                setIfStill(spaceId, startedIn, statusForFailure(link, uploaded.error))
                if (conflicted) SendResult.Conflicted else SendResult.Undone
            }
        }
    }

    /**
     * Puts the space back to [accepted], the last thing the server agreed to.
     *
     * Skipped when there is nothing to go back to — a space whose first upload never landed has no
     * agreed state, and its own status already says so.
     */
    private suspend fun undo(spaceId: String, accepted: String?, startedIn: Long) {
        if (accepted == null) return
        try {
            // The restore is itself a change, which comes back round through `changes`; the next
            // upload sees the payload it already has and stops there.
            if (!repository.replaceSpaceFromJson(spaceId, accepted)) return
            if (stillTheSameSpace(spaceId, startedIn)) undone.update { it + spaceId }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // The space is read-only either way, and the first refresh that succeeds replaces this
            // device's copy from the server — a change that could not be taken back is temporary
            // rather than kept.
        }
    }

    private suspend fun exportOf(spaceId: String): String? =
        runCatching { repository.exportSpaceToJson(spaceId, prettyPrint = false) }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
            .getOrNull()

    /**
     * The user has read the notice that a change of theirs did not survive.
     *
     * Cleared by being acknowledged rather than by the space coming right, because those are
     * different facts: a space that is now in step with its server still lost the change, and the
     * sentence saying so has to outlive the condition that caused it.
     */
    fun noticeSeen(spaceId: String) {
        undone.update { it - spaceId }
    }

    /**
     * Sends a space's pending change now, and says what became of it.
     *
     * For a screen that has to know. A save is debounced and answered later, which is right for a
     * checkbox and wrong for a form somebody has been filling in for five minutes: by the time the
     * refusal arrives the screen has navigated away and thrown its draft out, and the rollback
     * then removes the work with nothing left anywhere to put it back from.
     */
    suspend fun commit(spaceId: String, write: suspend () -> Boolean): CommitOutcome {
        if (sync.linkFor(spaceId) == null) {
            return if (write()) CommitOutcome.Accepted else CommitOutcome.NotWritten
        }
        // The waiting timer would otherwise send the same thing again a moment later.
        lock.withLock { waiting.remove(spaceId)?.cancel() }

        // On this class's own scope, and behind this space's own lock, with the change itself made
        // inside. The scope because the caller is a screen and screens go away — a request
        // cancelled halfway may already have been acted on, and the next attempt is then refused
        // as a conflict with nobody. The lock, and the write inside it, because otherwise anything
        // queued ahead runs *between* the write and the send: a rollback landing in that gap takes
        // the just-written change with it, and everything after would report the space as being in
        // step — which it now is, having quietly thrown the change away.
        return scope.async {
            lockFor(spaceId).withLock {
                if (!write()) return@withLock CommitOutcome.NotWritten
                when (send(spaceId)) {
                    SendResult.Uploaded, SendResult.AlreadyThere -> CommitOutcome.Accepted
                    SendResult.Conflicted -> CommitOutcome.AwaitingYourChoice
                    SendResult.Undone -> CommitOutcome.Undone

                    // Nothing went, and nothing was taken back — the space was in no state to
                    // send from. That leaves the change here and only here, which is the one
                    // state this class exists to prevent, and reporting it as undone while it sat
                    // in the space would have the form offer to make it a second time. So it is
                    // undone, properly, and then said so.
                    SendResult.NotSent -> {
                        val startedIn = generationOf(spaceId)
                        undo(spaceId, lock.withLock { lastUploaded[spaceId] }, startedIn)
                        CommitOutcome.Undone
                    }
                }
            }
        }.await()
    }

    /** Forgets everything known about a space, which is what unlinking or deleting it means. */
    suspend fun forget(spaceId: String) {
        lock.withLock {
            waiting.remove(spaceId)?.cancel()
            sendAgain -= spaceId
            lastUploaded.remove(spaceId)
            undone.update { it - spaceId }
            // An upload may still be on the wire. It cannot be recalled, but its result can be
            // refused: everything it would write back is guarded on this number.
            generations[spaceId] = (generations[spaceId] ?: 0L) + 1
        }
        statuses.update { it - spaceId }
    }

    suspend fun forgetAll() {
        lock.withLock {
            waiting.values.forEach { it.cancel() }
            waiting.clear()
            sendAgain.clear()
            undone.value = emptySet()
            // `perSpace` too: an operation registers its lock before it writes anything else, so
            // that is the only place a space is named between starting and having a status.
            val touched = lastUploaded.keys + sending + statuses.value.keys +
                generations.keys + perSpace.keys
            lastUploaded.clear()
            touched.forEach { spaceId ->
                generations[spaceId] = (generations[spaceId] ?: 0L) + 1
            }
        }
        statuses.value = emptyMap()
    }

    /**
     * Records where one space stands.
     *
     * Through `update` rather than by assigning: several spaces are refreshed at once, and a
     * read-modify-write on the map would drop whichever neighbour lost the race — a space that
     * quietly stayed editable while its server was unreachable.
     */
    private fun set(spaceId: String, status: CloudSpaceStatus) {
        statuses.update { current ->
            if (status is CloudSpaceStatus.OnThisDevice) current - spaceId
            else current + (spaceId to status)
        }
    }

    /**
     * Which kind of stop a failure is.
     *
     * The distinction the user sees: something that may simply be the network, against something
     * the server said and meant.
     */
    private fun statusForFailure(link: RemoteSpaceLink, error: RemoteError): CloudSpaceStatus =
        when (error.remedy) {
            RemoteRemedy.Retry, RemoteRemedy.RetryLater -> CloudSpaceStatus.Offline(
                link = link,
                error = error,
                asOf = link.lastSyncedAtEpochSeconds
                    .takeIf { it > 0 }
                    ?.let { Instant.fromEpochSeconds(it) },
            )

            else -> CloudSpaceStatus.Blocked(link, error)
        }

    /**
     * Puts a brand-new space on its server, under the space's own lock.
     *
     * The first upload has to go through here rather than straight to the service: it can take
     * half a minute, and opening the space during it would start a second one against the same
     * remote id — refused, correctly, as a conflict, leaving the user asked to choose between two
     * identical copies of a space they had only just made.
     */
    suspend fun putOnServer(
        spaceId: String,
        account: SignedInAccount,
        remoteSpaceId: String,
    ): Outcome<Uploaded> {
        preparing(spaceId, account.key, remoteSpaceId)
        return scope.async {
            lockFor(spaceId).withLock {
                val startedIn = generationOf(spaceId)
                // Before the upload, as everywhere else: an edit made while it is in flight is in
                // the later export but not in what the server received.
                val payload = exportOf(spaceId)
                val uploaded = sync.linkAndUpload(spaceId, account, remoteSpaceId)
                val link = sync.linkFor(spaceId)
                // Deleted while this was on the wire. The delete's own unlink found nothing to
                // remove — the link is written by the call above, after it — so it has to be
                // removed here, or it waits to attach itself to whichever space takes the id next.
                if (!stillTheSameSpace(spaceId, startedIn)) {
                    sync.unlink(spaceId)
                    return@withLock uploaded
                }
                when {
                    uploaded is Outcome.Success && link != null -> {
                        if (payload != null) rememberIfStill(spaceId, startedIn, payload)
                        setIfStill(spaceId, startedIn, CloudSpaceStatus.Live(link))
                    }

                    uploaded is Outcome.Failure && link != null ->
                        setIfStill(spaceId, startedIn, statusForFailure(link, uploaded.error))

                    else -> setIfStill(spaceId, startedIn, CloudSpaceStatus.OnThisDevice)
                }
                uploaded
            }
        }.await()
    }

    private companion object {
        val SETTLE = 400.milliseconds

        fun unreadableSnapshot() = RemoteError.Malformed(
            "the copy on the server was written by a newer version of the app",
        )
    }
}

/**
 * A short, stable fingerprint of a space's contents.
 *
 * Not a security hash and not trying to be one — it only ever answers "is this the same text as
 * before", between two runs of this app on this device. FNV-1a because it needs no dependency and
 * behaves the same on every target this app builds for.
 */
internal fun fingerprintOf(payload: String): String {
    var hash = -3750763034362895579L
    payload.forEach { character ->
        hash = hash xor character.code.toLong()
        hash *= 1099511628211L
    }
    return hash.toULong().toString(16)
}
