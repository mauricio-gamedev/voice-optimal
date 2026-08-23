package io.github.astromg01.clearmic.system.shizuku;

import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Registers transient source-default voice effects before a game opens its recorder. */
final class SourceDefaultNsController {
    static final String PROFILE_LIGHT = "LIGHT";
    static final String PROFILE_BALANCED = "BALANCED";
    static final String PROFILE_STRONG = "STRONG";

    private static final UUID NULL_EFFECT_UUID = new UUID(0L, 0L);

    private final List<Object> defaults = new ArrayList<>();
    private String profile = PROFILE_BALANCED;
    private String status = "SOURCE_DEFAULT: disabled";
    private int registeredNoiseSuppressors;

    void setProfile(String requested) {
        profile = normalizeProfile(requested);
    }

    String getProfile() {
        return profile;
    }

    int registeredNoiseSuppressors() {
        return registeredNoiseSuppressors;
    }

    boolean hasNoiseSuppressionReady() {
        return registeredNoiseSuppressors > 0;
    }

    String enable() {
        releaseInternal(false);
        registeredNoiseSuppressors = 0;

        Map<UUID, List<UUID>> implementations = effectImplementations();
        List<String> results = new ArrayList<>();

        if (registerEffect("NS", AudioEffect.EFFECT_TYPE_NS, MediaRecorder.AudioSource.MIC,
                implementations, 100, results, true)) registeredNoiseSuppressors++;
        if (registerEffect("NS", AudioEffect.EFFECT_TYPE_NS, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                implementations, 100, results, true)) registeredNoiseSuppressors++;
        if (registerEffect("NS", AudioEffect.EFFECT_TYPE_NS, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                implementations, 100, results, true)) registeredNoiseSuppressors++;

        if (!PROFILE_LIGHT.equals(profile)) {
            registerEffect("AEC", AudioEffect.EFFECT_TYPE_AEC, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    implementations, 90, results, false);
        }

        if (PROFILE_STRONG.equals(profile)) {
            registerEffect("AGC", AudioEffect.EFFECT_TYPE_AGC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    implementations, 80, results, false);
            registerEffect("AGC", AudioEffect.EFFECT_TYPE_AGC, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    implementations, 80, results, false);
        }

        status = "SOURCE_DEFAULT[" + profile + "]: " + String.join(" | ", results)
                + " • active=" + defaults.size()
                + " • nsReady=" + registeredNoiseSuppressors + "/3";
        return status;
    }

    String status() {
        return status;
    }

    void release() {
        releaseInternal(true);
    }

    private void releaseInternal(boolean updateStatus) {
        for (Object effect : defaults) {
            try {
                Method release = effect.getClass().getMethod("release");
                release.setAccessible(true);
                release.invoke(effect);
            } catch (Throwable ignored) {
            }
        }
        defaults.clear();
        registeredNoiseSuppressors = 0;
        if (updateStatus) status = "SOURCE_DEFAULT[" + profile + "]: disabled";
    }

    private boolean registerEffect(
            String label,
            UUID type,
            int source,
            Map<UUID, List<UUID>> implementations,
            int priority,
            List<String> results,
            boolean required
    ) {
        List<UUID> concrete = implementations.get(type);
        if (concrete == null || concrete.isEmpty()) {
            try {
                Object effect = create(type, NULL_EFFECT_UUID, priority, source);
                defaults.add(effect);
                results.add(label + "@" + sourceName(source) + "=type:OK");
                return true;
            } catch (Throwable error) {
                results.add(label + "@" + sourceName(source) + "="
                        + (required ? "FAIL:" + describe(unwrap(error)) : "unavailable"));
                return false;
            }
        }

        Throwable lastError = null;
        try {
            Object effect = create(type, NULL_EFFECT_UUID, priority, source);
            defaults.add(effect);
            results.add(label + "@" + sourceName(source) + "=type:OK");
            return true;
        } catch (Throwable error) {
            lastError = unwrap(error);
        }

        for (UUID uuid : concrete) {
            try {
                Object effect = create(NULL_EFFECT_UUID, uuid, priority, source);
                defaults.add(effect);
                results.add(label + "@" + sourceName(source) + "=uuid:" + shortUuid(uuid) + ":OK");
                return true;
            } catch (Throwable error) {
                lastError = unwrap(error);
            }
        }

        results.add(label + "@" + sourceName(source) + "="
                + (required ? "FAIL:" + describe(lastError) : "unavailable:" + describe(lastError)));
        return false;
    }

    private Map<UUID, List<UUID>> effectImplementations() {
        Map<UUID, List<UUID>> out = new HashMap<>();
        AudioEffect.Descriptor[] descriptors = AudioEffect.queryEffects();
        if (descriptors == null) return out;

        for (AudioEffect.Descriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.type == null || descriptor.uuid == null) continue;
            out.computeIfAbsent(descriptor.type, ignored -> new ArrayList<>()).add(descriptor.uuid);
        }
        return out;
    }

    private Object create(UUID type, UUID uuid, int priority, int source) throws Exception {
        Class<?> clazz = Class.forName("android.media.audiofx.SourceDefaultEffect");
        Constructor<?> constructor = clazz.getDeclaredConstructor(UUID.class, UUID.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(type, uuid, priority, source);
    }

    static String normalizeProfile(String value) {
        if (value == null) return PROFILE_BALANCED;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (PROFILE_LIGHT.equals(normalized) || PROFILE_STRONG.equals(normalized)) return normalized;
        return PROFILE_BALANCED;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }

    private static String describe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ":" + message.replace('\n', ' '));
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
