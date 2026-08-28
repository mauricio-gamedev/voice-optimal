package io.github.astromg01.clearmic.system.shizuku;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Process;
import android.system.Os;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

/** Shizuku-side audio daemon for source-default protection + alpha20 AI PCM injection. */
@Keep
public final class ShizukuAudioUserService extends IShizukuAudioService.Stub {
    private static final int MAX_OUTPUT_CHARS = 384 * 1024;
    private static final long COMMAND_TIMEOUT_SECONDS = 6L;
    private static final long MONITOR_IDLE_INTERVAL_MS = 1200L;
    private static final long MONITOR_ACTIVE_INTERVAL_MS = 250L;
    private static final String CLEARMIC_PACKAGE = "io.github.astromg01.clearmic";

    private final Object lock = new Object();
    private final Map<Integer, SessionNoiseEffectInstaller.Handle> effects = new HashMap<>();
    private final Map<Integer, Integer> attempts = new HashMap<>();
    private final Set<String> verifiedHistory = new HashSet<>();
    private final Set<String> failedHistory = new HashSet<>();
    private final SourceDefaultNsController sourceDefaults = new SourceDefaultNsController();
    private final Context context;
    private final AiSystemInjectionBridge aiBridge;

    private volatile boolean enabled;
    private volatile Thread monitorThread;
    private volatile String profile = SourceDefaultNsController.PROFILE_BALANCED;
    private volatile String status = "DISABLED";
    private volatile String monitor = "MONITOR: not started";
    private volatile String inventory = "NS_IMPLS: not scanned";
    private volatile String sourceDefaultStatus = "SOURCE_DEFAULT: disabled";
    private volatile String lastExternal = "LAST_EXTERNAL: none seen since enable";
    private volatile String lastSnapshot = "recordings=0";
    private volatile int protectedSessions;
    private volatile int failedSessions;
    private volatile String lastProtectedPackage = "—";
    private volatile String lastVerifiedChain = "—";

    @Keep
    public ShizukuAudioUserService() {
        this.context = null;
        this.aiBridge = new AiSystemInjectionBridge(null);
    }

    @Keep
    public ShizukuAudioUserService(Context context) {
        this.context = context;
        this.aiBridge = new AiSystemInjectionBridge(context);
    }

    @Override
    public String getIdentity() {
        return "uid=" + Os.getuid() + ";pid=" + Process.myPid() + ";alpha20-ai-injector";
    }

    @Override
    public String runProbe(String probeKey) {
        if (probeKey == null) return "ERROR: null probe";
        final String command;
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
        lastSnapshot = readSnapshot();
        return lastSnapshot;
    }

    @Override
    public String setGameEnhancementProfile(String requestedProfile) {
        String normalized = SourceDefaultNsController.normalizeProfile(requestedProfile);
        if (enabled) {
            return "PROFILE_LOCKED: disable Game Enhance before changing profile • current=" + profile;
        }
        profile = normalized;
        sourceDefaults.setProfile(profile);
        aiBridge.setProfile(profile);
        status = "DISABLED • profile=" + profile;
        return "PROFILE: " + profile;
    }

    @Override
    public String setGameBridgeEnabled(boolean value) {
        if (value) {
            inventory = SessionNoiseEffectInstaller.inventory();
            lastExternal = "LAST_EXTERNAL: none seen since enable";
            monitor = "MONITOR: starting IAudioService Binder";
            resetHealth();

            sourceDefaults.setProfile(profile);
            sourceDefaultStatus = sourceDefaults.enable();
            if (!sourceDefaults.hasNoiseSuppressionReady()) {
                sourceDefaults.release();
                sourceDefaultStatus = sourceDefaults.status();
                enabled = false;
                status = "ERROR: no source-default Noise Suppressor could be registered";
                return status;
            }

            aiBridge.setProfile(profile);
            String aiArm = aiBridge.arm();
            enabled = true;
            startMonitor();
            status = "ENABLED • profile=" + profile
                    + " • NS fallback armed • AI System Injector learning target • " + aiArm;
        } else {
            enabled = false;
            aiBridge.disarm();
            sourceDefaults.release();
            sourceDefaultStatus = sourceDefaults.status();
            releaseAll();
            status = "DISABLED • profile=" + profile + " • AI injector and transient effects released";
        }
        return status;
    }

    @Override
    public String getGameBridgeStatus() {
        return status
                + "\n" + protectionSummary()
                + "\n" + aiBridge.status()
                + "\n" + monitor
                + "\n" + sourceDefaultStatus
                + "\n" + inventory
                + "\n" + lastExternal
                + "\n" + lastSnapshot;
    }

