# ClearMic 0.7.0-alpha20 — AI System Injector

Experimental Shizuku AudioPolicy injector bridge. This build preserves the verified SourceDefaultEffect noise-suppression path as a fallback while attempting to route a target app's AudioRecord through an Android AudioMix recorder-injector policy. The Shizuku UserService captures the physical microphone separately, runs the existing RNNoise/VoiceDsp pipeline on 48 kHz mono 10 ms frames, and writes the processed PCM to the AudioTrack source associated with the recorder mix.

The bridge is intentionally fail-safe: if AudioPolicy registration, native DSP loading, physical-mic capture, or injector playback fails, the existing source-default NS remains active and the status reports the exact failing stage. No /system or /vendor files are modified.
