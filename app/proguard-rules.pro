# kotlinx.serialization ships its own R8 rules for @Serializable classes (DTOs in core and the
# navigation routes in the app). OkHttp, Coil and AndroidX ship consumer rules as well.

# Keep line numbers so crash reports from a personal device stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
