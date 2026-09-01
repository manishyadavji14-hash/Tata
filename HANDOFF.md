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
| Work on | **`main`** — PR #4 merged as `eec1ed5`; embedded lyrics on `feat/embedded-lyrics` |
| Prebuilt APK | `dist/BitPerfect-debug-arm64.apk` (15.7 MiB, debug-signed, arm64 only) |
| Test status | 282 native C++ tests, **168** JVM unit tests, `lintDebug` 0 errors (195 warnings, all pre-existing) |
| Target device used for testing | vivo I2501, Android 16 (API 36), arm64-v8a |

Branch from `main` for new work. The old `feat/audiotrack-playback-build-fix`
branch is fully merged and can be deleted; nothing references it.

### Setting up a toolchain from scratch

No Android SDK is present in a fresh sandbox. What worked, exactly:

```bash
# SDK command-line tools, then the pinned versions from app/build.gradle.kts
export ANDROID_HOME=/root/android-sdk
sdkmanager --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-36" \
  "build-tools;36.0.0" "cmake;3.22.1" "ndk;29.0.14206865"
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored, must exist
```

Build with **JDK 21**, not a newer one — AGP 8.10.1 accepts 17+, but the project
compiles at Java 21 and that is what the committed APK was built with. The NDK
download is ~2.5 GB and dominates setup time.

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

# JVM unit tests (expect 168 passing)
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
`https://github.com/manishyadavji14-hash/Tata/raw/main/dist/BitPerfect-debug-arm64.apk`

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

### ~~P1 — "Unconfirmed music" quarantine~~ — DONE, needs device check
Implemented. `Track.isUnconfirmed` (schema **v3**, `MIGRATION_2_3`), the rule in
`Track.looksUntagged`, quarantined rows filtered out of every browse query, and a
review screen at Settings → Library → "Review unconfirmed music" with
multi-select and "Move to library".

Notes for whoever tests it:
- The rule requires **all** of album, artist, album artist, year and artwork to be
  absent. Deliberately conservative — hiding real music is worse than showing a
  stray recording.
- `MIGRATION_2_3` backfills existing rows, so an established library is cleaned up
  on upgrade without a rescan.
- The scanner uses `getAllIncludingUnconfirmed()`; using the filtered `getAll()`
  would make quarantined files look new every scan and violate the unique path
  index.
- Confirming a track is permanent. `persistScanResult` carries `isFavourite` and
  `isUnconfirmed` over from the stored row, which also fixed a pre-existing bug
  where **editing a file's tags silently cleared its favourite**.
- 11 unit tests, including one that reimplements the migration's SQL predicate and
  asserts it agrees with the Kotlin across a full input matrix — the two are in
  different languages and would otherwise drift.

Still unverified on device: the upgrade path from a real v2 database.

### ~~P1 — Player screen cleanup~~ — DONE
Format badge folded into one line under the title (tinted by output mode), the
file-open button removed, and the Diagnostics shortcut removed from the player.
Reads artwork -> title -> seek -> transport.

### ~~P2 — Embedded lyrics~~ — DONE, needs device check

`EmbeddedLyricsReader` (`library/EmbeddedLyricsReader.kt`) reads lyrics out of the
file's own tags, and `LyricsRepository` now resolves two sources in order:
sidecar file first, embedded tags second. Coverage:

| Container | Source |
|---|---|
| MP3, AIFF, DSF | ID3v2.2/2.3/2.4 `USLT` and `SYLT` |
| FLAC | `VORBIS_COMMENT` block |
| Ogg Vorbis, Opus | comment header packet |
| M4A, ALAC, AAC | `moov.udta.meta.ilst.©lyr` |

Notes for whoever tests it:
- **Everything is converted to LRC text**, including `SYLT`, whose timings are
  binary. `LyricsParser` therefore stays the only code that decides timed vs
  plain, so embedded and sidecar lyrics cannot drift apart in behaviour.
- **Sidecars win**, and an empty sidecar parse falls through to the tags, so a
  stray blank `.txt` cannot mask real embedded lyrics.
- `SYLT` timed in MPEG frames rather than milliseconds is **refused**: the frame
  rate is not in the tag, and wrong timings are worse than none.
