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
| Contains | everything on `fix/usb-dac-never-claimed`: the USB DAC wiring below, the spectrum analyser and draggable player, library sort, play statistics, the per-song menu, and the album-art fixes through to the MediaStore thumbnail fix |
| ABI | `arm64-v8a` only |
| minSdk / targetSdk | 29 / 36 |
| Signing | Fixed debug key committed to this repo (`CN=BitPerfect Debug`), SHA-256 `131cba07…eccff5` — stable from this build onwards, so future builds install straight over the top |
| Size | 16.2 MiB (16,995,470 bytes) |
| SHA-256 | `629acb33486af4b6982d4b8092e39ac7d72e6812f4701434c29d23f5bf4c6f64` |

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

**Your lossless files no longer skip themselves, and they play.** The runaway
skipping was the worst of it and it had a single stupid cause: when a track failed on
the DAC, the USB error-recovery code classified it as a decoder error and its response
to that is *skip to the next track*. Every WAV and FLAC failed identically, so one tap
ran the whole queue down at speed until it hit an MP3 — which played, because that file
never went near the DAC. Nothing was wrong with your FLACs.

A track the DAC will not take now **falls back to Android's output and keeps playing**,
staying exactly where it is in the queue. Nothing skips. The Audio info panel says why
under "Not using the DAC". So worst case you get your music through Android's mixer with
an honest explanation, instead of a library that fast-forwards through itself.

**Fixed a message that sent me looking in the wrong place too.** When the bit-perfect
decoder could not open a file, the app said *"Bit-perfect USB output supports WAV and
FLAC"* — about a FLAC file. It now distinguishes "this format has no exact decoder"
from "the FLAC decoder could not open this particular file", which are completely
different problems.

**The transport could die mid-track in total silence.** Each block of audio is handed
to the kernel and re-queued when it comes back. If a re-queue was refused, that block
dropped out of the rotation permanently — and the return value was discarded. Once all
four had dropped out the stream was dead while every flag still said it was running:
the buffer filled, never drained, and the writer waited on a device that had stopped
listening. Forever, with nothing reported. Your screenshot showed exactly this
fingerprint — **8 rejected** with the stream reporting no error. Both halves are fixed:
the stream now admits when it has died, and the player reports it.

**Packets were splitting audio frames.** At 44.1 kHz/16-bit/stereo the app sent 23-byte
packets against a 4-byte frame, so every packet after the first began part-way through a
sample and the channel order shifted through the stream. Packet sizes are now always a
whole number of frames. To be clear about what this does *not* fix: 44,100 frames a
second does not divide evenly into the USB schedule, so a fixed packet size still cannot
carry the rate exactly — that needs the DAC's feedback channel, which the engine reads
and does not yet act on. If it now plays but sounds slightly fast, that is why.

**Stale numbers are labelled as stale.** Your panel showed a 44.1 kHz packet size above
a 48 kHz file, because those values describe the last stream that got as far as being
configured, not the track on screen. That row now says **"Last configured stream"**, and
the rejection count resets per attempt.

---

**The status now follows the song you are playing.** You were right, and this was a
real fault, not a cosmetic one. That "Status" line was showing the last thing that
*happened* to the DAC, not what is true *now* — so a message about your one M4A track
stayed on screen as the apparent verdict on every FLAC track after it. It is now
rebuilt from the engine every time you open the panel. The event itself is still
there, on its own row, honestly labelled **"Last USB event"**.

**Found why the FLAC track would not play, and it is not the FLAC.** Your screenshot
was enough to pin it down exactly, because of what it ruled out: transport
`usbdevfs isochronous`, claimed **Yes**, engine rate **44100** — so the DAC was
claimed, the real transport was installed, and the file opened and configured fine.
Streaming **No** with all of that true leaves exactly one possibility: the kernel
rejected the very first block of audio.

And the reason it looked like nothing happening is that the app **threw the failure
away**. The engine's "start playing" call ignored whether the audio stream actually
started and reported success either way — it even logged "Playback started". The only
trace left was that flag reading No. That is fixed: a start that did not start now
says so.

