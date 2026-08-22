package com.zhelenskiy.zheduler.zheduler.geo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * What a desktop can say about what is around it: which wifi network it is on, and which bluetooth
 * devices it is paired and connected with.
 *
 * There is no portable API for either, so each system is asked in its own words. That sounds worse
 * than the alternative and is not: the Kotlin bluetooth libraries that publish a JVM target —
 * Kable, Blue Falcon — are *low-energy* libraries, and a car stereo or a pair of headphones speaks
 * classic bluetooth, which they do not see at all. What they offer is scanning for nearby
 * peripherals, which is a different question from "is my car connected". The systems' own tools
 * answer the question actually being asked, and answer it for classic devices too.
 *
 * Any system not answered for here leaves that kind unmeasured, which fires nothing.
 */
actual fun createSignalSource(): SignalSource = DesktopSignalSource

actual val supportedSignalKinds: Set<SignalKind> = buildSet {
    add(SignalKind.Wifi)
    // Only where there is a tool that answers. Claiming it on Windows would put the picker's
    // "nothing is paired yet" in front of a user who can pair all day and never be offered
    // anything, instead of the plain "a rule about one cannot fire here".
    if (isMac() || isLinux()) add(SignalKind.Bluetooth)
}

private object DesktopSignalSource : SignalSource {
    override suspend fun nearby(): NearbySignals {
        val kinds = mutableSetOf<SignalKind>()
        val present = mutableSetOf<String>()

        joinedNetwork()?.let { ssid ->
            kinds += SignalKind.Wifi
            if (ssid.isNotEmpty()) present += NearbySignal.Wifi(ssid).key
        }
        pairedDevices()?.let { devices ->
            kinds += SignalKind.Bluetooth
            devices.filter { it.present }.forEach { present += it.signal.key }
        }
        return NearbySignals(kinds = kinds, present = present)
    }
}

/**
 * The bluetooth devices this machine is paired with, and which of them are connected — or null
 * where it cannot be told.
 *
 * Paired rather than in range, which is the question a rule asks: "the car" means the one this
 * machine knows, and whether it is connected right now is the thing that changes.
 */
internal suspend fun pairedDevices(): List<OfferedSignal>? = withContext(Dispatchers.IO) {
    when {
        isMac() -> run("system_profiler", "SPBluetoothDataType")?.let(::parseMacBluetooth)
        isLinux() -> linuxBluetooth()
        // Nothing tried on Windows. Its own tool reports bluetooth through the device manager,
        // where a paired-but-idle device and a device that is not there look much alike, and this
        // was written on a machine where that could not be checked. Guessing wrong here does not
        // give a wrong list, it gives a rule that fires while the user is in the car.
        else -> null
    }
}

/**
 * `system_profiler` groups paired devices under "Connected" and "Not Connected", each with its
 * name and address.
 *
 * Parsed by indentation rather than by matching names: the two group headings sit at one depth,
 * each device's name one deeper, and its fields deeper still. That is what tells a device called
 * "Address" — people name things anything — from the field of the same name.
 */
internal fun parseMacBluetooth(output: String): List<OfferedSignal>? {
    var connectedGroup: Boolean? = null
    var groupIndent = -1
    var deviceName: String? = null
    var deviceIndent = -1
    val found = mutableListOf<OfferedSignal>()
    var sawAGroup = false

    output.lineSequence().forEach { line ->
        if (line.isBlank()) return@forEach
        val indent = line.indexOfFirst { !it.isWhitespace() }
        val text = line.trim()
        when {
            // A heading only at the depth headings sit at, never deeper. Otherwise a pair of
            // headphones somebody has named "Connected" starts a group of its own and every device
            // after it is reported under the wrong heading.
            (text == "Connected:" || text == "Not Connected:") &&
                (groupIndent < 0 || indent <= groupIndent) -> {
                connectedGroup = text == "Connected:"
                groupIndent = indent
                sawAGroup = true
                deviceName = null
            }
            // Back out to a shallower heading — "Bluetooth Controller:" and the like.
            connectedGroup != null && indent <= groupIndent -> {
                connectedGroup = null
                deviceName = null
            }
            connectedGroup != null && deviceName == null && text.endsWith(":") -> {
                deviceName = text.dropLast(1)
                deviceIndent = indent
            }
            connectedGroup != null && deviceName != null && indent <= deviceIndent -> {
                // A sibling of the device: the next device in the same group.
                deviceName = if (text.endsWith(":")) text.dropLast(1) else null
            }
            connectedGroup != null && deviceName != null && text.startsWith("Address:") -> {
                val address = text.substringAfter("Address:").trim()
                if (address.isNotEmpty()) {
                    found += OfferedSignal(
                        signal = NearbySignal.Bluetooth(address = address, name = deviceName.orEmpty()),
                        present = connectedGroup == true,
                    )
                }
            }
        }
    }
    // No headings at all means the output was not what this expects — a machine with no bluetooth
    // hardware, or a format that has moved on. Either way it is not "nothing is connected".
    return if (sawAGroup) found else null
}

