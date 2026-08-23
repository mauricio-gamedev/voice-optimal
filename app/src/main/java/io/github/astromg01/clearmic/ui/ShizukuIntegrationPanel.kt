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
import io.github.astromg01.clearmic.system.shizuku.ShizukuAudioBridge
import io.github.astromg01.clearmic.system.shizuku.ShizukuAudioRuntime
import io.github.astromg01.clearmic.system.shizuku.ShizukuBridgeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ShizukuIntegrationPanel(
    onBeforeEnableGameBridge: () -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
    val bridge = remember(appContext) { ShizukuAudioBridge(appContext) }
    val report by ShizukuAudioRuntime.report.collectAsState()
    val scope = rememberCoroutineScope()
    var handoffInProgress by remember { mutableStateOf(false) }
    var handoffMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(bridge) {
        bridge.start()
        onDispose { bridge.stop() }
    }

    fun startGameBridgeWithHandoff() {
        if (handoffInProgress) return
        handoffInProgress = true
        handoffMessage = "Parando a captura local antes de entregar o microfone ao jogo…"

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

            val localReleased = AudioRuntime.state.value == EngineState.IDLE ||
                AudioRuntime.state.value == EngineState.ERROR

            if (localReleased) {
                handoffMessage = "Microfone local liberado. Registrando perfil ${profileLabel(report.gameEnhancementProfile)} antes do jogo abrir a captura…"
                bridge.setGameBridgeEnabled(true)
                delay(250L)
                bridge.refreshGameBridgeStatus()
                handoffMessage = null
            } else {
                handoffMessage = "Falha de handoff: a captura local não encerrou em 4 s. O Game Enhance não foi ativado para evitar disputa do microfone."
            }
            handoffInProgress = false
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Shizuku — Game Voice Enhance", style = MaterialTheme.typography.titleMedium)
            Text(
                "Alpha16 mantém a ponte source-default que funcionou no Roblox e adiciona perfis seguros de voz. O jogo continua dono do microfone; o ClearMic só registra preprocessamento transitório antes da sessão nascer.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Estado: ${stateLabel(report.state)}")
            Text("Modo/UID: ${uidLabel(report.serverUid)}")
            Text("Shizuku API: ${if (report.serverVersion >= 0) report.serverVersion else "—"}")
            Text("Contexto SELinux: ${report.selinuxContext}")

            Text(
                "MODIFY_AUDIO_ROUTING: ${yesNo(report.modifyAudioRoutingGranted)}",
                color = permissionColor(report.modifyAudioRoutingGranted),
            )
            Text(
                "MODIFY_DEFAULT_AUDIO_EFFECTS: ${yesNo(report.modifyDefaultAudioEffectsGranted)}",
                color = permissionColor(report.modifyDefaultAudioEffectsGranted),
            )
            Text("CAPTURE_AUDIO_OUTPUT: ${yesNo(report.captureAudioOutputGranted)}")

            Text(
                "Dumpsys: Audio ${okNo(report.audioServiceReadable)} • " +
                    "Flinger ${okNo(report.audioFlingerReadable)} • Policy ${okNo(report.audioPolicyReadable)}"
            )
            Text("VOICE_COMMUNICATION runtime: ${yesNo(report.voiceCommunicationSeen)}")
            Text(
                "Efeitos encontrados: ${report.nativeEffectHints.ifEmpty { listOf("nenhum confirmado") }.joinToString(" • ")}"
            )

            Text(
                "Veredito: ${verdictLabel(report.verdict)}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(report.recommendation, color = MaterialTheme.colorScheme.primary)

            if (report.recordingPackageHints.isNotEmpty()) {
                Text("Apps/pacotes ligados à captura:", style = MaterialTheme.typography.labelLarge)
                report.recordingPackageHints.take(6).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text("Perfil de voz para jogos", style = MaterialTheme.typography.titleMedium)
            Text(
                "Atual: ${profileLabel(report.gameEnhancementProfile)}",
                color = MaterialTheme.colorScheme.primary,
            )

            val profileControlsEnabled = !report.gameBridgeEnabled && !handoffInProgress &&
                (report.state == ShizukuBridgeState.READY_SHELL || report.state == ShizukuBridgeState.READY_ROOT)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProfileButton(
                    label = "Leve",
                    selected = report.gameEnhancementProfile == "LIGHT",
                    enabled = profileControlsEnabled,
                    onClick = { bridge.setGameEnhancementProfile("LIGHT") },
                )
                ProfileButton(
                    label = "Balanceado",
                    selected = report.gameEnhancementProfile == "BALANCED",
                    enabled = profileControlsEnabled,
                    onClick = { bridge.setGameEnhancementProfile("BALANCED") },
                )
                ProfileButton(
                    label = "Forte",
                    selected = report.gameEnhancementProfile == "STRONG",
                    enabled = profileControlsEnabled,
                    onClick = { bridge.setGameEnhancementProfile("STRONG") },
                )
            }

            Text(
                when (report.gameEnhancementProfile) {
                    "LIGHT" -> "Leve: Noise Suppression apenas. Menor chance de alterar o timbre da voz."
                    "STRONG" -> "Forte: NS + AEC em comunicação + AGC quando o firmware realmente oferecer. A intensidade interna do NS continua sendo definida pela Samsung."
                    else -> "Balanceado: NS sempre + AEC somente em VOICE_COMMUNICATION. Recomendado para uso normal."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Game Voice Enhance", style = MaterialTheme.typography.titleMedium)
            Text(
                if (report.gameBridgeEnabled) "Estado: ATIVO" else "Estado: DESATIVADO",
                color = if (report.gameBridgeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(report.gameBridgeStatus, style = MaterialTheme.typography.bodySmall)

            handoffMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            if (report.activeRecordingSnapshot != "—") {
                Text("Sessões de gravação agora:", style = MaterialTheme.typography.labelLarge)
                report.activeRecordingSnapshot
                    .lineSequence()
                    .take(7)
                    .forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            val bridgeReady = report.state == ShizukuBridgeState.READY_SHELL ||
                report.state == ShizukuBridgeState.READY_ROOT

            if (bridgeReady) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (report.gameBridgeEnabled) {
                        Button(
                            enabled = !handoffInProgress,
                            onClick = { bridge.setGameBridgeEnabled(false) },
                        ) {
                            Text("Desativar Enhance")
                        }
                    } else {
                        Button(
                            enabled = !handoffInProgress && report.modifyDefaultAudioEffectsGranted,
                            onClick = { startGameBridgeWithHandoff() },
                        ) {
                            Text(if (handoffInProgress) "Liberando mic…" else "Ativar Game Enhance")
                        }
                    }

                    OutlinedButton(
                        enabled = !handoffInProgress,
                        onClick = { bridge.refreshGameBridgeStatus() },
                    ) {
                        Text("Atualizar")
                    }
                }

                Text(
                    "O alpha16 registra os efeitos antes do jogo criar o AudioRecord e depois verifica a cadeia real via IAudioService. O status VERIFIED mostra o que entrou de verdade na sessão. O último alvo externo fica salvo após o jogo fechar o microfone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (report.scanDurationMs > 0L) {
                Text("Último scan: ${report.scanDurationMs} ms", style = MaterialTheme.typography.bodySmall)
            }
            report.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (report.state) {
                    ShizukuBridgeState.NOT_RUNNING -> {
                        OutlinedButton(onClick = { openShizuku(appContext) }) { Text("Abrir Shizuku") }
                    }
                    ShizukuBridgeState.PERMISSION_REQUIRED,
                    ShizukuBridgeState.PERMISSION_DENIED -> {
                        Button(onClick = { bridge.requestPermission() }) { Text("Autorizar Shizuku") }
                    }
                    else -> Unit
                }

                OutlinedButton(
                    enabled = !handoffInProgress &&
                        report.state != ShizukuBridgeState.SCANNING &&
                        report.state != ShizukuBridgeState.CONNECTING,
                    onClick = { bridge.refresh() },
                ) {
                    Text(if (report.state == ShizukuBridgeState.SCANNING) "Escaneando…" else "Escanear Shizuku")
                }
            }

            Text(
                "Segurança: os perfis são transitórios. O ClearMic não faz remount, não edita audio_effects, não grava em /system ou /vendor e libera os defaults ao desativar o Game Enhance.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProfileButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(enabled = enabled, onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(enabled = enabled, onClick = onClick) { Text(label) }
    }
}

private fun stopLocalEngine(context: Context) {
    runCatching {
        context.startService(
            Intent(context, GameMicService::class.java)
                .setAction(GameMicService.ACTION_STOP)
        )
    }
}

@Composable
private fun permissionColor(granted: Boolean) =
    if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

private fun openShizuku(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

private fun stateLabel(state: ShizukuBridgeState): String = when (state) {
    ShizukuBridgeState.NOT_RUNNING -> "BINDER DO SHIZUKU NÃO CONECTADO"
    ShizukuBridgeState.PERMISSION_REQUIRED -> "SHIZUKU DETECTADO • AGUARDANDO AUTORIZAÇÃO"
    ShizukuBridgeState.PERMISSION_DENIED -> "PERMISSÃO NEGADA"
    ShizukuBridgeState.CONNECTING -> "CONECTANDO USERSERVICE"
    ShizukuBridgeState.SCANNING -> "DIAGNÓSTICO EM ANDAMENTO"
    ShizukuBridgeState.READY_SHELL -> "PRONTO • SHELL/ADB"
    ShizukuBridgeState.READY_ROOT -> "PRONTO • ROOT"
    ShizukuBridgeState.ERROR -> "ERRO"
}

private fun verdictLabel(verdict: GameBridgeVerdict): String = when (verdict) {
    GameBridgeVerdict.SHIZUKU_NOT_READY -> "SHIZUKU AINDA NÃO PRONTO"
    GameBridgeVerdict.DIAGNOSTICS_ONLY -> "SHIZUKU SHELL — DIAGNÓSTICO"
    GameBridgeVerdict.ROUTING_PERMISSION_CANDIDATE -> "GAME EFFECT SOURCE BRIDGE READY"
    GameBridgeVerdict.ROOT_SYSTEM_BRIDGE_READY -> "ROOT — SYSTEM BRIDGE PRONTO PARA PROTÓTIPO"
}

private fun profileLabel(profile: String): String = when (profile) {
    "LIGHT" -> "Leve • NS"
    "STRONG" -> "Forte • NS/AEC/AGC quando disponível"
    else -> "Balanceado • NS + AEC em comunicação"
}

private fun uidLabel(uid: Int): String = when (uid) {
    0 -> "0 • ROOT"
    2000 -> "2000 • SHELL/ADB"
    -1 -> "—"
    else -> "$uid • OUTRO"
}

private fun yesNo(value: Boolean): String = if (value) "SIM" else "NÃO"
private fun okNo(value: Boolean): String = if (value) "OK" else "BLOQUEADO/INDISPONÍVEL"
