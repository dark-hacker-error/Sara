# Keep OkHttp / Okio internals used reflectively.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Our own model classes are referenced by name in JSON protocol payloads.
-keep class com.sara.assistant.data.** { *; }
-keep class com.sara.assistant.live.** { *; }
-keep class com.sara.assistant.backend.** { *; }
