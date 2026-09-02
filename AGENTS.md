# Instructions for AI coding assistants

This file is the entry point for any assistant or platform working on this
repository. It is intentionally short; the detail is in `HANDOFF.md`.

## Start here

1. **Read [`HANDOFF.md`](HANDOFF.md).** It has the current state, build commands,
   architecture and rationale, the prioritised backlog, a source map, and a list
   of traps that have already cost real time.
2. **Read recent `git log`.** Commit messages in this repo are detailed on
   purpose and are the primary design record. If you only have a source zip there
   is no history — `HANDOFF.md` is written to stand in for it.
3. **Check `dist/README.md`** for the current prebuilt APK and its checksum.

## If you cannot access GitHub

`HANDOFF.md` section 6 covers this. Short version: the maintainer can download a
source-only zip (~1.5 MB — `dist/` is `export-ignore`d) and upload it, or on a
text-only platform paste `AGENTS.md` + `HANDOFF.md` and then the specific files a
task needs, chosen from the source map in section 7. Do not refactor code you
have not been given.

## What this project is

An Android USB-audiophile music player. Kotlin + Jetpack Compose UI, Room
library, and a C++ audio engine over JNI that owns decoding and the USB Audio
Class isochronous transport.

## How the maintainer works

- **Phone only.** No filesystem, terminal, or desktop access. They install by
  tapping a link.
- Therefore: **commit the built APK to `dist/` and always give the direct raw
  download link, with size and SHA-256, in every reply that produces a build.**
  CI artifact zips and "check the Actions tab" are not usable. Do not make them
  ask for the link.
- They report bugs by attaching the crash files the app writes to
  `Download/bitperfect_crash.txt` and `bitperfect_startup.txt`. Those stack
  traces are accurate — trust them and start there.

## Non-negotiables in this codebase

- **Nothing ships that only looks like it works.** Two features were deleted for
  exactly this: an equalizer wired to no audio path, and a simulated USB
  transport that reported healthy byte counts while emitting silence. If a
  feature cannot work yet, make the UI say so or refuse the operation.
- **Do not present unavailable state as working.** Diagnostics separates
  ring-buffer throughput from bytes actually sent to the DAC for this reason.
- **The bit-perfect USB path must not be quietly compromised.** No platform
  decoders, no audio effects, no resampling on that path. If a format cannot be
  handled exactly, refuse it and explain why.
- **Verify, then claim.** A green build is not evidence a feature works. Inspect
  the artifact: dex contents, exported JNI symbols, APK signature. Say plainly
  what was verified and what still needs hardware.

## Before you finish any change

```bash
./gradlew clean :app:assembleDebug          # clean matters, see HANDOFF.md §5
./gradlew :app:testDebugUnitTest            # expect 238 passing
./gradlew :app:lintDebug                    # expect 0 errors
cmake -S app/src/main/cpp -B build-test -DSTANDALONE_TEST=ON \
  && cmake --build build-test -j"$(nproc)" \
  && (cd build-test && ctest)               # expect 282 passing
```

Then refresh `dist/BitPerfect-debug-arm64.apk`, update its checksum in
`dist/README.md`, push, and give the maintainer the direct link.
