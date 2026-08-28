package io.github.astromg01.clearmic.audio

import android.content.Context

enum class AiDspProfile(
    val code: Int,
    val label: String,
    val shortLabel: String,
) {
    OFF(0, "Desligado", "OFF"),
    NATURAL(1, "Natural", "NATURAL"),
    BALANCED(2, "AI Balanceado", "BALANCED"),
    STRONG(3, "AI Forte", "STRONG"),
    ;

    companion object {
        fun fromCode(code: Int): AiDspProfile = entries.firstOrNull { it.code == code } ?: BALANCED
        fun fromStored(value: String?): AiDspProfile = entries.firstOrNull { it.name == value } ?: BALANCED
    }
}

object AiDspSettings {
    private const val PREFS = "clearmic_ai_engine"
    private const val KEY_PROFILE = "profile"

    fun read(context: Context): AiDspProfile = AiDspProfile.fromStored(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILE, AiDspProfile.BALANCED.name)
    )

    fun write(context: Context, profile: AiDspProfile) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILE, profile.name)
            .apply()
    }
}
