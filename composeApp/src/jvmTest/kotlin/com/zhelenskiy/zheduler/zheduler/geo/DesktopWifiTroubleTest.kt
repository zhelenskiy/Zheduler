package com.zhelenskiy.zheduler.zheduler.geo

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether a desktop admits it cannot name the network it is on.
 *
 * Not a nicety: this message is the only thing standing between a user and a wifi rule that will
 * never fire, and it feeds the picker's decision about whether "not here now" is a thing it is
 * entitled to say about anything.
 */
class DesktopWifiTroubleTest {

    @Test
    fun `a machine that named its network has nothing to report`() {
        assertNull(wifiTrouble(joined = "acme-corp-5G", isMac = true))
        assertNull(wifiTrouble(joined = "acme-corp-5G", isMac = false))
    }

    @Test
    fun `a machine on no network at all has nothing to report either`() {
        // "" is a real answer and the one that fires a leaving rule. Read as trouble, a laptop
        // sitting off wifi would be told its rules cannot work, which is the opposite of the truth.
        assertNull(wifiTrouble(joined = "", isMac = true))
        assertNull(wifiTrouble(joined = "", isMac = false))
    }

    @Test
    fun `a desktop that is not a mac and would not say is reported too`() {
        // Windows with no wireless hardware and a Linux box with no NetworkManager are as silent
        // as an unauthorised Mac. Reported for the Mac alone, those users get a picker that says
        // nothing is wrong and a rule that never fires.
        val trouble = assertNotNull(wifiTrouble(joined = null, isMac = false))
        assertTrue("cannot fire here" in trouble)
        assertTrue("Mac" !in trouble, "it is not a Mac and should not be told about one")
    }

    @Test
    fun `a mac that would not say is told why`() {
        val trouble = assertNotNull(wifiTrouble(joined = null, isMac = true))
        assertTrue("Mac" in trouble)
    }
}
