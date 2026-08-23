package io.github.astromg01.clearmic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.astromg01.clearmic.audio.AudioEngine
import io.github.astromg01.clearmic.audio.AudioRuntime
import io.github.astromg01.clearmic.audio.EngineState
import io.github.astromg01.clearmic.ui.MainActivity

class GameMicService : Service() {
    companion object {
        const val ACTION_START = "io.github.astromg01.clearmic.action.START"
        const val ACTION_STOP = "io.github.astromg01.clearmic.action.STOP"

        private const val CHANNEL_ID = "clearmic_active"
        private const val NOTIFICATION_ID = 1001
        private const val DIAGNOSTICS_INTERVAL_MS = 5_000L
    }

    private lateinit var engine: AudioEngine
    private lateinit var survivalManager: BackgroundSurvivalManager
    private val diagnosticsHandler = Handler(Looper.getMainLooper())

    private var serviceStartedElapsedMs = 0L
    private var lastCpuElapsedMs = 0L
    private var lastCpuWallMs = 0L

    private val diagnosticsRunnable = object : Runnable {
        override fun run() {
            publishDiagnostics()
            if (survivalManager.desiredRunning) {
                diagnosticsHandler.postDelayed(this, DIAGNOSTICS_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine = AudioEngine(applicationContext)
        survivalManager = BackgroundSurvivalManager(applicationContext)
        serviceStartedElapsedMs = SystemClock.elapsedRealtime()
        lastCpuElapsedMs = Process.getElapsedCpuTime()
        lastCpuWallMs = serviceStartedElapsedMs
        publishDiagnostics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                survivalManager.markUserStopped()
                publishDiagnostics()
                stopEngineAndSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                survivalManager.markUserStarted()
                startEngineSafely(recovered = false)
            }

            null -> {
                if (!survivalManager.desiredRunning) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                survivalManager.markStickyRestart()
                startEngineSafely(recovered = true)
            }
        }

        return if (survivalManager.desiredRunning) START_STICKY else START_NOT_STICKY
    }

    private fun startEngineSafely(recovered: Boolean) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(recovered),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )

        if (AudioRuntime.state.value != EngineState.RUNNING) {
            val failure = runCatching { engine.start() }.exceptionOrNull()
            if (failure != null) {
                survivalManager.markUserStopped()
                survivalManager.markEvent("Falha ao iniciar motor: ${failure.javaClass.simpleName}")
                AudioRuntime.updateState(EngineState.ERROR)
                publishDiagnostics()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        }

        if (!recovered) {
            survivalManager.markEvent("Motor local em execução")
        }
        scheduleDiagnostics()
    }

    private fun scheduleDiagnostics() {
        diagnosticsHandler.removeCallbacks(diagnosticsRunnable)
        diagnosticsHandler.post(diagnosticsRunnable)
    }

    private fun publishDiagnostics() {
        val nowWall = SystemClock.elapsedRealtime()
        val nowCpu = Process.getElapsedCpuTime()
        val wallDelta = nowWall - lastCpuWallMs
        val cpuDelta = nowCpu - lastCpuElapsedMs
        val cpuPercent = if (wallDelta > 0L) {
            (cpuDelta.toFloat() * 100f / wallDelta.toFloat()).coerceIn(0f, 100f)
        } else {
            0f
        }

        lastCpuWallMs = nowWall
        lastCpuElapsedMs = nowCpu

        val powerManager = getSystemService(PowerManager::class.java)
        val batteryOptimizationActive = !powerManager.isIgnoringBatteryOptimizations(packageName)

        BackgroundRuntime.update(
            BackgroundStats(
                desiredRunning = survivalManager.desiredRunning,
                restartCount = survivalManager.restartCount,
                sessionStartedAtMs = survivalManager.sessionStartedAtMs,
                serviceUptimeMs = (nowWall - serviceStartedElapsedMs).coerceAtLeast(0L),
                memoryPssMb = Debug.getPss() / 1024f,
                cpuPercent = cpuPercent,
                batteryOptimizationActive = batteryOptimizationActive,
                lastEvent = survivalManager.lastEvent,
            )
        )
    }

    private fun stopEngineAndSelf() {
        diagnosticsHandler.removeCallbacks(diagnosticsRunnable)
        engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        diagnosticsHandler.removeCallbacks(diagnosticsRunnable)
        if (::survivalManager.isInitialized && survivalManager.desiredRunning) {
            survivalManager.markEvent("Serviço encerrado; aguardando recuperação do Android")
        }
        if (::engine.isInitialized) engine.stop()
        if (::survivalManager.isInitialized) publishDiagnostics()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ClearMic ativo",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantém o motor de áudio do ClearMic ativo."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(recovered: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GameMicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(io.github.astromg01.clearmic.R.drawable.ic_mic)
            .setContentTitle("ClearMic ativo")
            .setContentText(
                if (recovered) "Motor recuperado e processamento retomado"
                else "Processamento local de microfone em execução"
            )
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Desativar", stopIntent)
            .build()
    }
}
