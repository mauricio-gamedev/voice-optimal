package io.github.astromg01.clearmic.audio

data class AudioStats(
    val rmsDb: Float = -120f,
    val peak: Float = 0f,
    val noiseSuppressorEnabled: Boolean = false,
    val echoCancelerEnabled: Boolean = false,
    val automaticGainEnabled: Boolean = false,
    val engineBackend: String = "Idle",
    val dspBackend: String = "None",
    val voiceProbability: Float = 0f,
    val noiseFloorDb: Float = -120f,
    val xrunCount: Int = 0,
    val capturedFrames: Long = 0L,
    val fallbackReason: String? = null,
    val captureHealth: String = "IDLE",
    val captureRecoveryCount: Int = 0,
    val aiActive: Boolean = false,
    val aiVad: Float = 0f,
    val aiProcessingMs: Float = 0f,
    val aiEffectiveProfile: AiDspProfile = AiDspProfile.OFF,
)

enum class EngineState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}
