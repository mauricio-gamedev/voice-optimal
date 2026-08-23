package io.github.astromg01.clearmic.system.shizuku;

import android.content.Context;
import android.os.Process;
import android.system.Os;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Runs inside Shizuku UserService as UID 2000 (ADB shell) or UID 0 (root/Sui).
 * This service is intentionally read-only in alpha09: every accepted probe is a
 * diagnostic command and no command changes audio routing or system files.
 */
@Keep
public final class ShizukuAudioUserService extends IShizukuAudioService.Stub {

    private static final int MAX_OUTPUT_CHARS = 384 * 1024;
    private static final long COMMAND_TIMEOUT_SECONDS = 6L;

    public ShizukuAudioUserService() {
    }

    @Keep
    public ShizukuAudioUserService(Context context) {
    }

    @Override
    public String getIdentity() {
        return "uid=" + Os.getuid() + ";pid=" + Process.myPid();
    }

    @Override
    public String runProbe(String probeKey) {
        final String command;
        if (probeKey == null) return "ERROR: null probe";

        switch (probeKey) {
            case "identity":
                command = "id; printf '\\nSELINUX='; getenforce 2>/dev/null || true; " +
                        "printf '\\nMODEL='; getprop ro.product.model; " +
                        "printf '\\nSDK='; getprop ro.build.version.sdk";
                break;
            case "audio":
                command = "dumpsys audio 2>&1";
                break;
            case "audio_flinger":
                command = "dumpsys media.audio_flinger 2>&1";
                break;
            case "audio_policy":
                command = "dumpsys media.audio_policy 2>&1";
                break;
            case "record_appops":
                command = "dumpsys appops --op RECORD_AUDIO 2>&1";
                break;
            case "audio_configs":
                command = "for d in /vendor/etc /odm/etc /system/etc /product/etc /system_ext/etc; do " +
                        "for f in \\"$d\\"/audio_effects*.xml \\"$d\\"/audio_effects*.conf; do " +
                        "[ -f \\"$f\\" ] || continue; " +
                        "printf '\\n===== %s =====\\n' \\"$f\\"; " +
                        "head -c 131072 \\"$f\\" 2>/dev/null || printf '[unreadable]\\n'; " +
                        "done; done";
                break;
            default:
                return "ERROR: probe not allowed";
        }

        return executeReadOnly(command);
    }

    private String executeReadOnly(String command) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            final java.lang.Process runningProcess = process;
            FutureTask<String> readerTask = new FutureTask<>(() -> {
                StringBuilder output = new StringBuilder(Math.min(MAX_OUTPUT_CHARS, 16 * 1024));
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(runningProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = reader.read(buffer)) >= 0) {
                        if (output.length() < MAX_OUTPUT_CHARS) {
                            int remaining = MAX_OUTPUT_CHARS - output.length();
                            output.append(buffer, 0, Math.min(count, remaining));
                        }
                    }
                }
                return output.toString();
            });

            Thread readerThread = new Thread(readerTask, "ClearMic-ShizukuRead");
            readerThread.setDaemon(true);
            readerThread.start();

            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "ERROR: probe timeout";
            }

            return readerTask.get(1, TimeUnit.SECONDS);
        } catch (Throwable error) {
            if (process != null) process.destroyForcibly();
            return "ERROR: " + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        }
    }
}
