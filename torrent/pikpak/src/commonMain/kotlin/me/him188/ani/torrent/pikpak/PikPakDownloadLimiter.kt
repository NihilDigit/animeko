package me.him188.ani.torrent.pikpak

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Limits whole-file downloads across sessions; playback reads do not acquire a slot. */
internal class PikPakDownloadLimiter(limits: Flow<Int>, scope: CoroutineScope) {
    private data class State(val limit: Int, val active: Int = 0)

    private val state = MutableStateFlow(State(PikPakEngineConfig.DEFAULT_MAX_CONCURRENT_DOWNLOADS))

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            limits.collect { limit ->
                state.update { it.copy(limit = limit.coerceIn(1, PikPakEngineConfig.MAX_CONCURRENT_DOWNLOADS)) }
            }
        }
    }

    suspend fun <T> withSlot(block: suspend () -> T): T {
        while (true) {
            val current = state.first { it.active < it.limit }
            if (state.compareAndSet(current, current.copy(active = current.active + 1))) break
        }
        try {
            return block()
        } finally {
            state.update { it.copy(active = it.active - 1) }
        }
    }
}
