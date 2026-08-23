package io.github.astromg01.clearmic.audio

internal class NativeAudioBridge {
    companion object {
        val isLoaded: Boolean by lazy {
            runCatching { System.loadLibrary("clearmic_audio") }.isSuccess
        }
    }

    external fun nativeOpen(): Int
    external fun nativeGetSessionId(): Int
    external fun nativeConfigurePlatformEffects(noiseSuppressorEnabled: Boolean, agcEnabled: Boolean)
    external fun nativeStart(): Int
    external fun nativeGetStats(): FloatArray
    external fun nativeGetFramesProcessed(): Long
    external fun nativeStop()
}
