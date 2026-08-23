#include <aaudio/AAudio.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>

namespace {

constexpr int32_t kRequestedSampleRate = 48000;
constexpr int32_t kRequestedChannelCount = 1;
constexpr int32_t kFramesPerCallback = 480; // 10 ms at 48 kHz when honored by the device.
constexpr float kEpsilon = 1.0e-7f;

inline float dbFromLinear(float value) {
    if (value <= kEpsilon) return -120.0f;
    return std::max(-120.0f, 20.0f * std::log10(value));
}

inline float smoothStep(float edge0, float edge1, float x) {
    if (edge1 <= edge0) return x >= edge1 ? 1.0f : 0.0f;
    float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

struct DspFrameStats {
    float rmsDb = -120.0f;
    float peak = 0.0f;
    float voiceProbability = 0.0f;
    float noiseFloorDb = -120.0f;
};

class AdaptiveVoiceDsp {
public:
    void reset() {
        previousInput_ = 0.0f;
        previousOutput_ = 0.0f;
        noiseFloorRms_ = 0.0040f;
        suppressionGain_ = 1.0f;
        agcGain_ = 1.0f;
    }

    void configurePlatformEffects(bool noiseSuppressorEnabled, bool agcEnabled) {
        platformNoiseSuppressor_ = noiseSuppressorEnabled;
        platformAgc_ = agcEnabled;
    }

    DspFrameStats process(int16_t* samples, int32_t frames) {
        DspFrameStats stats;
        if (samples == nullptr || frames <= 0) return stats;

        double sumSquares = 0.0;
        float peak = 0.0f;

        // Pass 1: DC/high-pass cleanup. This is deliberately tiny and allocation-free.
        for (int32_t i = 0; i < frames; ++i) {
            const float x = static_cast<float>(samples[i]) / 32768.0f;
            const float y = x - previousInput_ + 0.995f * previousOutput_;
            previousInput_ = x;
            previousOutput_ = y;

            const float clipped = std::clamp(y, -1.0f, 1.0f);
            peak = std::max(peak, std::fabs(clipped));
            sumSquares += static_cast<double>(clipped) * static_cast<double>(clipped);
            samples[i] = static_cast<int16_t>(clipped * 32767.0f);
        }

        const float rms = static_cast<float>(std::sqrt(sumSquares / static_cast<double>(frames)));
        const float rmsDb = dbFromLinear(rms);
        const float previousNoise = std::max(noiseFloorRms_, 0.00015f);
        const float snrDb = 20.0f * std::log10((rms + kEpsilon) / (previousNoise + kEpsilon));

        const float snrVoice = smoothStep(2.0f, 15.0f, snrDb);
        const float levelVoice = smoothStep(-58.0f, -30.0f, rmsDb);
        const float voiceProbability = std::clamp(0.68f * snrVoice + 0.32f * levelVoice, 0.0f, 1.0f);

        // Learn the room floor mostly while speech is absent. The very slow speech path
        // prevents the estimator from becoming permanently stale after route changes.
        const float noiseLearn = voiceProbability < 0.35f ? 0.035f : 0.0015f;
        noiseFloorRms_ = std::clamp(
            noiseFloorRms_ + noiseLearn * (rms - noiseFloorRms_),
            0.00015f,
            0.12f
        );

        // If the vendor NS is already active, stay conservative to avoid robotic voice.
        const float suppressionFloor = platformNoiseSuppressor_ ? 0.72f : 0.34f;
        const float targetSuppression = suppressionFloor +
            (1.0f - suppressionFloor) * smoothStep(0.20f, 0.72f, voiceProbability);
        suppressionGain_ += 0.16f * (targetSuppression - suppressionGain_);

        float targetAgc = 1.0f;
        if (!platformAgc_ && voiceProbability > 0.45f && rms > 0.002f) {
            // Gentle voice leveling: target about -19 dBFS, with conservative headroom.
            targetAgc = std::clamp(0.112f / rms, 0.82f, 2.10f);
        }
        agcGain_ += 0.055f * (targetAgc - agcGain_);

        const float finalGain = suppressionGain_ * agcGain_;
        float processedPeak = 0.0f;

        // Pass 2: adaptive expander/leveling + hard safety limiter.
        const float sampleGate = std::max(noiseFloorRms_ * 1.45f, 0.0015f);
        for (int32_t i = 0; i < frames; ++i) {
            float x = static_cast<float>(samples[i]) / 32768.0f;
            float localGain = finalGain;

            if (!platformNoiseSuppressor_ && voiceProbability < 0.48f && std::fabs(x) < sampleGate) {
                localGain *= 0.58f;
            }

            float y = x * localGain;
            y = std::clamp(y, -0.965f, 0.965f);
            processedPeak = std::max(processedPeak, std::fabs(y));
            samples[i] = static_cast<int16_t>(y * 32767.0f);
        }

        stats.rmsDb = rmsDb;
        stats.peak = processedPeak;
        stats.voiceProbability = voiceProbability;
        stats.noiseFloorDb = dbFromLinear(noiseFloorRms_);
        return stats;
    }

private:
    float previousInput_ = 0.0f;
    float previousOutput_ = 0.0f;
    float noiseFloorRms_ = 0.0040f;
    float suppressionGain_ = 1.0f;
    float agcGain_ = 1.0f;
    bool platformNoiseSuppressor_ = false;
    bool platformAgc_ = false;
};

class NativeAudioEngine {
public:
    aaudio_result_t open() {
        close();
        dsp_.reset();
        framesProcessed_.store(0, std::memory_order_relaxed);
        rmsDb_.store(-120.0f, std::memory_order_relaxed);
        peak_.store(0.0f, std::memory_order_relaxed);
        voiceProbability_.store(0.0f, std::memory_order_relaxed);
        noiseFloorDb_.store(-120.0f, std::memory_order_relaxed);
        lastError_.store(AAUDIO_OK, std::memory_order_relaxed);

        AAudioStreamBuilder* builder = nullptr;
        aaudio_result_t result = AAudio_createStreamBuilder(&builder);
        if (result != AAUDIO_OK || builder == nullptr) return result;

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setChannelCount(builder, kRequestedChannelCount);
        AAudioStreamBuilder_setSampleRate(builder, kRequestedSampleRate);
        AAudioStreamBuilder_setFramesPerDataCallback(builder, kFramesPerCallback);
        AAudioStreamBuilder_setInputPreset(builder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
        AAudioStreamBuilder_setSessionId(builder, AAUDIO_SESSION_ID_ALLOCATE);
        AAudioStreamBuilder_setDataCallback(builder, dataCallback, this);
        AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);

        result = AAudioStreamBuilder_openStream(builder, &stream_);
        AAudioStreamBuilder_delete(builder);

        if (result != AAUDIO_OK || stream_ == nullptr) {
            stream_ = nullptr;
            return result;
        }

        sessionId_ = AAudioStream_getSessionId(stream_);
        return AAUDIO_OK;
    }

    aaudio_result_t start() {
        if (stream_ == nullptr) return AAUDIO_ERROR_INVALID_STATE;
        running_.store(true, std::memory_order_release);
        const aaudio_result_t result = AAudioStream_requestStart(stream_);
        if (result != AAUDIO_OK) running_.store(false, std::memory_order_release);
        return result;
    }

    void configurePlatformEffects(bool nsEnabled, bool agcEnabled) {
        dsp_.configurePlatformEffects(nsEnabled, agcEnabled);
    }

    void close() {
        running_.store(false, std::memory_order_release);
        if (stream_ != nullptr) {
            AAudioStream_requestStop(stream_);
            AAudioStream_close(stream_);
            stream_ = nullptr;
        }
        sessionId_ = AAUDIO_SESSION_ID_NONE;
    }

    int32_t sessionId() const {
        return sessionId_;
    }

    int64_t framesProcessed() const {
        return framesProcessed_.load(std::memory_order_relaxed);
    }

    void getStats(float* outValues) const {
        if (outValues == nullptr) return;
        outValues[0] = rmsDb_.load(std::memory_order_relaxed);
        outValues[1] = peak_.load(std::memory_order_relaxed);
        outValues[2] = voiceProbability_.load(std::memory_order_relaxed);
        outValues[3] = noiseFloorDb_.load(std::memory_order_relaxed);

        int32_t xruns = 0;
        if (stream_ != nullptr) {
            xruns = AAudioStream_getXRunCount(stream_);
            if (xruns < 0) xruns = 0;
        }
        outValues[4] = static_cast<float>(xruns);
    }

private:
    static aaudio_data_callback_result_t dataCallback(
        AAudioStream*,
        void* userData,
        void* audioData,
        int32_t numFrames
    ) {
        auto* self = static_cast<NativeAudioEngine*>(userData);
        if (self == nullptr || !self->running_.load(std::memory_order_acquire)) {
            return AAUDIO_CALLBACK_RESULT_STOP;
        }

        auto* samples = static_cast<int16_t*>(audioData);
        const DspFrameStats stats = self->dsp_.process(samples, numFrames);
        self->rmsDb_.store(stats.rmsDb, std::memory_order_relaxed);
        self->peak_.store(stats.peak, std::memory_order_relaxed);
        self->voiceProbability_.store(stats.voiceProbability, std::memory_order_relaxed);
        self->noiseFloorDb_.store(stats.noiseFloorDb, std::memory_order_relaxed);
        self->framesProcessed_.fetch_add(numFrames, std::memory_order_relaxed);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void errorCallback(AAudioStream*, void* userData, aaudio_result_t error) {
        auto* self = static_cast<NativeAudioEngine*>(userData);
        if (self != nullptr) self->lastError_.store(error, std::memory_order_relaxed);
    }

    AAudioStream* stream_ = nullptr;
    int32_t sessionId_ = AAUDIO_SESSION_ID_NONE;
    AdaptiveVoiceDsp dsp_;
    std::atomic<bool> running_{false};
    std::atomic<int64_t> framesProcessed_{0};
    std::atomic<float> rmsDb_{-120.0f};
    std::atomic<float> peak_{0.0f};
    std::atomic<float> voiceProbability_{0.0f};
    std::atomic<float> noiseFloorDb_{-120.0f};
    std::atomic<int32_t> lastError_{AAUDIO_OK};
};

NativeAudioEngine gEngine;

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeOpen(JNIEnv*, jobject) {
    return static_cast<jint>(gEngine.open());
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeGetSessionId(JNIEnv*, jobject) {
    return static_cast<jint>(gEngine.sessionId());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeConfigurePlatformEffects(
    JNIEnv*, jobject, jboolean nsEnabled, jboolean agcEnabled
) {
    gEngine.configurePlatformEffects(nsEnabled == JNI_TRUE, agcEnabled == JNI_TRUE);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeStart(JNIEnv*, jobject) {
    return static_cast<jint>(gEngine.start());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeGetStats(JNIEnv* env, jobject) {
    float values[5] = {-120.0f, 0.0f, 0.0f, -120.0f, 0.0f};
    gEngine.getStats(values);
    jfloatArray output = env->NewFloatArray(5);
    if (output != nullptr) env->SetFloatArrayRegion(output, 0, 5, values);
    return output;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeGetFramesProcessed(JNIEnv*, jobject) {
    return static_cast<jlong>(gEngine.framesProcessed());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeStop(JNIEnv*, jobject) {
    gEngine.close();
}
