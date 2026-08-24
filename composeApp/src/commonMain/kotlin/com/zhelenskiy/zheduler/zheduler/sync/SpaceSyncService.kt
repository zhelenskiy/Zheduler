package com.zhelenskiy.zheduler.zheduler.sync

import com.zhelenskiy.zheduler.zheduler.Space
import com.zhelenskiy.zheduler.zheduler.TaskRepository
import io.github.xxfast.kstore.KStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An account this device is signed in to, as the UI needs to describe it. */
data class SignedInAccount(
    val key: AccountKey,
    val userId: String,
)

/** What an upload did. */
data class Uploaded(val revision: Long)

/** A space downloaded from a server into a new local space. */
data class Downloaded(val space: Space, val link: RemoteSpaceLink)

/**
 * Everything the app does with a sync server, with the token handling and the bookkeeping in one
 * place instead of spread across the screens.
 *
 * Every method returns an [Outcome], and `Outcome` is marked `@MustUseReturnValues`, so a caller
 * that forgets a failure is a compiler warning rather than a screen that silently does nothing.
 * The gateway underneath knows nothing about which account is signed in; that is this class's job,
 * and it is why the token never has to be passed around the UI.
 */
@MustUseReturnValues
class SpaceSyncService(
    private val gateway: RemoteSpaceGateway,
    private val repository: TaskRepository,
    private val links: KStore<SyncSettings>,
    private val credentials: KStore<StoredCredentials>,
    /**
     * Where telling a server to forget a token happens.
     *
     * Fire and forget on purpose. A token this device has already deleted cannot be used from
     * here, so the revocation is a courtesy to the server — and awaiting it would make "erase all
     * data" sit for thirty seconds per account against a server that is not answering, which is
     * exactly the situation somebody erasing everything is likely to be in.
     */
    private val revocations: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /**
     * Guards the read-modify-write on each store.
     *
     * kstore's `update` is not atomic across coroutines, and two spaces finishing an upload at the
     * same moment would otherwise write one map over the other — losing a link the user had just
     * made, and with it their space's connection to the server.
     */
    private val storeLock = Mutex()

    /**
     * Bumped every time everything is forgotten.
     *
     * A remote call takes up to thirty seconds, and the app can delete a space or erase everything
     * while one is in flight. Without this, the reply lands afterwards and writes back what was
     * just thrown away: a link for a space that no longer exists — which the next space to take
     * that reused id would inherit, and whose first upload would then overwrite a stranger's
     * backup — or a token that outlived the erase that was supposed to remove it. Every write
     * carries the epoch its operation started in and is dropped if that epoch has passed.
     */
    private var epoch = 0L

    private suspend fun currentEpoch(): Long = storeLock.withLock { epoch }

    /** Every space this device has linked, so the list can say which ones are backed up. */
    val linksBySpaceId: Flow<Map<String, RemoteSpaceLink>> =
        links.updates.map { it?.bySpaceId.orEmpty() }

    /** The servers this device has used before, most recently used first. */
    val knownServers: Flow<List<KnownServer>> =
        links.updates.map { it?.knownServers.orEmpty() }

    suspend fun knownServersNow(): List<KnownServer> = links.get()?.knownServers.orEmpty()

    /**
     * Remembers a server the user has actually reached, and who they were on it.
     *
     * Recorded on a successful sign-in rather than on a successful health check: an address that
     * answered is not yet a server this user has anything on, and offering it later as one of
     * "their" servers would be putting words in their mouth.
     */
    private suspend fun rememberServer(account: AccountKey, startedIn: Long) = storeLock.withLock {
        if (epoch != startedIn) return@withLock
        links.update { current ->
            val settings = current ?: SyncSettings()
            val entry = KnownServer(url = account.serverUrl, lastUsername = account.username)
            settings.copy(
                // Most recent first, and one entry per address: signing in as somebody else on the
                // same server replaces the name offered rather than listing the server twice.
                knownServers = listOf(entry) + settings.knownServers.filterNot { it.url == entry.url },
            )
        }
    }

    /**
     * Forgets a server, and signs out of it.
     *
     * Refused while a space still belongs to it: that space's only copy of the truth is there, and
     * forgetting the address would leave it pointing at somewhere the user can no longer name.
     */
    suspend fun forgetServer(url: String): Boolean {
        if (allLinks().values.any { it.account.serverUrl == url }) return false
        val accounts = credentials.get()?.tokensByAccount?.keys.orEmpty()
            .mapNotNull { AccountKey.fromStorageKey(it) }
            .filter { it.serverUrl == url }
        // A revocation that does not land is not a reason to keep the address: the token is gone
        // from this device either way, and the server expires it on its own.
        accounts.forEach { account -> signOut(account).deliberatelyIgnored() }
        storeLock.withLock {
            links.update { current ->
                val settings = current ?: SyncSettings()
                settings.copy(knownServers = settings.knownServers.filterNot { it.url == url })
            }
        }
        return true
    }

    /**
     * Files a fingerprint of what the server has taken, beside the revision.
     *
     * Written from the cloud layer rather than from the upload itself, because it is the cloud
     * layer that knows which payload was actually agreed to — an upload re-exports internally, and
     * an adoption's agreed state is what the database holds afterwards.
     */
    suspend fun noteAccepted(spaceId: String, fingerprint: String) {
        val link = linkFor(spaceId) ?: return
        advanceLink(link.copy(lastAcceptedFingerprint = fingerprint), currentEpoch())
    }

    /** Every space that belongs to [url], so the user can be told why it cannot be forgotten. */
    suspend fun spacesOn(url: String): List<RemoteSpaceLink> =
        allLinks().values.filter { it.account.serverUrl == url }

    suspend fun linkFor(spaceId: String): RemoteSpaceLink? = links.get()?.bySpaceId?.get(spaceId)

    /** Every space this device has linked, by local space id. */
    suspend fun allLinks(): Map<String, RemoteSpaceLink> = links.get()?.bySpaceId.orEmpty()

    /**
     * Asks the server whether it has moved past the revision this device holds.
     *
     * Conditional, so a space that has not changed costs a header exchange rather than its whole
     * contents — which is what makes checking on every open affordable.
     */
    suspend fun fetchSpaceIfChanged(link: RemoteSpaceLink): Outcome<FetchedSpace> =
        fetchSpace(link, sinceKnownRevision = true)

    /**
     * Fetches a space, conditionally or not.
     *
     * Unconditional fetching exists for the case where "the server has not moved" is not enough to
     * conclude anything: this device may have written something the server never took, and
     * comparing against a revision number cannot see that. Asking for the whole copy is the only
     * way to find out what the space actually is.
     */
    suspend fun fetchSpace(
        link: RemoteSpaceLink,
        sinceKnownRevision: Boolean,
    ): Outcome<FetchedSpace> {
        val address = addressOf(link.account) ?: return badAddress()
        val token = tokenFor(link.account) ?: return signInAgain()
        return gateway
            .fetchSpace(
                address,
                token,
                link.remoteSpaceId,
                link.lastSyncedRevision.takeIf { sinceKnownRevision },
            )
            .also { it.recordIfAuthExpired(link.account) }
    }

    /**
     * Records that this device now holds the server's revision.
     *
     * Written after the local copy has been replaced, never before: the link is what the next
     * upload guards on, and moving it ahead of the contents would let a write claim to be based
     * on a revision this device never actually had.
     */
    suspend fun noteSynced(link: RemoteSpaceLink, revision: Long, updatedAtEpochSeconds: Long) {
        advanceLink(
            link.copy(
                lastSyncedRevision = revision,
                lastSyncedAtEpochSeconds = updatedAtEpochSeconds,
            ),
            currentEpoch(),
        )
    }

    // ------------------------------------------------------------------ signing in

    /** Whether this address is a server this app can talk to. Nothing is sent but the question. */
    suspend fun checkServer(address: ServerAddress): Outcome<ServerInfo> = gateway.serverInfo(address)

    suspend fun signUp(
        address: ServerAddress,
        username: String,
        password: String,
    ): Outcome<SignedInAccount> {
        val startedIn = currentEpoch()
        return gateway.register(address, username, password).flatMapStoring(address, startedIn)
    }

    suspend fun signIn(
        address: ServerAddress,
        username: String,
        password: String,
    ): Outcome<SignedInAccount> {
        val startedIn = currentEpoch()
        return gateway.logIn(address, username, password).flatMapStoring(address, startedIn)
    }

    private suspend fun Outcome<AuthResponse>.flatMapStoring(
        address: ServerAddress,
        startedIn: Long,
    ): Outcome<SignedInAccount> = when (this) {
        is Outcome.Failure -> this
        is Outcome.Success -> {
            // The server decides what the username is — it lower-cases and trims — so the key is
            // built from what came back, not from what was typed. Keying on the typed form would
            // file the token under a name the next sign-in never looks up.
            val key = AccountKey(address.value, value.username)
            storeToken(key, AuthToken(value.token), startedIn)
            rememberServer(key, startedIn)
            Outcome.Success(SignedInAccount(key, value.userId))
        }
    }

    /** Whether this device still holds a usable token for [account]. */
    suspend fun isSignedIn(account: AccountKey): Boolean = tokenFor(account) != null

    /**
     * Forgets [account]'s token here and tells the server to stop honouring it.
     *
     * The local half happens whatever the server says: a user who asks to sign out must end up
     * signed out even when the network is down, and a token this device has thrown away is one it
     * cannot use again.
     */
    suspend fun signOut(account: AccountKey): Outcome<Unit> {
        val token = tokenFor(account)
        forgetToken(account)
        val address = ServerAddress.parse(account.serverUrl).getOrNull()
        if (token == null || address == null) return Outcome.Success(Unit)
        return gateway.logOut(address, token)
    }

    // --------------------------------------------------------------------- uploads

    /**
     * Records which server a space belongs to, and makes the first upload.
     *
     * The link is written first, at [RemoteSpaceLink.NOT_UPLOADED], and stays there if the upload
     * fails. That is deliberate: a space whose first upload failed is one the user asked to back
     * up, and the link is what a later [upload] has to act on. Without it the space would be
     * stranded — a Retry button whose only possible answer is "this space is not connected to a
     * server".
     *
     * [remoteSpaceId] is generated by the caller rather than by the server, and is pinned by that
     * same link, so a retry after a lost reply sends the *same* id and is refused as a conflict
     * instead of leaving a second copy behind.
     */
    suspend fun linkAndUpload(
        spaceId: String,
        account: SignedInAccount,
        remoteSpaceId: String,
    ): Outcome<Uploaded> {
        val link = RemoteSpaceLink(
            spaceId = spaceId,
            account = account.key,
            remoteSpaceId = remoteSpaceId,
            lastSyncedRevision = RemoteSpaceLink.NOT_UPLOADED,
            lastSyncedAtEpochSeconds = 0,
        )
        val startedIn = currentEpoch()
        putLink(link, startedIn)
        return createFor(link, startedIn)
    }

    /**
     * Uploads the current state of a linked space.
     *
     * A space whose first upload never landed is created rather than replaced; after that, every
     * upload is guarded by the revision this device last saw. If another device has uploaded
     * since, the server refuses and the failure carries the revision that won, which is what makes
     * [uploadOverwriting] a deliberate choice rather than the default.
     */
    suspend fun upload(spaceId: String): Outcome<Uploaded> {
        val link = linkFor(spaceId) ?: return notLinked()
        return if (link.isUploaded) uploadAt(link, link.lastSyncedRevision) else createFor(link, currentEpoch())
    }

    private suspend fun createFor(link: RemoteSpaceLink, startedIn: Long): Outcome<Uploaded> {
        val address = addressOf(link.account) ?: return badAddress()
        val token = tokenFor(link.account) ?: return signInAgain()
        val space = repository.getSpaceById(link.spaceId)
            ?: return Outcome.Failure(RemoteError.Malformed("that space is no longer on this device"))
        val payload = exportOf(link.spaceId) ?: return exportFailed()

        return gateway
            .createSpace(
                address,
                token,
                link.remoteSpaceId,
                SpacePushRequest(space.name, space.idPrefix, payload),
            )
            .also { it.recordIfAuthExpired(link.account) }
            .flatMap { pushed ->
                advanceLink(
                    link.copy(
                        lastSyncedRevision = pushed.revision,
                        lastSyncedAtEpochSeconds = pushed.updatedAtEpochSeconds,
                    ),
                    startedIn,
                )
                Outcome.Success(Uploaded(pushed.revision))
            }
    }

    /**
     * Uploads over whatever the server currently holds, discarding the other device's copy.
     *
     * Only offered after a conflict has been shown, and only ever on the user's word: the remote
     * revision is read first so the write is still guarded — between the read and the write a
     * third device could arrive, and it should lose rather than be silently overwritten.
     */
    suspend fun uploadOverwriting(spaceId: String): Outcome<Uploaded> {
        val link = linkFor(spaceId) ?: return notLinked()
        val address = addressOf(link.account) ?: return badAddress()
        val token = tokenFor(link.account) ?: return signInAgain()
        return gateway.fetchSpace(address, token, link.remoteSpaceId)
            .also { it.recordIfAuthExpired(link.account) }
            .flatMap { fetched ->
                val revision = when (fetched) {
                    is FetchedSpace.Fresh -> fetched.snapshot.revision
                    is FetchedSpace.Unchanged -> fetched.revision
                }
                uploadAt(link, revision)
            }
    }

    private suspend fun uploadAt(link: RemoteSpaceLink, revision: Long): Outcome<Uploaded> {
        val startedIn = currentEpoch()
        val address = addressOf(link.account) ?: return badAddress()
        val token = tokenFor(link.account) ?: return signInAgain()
        val space = repository.getSpaceById(link.spaceId)
            ?: return Outcome.Failure(RemoteError.Malformed("that space is no longer on this device"))
        val payload = exportOf(link.spaceId) ?: return exportFailed()

        return gateway
            .updateSpace(
                address,
                token,
                link.remoteSpaceId,
                revision,
                SpacePushRequest(space.name, space.idPrefix, payload),
            )
            .also { it.recordIfAuthExpired(link.account) }
            .flatMap { pushed ->
                advanceLink(
                    link.copy(
                        lastSyncedRevision = pushed.revision,
                        lastSyncedAtEpochSeconds = pushed.updatedAtEpochSeconds,
                    ),
                    startedIn,
                )
                Outcome.Success(Uploaded(pushed.revision))
            }
    }

    // ------------------------------------------------------------------- downloads

    /** What [account] has on its server, without moving any of it. */
    suspend fun listRemoteSpaces(account: AccountKey): Outcome<List<SpaceSummary>> {
        val address = addressOf(account) ?: return badAddress()
        val token = tokenFor(account) ?: return signInAgain()
        return gateway.listSpaces(address, token).also { it.recordIfAuthExpired(account) }
    }

    /**
     * How the server describes one space, without downloading it.
     *
     * What the conflict dialog shows: nobody should be asked to discard a copy they have been told
     * nothing about. The listing carries names and sizes and no payloads, so this costs one small
     * request however large the space is.
     */
    suspend fun remoteSummaryOf(spaceId: String): Outcome<SpaceSummary> {
        val link = linkFor(spaceId) ?: return notLinked()
        return listRemoteSpaces(link.account).flatMap { summaries ->
            summaries.firstOrNull { it.remoteId == link.remoteSpaceId }
                ?.let { Outcome.Success(it) }
                ?: Outcome.Failure(RemoteError.NotFound)
        }
    }

    /**
     * Brings a space down from the server into a new local space.
     *
     * Deliberately additive: the existing import gives the arriving space a fresh local id and a
     * prefix that does not clash, so nothing already on this device is replaced. A download that
     * overwrote a local space would be the one operation here that can destroy work the user has
     * not backed up.
     */
    suspend fun download(
        account: AccountKey,
        remoteSpaceId: String,
        /**
         * Whether the new space should belong to the server it came from.
         *
         * False when the copy is being brought down beside a space that is *already* linked to
         * that same remote space — during a conflict. Two local spaces pointing at one remote one
         * would fight over it: whichever the user then chose, the other would adopt the winner on
         * its next check, and the copy they downloaded to keep safe would be overwritten by the
         * very thing they overwrote it with.
         */
        link: Boolean = true,
    ): Outcome<Downloaded> {
        val startedIn = currentEpoch()
        val address = addressOf(account) ?: return badAddress()
        val token = tokenFor(account) ?: return signInAgain()

        return gateway.fetchSpace(address, token, remoteSpaceId)
            .also { it.recordIfAuthExpired(account) }
            .flatMap { fetched ->
                val snapshot = when (fetched) {
                    is FetchedSpace.Fresh -> fetched.snapshot
                    // Only returned for a conditional request, and this one is unconditional.
                    is FetchedSpace.Unchanged ->
                        return@flatMap Outcome.Failure(
                            RemoteError.Malformed("the server sent no copy of that space")
                        )
                }
                val imported = runCatching { repository.importSpaceFromJson(snapshot.payload) }
                    .onFailure { failure -> if (failure is CancellationException) throw failure }
                    .getOrNull()
                    ?: return@flatMap Outcome.Failure(
                        RemoteError.Malformed("that space was written by a newer version of the app")
                    )

                val downloadedLink = RemoteSpaceLink(
                    spaceId = imported.id,
                    account = account,
                    remoteSpaceId = remoteSpaceId,
                    lastSyncedRevision = snapshot.revision,
                    lastSyncedAtEpochSeconds = snapshot.updatedAtEpochSeconds,
                )
                if (link) putLink(downloadedLink, startedIn)
                Outcome.Success(Downloaded(imported, downloadedLink))
            }
    }

    /** Whether the server holds something this device has not seen. */
    suspend fun checkForChanges(spaceId: String): Outcome<Boolean> {
        val link = linkFor(spaceId) ?: return notLinked()
        val address = addressOf(link.account) ?: return badAddress()
        val token = tokenFor(link.account) ?: return signInAgain()
        return gateway.fetchSpace(address, token, link.remoteSpaceId, link.lastSyncedRevision)
            .also { it.recordIfAuthExpired(link.account) }
            .map { fetched -> fetched is FetchedSpace.Fresh }
    }

    // -------------------------------------------------------------------- unlinking

    /**
     * Forgets that a space was ever on a server, leaving the server's copy alone.
     *
     * Also called when a space is deleted locally: local space ids are handed out as
     * `space-<count>-<prefix>` and are reused after a deletion, so a link left behind would
     * reattach itself to whichever space next takes that id.
     */
    suspend fun unlink(spaceId: String) = storeLock.withLock {
        links.update { current -> (current ?: SyncSettings()).let {
            it.copy(bySpaceId = it.bySpaceId - spaceId)
        } }
    }

    /**
     * Throws away every link and every token this device holds.
     *
     * What "erase all data" has to mean for the sync feature. Clearing the stores directly rather
     * than walking the links held in a screen's state: that state is fed by a flow, so a link
     * written a moment ago may not have reached it yet, and a link that survived an erase would
     * reattach itself to whichever space next takes the id it names.
     *
     * Each token is also revoked on its server, best effort. A token this device has forgotten
     * cannot be used from here, but one left live on the server is still live for anyone who read
     * the file before the erase.
     */
    suspend fun forgetEverything() {
        val held = storeLock.withLock {
            val tokens = credentials.get()?.tokensByAccount.orEmpty()
            // Bumped inside the lock, so a call already in flight cannot write its result back
            // after this: its epoch is now in the past and every write checks.
            epoch++
            links.set(SyncSettings())
            credentials.set(StoredCredentials())
            tokens
        }
        held.forEach { (storageKey, token) ->
            val account = AccountKey.fromStorageKey(storageKey) ?: return@forEach
            revokeInBackground(account, AuthToken(token))
        }
    }

    /**
     * Asks a server to forget a token, without waiting to hear back.
     *
     * See [revocations] for why nothing waits: the token is already gone from this device, so the
     * user is signed out whatever the server says.
     */
    private fun revokeInBackground(account: AccountKey, token: AuthToken) {
        val address = ServerAddress.parse(account.serverUrl).getOrNull() ?: return
        revocations.launch { gateway.logOut(address, token).onFailure { } }
    }

    /** Removes the server's copy as well, if the revision this device holds is still current. */
    suspend fun deleteRemote(spaceId: String): Outcome<Unit> {
        val link = linkFor(spaceId) ?: return notLinked()
        val address = addressOf(link.account) ?: return badAddress()
        val token = tokenFor(link.account) ?: return signInAgain()
        return gateway.deleteSpace(address, token, link.remoteSpaceId, link.lastSyncedRevision)
            .also { it.recordIfAuthExpired(link.account) }
            .onSuccess { unlink(spaceId) }
    }

    // ------------------------------------------------------------------ the details

    private suspend fun exportOf(spaceId: String): String? =
        runCatching { repository.exportSpaceToJson(spaceId, prettyPrint = false) }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
            .getOrNull()

    /** Writes a link that was not there before. */
    private suspend fun putLink(link: RemoteSpaceLink, startedIn: Long) = storeLock.withLock {
        if (epoch != startedIn) return@withLock
        links.update { current -> (current ?: SyncSettings()).let {
            it.copy(bySpaceId = it.bySpaceId + (link.spaceId to link))
        } }
    }

    /**
     * Moves a link forward, and does nothing unless the link stored under that id is still the
     * same one this upload was about.
     *
     * Presence alone is not enough. Local space ids are handed out as `space-<count>-<prefix>` and
     * come back around: delete a space while its upload is in flight, make another with the same
     * prefix, and the new one takes the same id. A check that only asked "is something there?"
     * would then let the old reply re-point the new space at the old space's server copy — and the
     * revision it wrote would match, so the next upload would overwrite that copy with unrelated
     * content and never show a conflict. Matching on the remote id and account is what makes the
     * two tell apart.
     */
    private suspend fun advanceLink(link: RemoteSpaceLink, startedIn: Long) = storeLock.withLock {
        if (epoch != startedIn) return@withLock
        links.update { current ->
            val existing = current ?: SyncSettings()
            val stored = existing.bySpaceId[link.spaceId]
            if (stored == null ||
                stored.remoteSpaceId != link.remoteSpaceId ||
                stored.account != link.account
            ) {
                existing
            } else {
                existing.copy(bySpaceId = existing.bySpaceId + (link.spaceId to link))
            }
        }
    }

    private suspend fun tokenFor(account: AccountKey): AuthToken? =
        credentials.get()?.tokensByAccount?.get(account.storageKey)?.let(::AuthToken)

    /**
     * Files a token, unless everything was forgotten while it was being minted.
     *
     * A token that arrives too late is revoked rather than merely dropped: the erase that raced it
     * revoked every other token, and one left live on the server because it was issued a moment
     * too late is the one credential the erase failed to remove.
     */
    private suspend fun storeToken(
        account: AccountKey,
        token: AuthToken,
        startedIn: Long,
    ) {
        val filed = storeLock.withLock {
            if (epoch != startedIn) return@withLock false
            credentials.update { current -> (current ?: StoredCredentials()).let {
                it.copy(tokensByAccount = it.tokensByAccount + (account.storageKey to token.value))
            } }
            true
        }
        if (!filed) revokeInBackground(account, token)
    }

    private suspend fun forgetToken(account: AccountKey) = storeLock.withLock {
        credentials.update { current -> (current ?: StoredCredentials()).let {
            it.copy(tokensByAccount = it.tokensByAccount - account.storageKey)
        } }
    }

    /**
     * Throws away a token the server has stopped honouring.
     *
     * Without this the app would keep a dead token and keep offering "sign in again" while
     * silently sending the dead one, so the button would never appear to do anything.
     */
    private suspend fun Outcome<*>.recordIfAuthExpired(account: AccountKey) {
        if (this is Outcome.Failure && error is RemoteError.AuthenticationRequired) {
            forgetToken(account)
        }
    }

    private fun addressOf(account: AccountKey): ServerAddress? =
        ServerAddress.parse(account.serverUrl).getOrNull()

    private companion object {
        fun <T> badAddress(): Outcome<T> = Outcome.Failure(
            RemoteError.InsecureAddress("This space's server address is no longer usable.")
        )

        fun <T> signInAgain(): Outcome<T> = Outcome.Failure(
            RemoteError.AuthenticationRequired("Sign in to this server again to keep syncing.")
        )

        fun <T> notLinked(): Outcome<T> = Outcome.Failure(
            RemoteError.Malformed("this space is not connected to a server")
        )

        fun <T> exportFailed(): Outcome<T> = Outcome.Failure(
            RemoteError.Malformed("this space could not be read, so there was nothing to upload")
        )
    }
}
