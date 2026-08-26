# Testing Guide

This document describes the test architecture, how to run tests, and what each test category verifies.

## Test Architecture Overview

BitPerfect uses a two-tier testing strategy:

1. **Native C++ Tests** (Google Test): Cover the core audio engine, buffer operations, USB protocol handling, and format processing. These run standalone without Android SDK.
2. **Kotlin Unit Tests** (JUnit 5): Cover the Android-layer logic including player state machine, play queue, and metadata extraction.

```
Test Structure:
+-----------------------------+
|  Kotlin Unit Tests (JUnit5) |  <- Player logic, queue, metadata
|  app/src/test/              |
+-----------------------------+
|  Native Tests (GTest)       |  <- Audio engine, USB, buffers, codecs
|  app/src/main/cpp/tests/    |
+-----------------------------+
|  Instrumented Tests         |  <- Requires device (UI, USB hardware)
|  app/src/androidTest/       |
+-----------------------------+
```

## Running Standalone Native Tests

The native C++ test suite can be compiled and run without Android SDK/NDK:

### Prerequisites
- CMake 3.18 or later
- C++17 compatible compiler (GCC 7+, Clang 5+)
- Internet access (to fetch Google Test via FetchContent)

### Build and Run

```bash
# Clean build
rm -rf build-test

# Configure with standalone test mode
cmake -S app/src/main/cpp -B build-test -DSTANDALONE_TEST=ON

# Build all test executables
cmake --build build-test

# Run all tests
cd build-test && ctest --output-on-failure

# Run with verbose output
cd build-test && ctest --output-on-failure --verbose

# Run a specific test executable
./build-test/test_ring_buffer
./build-test/test_dop_encoder
./build-test/test_full_pipeline
```

### Running Individual Test Cases

```bash
# Run specific test by name
./build-test/test_dop_encoder --gtest_filter="DopEncoderTest.MarkerSequenceAlternates"

# Run all tests matching a pattern
./build-test/test_dop_encoder --gtest_filter="DopEncoderTest.Marker*"

# List all available tests
./build-test/test_dop_encoder --gtest_list_tests
```

## Running with Android Studio

### Kotlin Unit Tests
1. Open the project in Android Studio
2. Right-click on `app/src/test/` directory
3. Select "Run All Tests"
4. Or run individual test classes via their gutter icons

### Native Tests (via Android Studio)
1. The CMakeLists.txt supports both Android NDK and standalone builds
2. For standalone: use the command-line method above
3. For on-device: run instrumented tests that call native methods via JNI

## Test Categories

### Core Audio Pipeline

| Test File | What It Verifies |
|-----------|-----------------|
| `test_ring_buffer.cpp` | Lock-free SPSC ring buffer: write/read, wraparound, fill/empty, thread safety |
| `test_pcm_engine.cpp` | PCM format conversions: 16-to-24, 24-to-32, passthrough, bit-perfect verification |
| `test_buffer_manager.cpp` | Audio buffer manager: prebuffering, underrun detection, overflow protection, latency config |
| `benchmark_ring_buffer.cpp` | Performance: throughput measurement, zero-allocation verification, multi-threaded stress |

### USB Audio

| Test File | What It Verifies |
|-----------|-----------------|
| `test_usb_descriptors.cpp` | USB descriptor parsing: UAC1/UAC2, interface/endpoint extraction, format detection |
| `test_sample_rate.cpp` | Sample rate negotiation: rate support detection, closest match, clock configuration |
| `test_isochronous_transfer.cpp` | Transfer queue: submission, packet sizing, completion tracking, error recovery, statistics |

### DSD Pipeline

| Test File | What It Verifies |
|-----------|-----------------|
| `test_dop_encoder.cpp` | DoP encoding: marker alternation, payload preservation, buffer boundary handling, stereo, long streams |
| `test_dsf_parser.cpp` | DSF parsing: header parsing, channel extraction, DSD rate detection |
| `test_native_dsd.cpp` | Native DSD: capability detection, packet preparation, rate support |

### Decoders