    private void startMonitor() {
        synchronized (lock) {
            if (monitorThread != null && monitorThread.isAlive()) return;
            monitorThread = new Thread(this::monitorLoop, "ClearMic-AudioPolicyMonitor");
            monitorThread.setDaemon(true);
            monitorThread.start();
        }
    }

    private void monitorLoop() {
        while (enabled) {
            boolean active = false;
            try {
                active = monitorPass();
            } catch (Throwable error) {
                status = "ERROR: monitor " + describe(error) + " • " + aiBridge.status();
            }
            try {
                Thread.sleep(active ? MONITOR_ACTIVE_INTERVAL_MS : MONITOR_IDLE_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        releaseAll();
        synchronized (lock) { monitorThread = null; }
    }

    private boolean monitorPass() throws Exception {
        List<AudioRecordingSessionMonitor.Target> targets = AudioRecordingSessionMonitor.read();
        long cadence = targets.isEmpty() ? MONITOR_IDLE_INTERVAL_MS : MONITOR_ACTIVE_INTERVAL_MS;
        monitor = "MONITOR: IAudioService Binder • records=" + targets.size() + " • cadence=" + cadence + "ms";
        lastSnapshot = formatSnapshot(targets);

        Set<Integer> eligibleSessions = new HashSet<>();
        List<String> active = new ArrayList<>();
        int silenced = 0;

        for (AudioRecordingSessionMonitor.Target target : targets) {
            if (!eligible(target)) continue;
            if (target.silenced) {
                silenced++;
                continue;
            }

            eligibleSessions.add(target.session);

            // Learn the real app UID from the same Binder recording monitor that already worked
            // for Roblox. The AudioPolicy remains registered for this UID after the session closes,
            // so the next recorder starts directly on the ClearMic PCM injector.
            aiBridge.ensureTarget(target.uid, target.packageName, target.source);

            boolean inheritedNs = hasEffect(target.effects, "noise suppress", "noise_suppress", "ns");
            boolean inheritedAec = hasEffect(target.effects, "acoustic echo", "acoustic_echo", "aec");
            boolean inheritedAgc = hasEffect(target.effects, "automatic gain", "automatic_gain", "agc");
            String verifiedVendor = sourceDefaults.verifiedVendorEffects(target.effects);

            StringBuilder chain = new StringBuilder();
            if (inheritedNs) chain.append("NS");
            if (inheritedAec) appendEffect(chain, "AEC");
            if (inheritedAgc) appendEffect(chain, "AGC");
            if (!verifiedVendor.isEmpty()) appendEffect(chain, "VENDOR");
            if (aiBridge.isRunningFor(target.uid)) appendEffect(chain, "AI_PCM");

            String targetLabel = target.label()
                    + (target.effects.isEmpty() ? "" : " effects=" + target.effects)
                    + (inheritedNs ? " DEFAULT_NS=IN_CHAIN" : "")
                    + (aiBridge.isRunningFor(target.uid) ? " AI_INJECTOR=ACTIVE" : "")
                    + (chain.length() > 0 ? " VERIFIED=" + chain : " VERIFIED=none");
            active.add(targetLabel);

            String key = sessionKey(target);
            if (inheritedNs) {
                if (verifiedHistory.add(key)) protectedSessions++;
                failedHistory.remove(key);
                lastProtectedPackage = target.packageName.isEmpty() ? "uid:" + target.uid : target.packageName;
                lastVerifiedChain = chain.length() == 0 ? "NS" : chain.toString();
            } else {
                synchronized (lock) {
                    SessionNoiseEffectInstaller.Handle current = effects.get(target.session);
                    int count = attempts.containsKey(target.session) ? attempts.get(target.session) : 0;
                    if ((current == null || !current.hasWorkingNs()) && count < 4) {
                        if (current != null) current.release();
                        SessionNoiseEffectInstaller.Handle next =
                                SessionNoiseEffectInstaller.install(target.session, target.source);
                        effects.put(target.session, next);
                        attempts.put(target.session, count + 1);
                    }
                    int currentAttempts = attempts.containsKey(target.session) ? attempts.get(target.session) : 0;
                    SessionNoiseEffectInstaller.Handle handle = effects.get(target.session);
                    if (currentAttempts >= 4 && (handle == null || !handle.hasWorkingNs())) {
                        if (failedHistory.add(key)) failedSessions++;
                    }
                }
            }
        }

        synchronized (lock) {
            List<Integer> stale = new ArrayList<>();
            for (Integer session : effects.keySet()) if (!eligibleSessions.contains(session)) stale.add(session);
            for (Integer session : stale) {
                SessionNoiseEffectInstaller.Handle handle = effects.remove(session);
                attempts.remove(session);
                if (handle != null) handle.release();
            }
        }

        if (!enabled) return !targets.isEmpty();
        if (active.isEmpty()) {
            status = "ENABLED • profile=" + profile + " • waiting for external mic session"
                    + (silenced > 0 ? " • silenced=" + silenced : "")
                    + " • " + aiBridge.status();
            return !targets.isEmpty();
        }

        String targetSummary = String.join(" | ", active);
        lastExternal = "LAST_EXTERNAL: " + targetSummary;
        status = "ACTIVE • profile=" + profile + " • targets=" + active.size()
                + " • " + aiBridge.status();
        return true;
    }

    private void resetHealth() {
        synchronized (lock) {
            verifiedHistory.clear();
            failedHistory.clear();
        }
        protectedSessions = 0;
        failedSessions = 0;
        lastProtectedPackage = "—";
        lastVerifiedChain = "—";
    }

    private String protectionSummary() {
        String state;
        if (!enabled) state = "OFF";
        else if (protectedSessions > 0) state = "CONFIRMED";
        else if (failedSessions > 0) state = "WARNING";
        else state = "ARMED";
        return "PROTECTION: " + state
                + " • protected=" + protectedSessions
                + " • failed=" + failedSessions
                + " • last=" + lastProtectedPackage
                + " • chain=" + lastVerifiedChain;
    }

    private boolean eligible(AudioRecordingSessionMonitor.Target target) {
        if (target.session <= 0 || target.uid <= 0) return false;
        if (target.uid == Process.SHELL_UID) return false; // ClearMic injector's physical mic capture
        if (CLEARMIC_PACKAGE.equals(target.packageName)) return false;
        int source = target.source;
        return source == MediaRecorder.AudioSource.DEFAULT
                || source == MediaRecorder.AudioSource.MIC
                || source == MediaRecorder.AudioSource.VOICE_RECOGNITION
                || source == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                || source == MediaRecorder.AudioSource.UNPROCESSED
                || source == MediaRecorder.AudioSource.VOICE_PERFORMANCE;
    }

    private static String sessionKey(AudioRecordingSessionMonitor.Target target) {
        return (target.packageName == null ? "" : target.packageName) + "#" + target.session;
    }

    private static boolean hasEffect(String effectsText, String... names) {
        if (effectsText == null || effectsText.isEmpty()) return false;
        String value = effectsText.toLowerCase(Locale.ROOT);
        for (String name : names) if (value.contains(name.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static void appendEffect(StringBuilder out, String value) {
        if (out.length() > 0) out.append('+');
        out.append(value);
    }

    private String readSnapshot() {
        try {
            List<AudioRecordingSessionMonitor.Target> targets = AudioRecordingSessionMonitor.read();
            monitor = "MONITOR: IAudioService Binder • records=" + targets.size();
            return formatSnapshot(targets);
        } catch (Throwable error) {
            monitor = "MONITOR ERROR: " + describe(error);
            return "recordings=ERROR " + describe(error);
        }
    }

    private String formatSnapshot(List<AudioRecordingSessionMonitor.Target> targets) {
        StringBuilder out = new StringBuilder("recordings=").append(targets.size());
        for (AudioRecordingSessionMonitor.Target target : targets) {
            out.append("\n• pkg=").append(target.packageName.isEmpty() ? "—" : target.packageName)
                    .append(" uid=").append(target.uid)
                    .append(" session=").append(target.session)
                    .append(" source=").append(target.source)
                    .append(" silenced=").append(target.silenced);
            if (!target.effects.isEmpty()) out.append(" effects=").append(target.effects);
        }
        return out.toString();
    }

    private void releaseAll() {
        synchronized (lock) {
            for (SessionNoiseEffectInstaller.Handle handle : effects.values()) handle.release();
            effects.clear();
            attempts.clear();
        }
    }

    private String executeReadOnly(String command) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final java.lang.Process runningProcess = process;
            FutureTask<String> reader = new FutureTask<>(() -> {
                StringBuilder out = new StringBuilder();
                try (BufferedReader input = new BufferedReader(
                        new InputStreamReader(runningProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = input.read(buffer)) >= 0 && out.length() < MAX_OUTPUT_CHARS) {
                        out.append(buffer, 0, Math.min(count, MAX_OUTPUT_CHARS - out.length()));
                    }
                }
                return out.toString();
            });
            Thread readerThread = new Thread(reader, "ClearMic-ShizukuRead");
            readerThread.setDaemon(true);
            readerThread.start();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "ERROR: probe timeout";
            }
            return reader.get(1, TimeUnit.SECONDS);
        } catch (Throwable error) {
            if (process != null) process.destroyForcibly();
            return "ERROR: " + describe(error);
        }
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage() == null ? "" : current.getMessage().replace('\n', ' ').trim();
        if (message.length() > 160) message = message.substring(0, 160);
        return current.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
    }
}
