# ClearMic

ClearMic is an experimental Android microphone noise-reduction and voice optimization project focused on low-latency multiplayer gaming.

## Current milestone

**Milestone 3 — native audio engine + adaptive DSP foundation**

This build keeps the foreground/background lifecycle from Milestone 2 and moves the real-time path toward native code:

- AAudio input backend in C++/NDK at 48 kHz mono
- low-latency native callback with no heap allocation in the realtime path
- automatic fallback to the proven `AudioRecord` backend if native open/start fails
- Android `NoiseSuppressor`, `AcousticEchoCanceler`, and `AutomaticGainControl` retained when the device exposes them
- native high-pass/DC cleanup
- adaptive noise-floor estimator
- lightweight voice-activity estimate
- conservative adaptive expander/noise reduction
- gentle native voice leveling when Android AGC is unavailable
- safety limiter
- native diagnostics: captured frames, estimated noise floor, voice probability and xruns
- persistent foreground service, restart diagnostics, CPU/PSS telemetry and dark UI from Milestone 2
- permanent update-signing identity kept unchanged

> Important: ClearMic still processes its own capture path in this milestone. It does **not** yet expose a universal virtual microphone to arbitrary games. System-wide routing remains a separate advanced/privileged layer.

## Architecture

```text
App / Compose UI
      |
GameMicService (foreground + sticky recovery)
      |-- BackgroundSurvivalManager
      |-- BackgroundRuntime (CPU / RAM / restarts / battery status)
      |
AudioEngine (orchestrator)
      |-- NativeAudioBackend (preferred)
      |     |-- AAudio C++ capture callback
      |     `-- Native Adaptive DSP V1
      |
      |-- LegacyAudioBackend (safe fallback)
      |     `-- AudioRecord + LightweightVoiceProcessor
      |
      |-- AndroidPreProcessing (NS / AEC / AGC when available)
      `-- AudioRuntime (state + meters + diagnostics)

Next DSP stages:
      |-- WebRTC Audio Processing Module integration
      |-- RNNoise backend/profile after CPU + latency baseline
      |-- DeviceCompatibilityLayer
      `-- SystemAudioBridge (advanced / privileged path)
```

## Build stack

- Android Gradle Plugin 9.3.0
- Gradle 9.5.0 in CI
- JDK 17
- compileSdk 36
- targetSdk 36
- minSdk 28
- NDK 26.3.11579264
- CMake 3.22.1
- native build currently targets `arm64-v8a`
- Kotlin / Compose Compiler plugin 2.3.21
- Compose BOM 2025.08.00

## Signing

Release updates must keep the same permanent certificate. Private key material is intentionally excluded from Git. See [`SIGNING.md`](SIGNING.md) for the public certificate fingerprint and GitHub Actions secret names.

## Build on GitHub

Every push to `main` or `master` runs the Android build workflow. Pull requests also validate the debug APK. CI installs the Android SDK, NDK and CMake toolchain. If release-signing secrets are configured, CI additionally produces `ClearMic-signed-release`.

## Milestone 3 test order

1. Install the alpha03 build over the permanently signed alpha02 once the alpha03 is signed with the same update key.
2. Grant microphone and notification permissions if Android asks again.
3. Tap **Ativar motor** while the app is visible.
4. Confirm `Backend: AAudio C++`. If it shows `AudioRecord fallback`, capture still works but the native backend needs device-specific investigation.
5. Speak and confirm dBFS/peak react.
6. Confirm `Frames capturados` keeps increasing.
7. Watch `Voz detectada`, `Piso de ruído` and `XRuns` while speaking and while the room is quiet.
8. Compare CPU/PSS with the Milestone 2 baseline; the target is a meaningful reduction from the ~25% process CPU observed during the first test.
9. Put ClearMic in the background and run it alongside a game for a longer stability pass.
10. Stop from the app/notification and confirm it stays stopped.

## Project rule

Stability and latency come before heavy AI features. WebRTC/RNNoise will only be enabled after the native capture callback has a clean CPU/xrun/capture baseline on the target device.
