package com.bitperfect.android.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The timeline window the media session publishes from.
 *
 * This is where the notification and lock screen get the track length and the
 * metadata: media3 reads the current timeline window, not `Player.getDuration()`,
 * and media3 1.2.1's `MediaMetadata` carries no duration at all. An empty
 * timeline is exactly why the panel showed `--:--` and "Unknown song".
 *
 * Testable off-device because `Timeline` and `MediaItem` are plain Java. The tests
 * avoid `MediaItem.Builder.setUri`, which parses through `android.net.Uri` and is
 * not mocked in a JVM unit test.
 */
@DisplayName("SingleItemTimeline Tests")
class SingleItemTimelineTest {

    private fun item(title: String = "Song", artist: String = "Artist") =
        MediaItem.Builder()
            .setMediaId("/music/$title.flac")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build()
            )
            .build()

    private fun windowOf(timeline: Timeline): Timeline.Window =
        timeline.getWindow(0, Timeline.Window())

    // --- Shape ---

    @Test
    @DisplayName("exposes exactly one window and one period")
    fun singleWindow() {
        // Not empty is the whole point: with no window there is nothing for media3
        // to publish, which is the bug this class fixes.
        val timeline = SingleItemTimeline(item(), durationMs = 1_000L)

        assertEquals(1, timeline.windowCount)
        assertEquals(1, timeline.periodCount)
        assertFalse(timeline.isEmpty)
    }

    @Test
    @DisplayName("the window carries the media item, so the panel can read its metadata")
    fun windowCarriesMediaItem() {
        val mediaItem = item(title = "Says So", artist = "Hania Rani")
        val window = windowOf(SingleItemTimeline(mediaItem, durationMs = 5_000L))

        assertSame(mediaItem, window.mediaItem)
        assertEquals("Says So", window.mediaItem.mediaMetadata.title)
        assertEquals("Hania Rani", window.mediaItem.mediaMetadata.artist)
    }

    // --- Duration, the reason for `--:--` ---

    @Test
    @DisplayName("a known duration is reported in microseconds")
    fun durationConvertedToMicroseconds() {
        // media3 windows are in microseconds; reporting milliseconds would show a
        // track a thousand times too short.
        val timeline = SingleItemTimeline(item(), durationMs = 4 * 60 * 1000L)
        val window = windowOf(timeline)

        assertEquals(240_000_000L, window.durationUs)
        assertEquals(240_000L, window.durationMs)
    }

    @Test
    @DisplayName("an unknown duration is TIME_UNSET, not zero")
    fun unknownDurationIsUnset() {
        // Before a file is opened the length genuinely is unknown. TIME_UNSET makes
        // the system print `--:--`; zero would claim a zero-length track and give a
        // full or broken scrubber.
        for (unknown in listOf(0L, -1L)) {
            val window = windowOf(SingleItemTimeline(item(), durationMs = unknown))
            assertEquals(C.TIME_UNSET, window.durationUs, "durationMs=$unknown")
        }
    }

    @Test
    @DisplayName("the period reports the same duration as the window")
    fun periodMatchesWindow() {
        val timeline = SingleItemTimeline(item(), durationMs = 90_000L)
        val period = timeline.getPeriod(0, Timeline.Period())

        assertEquals(90_000_000L, period.durationUs)
        assertEquals(0L, period.positionInWindowUs)
    }

    // --- Seekability ---

    @Test
    @DisplayName("the window is seekable, so the scrubber can be dragged")
    fun windowIsSeekable() {
        val window = windowOf(SingleItemTimeline(item(), durationMs = 10_000L))

        assertTrue(window.isSeekable)
        assertFalse(window.isDynamic)
        assertEquals(0L, window.defaultPositionUs)
    }

    @Test
    @DisplayName("the window is not live, so no live edge is drawn")
    fun notLive() {
        val window = windowOf(SingleItemTimeline(item(), durationMs = 10_000L))

        assertEquals(C.TIME_UNSET, window.presentationStartTimeMs)
        assertEquals(C.TIME_UNSET, window.windowStartTimeMs)
    }

    // --- Period identity, which position reports are matched against ---

    @Test
    @DisplayName("the period uid round-trips, so reported positions are not discarded")
    fun periodUidRoundTrips() {
        // media3 looks a reported position's period up by uid. If getUidOfPeriod
        // and getIndexOfPeriod disagree the position is dropped and the bar stops.
        val timeline = SingleItemTimeline(item(), durationMs = 10_000L)
        val uid = timeline.getUidOfPeriod(0)

        assertEquals(SingleItemTimeline.WINDOW_UID, uid)
        assertEquals(0, timeline.getIndexOfPeriod(uid))
    }

    @Test
    @DisplayName("an unknown period uid reports INDEX_UNSET rather than window 0")
    fun unknownUidIsUnset() {
        val timeline = SingleItemTimeline(item(), durationMs = 10_000L)

        assertEquals(C.INDEX_UNSET, timeline.getIndexOfPeriod("something else"))
    }

    @Test
    @DisplayName("the window uid matches the period uid")
    fun windowUidMatchesPeriod() {
        val timeline = SingleItemTimeline(item(), durationMs = 10_000L)

        assertEquals(timeline.getUidOfPeriod(0), windowOf(timeline).uid)
    }

    @Test
    @DisplayName("ids are only populated when asked for")
    fun periodIdsAreOptional() {
        val timeline = SingleItemTimeline(item(), durationMs = 10_000L)

        assertNotNull(timeline.getPeriod(0, Timeline.Period(), true).uid)
        // media3 calls this with setIds = false on hot paths; it must not throw.
        timeline.getPeriod(0, Timeline.Period(), false)
    }

    // --- Navigation helpers media3 derives from the timeline ---

    @Test
    @DisplayName("a single window has no next or previous window inside the timeline")
    fun navigationWithinTimeline() {
        // Track changes are driven by PlaybackController, not by walking this
        // timeline, so it correctly reports one item with nothing either side.
        val timeline = SingleItemTimeline(item(), durationMs = 10_000L)

        assertEquals(
            C.INDEX_UNSET,
            timeline.getNextWindowIndex(0, Player.REPEAT_MODE_OFF, false)
        )
        assertEquals(0, timeline.getFirstWindowIndex(false))
        assertEquals(0, timeline.getLastWindowIndex(false))
    }
}
