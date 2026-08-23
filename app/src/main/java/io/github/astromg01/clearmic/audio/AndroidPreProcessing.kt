package io.github.astromg01.clearmic.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

class AndroidPreProcessing(private val audioSessionId: Int) {
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null

    fun enable(): AudioStats {
        ns = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
        } else null

        aec = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
        } else null

        agc = if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
        } else null

        return AudioStats(
            noiseSuppressorEnabled = ns?.enabled == true,
            echoCancelerEnabled = aec?.enabled == true,
            automaticGainEnabled = agc?.enabled == true,
        )
    }

    fun release() {
        runCatching { ns?.release() }
        runCatching { aec?.release() }
        runCatching { agc?.release() }
        ns = null
        aec = null
        agc = null
    }
}