**The likely cause, and the fix I have made for it.** A USB DAC advertises several
"alternate settings" — one per bit depth and rate. Two separate pieces of this app
were choosing one independently and never comparing notes: the USB layer switched the
device to whichever setting it found first, while the engine addressed the audio
endpoint belonging to the setting that matches the track's rate and bit depth. When
those differ — which is any DAC with more than one setting — the kernel rejects the
first block, because the endpoint is not in the setting that is actually active. The
engine now says which setting it needs and the USB layer switches to that one, before
any audio is sent.

I cannot confirm this from here, so the same build also makes the answer readable:
**if it still does not stream, the player now tells you precisely why** — "the DAC has
no endpoint 0x… in its active setting 1", or "will not accept 23-byte packets", or
"the bus has no bandwidth left". The kernel's reason for refusing was previously
discarded on the spot; it is now kept and shown. The Audio info panel also gained a
**Stream** row with the interface, alternate setting, endpoint, packet size and
rejection count.

> **What to send me if it still does not play:** the **Status** line, the **Stream**
> line, and the red message on the player itself. Those three name the exact cause.

**Correction to my own notes:** the FLAC decoder's documentation claimed it could not
handle LPC compression and would emit silence. That was false — LPC has been fully
implemented for some time. Since LPC is what every real FLAC encoder produces, that
note read as "this cannot play normal FLAC files", and it nearly made me abandon a
working code path. Both the decoder's comment and the project handoff are corrected.

---

Three faults found from your screenshots, all in the DAC path. The good news first:
**the DAC itself works.** "TTGK Technology Co.,Ltd Audiocular Spark ready" means the
app took the audio interface away from Android's driver and the engine accepted it.
Everything below is about getting your music onto it.

**"Just once" did nothing.** This is the big one. Android's "choose an app for the USB
device" dialog *is* a permission grant — but it grants silently, because the app never
asked, so no result is sent anywhere. The app was waiting for an answer to a question
it hadn't been asked, and never looked to see that it already had permission. Picking
BitPerfect and tapping "Just once" therefore left the DAC unopened and the app
honestly reporting Android output. It now re-checks the moment that dialog is
answered, so "Just once" and "Always" both work.

**Only one dialog now.** You were getting two at once — Android's chooser, and the
app's own permission request on top of it — where answering either dismissed the
other. The app no longer asks on its own, because the chooser already does the job.
If you ever dismiss the chooser by accident, there is now an **"Ask Android for DAC
access"** button in the Audio info panel, which is a prompt you asked for rather than
one that lands on top of another.

**The DAC takes over the song that is already playing.** It used to wait for the next
track, which is why it said "ready" and carried on through Android's output — reading
exactly like the failure it was meant to have fixed. The current track now moves to
the DAC at the position it had reached. There is a brief gap while it moves: each
output owns its own worker thread and buffered audio, so this is a deliberate reopen,
not a swap underneath the stream. Unplugging the DAC moves playback back the same way,
instead of the music simply stopping.

**A file the DAC cannot take no longer stops playback.** This is the "sometimes it
doesn't play any song" case: your 40 Hz binaural test track is AAC, and AAC has no
exact decoder — nothing can hand a DAC a stream that Android's codec decoded and still
call it bit-perfect. The app used to start the track on the DAC anyway, fail, and reset
to `0:00` with "No device" and the reason gone a moment later. It now sends that track
to Android's output instead and says why, in the Audio info panel under **"Not using
the DAC"**. WAV and FLAC — which is almost everything in your library — still go
straight to the DAC untouched. The badge also no longer claims "No device" while a DAC
is plugged in.

---

**The USB DAC is finally connected to the app (same build).** This is the fix for "I
select BitPerfect in the USB dialog and it still says mixed by Android".

The app was not failing to claim your DAC. It never tried. Every part of the USB
chain was written — the code that claims the audio interface away from Android's
driver, hands the file descriptor to the engine and negotiates the sample rate over
control transfers — and not one line of it was ever called. Nothing registered a
listener, nothing started monitoring, and the only place that registered for USB
events was the playback service, which by design does not exist until music is
already playing. So at the exact moment a DAC is plugged in and Android offers to
launch this app, there was nothing in the app listening.

