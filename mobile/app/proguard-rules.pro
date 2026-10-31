# R8/ProGuard rules for the release build.

# JNI: the Rust core resolves these by name (Java_com_aiyu_switchboard_core_NativeCore_*).
-keepclasseswithmembernames class com.aiyu.switchboard.core.NativeCore {
    native <methods>;
}

# kotlinx.serialization ships its own consumer rules; keep the sealed wire types' names stable.
-keep,includedescriptorclasses class com.aiyu.switchboard.net.**$$serializer { *; }
-keepclassmembers class com.aiyu.switchboard.** { *** Companion; }
