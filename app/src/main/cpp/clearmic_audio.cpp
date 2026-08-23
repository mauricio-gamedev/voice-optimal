#include <aaudio/AAudio.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>

namespace {
constexpr int32_t kSampleRate = 48000;
constexpr int32_t kChannels = 1;
constexpr int32_t kFrames = 480;
constexpr int64_t kCalibrationSamples = 72000;
constexpr float kEps = 1.0e-7f;

inline float db(float v) {
    if (v <= kEps) return -120.0f;
    return std::max(-120.0f, 20.0f * std::log10(v));
}
inline float smooth(float a, float b, float x) {
    if (b <= a) return x >= b ? 1.0f : 0.0f;
    float t = std::clamp((x - a) / (b - a), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

struct Stats {
    float rmsDb = -120.0f;
    float peak = 0.0f;
    float voice = 0.0f;
    float noiseDb = -120.0f;
};

class VoiceDsp {
public:
    void reset() {
        prevIn_ = prevOut_ = 0.0f;
        noise_ = 0.0010f;
        calibrationMin_ = 1.0f;
        calibrationSamples_ = 0;
        suppression_ = agc_ = 1.0f;
        voiceSmooth_ = 0.0f;
        hangover_ = 0;
        previousRmsDb_ = -120.0f;
    }

    Stats process(int16_t* s, int32_t n) {
        Stats out;
        if (!s || n <= 0) return out;

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

        // Alpha06: weak/quiet speech must never be treated as definite noise.
        const float snrVoice = smooth(1.5f, 9.0f, snrDb);
        const float levelVoice = smooth(-61.0f, -38.0f, rmsDb);
        const float onsetVoice = smooth(0.8f, 5.0f, onsetDb);
        float rawVoice = calibrating
            ? 0.30f * levelVoice
            : std::clamp(0.52f * snrVoice + 0.35f * levelVoice + 0.13f * onsetVoice, 0.0f, 1.0f);

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
                // Only let the floor rise when the signal hugs the current floor.
                // A 2+ dB rise is frozen so normal speech cannot become "noise".
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
            // Critical safeguard: uncertain/weak speech is never sample-gated.
            if (!speechSafe && voice < 0.18f && rmsDb <= currentNoiseDb + 1.2f && std::fabs(x) < gate)
                g *= 0.72f;
            float y = std::clamp(x * g, -0.965f, 0.965f);
            processedPeak = std::max(processedPeak, std::fabs(y));
            s[i] = static_cast<int16_t>(y * 32767.0f);
        }

        out.rmsDb = rmsDb;
        out.peak = std::max(processedPeak, inputPeak * 0.25f);
        out.voice = voice;
        out.noiseDb = currentNoiseDb;
        return out;
    }

private:
    float prevIn_ = 0.0f, prevOut_ = 0.0f;
    float noise_ = 0.0010f, calibrationMin_ = 1.0f;
    int64_t calibrationSamples_ = 0;
    float suppression_ = 1.0f, agc_ = 1.0f, voiceSmooth_ = 0.0f;
    int32_t hangover_ = 0;
    float previousRmsDb_ = -120.0f;
};

class Engine {
public:
    aaudio_result_t open() {
        close(); dsp_.reset(); frames_.store(0); rms_.store(-120); peak_.store(0); voice_.store(0); noise_.store(-120);
        AAudioStreamBuilder* b = nullptr;
        auto r = AAudio_createStreamBuilder(&b); if (r != AAUDIO_OK || !b) return r;
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
        r = AAudioStreamBuilder_openStream(b, &stream_); AAudioStreamBuilder_delete(b);
        if (r != AAUDIO_OK || !stream_) { stream_ = nullptr; return r; }
        session_ = AAudioStream_getSessionId(stream_); return AAUDIO_OK;
    }
    aaudio_result_t start() { if (!stream_) return AAUDIO_ERROR_INVALID_STATE; running_.store(true); auto r=AAudioStream_requestStart(stream_); if(r!=AAUDIO_OK) running_.store(false); return r; }
    void close() { running_.store(false); if(stream_){ AAudioStream_requestStop(stream_); AAudioStream_close(stream_); stream_=nullptr;} session_=AAUDIO_SESSION_ID_NONE; }
    int32_t session() const { return session_; }
    int64_t frames() const { return frames_.load(); }
    void stats(float* o) const { if(!o)return; o[0]=rms_.load();o[1]=peak_.load();o[2]=voice_.load();o[3]=noise_.load();int x=stream_?AAudioStream_getXRunCount(stream_):0;o[4]=static_cast<float>(std::max(0,x)); }
private:
    static aaudio_data_callback_result_t callback(AAudioStream*,void* u,void* data,int32_t n){auto* e=static_cast<Engine*>(u);if(!e||!e->running_.load())return AAUDIO_CALLBACK_RESULT_STOP;auto s=e->dsp_.process(static_cast<int16_t*>(data),n);e->rms_.store(s.rmsDb);e->peak_.store(s.peak);e->voice_.store(s.voice);e->noise_.store(s.noiseDb);e->frames_.fetch_add(n);return AAUDIO_CALLBACK_RESULT_CONTINUE;}
    static void errorCallback(AAudioStream*,void*,aaudio_result_t){}
    AAudioStream* stream_=nullptr; int32_t session_=AAUDIO_SESSION_ID_NONE; VoiceDsp dsp_; std::atomic<bool> running_{false}; std::atomic<int64_t> frames_{0}; std::atomic<float> rms_{-120},peak_{0},voice_{0},noise_{-120};
};
Engine g;
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeOpen(JNIEnv*,jobject){return g.open();}
extern "C" JNIEXPORT jint JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeGetSessionId(JNIEnv*,jobject){return g.session();}
extern "C" JNIEXPORT void JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeConfigurePlatformEffects(JNIEnv*,jobject,jboolean,jboolean){}
extern "C" JNIEXPORT jint JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeStart(JNIEnv*,jobject){return g.start();}
extern "C" JNIEXPORT void JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeFillStats(JNIEnv* env,jobject,jfloatArray out){if(!out||env->GetArrayLength(out)<5)return;float v[5]={-120,0,0,-120,0};g.stats(v);env->SetFloatArrayRegion(out,0,5,v);}
extern "C" JNIEXPORT jlong JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeGetFramesProcessed(JNIEnv*,jobject){return g.frames();}
extern "C" JNIEXPORT void JNICALL Java_io_github_astromg01_clearmic_audio_NativeAudioBridge_nativeStop(JNIEnv*,jobject){g.close();}
