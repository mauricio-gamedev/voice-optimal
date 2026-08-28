package io.github.astromg01.clearmic.system.shizuku;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;

import io.github.astromg01.clearmic.audio.NativeAiProcessor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Experimental Android AudioPolicy recorder-injector bridge.
 *
 * The target app's recorder is matched by UID. AudioPolicy provides an AudioTrack source whose
 * samples replace the matched recording source. A separate shell-owned AudioRecord captures the
 * physical microphone, ClearMic RNNoise/VoiceDsp processes each 10 ms frame, then the processed
 * PCM is written to the injector AudioTrack.
 *
 * All hidden/SystemApi calls are reflective so the normal app remains buildable with the public
 * SDK. The Shizuku UserService already runs as shell and the device probe proved it has
 * MODIFY_AUDIO_ROUTING. Any failure tears down the policy immediately so the existing
 * SourceDefaultEffect NS path remains the fallback.
 */
final class AiSystemInjectionBridge {
    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SAMPLES = NativeAiProcessor.FRAME_SAMPLES;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int ROUTE_FLAG_LOOP_BACK = 2;
    private static final int MIX_ROLE_INJECTOR = 1;
    private static final int RULE_MATCH_UID = 4;
    private static final int AUDIO_MANAGER_SUCCESS = 0;
    private static final int MAX_CONSECUTIVE_IO_ERRORS = 4;

    private final Object lock = new Object();
    private final Context context;

    private volatile boolean armed;
    private volatile boolean running;
    private volatile int targetUid = -1;
    private volatile int targetSource = -1;
    private volatile String targetPackage = "—";
    private volatile String profile = SourceDefaultNsController.PROFILE_BALANCED;
    private volatile String status = "AI_ROUTE: disabled";
    private volatile long injectedFrames;
    private volatile float lastVad;
    private volatile float lastAiMs;
    private volatile boolean lastAiActive;
    private volatile int captureSource = -1;
    private volatile int restarts;

    private Object audioPolicy;
    private Object audioMix;
    private AudioManager audioManager;
    private AudioTrack injectorTrack;
    private AudioRecord micRecord;
    private NativeAiProcessor processor;
    private Thread pumpThread;

    AiSystemInjectionBridge(Context context) {
        this.context = context;
    }

    void setProfile(String value) {
        profile = SourceDefaultNsController.normalizeProfile(value);
    }

    String arm() {
        armed = true;
        if (context == null) {
            status = "AI_ROUTE: FALLBACK • no UserService Context";
            return status;
        }
        if (!NativeAiProcessor.ensureLoaded(context)) {
            status = "AI_ROUTE: FALLBACK • native RNNoise unavailable: " + NativeAiProcessor.getLoadError();
            return status;
        }
        status = "AI_ROUTE: ARMED • RNNoise ready • waiting for external recorder UID";
        return status;
    }

    void disarm() {
        armed = false;
        stop("disabled");
        status = "AI_ROUTE: disabled";
    }

    /**
     * Learn/switch to the external app UID. Keeping the policy alive after its recorder closes
     * means the next AudioRecord created by the same app is routed through ClearMic from startup.
     */
    void ensureTarget(int uid, String packageName, int source) {
        if (!armed || uid <= 0 || uid == Process.SHELL_UID) return;
        String pkg = packageName == null || packageName.isEmpty() ? "uid:" + uid : packageName;
        synchronized (lock) {
            if (running && targetUid == uid && pumpThread != null && pumpThread.isAlive()) return;
        }
        startForTarget(uid, pkg, source);
    }

    String status() {
        if (running) {
            long frames = injectedFrames;
            return "AI_ROUTE: INJECTING • target=" + targetPackage
                    + " uid=" + targetUid
                    + " capture=" + sourceName(captureSource)
                    + " frames=" + frames
                    + " (~" + (frames / 48_000L) + "s)"
                    + " • RNNoise=" + (lastAiActive ? "ON" : "V3_FALLBACK")
                    + " vad=" + String.format(Locale.US, "%.0f%%", lastVad * 100f)
                    + " ai=" + String.format(Locale.US, "%.2fms", lastAiMs)
                    + " • restarts=" + restarts;
        }
        return status;
    }

    boolean isRunningFor(int uid) {
        return running && targetUid == uid;
    }