/**
 * `bluetoothctl` lists what is paired and what is connected, each as "Device <address> <name>".
 *
 * Both are asked for, because a device can be either, and the pairing list is the one a rule is
 * written from.
 */
private suspend fun linuxBluetooth(): List<OfferedSignal>? {
    val paired = run("bluetoothctl", "devices", "Paired")
        ?: run("bluetoothctl", "paired-devices")
        ?: return null
    // No `orEmpty()` here, deliberately. `devices Connected` only arrived in BlueZ 5.65, and on
    // the older releases where the paired fallback above is what worked, this is not a command at
    // all. Read as "nothing is connected", every paired device is reported gone and a rule about
    // the car disconnecting fires while the user is driving it. Not knowing is the honest answer.
    val connected = run("bluetoothctl", "devices", "Connected") ?: return null
    val connectedAddresses = parseBluetoothctl(connected).mapTo(mutableSetOf()) { it.address.uppercase() }
    return parseBluetoothctl(paired).map { device ->
        OfferedSignal(
            signal = NearbySignal.Bluetooth(address = device.address, name = device.name),
            present = device.address.uppercase() in connectedAddresses,
        )
    }
}

/** One line of `bluetoothctl devices`, which is "Device AA:BB:CC:DD:EE:FF Some name". */
internal data class BluetoothctlDevice(val address: String, val name: String)

internal fun parseBluetoothctl(output: String): List<BluetoothctlDevice> = output.lineSequence()
    .map { it.trim() }
    .filter { it.startsWith("Device ") }
    .mapNotNull { line ->
        val rest = line.removePrefix("Device ").trim()
        val address = rest.substringBefore(' ').trim()
        if (!address.looksLikeAnAddress()) return@mapNotNull null
        BluetoothctlDevice(address = address, name = rest.substringAfter(' ', "").trim())
    }
    .toList()

/** Six pairs of hex, colon-separated — what every one of these tools calls an address. */
private fun String.looksLikeAnAddress(): Boolean =
    length == 17 && split(':').let { it.size == 6 && it.all { part -> part.length == 2 } }

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
        isLinux() -> linuxNetwork()
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
private fun isLinux(): Boolean = "nux" in osName() || "nix" in osName()

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
 * Asked of every desktop, not only the Mac that prompted it. A machine answering "cannot tell" is
 * giving the safe answer and fires nothing — but it also means a wifi rule written here can never
 * come true, and without this nothing in the app would ever say so. Windows with no wireless
 * hardware and a Linux box with no NetworkManager are as silent as an unauthorised Mac.
 */
actual suspend fun signalTrouble(kind: SignalKind): String? = when (kind) {
    SignalKind.Wifi -> wifiTrouble(joined = joinedNetwork(), isMac = isMac())

    // Asked rather than assumed: the tool can be missing, refused, or simply slower than the few
    // seconds a sweep will wait, and each of those is a rule that would never fire with nothing on
    // the screen to say why.
    SignalKind.Bluetooth -> {
        if (SignalKind.Bluetooth in supportedSignalKinds && pairedDevices() == null) {
            "This computer would not say which bluetooth devices it is paired with, so a rule " +
                "about one cannot fire here. It still works on your phone."
        } else {
            null
        }
    }
}

/**
 * What to say about a desktop that would not name the network it is on.
 *
 * [joined] is null for "cannot tell" and "" for "really on nothing", which is a working answer and
 * no trouble at all — the difference the whole feature turns on.
 */
internal fun wifiTrouble(joined: String?, isMac: Boolean): String? = when {
    joined != null -> null
    // Named, because it looks like something the user could put right and is not: a desktop
    // application does not appear in the system's location list to be authorised.
    isMac -> "This Mac will not tell an app which wifi network it is on, so a rule about one " +
        "cannot fire here. It still works on your phone."

    else -> "This computer would not say which wifi network it is on, so a rule about one cannot " +
        "fire here. It still works on your phone."
}

actual suspend fun offerableSignals(kind: SignalKind): List<OfferedSignal> = when (kind) {
    SignalKind.Wifi -> listOfNotNull(
        joinedNetwork()?.takeIf { it.isNotEmpty() }
            ?.let { OfferedSignal(NearbySignal.Wifi(it), present = true) }
    )

    SignalKind.Bluetooth -> pairedDevices().orEmpty()
}

@Composable
actual fun rememberSignalPermission(): LocationPermissionState =
    // Nothing to grant: reading the name of the network this machine is on is not a permission any
    // desktop asks about.
    remember { FixedLocationPermission(LocationPermissionStatus.Granted, worksWhileAway = true) }
