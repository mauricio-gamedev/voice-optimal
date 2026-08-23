package io.github.astromg01.clearmic.system.shizuku;

import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Registers transient source-default NS effects before a game opens its recorder. */
final class SourceDefaultNsController {
    // AudioEffect.EFFECT_TYPE_NULL is hidden from the public SDK stubs. AOSP defines
    // it as the all-zero effect UUID, so keep the value locally for hidden-API calls.
    private static final UUID NULL_EFFECT_UUID = new UUID(0L, 0L);

    private final List<Object> defaults = new ArrayList<>();
    private String status = "SOURCE_DEFAULT: disabled";

    String enable() {
        release();

        AudioEffect.Descriptor[] descriptors = AudioEffect.queryEffects();
        List<UUID> nsUuids = new ArrayList<>();
        if (descriptors != null) {
            for (AudioEffect.Descriptor descriptor : descriptors) {
                if (descriptor != null && AudioEffect.EFFECT_TYPE_NS.equals(descriptor.type) && descriptor.uuid != null) {
                    nsUuids.add(descriptor.uuid);
                }
            }
        }

        int[] sources = new int[] {
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        };

        List<String> results = new ArrayList<>();
        for (int source : sources) {
            Object effect = null;
            Throwable lastError = null;

            // First prefer type-based selection; AudioPolicy chooses the implementation.
            try {
                effect = create(AudioEffect.EFFECT_TYPE_NS, NULL_EFFECT_UUID, source);
                defaults.add(effect);
                results.add(sourceName(source) + "=type:OK");
                continue;
            } catch (Throwable error) {
                lastError = unwrap(error);
            }

            // If type-based selection fails, try concrete implementations discovered on device.
            for (UUID uuid : nsUuids) {
                try {
                    effect = create(NULL_EFFECT_UUID, uuid, source);
                    defaults.add(effect);
                    results.add(sourceName(source) + "=uuid:" + shortUuid(uuid) + ":OK");
                    lastError = null;
                    break;
                } catch (Throwable error) {
                    lastError = unwrap(error);
                }
            }

            if (effect == null) {
                results.add(sourceName(source) + "=FAIL:" + describe(lastError));
            }
        }

        status = "SOURCE_DEFAULT: " + String.join(" | ", results) + " • active=" + defaults.size();
        return status;
    }

    String status() {
        return status;
    }

    void release() {
        for (Object effect : defaults) {
            try {
                Method release = effect.getClass().getMethod("release");
                release.setAccessible(true);
                release.invoke(effect);
            } catch (Throwable ignored) {
            }
        }
        defaults.clear();
        status = "SOURCE_DEFAULT: disabled";
    }

    private Object create(UUID type, UUID uuid, int source) throws Exception {
        Class<?> clazz = Class.forName("android.media.audiofx.SourceDefaultEffect");
        Constructor<?> constructor = clazz.getDeclaredConstructor(UUID.class, UUID.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(type, uuid, 100, source);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }

    private static String describe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ":" + message.replace('\n', ' '));
    }

    private static String sourceName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            default: return "SRC_" + source;
        }
    }

    private static String shortUuid(UUID uuid) {
        String value = uuid.toString();
        return value.substring(0, Math.min(8, value.length()));
    }
}
