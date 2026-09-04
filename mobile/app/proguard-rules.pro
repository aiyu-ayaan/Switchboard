# BouncyCastle registers providers reflectively.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp ships optional platform integrations that are absent at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Error Prone annotations are compile-time only; classes are absent at runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