- Syllable-level `SYLT` fragments are joined into whole lines, per the spec's
  newline convention — otherwise it renders one word per line.
- `Track.lyrics` in the database is **still not populated, deliberately.** Lyrics
  are read from the file on demand so retagging takes effect on the next play; a
  DB copy would go stale, and only files that went through the single-file scan
  path would ever get one. The KDoc on `Metadata.lyrics` says so now, instead of
  implying the feature is missing.
- 55 new JVM tests. Layouts were additionally checked against real files written
  by `flac`, `oggenc` and mutagen — that is what caught a trailing `U+0000`
  leaking into UTF-16 `USLT` text, which the synthetic fixtures had missed.
  `TESTING.md` has the regeneration recipe; redo it after touching the reader.

Unverified on device: whether the maintainer's own files actually carry embedded
lyrics, and how a very long lyric sheet scrolls.

### ~~P2 — Lyrics (sidecar)~~ — DONE for sidecar files, needs device check
`LyricsParser` handles LRC and plain text; `LyricsRepository` loads a sidecar
`song.lrc` / `song.txt`, also looking in a `Lyrics/` subfolder. A lyrics icon
appears at the lower right of the artwork **only when lyrics exist**, and the
panel replaces the title block so the transport controls never move. Timed lyrics
auto-centre the current line and offer a +/- 0.5 s nudge; untimed lyrics are
hand-scrolled and say why.

Embedded lyrics are now supported too — see the section above. `LyricsRepository`
checks the sidecar first and the file's tags second.

### P2 — Album-art overflow menu: remaining items
Implemented: Info/Tags, Add to playlist, Go to album/artist/genre/folder. Hidden
when the target does not exist, so no entry opens an empty screen.

Still to do, and deliberately absent rather than inert:
- **Delete** — needs the MediaStore consent flow (`createDeleteRequest`) on
  Android 11+, plus a confirmation. A half-built destructive action is the worst
  thing to ship here.
- **Album art** and **Bookmark** — behaviour undefined. Ask the maintainer what
  each should do (view vs replace artwork; a saved position within a track vs a
  saved track).

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

## 6. Moving this project to a platform that cannot connect to GitHub

Not every assistant or IDE can link a repository. In rough order of preference:

### Option A — upload a source zip (works almost everywhere)

Download the source as a single file, in a phone browser, no login needed:

```
https://github.com/manishyadavji14-hash/Tata/archive/refs/heads/main.zip
```

Then upload that zip to the platform. `.gitattributes` marks `dist/` as
`export-ignore`, so the archive contains **only source — roughly 1.5 MB, not the
16 MB the committed APK would add.** That keeps it under the upload limits most
platforms impose.

The zip has no git history. Commit messages are a large part of the design record
here, so if the platform can run git, prefer cloning over the zip. If it cannot,
`HANDOFF.md` is written to stand in for that history.

### Option B — clone from a machine that has network, then upload

```bash
git clone https://github.com/manishyadavji14-hash/Tata.git
# main already has everything; no branch checkout needed
```

Keeps full history. Upload or point the tool at the directory.

### Option C — text-only platform, no upload at all

Paste these two files into the conversation, in this order:

1. `AGENTS.md` (~3 KB) — the rules and how the maintainer works
2. `HANDOFF.md` (this file, ~13 KB) — state, build, architecture, backlog, traps

Together they are about 4,000 words and fit comfortably in a modern context
window. Then paste only the specific source files the task touches — use the
source map below to pick them.

Be realistic about this mode: without the code the assistant can advise on
design and write new files, but it cannot safely refactor what it has not read.
For anything touching playback or the native engine, get the real source in.

### Getting the built app, independent of all of the above

The APK download never requires a platform integration — it is a plain URL:

```
https://github.com/manishyadavji14-hash/Tata/raw/main/dist/BitPerfect-debug-arm64.apk
```

If GitHub itself is unreachable, any assistant with a working Android toolchain
can rebuild it from the source zip with the commands in section 2.

---

## 7. Source map

187 tracked files. These are the ones that matter, so a limited-context or
text-only session can request the right subset.

