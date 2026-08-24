package com.zhelenskiy.zheduler.zheduler.sync

import io.github.xxfast.kstore.KStore
import kotlinx.serialization.Serializable

/**
 * Which account on which server, as one value.
 *
 * A user may hold accounts on more than one server, and the same username on two of them is not
 * the same account; nothing downstream may key on the username alone.
 */
@Serializable
data class AccountKey(
    val serverUrl: String,
    val username: String,
) {
    /** A stable string form, for use as a map key in stored JSON. */
    val storageKey: String get() = "$serverUrl$SEPARATOR$username"

    companion object {
        private const val SEPARATOR = '|'

        /**
         * Reads a [storageKey] back.
         *
         * Split at the *last* separator: a username cannot contain one — the server only accepts
         * letters, digits, dots, dashes and underscores — so everything before it is the address,
         * however many separators an odd address may itself contain.
         */
        fun fromStorageKey(key: String): AccountKey? {
            val separator = key.lastIndexOf(SEPARATOR)
            if (separator <= 0 || separator == key.lastIndex) return null
            return AccountKey(key.substring(0, separator), key.substring(separator + 1))
        }
    }
}

/**
 * What ties one local space to its copy on a server.
 *
 * [lastSyncedRevision] is what makes the next upload safe: it is the revision this device last saw,
 * and the server refuses a write that does not match it. Without it every upload would be an
 * unconditional overwrite of whatever another device had done in the meantime.
 *
 * A link is written *before* the first upload is attempted, at [NOT_UPLOADED]. That is what makes a
 * failed first upload recoverable: without a link there is nothing for a retry to act on, and the
 * space is stranded — offered a Retry button that can only ever answer "this space is not connected
 * to a server". It also pins the remote id, so a retry after a lost reply sends the *same* id and
 * is refused as a conflict rather than leaving a second copy behind.
 */
@Serializable
data class RemoteSpaceLink(
    val spaceId: String,
    val account: AccountKey,
    val remoteSpaceId: String,
    val lastSyncedRevision: Long,
    val lastSyncedAtEpochSeconds: Long,
) {
    /** Whether the server has ever acknowledged a copy of this space. */
    val isUploaded: Boolean get() = lastSyncedRevision >= FIRST_REVISION

    companion object {
        /** No revision: chosen by the user, never yet accepted by the server. */
        const val NOT_UPLOADED: Long = 0L

        /** The revision a server gives a space the first time it stores one. */
        const val FIRST_REVISION: Long = 1L
    }
}

/** Every space this device has linked, by local space id. */
@Serializable
data class RemoteSpaceLinks(
    val bySpaceId: Map<String, RemoteSpaceLink> = emptyMap(),
)

/**
 * The bearer tokens this device holds, by account.
 *
 * Kept in a file of its own rather than beside the links, so that the one file containing
 * credentials is the one file that has to be protected, exported by nothing, and thrown away when
 * the user signs out. See the platform implementations for what "protected" amounts to on each.
 */
@Serializable
data class StoredCredentials(
    val tokensByAccount: Map<String, String> = emptyMap(),
)

/**
 * The two files the sync feature keeps on the device.
 *
 * Separate stores rather than fields of one, because they have different lifetimes: signing out
 * throws away every token and keeps every link, so that signing back in reconnects the spaces the
 * user already had instead of asking them to set each one up again.
 */
expect fun createRemoteSpaceLinkStore(): KStore<RemoteSpaceLinks>

expect fun createCredentialStore(): KStore<StoredCredentials>
