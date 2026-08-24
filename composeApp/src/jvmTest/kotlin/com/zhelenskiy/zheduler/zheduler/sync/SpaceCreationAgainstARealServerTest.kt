@file:OptIn(ExperimentalTime::class, ExperimentalTestApi::class)

package com.zhelenskiy.zheduler.zheduler.sync

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.zhelenskiy.zheduler.zheduler.InMemoryTaskRepository
import com.zhelenskiy.zheduler.zheduler.ServerConfig
import com.zhelenskiy.zheduler.zheduler.StorageConfig
import com.zhelenskiy.zheduler.zheduler.components.dialogs.NewSpaceDialog
import com.zhelenskiy.zheduler.zheduler.components.dialogs.RemoteSetupTags
import com.zhelenskiy.zheduler.zheduler.store.InMemorySyncStore
import com.zhelenskiy.zheduler.zheduler.syncModule
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListContainer
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListIntent
import com.zhelenskiy.zheduler.zheduler.viewmodels.SpaceListState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import pro.respawn.flowmvi.dsl.subscribe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * The whole feature, used: a real Netty server, the real HTTP gateway, the real view model, and
 * the real dialog clicked through the way a person would.
 *
 * Everything below this is tested against a stand-in for something — the dialog against a state
 * machine, the gateway against a mock engine, the store against a fake gateway. This is the one
 * that would notice if those pieces disagreed: a test tag that moved, a button that stays disabled
 * because the state it waits on never arrives, an address the client builds and the server does
 * not route.
 */
class SpaceCreationAgainstARealServerTest {

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var syncClient: HttpClient
    private lateinit var repository: InMemoryTaskRepository
    private lateinit var sync: SpaceSyncService
    private lateinit var container: SpaceListContainer
    private var serverPort = 0
    private val serverStore = InMemorySyncStore()

    private val seen = MutableStateFlow(SpaceListState())
    private var screenScope: CoroutineScope? = null

    private val serverUrl get() = "http://127.0.0.1:$serverPort"

    // Not inside a coroutine: EmbeddedServer.start blocks on coroutines of its own and deadlocks
    // when called from one.
    @Before
    fun startEverything() {
        val config = ServerConfig(
            port = 0,
            host = "127.0.0.1",
            storage = StorageConfig.InMemory,
            tokenLifetime = 30.days,
            allowedOrigins = emptyList(),
            trustForwardedHeaders = false,
            strictTransportSecurity = false,
        )
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            syncModule(config, serverStore, Clock.System)
        }
        server.start(wait = false)
        serverPort = runBlocking { server.engine.resolvedConnectors().first().port }

        syncClient = HttpClient(CIO) { installSyncClientDefaults() }
        repository = InMemoryTaskRepository()
        sync = SpaceSyncService(
            gateway = KtorRemoteSpaceGateway(syncClient),
            repository = repository,
            links = inMemoryStore(RemoteSpaceLinks()),
            credentials = inMemoryStore(StoredCredentials()),
        )
        container = SpaceListContainer(
            repository = repository,
            sync = sync,
            newRemoteId = { "remote-under-test" },
            dispatcher = Dispatchers.Unconfined,
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        screenScope = scope
        scope.subscribe(container.store) { state: SpaceListState -> seen.value = state }
    }

    @After
    fun stopEverything() {
        screenScope?.cancel()
        container.close()
        syncClient.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        serverStore.close()
    }

    private fun until(reason: String, predicate: (SpaceListState) -> Boolean): SpaceListState =
        runBlocking {
            try {
                withTimeout(15_000) { seen.first(predicate) }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("timed out waiting for $reason; the state was ${seen.value}")
            }
        }

    @Test
    fun aPersonCanPointANewSpaceAtAServer_signUp_andHaveItUploaded() = runComposeUiTest {
        var created = false
        setContent {
            val state by seen.collectAsState()
            NewSpaceDialog(
                onDismiss = {},
                onSpaceCreated = { name, prefix, account ->
                    created = true
                    container.store.intent(SpaceListIntent.AddSpace(name, prefix, account))
                },
                remoteSetup = state.remoteSetup,
                onRemoteSetupChange = { container.store.intent(SpaceListIntent.UpdateRemoteSetup(it)) },
                onCheckServer = { container.store.intent(SpaceListIntent.CheckRemoteServer) },
                onAuthenticate = { container.store.intent(SpaceListIntent.AuthenticateRemote) },
            )
        }
        waitForIdle()

        // 1. Name the space.
        onNodeWithText("Space Name").performTextInput("Work")
        onNodeWithText("ID Prefix (e.g., WORK, HOME)").performTextInput("WRK")
        waitForIdle()

        // 2. Ask for it to be kept on a server, and give the address.
        onNodeWithTag(RemoteSetupTags.TOGGLE).performClick()
        waitForIdle()
        onNodeWithText("Create").assertIsNotEnabled()

        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput(serverUrl)
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.CONNECT).assertIsEnabled().performClick()