    private void startForTarget(int uid, String packageName, int source) {
        synchronized (lock) {
            stopLocked("switch target");
            if (!armed) return;

            targetUid = uid;
            targetPackage = packageName;
            targetSource = source;
            injectedFrames = 0L;
            lastVad = 0f;
            lastAiMs = 0f;
            lastAiActive = false;
            status = "AI_ROUTE: preparing target=" + packageName + " uid=" + uid;

            try {
                processor = new NativeAiProcessor(context);
                audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (audioManager == null) throw new IllegalStateException("AudioManager unavailable in UserService");

                Object[] policyParts = buildAndRegisterPolicy(audioManager, uid);
                audioPolicy = policyParts[0];
                audioMix = policyParts[1];
                injectorTrack = (AudioTrack) policyParts[2];
                if (injectorTrack == null || injectorTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                    throw new IllegalStateException("injector AudioTrack not initialized");
                }

                micRecord = openPhysicalMic();
                if (micRecord == null || micRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("physical AudioRecord not initialized");
                }

                injectorTrack.play();
                micRecord.startRecording();
                if (injectorTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    throw new IllegalStateException("injector AudioTrack failed to enter PLAYING");
                }
                if (micRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("physical AudioRecord failed to enter RECORDING");
                }

                running = true;
                restarts++;
                pumpThread = new Thread(this::pumpLoop, "ClearMic-AI-SystemInjector");
                pumpThread.setDaemon(true);
                pumpThread.start();
                status = "AI_ROUTE: ACTIVE • target=" + packageName + " uid=" + uid
                        + " • AudioPolicy injector + RNNoise";
            } catch (Throwable error) {
                String reason = describe(error);
                stopLocked("setup failed");
                status = "AI_ROUTE: FALLBACK • target=" + packageName + " • " + reason;
            }
        }
    }

    private Object[] buildAndRegisterPolicy(AudioManager manager, int uid) throws Exception {
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object ruleBuilder = ruleBuilderClass.getDeclaredConstructor().newInstance();
        invoke(ruleBuilderClass, ruleBuilder, "setTargetMixRole", new Class<?>[]{int.class}, MIX_ROLE_INJECTOR);
        invoke(ruleBuilderClass, ruleBuilder, "addMixRule", new Class<?>[]{int.class, Object.class}, RULE_MATCH_UID, Integer.valueOf(uid));
        Object rule = invoke(ruleBuilderClass, ruleBuilder, "build", new Class<?>[0]);

        AudioFormat mixFormat = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();

        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Constructor<?> mixCtor = mixBuilderClass.getDeclaredConstructor(ruleClass);
        mixCtor.setAccessible(true);
        Object mixBuilder = mixCtor.newInstance(rule);
        invoke(mixBuilderClass, mixBuilder, "setFormat", new Class<?>[]{AudioFormat.class}, mixFormat);
        invoke(mixBuilderClass, mixBuilder, "setRouteFlags", new Class<?>[]{int.class}, ROUTE_FLAG_LOOP_BACK);
        Object mix = invoke(mixBuilderClass, mixBuilder, "build", new Class<?>[0]);

        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Constructor<?> policyCtor = policyBuilderClass.getDeclaredConstructor(Context.class);
        policyCtor.setAccessible(true);
        Object policyBuilder = policyCtor.newInstance(context);
        invoke(policyBuilderClass, policyBuilder, "addMix", new Class<?>[]{mixClass}, mix);
        Object policy = invoke(policyBuilderClass, policyBuilder, "build", new Class<?>[0]);

        Method register = AudioManager.class.getDeclaredMethod("registerAudioPolicy", policyClass);
        register.setAccessible(true);
        Object result = register.invoke(manager, policy);
        int code = result instanceof Integer ? (Integer) result : -1;
        if (code != AUDIO_MANAGER_SUCCESS) {
            throw new IllegalStateException("registerAudioPolicy=" + code);
        }

        try {
            Method source = policyClass.getDeclaredMethod("createAudioTrackSource", mixClass);
            source.setAccessible(true);
            AudioTrack track = (AudioTrack) source.invoke(policy, mix);
            return new Object[]{policy, mix, track};
        } catch (Throwable error) {
            unregisterPolicy(manager, policy);
            throw error;
        }
    }

    private AudioRecord openPhysicalMic() {
        int[] candidates = new int[]{
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        int min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buffer = Math.max(FRAME_SAMPLES * BYTES_PER_SAMPLE * 8, Math.max(min, 0));
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();

        for (int source : candidates) {
            AudioRecord record = null;
            try {
                record = new AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(buffer)
                        .build();
                if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                    captureSource = source;
                    return record;
                }
            } catch (Throwable ignored) {
            }
            if (record != null) runCatchingRelease(record);
        }
        captureSource = -1;
        return null;
    }

