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
| Contains | everything on `feat/library-sort-and-track-actions`: library sort, play statistics, the per-song menu, and the album-art fixes through to the MediaStore thumbnail fix |
| ABI | `arm64-v8a` only |
| minSdk / targetSdk | 29 / 36 |
| Signing | Fixed debug key committed to this repo (`CN=BitPerfect Debug`), SHA-256 `131cba07…eccff5` — stable from this build onwards, so future builds install straight over the top |
| Size | 16.1 MiB (16,913,550 bytes) |
| SHA-256 | `b021a80a94bdc740ee14ca70669c0a33606eae2fb56784a7a400fe944f19d3e7` |

Verify the download matches before installing:

```bash
sha256sum BitPerfect-debug-arm64.apk
```

## Read this before installing (one last uninstall)

**This build is signed with a new, permanent key, so you have to uninstall
BitPerfect one final time.** After this one, every future build installs straight
over the top and keeps your library and permissions.

Until now the app was signed with whatever throwaway key the build machine happened
to have. Every rebuild produced a different signature, and Android refuses to
update in place across a signature change — so each build silently required an
uninstall, and **an uninstall resets every permission you had granted**. That is
what stopped the playback notification appearing: a fresh install begins with
notifications denied, and the app had no way of telling you. Both halves of that are
now fixed.

**After installing, if you see no notification:** open the player, tap the output
badge at the bottom left, and the Audio info panel will say whether notifications
are blocked, with an **Allow** button that takes you straight to the setting.

## New in this build

**Fixes navigation while the player is open.** Tapping Library or Settings from the
player did nothing visible: the screen behind really did change, but the open player
stayed on top of it. Selecting a tab now closes the player first. Tapping the
collapsed bar also works reliably again — it was drawn at the bottom of the screen
while its tap target could remain at the top, so the tap could miss entirely.
Screens like the equalizer also no longer have their bottom edge hidden underneath
the collapsed bar.

**Fixes the crash when tapping a song (previous build).** The previous build crashed the moment you
tapped a track in the library. Moving the player out of the navigation graph left one
place still trying to *navigate* to it, and that destination no longer existed. Fixed,
and the type now makes that mistake impossible to write rather than merely corrected.

**The mini player and the full player are one surface you can drag.**

Put your finger on the mini player and pull up: the player follows your finger the
whole way, at whatever speed you move. Pull down from the top of the full player and
it goes back down, revealing the library underneath as it goes. Change your mind
half-way and reverse — it just follows, with nothing to unwind or restart.

Let go and it decides where to land: a flick sends it there even from a few pixels in,
while a slow drag goes wherever it is closest to. Back also collapses it now, which
matches pulling it down.

This needed a real change underneath. The mini player and the full player used to be
two separate screens, and Android only ever keeps one of them on screen at a time —
so a continuous drag between them was impossible, and the old pull-up could only wait
for you to let go and then play a fixed animation. The player now lives *above* the
rest of the app as a single surface that slides. Nothing about how either one looks
has changed.

Every existing gesture still works exactly as before, because the drag is fed from
inside the gesture handlers that were already there rather than wrapped around them:
tapping the mini player still opens it, swiping it sideways still changes track, and
on the full player the seek bar, transport buttons and artwork swipe are all untouched.
The pull-down is still limited to the top half of the player, so it can never fight
the seek bar.

**Motion and feel (previous build).**

- **The progress bar glides.** It used to jump four times a second, because that is
  how often the player reports its position. It now moves continuously between those
  reports — and still snaps instantly when you seek or change track, because sliding
  across to meet a seek would read as the app being slow.
- **The album art follows your finger.** Drag sideways and it moves and tilts with
  you, previewing the track change instead of only reacting when you let go; drag
  down and it shrinks towards the mini player it is about to become. Let go without
  committing and it springs back.
- **Covers now slide in the direction you swiped** rather than cross-fading in place,
  so the gesture and the result agree. Swipe left and the next cover comes in from
  the right; swipe right and it comes from the left.
- **Haptics on the transport** — a firmer tap for play/pause and favourite, a lighter
  tick for skipping tracks, and a distinct one for releasing the seek bar. It uses
  Android's own expressive haptics where the phone is new enough and falls back
  gracefully where it is not.
