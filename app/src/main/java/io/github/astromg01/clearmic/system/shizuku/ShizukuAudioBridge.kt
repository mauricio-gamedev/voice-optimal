package io.github.astromg01.clearmic.system.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class ShizukuBridgeState {
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    CONNECTING,
    SCANNING,
    READY_SHELL,
    READY_ROOT,
    ERROR,
}

enum class GameBridgeVerdict {
    SHIZUKU_NOT_READY,
    DIAGNOSTICS_ONLY,
    ROUTING_PERMISSION_CANDIDATE,
    ROOT_SYSTEM_BRIDGE_READY,
}

data class PrivilegedAudioReport(
    val state: ShizukuBridgeState = ShizukuBridgeState.NOT_RUNNING,
    val serverUid: Int = -1,
    val serverVersion: Int = -1,
    val selinuxContext: String = "unknown",
    val userServiceIdentity: String = "—",
    val modifyAudioRoutingGranted: Boolean = false,
    val modifyDefaultAudioEffectsGranted: Boolean = false,
    val captureAudioOutputGranted: Boolean = false,
    val audioServiceReadable: Boolean = false,
    val audioFlingerReadable: Boolean = false,
    val audioPolicyReadable: Boolean = false,
    val appOpsReadable: Boolean = false,
    val privilegedConfigsReadable: Boolean = false,
    val voiceCommunicationSeen: Boolean = false,
    val preprocessSeen: Boolean = false,
    val nativeEffectHints: List<String> = emptyList(),
    val recordingPackageHints: List<String> = emptyList(),
    val captureActivityHints: List<String> = emptyList(),
    val verdict: GameBridgeVerdict = GameBridgeVerdict.SHIZUKU_NOT_READY,
    val recommendation: String = "Inicie o Shizuku e conceda acesso ao ClearMic.",
    val scanDurationMs: Long = 0L,
    val lastError: String? = null,
)

object ShizukuAudioRuntime {
    private val mutableReport = MutableStateFlow(PrivilegedAudioReport())
    val report: StateFlow<PrivilegedAudioReport> = mutableReport.asStateFlow()

    internal fun publish(report: PrivilegedAudioReport) {
        mutableReport.value = report
    }
}

