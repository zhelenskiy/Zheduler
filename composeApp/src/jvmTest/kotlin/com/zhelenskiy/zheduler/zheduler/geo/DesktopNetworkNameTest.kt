package com.zhelenskiy.zheduler.zheduler.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the joined network's name off what each system's own tool prints.
 *
 * There is no portable API for this, so the whole thing rests on parsing three formats — the part
 * most likely to be quietly wrong, and the part that cannot be checked by running the app, because
 * a given machine only ever exercises one of the three. The fixtures below are real output: the
 * macOS ones were captured from the machine this was written on.
 *
 * What matters most is the difference between "on no network" and "cannot tell". The first fires a
 * rule about leaving; the second must fire nothing.
 */
class DesktopNetworkNameTest {

    @Test
    fun `the wifi interface is picked out of the hardware port listing`() {
        // Real `networksetup -listallhardwareports` output, abridged only in the number of ports.
        val listing = """
            Hardware Port: Thunderbolt Bridge
            Device: bridge0
            Ethernet Address: 82:22:a0:c4:e4:01

            Hardware Port: Wi-Fi
            Device: en0
            Ethernet Address: 88:66:5a:36:48:71

            Hardware Port: Thunderbolt 1
            Device: en1
            Ethernet Address: 82:22:a0:c4:e4:01
        """.trimIndent()

        assertEquals(listOf("en0"), wifiDevices(listing))
    }

    @Test
    fun `a machine whose ports cannot be listed still has the usual names tried`() {
        assertEquals(listOf("en0", "en1"), wifiDevices(null))
        assertEquals(listOf("en0", "en1"), wifiDevices("nothing that parses"))
    }

    @Test
    fun `a joined network is read off the mac answer`() {
        assertEquals(
            "Office 5G",
            parseMacNetwork("Current Wi-Fi Network: Office 5G"),
        )
    }

    @Test
    fun `the sentence networksetup prints when it will not say is not a network name`() {
        // Worth knowing what this string does *not* mean. A modern Mac prints exactly this while
        // joined to a network, if the process is not authorised for location — so on its own it
        // cannot be read as "on nothing", and `macNetwork` asks `ipconfig` first for that reason.
        assertNull(parseMacNetwork("You are not associated with an AirPort network."))
    }

    @Test
    fun `a mac that is joined but will not say so is not a mac on no network`() {
        // Verbatim from `ipconfig getsummary en0` on the machine this was written on, which was
        // associated, addressed and online at the time. Read as "no network", a rule about leaving
        // the office wifi fires while the user sits in the office.
        val summary = """
            <dictionary> {
              BSSID : <redacted>
              ConnectionID : 16
              LinkStatusActive : TRUE
              NetworkID : <redacted>
              SSID : <redacted>
            }
        """.trimIndent()

        assertEquals(WifiName.Withheld, parseMacSummary(summary))
    }

    @Test
    fun `a mac that will say names the network`() {
        val summary = """
            <dictionary> {
              BSSID : 00:11:22:33:44:55
              LinkStatusActive : TRUE
              SSID : Office 5G
            }
        """.trimIndent()

        assertEquals(WifiName.Named("Office 5G"), parseMacSummary(summary))
    }

    @Test
    fun `an interface on no network has nothing to say about one`() {
        // A wired interface, or a radio that is on and joined to nothing: no SSID line at all.
        val summary = """
            <dictionary> {
              LinkStatusActive : FALSE
              InterfaceType : Ethernet
            }
        """.trimIndent()

        assertEquals(WifiName.NotAssociated, parseMacSummary(summary))
    }

    @Test
    fun `windows names the network without confusing it for the access point`() {
        // `netsh wlan show interfaces` lists BSSID directly under SSID, and a prefix match takes
        // the wrong one — which would make every rule watch a MAC address that changes as the user
        // walks between access points in the same building.
        val output = """
            There is 1 interface on the system:

                Name                   : Wi-Fi
                Description            : Intel(R) Wireless-AC 9560
                GUID                   : 707d1b41-a92e-4ad5-8e2b-5d9a1f6a0f9b
                Physical address       : 88:66:5a:36:48:71
                State                  : connected
                SSID                   : Office 5G
                BSSID                  : 00:11:22:33:44:55
                Network type           : Infrastructure
        """.trimIndent()

        assertEquals("Office 5G", parseWindowsNetwork(output))
    }

    @Test
    fun `windows with the radio off is on no network`() {
        val output = """
            There is 1 interface on the system:

                Name                   : Wi-Fi
                State                  : disconnected
        """.trimIndent()

        assertEquals("", parseWindowsNetwork(output))
    }

    @Test
    fun `linux names the active connection and ignores the rest`() {
        val output = "no:Neighbour\nyes:Office 5G\nno:Cafe"
        assertEquals("Office 5G", parseLinuxNetwork(output))
    }

    @Test
    fun `linux gives back the colon in a network's name`() {
        val BACKSLASH = "\\"
        // Terse mode escapes the separator; left in, the name would differ from the same network
        // as Android reports it or as the user typed it, and the rule would never match.
        assertEquals("Office:5G", parseLinuxNetwork("yes:Office" + BACKSLASH + ":5G"))
    }

    @Test
    fun `linux with nothing active is on no network`() {
        assertEquals("", parseLinuxNetwork("no:Neighbour\nno:Cafe"))
        assertEquals("", parseLinuxNetwork(""))
    }

    @Test
    fun `a name that did not survive being read is not a name`() {
        // An SSID is an arbitrary string of bytes and need not be text at all; a console that
        // hands its output over in one encoding while the reader assumes another turns "Buro"
        // with an umlaut into nonsense. Nonsense never matches the name the rule holds, so it
        // reads as the network being *absent* — and absence is what fires "when I am not on the
        // office wifi", while the user sits on it. Not knowing fires nothing, which is the point.
        assertNull(decodedName("Caf" + UNDECODABLE))
        assertNull(decodedName("" + UNDECODABLE))
        assertNull(decodedName(null))
    }

    @Test
    fun `a name that read cleanly is left exactly as it is`() {
        // Including the ones that look odd. An SSID may be any text at all, and second-guessing it
        // is how a rule stops matching the network it was written for.
        listOf("Office 5G", "Büro", "café-guest", "<3 Home", "  spaces  ").forEach { name ->
            assertEquals(name, decodedName(name))
        }
    }

    @Test
    fun `asking this machine what it is on answers without hanging`() {
        // Not an assertion about the answer — this machine may be on a network or not, and on
        // another system the tool may be missing entirely. What is pinned is that the call returns
        // at all, which is the failure that would take every reminder in the app down with it.
        val completed = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(30_000) {
                joinedNetwork()
                true
            }
        } ?: false
        assertTrue(completed, "asking this machine what network it is on never came back")
    }
}