        // 3. The server answers, and the credentials appear.
        until("the server to answer") { it.remoteSetup?.stage is RemoteSetupStage.Authenticating }
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.USERNAME).assertIsDisplayed()

        // 4. Make an account and sign in.
        onNodeWithText("Create an account").performClick()
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.USERNAME).performTextInput("ada")
        onNodeWithTag(RemoteSetupTags.PASSWORD).performTextInput("a long enough password")
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.SUBMIT).assertIsEnabled().performClick()

        until("the sign-up to finish") { it.remoteSetup?.stage is RemoteSetupStage.Ready }
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.SIGNED_IN).assertIsDisplayed()

        // 5. Create the space, which uploads it.
        onNodeWithText("Create").assertIsEnabled().performClick()
        waitForIdle()
        assertTrue(created)

        val spaceId = runBlocking { repository.getAllSpaces().single().id }
        val state = until("the upload to be recorded") { it.remoteLinks.containsKey(spaceId) }
        val link = assertNotNull(state.remoteLinks[spaceId])
        assertEquals(1L, link.lastSyncedRevision)
        assertEquals("ada", link.account.username)
        assertEquals(serverUrl, link.account.serverUrl)

        // 6. And the server really has it: fetched back over HTTP, it is this space's export.
        val fetched = runBlocking {
            val summaries = assertNotNull(sync.listRemoteSpaces(link.account).getOrNull())
            assertEquals(listOf("remote-under-test"), summaries.map { it.remoteId })
            assertEquals("Work", summaries.single().name)
            assertNotNull(repository.exportSpaceToJson(spaceId, prettyPrint = false))
        }
        assertTrue(fetched.contains("\"WRK\""), "the uploaded space should carry its own prefix")
    }

    @Test
    fun aWrongPasswordIsShownAndCreationStaysBlocked() = runComposeUiTest {
        // The account exists with one password; the dialog is given another.
        runBlocking {
            assertNotNull(
                sync.signUp(
                    ServerAddress.parse(serverUrl).getOrNull()!!,
                    "ada",
                    "the real password here",
                ).getOrNull()
            )
        }

        setContent {
            val state by seen.collectAsState()
            NewSpaceDialog(
                onDismiss = {},
                onSpaceCreated = { _, _, _ -> },
                remoteSetup = state.remoteSetup,
                onRemoteSetupChange = { container.store.intent(SpaceListIntent.UpdateRemoteSetup(it)) },
                onCheckServer = { container.store.intent(SpaceListIntent.CheckRemoteServer) },
                onAuthenticate = { container.store.intent(SpaceListIntent.AuthenticateRemote) },
            )
        }
        waitForIdle()

        onNodeWithText("Space Name").performTextInput("Work")
        onNodeWithText("ID Prefix (e.g., WORK, HOME)").performTextInput("WRK")
        onNodeWithTag(RemoteSetupTags.TOGGLE).performClick()
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput(serverUrl)
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.CONNECT).performClick()
        until("the server to answer") { it.remoteSetup?.stage is RemoteSetupStage.Authenticating }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.USERNAME).performTextInput("ada")
        onNodeWithTag(RemoteSetupTags.PASSWORD).performTextInput("not the real password")
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.SUBMIT).performClick()

        until("the password to be rejected") {
            (it.remoteSetup?.stage as? RemoteSetupStage.Authenticating)?.error != null
        }
        waitForIdle()

        onNodeWithText("That username and password do not match.").assertIsDisplayed()
        // Not "Try again": repeating a password the server has already refused cannot work.
        onNodeWithText("Try again").assertDoesNotExist()
        onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun aServerThatIsNotThereIsReportedWithARetryThatWorksOnceItIs() = runComposeUiTest {
        setContent {
            val state by seen.collectAsState()
            NewSpaceDialog(
                onDismiss = {},
                onSpaceCreated = { _, _, _ -> },
                remoteSetup = state.remoteSetup,
                onRemoteSetupChange = { container.store.intent(SpaceListIntent.UpdateRemoteSetup(it)) },
                onCheckServer = { container.store.intent(SpaceListIntent.CheckRemoteServer) },
                onAuthenticate = { container.store.intent(SpaceListIntent.AuthenticateRemote) },
            )
        }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.TOGGLE).performClick()
        waitForIdle()
        // Nothing listens on port 1.
        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput("http://127.0.0.1:1")
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.CONNECT).performClick()

        until("the failure to be shown") {
            (it.remoteSetup?.stage as? RemoteSetupStage.Addressing)?.error != null
        }
        waitForIdle()
        onNodeWithText("Try again").assertIsDisplayed()

        // Point it at the server that is actually running, and the same button gets through.
        onNodeWithTag(RemoteSetupTags.ADDRESS).performTextInput("")
        container.store.intent(
            SpaceListIntent.UpdateRemoteSetup(
                RemoteSetup.addressEdited(assertNotNull(seen.value.remoteSetup), serverUrl)
            )
        )
        until("the new address to be held") { it.remoteSetup?.addressText == serverUrl }
        waitForIdle()

        onNodeWithTag(RemoteSetupTags.CONNECT).performClick()
        until("the server to answer") { it.remoteSetup?.stage is RemoteSetupStage.Authenticating }
        waitForIdle()
        onNodeWithTag(RemoteSetupTags.USERNAME).assertIsDisplayed()
    }
}
