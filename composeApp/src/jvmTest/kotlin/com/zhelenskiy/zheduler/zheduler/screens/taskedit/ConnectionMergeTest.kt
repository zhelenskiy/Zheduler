package com.zhelenskiy.zheduler.zheduler.screens.taskedit

import com.zhelenskiy.zheduler.zheduler.ConnectionType
import com.zhelenskiy.zheduler.zheduler.TaskConnection
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Leaving the edit screen to create a connected task writes the other half of that link into the
 * task being edited, while the form the user left is restored from what they had typed. Both have
 * to survive: the link the database gained, and the edits the form was holding.
 */
class ConnectionMergeTest {

    private val existing = TaskConnection("TEST-1", ConnectionType.DependsOn)
    private val addedInDialog = TaskConnection("TEST-2", ConnectionType.SubtaskOf)
    private val createdWhileAway = TaskConnection("TEST-3", ConnectionType.ParentOf)

    @Test
    fun `a connection created while away is added to the restored form`() {
        val merged = mergedConnections(
            // What the user had in the form when they left.
            current = persistentSetOf(existing, addedInDialog),
            // What the task had when the form was built.
            base = persistentSetOf(existing),
            // What the task has now: the other half of the link just created.
            fresh = persistentSetOf(existing, createdWhileAway),
        )

        assertEquals(persistentSetOf(existing, addedInDialog, createdWhileAway), merged)
    }

    @Test
    fun `a connection the user removed stays removed`() {
        val merged = mergedConnections(
            current = persistentSetOf(),
            base = persistentSetOf(existing),
            fresh = persistentSetOf(existing, createdWhileAway),
        )

        assertEquals(persistentSetOf(createdWhileAway), merged)
    }

    @Test
    fun `a connection deleted elsewhere goes from the form too`() {
        val merged = mergedConnections(
            current = persistentSetOf(existing, addedInDialog),
            base = persistentSetOf(existing),
            fresh = persistentSetOf(),
        )

        assertEquals(persistentSetOf(addedInDialog), merged)
    }

    @Test
    fun `measuring from the fresh set loses the new connection`() {
        // The bug this guards against: with the mark taken from the freshly loaded task rather
        // than from what the form was built with, the new link reads as no change at all — and
        // saving the form then deleted it.
        val fresh = persistentSetOf(existing, createdWhileAway)
        val merged = mergedConnections(
            current = persistentSetOf(existing, addedInDialog),
            base = fresh,
            fresh = fresh,
        )

        assertEquals(persistentSetOf(existing, addedInDialog), merged, "no merge happens at all")
    }
}
