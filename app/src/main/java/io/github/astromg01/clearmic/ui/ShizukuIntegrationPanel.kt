package io.github.astromg01.clearmic.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.astromg01.clearmic.audio.AudioRuntime
import io.github.astromg01.clearmic.audio.EngineState
import io.github.astromg01.clearmic.service.GameMicService
import io.github.astromg01.clearmic.system.shizuku.GameBridgeVerdict
import io.github.astromg01.clearmic.system.shizuku.PrivilegedAudioReport
import io.github.astromg01.clearmic.system.shizuku.ShizukuAudioBridgeV17
import io.github.astromg01.clearmic.system.shizuku.ShizukuAudioRuntime
import io.github.astromg01.clearmic.system.shizuku.ShizukuBridgeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ShizukuIntegrationPanel(
    onBeforeEnableGameBridge: () -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
    val bridge = remember(appContext) { ShizukuAudioBridgeV17(appContext) }
    val report by ShizukuAudioRuntime.report.collectAsState()
    val scope = rememberCoroutineScope()
    var handoffInProgress by remember { mutableStateOf(false) }
    var handoffMessage by remember { mutableStateOf<String?>(null) }
    var showTechnical by remember { mutableStateOf(false) }

    DisposableEffect(bridge) {
        bridge.start()
        onDispose { bridge.stop() }
    }

    LaunchedEffect(report.gameBridgeEnabled) {
        if (report.gameBridgeEnabled) {
            while (true) {
                delay(1_500L)
                bridge.refreshGameBridgeStatus()
            }
        }
    }

    fun startProtection() {
        if (handoffInProgress) return
        handoffInProgress = true
        handoffMessage = "Liberando o microfone local…"
        scope.launch {
            stopLocalEngine(appContext)
            onBeforeEnableGameBridge()

            val deadline = SystemClock.elapsedRealtime() + 4_000L
            while (
                AudioRuntime.state.value != EngineState.IDLE &&
                AudioRuntime.state.value != EngineState.ERROR &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                delay(50L)
            }

            val released = AudioRuntime.state.value == EngineState.IDLE ||
                AudioRuntime.state.value == EngineState.ERROR
            if (released) {
                handoffMessage = "Registrando ${profileLabel(report.gameEnhancementProfile)} antes do jogo abrir o microfone…"
                bridge.setGameBridgeEnabled(true)
                delay(350L)
                bridge.refreshGameBridgeStatus()
                handoffMessage = null
            } else {
                handoffMessage = "Não foi possível liberar o microfone em 4 s. A proteção não foi ativada para evitar conflito com o jogo."
            }
            handoffInProgress = false
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Game Voice Protection", style = MaterialTheme.typography.titleLarge)
            Text(
                "Alpha18 • adaptive voice chain",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            val protection = protectionLabel(report)
            Text(
                protection,
                style = MaterialTheme.typography.titleMedium,
                color = protectionColor(report),
            )

            lastPackage(report.gameBridgeStatus)?.let {
                Text("Último jogo/app: $it")
            }
            verifiedChain(report.gameBridgeStatus)?.let {
                Text("Cadeia verificada: $it", color = MaterialTheme.colorScheme.primary)
            }
            healthMetrics(report.gameBridgeStatus)?.let { (protected, failed) ->
                Text("Sessões protegidas: $protected • falhas confirmadas: $failed")
            }
            advancedMetric(report.gameBridgeStatus)?.let {
                Text("Sessões com aprimoramento extra: $it")
            }

            Text(
                when {
                    report.gameBridgeEnabled && protection.contains("CONFIRMADA") ->
                        "A cadeia real do jogo foi verificada. Se aparecer NS+AEC, NS+AGC ou NS+VENDOR, o aprimoramento extra também entrou de verdade."
                    report.gameBridgeEnabled ->
                        "Proteção armada. Pode abrir o jogo e usar o chat de voz; o daemon detecta e valida a cadeia automaticamente."
                    else ->
                        "Escolha um perfil e ative antes de abrir o chat de voz do jogo."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Perfil", style = MaterialTheme.typography.titleMedium)
            Text(profileLabel(report.gameEnhancementProfile), color = MaterialTheme.colorScheme.primary)

            val bridgeReady = report.state == ShizukuBridgeState.READY_SHELL ||
                report.state == ShizukuBridgeState.READY_ROOT
            val profileEnabled = bridgeReady && !report.gameBridgeEnabled && !handoffInProgress

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProfileButton("Leve", report.gameEnhancementProfile == "LIGHT", profileEnabled) {
                    bridge.setGameEnhancementProfile("LIGHT")
                }
                ProfileButton("Balanceado", report.gameEnhancementProfile == "BALANCED", profileEnabled) {
                    bridge.setGameEnhancementProfile("BALANCED")
                }
                ProfileButton("Forte", report.gameEnhancementProfile == "STRONG", profileEnabled) {
                    bridge.setGameEnhancementProfile("STRONG")
                }
            }

            Text(profileDescription(report.gameEnhancementProfile), style = MaterialTheme.typography.bodySmall)

            handoffMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            if (bridgeReady) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (report.gameBridgeEnabled) {
                        Button(
                            enabled = !handoffInProgress,
                            onClick = { bridge.setGameBridgeEnabled(false) },
                        ) { Text("Desativar proteção") }
                    } else {
                        Button(
                            enabled = !handoffInProgress && report.modifyDefaultAudioEffectsGranted,
                            onClick = { startProtection() },
                        ) { Text(if (handoffInProgress) "Preparando…" else "Ativar proteção") }
                    }
                    OutlinedButton(
                        enabled = !handoffInProgress,
                        onClick = { bridge.refreshGameBridgeStatus() },
                    ) { Text("Atualizar") }
                }
            } else {
                ConnectionActions(report, bridge, appContext)
            }

            OutlinedButton(onClick = { showTechnical = !showTechnical }) {
                Text(if (showTechnical) "Ocultar detalhes técnicos" else "Detalhes técnicos")
            }

            if (showTechnical) {
                TechnicalDetails(report, bridge, appContext, handoffInProgress)
            }

            Text(
                "Segurança: efeitos extras do perfil Forte só são tentados quando o firmware os anuncia como preprocessadores de voz compatíveis. Efeitos de saída como EQ, bass, reverb e virtualizer são bloqueados. Tudo é transitório e liberado ao desativar.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TechnicalDetails(
    report: PrivilegedAudioReport,
    bridge: ShizukuAudioBridgeV17,
    context: Context,
    handoffInProgress: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Diagnóstico avançado", style = MaterialTheme.typography.titleMedium)
        Text("Shizuku: ${stateLabel(report.state)}")
        Text("Modo/UID: ${uidLabel(report.serverUid)} • API ${if (report.serverVersion >= 0) report.serverVersion else "—"}")
        Text("SELinux: ${report.selinuxContext}")
        Text("MODIFY_AUDIO_ROUTING: ${yesNo(report.modifyAudioRoutingGranted)}")
        Text("MODIFY_DEFAULT_AUDIO_EFFECTS: ${yesNo(report.modifyDefaultAudioEffectsGranted)}")
        Text("CAPTURE_AUDIO_OUTPUT: ${yesNo(report.captureAudioOutputGranted)}")
        Text("Audio ${okNo(report.audioServiceReadable)} • Flinger ${okNo(report.audioFlingerReadable)} • Policy ${okNo(report.audioPolicyReadable)}")
        Text("Efeitos detectados: ${report.nativeEffectHints.ifEmpty { listOf("nenhum") }.joinToString(" • ")}")
        Text("Veredito: ${verdictLabel(report.verdict)}", color = MaterialTheme.colorScheme.primary)
        Text(report.recommendation, style = MaterialTheme.typography.bodySmall)

        if (report.recordingPackageHints.isNotEmpty()) {
            Text("Pacotes vistos na captura:", style = MaterialTheme.typography.labelLarge)
            report.recordingPackageHints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }

        Text("Status bruto do daemon:", style = MaterialTheme.typography.labelLarge)
        report.gameBridgeStatus.lineSequence().take(12).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        if (report.activeRecordingSnapshot != "—") {
            Text("Sessões atuais:", style = MaterialTheme.typography.labelLarge)
            report.activeRecordingSnapshot.lineSequence().take(7).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (report.scanDurationMs > 0) Text("Scan: ${report.scanDurationMs} ms", style = MaterialTheme.typography.bodySmall)
        report.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        ConnectionActions(report, bridge, context, handoffInProgress)
    }
}

@Composable
private fun ConnectionActions(
    report: PrivilegedAudioReport,
    bridge: ShizukuAudioBridgeV17,
    context: Context,
    disabled: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (report.state) {
            ShizukuBridgeState.NOT_RUNNING ->
                OutlinedButton(enabled = !disabled, onClick = { openShizuku(context) }) { Text("Abrir Shizuku") }
            ShizukuBridgeState.PERMISSION_REQUIRED,
            ShizukuBridgeState.PERMISSION_DENIED ->
                Button(enabled = !disabled, onClick = { bridge.requestPermission() }) { Text("Autorizar Shizuku") }
            else -> Unit
        }
        OutlinedButton(
            enabled = !disabled && report.state != ShizukuBridgeState.SCANNING && report.state != ShizukuBridgeState.CONNECTING,
            onClick = { bridge.refresh() },
        ) { Text("Reescanear") }
    }
}

@Composable
private fun ProfileButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (selected) Button(enabled = enabled, onClick = onClick) { Text(label) }
    else OutlinedButton(enabled = enabled, onClick = onClick) { Text(label) }
}

private fun protectionLabel(report: PrivilegedAudioReport): String {
    val status = report.gameBridgeStatus
    return when {
        !report.gameBridgeEnabled -> "PROTEÇÃO DESATIVADA"
        "PROTECTION: CONFIRMED" in status || "VERIFIED=NS" in status -> "PROTEÇÃO CONFIRMADA ✓"
        "PROTECTION: WARNING" in status || "ERROR:" in status -> "ATENÇÃO • EFEITO NÃO CONFIRMADO"
        else -> "PROTEÇÃO ARMADA • AGUARDANDO JOGO"
    }
}

@Composable
private fun protectionColor(report: PrivilegedAudioReport) = when {
    report.gameBridgeStatus.contains("WARNING") || report.gameBridgeStatus.contains("ERROR:") -> MaterialTheme.colorScheme.error
    report.gameBridgeEnabled -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}

private fun lastPackage(status: String): String? {
    val health = Regex("PROTECTION:.*?last=([^ •\\n]+)").find(status)?.groupValues?.getOrNull(1)
    if (!health.isNullOrBlank() && health != "—") return health
    return Regex("LAST_EXTERNAL:\\s+([^\\s]+)").find(status)?.groupValues?.getOrNull(1)
}

private fun verifiedChain(status: String): String? {
    val health = Regex("chain=([A-Z+]+)").find(status)?.groupValues?.getOrNull(1)
    if (!health.isNullOrBlank()) return health
    return Regex("VERIFIED=([A-Z+]+)").find(status)?.groupValues?.getOrNull(1)
}

private fun healthMetrics(status: String): Pair<Int, Int>? {
    val match = Regex("protected=(\\d+)\\s+•\\s+failed=(\\d+)").find(status) ?: return null
    return (match.groupValues[1].toIntOrNull() ?: 0) to (match.groupValues[2].toIntOrNull() ?: 0)
}

private fun advancedMetric(status: String): Int? =
    Regex("advanced=(\\d+)").find(status)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun profileDescription(profile: String): String = when (profile) {
    "LIGHT" -> "Leve: Noise Suppression somente. Máxima preservação do timbre."
    "STRONG" -> "Forte adaptativo: NS + AEC/AGC quando apropriados e até dois preprocessadores vendor de voz considerados seguros. Extras só contam se aparecerem na cadeia real do jogo."
    else -> "Balanceado: NS sempre + AEC apenas em VOICE_COMMUNICATION. Recomendado para estabilidade."
}

private fun profileLabel(profile: String): String = when (profile) {
    "LIGHT" -> "Leve • NS"
    "STRONG" -> "Forte adaptativo • NS + extras verificados"
    else -> "Balanceado • NS + AEC em comunicação"
}

private fun stopLocalEngine(context: Context) {
    runCatching {
        context.startService(Intent(context, GameMicService::class.java).setAction(GameMicService.ACTION_STOP))
    }
}

private fun openShizuku(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun stateLabel(state: ShizukuBridgeState): String = when (state) {
    ShizukuBridgeState.NOT_RUNNING -> "NÃO CONECTADO"
    ShizukuBridgeState.PERMISSION_REQUIRED -> "AGUARDANDO AUTORIZAÇÃO"
    ShizukuBridgeState.PERMISSION_DENIED -> "PERMISSÃO NEGADA"
    ShizukuBridgeState.CONNECTING -> "CONECTANDO"
    ShizukuBridgeState.SCANNING -> "ESCANEANDO"
    ShizukuBridgeState.READY_SHELL -> "PRONTO • SHELL/ADB"
    ShizukuBridgeState.READY_ROOT -> "PRONTO • ROOT"
    ShizukuBridgeState.ERROR -> "ERRO"
}

private fun verdictLabel(verdict: GameBridgeVerdict): String = when (verdict) {
    GameBridgeVerdict.SHIZUKU_NOT_READY -> "SHIZUKU NÃO PRONTO"
    GameBridgeVerdict.DIAGNOSTICS_ONLY -> "SOMENTE DIAGNÓSTICO"
    GameBridgeVerdict.ROUTING_PERMISSION_CANDIDATE -> "SOURCE BRIDGE PRONTO"
    GameBridgeVerdict.ROOT_SYSTEM_BRIDGE_READY -> "ROOT SYSTEM BRIDGE"
}

private fun uidLabel(uid: Int): String = when (uid) {
    0 -> "0 ROOT"
    2000 -> "2000 SHELL/ADB"
    -1 -> "—"
    else -> uid.toString()
}

private fun yesNo(value: Boolean) = if (value) "SIM" else "NÃO"
private fun okNo(value: Boolean) = if (value) "OK" else "INDISPONÍVEL"
