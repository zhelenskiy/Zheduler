package com.zhelenskiy.zheduler.zheduler.geo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * A desktop knows which wifi network it is on, and nothing about bluetooth.
 *
 * The network is worth having: it is the one thing a desktop can say about where it is, and for a
 * machine that moves between home and an office it says it well. There is no portable API for it,
 * so each system is asked in its own words — and any system not answered for here simply leaves
 * wifi unmeasured, which fires nothing.
 *
 * Bluetooth is not attempted. The JVM has no bluetooth of its own, every route to it is a native
 * library per platform, and a desktop is rarely the thing a rule about a car or a pair of
 * headphones is written on.
 */
actual fun createSignalSource(): SignalSource = DesktopSignalSource

actual val supportedSignalKinds: Set<SignalKind> = setOf(SignalKind.Wifi)

private object DesktopSignalSource : SignalSource {
    override suspend fun nearby(): NearbySignals {
        val ssid = joinedNetwork() ?: return NearbySignals.Unknown
        return NearbySignals(
            kinds = setOf(SignalKind.Wifi),
            present = if (ssid.isEmpty()) emptySet() else setOf(NearbySignal.Wifi(ssid).key),
        )
    }
}

/**
 * The joined network's name, `""` for "on none", or null for "cannot tell".
 *
 * Null covers both an unknown system and a command that is not there — macOS has moved this
 * between tools more than once — and null is the safe answer: it leaves wifi rules unanswered
 * rather than reporting every network gone.
 */
internal suspend fun joinedNetwork(): String? = withContext(Dispatchers.IO) {
    // Remembered for a moment. The picker asks twice as it opens — once for what to offer and once
    // for whether this machine can answer at all — and on a Mac each ask is several subprocesses.
    memo.recent()?.let { return@withContext it.value }
    val answer = when {
        isWindows() -> windowsNetwork()
        isMac() -> macNetwork()
        "nux" in osName() || "nix" in osName() -> linuxNetwork()
        else -> null
    }
    // "" is a real answer — on no network — and only a name can be mangled.
    val checked = if (answer.isNullOrEmpty()) answer else decodedName(answer)
    memo.remember(checked)
    checked
}

private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()
private fun isWindows(): Boolean = "win" in osName()
private fun isMac(): Boolean = "mac" in osName() || "darwin" in osName()

/**
 * The last answer, for as long as it is worth reusing.
 *
 * Short enough that a sweep always asks afresh — a network joined a second ago should be seen —
 * and long enough to cover one screen asking the same question twice as it opens.
 */
private object memo {
    private const val VALID_FOR_MS = 2_000L
    private var at: Long = 0
    private var answer: Box? = null

    class Box(val value: String?)

    @Synchronized
    fun recent(): Box? = answer?.takeIf { System.nanoTime() / 1_000_000 - at < VALID_FOR_MS }

    @Synchronized
    fun remember(value: String?) {
        answer = Box(value)
        at = System.nanoTime() / 1_000_000
    }
}

/**
 * What macOS says, asked two ways.
 *
 * `networksetup -getairportnetwork` cannot be trusted on its own any more: on a Mac without
 * location authorisation it prints "You are not associated with an AirPort network" *while joined
 * to one* — verified on the machine this was written on, whose `en0` was active, addressed and
 * associated at the time. Believing it turns "the system will not tell me" into "on no network",
 * which is the one mistake this whole design exists to avoid: a rule about leaving the office wifi
 * would fire in the office.
 *
 * So `ipconfig getsummary` is asked first, because it distinguishes the two — a joined interface
 * has an `SSID` line, redacted or not, and an unassociated one has none. `networksetup` is kept as
 * the fallback for the older systems where the summary carries no SSID at all.
 */
