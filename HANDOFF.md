# BitPerfect — Handoff / Continuation Guide

Written so that work can resume on a different machine, or with a different AI
assistant, with no prior conversation context. Everything needed is in this
repository; nothing important lives only in a chat log.

**Read this first, then `git log` — the commit messages carry the reasoning for
each change and are deliberately detailed.**

---

## 1. Where things stand

| | |
|---|---|
| Active branch | `feat/audiotrack-playback-build-fix` |
| Merged to `main` | up to PR #1 (`e8e0325`) |
| Unmerged work | 10 commits on the branch after that merge |
| Prebuilt APK | `dist/BitPerfect-debug-arm64.apk` (~15.6 MB, debug-signed, arm64 only) |
| Test status | 282 native C++ tests, 86 JVM unit tests, `lintDebug` 0 errors |
| Target device used for testing | vivo I2501, Android 16 (API 36), arm64-v8a |

The app is a USB-audiophile music player: Jetpack Compose UI, Room library, a
Kotlin playback layer, and a C++ engine (JNI) that owns decoding and the USB
Audio Class transport.

### Confirmed working on the device
- Playback of WAV and FLAC.
- The library scans and lists music.

### Implemented but NOT yet confirmed on the device
Everything from the last few commits, because a crash (since fixed in `3ca2822`)
blocked testing:
- Notification with transport controls; pause on incoming call and resume after;
  pause when another app takes audio; pause on headphone unplug.
- Session persistence — reopening the app restores the last track, position and
  queue, paused.
- Mini player: album art, swipe left/right for previous/next (wrapping to the
  first track at the end), album-art-derived colour, tap or drag-up to open the
  player, drag-down on the player's top half to dismiss.
- Scan menu: scan all / choose folders / import from ZIP / scan by format.
- Seeking (was broken for every non-WAV format until `bbbd352`).
- Playback of Opus, MP3, AAC, M4A/ALAC, OGG Vorbis.

**First job for whoever picks this up: install the APK and verify that list.**

---

## 2. Build

### Requirements
- JDK 21 (AGP 8.10.1 needs 17+; the project compiles at Java 21)
- Android SDK Platform 36, Build-Tools 36.0.0
- NDK `29.0.14206865` and CMake `3.22.1` (versions are pinned in
  `app/build.gradle.kts`; using others will produce a different native binary)

`local.properties` is gitignored and must exist:

```properties
sdk.dir=/path/to/android-sdk
```

### Commands

```bash
# Debug APK. `clean` matters — see the packaging trap in section 5.
./gradlew clean :app:assembleDebug

# JVM unit tests (expect 86 passing)
./gradlew :app:testDebugUnitTest

# Lint (expect 0 errors; warnings are pre-existing)
./gradlew :app:lintDebug

# Native C++ suite, no Android SDK needed (expect 282 passing)
cmake -S app/src/main/cpp -B build-test -DSTANDALONE_TEST=ON
cmake --build build-test -j"$(nproc)"
cd build-test && ctest --output-on-failure
```

CI (`.github/workflows/android.yml`) runs all of the above on every push and
uploads the APK as an artifact.

### Refreshing the committed APK
The APK is committed because the maintainer installs from a phone and cannot use
CI artifact zips.

```bash
./gradlew clean :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk dist/BitPerfect-debug-arm64.apk
sha256sum dist/BitPerfect-debug-arm64.apk   # update dist/README.md
```

Direct install link (raw, works in a mobile browser, no login):
`https://github.com/manishyadavji14-hash/Tata/raw/feat/audiotrack-playback-build-fix/dist/BitPerfect-debug-arm64.apk`

> A 67 MB+ APK means the build was not clean. GitHub hard-rejects any file over
> 100 MB, and such a push cannot be fixed by amending — the oversized blob has to
> be removed from history before it will go through.

---

## 3. Architecture, and why it is shaped this way

### Two output paths, deliberately different

```
                        ┌─ NativePcmSource ──┐
file ─ PcmSourceFactory ┤                    ├─→ AudioTrackPlaybackSink → Android mixer
                        └─ MediaCodecPcmSource┘        (not bit-perfect)

file ─ PcmSourceFactory ── NativePcmSource ───→ UsbPlaybackSink → native engine
                                                  ring buffer → USB DAC
                                                       (bit-perfect)
```

