package com.zhelenskiy.zheduler.zheduler.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A server this device has used, and how much still depends on it. */
data class KnownServerEntry(
    val server: KnownServer,
    /** How many spaces keep their truth here. Any at all means it cannot be forgotten. */
    val spacesHeld: Int,
)

/**
 * The servers this device has signed in to, for the settings list.
 *
 * Reached through the composition rather than passed down, for the reason [LocalKnownServers]
 * gives: the cog is meant to open the same list on every screen, and only one screen holds the
 * sync service.
 */
interface KnownServerBook {

    val servers: List<KnownServerEntry>

    /** Signs out of [url] and drops it from the list. Only offered when nothing is held there. */
    fun forget(url: String)

    companion object {
        val Empty: KnownServerBook = object : KnownServerBook {
            override val servers: List<KnownServerEntry> = emptyList()
            override fun forget(url: String) = Unit
        }
    }
}

/**
 * The book, reachable from anywhere in the composition.
 *
 * Empty by default, so a build with no sync wired up — and every test that composes the settings
 * dialog on its own — simply has no Servers row rather than a broken one.
 */
val LocalKnownServers = compositionLocalOf { KnownServerBook.Empty }

/** Puts the real list into the composition, from the service that owns it. */
@Composable
fun KnownServersProvider(sync: SpaceSyncService, content: @Composable () -> Unit) {
    val knownServers by sync.knownServers.collectAsState(emptyList())
    val heldByUrl by remember(sync) {
        sync.linksBySpaceId.map { links ->
            links.values.groupingBy { it.account.serverUrl }.eachCount()
        }
    }.collectAsState(emptyMap())
    val scope = rememberCoroutineScope()

    val book = remember(knownServers, heldByUrl, sync, scope) {
        object : KnownServerBook {
            override val servers: List<KnownServerEntry> = knownServers.map { server ->
                KnownServerEntry(server, heldByUrl[server.url] ?: 0)
            }

            override fun forget(url: String) {
                scope.launch {
                    // Refused when a space started depending on this server between the list being
                    // drawn and the button being pressed. Nothing to report: the row stays, now
                    // without its button, which is the answer.
                    if (!sync.forgetServer(url)) return@launch
                }
            }
        }
    }
    CompositionLocalProvider(LocalKnownServers provides book, content = content)
}
