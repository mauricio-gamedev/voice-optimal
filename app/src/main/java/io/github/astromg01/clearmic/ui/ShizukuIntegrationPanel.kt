package io.github.astromg01.clearmic.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.astromg01.clearmic.system.shizuku.GameBridgeVerdict
import io.github.astromg01.clearmic.system.shizuku.ShizukuAudioBridge
import io.github.astromg01.clearmic.system.shizuku.ShizukuAudioRuntime
import io.github.astromg01.clearmic.system.shizuku.ShizukuBridgeState

@Composable
internal fun ShizukuIntegrationPanel() {
    val appContext = LocalContext.current.applicationContext
    val bridge = remember(appContext) { ShizukuAudioBridge(appContext) }
    val report by ShizukuAudioRuntime.report.collectAsState()

    DisposableEffect(bridge) {
        bridge.start()
        onDispose { bridge.stop() }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Shizuku — Bridge privilegiado", style = MaterialTheme.typography.titleMedium)
            Text(
                "Alpha09 faz a etapa inteira de diagnóstico privilegiado sem alterar roteamento ou arquivos do sistema.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Estado: ${stateLabel(report.state)}")
            Text("Modo/UID: ${uidLabel(report.serverUid)}")
            Text("Shizuku API: ${if (report.serverVersion >= 0) report.serverVersion else "—"}")
            Text("Contexto SELinux: ${report.selinuxContext}")
            Text("UserService: ${report.userServiceIdentity}")

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
            Text("AppOps RECORD_AUDIO: ${okNo(report.appOpsReadable)}")
            Text("Configs privilegiadas: ${okNo(report.privilegedConfigsReadable)}")
            Text("VOICE_COMMUNICATION runtime: ${yesNo(report.voiceCommunicationSeen)}")
            Text("Preprocess runtime/config: ${yesNo(report.preprocessSeen)}")
            Text(
                "Efeitos encontrados: ${report.nativeEffectHints.ifEmpty { listOf("nenhum confirmado") }.joinToString(" • ")}"
            )

            if (report.recordingPackageHints.isNotEmpty()) {
                Text("Apps/pacotes ligados à captura:", style = MaterialTheme.typography.labelLarge)
                report.recordingPackageHints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }

            if (report.captureActivityHints.isNotEmpty()) {
                Text("Pistas da cadeia de captura:", style = MaterialTheme.typography.labelLarge)
                report.captureActivityHints.take(5).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                "Veredito: ${verdictLabel(report.verdict)}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(report.recommendation, color = MaterialTheme.colorScheme.primary)

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
                    enabled = report.state != ShizukuBridgeState.SCANNING &&
                        report.state != ShizukuBridgeState.CONNECTING,
                    onClick = { bridge.refresh() },
                ) {
                    Text(if (report.state == ShizukuBridgeState.SCANNING) "Escaneando…" else "Escanear Shizuku")
                }
            }

            Text(
                "Segurança: esta versão usa Shizuku somente para leitura/diagnóstico. Nenhum comando de escrita, remount, setprop de áudio ou edição de audio_effects é executado.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
    ShizukuBridgeState.NOT_RUNNING -> "NÃO EXECUTANDO"
    ShizukuBridgeState.PERMISSION_REQUIRED -> "AGUARDANDO AUTORIZAÇÃO"
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
    GameBridgeVerdict.ROUTING_PERMISSION_CANDIDATE -> "SHIZUKU — ROTA BINDER CANDIDATA"
    GameBridgeVerdict.ROOT_SYSTEM_BRIDGE_READY -> "ROOT — SYSTEM BRIDGE PRONTO PARA PROTÓTIPO"
}

private fun uidLabel(uid: Int): String = when (uid) {
    0 -> "0 • ROOT"
    2000 -> "2000 • SHELL/ADB"
    -1 -> "—"
    else -> "$uid • OUTRO"
}

private fun yesNo(value: Boolean): String = if (value) "SIM" else "NÃO"
private fun okNo(value: Boolean): String = if (value) "OK" else "BLOQUEADO/INDISPONÍVEL"
