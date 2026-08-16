package com.zhelenskiy.zheduler.zheduler.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import pro.respawn.flowmvi.api.Container
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.dsl.intent
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.plugins.reduce
import kotlin.test.Test
import kotlin.test.assertEquals

private data object CountState : MVIState

private sealed interface CountIntent : MVIIntent {
    data object Ping : CountIntent
    data object Boom : CountIntent
}

private sealed interface CountAction : MVIAction

/** A container that fails the way a repository call does: by throwing out of a reduce. */
private class ExplodingContainer :
    ScopedContainer(Dispatchers.Unconfined),
    Container<CountState, CountIntent, CountAction> {

    /** Every intent the store got as far as handling. */
    val handled = mutableListOf<String>()

    override val store = store(CountState, scope) {
        reportingFailuresAs("ExplodingStore")

        reduce { intent ->
            when (intent) {
                CountIntent.Ping -> handled += "ping"
                CountIntent.Boom -> error("the database is on fire")
            }
        }
    }
}

/**
 * Repository calls throw — a constraint violation, a full disk, a corrupt row. FlowMVI rethrows
 * out of the store's coroutine unless the store recovers, and the scope these stores run in
 * installs no exception handler, so an unrecovered throw took the process down on Android and left
 * the store dead everywhere else.
 */
class StoreFailureTest {

    @Test
    fun `a failing intent is reported rather than thrown`() = runBlocking {
        val container = ExplodingContainer()
        try {
            val reported = async { container.failures.first() }
            yield()

            container.store.intent(CountIntent.Boom)

            assertEquals("the database is on fire", withTimeout(5_000) { reported.await() }.message)
        } finally {
            container.close()
        }
    }

    @Test
    fun `the store keeps handling intents after one of them fails`() = runBlocking {
        val container = ExplodingContainer()
        try {
            container.store.intent(CountIntent.Ping)
            container.store.intent(CountIntent.Boom)
            container.store.intent(CountIntent.Ping)

            withTimeout(5_000) {
                while (container.handled.size < 2) delay(10)
            }
            assertEquals(listOf("ping", "ping"), container.handled, "the failure must not stop later intents")
        } finally {
            container.close()
        }
    }
}
