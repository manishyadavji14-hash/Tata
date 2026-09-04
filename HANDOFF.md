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
| Test status | 282 native C++ tests, **324** JVM unit tests, `lintDebug` 0 errors (195 warnings, all pre-existing) |
| Database | schema **v4** — `addedAt`, `playedMs`, `isUserEdited`; MIGRATION_3_4 also re-applies quarantine |
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

### Ask the device, do not guess: the lock-screen artwork report
Album art on the lock screen has now been "fixed" four times, three of them from
static reasoning that turned out to be aimed at the wrong stage. The reason is that
this failure is invisible from inside the app — the player can be showing a cover
while the media session has none, and the two come from different code — and the
maintainer works from a phone with no way to read logcat.

So there is now a plain-language readout: **player screen → the output badge at the
bottom left → Audio info → "Lock screen / Album art"**. It says which of these
happened for the track playing right now:

| Reading | Meaning |
|---|---|
| `Published to the media session (N KB)` | the cover reached the session; if the lock screen is still blank the fault is below this app |
| `Published to the notification only, without session bytes` | encoding failed; the shade may show a cover but the lock screen will not |
| `Cover recorded but not published — retrying` | a cover exists and could not be loaded |
| `No cover recorded for this track` | nothing to show; the library has no cover for it |

**Ask for that line before theorising.** It distinguishes the stages that three
rounds of guesswork could not.

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

# JVM unit tests (expect 324 passing)
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
- **The rule is now the artist, and only the artist** (tightened in v4). A file is
  quarantined when both `artist` and `albumArtist` are missing, where "missing"
  also covers the placeholder values taggers write — `<unknown>`, `unknown`,
  `unknown artist`, `various`, `various artists` — matched whole-value, so
  "Unknown Mortal Orchestra" is not caught.
- The original rule required *every* tag to be absent, which let anything with a
  stray year or a scrap of folder artwork through. That is why recordings and voice
  notes kept reaching the library, and `albumTitle`, `year` and artwork are no
  longer treated as evidence.
- `MIGRATION_3_4` re-applies the rule to existing rows, so files already in the
  library that name no artist move into review on upgrade. That is intended.
  Anything previously confirmed by hand that has no artist needs confirming again —
  the v3 schema cannot distinguish "confirmed" from "never quarantined".
- `MetadataExtractor.buildTrack` now sets the flag too. It previously did not, so
  adding a file singly — from the file picker or a zip import — bypassed quarantine
  completely.
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

### ~~P1 — Library sort, play statistics, per-song menu~~ — DONE, needs device check

**Sort.** The Library's sort button was a blind cycle through five orders with no
label; it is now a menu that shows the current choice. Orders: name A-Z/Z-A, date
added newest/oldest, format, most played. `SortOrder.appliesTo(tab)` decides which
appear, so "format" is not offered on the Artists tab, and `selectTab` falls back
to name order when the active one does not apply. Every order breaks ties by
title, so a list cannot reshuffle between visits.

**Play statistics.** `Track.playedMs` accumulates listening time and
`Track.playedPercent` turns it into a share of the duration, which is what "most
played" ranks on. It is cumulative and can exceed 100%: a four-minute track played
once and then replayed for a minute is 125%. That ranks a track someone returns to
above one heard once, which a play count would not.

`PlayStatsRecorder` does the accounting. The important property is that **seeks are
not counted**: `PlaybackController` calls `startSegment` at every discontinuity
(seek, track start, external position override) and `sample` at every boundary
(pause, stop, end of track, track change), so within a segment the position only
moves because audio played. Because of that, sampling frequency does not affect
accuracy — the periodic call from `PlayerViewModel`'s 250 ms loop only bounds how
much is lost if the process is killed mid-track. A 30 s cap per sample is a
backstop for a jump that arrived without a matching `startSegment`.

Writes go through `PlaybackController.playStatsWriter`, set by `PlayerViewModel` to
launch on `BitPerfectApp.applicationScope` — **not** `viewModelScope`, because
playback outlives the player screen and a cancelled write would lose the count.

