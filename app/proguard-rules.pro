# JNA looks up its classes and native methods by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**

# UniFFI calls the generated bindings from native code.
-keep class io.github.techuouo520.folia.bridge.** { *; }
-keep class io.github.techuouo520.folia.AndroidContext { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Media3 builds the session and the notification from these types.
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class io.github.techuouo520.folia.PlaybackService { *; }
-keep class io.github.techuouo520.folia.FoliaPlayer { *; }

# rustls-platform-verifier calls this component through JNI.
-keep,includedescriptorclasses class org.rustls.platformverifier.** { *; }
