package com.zhelenskiy.zheduler.zheduler.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the paired bluetooth devices off what each system's own tool prints.
 *
 * The same shape of problem as the wifi name, and the same reason it is tested here rather than by
 * running the app: one machine only ever exercises one of the formats. The macOS fixture below is
 * verbatim from the machine this was written on, hardware and all.
 *
 * The distinction that matters most is again between "nothing is connected" and "I could not tell":
 * the first fires a rule about a device disconnecting, and the second must fire nothing.
 */
class DesktopBluetoothTest {

    /** Real `system_profiler SPBluetoothDataType` output, trimmed only in the number of devices. */
    private val macOutput = """
        Bluetooth:

              Bluetooth Controller:
                  Address: 88:66:5A:2B:06:94
                  State: On
                  Chipset: BCM_4364B3
                  Discoverable: Off
                  Vendor ID: 0x004C (Apple)
              Connected:
                  Jabra Evolve2 65 Flex:
                      Address: 50:C2:75:CF:E0:B2
                      Vendor ID: 0x0067
                      Minor Type: Headset
                      Services: 0x800019 < HFP AVRCP A2DP ACL >
              Not Connected:
                  HD 4.50BTNC:
                      Address: 00:16:94:43:5F:FF
                      Vendor ID: 0x0A12
                      Minor Type: Headset
                  M720 Triathlon:
                      Address: F8:F0:41:20:BA:B5
                      Minor Type: Mouse
                  WH-1000XM4:
                      Address: AC:80:0A:36:30:08
                      Minor Type: Headset
    """.trimIndent()

    @Test
    fun `paired devices are read with the connected ones marked`() {
        val devices = parseMacBluetooth(macOutput)

        assertEquals(
            listOf("Jabra Evolve2 65 Flex", "HD 4.50BTNC", "M720 Triathlon", "WH-1000XM4"),
            devices?.map { (it.signal as NearbySignal.Bluetooth).name },
        )
        assertEquals(
            listOf(true, false, false, false),
            devices?.map { it.present },
            "only the one under Connected is connected",
        )
    }

    @Test
    fun `the controller's own address is not a paired device`() {
        // It sits in a section of its own above the two groups, and it is the machine itself —
        // offered as something to watch, it would be a rule that is always true.
        val devices = parseMacBluetooth(macOutput)

        assertTrue(devices?.none { (it.signal as NearbySignal.Bluetooth).address == "88:66:5A:2B:06:94" } == true)
    }

    @Test
    fun `a device is known by its address whatever the tool calls it`() {
        val jabra = parseMacBluetooth(macOutput)?.first()?.signal as NearbySignal.Bluetooth
        assertEquals("50:C2:75:CF:E0:B2", jabra.address)
        assertEquals(NearbySignal.Bluetooth("50:c2:75:cf:e0:b2").key, jabra.key)
    }

    @Test
    fun `output that is not what this expects is not an empty list of devices`() {
        // A machine with no bluetooth hardware, or a format that has moved on. Read as "nothing is
        // paired", every rule about a device disconnecting would fire.
        assertNull(parseMacBluetooth(""))
        assertNull(parseMacBluetooth("Bluetooth:\n\n      Bluetooth Power: Off"))
    }

    @Test
    fun `a mac with the groups present but empty really has nothing paired`() {
        val output = """
            Bluetooth:

                  Bluetooth Controller:
                      Address: 88:66:5A:2B:06:94
                  Not Connected:
        """.trimIndent()

        assertEquals(emptyList(), parseMacBluetooth(output), "the tool answered; there is nothing")
    }

    @Test
    fun `bluetoothctl lines are read as devices`() {
        val output = """
            Device AA:BB:CC:DD:EE:FF Car audio
            Device 11:22:33:44:55:66 WH-1000XM4
        """.trimIndent()

        assertEquals(
            listOf(
                BluetoothctlDevice("AA:BB:CC:DD:EE:FF", "Car audio"),
                BluetoothctlDevice("11:22:33:44:55:66", "WH-1000XM4"),
            ),
            parseBluetoothctl(output),
        )
    }

    @Test
    fun `bluetoothctl chatter that is not a device is ignored`() {
        // The tool prints notices and prompts on the same stream, and a line that is not an
        // address must not become a device nobody can ever be near.
        val output = """
            Agent registered
            Device NOT-AN-ADDRESS Something
            Device AA:BB:CC:DD:EE:FF Car audio
            [bluetooth]# quit
        """.trimIndent()

        assertEquals(
            listOf(BluetoothctlDevice("AA:BB:CC:DD:EE:FF", "Car audio")),
            parseBluetoothctl(output),
        )
    }

    @Test
    fun `a device with no name is still a device`() {
        assertEquals(
            listOf(BluetoothctlDevice("AA:BB:CC:DD:EE:FF", "")),
            parseBluetoothctl("Device AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `a device named after a group heading is still a device`() {
        // People name things anything. Read as the start of a group, the rest of that section
        // lands under the wrong one — and the "Not Connected" devices after it are reported as
        // connected, which is the direction that fires nothing but keeps a rule alive wrongly.
        val output = """
            Bluetooth:

                  Connected:
                      Connected:
                          Address: 11:22:33:44:55:66
                  Not Connected:
                      WH-1000XM4:
                          Address: AC:80:0A:36:30:08
        """.trimIndent()

        val devices = parseMacBluetooth(output)

        assertEquals(
            listOf("Connected" to true, "WH-1000XM4" to false),
            devices?.map { (it.signal as NearbySignal.Bluetooth).name to it.present },
        )
    }

    @Test
    fun `asking this machine what it is paired with answers without hanging`() {
        // Not an assertion about the answer — this machine may be paired with anything or nothing,
        // and another may have no such tool. What is pinned is that the call comes back, which is
        // the failure that would take every reminder in the app down with it.
        val completed = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(30_000) {
                pairedDevices()
                true
            }
        } ?: false
        assertTrue(completed, "asking this machine what it is paired with never came back")
    }
}
