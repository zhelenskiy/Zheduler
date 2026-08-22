package com.zhelenskiy.zheduler.zheduler.viewmodels

import com.zhelenskiy.zheduler.zheduler.TaskRepository
import com.zhelenskiy.zheduler.zheduler.geo.SavedSignal
import com.zhelenskiy.zheduler.zheduler.screens.tasklist.viewmode.generateId as generateIdImpl
import pro.respawn.flowmvi.api.Container
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.dsl.store
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

/**
 * @param signals the book of networks and devices, as it stands. Narrowing it belongs to the
 *   dialog reading it — see [SavedSignal.matches].
 */
data class SavedSignalState(
    val signals: List<SavedSignal> = emptyList(),
) : MVIState

sealed interface SavedSignalIntent : MVIIntent {
    data object Load : SavedSignalIntent
    data class Save(val signal: SavedSignal) : SavedSignalIntent

    /** Files one only if the book has not got it already; see `TaskRepository.keepSignal`. */
    data class Keep(val signal: SavedSignal) : SavedSignalIntent
    data class Delete(val id: String) : SavedSignalIntent
}

sealed interface SavedSignalAction : MVIAction

private typealias SavedSignalPipelineContext =
    PipelineContext<SavedSignalState, SavedSignalIntent, SavedSignalAction>

/**
 * The book of networks and bluetooth devices, alongside [SavedLocationContainer].
 *
 * Not scoped to a space — the office wifi is the office wifi in every one of them — which is why
 * this takes no space id.
 */
class SavedSignalContainer(
    private val repository: TaskRepository,
) : ScopedContainer(), Container<SavedSignalState, SavedSignalIntent, SavedSignalAction> {

    override val store = store(SavedSignalState(), scope) {
        reportingFailuresAs("SavedSignalStore")

        whileSubscribed {
            load()
        }

        reduce { intent ->
            when (intent) {
                is SavedSignalIntent.Load -> load()
                is SavedSignalIntent.Save -> save(intent.signal)
                is SavedSignalIntent.Keep -> keep(intent.signal)
                is SavedSignalIntent.Delete -> delete(intent.id)
            }
        }
    }

    private suspend fun SavedSignalPipelineContext.load() {
        val signals = repository.getAllSavedSignals()
        updateState { copy(signals = signals) }
    }

    private suspend fun SavedSignalPipelineContext.save(signal: SavedSignal) {
        repository.saveSignal(signal)
        val signals = repository.getAllSavedSignals()
        updateState { copy(signals = signals) }
    }

    private suspend fun SavedSignalPipelineContext.keep(signal: SavedSignal) {
        repository.keepSignal(signal)
        val signals = repository.getAllSavedSignals()
        updateState { copy(signals = signals) }
    }

    private suspend fun SavedSignalPipelineContext.delete(id: String) {
        repository.deleteSavedSignal(id)
        val signals = repository.getAllSavedSignals()
        updateState { copy(signals = signals) }
    }

    fun generateId(): String = generateIdImpl()
}
