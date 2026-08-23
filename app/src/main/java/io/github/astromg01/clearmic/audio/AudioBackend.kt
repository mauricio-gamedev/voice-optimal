package io.github.astromg01.clearmic.audio

internal data class BackendSnapshot(
    val rmsDb: Float = -120f,
    val peak: Float = 0f,
    val voiceProbability: Float = 0f,
    val noiseFloorDb: Float = -120f,
    val xrunCount: Int = 0,
    val capturedFrames: Long = 0L,
)

internal interface AudioBackend {
    val engineName: String
    val dspName: String

    /** Opens the capture device and returns an Android audio session id when available. */
    fun open(): Int

    /** Allows the DSP backend to avoid fighting device/vendor preprocessing. */
    fun configurePlatformEffects(stats: AudioStats) = Unit

    fun start()
    fun snapshot(): BackendSnapshot
    fun stop()
}
