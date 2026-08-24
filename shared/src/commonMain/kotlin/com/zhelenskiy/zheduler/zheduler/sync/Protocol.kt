package com.zhelenskiy.zheduler.zheduler.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire format between the app and its sync server, compiled into both.
 *
 * The server never parses a space's contents: a space arrives as one opaque JSON string, the same
 * text `exportSpaceToJson` writes, and is handed back byte for byte. That keeps the server out of
 * the app's schema entirely — an old server serves a new client — and leaves it with no parser
 * pointed at untrusted data.
 */
object SyncProtocol {
    const val API_VERSION: Int = 1
    const val BASE_PATH: String = "/api/v1"

    /** What [ServerInfo.service] has to say for an address to be this kind of server. */
    const val SERVICE_NAME: String = "zheduler-sync"

    /**
     * The largest space the server will store, in bytes of payload.
     *
     * Enforced on both sides: the client so a doomed upload is not attempted, the server because
     * a client is not something to trust about its own size.
     */
    const val MAX_PAYLOAD_BYTES: Int = 8 * 1024 * 1024

    const val MAX_USERNAME_LENGTH: Int = 64
    const val MIN_USERNAME_LENGTH: Int = 3
    const val MIN_PASSWORD_LENGTH: Int = 12

    /**
     * Bounded because the hashing cost is paid by the server before anything is verified, and an
     * unbounded password is therefore an unbounded amount of work an anonymous caller can order.
     */
    const val MAX_PASSWORD_LENGTH: Int = 256

    const val MAX_SPACE_NAME_LENGTH: Int = 200

    /** Usernames are compared and stored lower-cased, so two accounts cannot differ only in case. */
    val USERNAME_PATTERN: Regex = Regex("^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$")
}

@Serializable
data class ServerInfo(
    val service: String,
    val apiVersion: Int,
)

@Serializable
data class AuthRequest(
    val username: String,
    val password: String,
)

@Serializable
data class AuthResponse(
    val token: String,
    val userId: String,
    val username: String,
    val expiresAtEpochSeconds: Long,
)

@Serializable
data class AccountInfo(
    val userId: String,
    val username: String,
)

/** One space as the list endpoint describes it: everything but the payload, which is the big part. */
@Serializable
data class SpaceSummary(
    val remoteId: String,
    val name: String,
    val idPrefix: String,
    val revision: Long,
    val updatedAtEpochSeconds: Long,
    val payloadBytes: Long,
)

@Serializable
data class SpaceSnapshot(
    val remoteId: String,
    val name: String,
    val idPrefix: String,
    val revision: Long,
    val updatedAtEpochSeconds: Long,
    val payload: String,
)

@Serializable
data class SpacePushRequest(
    val name: String,
    val idPrefix: String,
    val payload: String,
)

@Serializable
data class SpacePushResponse(
    val remoteId: String,
    val revision: Long,
    val updatedAtEpochSeconds: Long,
)

/**
 * The answer to a download that may not have needed to happen.
 *
 * [Unchanged] is what a conditional request gets when the caller already holds that revision; it
 * is a distinct case rather than a null so that "nothing new" cannot be mistaken for "nothing
 * there".
 */
sealed interface FetchedSpace {
    data class Fresh(val snapshot: SpaceSnapshot) : FetchedSpace
    data class Unchanged(val revision: Long) : FetchedSpace
}

/**
 * Why the server refused, as a value rather than a sentence.
 *
 * The sentence travels alongside in [ApiError.message] for display, but decisions are made on the
 * code — a client that matched on wording would break the moment the wording improved.
 */
@Serializable
enum class ApiErrorCode {
    @SerialName("invalid_request")
    InvalidRequest,

    @SerialName("invalid_credentials")
    InvalidCredentials,

    @SerialName("username_taken")
    UsernameTaken,

    @SerialName("weak_password")
    WeakPassword,

    @SerialName("unauthenticated")
    Unauthenticated,

    @SerialName("not_found")
    NotFound,

    @SerialName("revision_mismatch")
    RevisionMismatch,

    @SerialName("precondition_required")
    PreconditionRequired,

    @SerialName("payload_too_large")
    PayloadTooLarge,

    @SerialName("rate_limited")
    RateLimited,

    @SerialName("internal")
    Internal,
}

/**
 * The body of every refusal.
 *
 * [currentRevision] is filled in only for [ApiErrorCode.RevisionMismatch], where the caller needs
 * to know what it lost to in order to do anything about it.
 */
@Serializable
data class ApiError(
    val code: ApiErrorCode,
    val message: String,
    val currentRevision: Long? = null,
)