class ShizukuAudioBridge(
    context: Context,
) {
    companion object {
        private const val PERMISSION_REQUEST_CODE = 4109
        private const val USER_SERVICE_VERSION = 1
        private const val MAX_HINT_LINES = 8
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val scanRunning = AtomicBoolean(false)

    @Volatile
    private var remote: IShizukuAudioService? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, ShizukuAudioUserService::class.java.name)
        )
            .daemon(false)
            .tag("clearmic-audio-bridge")
            .version(USER_SERVICE_VERSION)
            .processNameSuffix("clearmic_shizuku_audio")
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        evaluateBinderState(bindIfAllowed = true)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        ShizukuAudioRuntime.publish(
            PrivilegedAudioReport(
                state = ShizukuBridgeState.NOT_RUNNING,
                recommendation = "O serviço Shizuku parou. Reinicie o Shizuku e abra o ClearMic novamente.",
            )
        )
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            evaluateBinderState(bindIfAllowed = true)
        } else {
            ShizukuAudioRuntime.publish(
                currentBaseReport().copy(
                    state = ShizukuBridgeState.PERMISSION_DENIED,
                    recommendation = "Permissão do Shizuku negada. Autorize o ClearMic no Shizuku para continuar a integração com jogos.",
                )
            )
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IShizukuAudioService.Stub.asInterface(service)
            runFullDiagnostics()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            ShizukuAudioRuntime.publish(
                currentBaseReport().copy(
                    state = if (isBinderAlive()) ShizukuBridgeState.CONNECTING else ShizukuBridgeState.NOT_RUNNING,
                    lastError = "UserService Shizuku desconectado",
                    recommendation = "O bridge privilegiado desconectou. Use 'Escanear Shizuku' para reconectar.",
                )
            )
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        evaluateBinderState(bindIfAllowed = true)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
        if (isBinderAlive()) {
            runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, false) }
        }
        remote = null
        scope.cancel()
    }

    fun requestPermission() {
        if (!isBinderAlive()) {
            ShizukuAudioRuntime.publish(
                PrivilegedAudioReport(
                    state = ShizukuBridgeState.NOT_RUNNING,
                    recommendation = "Shizuku não está rodando. Inicie-o por Depuração sem fio/ADB ou root e tente novamente.",
                )
            )
            return
        }

        val granted = runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (granted) {
            bindUserService()
            return
        }

        val cannotAskAgain = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        if (cannotAskAgain) {
            ShizukuAudioRuntime.publish(
                currentBaseReport().copy(
                    state = ShizukuBridgeState.PERMISSION_DENIED,
                    recommendation = "A permissão foi negada permanentemente. Abra o Shizuku e autorize o ClearMic manualmente.",
                )
            )
            return
        }

        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { error -> publishError("Falha ao solicitar permissão Shizuku", error) }
    }

    fun refresh() {
        evaluateBinderState(bindIfAllowed = true, forceScan = true)
    }

    private fun evaluateBinderState(bindIfAllowed: Boolean, forceScan: Boolean = false) {
        if (!isBinderAlive()) {
            ShizukuAudioRuntime.publish(
                PrivilegedAudioReport(
                    state = ShizukuBridgeState.NOT_RUNNING,
                    recommendation = "Shizuku não detectado. Inicie o Shizuku; no Android 11+ dá para usar Depuração sem fio sem PC.",
                )
            )
            return
        }

        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            ShizukuAudioRuntime.publish(
                currentBaseReport().copy(
                    state = ShizukuBridgeState.ERROR,
                    lastError = "Shizuku API anterior à v11 não suportada",
                    recommendation = "Atualize o Shizuku para uma versão atual.",
                )
            )
            return
        }

        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!permissionGranted) {
            ShizukuAudioRuntime.publish(
                currentBaseReport().copy(
                    state = ShizukuBridgeState.PERMISSION_REQUIRED,
                    recommendation = "Shizuku está ativo. Toque em 'Autorizar Shizuku' para liberar o diagnóstico privilegiado.",
                )
            )
            return
        }

        if (forceScan && remote != null) {
            runFullDiagnostics()
        } else if (bindIfAllowed && remote == null) {
            bindUserService()
        } else if (remote != null) {
            runFullDiagnostics()
        }
    }

    private fun bindUserService() {
        if (!isBinderAlive()) return
        ShizukuAudioRuntime.publish(
            currentBaseReport().copy(
                state = ShizukuBridgeState.CONNECTING,
                recommendation = "Conectando ao UserService privilegiado…",
            )
        )
        runCatching { Shizuku.bindUserService(serviceArgs, serviceConnection) }
            .onFailure { error -> publishError("Falha ao iniciar UserService Shizuku", error) }
    }

    private fun runFullDiagnostics() {
        val service = remote ?: return
        if (!scanRunning.compareAndSet(false, true)) return

        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            ShizukuAudioRuntime.publish(
                currentBaseReport().copy(
                    state = ShizukuBridgeState.SCANNING,
                    recommendation = "Lendo AudioService, AudioFlinger, AudioPolicy e cadeias de efeitos…",
                )
            )

            try {
                val serverUid = safeServerUid()
                val serverVersion = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
                val serverContext = runCatching { Shizuku.getSELinuxContext() }.getOrNull().orEmpty().ifBlank { "unknown" }

                val modifyRouting = remotePermission("android.permission.MODIFY_AUDIO_ROUTING")
                val modifyDefaultEffects = remotePermission("android.permission.MODIFY_DEFAULT_AUDIO_EFFECTS")
                val captureOutput = remotePermission("android.permission.CAPTURE_AUDIO_OUTPUT")

                val identity = service.getIdentity().orEmpty()
                val identityProbe = service.runProbe("identity").orEmpty()
                val audio = service.runProbe("audio").orEmpty()
                val flinger = service.runProbe("audio_flinger").orEmpty()
                val policy = service.runProbe("audio_policy").orEmpty()
                val appOps = service.runProbe("record_appops").orEmpty()
                val configs = service.runProbe("audio_configs").orEmpty()

                val combined = listOf(audio, flinger, policy, configs).joinToString("\n").lowercase(Locale.ROOT)
                val voiceCommunicationSeen =
                    "voice_communication" in combined ||
                        "voice communication" in combined ||
                        "source: 7" in combined
                val preprocessSeen = "preprocess" in combined || "pre_processing" in combined

                val nativeEffects = buildList {
                    if (containsEffect(combined, "aec", "acoustic_echo")) add("AEC")
                    if (containsEffect(combined, "ns", "noise_suppress")) add("NS")
                    if (containsEffect(combined, "agc", "automatic_gain")) add("AGC")
                }

                val packageHints = extractPackageHints(audio + "\n" + appOps)
                val captureHints = extractCaptureHints(audio + "\n" + flinger + "\n" + policy)

                val rootMode = serverUid == 0 || identity.contains("uid=0") || identityProbe.contains("uid=0")
                val verdict = when {
                    rootMode -> GameBridgeVerdict.ROOT_SYSTEM_BRIDGE_READY
                    modifyRouting || modifyDefaultEffects -> GameBridgeVerdict.ROUTING_PERMISSION_CANDIDATE
                    else -> GameBridgeVerdict.DIAGNOSTICS_ONLY
                }

                val recommendation = when (verdict) {
                    GameBridgeVerdict.ROOT_SYSTEM_BRIDGE_READY ->
                        "Shizuku está em modo ROOT. O aparelho está pronto para a próxima etapa: bridge system-wide com backup/rollback antes de qualquer alteração."
                    GameBridgeVerdict.ROUTING_PERMISSION_CANDIDATE ->
                        "O Shizuku shell possui pelo menos uma permissão crítica de áudio. Próxima etapa: validar uma rota Binder controlada antes de tocar em arquivos do sistema."
                    GameBridgeVerdict.DIAGNOSTICS_ONLY ->
                        "Shizuku shell funciona para diagnóstico, mas não recebeu permissão suficiente para roteamento universal. Ainda assim ele consegue mapear quem usa o microfone e a cadeia ativa; para bridge total será necessário root/Sui ou integração de sistema."
                    GameBridgeVerdict.SHIZUKU_NOT_READY ->
                        "Inicie e autorize o Shizuku."
                }

                ShizukuAudioRuntime.publish(
                    PrivilegedAudioReport(
                        state = if (rootMode) ShizukuBridgeState.READY_ROOT else ShizukuBridgeState.READY_SHELL,
                        serverUid = serverUid,
                        serverVersion = serverVersion,
                        selinuxContext = serverContext,
                        userServiceIdentity = (identity + " " + identityProbe.lineSequence().firstOrNull().orEmpty()).trim(),
                        modifyAudioRoutingGranted = modifyRouting,
                        modifyDefaultAudioEffectsGranted = modifyDefaultEffects,
                        captureAudioOutputGranted = captureOutput,
                        audioServiceReadable = probeReadable(audio),
                        audioFlingerReadable = probeReadable(flinger),
                        audioPolicyReadable = probeReadable(policy),
                        appOpsReadable = probeReadable(appOps),
                        privilegedConfigsReadable = probeReadable(configs) && "=====" in configs,
                        voiceCommunicationSeen = voiceCommunicationSeen,
                        preprocessSeen = preprocessSeen,
                        nativeEffectHints = nativeEffects,
                        recordingPackageHints = packageHints,
                        captureActivityHints = captureHints,
                        verdict = verdict,
                        recommendation = recommendation,
                        scanDurationMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                )
            } catch (error: Throwable) {
                publishError("Falha no diagnóstico privilegiado", error)
            } finally {
                scanRunning.set(false)
            }
        }
    }

    private fun remotePermission(permission: String): Boolean = runCatching {
        Shizuku.checkRemotePermission(permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun safeServerUid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    private fun currentBaseReport(): PrivilegedAudioReport {
        val current = ShizukuAudioRuntime.report.value
        if (!isBinderAlive()) return current
        return current.copy(
            serverUid = safeServerUid(),
            serverVersion = runCatching { Shizuku.getVersion() }.getOrDefault(current.serverVersion),
            selinuxContext = runCatching { Shizuku.getSELinuxContext() }.getOrNull()
                .orEmpty().ifBlank { current.selinuxContext },
        )
    }

    private fun publishError(prefix: String, error: Throwable) {
        ShizukuAudioRuntime.publish(
            currentBaseReport().copy(
                state = ShizukuBridgeState.ERROR,
                lastError = "$prefix: ${error.javaClass.simpleName}: ${error.message ?: "sem detalhe"}",
                recommendation = "O ClearMic manteve o áudio intacto. Tente reconectar o Shizuku; nenhuma alteração de sistema foi aplicada.",
            )
        )
    }

    private fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun probeReadable(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase(Locale.ROOT)
        return !lower.startsWith("error:") &&
            "permission denial" !in lower &&
            "permission denied" !in lower &&
            "can't find service" !in lower
    }

    private fun containsEffect(text: String, vararg keys: String): Boolean =
        keys.any { key -> key in text }

    private fun extractPackageHints(text: String): List<String> {
        val packageRegex = Regex("\\b[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+){2,}\\b")
        val interestingLines = text.lineSequence().filter { line ->
            val lower = line.lowercase(Locale.ROOT)
            "record" in lower || "capture" in lower || "package" in lower || "active" in lower
        }
        return interestingLines
            .flatMap { packageRegex.findAll(it).map { match -> match.value } }
            .filterNot { it.startsWith("android.media.") || it.startsWith("android.permission.") }
            .distinct()
            .take(MAX_HINT_LINES)
            .toList()
    }

    private fun extractCaptureHints(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                val lower = line.lowercase(Locale.ROOT)
                line.length in 4..220 &&
                    ("record" in lower || "capture" in lower || "voice_communication" in lower || "silenced" in lower)
            }
            .distinct()
            .take(MAX_HINT_LINES)
            .toList()
}
