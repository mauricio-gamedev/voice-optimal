package io.github.astromg01.clearmic.system.shizuku;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.system.Os;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Runs inside Shizuku UserService as UID 2000 (ADB shell) or UID 0 (root/Sui).
 *
 * Alpha12 hardens the game-facing bridge: the app waits for its own recorder to
 * stop before enabling this daemon, while this service preserves the last external
 * recording target/effect result so evidence is still visible after returning from a game.
 */
@Keep
public final class ShizukuAudioUserService extends IShizukuAudioService.Stub {

    private static final int MAX_OUTPUT_CHARS = 384 * 1024;
    private static final long COMMAND_TIMEOUT_SECONDS = 6L;
    private static final long GAME_MONITOR_INTERVAL_MS = 500L;
    private static final String CLEARMIC_PACKAGE = "io.github.astromg01.clearmic";

    private final Object gameLock = new Object();
    private final Map<Integer, EffectBundle> activeEffectBundles = new HashMap<>();

    private volatile Context serviceContext;
    private volatile boolean gameBridgeEnabled = false;
    private volatile Thread gameMonitorThread;
    private volatile String gameBridgeStatus = "DISABLED";
    private volatile String lastRecordingSnapshot = "NO SNAPSHOT";
    private volatile String lastExternalTarget = "LAST_EXTERNAL: none seen since enable";

    public ShizukuAudioUserService() {
    }

    @Keep
    public ShizukuAudioUserService(Context context) {
        serviceContext = context;
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
                command = "id; getenforce 2>/dev/null || true; getprop ro.product.model; getprop ro.build.version.sdk";
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
                return "CONFIG_PROBE_DEFERRED_TO_PASSIVE_SCANNER";
            default:
                return "ERROR: probe not allowed";
        }

