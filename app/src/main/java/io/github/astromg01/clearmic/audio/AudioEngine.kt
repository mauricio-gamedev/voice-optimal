package io.github.astromg01.clearmic.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat

class AudioEngine(
    private val context: Context,
) {
    companion object {
        private const val STATS_POLL_INTERVAL_MS = 750L
        private const val NATIVE_SILENT_FRAME_THRESHOLD = 96_000L
        private const val NATIVE_STALL_TIMEOUT_MS = 2_500L
        private const val NATIVE_RECOVERY_COOLDOWN_MS = 1_500L
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

    @Volatile
    private var captureHealth = "IDLE"

    @Volatile
    private var latestSnapshot = BackendSnapshot()

    @Volatile
    private var lastFrameAdvanceAtMs = 0L

    private var lastObservedFrames = 0L
    private var nativeRouteVerified = false
    private var nativeStartupRecoveryAttempts = 0
    private var captureRecoveryCount = 0
    private var lastNativeRecoveryAtMs = 0L
    private var nativeMonitorStartedAtMs = 0L

    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            check(
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            ) { "RECORD_AUDIO permission is required" }

            AudioRuntime.updateState(EngineState.STARTING)
            fallbackReason = null
            captureHealth = "STARTING"
            nativeRouteVerified = false
            nativeStartupRecoveryAttempts = 0
            captureRecoveryCount = 0

            val activation = runCatching {
                activate(NativeAudioBackend())
            }.getOrElse { nativeError ->
                fallbackReason = "AAudio não abriu (${nativeError.javaClass.simpleName}); usando AudioRecord seguro."
                runCatching { activate(LegacyAudioBackend()) }
                    .getOrElse { fallbackError ->
                        captureHealth = "ERROR"
                        AudioRuntime.updateState(EngineState.ERROR)
                        throw IllegalStateException("No audio backend could start", fallbackError)
                    }
            }

            backend = activation.backend
            effects = activation.preprocessing
            baseEffectStats = activation.effectStats
            running = true
            resetFrameMonitor()

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

    private fun resetFrameMonitor() {
        val now = SystemClock.elapsedRealtime()
        nativeMonitorStartedAtMs = now
        lastFrameAdvanceAtMs = now
        lastObservedFrames = 0L
        latestSnapshot = BackendSnapshot()
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
                        latestSnapshot = snapshot
                        if (activeBackend is NativeAudioBackend) {
                            inspectNativeCapture(snapshot)
                        } else {
                            captureHealth = if (snapshot.capturedFrames > 0L) "LIVE_FALLBACK" else "STARTING_FALLBACK"
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

    private fun inspectNativeCapture(snapshot: BackendSnapshot) {
        val now = SystemClock.elapsedRealtime()
        val framesAdvanced = snapshot.capturedFrames > lastObservedFrames
        if (framesAdvanced) {
            lastObservedFrames = snapshot.capturedFrames
            lastFrameAdvanceAtMs = now
        }

        val hardSilence = snapshot.rmsDb <= -119.5f && snapshot.peak <= 0.00001f
        if (!hardSilence) {
            nativeRouteVerified = true
            captureHealth = "LIVE"
        } else if (framesAdvanced && nativeRouteVerified) {
            // Android can intentionally silence a background ordinary app while another app
            // owns microphone priority. Keep the stream alive and wait for unsilencing.
            captureHealth = "SILENCED_BY_SYSTEM"
        }

        val stalled = now - lastFrameAdvanceAtMs >= NATIVE_STALL_TIMEOUT_MS
        if (stalled) {
            captureHealth = "STALLED"
            val recovered = recoverNativeSession("AAudio parou de entregar frames; sessão reaberta automaticamente.")
            if (!recovered) publishSnapshot(snapshot)
            return
        }

        val startupSilent =
            !nativeRouteVerified &&
                snapshot.capturedFrames >= NATIVE_SILENT_FRAME_THRESHOLD &&
                now - nativeMonitorStartedAtMs >= NATIVE_STALL_TIMEOUT_MS &&
                hardSilence

        if (startupSilent) {
            if (nativeStartupRecoveryAttempts == 0) {
                nativeStartupRecoveryAttempts++
                val recovered = recoverNativeSession("AAudio iniciou silencioso; tentando uma nova sessão.")
                if (!recovered) publishSnapshot(snapshot)
            } else {
                switchToLegacy("AAudio continuou silencioso após recuperação; fallback automático ativado.")
            }
            return
        }

        publishSnapshot(snapshot)
    }

    /**
     * Called when the Activity becomes visible again. If Android left AAudio frozen or
     * hard-silenced during an app switch, reopen the native session immediately.
     */
    fun onClientVisible(): Boolean {
        if (!running || backend !is NativeAudioBackend) return false

        val now = SystemClock.elapsedRealtime()
        val snapshot = latestSnapshot
        val stalled = now - lastFrameAdvanceAtMs >= 1_200L
        val hardSilence =
            nativeRouteVerified &&
                snapshot.capturedFrames > 0L &&
                snapshot.rmsDb <= -119.5f &&
                snapshot.peak <= 0.00001f

        return if (stalled || hardSilence) {
            recoverNativeSession("Retorno ao ClearMic detectado; sessão do microfone recuperada.")
        } else {
            false
        }
    }

    private fun recoverNativeSession(reason: String): Boolean = synchronized(lifecycleLock) {
        if (!running || backend !is NativeAudioBackend) return@synchronized false

        val now = SystemClock.elapsedRealtime()
        if (now - lastNativeRecoveryAtMs < NATIVE_RECOVERY_COOLDOWN_MS) {
            return@synchronized false
        }
        lastNativeRecoveryAtMs = now
        captureHealth = "RECOVERING"

        val oldBackend = backend
        runCatching { effects?.release() }
        effects = null
        runCatching { oldBackend?.stop() }

        val activation = runCatching { activate(NativeAudioBackend()) }
            .getOrElse {
                fallbackReason = "$reason Reabertura nativa falhou: ${it.javaClass.simpleName}."
                return@synchronized switchToLegacy("$reason AAudio não reabriu; fallback seguro ativado.")
            }

        backend = activation.backend
        effects = activation.preprocessing
        baseEffectStats = activation.effectStats
        captureRecoveryCount++
        fallbackReason = reason
        captureHealth = "RECOVERED"
        resetFrameMonitor()
        publishSnapshot(activation.backend.snapshot())
        true
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
                captureHealth = "ERROR"
                fallbackReason = "$reason O fallback também falhou: ${it.javaClass.simpleName}."
                AudioRuntime.updateStats(
                    AudioStats(
                        engineBackend = "Falha de captura",
                        dspBackend = "Nenhum",
                        fallbackReason = fallbackReason,
                        captureHealth = captureHealth,
                        captureRecoveryCount = captureRecoveryCount,
                    )
                )
                AudioRuntime.updateState(EngineState.ERROR)
                return@synchronized false
            }

        backend = activation.backend
        effects = activation.preprocessing
        baseEffectStats = activation.effectStats
        fallbackReason = reason
        captureHealth = "LIVE_FALLBACK"
        captureRecoveryCount++
        latestSnapshot = activation.backend.snapshot()
        publishSnapshot(latestSnapshot)
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
                captureHealth = captureHealth,
                captureRecoveryCount = captureRecoveryCount,
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
            captureHealth = "IDLE"
            latestSnapshot = BackendSnapshot()
            lastObservedFrames = 0L
            nativeRouteVerified = false
            nativeStartupRecoveryAttempts = 0
            captureRecoveryCount = 0
        }

        AudioRuntime.updateStats(AudioStats())
        AudioRuntime.updateState(EngineState.IDLE)
    }
}
