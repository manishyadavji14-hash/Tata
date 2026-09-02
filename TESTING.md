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
| `LyricsParserTest.kt` | LRC parsing: timestamps, metadata tags, fraction scaling, current-line lookup |
| `LyricsRepositoryTest.kt` | Source order: sidecar overrides embedded tags, blank sidecar falls through, caching |
| `EmbeddedLyricsReaderTest.kt` | Tag parsing: ID3 `USLT`/`SYLT`, Vorbis comments, MP4 `©lyr`, malformed input |

### Re-verifying the embedded lyrics reader against real files

`EmbeddedLyricsReaderTest` builds its tags byte by byte, which proves the reader
is self-consistent but not that the byte layouts are right. The layouts were
checked against files produced by independent tools. Those files are binaries and
are not committed, so here is how to regenerate them — worth redoing after any
change to `EmbeddedLyricsReader`:

```bash
# Fedora/Amazon Linux; on Debian use apt-get
dnf install -y flac vorbis-tools && pip install mutagen
mkdir -p build-fixtures && cd build-fixtures

python3 - <<'PY'
import math, struct, wave
with wave.open('tone.wav','w') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(44100)
    w.writeframes(b''.join(
        struct.pack('<hh', *(int(12000*math.sin(2*math.pi*440*t/44100)),)*2)
        for t in range(44100)))
PY

flac -f --totally-silent -o real.flac tone.wav
oggenc -Q -o real.ogg tone.wav

python3 - <<'PY'
from mutagen.flac import FLAC
from mutagen.oggvorbis import OggVorbis
from mutagen.id3 import ID3, USLT, SYLT, TIT2, APIC
TIMED = "[00:01.00]First line\n[00:04.50]Second line\n[01:02.25]Third line"
f = FLAC('real.flac'); f['LYRICS'] = TIMED; f.save()
o = OggVorbis('real.ogg'); o['LYRICS'] = TIMED; o.save()

# USLT behind a 40 KB picture frame, to prove the frame walk gets past it
open('real_uslt.mp3','wb').write(b'\xff\xfb\x90\x00' + b'\x00'*512)
t = ID3(); t.add(TIT2(encoding=3, text="Title"))
t.add(APIC(encoding=0, mime='image/jpeg', type=3, desc='', data=b'\xff\xd8'+b'A'*40000))
t.add(USLT(encoding=1, lang='eng', desc='desc', text=TIMED))
t.save('real_uslt.mp3', v2_version=4)

open('real_sylt.mp3','wb').write(b'\xff\xfb\x90\x00' + b'\x00'*512)
s = ID3()
s.add(SYLT(encoding=0, lang='eng', format=2, type=1, desc='',
           text=[("\nHold",1000), (" me",1200), (" close",1400), ("\nNext line",5000)]))
s.save('real_sylt.mp3', v2_version=3)
PY
```

Then point a temporary test at `build-fixtures/` and assert:

| File | Expected `EmbeddedLyricsReader.read` result |
|---|---|
| `real.flac`, `real.ogg` | the `TIMED` string above, unchanged |
| `real_uslt.mp3` | the `TIMED` string, with no trailing `U+0000` |
| `real_sylt.mp3` | `[00:01.00]Hold me close` then `[00:05.00]Next line` |

**Delete that test again afterwards** — it cannot pass without the fixtures, and
a permanently red test is worse than no test. This exercise is what caught the
trailing-terminator bug that the synthetic fixtures had missed; the committed
suite now guards it in `usltTrailingTerminator`.

There is no MP4 encoder in that package set, so the `©lyr` path was validated the
other way round: a file was assembled in Python and `mutagen.mp4.MP4` was asked to
read the atom back, confirming the layout the reader expects is the standard one.

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


## Validating USB DAC Output on Hardware

The isochronous transport submits real URBs through `usbdevfs`, which cannot be
exercised without a device attached. The automated suite covers the queueing,
underrun padding, resubmit loop and kernel status mapping against an injected
fake backend (`tests/test_usb_iso_backend.cpp`), so what remains for hardware is
specifically: does the kernel accept our URBs, and does the DAC lock to them.

### What to check first

The diagnostics screen answers the important question directly. Under
**Transport**:

| Field | Meaning |
|---|---|
| `Streaming To DAC` | `Yes` only when the transport is hardware-backed *and* running |
| `Transport` | `usbdevfs isochronous` on hardware, `loopback (no hardware)` otherwise |
| `Sent To DAC` | Bytes the kernel accepted. Must climb during playback |

`Read From Buffer` under **Buffer Status** is deliberately separate: it counts
bytes leaving the engine's ring buffer and moves even with no DAC attached. Only
`Sent To DAC` indicates data on the wire.

### Procedure

1. Connect the DAC by OTG *before* starting playback, and grant the USB
   permission prompt.
2. Open Diagnostics and confirm `Transport` reads `usbdevfs isochronous`. If it
   reads `loopback`, the interface was never claimed — check logcat for
   `Another driver is holding the audio interface`, which means the kernel's
   `snd-usb-audio` driver kept it.
3. Play a 44.1 kHz/16-bit WAV. Confirm `Streaming To DAC` is `Yes` and
   `Sent To DAC` increases.
4. Check the DAC's own display reports 44.1 kHz. This is the real bit-perfect
   check: the rate shown must match the file, with no resampling to 48 kHz.
5. Repeat for 96 kHz and 192 kHz FLAC, and for 24-bit material.
6. Confirm the Equalizer screen reports that EQ is unavailable while the DAC is
   the output. That is intended: platform effects would break bit-perfect output.

### Known limitations to expect

- **Non-integer packet sizes.** `calculateNominalPacketSize` truncates, so at
  44.1 kHz the nominal packet is 22 bytes where the true average is 22.05. On an
  asynchronous DAC the feedback endpoint would normally correct this; that
  endpoint is not yet read, so very long playback may drift. 48 kHz and its
  multiples divide evenly and are unaffected.
- **No feedback endpoint.** Asynchronous DACs expose an explicit feedback IN
  endpoint to report their actual rate. It is parsed but not yet consumed.
- **DSD is not routed here yet.** DoP and native DSD transport exist in the
  engine but the sink selection only covers PCM.
- **Output is chosen per track, not mid-track.** Attaching a DAC during playback
  takes effect on the next track, because the sinks own their worker threads and
  buffered audio.

### If no audio is produced

Check in this order, since each step depends on the previous one:

1. `Transport` is `usbdevfs isochronous` — if not, the interface claim failed.
2. `Sent To DAC` is climbing — if not, `USBDEVFS_SUBMITURB` is being rejected;
   logcat will carry the errno.
3. Underruns under **Error Counters** — a high count means the decoder is not
   keeping the ring buffer fed.
4. The DAC shows the expected sample rate — if it shows a different one, rate
   negotiation via UAC `SET_CUR` did not take.
