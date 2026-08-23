package io.github.astromg01.clearmic.audio

data class AudioStats(
    val rmsDb: Float = -120f,
    val peak: Float = 0f,
    val noiseSuppressorEnabled: Boolean = false,
    val echoCancelerEnabled: Boolean = false,
    val automaticGainEnabled: Boolean = false,
)

enum class EngineState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}