- **Track changes are lighter.** The accent colour taken from each cover was being
  computed with a brand-new image loader every single time, which bypassed all
  caching and re-decoded the artwork the app had *just* decoded for the screen.
  It now shares the app's loader and remembers colours it has already worked out, so
  swiping back and forth through a queue no longer redoes the work.

Your existing layout, navigation and controls are unchanged, and none of this touches
the audio path.

**The missing notification was my build process, not the notification code.**

Every build was signed with a different throwaway key, so each one forced an
uninstall — and an uninstall wipes every permission you had granted, including
permission to post notifications. A fresh install starts with notifications denied,
and the app said nothing about it, so it looked like the notification feature had
broken. The key is now fixed and committed, so this is the **last** uninstall; and
the app now tells you when notifications are blocked and offers a one-tap **Allow**.

Also hardened: the music notification used to be thrown away completely if the media
session was unavailable for any reason, when all that is really lost is the scrubber.
It now always shows the track and its controls.

**A-Z jump strip in the library.** Scroll the Tracks list while it is sorted by name
and a letter strip fades in down the right edge. Drag it to jump — the letter you are
on is shown in a bubble clear of your finger — and it fades out two seconds after you
stop. Only the letters your library actually has appear, so every one goes somewhere,
and it works in both directions: sort Z-A and the strip reads Z-A. Titles starting
with a number or a symbol group under `#`, and non-English titles keep their own
letter rather than being lumped together.

**Album art on the lock screen: three more faults, and a way to see what happens.**

The one that most likely explains it: covers were being decoded at up to twice the
intended size, and the step that packs one for the lock screen gave up **silently**
if the result came out too large — no message, no retry, no second attempt at lower
quality. A cover that had been found and decoded perfectly well simply never
arrived, which is exactly "it shows in the app but not on the lock screen". Covers
are now sized correctly and, if one still will not fit, it is made smaller rather
than dropped.

The second: Android does not draw the cover from the data we hand the media session.
It asks a decoder for it first — and the built-in decoder opens covers the one way
MediaStore refuses, the same mistake fixed inside the app last build. So the
data's companion reference could never work as a backup. The app now supplies its own
decoder, shared with the rest of the app.

The third: if loading a cover failed once, it was never tried again for that song.
"Not yet" and "there is none" were the same state; they no longer are.

> **Please check this one for me.** I have no device, and this has now been fixed
> four times. Play a song, then open the player, tap the **output badge at the bottom
> left**, and read the new **"Lock screen → Album art"** line at the bottom. It says
> whether the cover actually reached the lock screen, and if not, which stage stopped
> it. That single line tells me more than any guess.

**Album art: the app was asking Android for covers the wrong way (previous build).** This is a
different fault from the previous three, and it explains the part that kept coming
back — covers showing in the app but not on the lock screen.

Android hands out an album cover as a *thumbnail*, which has to be requested
specifically. Asking for it as an ordinary file, which is what this app did, fails
every time. Coil, the library that draws covers inside the app, happens to ask the
correct way — so covers appeared there and nowhere else. Three symptoms that looked
unrelated were all this one cause:

- the **lock screen and notification** got no cover for any track Android had
  indexed;
- **every play re-read the whole audio file** hunting for a cover, because the
  reference already stored could never be confirmed as working;
- **"Rebuild album art" counted those tracks as having no cover**, which was the
  opposite of the truth — so the report was misleading exactly where it mattered.

There is now one piece of code that opens covers, shared by both, and it asks the
same way Coil does.

**A scan can no longer cost a track a cover it already had.** A scan writes the
whole library row, so if reading a cover failed for a moment mid-scan, the working
one was replaced by whatever the scan happened to carry. The "never replace
something with nothing" rule from the last build now covers scanning too.

**Small ones:** the cover cache was evicting by *write* order rather than by use, so
a constantly played album's cover could be dropped in favour of one never looked at;
and a cover that fails to load now says so in the log instead of falling back to a
placeholder identical to "there is no cover", which is what made these faults so
hard to tell apart.