**Per-song menu.** The Library's Tracks tab now uses the shared
`ui/components/TrackRow`, which already had the overflow menu, instead of its own
inline row. Entries: Play, Add to playlist, favourite, Info / Tags, Edit tags,
Lyrics, Remove from library. `TrackInfoDialog` moved to `ui/components` and is
parameterised on a neutral `TrackInfo` so the player and the library share one
copy; it also shows the played percentage and the listened time behind it.

Notes for whoever tests it:
- **"Remove from library" does not delete the file**, and both the menu label and
  the confirmation say so. Deleting needs the MediaStore consent flow — still not
  built, deliberately.
- **"Edit tags" is library-only.** There is no tag writer in the app. The dialog
  states that the file's own tags are unchanged, and `Track.isUserEdited` makes
  `persistScanResult` keep the user's descriptive fields so a rescan does not
  silently revert the edit. Technical fields are still refreshed from the file.
  Giving a quarantined track an artist releases it from quarantine.
- **Lyrics** are written to app-private storage, not next to the audio, because
  writing there needs consent on Android 11+ and fails on a read-only volume.
  "Remove" records a suppression marker — without it, lyrics embedded in the file
  would simply reappear on the next play. Resolution order is override, then
  sidecar, then tags.
- `MusicLibrary.lyricsRepository` is now shared with `PlayerViewModel`; two
  instances would each cache separately and edits would not show up in the player.

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

### P3 — Folder covers (`cover.jpg`) — blocked on a permission, do not just add it
The obvious next artwork source is a sidecar image beside the audio file
(`cover.jpg`, `folder.jpg`, `front.jpg`), which is how many FLAC rips store art.
**It cannot work as things stand.** The manifest requests `READ_MEDIA_AUDIO` and a
`maxSdkVersion`-capped `READ_EXTERNAL_STORAGE`; on Android 13+ that grants *audio
files only*, so opening a JPEG next to a track by path fails with `EACCES`. Reading
it would need `READ_MEDIA_IMAGES` — asking a music player for photo access, which
is worth a decision from the maintainer rather than a quiet addition.
Whatever happens, do not ship a silent best-effort attempt: on the maintainer's own
device it would be a no-op that merely *looks* like a fix, which is the one thing
this codebase does not do.

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
- Also because of that: **that `ArtworkResolver`'s default constructor and
  `ArtworkLoader` really do route through `MediaStoreArtwork` is not covered by a
  test.** The decision they delegate is (`accessFor`), but the wiring itself is
  verified only by inspecting the dex — `MediaStoreArtwork.openThumbnail` is
  present and issues the same `openTypedAssetFile` call as
  `coil/fetch/ContentUriFetcher.fetch`, and the only remaining `openInputStream`
  calls in our own code are the non-album branch plus two unrelated
  document-copy paths. Re-run that check after touching either consumer.
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
7. **A repair must never replace something with nothing.**
   The artwork repair passes wrote back whatever they resolved, including null. When
   a cover could not be read they overwrote the recorded MediaStore URI with null —
   unrecoverable without a rescan, because the album id it was derived from is not
   stored on the row. One background pass could therefore strip artwork from an
   entire library, and did. `ArtworkResolver.shouldWriteArtwork` is now the single
   place that decides, and it only ever approves an improvement.
   The first version of that guard returned "the value to write, or null to skip",
   which made null mean both things — so **no test could catch the bug it was
   written for**, and a mutation proved it. It is a predicate for that reason.
8. **`MediaMetadataRetriever.embeddedPicture` does not cover the formats this app
   accepts.** It reads ID3 `APIC` and MP4 `covr`, usually reads a FLAC `PICTURE`
   block, **misses the base64 `METADATA_BLOCK_PICTURE` comment that Ogg Vorbis and
   Opus use**, and cannot parse DSF at all. Relying on it alone is why artwork
   appeared for some tracks and not others with no pattern visible from the UI.
   `EmbeddedArtworkReader` parses the containers directly and is tried first; the
   platform call remains as a second opinion. Verified byte-exact against real
   files from `flac`, `oggenc` and mutagen — recipe in TESTING.md.
   Related: `MetadataExtractor.Metadata.hasArtwork` reflects only what the platform
   can see, so **do not gate artwork extraction on it** — that gate was hiding
   covers the reader can find.
