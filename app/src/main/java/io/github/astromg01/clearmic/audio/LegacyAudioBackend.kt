package io.github.astromg01.clearmic.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

internal class LegacyAudioBackend(
    private val processor: AudioProcessor = LightweightVoiceProcessor(),
) : AudioBackend {
    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 480
    }

    override val engineName: String = "AudioRecord safe fallback"
    override val dspName: String = "Kotlin Lightweight"
    override val allowPlatformPreprocessing: Boolean = false

    @Volatile
    private var running = false

    @Volatile
    private var latest = BackendSnapshot()

    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var capturedFrames = 0L

    override fun open(): Int {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "AudioRecord min buffer is invalid: $minBuffer" }

        val recorderBufferBytes = max(minBuffer * 2, FRAME_SAMPLES * 2 * 8)
        val localRecorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
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
        latest = BackendSnapshot()
        capturedFrames = 0L
        return localRecorder.audioSessionId
    }

    override fun start() {
        val localRecorder = checkNotNull(recorder) { "Legacy backend is not open" }
        running = true
        localRecorder.startRecording()
        worker = Thread({ captureLoop(localRecorder) }, "ClearMic-LegacyAudio").apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    private fun captureLoop(localRecorder: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frame = ShortArray(FRAME_SAMPLES)
        var updateDivider = 0

        try {
            while (running) {
                val read = localRecorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                capturedFrames += read
                processor.process(frame, read)

                if (++updateDivider >= 10) {
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
                    latest = BackendSnapshot(
                        rmsDb = rmsDb.coerceAtLeast(-120f),
                        peak = (peak / 32768f).coerceIn(0f, 1f),
                        capturedFrames = capturedFrames,
                    )
                }
            }
        } catch (_: Throwable) {
            // AudioEngine owns the public lifecycle state and fallback behavior.
        }
    }

    override fun snapshot(): BackendSnapshot = latest.copy(capturedFrames = capturedFrames)

    override fun stop() {
        if (!running && recorder == null) return
        running = false
        runCatching { recorder?.stop() }
        worker?.join(500)
        worker = null
        recorder?.release()
        recorder = null
        latest = BackendSnapshot()
    }
}
