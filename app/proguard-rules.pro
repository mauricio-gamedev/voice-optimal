# Native symbols use this exact Java/Kotlin class and method names.
-keep class io.github.astromg01.clearmic.audio.NativeAudioBridge {
    *;
}

# Shizuku loads this class by name inside app_process/UserService.
-keep class io.github.astromg01.clearmic.system.shizuku.ShizukuAudioUserService {
    public <init>();
    public <init>(android.content.Context);
    *;
}

-keep class io.github.astromg01.clearmic.system.shizuku.IShizukuAudioService$Stub { *; }
