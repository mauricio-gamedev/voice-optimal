package io.github.astromg01.clearmic.audio

import android.content.Context

internal class NativeAudioBackend(
    context: Context,
) : AudioBackend {
    private val appContext = context.applicationContext
    private val bridge = NativeAudioBridge()
    private val statsBuffer = FloatArray(9)
    private var opened = false
    private var selectedProfile = AiDspSettings.read(appContext)
    private var lastAiActive = false

    override val engineName: String = "AAudio C++"
    override val dspName: String
        get() = if (lastAiActive) {
            "ClearMic AI V1 • ${selectedProfile.label}"
        } else if (selectedProfile == AiDspProfile.OFF) {
            "Native Adaptive V3"
        } else {
            "Native Adaptive V3 • AI standby/fallback"
        }
    override val allowPlatformPreprocessing: Boolean = false

    override fun open(): Int {
        check(NativeAudioBridge.isLoaded) { "Native audio library failed to load" }
        selectedProfile = AiDspSettings.read(appContext)
        bridge.nativeSetAiProfile(selectedProfile.code)
        val result = bridge.nativeOpen()
        check(result == 0) { "AAudio open failed: $result" }
        opened = true
        return bridge.nativeGetSessionId()
    }

    override fun configurePlatformEffects(stats: AudioStats) {
        if (!opened) return
        bridge.nativeConfigurePlatformEffects(false, false)
    }

    override fun start() {
        check(opened) { "Native backend is not open" }
        val result = bridge.nativeStart()
        check(result == 0) { "AAudio start failed: $result" }
    }

    override fun snapshot(): BackendSnapshot {
        if (!opened) return BackendSnapshot()

        val requested = AiDspSettings.read(appContext)
        if (requested != selectedProfile) {
            selectedProfile = requested
            bridge.nativeSetAiProfile(requested.code)
        }

        bridge.nativeFillStats(statsBuffer)
        lastAiActive = statsBuffer[5] >= 0.5f
        return BackendSnapshot(
            rmsDb = statsBuffer[0],
            peak = statsBuffer[1].coerceIn(0f, 1f),
            voiceProbability = statsBuffer[2].coerceIn(0f, 1f),
            noiseFloorDb = statsBuffer[3],
            xrunCount = statsBuffer[4].toInt().coerceAtLeast(0),
            capturedFrames = bridge.nativeGetFramesProcessed().coerceAtLeast(0L),
            aiActive = lastAiActive,
            aiVad = statsBuffer[6].coerceIn(0f, 1f),
            aiProcessingMs = statsBuffer[7].coerceAtLeast(0f),
            aiEffectiveProfile = AiDspProfile.fromCode(statsBuffer[8].toInt()),
        )
    }

    override fun stop() {
        if (!opened) return
        bridge.nativeStop()
        opened = false
        lastAiActive = false
    }
}
