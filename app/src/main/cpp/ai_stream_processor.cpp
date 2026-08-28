#include <jni.h>
#include <rnnoise.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <ctime>
#include <new>

namespace {
constexpr int kFrame = 480;
constexpr float kLimiter = 0.965f;

inline float elapsedMs(const timespec& begin, const timespec& end) {
    const int64_t sec = static_cast<int64_t>(end.tv_sec) - static_cast<int64_t>(begin.tv_sec);
    const int64_t nsec = static_cast<int64_t>(end.tv_nsec) - static_cast<int64_t>(begin.tv_nsec);
    return static_cast<float>(sec * 1000.0 + nsec / 1000000.0);
}

struct StreamProcessor {
    DenoiseState* rn = nullptr;
    float in[kFrame]{};
    float out[kFrame]{};
    float prevIn = 0.0f;
    float prevOut = 0.0f;
    float vadSmooth = 0.0f;
    float msSmooth = 0.0f;
    float gainSmooth = 1.0f;
    int slowFrames = 0;
    bool budgetFallback = false;
    int lastProfile = -1;

    StreamProcessor() {
        rn = rnnoise_create(nullptr);
    }

    ~StreamProcessor() {
        if (rn) rnnoise_destroy(rn);
    }

    int process(int16_t* samples, int profile, float stats[4]) {
        if (!samples || !rn) return -1;
        profile = std::clamp(profile, 1, 3);
        if (profile != lastProfile) {
            lastProfile = profile;
            rnnoise_init(rn, nullptr);
            vadSmooth = 0.0f;
            msSmooth = 0.0f;
            slowFrames = 0;
            budgetFallback = false;
        }

        // Very cheap DC/high-pass stage before the neural denoiser.
        double sum = 0.0;
        for (int i = 0; i < kFrame; ++i) {
            const float x = static_cast<float>(samples[i]) / 32768.0f;
            const float hp = x - prevIn + 0.995f * prevOut;
            prevIn = x;
            prevOut = hp;
            const float clipped = std::clamp(hp, -1.0f, 1.0f);
            in[i] = clipped * 32768.0f;
            sum += static_cast<double>(clipped) * clipped;
        }

        int aiApplied = 0;
        if (!budgetFallback) {
            timespec begin{}, end{};
            clock_gettime(CLOCK_MONOTONIC, &begin);
            const float vad = rnnoise_process_frame(rn, out, in);
            clock_gettime(CLOCK_MONOTONIC, &end);
            const float ms = std::max(0.0f, elapsedMs(begin, end));
            msSmooth = msSmooth <= 0.0f ? ms : msSmooth + 0.12f * (ms - msSmooth);
            vadSmooth += 0.28f * (std::clamp(vad, 0.0f, 1.0f) - vadSmooth);

            if (ms > 8.5f || msSmooth > 8.5f) ++slowFrames;
            else slowFrames = std::max(0, slowFrames - 1);
            if (slowFrames >= 3) budgetFallback = true;

            if (!budgetFallback) aiApplied = 1;
        }

        const float wet = profile == 1 ? 0.35f : (profile == 2 ? 0.70f : 1.0f);
        const float rms = static_cast<float>(std::sqrt(sum / kFrame));
        float targetGain = 1.0f;
        if (rms > 0.001f && vadSmooth > 0.35f) {
            targetGain = std::clamp(0.075f / rms, 0.92f, profile == 3 ? 2.6f : 2.1f);
        }
        gainSmooth += (targetGain > gainSmooth ? 0.025f : 0.07f) * (targetGain - gainSmooth);

        for (int i = 0; i < kFrame; ++i) {
            float value = in[i];
            if (aiApplied) value += wet * (std::clamp(out[i], -32768.0f, 32767.0f) - in[i]);
            value = (value / 32768.0f) * gainSmooth;
            value = std::clamp(value, -kLimiter, kLimiter);
            samples[i] = static_cast<int16_t>(value * 32767.0f);
        }

        stats[0] = aiApplied ? 1.0f : 0.0f;
        stats[1] = vadSmooth;
        stats[2] = msSmooth;
        stats[3] = aiApplied ? static_cast<float>(profile) : 0.0f;
        return aiApplied;
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_astromg01_clearmic_audio_NativeAiProcessor_nativeCreateProcessor(
        JNIEnv*, jclass) {
    auto* processor = new (std::nothrow) StreamProcessor();
    if (!processor || !processor->rn) {
        delete processor;
        return 0;
    }
    return reinterpret_cast<jlong>(processor);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_astromg01_clearmic_audio_NativeAiProcessor_nativeProcessFrame(
        JNIEnv* env, jclass, jlong handle, jshortArray frame, jint profile, jfloatArray statsArray) {
    if (!handle || !frame || !statsArray) return -1;
    if (env->GetArrayLength(frame) != kFrame || env->GetArrayLength(statsArray) < 4) return -2;
    auto* processor = reinterpret_cast<StreamProcessor*>(handle);

    jboolean copied = JNI_FALSE;
    jshort* samples = env->GetShortArrayElements(frame, &copied);
    if (!samples) return -3;
    float stats[4] = {0, 0, 0, 0};
    const int result = processor->process(reinterpret_cast<int16_t*>(samples), profile, stats);
    env->ReleaseShortArrayElements(frame, samples, 0);
    env->SetFloatArrayRegion(statsArray, 0, 4, stats);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_astromg01_clearmic_audio_NativeAiProcessor_nativeDestroyProcessor(
        JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<StreamProcessor*>(handle);
}
