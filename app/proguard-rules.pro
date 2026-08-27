# BitPerfect ProGuard Rules
# ========================
# These rules ensure native JNI methods, model classes, and services
# are preserved during code shrinking and obfuscation.

# === JNI Native Methods ===
# Keep all classes with native methods and their native method signatures
-keepclasseswithmembernames class * {
    native <methods>;
}

# === Shrink, but do not obfuscate ===
# The APK is large because of unused library code (material-icons-extended is
# tens of MB); the fix is dead-code elimination, not renaming. Keeping every
# name means the JNI boundary and all reflective lookups stay valid with no risk
# of R8 renaming a symbol the native layer resolves by string.
-dontobfuscate

# === NativeAudioEngine ===
# The primary JNI bridge class - must be fully preserved
-keep class com.bitperfect.android.engine.NativeAudioEngine {
    *;
}

# The engine's nested types are used across the JNI boundary and from the
# playback sinks, so keep them whole.
-keep class com.bitperfect.android.engine.NativeAudioEngine$* {
    *;
}

# === Methods invoked only from native via GetMethodID ===
# R8 cannot see native callers, so without these it would remove the methods as
# unused. Both are looked up by exact name in native_bridge.cpp.
-keep class com.bitperfect.android.player.PlaybackController {
    public void onTrackTransition();
}
-keep interface com.bitperfect.android.engine.NativeAudioEngine$UsbControlTransferBridge {
    *;
}
-keepclassmembers class * implements com.bitperfect.android.engine.NativeAudioEngine$UsbControlTransferBridge {
    public int controlTransfer(int, int, int, int, byte[], int, int);
}

# Keep DsdManager which interacts with native layer
-keep class com.bitperfect.android.engine.DsdManager {
    *;
}

# === @Keep Annotation ===
# Keep all classes and members annotated with @Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# === Room Database Model Classes ===
# Room entities must not be obfuscated (column names mapped to fields)
-keep class com.bitperfect.android.library.model.Track { *; }
-keep class com.bitperfect.android.library.model.Album { *; }
-keep class com.bitperfect.android.library.model.Artist { *; }
-keep class com.bitperfect.android.library.model.Genre { *; }
-keep class com.bitperfect.android.library.model.Composer { *; }
-keep class com.bitperfect.android.library.model.Playlist { *; }

# Room DAO interfaces
-keep interface com.bitperfect.android.library.dao.** { *; }

# Room Database class
-keep class com.bitperfect.android.library.LibraryDatabase { *; }

# === Service Classes Referenced in Manifest ===
-keep class com.bitperfect.android.service.PlaybackService { *; }
-keep class com.bitperfect.android.service.MediaSessionService { *; }

# === USB Related Classes ===
# Keep USB classes that may be referenced via intent filters or reflection
-keep class com.bitperfect.android.usb.UsbAudioManager { *; }
-keep class com.bitperfect.android.usb.UsbPermissionHandler { *; }
-keep class com.bitperfect.android.usb.UsbErrorRecovery { *; }

# === Parcelable/Serializable ===
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# === Enum Classes ===
# Keep enum values for correct serialization
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# === Kotlin Coroutines ===
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# === Compose ===
# Do NOT blanket-keep androidx.compose.**: that pins every unused vector in
# material-icons-extended (tens of MB) and defeats shrinking entirely. The
# Compose libraries ship their own consumer R8 rules for the reflection they
# actually need, so only warnings are suppressed here.
-dontwarn androidx.compose.**

# === Suppress Warnings ===
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn javax.annotation.**