### Playback (Kotlin) — `app/src/main/java/com/bitperfect/android/player/`
| File | Role |
|---|---|
| `PlaybackController.kt` | State machine, queue operations, **per-track sink selection** |
| `PlaybackSink.kt` | Interface both outputs implement |
| `AudioTrackPlaybackSink.kt` | Android mixer output. Holds the end-of-track drain logic |
| `UsbPlaybackSink.kt` | Bit-perfect output via the native engine ring buffer |
| `PcmSource.kt` / `PcmSourceFactory.kt` | Decoder abstraction and per-path routing |
| `MediaCodecPcmSource.kt` | Platform decoders: FLAC, Opus, MP3, AAC, M4A, Vorbis |
| `NativePcmSource.kt` | Native decoders, exact samples, used by USB |
| `PlayQueue.kt` | Lock-guarded queue; shuffle and repeat |
| `PlaybackStateStore.kt` | Session persistence across app restarts |
| `AudioEffectsController.kt` | Equalizer/bass boost. AudioTrack sessions only |
| `SleepTimer.kt` | Main-looper timer with a generation counter |
| `Lyrics.kt` | `Lyrics` model and `LyricsParser` — the only judge of timed vs plain |
| `LyricsRepository.kt` | Resolves lyrics: sidecar file first, embedded tags second |

### Service — `app/src/main/java/com/bitperfect/android/service/`
`PlaybackService.kt` (notification, audio focus, adopts shared components),
`MediaSessionManager.kt` (media3 session + the `Player` adapter),
`PlaybackNotificationManager.kt`.

### UI — `app/src/main/java/com/bitperfect/android/ui/`
`MainActivity.kt` (wiring and ownership), `navigation/NavGraph.kt` (routes,
transitions, mini player host), `player/PlayerScreen.kt` +
`player/PlayerViewModel.kt`, `library/LibraryScreen.kt` +
`library/LibraryViewModel.kt` (tabs, scan menu),
`components/MiniPlayerBar.kt`, `components/DynamicAlbumColor.kt` (album-art
palette extraction — reuse this for the visualization spectrum),
`diagnostics/`, `equalizer/`, `queue/`, `playlist/`, `detail/`, `settings/`.

### Library — `app/src/main/java/com/bitperfect/android/library/`
`MusicLibrary.kt` (facade, all suspend; ZIP import), `LibraryDatabase.kt` (Room,
schema v3, migrations), `MetadataExtractor.kt`,
`EmbeddedLyricsReader.kt` (ID3/Vorbis/MP4 tag parsing — pure, no Android APIs),
`scanner/LibraryScanner.kt`, `scanner/MediaStoreAudioSource.kt`, `dao/`, `model/`.

### Native engine — `app/src/main/cpp/`
| Path | Role |
|---|---|
| `jni/native_bridge.cpp` | Every JNI export and the engine state machine |
| `usb/usb_iso_backend.h` | Platform boundary; `LoopbackIsoBackend` for tests |
| `usb/usbdevfs_iso_backend.{h,cpp}` | Real isochronous transport via usbdevfs ioctls |
| `usb/isochronous_transfer.{h,cpp}` | Queueing, resubmit loop, statistics |
| `usb/usb_audio_device.cpp` | UAC1/UAC2 descriptor parsing |
| `usb/usb_control.cpp` | UAC rate negotiation (`SET_CUR`, `SET_INTERFACE`) |
| `decoder/flac_decoder.cpp` | **Not trusted for playback** — see section 4 |
| `decoder/wav_decoder.cpp` | Reliable |
| `buffer/ring_buffer.cpp` | Lock-free SPSC, the real-time boundary |
| `pcm/`, `dsd/`, `dop/`, `native_dsd/` | Format conversion and DSD transport |
| `tests/` | 282 tests; `test_usb_iso_backend.cpp` covers the transport |

### Config
`app/build.gradle.kts` (SDK/NDK versions, dependencies, R8 for debug),
`app/proguard-rules.pro` (**keep rules — read before changing**),
`app/src/main/cpp/CMakeLists.txt`, `app/src/main/AndroidManifest.xml`,
`.github/workflows/android.yml`, `app/schemas/` (Room schema JSON).

---

## 8. Conventions

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