9. **A track change resolves the same track twice, concurrently.**
   `PlayerViewModel.resolveTrackDetails` runs for the screen while
   `PlaybackService.publishMetadataFor` runs for the notification, and both call
   `MusicLibrary.getTrackDetails`. Anything with a check-then-write in that path
   needs to be safe under it. `ArtworkCache.put` was not: its temporary file was
   named after the target, so the two writes shared one path and produced a
   corrupt image or a failed rename — which is why artwork appeared only
   sometimes, and sometimes in the app but not on the lock screen. `put` is now
   serialised with a unique temporary, and `ArtworkResolver` locks per path so the
   extraction happens once. `ArtworkCacheTest` reproduces the race with eight
   threads; it fails reliably against the old code.
10. **The player is the NavHost start destination, so `popBackStack()` is a no-op
   there.** The pull-down-to-minimise gesture called it and silently did nothing.
   Anything that means "leave the player" has to fall back to navigating somewhere.
11. **A MediaStore album cover is a *typed asset*, not a stream — and this entry
   used to say the opposite, which caused a bug of its own.**
   `MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI` + album id gives
   `content://media/external/audio/albums/<id>` — note `albums`, not the legacy
   `albumart`. That URI is current and it works, but it names a *row*, so
   `openInputStream` on it throws `FileNotFoundException: No media for album
   content`. The cover has to be requested with
   `openTypedAssetFile(uri, "image/*", Bundle{EXTRA_SIZE=Point}, null)`, which is
   what the documented `ContentResolver.loadThumbnail` does internally and what
   `MediaStore.Audio.Albums.ALBUM_ART` was deprecated in favour of.
   Coil does exactly that for these URIs (`ContentUriFetcher.isMusicThumbnailUri`,
   verified against the 2.7.0 bytecode), so every cover rendered *inside* the app.
   This app's own two consumers used `openInputStream` and so both failed on the
   same URI, which looked like three unrelated bugs:
   covers missing from the lock screen and notification only; every play
   re-parsing the audio file because the recorded URI could never be judged usable;
   and "Rebuild album art" reporting those tracks as having no cover, the opposite
   of the truth.
   `MediaStoreArtwork` is now the only way this app opens an artwork reference, and
   `MediaStoreArtwork.isAlbumArtUri` is a deliberate copy of Coil's predicate — if
   the two ever disagree, a cover appears on one surface and not the other.
   **The decision is exposed as `accessFor()` returning an enum specifically so a
   test can pin it.** While it was buried inside the `ContentResolver` call no test
   could have caught the wrong choice, and a mutation confirmed that.
   Do not "simplify" either consumer back to `openInputStream`.
12. **State that is declared and never assigned is worse than absent.**
   `PlayerUiState.trackPath` was declared and never once written, and
   `AlbumArtActions` early-returns on an empty path — so the album-art overflow
   menu shipped in `93bca4d` never appeared. Nothing failed; it simply did not
   exist. Same class of bug as the dead `updateMetadata`. When a feature is
   invisible rather than broken, check whether the state it reads is ever set.
13. **The system reads the media session's *timeline*, not the Player's getters.**
   The notification panel, lock screen and vendor widgets are fed from
   `MediaMetadataCompat`/`PlaybackStateCompat`, which media3 builds from the
   current timeline window. A player returning `Timeline.EMPTY` has no window, so
   there is nothing to publish — that produced "Unknown song", no artwork and
   `--:--` at both ends of a dead scrubber even with transport buttons working.
   `SingleItemTimeline` supplies the window. Two specifics worth keeping:
   - media3 1.2.1's `MediaMetadata` has **no duration field at all**; the window
     is the only route a track length can take.
   - `COMMAND_GET_TIMELINE` must be advertised, or media3 refuses to read the
     timeline it needs.
