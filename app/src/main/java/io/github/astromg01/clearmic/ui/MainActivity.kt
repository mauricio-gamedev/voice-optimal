package io.github.astromg01.clearmic.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.core.content.ContextCompat
import io.github.astromg01.clearmic.BuildConfig
import io.github.astromg01.clearmic.audio.AudioRuntime
import io.github.astromg01.clearmic.audio.EngineState
import io.github.astromg01.clearmic.service.BackgroundRuntime
import io.github.astromg01.clearmic.service.GameMicService
import io.github.astromg01.clearmic.system.BridgeReadiness
import io.github.astromg01.clearmic.system.SystemCapabilityReport
import io.github.astromg01.clearmic.system.SystemCapabilityScanner
import io.github.astromg01.clearmic.system.shizuku.ShizukuAudioRuntime
import io.github.astromg01.clearmic.ui.theme.ClearMicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClearMicTheme {
                ClearMicScreen(
                    onStop = { stopEngine() },
                    onOpenBatterySettings = { openBatterySettings() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestForegroundRecovery()
    }

    private fun requestForegroundRecovery() {
        if (AudioRuntime.state.value != EngineState.RUNNING) return
        runCatching {
            startService(
                Intent(this, GameMicService::class.java)
                    .setAction(GameMicService.ACTION_CLIENT_VISIBLE)
            )
        }
    }

    private fun startEngine() {
        val intent = Intent(this, GameMicService::class.java).setAction(GameMicService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopEngine() {
        val intent = Intent(this, GameMicService::class.java).setAction(GameMicService.ACTION_STOP)
        startService(intent)
    }

    private fun openBatterySettings() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.isSuccess

        if (!opened) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    @Composable
    private fun ClearMicScreen(
        onStop: () -> Unit,
        onOpenBatterySettings: () -> Unit,
    ) {
        val state by AudioRuntime.state.collectAsState()
        val stats by AudioRuntime.stats.collectAsState()
        val background by BackgroundRuntime.stats.collectAsState()
        val shizukuReport by ShizukuAudioRuntime.report.collectAsState()
        val context = LocalContext.current.applicationContext
        val systemScanner = remember(context) { SystemCapabilityScanner(context) }
        val scanScope = rememberCoroutineScope()
        var systemReport by remember { mutableStateOf<SystemCapabilityReport?>(null) }
        var systemScanRunning by remember { mutableStateOf(false) }

        fun runSystemScan() {
            if (systemScanRunning) return
            systemScanRunning = true
            scanScope.launch {
                systemReport = withContext(Dispatchers.IO) { systemScanner.scan() }
                systemScanRunning = false
            }
        }

        LaunchedEffect(systemScanner) {
            systemScanRunning = true
            systemReport = withContext(Dispatchers.IO) { systemScanner.scan() }
            systemScanRunning = false
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true && !shizukuReport.gameBridgeEnabled) startEngine()
        }

        fun requestAndStart() {
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
            permissionLauncher.launch(permissions)
        }

        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("ClearMic", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Milestone 4.2 • Game session bridge • ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Motor de áudio", style = MaterialTheme.typography.titleMedium)
                        Text("Estado: ${state.name}")
                        Text("Backend: ${stats.engineBackend}")
                        Text("DSP: ${stats.dspBackend}")
                        Text("Saúde da captura: ${stats.captureHealth}")
                        Text("Recuperações da captura: ${stats.captureRecoveryCount}")

                        if (shizukuReport.gameBridgeEnabled) {
                            Text(
                                "Game Bridge ativo: a captura local deve permanecer desligada para o jogo ser o dono do microfone.",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        stats.fallbackReason?.let { reason ->
                            Text("Proteção automática: $reason", color = MaterialTheme.colorScheme.primary)
                        }

                        Text("Nível: %.1f dBFS".format(stats.rmsDb))
                        LinearProgressIndicator(
                            progress = { stats.peak },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Voz detectada: ${stats.voiceProbability.times(100f).roundToInt()}%")
                        Text("Piso de ruído: %.1f dBFS".format(stats.noiseFloorDb))
                        Text("Frames capturados: ${stats.capturedFrames}")
                        Text("XRuns: ${stats.xrunCount}")

                        if (stats.captureHealth == "SILENCED_BY_SYSTEM") {
                            Text(
                                "O Android está entregando silêncio temporariamente, normalmente porque outro app ganhou prioridade do microfone. O ClearMic aguarda a rota voltar.",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        if (stats.captureHealth == "STALLED" || stats.captureHealth == "RECOVERING") {
                            Text(
                                "A sessão de entrada congelou e está sendo reconstruída automaticamente.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Text("Noise Suppressor Android: ${if (stats.noiseSuppressorEnabled) "ON" else "OFF"}")
                        Text("Echo Canceler Android: ${if (stats.echoCancelerEnabled) "ON" else "OFF"}")
                        Text("AGC Android: ${if (stats.automaticGainEnabled) "ON" else "OFF"}")
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Milestone 4 — Scanner do sistema", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Scanner passivo. Ele continua disponível como fallback mesmo sem Shizuku.",
                            style = MaterialTheme.typography.bodySmall,
                        )

                        if (systemScanRunning && systemReport == null) {
                            Text("Escaneando capacidades do sistema…", color = MaterialTheme.colorScheme.primary)
                        }

                        systemReport?.let { report ->
                            Text("Dispositivo: ${report.manufacturer} ${report.model}")
                            Text("Android/API: ${report.sdkInt} • ABI: ${report.abi}")
                            Text("SELinux: ${report.selinuxState}")
                            Text("Indício passivo de root: ${if (report.rootCandidate) "SIM" else "NÃO"}")
                            Text("SU: ${report.suCommandPath ?: "não localizado"}")
                            Text("Magisk: ${report.magiskCommandPath ?: "não localizado"}")
                            Text("KernelSU/ksud: ${report.kernelSuCommandPath ?: "não localizado"}")
                            Text("Configs de efeitos legíveis: ${report.readableAudioConfigCount}")
                            Text(
                                "Preprocess voice_communication: ${if (report.hasVoiceCommunicationPreprocess) "ENCONTRADO" else "NÃO ENCONTRADO"}"
                            )
                            Text(
                                "Efeitos nativos: NS ${onOff(report.nativeNoiseSuppressorAvailable)} • " +
                                    "AEC ${onOff(report.nativeEchoCancelerAvailable)} • " +
                                    "AGC ${onOff(report.nativeAgcAvailable)}"
                            )
                            Text("Rota passiva: ${bridgeReadinessLabel(report.bridgeReadiness)}")

                            report.audioEffectConfigs.take(4).forEach { config ->
                                Text(
                                    "• ${config.path} — ${if (config.readable) "legível" else "bloqueado"}" +
                                        if (config.readable && config.hasVoiceCommunication) " • voice_comm" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        OutlinedButton(
                            enabled = !systemScanRunning,
                            onClick = { runSystemScan() },
                        ) {
                            Text(if (systemScanRunning) "Escaneando…" else "Escanear sistema")
                        }
                    }
                }

                ShizukuIntegrationPanel(onBeforeEnableGameBridge = onStop)

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sobrevivência em segundo plano", style = MaterialTheme.typography.titleMedium)
                        Text("Modo persistente: ${if (background.desiredRunning) "ATIVO" else "INATIVO"}")
                        Text("Recuperações do serviço: ${background.restartCount}")
                        Text("Uptime do serviço: ${background.serviceUptimeMs / 1000}s")
                        Text("Memória PSS: %.1f MB".format(background.memoryPssMb))
                        Text("CPU do processo: %.1f%%".format(background.cpuPercent))
                        Text("Otimização de bateria: ${if (background.batteryOptimizationActive) "ATIVA" else "IGNORADA"}")
                        Text("Último evento: ${background.lastEvent}")

                        OutlinedButton(onClick = onOpenBatterySettings) {
                            Text("Ajustar bateria")
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = (state == EngineState.IDLE || state == EngineState.ERROR) && !shizukuReport.gameBridgeEnabled,
                        onClick = { requestAndStart() },
                    ) { Text("Ativar motor") }

                    Button(
                        enabled = state == EngineState.RUNNING || state == EngineState.ERROR,
                        onClick = onStop,
                    ) { Text("Desativar") }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "O alpha12 corrige o handoff entre o motor local e o Game Bridge: o AAudio precisa parar de verdade antes de o daemon Shizuku assumir o monitoramento das sessões do jogo. Enquanto o Game Bridge estiver ativo, a captura local fica bloqueada para evitar disputa pelo microfone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    private fun onOff(value: Boolean): String = if (value) "ON" else "OFF"

    private fun bridgeReadinessLabel(readiness: BridgeReadiness): String = when (readiness) {
        BridgeReadiness.DIAGNOSTIC_ONLY -> "DIAGNÓSTICO / ROOT NECESSÁRIO"
        BridgeReadiness.ROOT_BRIDGE_CANDIDATE -> "ROOT BRIDGE CANDIDATE"
        BridgeReadiness.SYSTEM_PREPROCESS_CANDIDATE -> "SYSTEM PREPROCESS CANDIDATE"
    }
}
