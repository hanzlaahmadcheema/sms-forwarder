# ProGuard & R8 configuration for Personal Android SMS Forwarder
# Hardened for release builds with complete logging stripping and reflection safety

# 1. Strip Timber logging calls completely in release builds
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** log(...);
}

-assumenosideeffects class timber.log.Timber$Tree {
    public *** v(...);
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
    public *** wtf(...);
    public *** log(...);
}

# 2. Keep Room entities, DAOs, and Database classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# 3. Keep KotlinX Serialization classes and companion serializers
-keepattributes *Annotation*,InnerClasses,Signature
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowobfuscation,allowoptimization class * {
    @kotlinx.serialization.Serializable class *;
}

# 4. Keep Domain Models and Enums
-keep class com.smsforwarder.app.domain.model.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 5. Keep WorkManager Worker Constructors
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 6. Keep AndroidX Security Crypto MasterKey and Keystore components
-keep class androidx.security.crypto.** { *; }

# 7. Keep OkHttp and Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# 8. Suppress warnings for optional Tink dependencies
-dontwarn com.google.api.client.http.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.joda.time.**
