# Release hardening for the app's two native entry surfaces.
# JNI method names must remain stable; unrelated Java implementation can be
# optimized/obfuscated by R8.
-keep class com.xyether.upscaler.MainActivity {
    public <init>();
}
-keepclassmembers,allowoptimization class com.xyether.upscaler.MainActivity {
    native <methods>;
}

# XML inflation requires the custom comparison view and both constructors.
-keep class com.xyether.upscaler.SyncZoomImageView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Native libraries are loaded explicitly by MainActivity's static initializer.
-keepclassmembers class com.xyether.upscaler.MainActivity {
    static <methods>;
}