14. **Nothing published metadata until it was explicitly wired.**
   `MediaSessionManager.updateMetadata` and
   `PlaybackNotificationManager.updateTrackInfo` existed for months with **zero
   call sites**, so the session carried `MediaMetadata.EMPTY` for the whole life
   of the process. `PlaybackService.publishMetadataFor` is now the one caller;
   if the panel goes blank again, check there first.
15. **media3 `MediaSession.Builder` asserts `player.canAdvertiseSession()`.**
   Returning false throws `IllegalArgumentException` out of `Service.onCreate`,
   which kills the app — and with `START_STICKY` it loops. The service is now
   `START_NOT_STICKY` and fails soft.
16. **Do not start a foreground service before there is audio to show.** On
   Android 14+ that gets the app killed. The service is promoted on the first
   `Playing` state.
17. **media3 publishes artwork to the platform session only through a
   `BitmapLoader`, and the default one cannot read this app's URIs.**
   Setting `MediaMetadata.artworkData` is not enough on its own. media3 asks a
   `BitmapLoader` for a `Bitmap` and only then sets `METADATA_KEY_ALBUM_ART` on the
   platform session, which is what SystemUI draws on the lock screen. Left unset,
   `MediaSession.Builder` installs `CacheBitmapLoader(DataSourceBitmapLoader(ctx))`
   — verified in the 1.2.1 bytecode — whose URI branch uses `openInputStream`, the
   one call MediaStore refuses for an album-art URI (trap 11). So the `artworkUri`
   beside the bytes was a fallback in appearance only: it could never succeed here,
   and whenever the bytes were missing the lock screen simply stayed blank.
   `SessionArtworkBitmapLoader` is now set explicitly and shares
   `MediaStoreArtwork` with the rest of the app. **Do not remove it** on the
   grounds that media3 "has a default".
   Also worth knowing: `PlayerWrapper.getMediaMetadataWithCommandCheck()` returns
   `MediaMetadata.EMPTY` unless `COMMAND_GET_METADATA` (command 18) is advertised,
   so metadata silently vanishes if that is ever dropped from
   `isCommandAvailable`/`getAvailableCommands`.
18. **"At least this big" is not "at most this big", and a silent drop hides it.**
   `ArtworkLoader` sampled covers with the Android documentation's recipe — halve
   while *both* halved edges are still >= 512 — which is a floor, not a cap. A
   1000x1000 cover decoded at its full size, four times the intended pixels, and a
   wide cover could not be reduced at all because its short edge blocked the
   halving. `compress` then made **one** attempt and returned null if the JPEG
   exceeded the 512 KB session budget: no log, no retry, and a cover that had been
   read and decoded perfectly well never reached the lock screen. Two lessons, both
   already written elsewhere in this file: cap what you mean to cap, and **degrade
   rather than drop** — a soft cover beats none, and the difference between "did not
   fit" and "was thrown away" has to be visible.
   Related: `PlaybackService.publishedMetadataPath` latched the track *before* doing
   the work and never cleared it, so one failed cover meant no cover for that track
   for as long as it played. "Not yet" and "there is none" are now different states.

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
| File | Role |
|---|---|
| `PlaybackService.kt` | Notification, audio focus, adopts shared components, and **publishes track metadata** to the session (`publishMetadataFor`) |
| `MediaSessionManager.kt` | media3 session + the `Player` adapter; position and duration are read live from the controller |
| `SingleItemTimeline.kt` | The one-window timeline the system reads title, artwork and **duration** from |
| `ArtworkSource.kt` | Whether stored artwork is a system-readable URI or an app-private file |
| `ArtworkLoader.kt` | Decodes and size-bounds covers for the session and the notification |
| `PlaybackNotificationManager.kt` | The MediaStyle notification |

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