- `PlaybackController` picks the sink **per track** (`selectSinkForNextTrack`),
  never mid-track: the sinks own worker threads and buffered audio, so swapping
  under a running stream would drop or duplicate what is in flight.
- **Android path** uses the platform decoders for everything except WAV, and
  normalises to 16-bit PCM. That path is not bit-perfect anyway (Android may
  resample or mix), so coverage and reliability win.
- **USB path** uses the native decoders only. A platform-decoded stream has been
  through a conversion that cannot be vouched for, so sending it to a DAC while
  calling the path bit-perfect would be dishonest; unsupported formats are
  refused there with a message telling the user to disconnect the DAC.
- `PlaybackSink.audioEffects` is **null on USB** for the same reason: platform
  effects bind to an AudioTrack session, which a bypassing stream does not have,
  and applying them would defeat bit-perfect output. The Equalizer screen says so
  rather than showing dead sliders.

### Component ownership (this has bitten twice — read it)

`PlayerViewModel` (retained) **owns** the `NativeAudioEngine` and
`PlaybackController`. It publishes them through `ServiceLocator` and clears them
in `onCleared`.

`PlaybackService` **adopts** them from `ServiceLocator` and tracks
`ownsEngineAndController`. It must not release what it did not create — doing so
stops the audio the UI is driving. It only builds its own pair when the system
recreates it with no Activity alive.

The service is **optional infrastructure**: it provides the notification,
lock-screen controls and audio focus, but audio plays without it. It is written
to degrade, never to crash the app (see `componentsReady`).

### Native engine (C++)

- `usb/usb_iso_backend.h` is the platform boundary. `IsochronousTransfer` keeps
  queueing, the resubmit loop and statistics; only device I/O sits behind the
  interface. That is what makes the transport unit-testable with no hardware.
- `UsbdevfsIsoBackend` is the real transport: `usbdevfs_urb` +
  `USBDEVFS_SUBMITURB`, reaped via `poll()` + `USBDEVFS_REAPURBNDELAY`. Raw
  ioctls, no libusb, so no LGPL dependency enters the audio path.
- `LoopbackIsoBackend` accepts and discards data, for tests. It reports
  `isHardware() == false` and the engine refuses to claim USB output with it
  installed — the codebase previously had exactly one such fake that everything
  mistook for a working transport.