        return executeReadOnly(command);
    }

    @Override
    public String getActiveRecordingSnapshot() {
        String snapshot = buildRecordingSnapshot();
        lastRecordingSnapshot = snapshot;
        return snapshot;
    }

    @Override
    public String setGameBridgeEnabled(boolean enabled) {
        if (enabled) {
            if (serviceContext == null) {
                gameBridgeStatus = "ERROR: Shizuku UserService Context unavailable";
                return gameBridgeStatus;
            }
            lastExternalTarget = "LAST_EXTERNAL: none seen since enable";
            gameBridgeEnabled = true;
            startGameMonitorIfNeeded();
            gameBridgeStatus = "ENABLED • waiting for an eligible game/voice recording session";
        } else {
            gameBridgeEnabled = false;
            releaseAllEffects();
            gameBridgeStatus = "DISABLED • all ClearMic-owned session effects released";
        }
        return gameBridgeStatus;
    }

    @Override
    public String getGameBridgeStatus() {
        return gameBridgeStatus + "\n" + lastExternalTarget + "\n" + lastRecordingSnapshot;
    }

    private void startGameMonitorIfNeeded() {
        synchronized (gameLock) {
            if (gameMonitorThread != null && gameMonitorThread.isAlive()) return;
            Thread monitor = new Thread(this::runGameMonitor, "ClearMic-GameEffectsMonitor");
            monitor.setDaemon(true);
            gameMonitorThread = monitor;
            monitor.start();
        }
    }

    private void runGameMonitor() {
        while (gameBridgeEnabled) {
            try {
                monitorOnePass();
            } catch (Throwable error) {
                gameBridgeStatus = "ERROR: monitor " + error.getClass().getSimpleName() + ": " + safe(error.getMessage());
            }

            try {
                Thread.sleep(GAME_MONITOR_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        releaseAllEffects();
        synchronized (gameLock) {
            gameMonitorThread = null;
        }
    }

    private void monitorOnePass() {
        List<AudioRecordingConfiguration> configs = getActiveRecordingConfigurations();
        Set<Integer> eligibleSessions = new HashSet<>();
        List<String> activeTargets = new ArrayList<>();
        int silencedCount = 0;

        for (AudioRecordingConfiguration config : configs) {
            int session = config.getClientAudioSessionId();
            int source = config.getClientAudioSource();
            boolean silenced = safeIsSilenced(config);
            String packageName = getClientPackageName(config);
            int clientUid = getClientUid(config);

            if (session <= 0) continue;
            if (CLEARMIC_PACKAGE.equals(packageName)) continue;
            if (!isEligibleCaptureSource(source)) continue;

            if (silenced) {
                silencedCount++;
                continue;
            }

            eligibleSessions.add(session);
            String targetLabel = (packageName == null || packageName.isEmpty() ? "uid=" + clientUid : packageName)
                    + " session=" + session + " src=" + sourceName(source);
            activeTargets.add(targetLabel);

            synchronized (gameLock) {
                if (!activeEffectBundles.containsKey(session)) {
                    activeEffectBundles.put(session, attachSafeNativeEffects(session, source, targetLabel));
                }
            }
        }

        synchronized (gameLock) {
            List<Integer> staleSessions = new ArrayList<>();
            for (Integer session : activeEffectBundles.keySet()) {
                if (!eligibleSessions.contains(session)) staleSessions.add(session);
            }
            for (Integer stale : staleSessions) {
                EffectBundle bundle = activeEffectBundles.remove(stale);
                if (bundle != null) bundle.release();
            }
        }

        lastRecordingSnapshot = formatRecordingSnapshot(configs);

        int attached;
        String bundleSummary;
        synchronized (gameLock) {
            attached = activeEffectBundles.size();
            StringBuilder details = new StringBuilder();
            for (EffectBundle bundle : activeEffectBundles.values()) {
                if (details.length() > 0) details.append(" | ");
                details.append(bundle.summary());
            }
            bundleSummary = details.toString();
        }

        if (!gameBridgeEnabled) return;
        if (activeTargets.isEmpty()) {
            gameBridgeStatus = "ENABLED • no eligible unsilenced mic session"
                    + (silencedCount > 0 ? " • silenced sessions=" + silencedCount : "");
        } else {
            String targetSummary = String.join(" | ", activeTargets)
                    + (bundleSummary.isEmpty() ? "" : " • " + bundleSummary);
            lastExternalTarget = "LAST_EXTERNAL: " + targetSummary;
            gameBridgeStatus = "ACTIVE • targets=" + activeTargets.size()
                    + " • effect sessions=" + attached
                    + " • " + targetSummary;
        }
    }

    private EffectBundle attachSafeNativeEffects(int session, int source, String targetLabel) {
        NoiseSuppressor ns = null;
        AcousticEchoCanceler aec = null;
        String nsState = "NS unavailable";
        String aecState = "AEC skipped";

        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(session);
                if (ns != null) {
                    int result = ns.setEnabled(true);
                    nsState = "NS=" + (ns.getEnabled() ? "ON" : "OFF") + "/result=" + result
                            + "/control=" + ns.hasControl();
                } else {
                    nsState = "NS create=null";
                }
            }
        } catch (Throwable error) {
            nsState = "NS error=" + error.getClass().getSimpleName();
            if (ns != null) {
                try { ns.release(); } catch (Throwable ignored) { }
                ns = null;
            }
        }

        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(session);
                    if (aec != null) {
                        int result = aec.setEnabled(true);
                        aecState = "AEC=" + (aec.getEnabled() ? "ON" : "OFF") + "/result=" + result
                                + "/control=" + aec.hasControl();
                    } else {
                        aecState = "AEC create=null";
                    }
                } else {
                    aecState = "AEC unavailable";
                }
            } catch (Throwable error) {
                aecState = "AEC error=" + error.getClass().getSimpleName();
                if (aec != null) {
                    try { aec.release(); } catch (Throwable ignored) { }
                    aec = null;
                }
            }
        }

        return new EffectBundle(session, targetLabel, ns, aec, nsState, aecState);
    }

    private void releaseAllEffects() {
        synchronized (gameLock) {
            for (EffectBundle bundle : activeEffectBundles.values()) {
                bundle.release();
            }
            activeEffectBundles.clear();
        }
    }

    private List<AudioRecordingConfiguration> getActiveRecordingConfigurations() {
        Context context = serviceContext;
        if (context == null) throw new IllegalStateException("UserService Context unavailable");

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) throw new IllegalStateException("AudioManager unavailable");

        List<AudioRecordingConfiguration> result = audioManager.getActiveRecordingConfigurations();
        return result == null ? new ArrayList<>() : result;
    }

    private String buildRecordingSnapshot() {
        try {
            return formatRecordingSnapshot(getActiveRecordingConfigurations());
        } catch (Throwable error) {
            return "RECORDINGS ERROR: " + error.getClass().getSimpleName() + ": " + safe(error.getMessage());
        }
    }

    private String formatRecordingSnapshot(List<AudioRecordingConfiguration> configs) {
        if (configs == null || configs.isEmpty()) return "recordings=0";

        StringBuilder out = new StringBuilder("recordings=").append(configs.size());
        for (AudioRecordingConfiguration config : configs) {
            int source = config.getClientAudioSource();
            out.append("\n• pkg=").append(blankToDash(getClientPackageName(config)))
                    .append(" uid=").append(getClientUid(config))
                    .append(" session=").append(config.getClientAudioSessionId())
                    .append(" source=").append(sourceName(source))
                    .append(" silenced=").append(safeIsSilenced(config));

            try {
                List<AudioEffect.Descriptor> effects = config.getEffects();
                if (effects != null && !effects.isEmpty()) {
                    out.append(" effects=");
                    for (int i = 0; i < effects.size(); i++) {
                        if (i > 0) out.append(',');
                        AudioEffect.Descriptor descriptor = effects.get(i);
                        String name = descriptor == null ? "?" : descriptor.name;
                        out.append(name == null ? "?" : name.replace('\n', ' '));
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out.toString();
    }

    private String getClientPackageName(AudioRecordingConfiguration config) {
        try {
            Method method = AudioRecordingConfiguration.class.getDeclaredMethod("getClientPackageName");
            method.setAccessible(true);
            Object value = method.invoke(config);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private int getClientUid(AudioRecordingConfiguration config) {
        try {
            Method method = AudioRecordingConfiguration.class.getDeclaredMethod("getClientUid");
            method.setAccessible(true);
            Object value = method.invoke(config);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private boolean safeIsSilenced(AudioRecordingConfiguration config) {
        try {
            return config.isClientSilenced();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isEligibleCaptureSource(int source) {
        return source == MediaRecorder.AudioSource.DEFAULT
                || source == MediaRecorder.AudioSource.MIC
                || source == MediaRecorder.AudioSource.VOICE_RECOGNITION
                || source == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                || source == MediaRecorder.AudioSource.UNPROCESSED
                || source == MediaRecorder.AudioSource.VOICE_PERFORMANCE;
    }

    private String sourceName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.DEFAULT: return "DEFAULT";
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.CAMCORDER: return "CAMCORDER";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.UNPROCESSED: return "UNPROCESSED";
            case MediaRecorder.AudioSource.VOICE_PERFORMANCE: return "VOICE_PERFORMANCE";
            default: return String.format(Locale.ROOT, "SOURCE_%d", source);
        }
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
            return "ERROR: " + error.getClass().getSimpleName() + ": " + safe(error.getMessage());
        }
    }

    private static String blankToDash(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class EffectBundle {
        private final int session;
        private final String target;
        private NoiseSuppressor ns;
        private AcousticEchoCanceler aec;
        private final String nsState;
        private final String aecState;

        EffectBundle(
                int session,
                String target,
                NoiseSuppressor ns,
                AcousticEchoCanceler aec,
                String nsState,
                String aecState
        ) {
            this.session = session;
            this.target = target;
            this.ns = ns;
            this.aec = aec;
            this.nsState = nsState;
            this.aecState = aecState;
        }

        String summary() {
            return "session=" + session + " " + nsState + " " + aecState + " target=" + target;
        }

        void release() {
            if (ns != null) {
                try { ns.release(); } catch (Throwable ignored) { }
                ns = null;
            }
            if (aec != null) {
                try { aec.release(); } catch (Throwable ignored) { }
                aec = null;
            }
        }
    }
}
