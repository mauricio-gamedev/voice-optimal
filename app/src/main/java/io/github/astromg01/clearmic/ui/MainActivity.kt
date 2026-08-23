package io.github.astromg01.clearmic.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.astromg01.clearmic.audio.AudioRuntime
import io.github.astromg01.clearmic.audio.EngineState
import io.github.astromg01.clearmic.service.GameMicService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ClearMicScreen(
                    onStart = { requestStartFlow() },
                    onStop = { stopEngine() },
                )
            }
        }
    }

    private var deferredStart: (() -> Unit)? = null

    private fun requestStartFlow() {
        deferredStart = { startEngine() }
        // Actual permission request is owned by Compose below.
    }

    private fun startEngine() {
        val intent = Intent(this, GameMicService::class.java).setAction(GameMicService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopEngine() {
        val intent = Intent(this, GameMicService::class.java).setAction(GameMicService.ACTION_STOP)
        startService(intent)
    }

    @Composable
    private fun ClearMicScreen(onStart: () -> Unit, onStop: () -> Unit) {
        val state by AudioRuntime.state.collectAsState()
        val stats by AudioRuntime.stats.collectAsState()

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
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("ClearMic", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Milestone 1 • motor local de áudio",
                    style = MaterialTheme.typography.labelLarge,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Estado: ${state.name}", style = MaterialTheme.typography.titleMedium)
                        Text("Nível: %.1f dBFS".format(stats.rmsDb))
                        LinearProgressIndicator(
                            progress = { stats.peak },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Noise Suppressor: ${if (stats.noiseSuppressorEnabled) "ON" else "OFF"}")
                        Text("Echo Canceler: ${if (stats.echoCancelerEnabled) "ON" else "OFF"}")
                        Text("AGC: ${if (stats.automaticGainEnabled) "ON" else "OFF"}")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = state == EngineState.IDLE || state == EngineState.ERROR,
                        onClick = {
                            onStart()
                            requestAndStart()
                        },
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
                    "Nesta etapa o ClearMic processa a captura própria para validar latência, efeitos e estabilidade em segundo plano. " +
                        "Ainda não injeta esse áudio no microfone de outros jogos; o bridge system-wide será uma camada separada.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
