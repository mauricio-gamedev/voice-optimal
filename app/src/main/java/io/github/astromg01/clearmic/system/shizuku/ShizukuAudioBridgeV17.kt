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
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Alpha17 bridge: persistent profile, UserService v8 and compact protection-first diagnostics. */
class ShizukuAudioBridgeV17(context: Context) {
    companion object {
        private const val PERMISSION_REQUEST_CODE = 4117
        private const val USER_SERVICE_VERSION = 8
        private const val PREFS = "clearmic_game_enhance"
        private const val KEY_PROFILE = "profile"
        private const val MAX_HINTS = 6
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val scanRunning = AtomicBoolean(false)

    @Volatile
    private var remote: IShizukuAudioService? = null

    private val savedProfile: String
        get() = normalizeProfile(prefs.getString(KEY_PROFILE, "BALANCED").orEmpty())

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, ShizukuAudioUserService::class.java.name)
        )
            .daemon(true)
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
            ShizukuAudioRuntime.report.value.copy(
                state = ShizukuBridgeState.NOT_RUNNING,
                gameBridgeEnabled = false,
                gameEnhancementProfile = savedProfile,
                recommendation = "Shizuku parou. Inicie o Shizuku novamente para reativar a proteção de jogos.",
            )
        )
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            bindUserService()
        } else {
            publish(
                state = ShizukuBridgeState.PERMISSION_DENIED,
                recommendation = "Permissão negada. Autorize o ClearMic no Shizuku para usar Game Voice Protection.",
            )
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IShizukuAudioService.Stub.asInterface(service)
            scope.launch {
                val status = runCatching { remote?.getGameBridgeStatus().orEmpty() }.getOrDefault("")
                val running = status.startsWith("ENABLED") || status.startsWith("ACTIVE")
                if (!running) {
                    runCatching { remote?.setGameEnhancementProfile(savedProfile) }
                }
                runDiagnostics()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            publish(
                state = if (binderAlive()) ShizukuBridgeState.CONNECTING else ShizukuBridgeState.NOT_RUNNING,
                recommendation = "Reconectando ao daemon de áudio do Shizuku…",
            )
        }
    }

    init {
        ShizukuAudioRuntime.publish(
            ShizukuAudioRuntime.report.value.copy(gameEnhancementProfile = savedProfile)
        )
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        evaluateBinderState(bindIfAllowed = true)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        if (binderAlive()) runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, false) }
        remote = null
        scope.cancel()
    }

    fun requestPermission() {
        if (!binderAlive()) {
            publish(
                state = ShizukuBridgeState.NOT_RUNNING,
                recommendation = "Inicie o Shizuku por Depuração sem fio/ADB ou root.",
            )
            return
        }
        if (permissionGranted()) {
            bindUserService()
            return
        }
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { publishError("Falha ao pedir permissão Shizuku", it) }
    }

    fun refresh() {
        evaluateBinderState(bindIfAllowed = true, forceScan = true)
    }

    fun setGameEnhancementProfile(profile: String) {
        val service = remote ?: return
        val normalized = normalizeProfile(profile)
        scope.launch {
            runCatching {
                val result = service.setGameEnhancementProfile(normalized).orEmpty()
                if (!result.startsWith("PROFILE_LOCKED") && !result.startsWith("ERROR")) {
                    prefs.edit().putString(KEY_PROFILE, normalized).apply()
                }
                val current = ShizukuAudioRuntime.report.value
                ShizukuAudioRuntime.publish(
                    current.copy(
                        gameEnhancementProfile = if (result.startsWith("PROFILE_LOCKED")) current.gameEnhancementProfile else normalized,
                        gameBridgeStatus = result.ifBlank { "PROFILE: $normalized" },
                        lastError = if (result.startsWith("ERROR")) result else null,
                    )
                )
            }.onFailure { publishError("Falha ao alterar perfil", it) }
        }
    }

    fun setGameBridgeEnabled(enabled: Boolean) {
        val service = remote
        if (service == null) {
            evaluateBinderState(bindIfAllowed = true)
            return
        }
        scope.launch {
            runCatching {
                if (enabled) service.setGameEnhancementProfile(savedProfile)
                val status = service.setGameBridgeEnabled(enabled).orEmpty()
                val snapshot = service.getActiveRecordingSnapshot().orEmpty()
                val actualEnabled = enabled && !status.startsWith("ERROR")
                ShizukuAudioRuntime.publish(
                    ShizukuAudioRuntime.report.value.copy(
                        gameBridgeEnabled = actualEnabled,
                        gameEnhancementProfile = profileFromStatus(status, savedProfile),
                        gameBridgeStatus = status.ifBlank { if (actualEnabled) "ENABLED" else "DISABLED" },
                        activeRecordingSnapshot = snapshot.ifBlank { "—" },
                        lastError = if (status.startsWith("ERROR")) status else null,
                    )
                )
            }.onFailure { publishError("Falha ao alterar Game Voice Protection", it) }
        }
    }

    fun refreshGameBridgeStatus() {
        val service = remote ?: return
        scope.launch {
            runCatching {
                val status = service.getGameBridgeStatus().orEmpty()
                val snapshot = service.getActiveRecordingSnapshot().orEmpty()
                val enabled = status.startsWith("ENABLED") || status.startsWith("ACTIVE")
                val profile = profileFromStatus(status, savedProfile)
                if (!enabled) prefs.edit().putString(KEY_PROFILE, profile).apply()
                ShizukuAudioRuntime.publish(
                    ShizukuAudioRuntime.report.value.copy(
                        gameBridgeEnabled = enabled,
                        gameEnhancementProfile = profile,
                        gameBridgeStatus = status.ifBlank { "—" },
                        activeRecordingSnapshot = snapshot.ifBlank { "—" },
                    )
                )
            }.onFailure { publishError("Falha ao atualizar proteção", it) }
        }
    }

    private fun evaluateBinderState(bindIfAllowed: Boolean, forceScan: Boolean = false) {
        if (!binderAlive()) {
            publish(
                state = ShizukuBridgeState.NOT_RUNNING,
                recommendation = "Shizuku não detectado. Inicie-o para usar a proteção de voz nos jogos.",
            )
            return
        }
        if (!permissionGranted()) {
            publish(
                state = ShizukuBridgeState.PERMISSION_REQUIRED,
                recommendation = "Shizuku está ativo. Autorize o ClearMic para continuar.",
            )
            return
        }
        when {
            remote == null && bindIfAllowed -> bindUserService()
            remote != null && forceScan -> scope.launch { runDiagnostics() }
            remote != null -> scope.launch { runDiagnostics() }
        }
    }

    private fun bindUserService() {
        if (!binderAlive()) return
        publish(
            state = ShizukuBridgeState.CONNECTING,
            recommendation = "Conectando ao daemon de proteção de voz…",
        )
        runCatching { Shizuku.bindUserService(serviceArgs, serviceConnection) }
            .onFailure { publishError("Falha ao iniciar daemon Shizuku", it) }
    }

    private suspend fun runDiagnostics() {
        val service = remote ?: return
        if (!scanRunning.compareAndSet(false, true)) return
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val serverUid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
            val serverVersion = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
            val selinux = runCatching { Shizuku.getSELinuxContext() }.getOrNull().orEmpty().ifBlank { "unknown" }
            val modifyRouting = remotePermission("android.permission.MODIFY_AUDIO_ROUTING")
            val modifyDefaults = remotePermission("android.permission.MODIFY_DEFAULT_AUDIO_EFFECTS")
            val captureOutput = remotePermission("android.permission.CAPTURE_AUDIO_OUTPUT")

            val identity = runCatching { service.getIdentity().orEmpty() }.getOrDefault("")
            val audio = runCatching { service.runProbe("audio").orEmpty() }.getOrDefault("")
            val flinger = runCatching { service.runProbe("audio_flinger").orEmpty() }.getOrDefault("")
            val policy = runCatching { service.runProbe("audio_policy").orEmpty() }.getOrDefault("")
            val appOps = runCatching { service.runProbe("record_appops").orEmpty() }.getOrDefault("")
            val snapshot = runCatching { service.getActiveRecordingSnapshot().orEmpty() }.getOrDefault("")
            val gameStatus = runCatching { service.getGameBridgeStatus().orEmpty() }.getOrDefault("DISABLED")
            val gameEnabled = gameStatus.startsWith("ENABLED") || gameStatus.startsWith("ACTIVE")
            val profile = profileFromStatus(gameStatus, savedProfile)
            if (!gameEnabled) prefs.edit().putString(KEY_PROFILE, profile).apply()

            val combined = listOf(audio, flinger, policy, snapshot).joinToString("\n").lowercase(Locale.ROOT)
            val effects = buildList {
                if (containsAny(combined, "noise suppress", "noise_suppress", "ns")) add("NS")
                if (containsAny(combined, "acoustic echo", "acoustic_echo", "aec")) add("AEC")
                if (containsAny(combined, "automatic gain", "automatic_gain", "agc")) add("AGC")
            }
            val root = serverUid == 0 || identity.contains("uid=0")
            val verdict = when {
                root -> GameBridgeVerdict.ROOT_SYSTEM_BRIDGE_READY
                modifyDefaults || modifyRouting -> GameBridgeVerdict.ROUTING_PERMISSION_CANDIDATE
                else -> GameBridgeVerdict.DIAGNOSTICS_ONLY
            }

            ShizukuAudioRuntime.publish(
                PrivilegedAudioReport(
                    state = if (root) ShizukuBridgeState.READY_ROOT else ShizukuBridgeState.READY_SHELL,
                    serverUid = serverUid,
                    serverVersion = serverVersion,
                    selinuxContext = selinux,
                    userServiceIdentity = identity,
                    modifyAudioRoutingGranted = modifyRouting,
                    modifyDefaultAudioEffectsGranted = modifyDefaults,
                    captureAudioOutputGranted = captureOutput,
                    audioServiceReadable = readable(audio),
                    audioFlingerReadable = readable(flinger),
                    audioPolicyReadable = readable(policy),
                    appOpsReadable = readable(appOps),
                    voiceCommunicationSeen = containsAny(combined, "voice_communication", "voice communication", "source: 7"),
                    preprocessSeen = containsAny(combined, "preprocess", "pre_processing"),
                    nativeEffectHints = effects,
                    recordingPackageHints = packageHints(audio + "\n" + appOps + "\n" + snapshot),
                    captureActivityHints = emptyList(),
                    gameBridgeEnabled = gameEnabled,
                    gameEnhancementProfile = profile,
                    gameBridgeStatus = gameStatus,
                    activeRecordingSnapshot = snapshot.ifBlank { "—" },
                    verdict = verdict,
                    recommendation = when (verdict) {
                        GameBridgeVerdict.ROUTING_PERMISSION_CANDIDATE,
                        GameBridgeVerdict.ROOT_SYSTEM_BRIDGE_READY -> "Game Voice Protection pronta. O status principal mostra apenas efeitos realmente verificados na sessão do jogo."
                        GameBridgeVerdict.DIAGNOSTICS_ONLY -> "Shizuku conectado, mas faltam permissões para registrar efeitos de entrada."
                        GameBridgeVerdict.SHIZUKU_NOT_READY -> "Inicie o Shizuku."
                    },
                    scanDurationMs = SystemClock.elapsedRealtime() - startedAt,
                )
            )
        } catch (error: Throwable) {
            publishError("Falha no diagnóstico privilegiado", error)
        } finally {
            scanRunning.set(false)
        }
    }

    private fun permissionGranted(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun remotePermission(permission: String): Boolean = runCatching {
        Shizuku.checkRemotePermission(permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun publish(state: ShizukuBridgeState, recommendation: String) {
        ShizukuAudioRuntime.publish(
            ShizukuAudioRuntime.report.value.copy(
                state = state,
                gameEnhancementProfile = savedProfile,
                recommendation = recommendation,
            )
        )
    }

    private fun publishError(prefix: String, error: Throwable) {
        ShizukuAudioRuntime.publish(
            ShizukuAudioRuntime.report.value.copy(
                state = ShizukuBridgeState.ERROR,
                lastError = "$prefix: ${error.javaClass.simpleName}: ${error.message ?: "sem detalhe"}",
            )
        )
    }

    private fun normalizeProfile(value: String): String = when (value.trim().uppercase(Locale.ROOT)) {
        "LIGHT" -> "LIGHT"
        "STRONG" -> "STRONG"
        else -> "BALANCED"
    }

    private fun profileFromStatus(status: String, fallback: String): String {
        val index = status.indexOf("profile=")
        if (index < 0) return normalizeProfile(fallback)
        val value = status.substring(index + 8).takeWhile { it.isLetter() || it == '_' }
        return normalizeProfile(value)
    }

    private fun readable(value: String): Boolean {
        if (value.isBlank()) return false
        val lower = value.lowercase(Locale.ROOT)
        return !lower.startsWith("error:") && "permission denial" !in lower && "permission denied" !in lower
    }

    private fun containsAny(text: String, vararg keys: String): Boolean = keys.any { it in text }

    private fun packageHints(text: String): List<String> {
        val regex = Regex("\\b[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+){2,}\\b")
        return text.lineSequence()
            .filter { line ->
                val lower = line.lowercase(Locale.ROOT)
                "record" in lower || "capture" in lower || "pkg=" in lower || "package" in lower
            }
            .flatMap { regex.findAll(it).map { match -> match.value } }
            .filterNot { it.startsWith("android.media.") || it.startsWith("android.permission.") }
            .distinct()
            .take(MAX_HINTS)
            .toList()
    }
}
