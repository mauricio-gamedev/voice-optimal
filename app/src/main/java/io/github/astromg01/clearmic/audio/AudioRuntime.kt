package io.github.astromg01.clearmic.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AudioRuntime {
    private val _state = MutableStateFlow(EngineState.IDLE)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(AudioStats())
    val stats: StateFlow<AudioStats> = _stats.asStateFlow()

    internal fun updateState(value: EngineState) {
        _state.value = value
    }

    internal fun updateStats(value: AudioStats) {
        _stats.value = value
    }
}
