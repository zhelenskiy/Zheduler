package com.zhelenskiy.zheduler.zheduler.sync

import kotlin.jvm.JvmInline

/**
 * A bearer token, wrapped so it cannot be passed where a username goes and cannot be logged by
 * accident: [toString] deliberately does not contain it.
 */
@JvmInline
value class AuthToken(val value: String) {
    override fun toString(): String = "AuthToken(***)"
}

/**
 * Everything the app asks of a sync server.
 *
 * Every method returns an [Outcome], and the interface is marked [MustUseReturnValues], so a call
 * whose failure is dropped on the floor is a compiler warning rather than a silent no-op. The
 * token is a parameter rather than state on the gateway: one instance then serves however many
 * accounts and servers the user has, and a test can hand it a token that is deliberately wrong.
 */
@MustUseReturnValues
interface RemoteSpaceGateway {

    /** Whether this address is a sync server at all, and one this build can talk to. */
    suspend fun serverInfo(address: ServerAddress): Outcome<ServerInfo>

    suspend fun register(address: ServerAddress, username: String, password: String): Outcome<AuthResponse>

    suspend fun logIn(address: ServerAddress, username: String, password: String): Outcome<AuthResponse>

    /** Revokes [token] on the server. Succeeds even if it had already expired. */
    suspend fun logOut(address: ServerAddress, token: AuthToken): Outcome<Unit>

    /** Who [token] belongs to; the cheapest way to find out whether it is still good. */
    suspend fun account(address: ServerAddress, token: AuthToken): Outcome<AccountInfo>

    /** Every space this account has on this server, without their contents. */
    suspend fun listSpaces(address: ServerAddress, token: AuthToken): Outcome<List<SpaceSummary>>

    /**
     * Downloads a space, skipping the body when the caller already has it.
     *
     * [knownRevision] is what the caller holds; passing it turns this into a conditional request,
     * and an unchanged space comes back as [FetchedSpace.Unchanged] with no payload transferred.
     */
    suspend fun fetchSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        knownRevision: Long? = null,
    ): Outcome<FetchedSpace>

    /**
     * Uploads a space that the server must not already have.
     *
     * Fails with [RemoteError.Conflict] if it does, which is how two devices creating the same
     * space at once is resolved rather than one of them overwriting the other.
     */
    suspend fun createSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        request: SpacePushRequest,
    ): Outcome<SpacePushResponse>

    /**
     * Uploads a space over a revision the caller has seen.
     *
     * [expectedRevision] is the last revision downloaded. If the server has moved past it the
     * upload is refused with [RemoteError.Conflict] carrying the revision it actually holds, so
     * nothing is lost by a device that was working from a stale copy.
     */
    suspend fun updateSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        expectedRevision: Long,
        request: SpacePushRequest,
    ): Outcome<SpacePushResponse>

    suspend fun deleteSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        expectedRevision: Long,
    ): Outcome<Unit>
}
