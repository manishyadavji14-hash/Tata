# BitPerfect ProGuard Rules
# ========================
# These rules ensure native JNI methods, model classes, and services
# are preserved during code shrinking and obfuscation.

# === JNI Native Methods ===
# Keep all classes with native methods and their native method signatures
-keepclasseswithmembernames class * {
    native <methods>;
}

# === NativeAudioEngine ===
# The primary JNI bridge class - must be fully preserved
-keep class com.bitperfect.android.engine.NativeAudioEngine {
    *;
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
# Compose compiler generates code that uses reflection in some cases
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# === Suppress Warnings ===
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn javax.annotation.**
