package com.zhelenskiy.zheduler.zheduler.sync

import io.github.xxfast.kstore.Codec
import io.github.xxfast.kstore.KStore
import kotlinx.serialization.Serializable

/** A store that keeps its value in a variable, so a test needs no filesystem and no browser. */
class InMemoryCodec<T : @Serializable Any>(private var value: T? = null) : Codec<T> {
    override suspend fun encode(value: T?) {
        this.value = value
    }

    override suspend fun decode(): T? = value
}

fun <T : @Serializable Any> inMemoryStore(default: T): KStore<T> =
    KStore(default = default, enableCache = true, codec = InMemoryCodec())

/**
 * A gateway that answers from a script.
 *
 * Each method returns whatever the corresponding lambda says, so a test can make one call fail
 * without also having to describe the other nine. Every call is recorded, which is how "the token
 * was thrown away after a 401" is checked.
 */
class FakeRemoteSpaceGateway(
    var onServerInfo: suspend (ServerAddress) -> Outcome<ServerInfo> = {
        Outcome.Success(ServerInfo(SyncProtocol.SERVICE_NAME, SyncProtocol.API_VERSION))
    },
    var onRegister: suspend (String, String) -> Outcome<AuthResponse> = { username, _ ->
        Outcome.Success(AuthResponse("token-for-$username", "user-$username", username, 0))
    },
    var onLogIn: suspend (String, String) -> Outcome<AuthResponse> = { username, _ ->
        Outcome.Success(AuthResponse("token-for-$username", "user-$username", username, 0))
    },
    var onLogOut: suspend (AuthToken) -> Outcome<Unit> = { Outcome.Success(Unit) },
    var onAccount: suspend (AuthToken) -> Outcome<AccountInfo> = {
        Outcome.Success(AccountInfo("user", "user"))
    },
    var onListSpaces: suspend (AuthToken) -> Outcome<List<SpaceSummary>> = { Outcome.Success(emptyList()) },
    var onFetch: suspend (AuthToken, String, Long?) -> Outcome<FetchedSpace> = { _, _, _ ->
        Outcome.Failure(RemoteError.NotFound)
    },
    var onCreate: suspend (AuthToken, String, SpacePushRequest) -> Outcome<SpacePushResponse> =
        { _, remoteId, _ -> Outcome.Success(SpacePushResponse(remoteId, 1, 100)) },
    var onUpdate: suspend (AuthToken, String, Long, SpacePushRequest) -> Outcome<SpacePushResponse> =
        { _, remoteId, revision, _ -> Outcome.Success(SpacePushResponse(remoteId, revision + 1, 200)) },
    var onDelete: suspend (AuthToken, String, Long) -> Outcome<Unit> = { _, _, _ -> Outcome.Success(Unit) },
) : RemoteSpaceGateway {

    val calls = mutableListOf<String>()

    /** Every push the fake was given, so a test can assert on what was actually uploaded. */
    val pushes = mutableListOf<SpacePushRequest>()

    override suspend fun serverInfo(address: ServerAddress): Outcome<ServerInfo> {
        calls += "serverInfo"
        return onServerInfo(address)
    }

    override suspend fun register(
        address: ServerAddress,
        username: String,
        password: String,
    ): Outcome<AuthResponse> {
        calls += "register"
        return onRegister(username, password)
    }

    override suspend fun logIn(
        address: ServerAddress,
        username: String,
        password: String,
    ): Outcome<AuthResponse> {
        calls += "logIn"
        return onLogIn(username, password)
    }

    override suspend fun logOut(address: ServerAddress, token: AuthToken): Outcome<Unit> {
        calls += "logOut"
        return onLogOut(token)
    }

    override suspend fun account(address: ServerAddress, token: AuthToken): Outcome<AccountInfo> {
        calls += "account"
        return onAccount(token)
    }

    override suspend fun listSpaces(
        address: ServerAddress,
        token: AuthToken,
    ): Outcome<List<SpaceSummary>> {
        calls += "listSpaces"
        return onListSpaces(token)
    }

    override suspend fun fetchSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        knownRevision: Long?,
    ): Outcome<FetchedSpace> {
        calls += "fetchSpace"
        return onFetch(token, remoteId, knownRevision)
    }

    override suspend fun createSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        request: SpacePushRequest,
    ): Outcome<SpacePushResponse> {
        calls += "createSpace"
        pushes += request
        return onCreate(token, remoteId, request)
    }

    override suspend fun updateSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        expectedRevision: Long,
        request: SpacePushRequest,
    ): Outcome<SpacePushResponse> {
        calls += "updateSpace"
        pushes += request
        return onUpdate(token, remoteId, expectedRevision, request)
    }

    override suspend fun deleteSpace(
        address: ServerAddress,
        token: AuthToken,
        remoteId: String,
        expectedRevision: Long,
    ): Outcome<Unit> {
        calls += "deleteSpace"
        return onDelete(token, remoteId, expectedRevision)
    }
}

/** The address the tests use. Loopback, so it is one `ServerAddress` will accept over plain http. */
fun testAddress(): ServerAddress =
    (ServerAddress.parse("http://127.0.0.1:8080") as Outcome.Success).value
