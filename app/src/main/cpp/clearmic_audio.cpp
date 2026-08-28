#include <aaudio/AAudio.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <ctime>
#include <rnnoise.h>

namespace {
constexpr int32_t kSampleRate = 48000;
constexpr int32_t kChannels = 1;
constexpr int32_t kFrames = 480;
constexpr int64_t kCalibrationSamples = 72000;
constexpr float kEps = 1.0e-7f;
constexpr float kAiBudgetMs = 8.5f;

constexpr int32_t kAiOff = 0;
constexpr int32_t kAiNatural = 1;
constexpr int32_t kAiBalanced = 2;
constexpr int32_t kAiStrong = 3;

inline float db(float v) {
    if (v <= kEps) return -120.0f;
    return std::max(-120.0f, 20.0f * std::log10(v));
}

inline float smooth(float a, float b, float x) {
    if (b <= a) return x >= b ? 1.0f : 0.0f;
    float t = std::clamp((x - a) / (b - a), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

inline float elapsedMs(const timespec& begin, const timespec& end) {
    const int64_t sec = static_cast<int64_t>(end.tv_sec) - static_cast<int64_t>(begin.tv_sec);
    const int64_t nsec = static_cast<int64_t>(end.tv_nsec) - static_cast<int64_t>(begin.tv_nsec);
    return static_cast<float>(sec * 1000.0 + nsec / 1000000.0);
}

struct Stats {
    float rmsDb = -120.0f;
    float peak = 0.0f;
    float voice = 0.0f;
    float noiseDb = -120.0f;
    float aiActive = 0.0f;
    float aiVad = 0.0f;
    float aiMs = 0.0f;
    float aiProfile = 0.0f;
};

class VoiceDsp {
public:
    ~VoiceDsp() {
        if (rnnoise_) rnnoise_destroy(rnnoise_);
    }

    void reset() {
        prevIn_ = prevOut_ = 0.0f;
        noise_ = 0.0010f;
        calibrationMin_ = 1.0f;
        calibrationSamples_ = 0;
        suppression_ = agc_ = 1.0f;
        voiceSmooth_ = 0.0f;
        hangover_ = 0;
        previousRmsDb_ = -120.0f;
        lastRequestedAiProfile_ = -1;
        aiDisabledByBudget_ = false;
        aiSlowFrames_ = 0;
        aiVad_ = 0.0f;
        aiMsSmooth_ = 0.0f;

        if (!rnnoise_) rnnoise_ = rnnoise_create(nullptr);
        else rnnoise_init(rnnoise_, nullptr);
    }

    Stats process(int16_t* s, int32_t n, int32_t requestedAiProfile) {
        Stats out;
        if (!s || n <= 0) return out;

        requestedAiProfile = std::clamp(requestedAiProfile, kAiOff, kAiStrong);
        if (requestedAiProfile != lastRequestedAiProfile_) {
            lastRequestedAiProfile_ = requestedAiProfile;
            aiDisabledByBudget_ = false;
            aiSlowFrames_ = 0;
            aiVad_ = 0.0f;
            aiMsSmooth_ = 0.0f;
            if (rnnoise_) rnnoise_init(rnnoise_, nullptr);
        }

        double sum = 0.0;
        float inputPeak = 0.0f;
        for (int32_t i = 0; i < n; ++i) {
            const float x = static_cast<float>(s[i]) / 32768.0f;
            const float y = x - prevIn_ + 0.995f * prevOut_;
            prevIn_ = x;
            prevOut_ = y;
            const float v = std::clamp(y, -1.0f, 1.0f);
            inputPeak = std::max(inputPeak, std::fabs(v));
            sum += static_cast<double>(v) * v;
            s[i] = static_cast<int16_t>(v * 32767.0f);
        }

        const float rms = static_cast<float>(std::sqrt(sum / n));
        const float rmsDb = db(rms);
        const bool calibrating = calibrationSamples_ < kCalibrationSamples;

        bool aiApplied = false;
        if (requestedAiProfile > kAiOff && !aiDisabledByBudget_ && rnnoise_ && n == kFrames && rnnoise_get_frame_size() == kFrames) {
            for (int32_t i = 0; i < n; ++i) rnIn_[i] = static_cast<float>(s[i]);

            timespec begin{}, end{};
            clock_gettime(CLOCK_MONOTONIC, &begin);
            const float vad = rnnoise_process_frame(rnnoise_, rnOut_, rnIn_);
            clock_gettime(CLOCK_MONOTONIC, &end);
            const float frameMs = std::max(0.0f, elapsedMs(begin, end));
            aiMsSmooth_ = aiMsSmooth_ <= 0.0f ? frameMs : aiMsSmooth_ + 0.12f * (frameMs - aiMsSmooth_);
            aiVad_ += 0.28f * (std::clamp(vad, 0.0f, 1.0f) - aiVad_);

            if (frameMs > kAiBudgetMs || aiMsSmooth_ > kAiBudgetMs) ++aiSlowFrames_;
            else aiSlowFrames_ = std::max(0, aiSlowFrames_ - 1);

            // One 10 ms callback must keep enough headroom for capture + JNI/stat work.
            // If RNNoise repeatedly consumes most of that budget, fail safe to V3 for
            // the remainder of this audio session instead of causing game/phone stutter.
            if (aiSlowFrames_ >= 3) {
                aiDisabledByBudget_ = true;
            } else {
                const float wet = requestedAiProfile == kAiNatural
                    ? 0.35f
                    : (requestedAiProfile == kAiBalanced ? 0.70f : 1.0f);
                for (int32_t i = 0; i < n; ++i) {
                    const float dry = rnIn_[i];
                    const float denoised = std::clamp(rnOut_[i], -32768.0f, 32767.0f);
                    const float mixed = dry + wet * (denoised - dry);
                    s[i] = static_cast<int16_t>(std::clamp(mixed, -32768.0f, 32767.0f));
                }
                aiApplied = true;
            }
        }

        if (calibrating) {
            calibrationSamples_ += n;
            if (rms > 0.00015f) calibrationMin_ = std::min(calibrationMin_, rms);
            if (calibrationMin_ < 1.0f)
                noise_ = std::clamp(calibrationMin_ * 1.10f, 0.00015f, 0.035f);
        }

        const float noiseBefore = std::max(noise_, 0.00015f);
        const float noiseDbBefore = db(noiseBefore);
        const float snrDb = 20.0f * std::log10((rms + kEps) / (noiseBefore + kEps));
        const float onsetDb = rmsDb - previousRmsDb_;
        previousRmsDb_ = rmsDb;

        const float snrVoice = smooth(1.5f, 9.0f, snrDb);
        const float levelVoice = smooth(-61.0f, -38.0f, rmsDb);
        const float onsetVoice = smooth(0.8f, 5.0f, onsetDb);
        float rawVoice = calibrating
            ? 0.30f * levelVoice
            : std::clamp(0.52f * snrVoice + 0.35f * levelVoice + 0.13f * onsetVoice, 0.0f, 1.0f);

        if (aiApplied) rawVoice = std::max(rawVoice, aiVad_ * 0.82f);

        const bool speechCandidate = !calibrating && rmsDb > -62.0f && snrDb > 2.0f;
        const bool strongLevelCandidate = !calibrating && rmsDb > -50.0f && snrDb > 0.5f;
        if (speechCandidate) rawVoice = std::max(rawVoice, 0.52f);
        if (strongLevelCandidate) rawVoice = std::max(rawVoice, 0.46f);

        if (!calibrating) {
            if (rawVoice >= 0.50f) hangover_ = 24;
            else if (rawVoice >= 0.34f) hangover_ = std::max(hangover_, 10);
            else if (hangover_ > 0) --hangover_;
        }
        if (hangover_ > 0) rawVoice = std::max(rawVoice, 0.50f);

        const float alpha = rawVoice > voiceSmooth_ ? 0.42f : 0.06f;
        voiceSmooth_ += alpha * (rawVoice - voiceSmooth_);
        const float voice = std::clamp(voiceSmooth_, 0.0f, 1.0f);

        if (!calibrating) {
            if (rms < noise_) {
                noise_ += (voice < 0.35f ? 0.18f : 0.06f) * (rms - noise_);
            } else {
                const float riseDb = 20.0f * std::log10((rms + kEps) / (noise_ + kEps));
                const float rate = (riseDb < 1.5f && voice < 0.22f) ? 0.0008f : 0.000015f;
                noise_ += rate * (rms - noise_);
            }
            noise_ = std::clamp(noise_, 0.00015f, 0.060f);
        }

        const float currentNoiseDb = db(noise_);
        const float currentSnrDb = rmsDb - currentNoiseDb;
        const bool speechSafe = !calibrating &&
            (voice > 0.30f || (rmsDb > -62.0f && currentSnrDb > 1.8f));

        float targetSuppression;
        if (calibrating) targetSuppression = 0.94f;
        else if (aiApplied && speechSafe) targetSuppression = 0.985f;
        else if (aiApplied) targetSuppression = 0.92f + 0.06f * smooth(0.10f, 0.40f, voice);
        else if (speechSafe) targetSuppression = 0.88f + 0.12f * smooth(0.30f, 0.75f, voice);
        else targetSuppression = 0.58f + 0.22f * smooth(0.10f, 0.35f, voice);
        suppression_ += 0.10f * (targetSuppression - suppression_);

        float targetAgc = 1.0f;
        if (speechSafe && rms > 0.0006f)
            targetAgc = std::clamp(0.085f / rms, 0.92f, 3.20f);
        const float agcRate = targetAgc > agc_ ? 0.032f : 0.080f;
        agc_ += agcRate * (targetAgc - agc_);

        const float baseGain = suppression_ * agc_;
        const float gate = std::max(noise_ * 1.25f, 0.0010f);
        float processedPeak = 0.0f;
        for (int32_t i = 0; i < n; ++i) {
            const float x = static_cast<float>(s[i]) / 32768.0f;
            float g = baseGain;
            if (!speechSafe && voice < 0.18f && rmsDb <= currentNoiseDb + 1.2f && std::fabs(x) < gate)
                g *= aiApplied ? 0.90f : 0.72f;
            const float y = std::clamp(x * g, -0.965f, 0.965f);
            processedPeak = std::max(processedPeak, std::fabs(y));
            s[i] = static_cast<int16_t>(y * 32767.0f);
        }

        out.rmsDb = rmsDb;
        out.peak = std::max(processedPeak, inputPeak * 0.25f);
        out.voice = voice;
        out.noiseDb = currentNoiseDb;
        out.aiActive = aiApplied ? 1.0f : 0.0f;
        out.aiVad = aiVad_;
        out.aiMs = aiMsSmooth_;
        out.aiProfile = aiApplied ? static_cast<float>(requestedAiProfile) : 0.0f;
        return out;
    }

private:
    DenoiseState* rnnoise_ = nullptr;
    float rnIn_[kFrames]{};
    float rnOut_[kFrames]{};
    float prevIn_ = 0.0f, prevOut_ = 0.0f;
    float noise_ = 0.0010f, calibrationMin_ = 1.0f;
    int64_t calibrationSamples_ = 0;
    float suppression_ = 1.0f, agc_ = 1.0f, voiceSmooth_ = 0.0f;
    int32_t hangover_ = 0;
    float previousRmsDb_ = -120.0f;
    int32_t lastRequestedAiProfile_ = -1;
    bool aiDisabledByBudget_ = false;
    int32_t aiSlowFrames_ = 0;
    float aiVad_ = 0.0f;
    float aiMsSmooth_ = 0.0f;
};

class Engine {
public:
    aaudio_result_t open() {
        close();
        dsp_.reset();
        frames_.store(0);
        rms_.store(-120);
        peak_.store(0);
        voice_.store(0);
        noise_.store(-120);
        aiActive_.store(0);
        aiVad_.store(0);
        aiMs_.store(0);
        aiEffectiveProfile_.store(0);

        AAudioStreamBuilder* b = nullptr;
        auto r = AAudio_createStreamBuilder(&b);
        if (r != AAUDIO_OK || !b) return r;
        AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_INPUT);
        AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setChannelCount(b, kChannels);
        AAudioStreamBuilder_setSampleRate(b, kSampleRate);
        AAudioStreamBuilder_setFramesPerDataCallback(b, kFrames);
        AAudioStreamBuilder_setInputPreset(b, AAUDIO_INPUT_PRESET_VOICE_RECOGNITION);
        AAudioStreamBuilder_setDataCallback(b, callback, this);
        AAudioStreamBuilder_setErrorCallback(b, errorCallback, this);
        r = AAudioStreamBuilder_openStream(b, &stream_);
        AAudioStreamBuilder_delete(b);
        if (r != AAUDIO_OK || !stream_) {
            stream_ = nullptr;
            return r;
        }
        session_ = AAudioStream_getSessionId(stream_);
        return AAUDIO_OK;
    }

    aaudio_result_t start() {
        if (!stream_) return AAUDIO_ERROR_INVALID_STATE;
        running_.store(true);
        auto r = AAudioStream_requestStart(stream_);
        if (r != AAUDIO_OK) running_.store(false);
        return r;
    }

    void close() {
        running_.store(false);
        if (stream_) {
            AAudioStream_requestStop(stream_);
            AAudioStream_close(stream_);
            stream_ = nullptr;
        }
        session_ = AAUDIO_SESSION_ID_NONE;
    }

    void setAiProfile(int32_t profile) {
        aiProfile_.store(std::clamp(profile, kAiOff, kAiStrong));
    }

    int32_t session() const { return session_; }
    int64_t frames() const { return frames_.load(); }

    void stats(float* o) const {
        if (!o) return;
        o[0] = rms_.load();
        o[1] = peak_.load();
        o[2] = voice_.load();
        o[3] = noise_.load();
        const int x = stream_ ? AAudioStream_getXRunCount(stream_) : 0;
        o[4] = static_cast<float>(std::max(0, x));
        o[5] = aiActive_.load();
        o[6] = aiVad_.load();
        o[7] = aiMs_.load();
        o[8] = aiEffectiveProfile_.load();
    }

private:
    static aaudio_data_callback_result_t callback(
        AAudioStream*, void* u, void* data, int32_t n
    ) {
        auto* e = static_cast<Engine*>(u);
        if (!e || !e->running_.load()) return AAUDIO_CALLBACK_RESULT_STOP;
        const auto s = e->dsp_.process(
            static_cast<int16_t*>(data), n, e->aiProfile_.load()
        );
        e->rms_.store(s.rmsDb);
        e->peak_.store(s.peak);
        e->voice_.store(s.voice);
        e->noise_.store(s.noiseDb);
        e->aiActive_.store(s.aiActive);
        e->aiVad_.store(s.aiVad);
        e->aiMs_.store(s.aiMs);
        e->aiEffectiveProfile_.store(s.aiProfile);
        e->frames_.fetch_add(n);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void errorCallback(AAudioStream*, void*, aaudio_result_t) {}

    AAudioStream* stream_ = nullptr;
    int32_t session_ = AAUDIO_SESSION_ID_NONE;
    VoiceDsp dsp_;
    std::atomic<bool> running_{false};
    std::atomic<int32_t> aiProfile_{kAiBalanced};
    std::atomic<int64_t> frames_{0};
    std::atomic<float> rms_{-120}, peak_{0}, voice_{0}, noise_{-120};
    std::atomic<float> aiActive_{0}, aiVad_{0}, aiMs_{0}, aiEffectiveProfile_{0};
};

Engine g;
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeOpen(JNIEnv*, jobject) {
    return g.open();
}
extern "C" JNIEXPORT jint JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeGetSessionId(JNIEnv*, jobject) {
    return g.session();
}
extern "C" JNIEXPORT void JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeConfigurePlatformEffects(JNIEnv*, jobject, jboolean, jboolean) {}
extern "C" JNIEXPORT void JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeSetAiProfile(JNIEnv*, jobject, jint profile) {
    g.setAiProfile(profile);
}
extern "C" JNIEXPORT jint JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeStart(JNIEnv*, jobject) {
    return g.start();
}
extern "C" JNIEXPORT void JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeFillStats(JNIEnv* env, jobject, jfloatArray out) {
    if (!out || env->GetArrayLength(out) < 9) return;
    float v[9] = {-120, 0, 0, -120, 0, 0, 0, 0, 0};
    g.stats(v);
    env->SetFloatArrayRegion(out, 0, 9, v);
}
extern "C" JNIEXPORT jlong JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeGetFramesProcessed(JNIEnv*, jobject) {
    return g.frames();
}
extern "C" JNIEXPORT void JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeStop(JNIEnv*, jobject) {
    g.close();
}
