# Building BitPerfect in Termux (aarch64)

This guide covers building BitPerfect directly on an aarch64 Android device using Termux.

## Tested Environment

- **Device**: iQOO 15 (aarch64)
- **OS**: Android 16 (kernel 6.12.58)
- **Termux**: Latest from F-Droid
- **Architecture**: Building ON aarch64 FOR aarch64

## Prerequisites

### System Packages

Install these packages in Termux:

```bash
pkg update && pkg upgrade
pkg install openjdk-21 gradle cmake ninja make git
```

### Android SDK

Set up the Android SDK at the default Termux path:

```bash
export ANDROID_HOME=$HOME/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/android-ndk-r29
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
```

Add these exports to your `~/.bashrc` or `~/.profile` for persistence.

### Required SDK Components

Using `sdkmanager`:

```bash
sdkmanager "platforms;android-36"
sdkmanager "build-tools;36.0.0"
sdkmanager "ndk;android-ndk-r29"
sdkmanager "cmake;3.22.1"
```

Or if using manually downloaded NDK r29, ensure it is placed at:
```
/data/data/com.termux/files/home/android-sdk/ndk/android-ndk-r29
```

## Version Summary

| Component       | Version          |
|-----------------|------------------|
| Gradle          | 9.7.0            |
| AGP             | 8.9.0            |
| Kotlin          | 2.1.0            |
| NDK             | r29 (29.0.14206865) |
| Build Tools     | 36.0.0           |
| compileSdk      | 36               |
| targetSdk       | 36               |
| minSdk          | 29               |
| Java            | OpenJDK 21       |
| CMake           | 4.4.2 (system) / 3.22.1+ (NDK) |

## Setup

### 1. Clone the Repository

```bash
git clone https://github.com/manishyadavji14-hash/Tata.git
cd Tata
```

### 2. Create local.properties

```bash
cp local.properties.example local.properties
```

Or create it manually:

```properties
sdk.dir=/data/data/com.termux/files/home/android-sdk
ndk.dir=/data/data/com.termux/files/home/android-sdk/ndk/android-ndk-r29
```

### 3. Verify Java Version

```bash
java -version
# Expected: openjdk version "21.0.12" or similar
```

### 4. Verify Gradle Version

```bash
gradle --version
# Expected: Gradle 9.7.0
```

## Building

### Debug APK (Recommended for Development)

```bash
gradle assembleDebug
```

The output APK will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Release APK

```bash
gradle assembleRelease
```

Note: Release builds require signing configuration. For development, use debug builds.

### Clean Build

If you encounter issues, try a clean build:

```bash
gradle clean assembleDebug
```

## Native Tests (No Android SDK Required)

You can build and run the native C++ test suite without the Android SDK:

```bash
# Build native tests
cmake -S app/src/main/cpp -B build-test -DSTANDALONE_TEST=ON
cmake --build build-test -j$(nproc)

# Run tests
cd build-test && ctest --output-on-failure
```

This runs all 262 native unit tests covering the audio engine, decoders, DSD pipeline, and USB transport layer.

## Performance Tips for Termux

### Memory Management

The project is configured with `-Xmx4g` for the Gradle JVM. If you have less than 8GB of available RAM (RAM + swap), reduce this in `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx3g -XX:+UseG1GC -XX:MaxMetaspaceSize=512m
```

### Parallel Builds

Parallel execution is enabled by default. On devices with fewer cores, you can disable it:

```properties
org.gradle.parallel=false
```

### Build Caching

Build caching is enabled by default. The cache is stored in `~/.gradle/caches/`. If storage is limited, you can periodically clean it:

```bash
gradle --stop
rm -rf ~/.gradle/caches/build-cache-*
```

### Daemon Management

If the Gradle daemon consumes too much memory in the background:

```bash
gradle --stop
```

To disable the daemon entirely (slower builds but less background memory usage):

```properties
# Add to gradle.properties
org.gradle.daemon=false
```

## Troubleshooting

### "SDK location not found"

Ensure `local.properties` exists with the correct `sdk.dir` path:

```bash
cat local.properties
# Should show: sdk.dir=/data/data/com.termux/files/home/android-sdk
```

### "NDK not configured"

Verify the NDK is at the expected path:

```bash
ls $ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake
```

If using a different NDK path, update `local.properties`:

```properties
ndk.dir=/your/actual/ndk/path
```

### Out of Memory Errors

Reduce Gradle JVM heap size in `gradle.properties` and stop other memory-intensive processes:

```bash
gradle --stop
# Edit gradle.properties to reduce -Xmx
gradle assembleDebug
```

### CMake Version Mismatch

The project requires CMake 3.22.1+. The NDK bundles a compatible version. If Gradle cannot find it, ensure the NDK path is correct.

Your system CMake (4.4.2) is used for standalone native tests only. The Android build uses the NDK-bundled CMake.

### Slow First Build

The first build downloads dependencies and compiles everything from scratch. This can take 10-20 minutes on a mobile device. Subsequent builds are much faster due to caching.

### Kotlin Compilation Errors

Ensure you are using Kotlin 2.1.0 (bundled with AGP 8.9.0). If you see version conflicts:

```bash
gradle --stop
rm -rf .gradle
gradle assembleDebug
```

## Architecture Notes

- The project targets **arm64-v8a only** (configured in `app/build.gradle.kts`)
- Building on aarch64 for aarch64 avoids any cross-compilation overhead
- The NDK r29 toolchain uses Clang 21 for native code compilation
- JDK 21 is used for Kotlin/Java compilation (source and target compatibility)

## Installing the APK

After building, install directly on the device:

```bash
# Using adb (if available)
adb install app/build/outputs/apk/debug/app-debug.apk

# Or using pm (directly in Termux with root)
pm install app/build/outputs/apk/debug/app-debug.apk

# Or using Termux:API
termux-open app/build/outputs/apk/debug/app-debug.apk
```
