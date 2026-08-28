package io.github.astromg01.clearmic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.astromg01.clearmic.audio.AiDspProfile
import io.github.astromg01.clearmic.audio.AiDspSettings
import io.github.astromg01.clearmic.audio.AudioRuntime
import io.github.astromg01.clearmic.system.shizuku.ShizukuAudioRuntime
import kotlin.math.roundToInt

@Composable
internal fun AiEnginePanel() {
    val context = LocalContext.current.applicationContext
    val stats by AudioRuntime.stats.collectAsState()
    val gameReport by ShizukuAudioRuntime.report.collectAsState()
    var selected by remember(context) { mutableStateOf(AiDspSettings.read(context)) }

    fun select(profile: AiDspProfile) {
        selected = profile
        AiDspSettings.write(context, profile)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("ClearMic AI", style = MaterialTheme.typography.titleLarge)
            Text(
                "Motor AI V1 • RNNoise local • 48 kHz / 10 ms",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )

            Text("Perfil selecionado: ${selected.label}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProfileButton("Natural", selected == AiDspProfile.NATURAL) { select(AiDspProfile.NATURAL) }
                ProfileButton("Balanceado", selected == AiDspProfile.BALANCED) { select(AiDspProfile.BALANCED) }
                ProfileButton("Forte", selected == AiDspProfile.STRONG) { select(AiDspProfile.STRONG) }
            }
            OutlinedButton(onClick = { select(AiDspProfile.OFF) }) {
                Text("Bypass AI")
            }

            Text(
                when (selected) {
                    AiDspProfile.NATURAL -> "Natural mistura ~35% do sinal neural para preservar mais o timbre."
                    AiDspProfile.BALANCED -> "Balanceado mistura ~70% do RNNoise e mantém o DSP V3 apenas como acabamento. Recomendado."
                    AiDspProfile.STRONG -> "Forte usa o RNNoise integral e é voltado para ventilador, TV e ambiente mais barulhento."
                    AiDspProfile.OFF -> "Bypass: usa apenas o Native Adaptive V3, sem RNNoise."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            if (stats.engineBackend == "AAudio C++") {
                Text(
                    if (stats.aiActive) "AI: ATIVA • ${stats.aiEffectiveProfile.label}" else "AI: BYPASS / FALLBACK",
                    color = if (stats.aiActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text("VAD neural: ${stats.aiVad.times(100f).roundToInt()}%")
                Text("Custo RNNoise: %.2f ms / frame de 10 ms".format(stats.aiProcessingMs))
                if (selected != AiDspProfile.OFF && !stats.aiActive && stats.capturedFrames > 0L) {
                    Text(
                        "Fail-safe ativo: se RNNoise consumir repetidamente mais de 8,5 ms do callback, o ClearMic volta ao DSP V3 para evitar XRuns/travamentos.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text("Ative o motor local para medir a IA neste aparelho.", style = MaterialTheme.typography.bodySmall)
            }

            if (gameReport.gameBridgeEnabled) {
                Text(
                    "Game Voice Protection está usando a cadeia system-wide do Android. O RNNoise V1 ainda é um motor PCM local e não é injetado no jogo nesta versão.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Mudanças de perfil entram no backend nativo em até ~1 s, sem reiniciar o app.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProfileButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}
