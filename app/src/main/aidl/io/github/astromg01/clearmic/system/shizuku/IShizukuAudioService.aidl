package io.github.astromg01.clearmic.system.shizuku;

interface IShizukuAudioService {
    String getIdentity();
    String runProbe(String probeKey);
    String getActiveRecordingSnapshot();
    String setGameEnhancementProfile(String profile);
    String setGameBridgeEnabled(boolean enabled);
    String getGameBridgeStatus();
}