    private void pumpLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] frame = new short[FRAME_SAMPLES];
        float[] stats = new float[4];
        int ioErrors = 0;
        String terminalError = null;
        try {
            while (running && armed) {
                AudioRecord record = micRecord;
                AudioTrack track = injectorTrack;
                NativeAiProcessor dsp = processor;
                if (record == null || track == null || dsp == null) break;

                int offset = 0;
                while (offset < FRAME_SAMPLES && running) {
                    int count = record.read(frame, offset, FRAME_SAMPLES - offset, AudioRecord.READ_BLOCKING);
                    if (count <= 0) {
                        ioErrors++;
                        if (ioErrors >= MAX_CONSECUTIVE_IO_ERRORS) {
                            throw new IllegalStateException("mic read=" + count);
                        }
                        continue;
                    }
                    offset += count;
                }
                if (!running || offset != FRAME_SAMPLES) break;

                int ai = dsp.process(frame, aiProfile(), stats);
                if (ai < 0) throw new IllegalStateException("native AI process=" + ai);
                lastAiActive = ai > 0;
                lastVad = stats[1];
                lastAiMs = stats[2];

                offset = 0;
                while (offset < FRAME_SAMPLES && running) {
                    int count = track.write(frame, offset, FRAME_SAMPLES - offset, AudioTrack.WRITE_BLOCKING);
                    if (count <= 0) {
                        ioErrors++;
                        if (ioErrors >= MAX_CONSECUTIVE_IO_ERRORS) {
                            throw new IllegalStateException("inject write=" + count);
                        }
                        continue;
                    }
                    offset += count;
                }
                injectedFrames += offset;
                ioErrors = 0;
            }
        } catch (Throwable error) {
            terminalError = describe(error);
        } finally {
            synchronized (lock) {
                boolean wasArmed = armed;
                stopLocked("pump ended");
                if (wasArmed) {
                    status = terminalError == null
                            ? "AI_ROUTE: FALLBACK • injector pump stopped"
                            : "AI_ROUTE: FALLBACK • " + terminalError;
                }
            }
        }
    }

    private int aiProfile() {
        if (SourceDefaultNsController.PROFILE_LIGHT.equals(profile)) return 1;
        if (SourceDefaultNsController.PROFILE_STRONG.equals(profile)) return 3;
        return 2;
    }

    private void stop(String reason) {
        synchronized (lock) {
            stopLocked(reason);
        }
    }

    private void stopLocked(String reason) {
        running = false;
        Thread thread = pumpThread;
        pumpThread = null;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();

        AudioRecord record = micRecord;
        micRecord = null;
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) {}
            runCatchingRelease(record);
        }

        AudioTrack track = injectorTrack;
        injectorTrack = null;
        if (track != null) {
            try { track.pause(); } catch (Throwable ignored) {}
            try { track.flush(); } catch (Throwable ignored) {}
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
        }

        NativeAiProcessor dsp = processor;
        processor = null;
        if (dsp != null) try { dsp.close(); } catch (Throwable ignored) {}

        Object policy = audioPolicy;
        audioPolicy = null;
        audioMix = null;
        AudioManager manager = audioManager;
        audioManager = null;
        if (policy != null && manager != null) unregisterPolicy(manager, policy);

        if (!armed) {
            targetUid = -1;
            targetSource = -1;
            targetPackage = "—";
        }
    }

    private static void unregisterPolicy(AudioManager manager, Object policy) {
        if (manager == null || policy == null) return;
        try {
            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Method unregister = AudioManager.class.getDeclaredMethod("unregisterAudioPolicy", policyClass);
            unregister.setAccessible(true);
            unregister.invoke(manager, policy);
        } catch (Throwable ignored) {
        }
    }

    private static Object invoke(Class<?> owner, Object receiver, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(receiver, args);
    }

    private static void runCatchingRelease(AudioRecord record) {
        try { record.release(); } catch (Throwable ignored) {}
    }

    private static String sourceName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.UNPROCESSED: return "UNPROCESSED";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.MIC: return "MIC";
            default: return String.valueOf(source);
        }
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage() == null ? "" : current.getMessage().replace('\n', ' ').trim();
        if (message.length() > 180) message = message.substring(0, 180);
        return current.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
    }
}
