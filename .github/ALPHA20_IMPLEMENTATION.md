# Alpha20 implementation

ClearMic 0.7.0-alpha20 introduces an experimental Shizuku AudioPolicy recorder injector while preserving the already-proven source-default Noise Suppression bridge as the fallback.

The daemon learns an external recorder UID from IAudioService, registers an AudioMixingRule with MIX_ROLE_INJECTOR + RULE_MATCH_UID, creates the AudioTrack source for that recorder mix, captures the physical microphone separately as shell, processes 48 kHz mono 10 ms PCM frames through the dedicated RNNoise streaming JNI engine, and writes the processed PCM into the injector track.

The UI exposes AI_ROUTE states (ARMED, INJECTING, FALLBACK), target UID/package, capture source, injected frame count, RNNoise VAD and processing time. The first target session may only teach the UID; a subsequent recorder session is the strongest device test because the AudioPolicy is already registered before that AudioRecord starts.

No system/vendor files are modified. Any injector setup or runtime failure tears the experimental route down and leaves native source-default NS available.
