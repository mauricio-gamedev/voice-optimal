# ClearMic

ClearMic is an experimental Android microphone noise-reduction and voice optimization project focused on low-latency multiplayer gaming.

## Current milestone

**Milestone 2 — background survival and update identity**

The current build keeps the Milestone 1 local audio engine and adds controlled background resilience:

- Android Foreground Service for microphone work
- `AudioRecord` at 48 kHz, mono, 10 ms processing frames
- Android `NoiseSuppressor`, `AcousticEchoCanceler`, and `AutomaticGainControl` when available
- low-allocation real-time capture loop
- lightweight DC blocker and soft noise gate placeholder
- persistent user intent for background execution
- `START_STICKY` recovery when Android recreates the service
- restart counter, service uptime, CPU and PSS memory diagnostics
- battery-optimization status and direct settings shortcut
- protection against duplicate engine starts
- permanent dark UI theme
- permanent release update-signing identity prepared outside the public repository
- GitHub Actions debug APK build and optional signed release build

> Important: this milestone processes ClearMic's own capture session. It does **not** yet inject processed audio into another game's microphone session. Android intentionally isolates microphone capture sessions, so the future system-wide bridge is a separate advanced layer.

## Architecture

```text
App / Compose UI
      |
GameMicService (foreground + sticky recovery)
      |-- BackgroundSurvivalManager
      |-- BackgroundRuntime (CPU / RAM / restarts / battery status)
      |
AudioEngine
      |-- AndroidPreProcessing (NS / AEC / AGC)
      |-- AudioProcessor
      |     `-- LightweightVoiceProcessor
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
- Compose BOM 2025.08.00

## Signing

Release updates must keep the same permanent certificate. Private key material is intentionally excluded from Git. See [`SIGNING.md`](SIGNING.md) for the public certificate fingerprint and GitHub Actions secret names.

## Build on GitHub

Every push to `main` or `master` runs the Android build workflow. Pull requests also validate the debug APK. If release-signing secrets are configured, CI additionally produces `ClearMic-signed-release`.

## Milestone 2 test order

1. Install the debug APK.
2. Grant microphone access and notifications.
3. Tap **Ativar motor** while the app is visible.
4. Confirm the persistent ClearMic notification appears.
5. Speak and verify the dBFS/peak meter moves.
6. Check which device effects report `ON`.
7. Put ClearMic in the background for a longer session.
8. Reopen it and inspect service uptime, CPU, PSS memory and restart count.
9. Open **Ajustar bateria** and, when desired for testing, exempt ClearMic from battery optimization.
10. Stop it using the app or notification action and confirm it remains stopped.

## Project rule

Stability and latency come before heavy AI features. System-wide routing will only be added after the local engine and background lifecycle are proven stable.