| Test File | What It Verifies |
|-----------|-----------------|
| `test_wav_decoder.cpp` | WAV decoding: header parsing, PCM extraction, seek, various bit depths |
| `test_flac_decoder.cpp` | FLAC decoding: metadata parsing, frame decoding, seek |

### Playback

| Test File | What It Verifies |
|-----------|-----------------|
| `test_playback_mode.cpp` | Mode selection: PCM/DoP/Native DSD routing based on source + DAC capabilities |
| `test_gapless.cpp` | Gapless engine: track transitions, buffer management, crossfade |
| `test_replaygain.cpp` | ReplayGain: gain calculation, peak limiting, album/track mode |

### Integration

| Test File | What It Verifies |
|-----------|-----------------|
| `test_full_pipeline.cpp` | End-to-end: WAV->decode->buffer->verify, DSF->parse->DoP->verify, format detection->mode selection |

### Kotlin Tests

| Test File | What It Verifies |
|-----------|-----------------|
| `PlaybackControllerTest.kt` | State machine: Idle->Playing->Paused->Stopped transitions, seek, error handling |
| `PlayQueueTest.kt` | Queue: add/remove, reorder, shuffle, repeat modes, boundary behavior |
| `MetadataExtractorTest.kt` | Metadata: format detection, extension mapping, supported formats |

## Hardware-Dependent Tests

The following scenarios require a real USB DAC connected to an Android device:

### USB Device Detection
- Verify USB permission dialog appears
- Verify device descriptor parsing with real hardware
- Verify sample rate negotiation with actual DAC

### Audio Output Verification
- Connect DAC with sample rate display
- Play 44.1kHz WAV, verify DAC shows 44.1kHz
- Play 192kHz FLAC, verify DAC shows 192kHz
- Play DSD64 DSF with DSD-capable DAC, verify DAC shows DSD mode
- Verify no audible glitches or dropouts during playback

### Gapless Testing
- Create two consecutive tracks
- Verify no silence gap between them
- Verify no click/pop at transition point

### Stress Testing
- Play continuously for 1+ hours
- Monitor for buffer underruns (should be zero)
- Monitor memory usage (should be stable, no leaks)
- Test unplugging/replugging DAC during playback

## Offline Validation Procedures

For environments without Android SDK or USB hardware:

### 1. Native Engine Validation
```bash
cmake -S app/src/main/cpp -B build-test -DSTANDALONE_TEST=ON
cmake --build build-test
cd build-test && ctest --output-on-failure
```
This validates all audio processing logic, buffer management, USB protocol handling, and format conversion correctness.

### 2. Code Structure Validation
```bash
# Verify file count indicates complete implementation
find . -name "*.kt" -o -name "*.cpp" -o -name "*.h" | wc -l
# Expected: 100+ files

# Verify Gradle files are syntactically valid
grep -l "plugins" app/build.gradle.kts build.gradle.kts settings.gradle.kts
```

### 3. Bit-Perfect Verification
The `test_full_pipeline` test creates known audio data, processes it through the complete pipeline, and verifies the output matches the input byte-for-byte. This proves the audio path introduces no modifications.

### 4. DSD Path Verification
The `test_full_pipeline` test also verifies that:
- DSD content is never accidentally converted to PCM
- DoP encoding preserves every DSD bit
- DoP markers alternate correctly across buffer boundaries
- Decoding DoP back to DSD yields the original data

## Continuous Integration

For CI environments:

```yaml
# Example CI step
- name: Build and Test Native Engine
  run: |
    cmake -S app/src/main/cpp -B build-test -DSTANDALONE_TEST=ON
    cmake --build build-test --parallel
    cd build-test && ctest --output-on-failure --parallel 4
```

## Test Metrics

Current test coverage targets:
- Ring buffer: 100% of public API
- PCM engine: All conversion paths
- DoP encoder: Marker correctness, boundary handling, stereo
- USB descriptors: UAC1 and UAC2 paths
- Mode selection: All source/DAC capability combinations
- Decoders: WAV and FLAC format parsing and extraction
- Buffer manager: All states (Empty, Prebuffering, Streaming, Underrun, Full)
