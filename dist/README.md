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
| Contains | everything on `main` up to and including library sort, play statistics and the per-song menu (`feat/library-sort-and-track-actions`) |
| ABI | `arm64-v8a` only |
| minSdk / targetSdk | 29 / 36 |
| Signing | Android debug key, APK Signature Scheme v2 |
| Size | 15.9 MiB (16,684,116 bytes) |
| SHA-256 | `9de2f4fe1e676ad82e803292342acb37488f43820764af9cd70ee4cc8348c7df` |

Verify the download matches before installing:

```bash
sha256sum BitPerfect-debug-arm64.apk
```

If you get `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall any existing
BitPerfect first. Debug signing keys differ between machines, so an APK built
elsewhere cannot upgrade this one in place.

## New in this build

**The notification and lock screen now show the track.** Title, artist, album art
and both times were missing — the panel read "Unknown song" with `--:--` at each
end and a progress bar that never moved. The media session was never being told
what was playing, and it had no timeline for the system to read a track length
from. Both are fixed, so the shade, the lock screen and vivo's Origin Island have
something to display and the progress bar tracks playback.

Check it by playing anything and pulling down the shade: you should see the real
title and artist, the cover, the elapsed and total time, and a scrubber you can
drag. Origin Island is a vivo feature that reads the same media session, so it
should animate now too — that part could not be tested here, so please say if it
still does not.

**Sort the library.** The sort button on the Library screen now opens a labelled
menu showing which order is active: name A-Z/Z-A, date added newest/oldest,
format, and most played. Only the orders that mean something on the current tab
are offered.

**Most played** ranks on the share of each track actually listened to, added up
over every play, so it can exceed 100%: a four-minute track played once and then
replayed for a minute reads 125%. Seeking does not count. The figure is visible
per track under Info / Tags, so it can be checked rather than taken on trust. It
starts from zero — the app has not been counting until now — so this order is
only meaningful after some listening.

**Untagged audio is kept out of the library properly now.** The rule is the
artist: a file that does not say who made it goes to Settings →
"Review unconfirmed music" instead of the main library, and that includes files
tagged with placeholder names like "Unknown Artist". Previously anything with a
stray year or a scrap of folder artwork got in, which is why WhatsApp clips and
voice notes kept appearing.

> **On first launch after this update**, files already in your library that name
> no artist will move into "Review unconfirmed music". Nothing is deleted. If real
> music is caught, select it there and tap "Move to library" — that is permanent.

**Every song row has a three-dot menu** at its right end: Play, Add to playlist
(existing or new), favourite, Info / Tags, Edit tags, Lyrics, Remove from library.

- **Remove from library does not delete the file.** It drops the library entry; a
  later scan of that folder finds the file again.
- **Edit tags is library-only.** It changes how BitPerfect files the track; the
  file's own tags are untouched, because the app has no tag writer. The edit does
  survive a rescan.
- **Lyrics** accepts LRC (`[00:12.50]First line`) or plain text, and can remove
  lyrics — including lyrics embedded in the file, which stay hidden rather than
  reappearing.

**About** now names the creator, Maneesh Yadav.

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
