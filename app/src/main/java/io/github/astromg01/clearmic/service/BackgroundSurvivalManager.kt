package io.github.astromg01.clearmic.service

import android.content.Context

class BackgroundSurvivalManager(context: Context) {
    companion object {
        private const val PREFS = "clearmic_background_survival"
        private const val KEY_DESIRED_RUNNING = "desired_running"
        private const val KEY_RESTART_COUNT = "restart_count"
        private const val KEY_SESSION_STARTED_AT = "session_started_at"
        private const val KEY_LAST_EVENT = "last_event"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val desiredRunning: Boolean
        get() = prefs.getBoolean(KEY_DESIRED_RUNNING, false)

    val restartCount: Int
        get() = prefs.getInt(KEY_RESTART_COUNT, 0)

    val sessionStartedAtMs: Long
        get() = prefs.getLong(KEY_SESSION_STARTED_AT, 0L)

    val lastEvent: String
        get() = prefs.getString(KEY_LAST_EVENT, "Aguardando ativação") ?: "Aguardando ativação"

    fun markUserStarted(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putBoolean(KEY_DESIRED_RUNNING, true)
            .putInt(KEY_RESTART_COUNT, 0)
            .putLong(KEY_SESSION_STARTED_AT, nowMs)
            .putString(KEY_LAST_EVENT, "Ativado pelo usuário")
            .apply()
    }

    fun markUserStopped() {
        prefs.edit()
            .putBoolean(KEY_DESIRED_RUNNING, false)
            .putString(KEY_LAST_EVENT, "Desativado pelo usuário")
            .apply()
    }

    fun markStickyRestart(): Int {
        val next = restartCount + 1
        prefs.edit()
            .putInt(KEY_RESTART_COUNT, next)
            .putString(KEY_LAST_EVENT, "Serviço recuperado pelo Android")
            .apply()
        return next
    }

    fun markEvent(event: String) {
        prefs.edit().putString(KEY_LAST_EVENT, event).apply()
    }
}
