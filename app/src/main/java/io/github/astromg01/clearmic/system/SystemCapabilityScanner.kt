package io.github.astromg01.clearmic.system

import android.content.Context
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

data class AudioEffectConfigProbe(
    val path: String,
    val readable: Boolean,
    val hasPreprocess: Boolean = false,
    val hasVoiceCommunication: Boolean = false,
    val hasAec: Boolean = false,
    val hasNoiseSuppression: Boolean = false,
)

enum class BridgeReadiness {
    DIAGNOSTIC_ONLY,
    ROOT_BRIDGE_CANDIDATE,
    SYSTEM_PREPROCESS_CANDIDATE,
}

data class SystemCapabilityReport(
    val sdkInt: Int,
    val abi: String,
    val manufacturer: String,
    val model: String,
    val selinuxState: String,
    val suCommandPath: String?,
    val magiskCommandPath: String?,
    val kernelSuCommandPath: String?,
    val rootCandidate: Boolean,
    val nativeNoiseSuppressorAvailable: Boolean,
    val nativeEchoCancelerAvailable: Boolean,
    val nativeAgcAvailable: Boolean,
    val audioEffectConfigs: List<AudioEffectConfigProbe>,
    val bridgeReadiness: BridgeReadiness,
    val recommendation: String,
) {
    val readableAudioConfigCount: Int
        get() = audioEffectConfigs.count { it.readable }

    val hasVoiceCommunicationPreprocess: Boolean
        get() = audioEffectConfigs.any {
            it.readable && it.hasPreprocess && it.hasVoiceCommunication
        }
}

