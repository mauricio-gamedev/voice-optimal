package io.github.astromg01.clearmic.audio

internal class NativeAudioBackend : AudioBackend {
    private val bridge = NativeAudioBridge()
    private val statsBuffer = FloatArray(5)
    private var opened = false

    override val engineName: String = "AAudio C++"
    override val dspName: String = "Native Adaptive V1"
    override val allowPlatformPreprocessing: Boolean = false

    override fun open(): Int {
        check(NativeAudioBridge.isLoaded) { "Native audio library failed to load" }
        val result = bridge.nativeOpen()
        check(result == 0) { "AAudio open failed: $result" }
        opened = true
        return bridge.nativeGetSessionId()
    }

    override fun configurePlatformEffects(stats: AudioStats) {
        if (!opened) return
        // Native capture intentionally runs software-only in alpha04. This avoids
        // vendor AEC/NS implementations that can mute AAudio sessions on some devices.
        bridge.nativeConfigurePlatformEffects(false, false)
    }

    override fun start() {
        check(opened) { "Native backend is not open" }
        val result = bridge.nativeStart()
        check(result == 0) { "AAudio start failed: $result" }
    }

    override fun snapshot(): BackendSnapshot {
        if (!opened) return BackendSnapshot()
        bridge.nativeFillStats(statsBuffer)
        return BackendSnapshot(
            rmsDb = statsBuffer[0],
            peak = statsBuffer[1].coerceIn(0f, 1f),
            voiceProbability = statsBuffer[2].coerceIn(0f, 1f),
            noiseFloorDb = statsBuffer[3],
            xrunCount = statsBuffer[4].toInt().coerceAtLeast(0),
            capturedFrames = bridge.nativeGetFramesProcessed().coerceAtLeast(0L),
        )
    }

    override fun stop() {
        if (!opened) return
        bridge.nativeStop()
        opened = false
    }
}
