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
    private static final int MAX_VENDOR_CANDIDATES = 2;

    private static final String[] SAFE_VENDOR_KEYWORDS = {
            "voice", "speech", "clarity", "clear voice", "denoise", "noise reduction",
            "noise cancel", "wind", "beam", "dereverb", "de-reverb", "preprocess",
            "pre-process", "voice enhance", "speech enhance"
    };

    private static final String[] BLOCKED_VENDOR_KEYWORDS = {
            "equalizer", "bass", "virtualizer", "reverb", "loudness", "visualizer",
            "spatial", "haptic", "music", "surround", "volume", "compressor", "limiter"
    };

    private final List<Object> defaults = new ArrayList<>();
    private String profile = PROFILE_BALANCED;
    private String status = "SOURCE_DEFAULT: disabled";
    private int registeredNoiseSuppressors;
    private int registeredVendorEnhancers;
    private String vendorInventory = "none";

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
        registeredVendorEnhancers = 0;

        Map<UUID, List<UUID>> implementations = effectImplementations();
        AudioEffect.Descriptor[] descriptors = safeQueryEffects();
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

            List<AudioEffect.Descriptor> vendorCandidates = safeVendorVoiceCandidates(descriptors);
            vendorInventory = describeVendorCandidates(vendorCandidates);
            int priority = 70;
            for (int i = 0; i < vendorCandidates.size() && i < MAX_VENDOR_CANDIDATES; i++) {
                AudioEffect.Descriptor descriptor = vendorCandidates.get(i);
                boolean loadedRecognition = registerConcreteVendor(
                        descriptor,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        priority,
                        results
                );
                boolean loadedCommunication = registerConcreteVendor(
                        descriptor,
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        priority,
                        results
                );
                if (loadedRecognition || loadedCommunication) registeredVendorEnhancers++;
                priority = Math.max(50, priority - 5);
            }
        } else {
            vendorInventory = "strong-only";
        }

        status = "SOURCE_DEFAULT[" + profile + "]: " + String.join(" | ", results)
                + " • active=" + defaults.size()
                + " • nsReady=" + registeredNoiseSuppressors + "/3"
                + " • vendorLoaded=" + registeredVendorEnhancers
                + " • vendorCandidates=" + vendorInventory;
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
        registeredVendorEnhancers = 0;
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

    private boolean registerConcreteVendor(
            AudioEffect.Descriptor descriptor,
            int source,
            int priority,
            List<String> results
    ) {
        if (descriptor == null || descriptor.uuid == null) return false;
        String label = compactName(descriptor.name);
        try {
            Object effect = create(NULL_EFFECT_UUID, descriptor.uuid, priority, source);
            defaults.add(effect);
            results.add("VENDOR[" + label + "]@" + sourceName(source) + "=OK");
            return true;
        } catch (Throwable error) {
            results.add("VENDOR[" + label + "]@" + sourceName(source) + "=skip:"
                    + describe(unwrap(error)));
            return false;
        }
    }

    private Map<UUID, List<UUID>> effectImplementations() {
        Map<UUID, List<UUID>> out = new HashMap<>();
        AudioEffect.Descriptor[] descriptors = safeQueryEffects();
        if (descriptors == null) return out;

        for (AudioEffect.Descriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.type == null || descriptor.uuid == null) continue;
            out.computeIfAbsent(descriptor.type, ignored -> new ArrayList<>()).add(descriptor.uuid);
        }
        return out;
    }

    private AudioEffect.Descriptor[] safeQueryEffects() {
        try {
            return AudioEffect.queryEffects();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<AudioEffect.Descriptor> safeVendorVoiceCandidates(AudioEffect.Descriptor[] descriptors) {
        List<AudioEffect.Descriptor> out = new ArrayList<>();
        if (descriptors == null) return out;

        for (AudioEffect.Descriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.uuid == null || descriptor.type == null) continue;
            if (AudioEffect.EFFECT_TYPE_NS.equals(descriptor.type)
                    || AudioEffect.EFFECT_TYPE_AEC.equals(descriptor.type)
                    || AudioEffect.EFFECT_TYPE_AGC.equals(descriptor.type)) continue;

            String name = safeLower(descriptor.name);
            String implementor = safeLower(descriptor.implementor);
            String combined = name + " " + implementor;
            if (combined.contains("android open source project") || combined.contains("aosp")) continue;
            if (containsAny(combined, BLOCKED_VENDOR_KEYWORDS)) continue;
            if (!containsAny(combined, SAFE_VENDOR_KEYWORDS)) continue;
            out.add(descriptor);
        }
        return out;
    }

    private String describeVendorCandidates(List<AudioEffect.Descriptor> candidates) {
        if (candidates == null || candidates.isEmpty()) return "none";
        List<String> names = new ArrayList<>();
        int limit = Math.min(MAX_VENDOR_CANDIDATES, candidates.size());
        for (int i = 0; i < limit; i++) names.add(compactName(candidates.get(i).name));
        return String.join(",", names);
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

    private static boolean containsAny(String text, String[] keys) {
        if (text == null || text.isEmpty()) return false;
        for (String key : keys) if (text.contains(key)) return true;
        return false;
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String compactName(String value) {
        if (value == null || value.trim().isEmpty()) return "unnamed";
        String clean = value.trim().replace('|', '_').replace('\n', ' ');
        return clean.length() > 28 ? clean.substring(0, 28) : clean;
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
