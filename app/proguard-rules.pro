# kotlinx.serialization ships its own R8 rules for @Serializable classes (DTOs in core and the
# navigation routes in the app). OkHttp, Coil and AndroidX ship consumer rules as well.

# Keep line numbers so crash reports from a personal device stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Tink (behind androidx.security:security-crypto) references Error Prone annotations that are only
# needed at compile time and are not on the runtime classpath.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# The callbacks native code finds by name (GetMethodID in app/src/main/cpp/jni/rip_jni.c). Nothing
# in Kotlin calls them, so R8 would drop them; kept whole, so a changed signature needs no change
# here. scripts/check_jni_keep.py checks the release APK for every method the C code looks up.
-keep interface be.discrip.rip.NativeBlocks { <methods>; }
-keep interface be.discrip.rip.NativeFile { <methods>; }
-keep interface be.discrip.rip.NativeProgress { <methods>; }
-keep class be.discrip.rip.NativeFolder { <methods>; }

# smbj (the NAS client) reports connection events through mbassador, which finds the receiving
# methods by their @Handler annotation and calls them, and creates its own handler invocations,
# by reflection.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class com.hierynomus.** {
    @net.engio.mbassy.listener.Handler <methods>;
}
-keep class net.engio.mbassy.** { *; }
# Never used on Android: smbj's Kerberos authenticator (it signs in with NTLM there) and
# mbassador's optional expression-language filters.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