private suspend fun macNetwork(): String? {
    // The interface is not always en0 — a Mac with more than one radio, or an older one. Each is
    // asked in turn and the first that is on a network wins.
    val devices = wifiDevices(run("networksetup", "-listallhardwareports"))

    var sawUnassociated = false
    var anyWithheld = false
    devices.forEach { device ->
        val summary = run("ipconfig", "getsummary", device)?.let(::parseMacSummary)
        if (summary is WifiName.Named) return summary.ssid
        if (summary == WifiName.Withheld) {
            // Joined to something the system will not name. Decisive: there is a network, and
            // saying "none" about it is worse than saying nothing at all.
            anyWithheld = true
            return@forEach
        }
        // Either no SSID line — an older system whose summary carries none, or an interface that
        // is genuinely on nothing — or no summary at all. Both need a second opinion before "on no
        // network" is believed, because that answer is what fires a rule about leaving one.
        val line = run("networksetup", "-getairportnetwork", device) ?: return@forEach
        parseMacNetwork(line)?.let { return it }
        // Both asked, and both say there is nothing. Only now is it a real answer.
        if (summary == WifiName.NotAssociated) sawUnassociated = true
    }
    return when {
        anyWithheld -> null
        sawUnassociated -> ""
        else -> null
    }
}

/** What one interface's wifi state amounts to. */
internal sealed interface WifiName {
    data class Named(val ssid: String) : WifiName
    /** Joined to a network the system declines to name. */
    data object Withheld : WifiName
    /** Really on nothing, which is a proper answer. */
    data object NotAssociated : WifiName
}

/**
 * The wifi state in an `ipconfig getsummary` block.
 *
 * An interface that is not on a network has no `SSID` line at all; one that is has either the name
 * or `<redacted>`, which is what an unauthorised process is shown.
 */
internal fun parseMacSummary(output: String): WifiName {
    val ssid = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("SSID ") || it.startsWith("SSID:") }
        ?.substringAfter(':', "")
        ?.trim()
        ?: return WifiName.NotAssociated
    return when {
        ssid.isEmpty() -> WifiName.NotAssociated
        // The system's placeholder, not a name. Bracketed at both ends, so a network someone
        // has actually called "<3 Home" is a name like any other.
        ssid.startsWith('<') && ssid.endsWith('>') -> WifiName.Withheld
        else -> WifiName.Named(ssid)
    }
}

/** The wifi interfaces `networksetup` lists, or the usual suspects if it listed none. */
internal fun wifiDevices(listing: String?): List<String> = listing
    ?.lineSequence()
    ?.zipWithNext()
    ?.filter { (port, _) -> port.contains("Wi-Fi", ignoreCase = true) }
    ?.mapNotNull { (_, device) -> device.substringAfter("Device:", "").trim().takeIf { it.isNotEmpty() } }
    ?.toList()
    .orEmpty()
    .ifEmpty { listOf("en0", "en1") }

/** The name in one `-getairportnetwork` answer, or null where it says there is none. */
internal fun parseMacNetwork(line: String): String? {
    val marker = "Current Wi-Fi Network: "
    return if (marker in line) line.substringAfter(marker).trim() else null
}

/** `netsh` prints a block of fields; the SSID line is the one wanted, and "BSSID" is not it. */
private suspend fun windowsNetwork(): String? =
    run("netsh", "wlan", "show", "interfaces")?.let(::parseWindowsNetwork)

internal fun parseWindowsNetwork(output: String): String {
    val ssid = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("SSID", ignoreCase = true) && !it.startsWith("BSSID", ignoreCase = true) }
        ?: return ""
    return ssid.substringAfter(':', "").trim()
}

/** What a decoder leaves behind where the bytes meant nothing to it. */
internal const val UNDECODABLE: Char = '\uFFFD'

/**
 * A network name as something worth comparing, or null if it did not survive being read.
 *
 * A name that came through a decoder wrong is not a name: a network the user calls "Büro" that
 * arrives as nonsense will never match the one the rule holds, so it reads as *measured absence* —
 * and absence is the answer that fires "when I am not on the office wifi" while the user sits on
 * it. Unknown is the honest answer and fires nothing.
 *
 * Every system, not only the one where it was noticed. An SSID is an arbitrary string of bytes and
 * need not be text at all, so a mangled one can come back from any of them.
 */
internal fun decodedName(name: String?): String? = when {
    name == null -> null
    UNDECODABLE in name -> null
    else -> name
}

/** `nmcli` can be asked for exactly the field wanted, one line per connection. */
private suspend fun linuxNetwork(): String? =
    run("nmcli", "-t", "-f", "ACTIVE,SSID", "dev", "wifi")?.let(::parseLinuxNetwork)

