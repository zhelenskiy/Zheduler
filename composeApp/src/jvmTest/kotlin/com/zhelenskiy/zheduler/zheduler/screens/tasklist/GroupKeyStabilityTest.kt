package com.zhelenskiy.zheduler.zheduler.screens.tasklist

import com.zhelenskiy.zheduler.zheduler.TaskGroupInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Which groups the user has collapsed is remembered by these keys, so a key has to mean the same
 * group after the list reloads.
 *
 * Groups come and go on their own: one with no tasks is left out unless the view mode asks for
 * empty ones, and the trailing Uncategorized group exists only while something is in it. Keyed by
 * position, a single task changing status shifted every group below it onto its neighbour's key —
 * a collapsed group sprang open and the one that took its place snapped shut.
 */
class GroupKeyStabilityTest {

    private fun group(label: String, uncategorized: Boolean = false) =
        TaskGroupInfo(label = label, taskCount = 1, isUncategorized = uncategorized)

    @Test
    fun aGroupKeepsItsKeyWhenAnEarlierGroupDisappears() {
        val before = groupKeysFor("", listOf(group("Unresolved"), group("Blocked"), group("Resolved")))
        val after = groupKeysFor("", listOf(group("Unresolved"), group("Resolved")))

        assertEquals(before[0], after[0])
        assertEquals(before[2], after[1], "Resolved moved up a slot and lost what the user did to it")
    }

    @Test
    fun theUncategorizedGroupKeepsItsKeyAsSiblingsComeAndGo() {
        val withBlocked = groupKeysFor("", listOf(group("Open"), group("Blocked"), group("Other", uncategorized = true)))
        val withoutBlocked = groupKeysFor("", listOf(group("Open"), group("Other", uncategorized = true)))

        assertEquals(withBlocked[2], withoutBlocked[1])
    }

    @Test
    fun siblingsSharingALabelGetDistinctKeys() {
        val keys = groupKeysFor("", listOf(group("Later"), group("Later"), group("Later")))

        assertEquals(keys.size, keys.toSet().size, "two groups answering to one key: $keys")
    }

    @Test
    fun aDotInALabelDoesNotReadAsAChildOfAnotherGroup() {
        val nested = groupKeysFor(groupKeysFor("", listOf(group("a"))).single(), listOf(group("b")))
        val dotted = groupKeysFor("", listOf(group("a.b")))

        assertNotEquals(dotted.single(), nested.single(), "a group named \"a.b\" collided with a.b")
    }

    @Test
    fun aLabelCannotSpellTheKeyOfARepeatedSibling() {
        // "X" twice numbers the second "X#1" — which is also what a group actually labelled "X#1"
        // would be called. Two groups on one key is a crash: these are the list's item keys.
        val keys = groupKeysFor("", listOf(group("X"), group("X"), group("X#1")))

        assertEquals(keys.size, keys.toSet().size, "two groups answering to one key: $keys")
    }

    @Test
    fun childKeysHangOffTheirParent() {
        val parent = groupKeysFor("", listOf(group("Open"))).single()
        val children = groupKeysFor(parent, listOf(group("High"), group("Low")))

        assertEquals(listOf("Open.High", "Open.Low"), children)
    }
}