The engine was therefore never told a DAC existed, and the rule that picks the output
— "USB if a DAC is attached, otherwise Android's mixer" — had no attached DAC to find.
"Android output / mixed by Android" was a completely honest report. Choosing BitPerfect
in that dialog granted permission to a component that was not listening for it.

Three further faults were in the way behind it:

- **The permission request would have crashed the app** on Android 14 and newer, from
  inside a broadcast receiver, because of how the pending intent was built. That is the
  path taken by a DAC attached while the app is already open.
- **Device-attached and device-detached events could not be received at all.** They are
  sent by Android, and the receiver was registered as accepting nothing from outside the
  app. The permission result, which really does come from this app, needs the opposite
  setting — so the three were split apart.
- **Stopping the playback service would have dropped the DAC.** It detached USB from the
  shared engine on the way out, even though the DAC belonged to the activity, silently
  returning playback to the Android mixer.

**When you plug in a DAC, the app now says what happened.** Every failure in the attach
sequence used to be a log line, which on a phone means it never existed. There is now a
**USB DAC** section in the Audio info panel — player, output badge at the bottom left —
with a plain-language status line and a "Claimed by engine" row. It distinguishes the
cases that all used to look identical: no DAC, permission refused, another driver
holding the audio interface, a device with no isochronous output endpoint, and a device
the engine rejected. The same sentence appears as a message on the player when it
happens.

> **This is the one thing I need you to check**, because I have no DAC here and nothing
> about USB can be tested without the hardware. Attach the DAC, choose BitPerfect, then
> **play a FLAC track** and open the player → output badge → **Audio info**. Send me the
> **USB DAC → Status** line and the **Claimed by engine** row. If the status names a
> specific failure, that tells me exactly which step to fix next; if it says the DAC is
> ready and claimed, **Bit-perfect** above it should read "Yes — samples unmodified".

**The output badge shows the right icon (previous build).** It was always a USB symbol, so a phone
playing through its own speaker still claimed a DAC in the chain. It now shows a phone
when audio is going to Android's output and the USB symbol only when a DAC really is
receiving it.

**The spectrum is balanced across the range.** Each bar used to report its loudest
single frequency, which sounds reasonable but tilts the whole display: the bars are not
equal in width — the lowest covers one frequency step and the highest covers about
sixty-five — so measuring a single peak understated the treble by roughly 3 dB per
octave. Each bar now reports the total energy in its range, which makes music sit level
across the display instead of leaning into the bass.

**A real spectrum analyser on the player (previous build).** A row of bars above the seek bar, showing
the actual frequency content of what is playing — bass on the left, treble on the
right. It reads the audio this app has already decoded, so it works for every format
and on both outputs, including the bit-perfect USB path.

It does **not** use Android's built-in visualiser, which would have needed microphone
permission, would have returned deliberately low-quality audio, and — because it
attaches to an Android audio session — would have shown nothing at all on the USB
path, the one output this app exists for.

Your samples are untouched: the analyser reads a copy, on the same thread that was
already reading the file, never on the thread that feeds the DAC. When the player
screen is not open it does no work at all.

**The album art is one object that moves between the two players (previous build).** Drag the
player open and the small cover in the bar physically grows and travels into the big
cover on the player screen — same square, moving, with its rounded corner opening out
as it goes. Drag back down and it returns. Previously the two covers cross-faded, so
you saw one picture dissolve into another in a different place; now it reads as the
same thing changing size.

**The player is no longer see-through while you drag it.** It was fading in across the
whole gesture, so for most of the drag you could see the library through it and the
whole thing looked washed out. It becomes solid early and then simply slides.

**Fixes navigation while the player is open (previous build).** Tapping Library or Settings from the
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



This is the first build in which the app even attempts to claim a USB DAC, so start
here rather than with playback:

1. Attach the DAC by OTG and grant the USB permission prompt (or choose BitPerfect
   in Android's "choose an app for this USB device" dialog).
2. Open the player, tap the **output badge at the bottom left**, and read the
   **USB DAC** section. `Status` says how far the attach got; `Claimed by engine`
   must read Yes.
3. Then play a track and open **Diagnostics** → the **Transport** card.
4. It must read `usbdevfs isochronous`. If it reads `loopback (no hardware)`,
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