class SystemCapabilityScanner(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun scan(): SystemCapabilityReport {
        // Keep this phase passive. command -v only resolves binaries; it never invokes su.
        val suPath = findCommand("su") ?: findExistingPath(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
        )
        val magiskPath = findCommand("magisk") ?: findExistingPath(
            "/sbin/magisk",
            "/debug_ramdisk/magisk",
        )
        val kernelSuPath = findCommand("ksud") ?: findExistingPath(
            "/data/adb/ksu/bin/ksud",
            "/data/adb/ksud",
        )

        val rootCandidate =
            suPath != null ||
                magiskPath != null ||
                kernelSuPath != null ||
                Build.TAGS?.contains("test-keys", ignoreCase = true) == true

        val effectConfigs = discoverAudioEffectConfigs()
        val voicePreprocess = effectConfigs.any {
            it.readable && it.hasPreprocess && it.hasVoiceCommunication
        }

        val readiness = when {
            rootCandidate && voicePreprocess -> BridgeReadiness.SYSTEM_PREPROCESS_CANDIDATE
            rootCandidate -> BridgeReadiness.ROOT_BRIDGE_CANDIDATE
            else -> BridgeReadiness.DIAGNOSTIC_ONLY
        }

        val recommendation = when (readiness) {
            BridgeReadiness.SYSTEM_PREPROCESS_CANDIDATE ->
                "Há indícios de root e uma cadeia voice_communication/preprocess legível. Próximo passo: validar acesso root e mapear a cadeia sem alterá-la."

            BridgeReadiness.ROOT_BRIDGE_CANDIDATE ->
                "Há indícios de root, mas a cadeia de preprocessamento não ficou visível para o app comum. Próximo passo: validar root e fazer leitura privilegiada somente para diagnóstico."

            BridgeReadiness.DIAGNOSTIC_ONLY ->
                "Sem indício passivo de root. O ClearMic pode continuar como laboratório/DSP local, mas um bridge universal para jogos exigirá integração privilegiada no aparelho."
        }

        return SystemCapabilityReport(
            sdkInt = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "desconhecida" },
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "desconhecido" },
            model = Build.MODEL.orEmpty().ifBlank { "desconhecido" },
            selinuxState = readSelinuxState(),
            suCommandPath = suPath,
            magiskCommandPath = magiskPath,
            kernelSuCommandPath = kernelSuPath,
            rootCandidate = rootCandidate,
            nativeNoiseSuppressorAvailable = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false),
            nativeEchoCancelerAvailable = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false),
            nativeAgcAvailable = runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false),
            audioEffectConfigs = effectConfigs,
            bridgeReadiness = readiness,
            recommendation = recommendation,
        )
    }

    private fun readSelinuxState(): String {
        val enforceFile = File("/sys/fs/selinux/enforce")
        val value = runCatching {
            if (!enforceFile.canRead()) return@runCatching null
            enforceFile.bufferedReader().use { it.readLine()?.trim() }
        }.getOrNull()

        return when (value) {
            "1" -> "ENFORCING"
            "0" -> "PERMISSIVE"
            else -> "UNKNOWN"
        }
    }

    private fun findCommand(command: String): String? {
        val process = runCatching {
            ProcessBuilder("sh", "-c", "command -v $command 2>/dev/null")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null

        return try {
            if (!process.waitFor(350, TimeUnit.MILLISECONDS)) {
                process.destroy()
                null
            } else {
                process.inputStream.bufferedReader().use { reader ->
                    reader.readLine()?.trim()?.takeIf { it.startsWith("/") }
                }
            }
        } catch (_: Throwable) {
            process.destroy()
            null
        }
    }

    private fun findExistingPath(vararg candidates: String): String? =
        candidates.firstOrNull { path -> runCatching { File(path).exists() }.getOrDefault(false) }

    private fun discoverAudioEffectConfigs(): List<AudioEffectConfigProbe> {
        val candidates = linkedSetOf<String>()
        val directories = listOf(
            "/vendor/etc",
            "/odm/etc",
            "/system/etc",
            "/product/etc",
            "/system_ext/etc",
        )

        directories.forEach { directoryPath ->
            val directory = File(directoryPath)
            runCatching {
                directory.listFiles()
                    ?.asSequence()
                    ?.filter { file ->
                        file.isFile &&
                            file.name.startsWith("audio_effects", ignoreCase = true) &&
                            (file.extension.equals("xml", true) || file.extension.equals("conf", true))
                    }
                    ?.forEach { candidates += it.absolutePath }
            }
        }

        listOf(
            "/vendor/etc/audio_effects.xml",
            "/vendor/etc/audio_effects.conf",
            "/odm/etc/audio_effects.xml",
            "/odm/etc/audio_effects.conf",
            "/system/etc/audio_effects.xml",
            "/system/etc/audio_effects.conf",
            "/product/etc/audio_effects.xml",
            "/system_ext/etc/audio_effects.xml",
        ).forEach { path ->
            if (File(path).exists()) candidates += path
        }

        return candidates
            .map(::probeAudioEffectConfig)
            .sortedWith(compareByDescending<AudioEffectConfigProbe> { it.readable }.thenBy { it.path })
    }

    private fun probeAudioEffectConfig(path: String): AudioEffectConfigProbe {
        val file = File(path)
        if (!file.canRead()) {
            return AudioEffectConfigProbe(path = path, readable = false)
        }

        val text = readLimitedText(file, maxChars = 384 * 1024)?.lowercase()
            ?: return AudioEffectConfigProbe(path = path, readable = false)

        return AudioEffectConfigProbe(
            path = path,
            readable = true,
            hasPreprocess = "preprocess" in text || "pre_processing" in text,
            hasVoiceCommunication =
                "voice_communication" in text || "voice communication" in text,
            hasAec =
                "acoustic_echo_canceler" in text ||
                    "acoustic echo cancel" in text ||
                    "aec" in text,
            hasNoiseSuppression =
                "noise_suppress" in text ||
                    "noise suppress" in text ||
                    "\"ns\"" in text ||
                    "name=\"ns\"" in text,
        )
    }

    private fun readLimitedText(file: File, maxChars: Int): String? = runCatching {
        val output = StringBuilder(minOf(maxChars, 16 * 1024))
        val buffer = CharArray(4096)
        file.bufferedReader().use { reader ->
            while (output.length < maxChars) {
                val remaining = maxChars - output.length
                val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (count <= 0) break
                output.append(buffer, 0, count)
            }
        }
        output.toString()
    }.getOrNull()
}
