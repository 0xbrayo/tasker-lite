# Tasker Lite — keep for release if minify is enabled later
-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
