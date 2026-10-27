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
