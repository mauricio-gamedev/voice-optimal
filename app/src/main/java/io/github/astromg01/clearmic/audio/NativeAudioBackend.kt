package io.github.astromg01.clearmic.audio

internal class NativeAudioBackend : AudioBackend {
    private val bridge = NativeAudioBridge()
    private var opened = false

    override val engineName: String = "AAudio C++"
    override val dspName: String = "Native Adaptive V1"

    override fun open(): Int {
        check(NativeAudioBridge.isLoaded) { "Native audio library failed to load" }
        val result = bridge.nativeOpen()
        check(result == 0) { "AAudio open failed: $result" }
        opened = true
        return bridge.nativeGetSessionId()
    }

    override fun configurePlatformEffects(stats: AudioStats) {
        if (!opened) return
        bridge.nativeConfigurePlatformEffects(
            stats.noiseSuppressorEnabled,
            stats.automaticGainEnabled,
        )
    }

    override fun start() {
        check(opened) { "Native backend is not open" }
        val result = bridge.nativeStart()
        check(result == 0) { "AAudio start failed: $result" }
    }

    override fun snapshot(): BackendSnapshot {
        if (!opened) return BackendSnapshot()
        val values = bridge.nativeGetStats()
        return BackendSnapshot(
            rmsDb = values.getOrElse(0) { -120f },
            peak = values.getOrElse(1) { 0f }.coerceIn(0f, 1f),
            voiceProbability = values.getOrElse(2) { 0f }.coerceIn(0f, 1f),
            noiseFloorDb = values.getOrElse(3) { -120f },
            xrunCount = values.getOrElse(4) { 0f }.toInt().coerceAtLeast(0),
            capturedFrames = bridge.nativeGetFramesProcessed().coerceAtLeast(0L),
        )
    }

    override fun stop() {
        if (!opened) return
        bridge.nativeStop()
        opened = false
    }
}