- Android forbids native code opening a USB device, so Kotlin claims the
  interface (`force = true`, taking it from the kernel's `snd-usb-audio`), selects
  the alternate setting, and passes the file descriptor down via
  `nativeAttachUsbDevice`.

---

## 4. Outstanding work, in priority order

### P0 — Verify the unconfirmed list in section 1
No new work should start until the last build is known good on hardware.

### P1 — "Unconfirmed music" quarantine (requested, not started)
Detect files that are probably not music and keep them out of the main track
list. The maintainer's heuristic: **no tags at all** — no album, no artist, no
year, no cover art.

Required:
- A `Track` flag (e.g. `isUnconfirmed`) plus a **Room migration** —
  schema is at `app/schemas/`, currently v2, and
  `fallbackToDestructiveMigration` is deliberately **off** because playlists and
  favourites are user data that a rescan cannot rebuild. A bad migration now
  crashes rather than wipes; write `MIGRATION_2_3` carefully.
- Scanner sets the flag; main library queries exclude it.
- A Settings section listing them with multi-select and "move to library".

### P1 — "Player screen should be clean" (requested, needs a decision)
The maintainer asked for this but has not yet said what to remove. A proposal was
put to them and not answered: drop the "Open WAV or FLAC" button and the
diagnostics icon, fold the format badge into small text under the title, keep
art → title → seek → transport. **Confirm before implementing.**

### P2 — Visualization spectrum (requested, not started)
A spectrum driven by `android.media.audiofx.Visualizer` on the AudioTrack
session, rendered on a Compose canvas, coloured from the album-art palette
(reuse `ui/components/DynamicAlbumColor.kt`, which already extracts a vivid
accent).

Important: `Visualizer` reads the **mixed Android output**, so it cannot work on
the bit-perfect USB path, which bypasses the mixer. It must degrade gracefully
there rather than appear broken. It also requires `RECORD_AUDIO` permission on
many devices — check before committing to the approach.

### P2 — Lyrics and the album-art overflow menu (requested, not started)
- Two controls at the lower right of the album art: a lyrics icon, and a
  three-dot menu with Delete, Playlist, Album Art, Bookmark, Info/Tags, Lyrics,
  Artist, Album, Folder, Genre.
- Tapping lyrics replaces the song name under the art with **three rows** that
  follow playback. When lyrics are unsynchronised, the user can scroll them
  manually.

### P3 — USB DAC hardware validation
The transport is written and unit-tested but **has never moved a byte to real
hardware**. `TESTING.md` has the procedure. Start at Diagnostics → Transport: it
must read `usbdevfs isochronous`, not `loopback (no hardware)`.

Known gaps to expect:
- `calculateNominalPacketSize` truncates, so 44.1 kHz drifts slowly (22 vs 22.05
  bytes/packet). 48 kHz and multiples divide evenly. The proper fix is reading
  the asynchronous feedback endpoint, which is parsed but not consumed.
- DSD/DoP is implemented in the engine but not routed through the sink selection.

### P3 — Test gaps
- No `androidTest` source set, so `MIGRATION_1_2` has no `MigrationTestHelper`
  test. Highest-value test to add.
- The native FLAC decoder is **not trusted**: its own header lists LPC subframes
  as unsupported, and all 19 of its unit tests cover STREAMINFO parsing or
  synthetic frames — none decode a real encoded file. The Android path routes
  FLAC to MediaCodec instead. The **USB path still uses it**, so it must be
  verified or fixed before USB FLAC playback can be trusted.

---

## 5. Traps that have already cost time

1. **APK size.** Debug builds are R8-shrunk with `-dontobfuscate` (see
   `app/proguard-rules.pro`). Shrinking is what removes the tens of MB of unused
   `material-icons-extended`; obfuscation stays off so the JNI boundary and
   reflective lookups keep their names. Do **not** reintroduce a blanket
   `-keep class androidx.compose.** { *; }` — that pins all those icons and
   defeats the shrink.
2. **Incremental packaging bloat.** A non-clean `assembleDebug` has produced a
   67 MB APK whose zip entries totalled 15 MB — ~55 MB of stale padding. Always
   `clean` before producing a distributable APK. CI does.
3. **Methods called only from native.** R8 cannot see native callers.
   `PlaybackController.onTrackTransition` and the control-transfer bridge have
   explicit keep rules. Adding another native→Kotlin call means adding a rule, or
   it will be silently stripped and fail at runtime, not build time.
4. **Verify JNI symbols after touching the native layer.** A name mismatch is an
   `UnsatisfiedLinkError` at runtime, not a compile error:
   ```bash
   nm -D --defined-only <libbitperfect_engine.so> | grep NativeAudioEngine_
   ```
   All 34 Kotlin `external` declarations should have a matching export.
5. **`MediaCodec.flush()` must not be followed by `start()`** in synchronous
   mode; it throws on most devices. That was the seek bug.
6. **`AudioTrack` in `MODE_STREAM` will not render the final partial buffer**
   while PLAYING. `stop()` is what drains it. That was the end-of-track error.
7. **media3 `MediaSession.Builder` asserts `player.canAdvertiseSession()`.**
   Returning false throws `IllegalArgumentException` out of `Service.onCreate`,
   which kills the app — and with `START_STICKY` it loops. The service is now
   `START_NOT_STICKY` and fails soft.
8. **Do not start a foreground service before there is audio to show.** On
   Android 14+ that gets the app killed. The service is promoted on the first
   `Playing` state.

---

## 6. Conventions

- Commit messages explain **why**, lead with the problem, and state what was
  verified. They are the primary design record — match that standard.
- Comments explain reasoning and constraints, not mechanics. Several in the
  codebase document a specific bug that a naive change would reintroduce; do not
  delete them.
- Nothing ships that only looks like it works. Two features were removed for
  this reason — an equalizer wired to nothing, and a simulated USB transport that
  reported healthy throughput while emitting silence. If something cannot work
  yet, say so in the UI or refuse the operation.
- Verify before claiming done: a build exiting 0 is not evidence a feature works.
  Prefer inspecting the artifact (dex contents, exported symbols, APK signature).
