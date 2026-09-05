package com.dlang.homewx.state

import com.dlang.homewx.model.AppErrorEntry
import com.dlang.homewx.model.UiState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.update

object AppState {
    val uiState = MutableStateFlow(UiState())

    private val _networkRecovered = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Fires once whenever the network transitions from unreachable to reachable. Pollers that
     *  are currently sitting out a failure-backoff delay race this against their delay so they
     *  retry the moment the network is actually back, instead of waiting out the full backoff -
     *  see [com.dlang.homewx.service.HomeWxMonitorService]. */
    val networkRecovered: SharedFlow<Unit> get() = _networkRecovered

    fun notifyNetworkRecovered() {
        _networkRecovered.tryEmit(Unit)
    }

    private const val MAX_ERROR_LOG_ENTRIES = 5

    /** Rolling log of the most recent failures across every poller (newest first), capped at
     *  [MAX_ERROR_LOG_ENTRIES] - backs the Settings > Errors panel so a problem can be diagnosed
     *  and shared after the fact instead of only ever seeing the single latest error. */
    fun recordError(source: String, message: String) {
        uiState.update {
            it.copy(
                errorLog = (listOf(AppErrorEntry(System.currentTimeMillis(), source, message)) + it.errorLog)
                    .take(MAX_ERROR_LOG_ENTRIES)
            )
        }
    }

    /** Same as [recordError], but captures the exception's message and full stack trace - a
     *  bare "Time cannot be null" (or similar) is useless for after-the-fact diagnosis without
     *  knowing which line/library call actually threw it. */
    fun recordError(source: String, throwable: Throwable) {
        val summary = throwable.message ?: throwable.javaClass.simpleName
        recordError(source, "$summary\n\n${throwable.stackTraceToString()}")
    }

    fun clearErrors() {
        uiState.update { it.copy(errorLog = emptyList()) }
    }
}
