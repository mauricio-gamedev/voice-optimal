package io.github.astromg01.clearmic.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class AudioEngine(
    private val context: Context,
    private val processor: AudioProcessor = LightweightVoiceProcessor(),
) {
    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 480 // 10 ms @ 48 kHz
    }

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var effects: AndroidPreProcessing? = null

    fun start() {
        if (running) return
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "RECORD_AUDIO permission is required" }

        AudioRuntime.updateState(EngineState.STARTING)

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "AudioRecord min buffer is invalid: $minBuffer" }

        val recorderBufferBytes = max(minBuffer * 2, FRAME_SAMPLES * 2 * 8)
        val localRecorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(recorderBufferBytes)
            .build()

        check(localRecorder.state == AudioRecord.STATE_INITIALIZED) {
            localRecorder.release()
            "AudioRecord failed to initialize"
        }

        recorder = localRecorder
        effects = AndroidPreProcessing(localRecorder.audioSessionId)
        val effectStats = effects!!.enable()

        running = true
        localRecorder.startRecording()
        AudioRuntime.updateStats(effectStats)
        AudioRuntime.updateState(EngineState.RUNNING)

        worker = Thread({ captureLoop(localRecorder, effectStats) }, "ClearMic-Audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun captureLoop(localRecorder: AudioRecord, effectStats: AudioStats) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ShortArray(FRAME_SAMPLES)
        var updateDivider = 0

        try {
            while (running) {
                val read = localRecorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                processor.process(frame, read)

                if (++updateDivider >= 8) {
                    updateDivider = 0
                    var sum = 0.0
                    var peak = 0
                    for (i in 0 until read) {
                        val value = kotlin.math.abs(frame[i].toInt())
                        peak = max(peak, value)
                        sum += value.toDouble() * value.toDouble()
                    }
                    val rms = sqrt(sum / read.coerceAtLeast(1))
                    val rmsDb = if (rms <= 0.0) -120f else (20.0 * log10(rms / 32768.0)).toFloat()

                    AudioRuntime.updateStats(
                        effectStats.copy(
                            rmsDb = rmsDb.coerceAtLeast(-120f),
                            peak = (peak / 32768f).coerceIn(0f, 1f),
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            AudioRuntime.updateState(EngineState.ERROR)
        }
    }

    fun stop() {
        if (!running && recorder == null) return
        AudioRuntime.updateState(EngineState.STOPPING)
        running = false

        runCatching { recorder?.stop() }
        worker?.join(600)
        worker = null

        effects?.release()
        effects = null
        recorder?.release()
        recorder = null

        AudioRuntime.updateStats(AudioStats())
        AudioRuntime.updateState(EngineState.IDLE)
    }
}
