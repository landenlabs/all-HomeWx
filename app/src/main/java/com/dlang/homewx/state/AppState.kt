package com.dlang.homewx.state

import com.dlang.homewx.model.AppErrorEntry
import com.dlang.homewx.model.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object AppState {
    val uiState = MutableStateFlow(UiState())

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
