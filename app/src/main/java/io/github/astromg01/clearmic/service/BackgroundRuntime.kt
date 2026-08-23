package io.github.astromg01.clearmic.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BackgroundStats(
    val desiredRunning: Boolean = false,
    val restartCount: Int = 0,
    val sessionStartedAtMs: Long = 0L,
    val serviceUptimeMs: Long = 0L,
    val memoryPssMb: Float = 0f,
    val cpuPercent: Float = 0f,
    val batteryOptimizationActive: Boolean = true,
    val lastEvent: String = "Aguardando ativação",
)

object BackgroundRuntime {
    private val _stats = MutableStateFlow(BackgroundStats())
    val stats: StateFlow<BackgroundStats> = _stats.asStateFlow()

    internal fun update(value: BackgroundStats) {
        _stats.value = value
    }
}