**Album art: a repair pass was erasing covers (previous build).** The background repair wrote back
whatever it resolved — *including nothing*. When it could not read a cover it
overwrote the recorded MediaStore reference with null, which cannot be undone
without a rescan, so a single pass could strip artwork from a whole library. It now
never replaces something with nothing.

> If your library currently shows no art at all, **run a scan once** (the refresh
> icon on the Library screen). That restores the references the old pass erased.
> Then Settings -> Library -> "Rebuild album art" reports how many tracks have a
> cover, broken down by format, so a remaining placeholder is explainable rather
> than mysterious.

**The grey disc over the tab row is gone** — Material3's pull-to-refresh container
paints its circular surface even when idle, so it is now only composed while the
gesture is actually active.

**Album art: format coverage (previous build).** The app read covers only
through Android's `MediaMetadataRetriever`, whose picture support does not cover the
formats the library accepts. It misses the base64 `METADATA_BLOCK_PICTURE` comment
that **Ogg Vorbis and Opus** use, and it cannot parse **DSF/DSD** at all — so those
files showed a placeholder however well they were tagged, while MP3, M4A and FLAC
worked. Covers are now parsed out of the container directly: ID3 `APIC`, FLAC
`PICTURE` blocks, the base64 comment form, DSF's trailing ID3 tag, and the MP4
`covr` atom.

The library also repairs itself in the background now, so covers fill in on their
own without needing Settings → "Rebuild album art". That button still exists and
now reports how many files genuinely have no cover stored, so a remaining
placeholder reads as "nothing to show" rather than "still broken".

**Album art: the intermittent case (previous build).** Covers appeared sometimes, or in the
app but not on the lock screen. A track change asks for the same cover twice at
once — the player wants it for the screen, the playback service for the
notification — and both writes to the artwork cache shared one temporary filename.
They interleaved into a corrupt image, or one write renamed the temporary away and
the other then reported no artwork at all. Each write now gets its own temporary,
and the two lookups share one extraction instead of racing.

**Pull down on the player minimises it.** The gesture called `popBackStack()`, but
the player is the app's start destination so there was nothing to pop and it did
nothing at all. It now falls back to the library. Pull-down also works when it
starts on the album art, which is where it naturally does.

**The playing song is marked in the library, and the list opens at it** instead of
at the top.

**Album art (earlier fix).** Covers are also read out of the files themselves, not
just taken from Android's media index, so a file with a cover inside it shows one
even when Android never extracted it.

> An earlier version of this note said the app was storing a deprecated MediaStore
> URI that no longer resolved. That turned out to be wrong: the reference it stores
> is current and valid, and the real fault was *how* the app asked for it — see the
> top of this list. Correcting it here because that mistaken explanation is what
> kept the underlying bug hidden through three builds.

> If art is still missing for music added before this update, open **Settings →
> Library → "Rebuild album art"**. That re-reads the cover from each file and is
> much quicker than a full rescan. New scans do it automatically.

**Shuffle and repeat stick.** Turning shuffle on and then tapping a song reset the
queue to list order while the button still showed shuffle as active, so tracks kept
playing in order. Reaching the end of a queue also silently switched both back off.

**Bass and treble do something audible.** Treble put nearly all its gain at 14 kHz,
where there is almost no music; it is now a shelf from 2 kHz up. Bass works even on
devices with no bass-boost effect. Your saved curve is also applied at startup now,
instead of only after opening the Equalizer screen.

**Swipe the album art left or right** to change track, like the mini player.

**Tapping the mini player always opens the player.** A slightly smudged tap fell
between the tap and swipe thresholds and was discarded.

**The player shows a song on first launch** instead of an empty screen with a dead
transport.

**Audio info panel.** Tap the output badge at the bottom-left of the player for the
signal chain: source format, decoder, effects, output device, whether it is
bit-perfect, and buffer/underrun counters. Anything not actually measured says
"Not reported" rather than showing a made-up zero.

Also fixed: the album-art three-dot menu never appeared at all — the state it was
gated on was never set — and the player showed a hardcoded "Android AudioTrack"
instead of the real output.

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
