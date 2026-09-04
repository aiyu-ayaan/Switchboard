# BouncyCastle registers providers reflectively.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp ships optional platform integrations that are absent at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
