package io.github.astromg01.clearmic.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat

class AudioEngine(
    private val context: Context,
) {
    companion object {
        private const val STATS_POLL_INTERVAL_MS = 750L
        private const val NATIVE_SILENT_FRAME_THRESHOLD = 96_000L // ~2 seconds at 48 kHz
    }

    private data class Activation(
        val backend: AudioBackend,
        val preprocessing: AndroidPreProcessing?,
        val effectStats: AudioStats,
    )

    private val lifecycleLock = Any()

    @Volatile
    private var running = false

    private var backend: AudioBackend? = null
    private var effects: AndroidPreProcessing? = null
    private var statsWorker: Thread? = null
    private var baseEffectStats = AudioStats()

    @Volatile
    private var fallbackReason: String? = null

    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            check(
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            ) { "RECORD_AUDIO permission is required" }

            AudioRuntime.updateState(EngineState.STARTING)
            fallbackReason = null

            val activation = runCatching {
                activate(NativeAudioBackend())
            }.getOrElse { nativeError ->
                fallbackReason = "AAudio não abriu (${nativeError.javaClass.simpleName}); usando AudioRecord seguro."
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
    }

    private fun activate(candidate: AudioBackend): Activation {
        var preprocessing: AndroidPreProcessing? = null
        try {
            val sessionId = candidate.open()
            val effectStats = if (candidate.allowPlatformPreprocessing && sessionId > 0) {
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
                    val snapshot = runCatching { activeBackend.snapshot() }.getOrNull()

                    if (snapshot != null) {
                        val nativeSilent =
                            activeBackend is NativeAudioBackend &&
                                snapshot.capturedFrames >= NATIVE_SILENT_FRAME_THRESHOLD &&
                                snapshot.rmsDb <= -119.5f &&
                                snapshot.peak <= 0.00001f

                        if (nativeSilent) {
                            switchToLegacy(
                                "AAudio entregou frames silenciosos; fallback automático ativado."
                            )
                        } else {
                            publishSnapshot(snapshot)
                        }
                    }

                    try {
                        Thread.sleep(STATS_POLL_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            },
            "ClearMic-Stats",
        ).apply { start() }
    }

    private fun switchToLegacy(reason: String): Boolean = synchronized(lifecycleLock) {
        if (!running || backend !is NativeAudioBackend) {
            return@synchronized false
        }

        val oldBackend = backend
        runCatching { effects?.release() }
        effects = null
        runCatching { oldBackend?.stop() }

        val activation = runCatching { activate(LegacyAudioBackend()) }
            .getOrElse {
                backend = null
                baseEffectStats = AudioStats()
                running = false
                fallbackReason = "$reason O fallback também falhou: ${it.javaClass.simpleName}."
                AudioRuntime.updateStats(
                    AudioStats(
                        engineBackend = "Falha de captura",
                        dspBackend = "Nenhum",
                        fallbackReason = fallbackReason,
                    )
                )
                AudioRuntime.updateState(EngineState.ERROR)
                return@synchronized false
            }

        backend = activation.backend
        effects = activation.preprocessing
        baseEffectStats = activation.effectStats
        fallbackReason = reason
        publishSnapshot(activation.backend.snapshot())
        true
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
                fallbackReason = fallbackReason,
            )
        )
    }

    fun stop() {
        if (!running && backend == null) return
        AudioRuntime.updateState(EngineState.STOPPING)
        running = false

        statsWorker?.interrupt()
        statsWorker?.join(400)
        statsWorker = null

        synchronized(lifecycleLock) {
            effects?.release()
            effects = null
            runCatching { backend?.stop() }
            backend = null
            baseEffectStats = AudioStats()
            fallbackReason = null
        }

        AudioRuntime.updateStats(AudioStats())
        AudioRuntime.updateState(EngineState.IDLE)
    }
}