internal fun parseLinuxNetwork(output: String): String = output.lineSequence()
    .firstOrNull { it.startsWith("yes:") }
    ?.substringAfter("yes:")
    ?.trim()
    // Terse mode escapes the separator *and* the backslash itself. A name that differs by a
    // stray backslash is a different network to every other device, so every escape is
    // undone rather than only the obvious one.
    ?.let { UNESCAPED.replace(it) { match -> match.groupValues[1] } }
    ?: ""

/**
 * The command's output, or null if it could not be run at all.
 *
 * Bounded, because a sweep must not be held up by a system tool that has decided to hang, and
 * because these are asked on every sweep for as long as a rule wants a network.
 */
private suspend fun run(vararg command: String): String? = withContext(Dispatchers.IO) {
    var process: Process? = null
    try {
        process = ProcessBuilder(commandFor(command))
            .redirectErrorStream(true)
            // Asked in a language this code can read. These tools localise their output — nmcli's
            // own manual says to do exactly this before parsing it — and a German desktop whose
            // "yes" comes back as "ja" matches nothing, which this design reads as the decisive
            // answer "on no network". That fires a rule about leaving a network the user is on.
            .apply { environment()["LC_ALL"] = "C" }
            .start()
        // Waited for *before* anything is read. Reading first would block in a system call that
        // no timeout can interrupt — `withTimeoutOrNull` can only cancel at a suspension point,
        // and there is none inside a stream read — so a tool that hangs without printing anything
        // would hold this for ever. And because a sweep is serialised behind one mutex, that is
        // not one broken feature but every reminder in the app stopping.
        if (!process.waitFor(COMMAND_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)) return@withContext null
        if (process.exitValue() != 0) return@withContext null
        // Safe now: the process has exited, so what it wrote is sitting in the pipe and complete.
        // These tools print a few lines; one that filled the buffer would have blocked and been
        // killed above, which reads as "cannot tell" and fires nothing.
        // UTF-8, which is what these tools are asked to speak — see [commandFor]. Anything that
        // still does not decode leaves a replacement mark, and [decodedName] treats that as not
        // knowing rather than as a name.
        process.inputStream.reader(Charsets.UTF_8).use { it.readText() }
    } catch (_: Throwable) {
        null
    } finally {
        // Including on cancellation, so a sweep that is called off does not leave a tool running.
        process?.destroyForcibly()
    }
}

private val COMMAND_TIMEOUT = 3.seconds

/**
 * The command as it has to be run to get UTF-8 out of it.
 *
 * macOS and Linux already speak it. Windows writes to a pipe in the console's own code page, which
 * is neither UTF-8 nor the one the JVM would guess — `native.encoding` is the *ANSI* page, and the
 * pipe carries the *OEM* one, so decoding with it turns an accented name into plausible-looking
 * punctuation that no check can spot. Asking the console for UTF-8 first is the way out that does
 * not require guessing which page a given machine uses.
 */
private fun commandFor(command: Array<out String>): List<String> = when {
    isWindows() -> listOf("cmd.exe", "/c", "chcp", "65001", ">nul", "&") + command
    else -> command.toList()
}

/** A backslash and whatever it was protecting, as nmcli terse output writes it. */
private val UNESCAPED = Regex("""\\(.)""")

/**
 * Whether this machine will actually name the network it is on.
 *
 * A Mac without location authorisation answers "cannot tell" to every reading — which is the safe
 * answer and fires nothing, but it also means a wifi rule written here can never come true, and
 * nothing else in the app would ever say so. A desktop JVM application does not appear in the
 * system's location list to be authorised, so this is not something the user can simply grant.
 */
actual suspend fun signalTrouble(): String? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if ("mac" !in os && "darwin" !in os) return null
    // Null means it would not say; "" means it really is on nothing, which is a working answer.
    if (joinedNetwork() != null) return null
    return "This Mac will not tell an app which wifi network it is on, so a rule about one " +
        "cannot fire here. It still works on your phone."
}

actual suspend fun offerableSignals(): List<NearbySignal> =
    listOfNotNull(joinedNetwork()?.takeIf { it.isNotEmpty() }?.let { NearbySignal.Wifi(it) })

@Composable
actual fun rememberSignalPermission(): LocationPermissionState =
    // Nothing to grant: reading the name of the network this machine is on is not a permission any
    // desktop asks about.
    remember { FixedLocationPermission(LocationPermissionStatus.Granted, worksWhileAway = true) }
