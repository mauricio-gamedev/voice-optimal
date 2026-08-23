# Keep JNI bridge names stable because native symbols use the Java class/method names.
-keep class io.github.astromg01.clearmic.audio.NativeAudioBridge {
    native <methods>;
}
