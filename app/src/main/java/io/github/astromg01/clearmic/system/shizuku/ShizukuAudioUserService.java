package io.github.astromg01.clearmic.system.shizuku;

import android.content.Context;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.IBinder;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shizuku UserService running as UID 2000 (ADB shell) or UID 0 (root/Sui).
 *
 * Alpha13 no longer depends on Context#getSystemService(AudioManager) for the game
 * monitor. Shizuku UserService is not a normal Android app process, so the monitor
 * talks directly to IAudioService over Binder and uses dumpsys only as a safety
 * fallback. This keeps the daemon independent from the ClearMic Activity lifecycle.
 */
@Keep
public final class ShizukuAudioUserService extends IShizukuAudioService.Stub {

    private static final int MAX_OUTPUT_CHARS = 384 * 1024;
    private static final long COMMAND_TIMEOUT_SECONDS = 6L;
    private static final long GAME_MONITOR_INTERVAL_MS = 500L;
    private static final String CLEARMIC_PACKAGE = "io.github.astromg01.clearmic";

    private static final Pattern SESSION_PATTERN = Pattern.compile("session[:=](\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UID_PATTERN = Pattern.compile("uid[:=](\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?:pack|pkg)[:=]([^\\s,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_PATTERN = Pattern.compile("(?:source\\s+client=|source:|src:)([A-Z0-9_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SILENCED_PATTERN = Pattern.compile("silenced[:=](true|false)", Pattern.CASE_INSENSITIVE);

    private final Object gameLock = new Object();
    private final Map<Integer, EffectBundle> activeEffectBundles = new HashMap<>();

    private volatile boolean gameBridgeEnabled = false;
    private volatile Thread gameMonitorThread;
    private volatile String gameBridgeStatus = "DISABLED";
    private volatile String lastRecordingSnapshot = "NO SNAPSHOT";
    private volatile String lastExternalTarget = "LAST_EXTERNAL: none seen since enable";
    private volatile String monitorBackend = "MONITOR: not started";

    public ShizukuAudioUserService() {
    }

    @Keep
    public ShizukuAudioUserService(Context context) {
        // Kept for Shizuku v13 constructor compatibility. Alpha13 intentionally does
        // not use this Context for AudioManager because UserService is not a normal app process.
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
            lastExternalTarget = "LAST_EXTERNAL: none seen since enable";
            monitorBackend = "MONITOR: starting Binder monitor";
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
        return gameBridgeStatus + "\n" + monitorBackend + "\n" + lastExternalTarget + "\n" + lastRecordingSnapshot;
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
        List<CaptureTarget> targets = readActiveCaptureTargets();
        Set<Integer> eligibleSessions = new HashSet<>();
        List<String> activeTargets = new ArrayList<>();
        int silencedCount = 0;

        for (CaptureTarget target : targets) {
            if (target.session <= 0) continue;
            if (CLEARMIC_PACKAGE.equals(target.packageName)) continue;
            if (!isEligibleCaptureSource(target.source)) continue;

            if (target.silenced) {
                silencedCount++;
                continue;
            }

            eligibleSessions.add(target.session);
            String targetLabel = target.label();
            activeTargets.add(targetLabel);

            synchronized (gameLock) {
                if (!activeEffectBundles.containsKey(target.session)) {
                    activeEffectBundles.put(
                            target.session,
                            attachSafeNativeEffects(target.session, target.source, targetLabel)
                    );
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

        lastRecordingSnapshot = formatRecordingSnapshot(targets);

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

    private List<CaptureTarget> readActiveCaptureTargets() {
        try {
            List<CaptureTarget> targets = readViaAudioServiceBinder();
            monitorBackend = "MONITOR: IAudioService Binder • records=" + targets.size();
            return targets;
        } catch (Throwable binderError) {
            try {
                List<CaptureTarget> targets = readViaDumpsysFallback();
                monitorBackend = "MONITOR: dumpsys fallback • records=" + targets.size()
                        + " • binder=" + binderError.getClass().getSimpleName();
                return targets;
            } catch (Throwable dumpError) {
                monitorBackend = "MONITOR ERROR: Binder=" + binderError.getClass().getSimpleName()
                        + " dumpsys=" + dumpError.getClass().getSimpleName();
                throw new IllegalStateException(monitorBackend, dumpError);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<CaptureTarget> readViaAudioServiceBinder() throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Method getService = serviceManagerClass.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        IBinder audioBinder = (IBinder) getService.invoke(null, "audio");
        if (audioBinder == null) throw new IllegalStateException("audio Binder unavailable");

        Class<?> stubClass = Class.forName("android.media.IAudioService$Stub");
        Method asInterface = stubClass.getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        Object audioService = asInterface.invoke(null, audioBinder);
        if (audioService == null) throw new IllegalStateException("IAudioService unavailable");

        Class<?> interfaceClass = Class.forName("android.media.IAudioService");
        Method getActive = interfaceClass.getDeclaredMethod("getActiveRecordingConfigurations");
        getActive.setAccessible(true);
        Object raw = getActive.invoke(audioService);

        List<CaptureTarget> result = new ArrayList<>();
        if (!(raw instanceof List<?>)) return result;

        for (Object item : (List<?>) raw) {
            if (item instanceof AudioRecordingConfiguration) {
                result.add(CaptureTarget.fromConfig((AudioRecordingConfiguration) item));
            }
        }
        return result;
    }

    private List<CaptureTarget> readViaDumpsysFallback() {
        String dump = executeReadOnly("dumpsys audio 2>&1");
        if (dump.startsWith("ERROR:")) throw new IllegalStateException(dump);

        List<CaptureTarget> result = new ArrayList<>();
        boolean nextConfigIsActive = false;
        for (String rawLine : dump.split("\\r?\\n")) {
            String line = rawLine.trim();
            String lower = line.toLowerCase(Locale.ROOT);

            if (lower.startsWith("riid ") && lower.contains("active?")) {
                nextConfigIsActive = lower.contains("active? true");
                continue;
            }

            if (nextConfigIsActive && lower.contains("session:")) {
                CaptureTarget target = parseDumpConfigLine(line);
                if (target != null) result.add(target);
                nextConfigIsActive = false;
            }
        }
        return result;
    }

    private CaptureTarget parseDumpConfigLine(String line) {
        int session = matchInt(SESSION_PATTERN, line, -1);
        if (session <= 0) return null;

        int uid = matchInt(UID_PATTERN, line, -1);
        String packageName = matchString(PACKAGE_PATTERN, line, "");
        String sourceToken = matchString(SOURCE_PATTERN, line, "DEFAULT");
        int source = sourceFromName(sourceToken);
        String silencedToken = matchString(SILENCED_PATTERN, line, "false");
        boolean silenced = Boolean.parseBoolean(silencedToken);

        return new CaptureTarget(session, source, uid, packageName, silenced, "");
    }

    private int matchInt(Pattern pattern, String text, int fallback) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return fallback;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String matchString(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? safe(matcher.group(1)) : fallback;
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
                    nsState = "NS=" + (ns.getEnabled() ? "ON" : "OFF")
                            + "/result=" + result + "/control=" + ns.hasControl();
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
                        aecState = "AEC=" + (aec.getEnabled() ? "ON" : "OFF")
                                + "/result=" + result + "/control=" + aec.hasControl();
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
            for (EffectBundle bundle : activeEffectBundles.values()) bundle.release();
            activeEffectBundles.clear();
        }
    }

    private String buildRecordingSnapshot() {
        try {
            return formatRecordingSnapshot(readActiveCaptureTargets());
        } catch (Throwable error) {
            return "RECORDINGS ERROR: " + error.getClass().getSimpleName() + ": " + safe(error.getMessage());
        }
    }

    private String formatRecordingSnapshot(List<CaptureTarget> targets) {
        StringBuilder out = new StringBuilder(monitorBackend)
                .append("\nrecordings=").append(targets == null ? 0 : targets.size());
        if (targets == null) return out.toString();

        for (CaptureTarget target : targets) {
            out.append("\n• pkg=").append(blankToDash(target.packageName))
                    .append(" uid=").append(target.uid)
                    .append(" session=").append(target.session)
                    .append(" source=").append(sourceName(target.source))
                    .append(" silenced=").append(target.silenced);
            if (!target.effects.isEmpty()) out.append(" effects=").append(target.effects);
        }
        return out.toString();
    }

    private static String getClientPackageName(AudioRecordingConfiguration config) {
        try {
            Method method = AudioRecordingConfiguration.class.getDeclaredMethod("getClientPackageName");
            method.setAccessible(true);
            Object value = method.invoke(config);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int getClientUid(AudioRecordingConfiguration config) {
        try {
            Method method = AudioRecordingConfiguration.class.getDeclaredMethod("getClientUid");
            method.setAccessible(true);
            Object value = method.invoke(config);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean safeIsSilenced(AudioRecordingConfiguration config) {
        try {
            return config.isClientSilenced();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String effectNames(AudioRecordingConfiguration config) {
        try {
            List<AudioEffect.Descriptor> effects = config.getEffects();
            if (effects == null || effects.isEmpty()) return "";
            StringBuilder out = new StringBuilder();
            for (AudioEffect.Descriptor descriptor : effects) {
                if (descriptor == null || descriptor.name == null) continue;
                if (out.length() > 0) out.append(',');
                out.append(descriptor.name.replace('\n', ' '));
            }
            return out.toString();
        } catch (Throwable ignored) {
            return "";
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

    private int sourceFromName(String value) {
        String source = safe(value).toUpperCase(Locale.ROOT);
        switch (source) {
            case "MIC": return MediaRecorder.AudioSource.MIC;
            case "VOICE_RECOGNITION": return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            case "VOICE_COMMUNICATION": return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            case "UNPROCESSED": return MediaRecorder.AudioSource.UNPROCESSED;
            case "VOICE_PERFORMANCE": return MediaRecorder.AudioSource.VOICE_PERFORMANCE;
            case "CAMCORDER": return MediaRecorder.AudioSource.CAMCORDER;
            default: return MediaRecorder.AudioSource.DEFAULT;
        }
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

    private static final class CaptureTarget {
        final int session;
        final int source;
        final int uid;
        final String packageName;
        final boolean silenced;
        final String effects;

        CaptureTarget(int session, int source, int uid, String packageName, boolean silenced, String effects) {
            this.session = session;
            this.source = source;
            this.uid = uid;
            this.packageName = packageName == null ? "" : packageName;
            this.silenced = silenced;
            this.effects = effects == null ? "" : effects;
        }

        static CaptureTarget fromConfig(AudioRecordingConfiguration config) {
            return new CaptureTarget(
                    config.getClientAudioSessionId(),
                    config.getClientAudioSource(),
                    getClientUid(config),
                    getClientPackageName(config),
                    safeIsSilenced(config),
                    effectNames(config)
            );
        }

        String label() {
            String owner = packageName.isEmpty() ? "uid=" + uid : packageName;
            return owner + " session=" + session + " src=" + source;
        }
    }

    private static final class EffectBundle {
        private final int session;
        private final String target;
        private NoiseSuppressor ns;
        private AcousticEchoCanceler aec;
        private final String nsState;
        private final String aecState;

        EffectBundle(int session, String target, NoiseSuppressor ns, AcousticEchoCanceler aec,
                     String nsState, String aecState) {
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
