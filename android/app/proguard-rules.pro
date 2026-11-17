# Keep OkHttp / Okio internals used reflectively.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink ships as a transitive dependency of androidx.security:security-crypto (our
# EncryptedSharedPreferences store). It references Error Prone annotations that only exist at
# compile time, so R8 must not treat them as missing runtime classes.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.json.**

# Our own model classes are referenced by name in JSON protocol payloads.
-keep class com.sara.assistant.data.** { *; }
-keep class com.sara.assistant.live.** { *; }
-keep class com.sara.assistant.backend.** { *; }
