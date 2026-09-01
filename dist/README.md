# Prebuilt APKs

Debug builds committed here so they can be installed straight from a phone,
without a local toolchain or a GitHub login.

## Download

**[BitPerfect-debug-arm64.apk](https://github.com/manishyadavji14-hash/Tata/raw/main/dist/BitPerfect-debug-arm64.apk)**

Open that link in the phone's browser and it downloads directly. Android will
ask you to allow installing from the browser the first time.

## What this build is

| | |
|---|---|
| Contains | everything on `main` up to and including embedded lyrics (`feat/embedded-lyrics`) |
| ABI | `arm64-v8a` only |
| minSdk / targetSdk | 29 / 36 |
| Signing | Android debug key, APK Signature Scheme v2 |
| Size | 15.7 MiB (16,503,892 bytes) |
| SHA-256 | `443b021ba259b4b9d120680e11cc5681471918f1883e1ed8f6f1f2ef3e8d5b56` |

Verify the download matches before installing:

```bash
sha256sum BitPerfect-debug-arm64.apk
```

If you get `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall any existing
BitPerfect first. Debug signing keys differ between machines, so an APK built
elsewhere cannot upgrade this one in place.

## New in this build

Lyrics stored **inside** the music file are now read, not just `.lrc` sidecars:
ID3 `USLT`/`SYLT` (MP3), Vorbis `LYRICS`/`UNSYNCEDLYRICS` (FLAC, Ogg, Opus) and
the MP4 `©lyr` atom (M4A, ALAC).

To check it, play a track you know has lyrics baked into its tags. The lyrics icon
appears at the lower right of the album art **only when lyrics were found**, so if
it is absent that file's tags had none — try one tagged in a desktop player first.
A `.lrc` file next to the track still wins over the tags, so your own corrections
are not overridden.

## Also worth checking



This is the first build in which audio can actually reach a USB DAC, so start
here rather than with playback:

1. Attach the DAC by OTG and grant the USB permission prompt.
2. Open **Diagnostics** and find the **Transport** card.
3. It must read `usbdevfs isochronous`. If it reads `loopback (no hardware)`,
   the streaming interface was never claimed and audio is going to the Android
   mixer instead.

`Sent To DAC` should climb during playback. `Read From Buffer`, under Buffer
Status, moves whether or not a DAC is attached, so it is not evidence of USB
output.

The full procedure and the known limitations are in
[TESTING.md](../TESTING.md#validating-usb-dac-output-on-hardware).

## A note on keeping binaries in git

A 67 MiB file in version control is permanent: it stays in history even if
deleted later, and everyone who clones pays for it. It is here because
installing on a phone is the priority and the alternatives are worse on mobile —
CI artifacts arrive as a zip and require being signed in.

The cleaner option, if this repo ever gets cloned regularly, is to push a `v*`
tag: `.github/workflows/android.yml` builds the APK and attaches it to a GitHub
Release, whose download links work on mobile without a login and do not touch
git history. Releases would then replace this directory.
