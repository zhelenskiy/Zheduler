@file:OptIn(ExperimentalTime::class)

package com.zhelenskiy.zheduler.zheduler.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhelenskiy.zheduler.zheduler.util.LocalNow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Whether the space on screen may be changed, and why not when it may not.
 *
 * Every screen inside a space reads this before offering a way to write. A space kept on a server
 * is a copy while the server is out of reach, and a copy that can be typed into is a second truth
 * the next refresh would silently throw away — so the affordances go rather than the changes.
 */
data class SpaceEditing(
    val status: CloudSpaceStatus,
    /** Asks the server again. What the banner's button does. */
    val retry: () -> Unit,
    /** Whether the last thing written here was taken back because the server refused it. */
    val changeWasUndone: Boolean = false,
    /** Marks [changeWasUndone] as read. */
    val dismissNotice: () -> Unit = {},
) {
    val isEditable: Boolean get() = status.isEditable

    companion object {
        /**
         * Editable, with nothing to retry.
         *
         * The default for a composition with no gate above it: a local space, and every test that
         * composes a screen on its own.
         */
        val Always = SpaceEditing(CloudSpaceStatus.OnThisDevice, retry = {})
    }
}

val LocalSpaceEditing = compositionLocalOf { SpaceEditing.Always }

/**
 * Puts a space's standing with its server into the composition, and asks the server on entry.
 *
 * Asking on entry is what makes the server the source of truth rather than a backup: opening a
 * space is the moment the user is about to believe what it says, so it is the moment to find out
 * whether the server has moved on. If it has, its copy replaces this device's outright — there is
 * nothing to choose between a space and a stale photograph of it.
 */
@Composable
fun CloudSpaceGate(spaceId: String, cloud: CloudSpaces, content: @Composable () -> Unit) {
    val statuses by cloud.all.collectAsState()
    val undoneSpaces by cloud.rolledBack.collectAsState()
    val scope = rememberCoroutineScope()
    val status = statuses[spaceId] ?: CloudSpaceStatus.OnThisDevice
    // Read rather than listened for. A rollback lands a network timeout after the edit that caused
    // it, by which time this screen may not have been composed — and the notice has to be there
    // when the user comes back and finds their work gone.
    val changeWasUndone = spaceId in undoneSpaces

    LaunchedEffect(spaceId, cloud) { cloud.refresh(spaceId) }

    val editing = remember(status, spaceId, cloud, scope, changeWasUndone) {
        SpaceEditing(
            status = status,
            retry = { scope.launch { cloud.refresh(spaceId) } },
            changeWasUndone = changeWasUndone,
            dismissNotice = { cloud.noticeSeen(spaceId) },
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalSpaceEditing provides editing,
        content = content,
    )
}

/**
 * What the space's server is doing, above the content, when it is doing anything worth saying.
 *
 * Silent while the space is in step or belongs to this device, which is nearly always — a bar
 * that is always there is a bar nobody reads.
 */
@Composable
fun CloudSpaceBanner(modifier: Modifier = Modifier) {
    val editing = LocalSpaceEditing.current
    when (val status = editing.status) {
        is CloudSpaceStatus.OnThisDevice, is CloudSpaceStatus.Live, is CloudSpaceStatus.Saving ->
            // Silent unless something was lost. A change that did not survive is worth saying
            // even once the space is well again — being in step now is not an answer to "where
            // did my task go", and the user has to be the one who decides they have read it.
            if (editing.changeWasUndone) {
                Banner(
                    modifier = modifier,
                    tag = UNDONE_TAG,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    onContainer = MaterialTheme.colorScheme.onSurfaceVariant,
                    headline = "A change made here did not survive",
                    detail = "The server had a newer copy of this space, and it replaced what " +
                        "was here. Anything typed in the meantime is not in it.",
                    leading = { Icon(Icons.Default.CloudOff, contentDescription = null) },
                    onRetry = null,
                    action = "Got it" to editing.dismissNotice,
                )
            }

        is CloudSpaceStatus.Checking -> Banner(
            modifier = modifier,
            tag = CHECKING_TAG,
            container = MaterialTheme.colorScheme.surfaceVariant,
            onContainer = MaterialTheme.colorScheme.onSurfaceVariant,
            headline = "Checking the server…",
            detail = "Changes are held until this device knows what the server has.",
            leading = {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onRetry = null,
        )

        is CloudSpaceStatus.Offline -> Banner(
            modifier = modifier,
            tag = OFFLINE_TAG,
            container = MaterialTheme.colorScheme.surfaceVariant,
            onContainer = MaterialTheme.colorScheme.onSurfaceVariant,
            headline = "Offline — showing the copy from ${agoOf(status.asOf)}",
            detail = if (editing.changeWasUndone) {
                "Your last change could not be sent to ${status.link.account.serverUrl}, so it " +
                    "was undone. Editing is off until the server can be reached again."
            } else {
                "This space is kept on ${status.link.account.serverUrl}. " +
                    "Editing is off until it can be reached again."
            },
            leading = { Icon(Icons.Default.CloudOff, contentDescription = null) },
            onRetry = editing.retry,
        )

        is CloudSpaceStatus.Blocked -> Banner(
            modifier = modifier,
            tag = BLOCKED_TAG,
            container = MaterialTheme.colorScheme.errorContainer,
            onContainer = MaterialTheme.colorScheme.onErrorContainer,
            headline = "This space cannot be changed",
            detail = undoneFirst(editing.changeWasUndone) +
                status.error.message +
                whereToGo(status.error.remedy),
            leading = { Icon(Icons.Default.ErrorOutline, contentDescription = null) },
            // Offered only where repeating the request could actually work. Signing in and
            // choosing between two copies both happen on the space list, and a button here that
            // re-ran the same refusal would read as the app not listening.
            onRetry = editing.retry.takeIf { status.error.remedy.isWorthRepeating },
        )
    }
}

@Composable
private fun Banner(
    modifier: Modifier,
    tag: String,
    container: androidx.compose.ui.graphics.Color,
    onContainer: androidx.compose.ui.graphics.Color,
    headline: String,
    detail: String,
    leading: @Composable () -> Unit,
    onRetry: (() -> Unit)?,
    action: Pair<String, () -> Unit>? = null,
) {
    Surface(color = container, contentColor = onContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading()
            Column(modifier = Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.bodyMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (onRetry != null) {
                TextButton(onClick = onRetry, modifier = Modifier.testTag(RETRY_TAG)) {
                    Text("Try again")
                }
            }
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick, modifier = Modifier.testTag(DISMISS_TAG)) {
                    Text(label)
                }
            }
        }
    }
}

