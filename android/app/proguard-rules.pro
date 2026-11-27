# Keep class & method names readable while still running full R8 dead-code and resource shrinking.
# Prevents reflection/ServiceLoader issues in Firebase/gRPC/Play Services and avoids obfuscation
# false positives in Android Play Protect & device security scanners on sideloaded beta APKs.
-dontobfuscate
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,*Annotation*

# Keep Aravia manifest-declared components & protocol enums
-keep class app.aravia.MainActivity { *; }
-keep class app.aravia.band.BandService { *; }
-keep class app.aravia.training.RestAlarmReceiver { *; }
-keepclassmembers enum app.aravia.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Firebase Auth, Firestore, Play Services, AdMob, Health Connect & gRPC ServiceLoader providers intact
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.ads.** { *; }
-keep class androidx.health.connect.** { *; }
-keep class io.grpc.** { *; }
-keep class com.google.protobuf.** { *; }

# Defensive keeps for MapLibre JNI callbacks & GeoJSON
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**

# Defensive dontwarn for optional OkHttp/Conscrypt/gRPC/annotation classes
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.grpc.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
