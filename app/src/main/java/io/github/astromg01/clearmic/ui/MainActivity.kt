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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.astromg01.clearmic.BuildConfig
import io.github.astromg01.clearmic.audio.AudioRuntime
import io.github.astromg01.clearmic.audio.EngineState
import io.github.astromg01.clearmic.service.BackgroundRuntime
import io.github.astromg01.clearmic.service.GameMicService
import io.github.astromg01.clearmic.ui.theme.ClearMicTheme
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

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                startEngine()
            }
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
                    "Milestone 3 • native audio + DSP • ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Motor de áudio", style = MaterialTheme.typography.titleMedium)
                        Text("Estado: ${state.name}")
                        Text("Backend: ${stats.engineBackend}")
                        Text("DSP: ${stats.dspBackend}")
                        Text("Nível: %.1f dBFS".format(stats.rmsDb))
                        LinearProgressIndicator(
                            progress = { stats.peak },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Voz detectada: ${stats.voiceProbability.times(100f).roundToInt()}%")
                        Text("Piso de ruído: %.1f dBFS".format(stats.noiseFloorDb))
                        Text("Frames capturados: ${stats.capturedFrames}")
                        Text("XRuns: ${stats.xrunCount}")

                        if (stats.capturedFrames > 48_000L && stats.rmsDb <= -119.5f) {
                            Text(
                                "Diagnóstico: o stream está entregando frames, mas eles estão silenciosos. Vamos investigar rota/fonte do microfone.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Text("Noise Suppressor: ${if (stats.noiseSuppressorEnabled) "ON" else "OFF"}")
                        Text("Echo Canceler: ${if (stats.echoCancelerEnabled) "ON" else "OFF"}")
                        Text("AGC Android: ${if (stats.automaticGainEnabled) "ON" else "OFF"}")
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sobrevivência em segundo plano", style = MaterialTheme.typography.titleMedium)
                        Text("Modo persistente: ${if (background.desiredRunning) "ATIVO" else "INATIVO"}")
                        Text("Recuperações do serviço: ${background.restartCount}")
                        Text("Uptime do serviço: ${background.serviceUptimeMs / 1000}s")
                        Text("Memória PSS: %.1f MB".format(background.memoryPssMb))
                        Text("CPU do processo: %.1f%%".format(background.cpuPercent))
                        Text(
                            "Otimização de bateria: ${if (background.batteryOptimizationActive) "ATIVA" else "IGNORADA"}"
                        )
                        Text("Último evento: ${background.lastEvent}")

                        OutlinedButton(onClick = onOpenBatterySettings) {
                            Text("Ajustar bateria")
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = state == EngineState.IDLE || state == EngineState.ERROR,
                        onClick = { requestAndStart() },
                    ) {
                        Text("Ativar motor")
                    }

                    Button(
                        enabled = state == EngineState.RUNNING || state == EngineState.ERROR,
                        onClick = onStop,
                    ) {
                        Text("Desativar")
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Camada 2 agora tenta AAudio/C++ primeiro e mantém AudioRecord como fallback. A Camada 3 inicia com DSP adaptativo nativo de baixo custo; WebRTC APM/RNNoise entram após medirmos CPU, xruns e captura real neste núcleo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