/**
 * How old the copy on screen is, in the words someone would use out loud.
 *
 * Rounded down on purpose: "2 hours ago" for something 2h59m old understates nothing that
 * matters, where a bare timestamp would make the reader do the subtraction themselves.
 */
@Composable
private fun agoOf(asOf: Instant?): String {
    if (asOf == null) return "before this device last knew"
    val now = LocalNow.current
    val age = now - asOf
    return when {
        age < 1.minutes -> "a moment ago"
        age < 1.hours -> "${age.inWholeMinutes} min ago"
        age < 1.days -> "${age.inWholeHours} h ago"
        else -> "${age.inWholeDays} days ago"
    }
}

/**
 * Said before the reason, because it is the part the user is looking for.
 *
 * A refusal takes the change back out, so the screen behind this banner is missing something that
 * was there a moment ago. Explaining only why the server said no leaves that unaccounted for.
 */
private fun undoneFirst(changeWasUndone: Boolean): String =
    if (changeWasUndone) "Your last change was undone. " else ""

/** Where the thing that would actually help lives, when it is not on this screen. */
private fun whereToGo(remedy: RemoteRemedy): String = when (remedy) {
    RemoteRemedy.SignIn -> " Sign in again from the space list."
    RemoteRemedy.ResolveConflict -> " Choose which copy to keep from the space list."
    else -> ""
}

private val RemoteRemedy.isWorthRepeating: Boolean
    get() = this == RemoteRemedy.Retry || this == RemoteRemedy.RetryLater

internal const val CHECKING_TAG = "cloud-banner-checking"
internal const val UNDONE_TAG = "cloud-banner-undone"
internal const val DISMISS_TAG = "cloud-banner-dismiss"
internal const val OFFLINE_TAG = "cloud-banner-offline"
internal const val BLOCKED_TAG = "cloud-banner-blocked"
internal const val RETRY_TAG = "cloud-banner-retry"
