package io.github.astromg01.clearmic.system.shizuku;

import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.NoiseSuppressor;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class SessionNoiseEffectInstaller {
    private static final UUID EFFECT_TYPE_NULL =
            UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");

    private SessionNoiseEffectInstaller() {}

    static String inventory() {
        AudioEffect.Descriptor[] descriptors = queryEffects();
        if (descriptors == null) return "NS_IMPLS: query failed";
        List<String> found = new ArrayList<>();
        for (AudioEffect.Descriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.type == null) continue;
            if (!AudioEffect.EFFECT_TYPE_NS.equals(descriptor.type)) continue;
            String uuid = descriptor.uuid == null ? "?" : shortUuid(descriptor.uuid);
            found.add(compact(descriptor.name) + "@" + uuid + "/" + compact(descriptor.implementor));
        }
        return found.isEmpty() ? "NS_IMPLS: none" : "NS_IMPLS: " + join(found, 4);
    }

    static Handle install(int session, int source) {
        Attempt ns = installNs(session);
        AcousticEchoCanceler aec = null;
        String aecState = "AEC skipped";

        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(session);
                    if (aec == null) {
                        aecState = "AEC create=null";
                    } else {
                        int result = aec.setEnabled(true);
                        aecState = "AEC=" + onOff(aec.getEnabled())
                                + "/result=" + result + "/control=" + aec.hasControl();
                    }
                } else {
                    aecState = "AEC unavailable";
                }
            } catch (Throwable error) {
                aecState = "AEC error=" + describe(error);
                if (aec != null) {
                    try { aec.release(); } catch (Throwable ignored) {}
                    aec = null;
                }
            }
        }

        String chain = querySessionPreprocessings(session);
        return new Handle(ns.effect, aec, ns.summary + " " + aecState + " chain=" + chain);
    }

    private static Attempt installNs(int session) {
        List<String> attempts = new ArrayList<>();

        if (NoiseSuppressor.isAvailable()) {
            try {
                NoiseSuppressor wrapper = NoiseSuppressor.create(session);
                if (wrapper == null) {
                    attempts.add("wrapper:null");
                } else {
                    String state = enableAndDescribe(wrapper, "wrapper");
                    if (working(wrapper)) return new Attempt(wrapper, state);
                    attempts.add(state);
                    wrapper.release();
                }
            } catch (Throwable error) {
                attempts.add("wrapper:" + describe(error));
            }
        } else {
            attempts.add("wrapper:unavailable");
        }

        Attempt generic = reflective(
                AudioEffect.EFFECT_TYPE_NS,
                EFFECT_TYPE_NULL,
                0,
                session,
                "generic-type"
        );
        if (generic.effect != null) return generic;
        attempts.add(generic.summary);

        AudioEffect.Descriptor[] descriptors = queryEffects();
        if (descriptors != null) {
            int tried = 0;
            for (AudioEffect.Descriptor descriptor : descriptors) {
                if (descriptor == null || descriptor.type == null || descriptor.uuid == null) continue;
                if (!AudioEffect.EFFECT_TYPE_NS.equals(descriptor.type)) continue;
                if (tried++ >= 4) break;

                String label = compact(descriptor.name);
                Attempt concrete = reflective(
                        AudioEffect.EFFECT_TYPE_NS,
                        descriptor.uuid,
                        0,
                        session,
                        "vendor:" + label
                );
                if (concrete.effect != null) return concrete;
                attempts.add(concrete.summary);

                Attempt uuidOnly = reflective(
                        EFFECT_TYPE_NULL,
                        descriptor.uuid,
                        0,
                        session,
                        "uuid-only:" + label
                );
                if (uuidOnly.effect != null) return uuidOnly;
                attempts.add(uuidOnly.summary);
            }
        }

        return new Attempt(null, "NS FAILED [" + join(attempts, 6) + "]");
    }

    private static Attempt reflective(UUID type, UUID uuid, int priority, int session, String label) {
        AudioEffect effect = null;
        try {
            Constructor<AudioEffect> constructor = AudioEffect.class.getDeclaredConstructor(
                    UUID.class,
                    UUID.class,
                    int.class,
                    int.class
            );
            constructor.setAccessible(true);
            effect = constructor.newInstance(type, uuid, priority, session);
            String state = enableAndDescribe(effect, label);
            if (working(effect)) return new Attempt(effect, state);
            try { effect.release(); } catch (Throwable ignored) {}
            return new Attempt(null, state);
        } catch (Throwable error) {
            if (effect != null) {
                try { effect.release(); } catch (Throwable ignored) {}
            }
            return new Attempt(null, label + ":" + describe(error));
        }
    }

    private static String enableAndDescribe(AudioEffect effect, String label) {
        try {
            int result = effect.setEnabled(true);
            AudioEffect.Descriptor descriptor = null;
            try { descriptor = effect.getDescriptor(); } catch (Throwable ignored) {}
            String name = descriptor == null ? "?" : compact(descriptor.name);
            String uuid = descriptor == null || descriptor.uuid == null ? "?" : shortUuid(descriptor.uuid);
            return label + "=enabled:" + effect.getEnabled()
                    + "/control:" + effect.hasControl()
                    + "/result:" + result
                    + "/impl:" + name
                    + "/uuid:" + uuid;
        } catch (Throwable error) {
            return label + ":enable-error:" + describe(error);
        }
    }

    private static boolean working(AudioEffect effect) {
        if (effect == null) return false;
        try { return effect.getEnabled() && effect.hasControl(); }
        catch (Throwable ignored) { return false; }
    }

    private static String querySessionPreprocessings(int session) {
        try {
            Method method = AudioEffect.class.getDeclaredMethod("queryPreProcessings", int.class);
            method.setAccessible(true);
            Object raw = method.invoke(null, session);
            if (!(raw instanceof AudioEffect.Descriptor[])) return "?";
            AudioEffect.Descriptor[] descriptors = (AudioEffect.Descriptor[]) raw;
            if (descriptors.length == 0) return "none";
            List<String> labels = new ArrayList<>();
            for (AudioEffect.Descriptor descriptor : descriptors) {
                if (descriptor == null) continue;
                labels.add(compact(descriptor.name) + "@"
                        + (descriptor.uuid == null ? "?" : shortUuid(descriptor.uuid)));
            }
            return labels.isEmpty() ? "none" : join(labels, 4);
        } catch (Throwable error) {
            return "query-error:" + describe(error);
        }
    }

    private static AudioEffect.Descriptor[] queryEffects() {
        try { return AudioEffect.queryEffects(); }
        catch (Throwable ignored) { return null; }
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        if (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getCause() != null) {
            current = ((InvocationTargetException) current).getCause();
        }
        String message = current.getMessage() == null ? "" : current.getMessage().replace('\n', ' ').trim();
        if (message.length() > 90) message = message.substring(0, 90);
        return current.getClass().getSimpleName() + (message.isEmpty() ? "" : ":" + message);
    }

    private static String compact(String value) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('|', '/').trim();
        if (text.isEmpty()) return "?";
        return text.length() <= 40 ? text : text.substring(0, 40);
    }

    private static String shortUuid(UUID uuid) {
        String text = uuid.toString();
        return text.length() <= 13 ? text : text.substring(0, 13);
    }

    private static String join(List<String> values, int maxItems) {
        if (values == null || values.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        int count = Math.min(values.size(), maxItems);
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(" ; ");
            out.append(values.get(i));
        }
        if (values.size() > count) out.append(" ; +").append(values.size() - count).append(" more");
        return out.toString();
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    static final class Handle {
        private AudioEffect ns;
        private AcousticEchoCanceler aec;
        final String summary;

        Handle(AudioEffect ns, AcousticEchoCanceler aec, String summary) {
            this.ns = ns;
            this.aec = aec;
            this.summary = summary;
        }

        boolean hasWorkingNs() {
            if (ns == null) return false;
            try { return ns.getEnabled() && ns.hasControl(); }
            catch (Throwable ignored) { return false; }
        }

        void release() {
            if (ns != null) {
                try { ns.release(); } catch (Throwable ignored) {}
                ns = null;
            }
            if (aec != null) {
                try { aec.release(); } catch (Throwable ignored) {}
                aec = null;
            }
        }
    }

    private static final class Attempt {
        final AudioEffect effect;
        final String summary;

        Attempt(AudioEffect effect, String summary) {
            this.effect = effect;
            this.summary = summary;
        }
    }
}
