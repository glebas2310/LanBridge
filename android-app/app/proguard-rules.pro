# LanBridge Proguard Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers enum * { *; }
-dontwarn okio.**
-dontwarn okhttp3.**
