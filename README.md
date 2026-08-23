# ClearMic

ClearMic is an experimental Android microphone noise-reduction and voice optimization project focused on low-latency multiplayer gaming.

## Current milestone

**Milestone 1 — local audio engine**

The current build validates the foundations before any system-wide microphone routing is introduced:

- Android Foreground Service for microphone work
- `AudioRecord` at 48 kHz, mono, 10 ms processing frames
- Android `NoiseSuppressor`, `AcousticEchoCanceler`, and `AutomaticGainControl` when available
- low-allocation real-time capture loop
- lightweight DC blocker and soft noise gate placeholder
- live RMS/peak statistics in Jetpack Compose
- service notification with a stop action
- GitHub Actions debug APK build

> Important: this milestone processes ClearMic's own capture session. It does **not** yet inject processed audio into another game's microphone session. Android intentionally isolates microphone capture sessions, so the future system-wide bridge is a separate advanced layer.

## Architecture

```text
App / Compose UI
      |
GameMicController
      |
GameMicService (foreground)
      |
AudioEngine
      |-- AndroidPreProcessing (NS / AEC / AGC)
      |-- AudioProcessor
      |     `-- LightweightVoiceProcessor`
      |
AudioRuntime (state + meters)

Future layers:
      |-- NativeAudioEngine (C++ / NDK)
      |-- WebRTC / RNNoise DSP
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
- Kotlin / Compose Compiler plugin 2.3.21
- Compose BOM 2026.08.00

## Build on GitHub

Every push to `main` or `master` runs the Android build workflow. If successful, download the `ClearMic-debug` artifact from the Actions run.

## Test order

1. Install the debug APK.
2. Grant microphone access.
3. Tap **Ativar motor** while the app is visible.
4. Confirm the persistent ClearMic notification appears.
5. Speak and verify the dBFS/peak meter moves.
6. Check which device effects report `ON`.
7. Put ClearMic in the background and keep it running for a longer session.
8. Stop it using the app or notification action.

## Project rule

Stability and latency come before heavy AI features. System-wide routing will only be added after the local engine and background lifecycle are proven stable.
