package io.github.astromg01.clearmic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
    }

    private lateinit var engine: AudioEngine

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine = AudioEngine(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEngineAndSelf()
            ACTION_START, null -> startEngineSafely()
        }
        return START_NOT_STICKY
    }

    private fun startEngineSafely() {
        if (AudioRuntime.state.value == EngineState.RUNNING) return

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )

        runCatching { engine.start() }
            .onFailure {
                AudioRuntime.updateState(EngineState.ERROR)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
    }

    private fun stopEngineAndSelf() {
        engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine.stop()
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

    private fun buildNotification(): Notification {
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
            .setContentText("Processamento local de microfone em execução")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Desativar", stopIntent)
            .build()
    }
}
