# The Go core. gomobile's generated Java is called from native code by name
# (JNI), and calls back into Kotlin classes that implement its interfaces
# (Progress, Protector, Namer, ReachProgress, PinProgress) the same way.
# R8 renaming or dropping any of it is a crash the first time Go reaches for
# a method that is no longer there.
-keep class go.** { *; }
-keep class mirage.** { *; }
-keep class * implements mirage.Progress { *; }
-keep class * implements mirage.Protector { *; }
-keep class * implements mirage.Namer { *; }
-keep class * implements mirage.ReachProgress { *; }
-keep class * implements mirage.PinProgress { *; }
-keep class * implements mirage.ConfigsProgress { *; }

# Stack traces that still say where they came from.
-keepattributes SourceFile,LineNumberTable

# Tink (under androidx.security) names annotation classes it does not ship.
# They are compile-time only; R8 is told not to look for them.
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn javax.annotation.concurrent.GuardedBy
