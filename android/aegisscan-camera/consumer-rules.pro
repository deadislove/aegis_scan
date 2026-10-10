# AegisScan: strip all android.util.Log calls from consumers' release builds
# as defense in depth (the SDK itself never logs recognized text or PANs).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
