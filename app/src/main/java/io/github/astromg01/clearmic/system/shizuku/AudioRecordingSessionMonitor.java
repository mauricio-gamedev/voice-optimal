package io.github.astromg01.clearmic.system.shizuku;

import android.media.AudioRecordingConfiguration;
import android.media.audiofx.AudioEffect;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class AudioRecordingSessionMonitor {
    private AudioRecordingSessionMonitor() {}

    static List<Target> read() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        IBinder binder = (IBinder) getService.invoke(null, "audio");
        if (binder == null) throw new IllegalStateException("audio Binder unavailable");

        Class<?> stubClass = Class.forName("android.media.IAudioService$Stub");
        Method asInterface = stubClass.getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        Object audioService = asInterface.invoke(null, binder);
        if (audioService == null) throw new IllegalStateException("IAudioService unavailable");

        Class<?> interfaceClass = Class.forName("android.media.IAudioService");
        Method getActive = interfaceClass.getDeclaredMethod("getActiveRecordingConfigurations");
        getActive.setAccessible(true);
        Object raw = getActive.invoke(audioService);

        List<Target> out = new ArrayList<>();
        if (!(raw instanceof List<?>)) return out;
        for (Object item : (List<?>) raw) {
            if (item instanceof AudioRecordingConfiguration) {
                out.add(Target.from((AudioRecordingConfiguration) item));
            }
        }
        return out;
    }

    static final class Target {
        final int session;
        final int source;
        final int uid;
        final String packageName;
        final boolean silenced;
        final String effects;

        Target(int session, int source, int uid, String packageName, boolean silenced, String effects) {
            this.session = session;
            this.source = source;
            this.uid = uid;
            this.packageName = packageName == null ? "" : packageName;
            this.silenced = silenced;
            this.effects = effects == null ? "" : effects;
        }

        static Target from(AudioRecordingConfiguration config) {
            return new Target(
                    config.getClientAudioSessionId(),
                    config.getClientAudioSource(),
                    readHiddenInt(config, "getClientUid", -1),
                    readHiddenString(config, "getClientPackageName"),
                    readSilenced(config),
                    effectNames(config)
            );
        }

        String label() {
            String owner = packageName.isEmpty() ? "uid=" + uid : packageName;
            return owner + " session=" + session + " src=" + source;
        }

        private static int readHiddenInt(AudioRecordingConfiguration config, String name, int fallback) {
            try {
                Method method = AudioRecordingConfiguration.class.getDeclaredMethod(name);
                method.setAccessible(true);
                Object value = method.invoke(config);
                return value instanceof Integer ? (Integer) value : fallback;
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        private static String readHiddenString(AudioRecordingConfiguration config, String name) {
            try {
                Method method = AudioRecordingConfiguration.class.getDeclaredMethod(name);
                method.setAccessible(true);
                Object value = method.invoke(config);
                return value instanceof String ? (String) value : "";
            } catch (Throwable ignored) {
                return "";
            }
        }

        private static boolean readSilenced(AudioRecordingConfiguration config) {
            try { return config.isClientSilenced(); }
            catch (Throwable ignored) { return false; }
        }

        private static String effectNames(AudioRecordingConfiguration config) {
            try {
                List<AudioEffect.Descriptor> list = config.getEffects();
                if (list == null || list.isEmpty()) return "";
                StringBuilder out = new StringBuilder();
                for (AudioEffect.Descriptor descriptor : list) {
                    if (descriptor == null || descriptor.name == null) continue;
                    if (out.length() > 0) out.append(',');
                    out.append(compact(descriptor.name));
                    if (out.length() > 120) break;
                }
                return out.toString();
            } catch (Throwable ignored) {
                return "";
            }
        }

        private static String compact(String value) {
            String text = value == null ? "" : value.replace('\n', ' ').replace('|', '/').trim();
            return text.length() <= 40 ? text : text.substring(0, 40);
        }
    }
}
