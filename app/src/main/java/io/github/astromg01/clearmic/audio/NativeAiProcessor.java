package io.github.astromg01.clearmic.audio;

import android.content.Context;

import java.io.File;

/**
 * Small streaming JNI wrapper used by the Shizuku AudioPolicy injector.
 *
 * Unlike NativeAudioBridge this class does not open a microphone. It owns one streaming
 * RNNoise processor and processes caller-provided 48 kHz mono 10 ms / 480-sample PCM16 frames.
 */
public final class NativeAiProcessor implements AutoCloseable {
    public static final int FRAME_SAMPLES = 480;
    private static final String LIBRARY = "clearmic_ai_stream";

    private static final Object LOAD_LOCK = new Object();
    private static volatile boolean loadAttempted;
    private static volatile boolean loaded;
    private static volatile String loadError = "not attempted";

    private long handle;

    public NativeAiProcessor(Context context) {
        if (!ensureLoaded(context)) {
            throw new IllegalStateException(LIBRARY + " load failed: " + loadError);
        }
        handle = nativeCreateProcessor();
        if (handle == 0L) throw new IllegalStateException("nativeCreateProcessor returned 0");
    }

    public static boolean ensureLoaded(Context context) {
        if (loadAttempted) return loaded;
        synchronized (LOAD_LOCK) {
            if (loadAttempted) return loaded;
            loadAttempted = true;
            try {
                System.loadLibrary(LIBRARY);
                loaded = true;
                loadError = "none";
                return true;
            } catch (Throwable first) {
                try {
                    if (context == null || context.getApplicationInfo() == null) throw first;
                    String dir = context.getApplicationInfo().nativeLibraryDir;
                    if (dir == null || dir.isEmpty()) throw first;
                    File library = new File(dir, "lib" + LIBRARY + ".so");
                    System.load(library.getAbsolutePath());
                    loaded = true;
                    loadError = "none";
                    return true;
                } catch (Throwable second) {
                    loaded = false;
                    loadError = describe(second);
                    return false;
                }
            }
        }
    }

    public static String getLoadError() {
        return loadError;
    }

    /**
     * Processes exactly one 10 ms frame in place.
     * stats[0..3] = AI active, VAD, RNNoise smoothed ms, effective AI profile.
     * Returns 1 when RNNoise was applied, 0 when the lightweight fallback was used, negative on error.
     */
    public int process(short[] frame, int profile, float[] stats) {
        if (handle == 0L || frame == null || frame.length != FRAME_SAMPLES) return -1;
        if (stats == null || stats.length < 4) return -2;
        return nativeProcessFrame(handle, frame, profile, stats);
    }

    @Override
    public void close() {
        long value = handle;
        handle = 0L;
        if (value != 0L) nativeDestroyProcessor(value);
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        String type = current.getClass().getSimpleName();
        if (message == null || message.trim().isEmpty()) return type;
        message = message.replace('\n', ' ').trim();
        if (message.length() > 180) message = message.substring(0, 180);
        return type + ": " + message;
    }

    private static native long nativeCreateProcessor();
    private static native int nativeProcessFrame(long handle, short[] frame, int profile, float[] stats);
    private static native void nativeDestroyProcessor(long handle);
}
