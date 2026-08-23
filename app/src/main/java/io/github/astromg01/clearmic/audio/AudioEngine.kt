package io.github.astromg01.clearmic.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat

class AudioEngine(
    private val context: Context,
) {
    private data class Activation(
        val backend: AudioBackend,
        val preprocessing: AndroidPreProcessing?,
        val effectStats: AudioStats,
    )

    @Volatile
    private var running = false

    private var backend: AudioBackend? = null
    private var effects: AndroidPreProcessing? = null
    private var statsWorker: Thread? = null
    private var baseEffectStats = AudioStats()

    fun start() {
        if (running) return
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "RECORD_AUDIO permission is required" }

        AudioRuntime.updateState(EngineState.STARTING)

        val activation = runCatching {
            activate(NativeAudioBackend())
        }.getOrElse {
            runCatching { activate(LegacyAudioBackend()) }
                .getOrElse { fallbackError ->
                    AudioRuntime.updateState(EngineState.ERROR)
                    throw IllegalStateException("No audio backend could start", fallbackError)
                }
        }

        backend = activation.backend
        effects = activation.preprocessing
        baseEffectStats = activation.effectStats
        running = true

        publishSnapshot(activation.backend.snapshot())
        AudioRuntime.updateState(EngineState.RUNNING)
        startStatsPolling()
    }

    private fun activate(candidate: AudioBackend): Activation {
        var preprocessing: AndroidPreProcessing? = null
        try {
            val sessionId = candidate.open()
            val effectStats = if (sessionId > 0) {
                AndroidPreProcessing(sessionId).also { preprocessing = it }.enable()
            } else {
                AudioStats()
            }

            candidate.configurePlatformEffects(effectStats)
            candidate.start()
            return Activation(candidate, preprocessing, effectStats)
        } catch (error: Throwable) {
            runCatching { preprocessing?.release() }
            runCatching { candidate.stop() }
            throw error
        }
    }

    private fun startStatsPolling() {
        statsWorker?.interrupt()
        statsWorker = Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                while (running) {
                    val activeBackend = backend ?: break
                    runCatching { activeBackend.snapshot() }
                        .onSuccess { publishSnapshot(it) }

                    try {
                        Thread.sleep(200L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            },
            "ClearMic-Stats",
        ).apply { start() }
    }

    private fun publishSnapshot(snapshot: BackendSnapshot) {
        val activeBackend = backend
        AudioRuntime.updateStats(
            baseEffectStats.copy(
                rmsDb = snapshot.rmsDb.coerceAtLeast(-120f),
                peak = snapshot.peak.coerceIn(0f, 1f),
                engineBackend = activeBackend?.engineName ?: "Starting",
                dspBackend = activeBackend?.dspName ?: "Starting",
                voiceProbability = snapshot.voiceProbability.coerceIn(0f, 1f),
                noiseFloorDb = snapshot.noiseFloorDb.coerceAtLeast(-120f),
                xrunCount = snapshot.xrunCount.coerceAtLeast(0),
                capturedFrames = snapshot.capturedFrames.coerceAtLeast(0L),
            )
        )
    }

    fun stop() {
        if (!running && backend == null) return
        AudioRuntime.updateState(EngineState.STOPPING)
        running = false

        statsWorker?.interrupt()
        statsWorker?.join(350)
        statsWorker = null

        effects?.release()
        effects = null
        runCatching { backend?.stop() }
        backend = null
        baseEffectStats = AudioStats()

        AudioRuntime.updateStats(AudioStats())
        AudioRuntime.updateState(EngineState.IDLE)
    }
}
