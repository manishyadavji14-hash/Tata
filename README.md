# BitPerfect - USB Audiophile Music Player for Android

BitPerfect is a high-fidelity music player for Android that delivers bit-perfect audio output through USB Audio Class devices. It bypasses Android's audio mixing layer to send unaltered audio data directly to your USB DAC.

## Features

- **Bit-Perfect Playback**: Zero modification of audio data from file to DAC
- **DSD Support**: Native DSD and DoP (DSD over PCM) transport
- **High-Resolution PCM**: Supports 16/24/32-bit at rates up to 768kHz
- **USB Audio Class 1 & 2**: Full descriptor parsing and device configuration
- **Gapless Playback**: Seamless transitions between tracks
- **ReplayGain**: Album and track gain normalization
- **Material 3 UI**: Modern Android interface with Jetpack Compose
- **Lock-Free Audio Path**: Zero heap allocation in the real-time audio pipeline

## Architecture

```
+-------------------+     +-------------------+     +-------------------+
|   UI Layer        |     |   Service Layer   |     |  Native Engine    |
|   (Compose)       |---->|   (Foreground     |---->|  (C++ / JNI)      |
|                   |     |    Service)        |     |                   |
+-------------------+     +-------------------+     +-------------------+
                                                            |
+-------------------+     +-------------------+     +-------------------+
|  Music Library    |     |  Player           |     |  Audio Pipeline   |
|  (Room DB)        |     |  Controller       |     |                   |
|  - Tracks         |     |  - State Machine  |     |  Decoder (WAV/    |
|  - Albums         |     |  - Queue          |     |   FLAC/DSF)       |
|  - Artists        |     |  - Shuffle/Repeat |     |       |           |
|  - Playlists      |     |                   |     |  Format Detector  |
+-------------------+     +-------------------+     |       |           |
                                                    |  Mode Selector    |
                                                    |   (PCM/DoP/DSD)   |
                                                    |       |           |
                                                    |  Ring Buffer      |
                                                    |  (lock-free SPSC) |
                                                    |       |           |
                                                    |  USB Isochronous  |
                                                    |  Transfer Queue   |
                                                    +-------------------+
                                                            |
                                                            v
                                                    +-------------------+
                                                    |   USB DAC         |
                                                    |   (Hardware)      |
                                                    +-------------------+
```

### Key Design Principles

1. **No DSD-to-PCM conversion**: When a DSD file is selected for playback, it always goes through either Native DSD or DoP transport, never PCM conversion.
2. **Zero-copy where possible**: Direct buffer transfers between pipeline stages.
3. **Lock-free real-time path**: The audio callback thread never blocks or allocates.
4. **Correct USB Audio Class handling**: Proper descriptor parsing, alternate setting selection, and isochronous transfer management.

## Supported Formats

| Format | Type | Bit Depth | Sample Rates |
|--------|------|-----------|--------------|
| WAV    | PCM  | 16/24/32  | 8kHz - 768kHz |
| FLAC   | PCM  | 16/24/32  | 8kHz - 768kHz |
| DSF    | DSD  | 1-bit     | DSD64/128/256 |
| DFF    | DSD  | 1-bit     | DSD64/128/256 (planned) |
| AIFF   | PCM  | 16/24/32  | 8kHz - 768kHz (planned) |

## USB Audio Class Support

- **UAC1 and UAC2**: Full descriptor parsing for both versions
- **Synchronization Types**: Async, Adaptive, and Synchronous endpoints
- **Clock Management**: Proper clock source selection and sample rate negotiation
- **Native DSD Detection**: Inspects descriptors for raw DSD capability (TYPE_I_RAW_DATA)
- **DoP Fallback**: Automatic DoP encoding when native DSD is not available

## Build Instructions

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34
- Android NDK 25.2.x or later
- CMake 3.18+
- Kotlin 1.9+

### Building with Android Studio

1. Clone the repository
2. Open the project in Android Studio
3. Sync Gradle files
4. Build and run on a connected device

### Standalone Native Test Build (no Android SDK required)

The native C++ engine can be compiled and tested independently:

```bash
# Configure
cmake -S app/src/main/cpp -B build-test -DSTANDALONE_TEST=ON

# Build
cmake --build build-test

# Run tests
cd build-test && ctest --output-on-failure
```

This builds all native components with Google Test and runs the complete test suite covering:
- Ring buffer operations and thread safety
- PCM format conversion and bit-perfect verification
- USB descriptor parsing
- Sample rate negotiation
- DoP encoding and marker verification
- DSF file parsing
- Native DSD transport
- Playback mode selection
- WAV/FLAC decoders
- Gapless playback engine
- ReplayGain processing
- Isochronous transfer management
- Audio buffer management
- Full pipeline integration tests
- Performance benchmarks

## Testing

See [TESTING.md](TESTING.md) for comprehensive testing documentation.

Quick test run:

```bash
cmake -S app/src/main/cpp -B build-test -DSTANDALONE_TEST=ON
cmake --build build-test
cd build-test && ctest --output-on-failure
```

## Hardware Testing Guide

### Recommended DACs for Testing

1. **Basic USB Audio**: Any USB Audio Class 1 device (up to 96kHz/24-bit)
2. **High-Resolution**: USB Audio Class 2 device supporting 192kHz+
3. **DSD-capable**: DACs like iFi, Topping, SMSL with native DSD support
4. **Async USB**: Most modern DACs use asynchronous mode

### Testing Procedure

1. Connect USB DAC to Android device via OTG cable
2. Grant USB permission when prompted
3. Verify device detection in app settings
4. Play test files of various formats
5. Verify bit-perfect output using a digital loopback or DAC display

### Verifying Bit-Perfect Output

- Many DACs display the incoming sample rate - verify it matches the source file
- For DSD, verify the DAC shows "DSD" mode (not PCM)
- Use the app's diagnostic screen to check for buffer underruns
- Compare DAC's reported rate against file metadata

## Project Structure

```
app/
  src/
    main/
      java/com/bitperfect/android/
        engine/          - JNI bridge to native engine
        player/          - Playback controller, queue, state machine
        library/         - Music library, metadata, database
        usb/             - USB device management
        service/         - Foreground service, media session
        ui/              - Jetpack Compose UI screens
        settings/        - App settings and preferences
      cpp/
        usb/             - USB Audio Class device handling
        pcm/             - PCM format engine
        audio/           - Sample rate, format detection, mode selection
        decoder/         - WAV/FLAC decoders
        dsd/             - DSF parser, DSD stream
        dop/             - DoP encoder
        native_dsd/      - Native DSD transport
        buffer/          - Lock-free ring buffer, buffer manager
        jni/             - JNI bridge implementation
        diagnostics/     - Runtime diagnostics
        tests/           - Google Test test files
      res/               - Android resources
    test/                - JUnit unit tests
    androidTest/         - Instrumented tests
```

## Known Limitations

- Final APK build requires Android SDK/NDK (not available in all environments)
- Hardware-dependent features (USB communication) cannot be tested without physical devices
- DFF format support is planned but not yet implemented
- Bluetooth audio output is intentionally not supported (not bit-perfect)
- MQA decoding is not supported (proprietary format)

## License

This project is original code. See [LICENSES.md](LICENSES.md) for third-party dependency licenses.
