package com.bitperfect.android.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The listening-time accounting behind the library's "most played" order.
 *
 * Two things have to hold for that order to mean anything: real listening is
 * counted in full, and seeking is not counted at all. A recorder that credited
 * seeks would rank whichever track someone scrubbed through the most, which is
 * the opposite of the intent.
 */
@DisplayName("PlayStatsRecorder Tests")
class PlayStatsRecorderTest {

    private val track = "/music/song.flac"
    private val other = "/music/other.flac"

    /** Play from [from] to [to] in [stepMs] steps, as the position loop would. */
    private fun PlayStatsRecorder.playThrough(
        path: String,
        from: Long,
        to: Long,
        stepMs: Long = 250L
    ) {
        var position = from
        while (position < to) {
            position = minOf(position + stepMs, to)
            sample(path, position)
        }
    }

    // --- The rule ---

    @Test
    @DisplayName("playing a track from start to finish credits its whole duration")
    fun fullPlayCreditsDuration() {
        val recorder = PlayStatsRecorder()
        val duration = 4 * 60 * 1000L

        recorder.startSegment(track, 0L)
        recorder.playThrough(track, 0L, duration)

        assertEquals(duration, recorder.takePending()[track])
    }

    @Test
    @DisplayName("a four-minute track played once then replayed for a minute totals 125%")
    fun cumulativeAcrossPlaysExceeds100Percent() {
        // The worked example the feature was specified with: one full play plus a
        // minute replayed by seeking back is five minutes of a four-minute track.
        val recorder = PlayStatsRecorder()
        val duration = 4 * 60 * 1000L

        // Played in full.
        recorder.startSegment(track, 0L)
        recorder.playThrough(track, 0L, duration)

        // Seeked back to 3:00 and played the last minute again.
        recorder.startSegment(track, 3 * 60 * 1000L)
        recorder.playThrough(track, 3 * 60 * 1000L, duration)

        val listened = recorder.takePending().getValue(track)
        assertEquals(5 * 60 * 1000L, listened)

        // Which is what the library turns into a percentage.
        val percent = (listened * 100 / duration).toInt()
        assertEquals(125, percent)
    }

    @Test
    @DisplayName("a track abandoned early credits only what was heard")
    fun partialPlay() {
        val recorder = PlayStatsRecorder()

        recorder.startSegment(track, 0L)
        recorder.playThrough(track, 0L, 30_000L)

        val listened = recorder.takePending().getValue(track)
        assertEquals(30_000L, listened)
        assertEquals(12, (listened * 100 / (4 * 60 * 1000L)).toInt())
    }

    // --- Seeking must not count ---

    @Test
    @DisplayName("seeking forward credits nothing for the part skipped")
    fun forwardSeekNotCredited() {
        val recorder = PlayStatsRecorder()

        recorder.startSegment(track, 0L)
        recorder.playThrough(track, 0L, 10_000L)

        // The controller banks the position and rebases on a seek.
        recorder.sample(track, 10_000L)
        recorder.startSegment(track, 230_000L)
        recorder.playThrough(track, 230_000L, 240_000L)

        // Ten seconds heard at the start, ten at the end. Nothing for the jump.
        assertEquals(20_000L, recorder.takePending().getValue(track))
    }

    @Test
    @DisplayName("seeking backwards credits nothing, and replayed audio counts again")
    fun backwardSeekThenReplay() {
        val recorder = PlayStatsRecorder()

        recorder.startSegment(track, 0L)
        recorder.playThrough(track, 0L, 60_000L)
        recorder.startSegment(track, 30_000L)
        recorder.playThrough(track, 30_000L, 60_000L)

        // A minute, then the second half of it again.
        assertEquals(90_000L, recorder.takePending().getValue(track))
    }

    @Test
    @DisplayName("a backwards jump with no rebase is ignored rather than counted")
    fun backwardJumpWithoutRebase() {
        val recorder = PlayStatsRecorder()

        recorder.startSegment(track, 60_000L)
        recorder.sample(track, 10_000L)

        assertTrue(recorder.takePending().isEmpty())
    }

    @Test
    @DisplayName("a jump larger than the cap is not credited even without a rebase")
    fun oversizedJumpIsCapped() {
        // The backstop for a position jump that arrived without a matching
        // startSegment: a seek notification the controller did not see.
        val recorder = PlayStatsRecorder(maxCreditPerSampleMs = 5_000L)

        recorder.startSegment(track, 0L)
        recorder.sample(track, 200_000L)

        assertTrue(recorder.takePending().isEmpty())
    }

    @Test
    @DisplayName("a gap up to the cap is credited, so a delayed sample is not lost")
    fun gapWithinCapIsCredited() {
        val recorder = PlayStatsRecorder(maxCreditPerSampleMs = 5_000L)

        recorder.startSegment(track, 0L)
        recorder.sample(track, 4_000L)

        assertEquals(4_000L, recorder.takePending().getValue(track))
    }

    @Test
    @DisplayName("repeating the same position credits nothing")
    fun stalledPositionCreditsNothing() {
        val recorder = PlayStatsRecorder()

        recorder.startSegment(track, 5_000L)
        recorder.sample(track, 5_000L)
        recorder.sample(track, 5_000L)

        assertTrue(recorder.takePending().isEmpty())
    }

    // --- Multiple tracks ---

    @Test
    @DisplayName("the first sample of an unseen track takes a baseline instead of crediting it")
    fun firstSampleOfNewTrackIsBaseline() {
        // Joining a track already in progress must not credit everything up to the
        // current position as though it had been listened to.
        val recorder = PlayStatsRecorder()

        recorder.sample(track, 120_000L)
        recorder.sample(track, 121_000L)

        assertEquals(1_000L, recorder.takePending().getValue(track))
    }

    @Test
    @DisplayName("listening is accumulated per track")
    fun perTrackTotals() {
        val recorder = PlayStatsRecorder()

        recorder.startSegment(track, 0L)
        recorder.playThrough(track, 0L, 20_000L)
        recorder.startSegment(other, 0L)
        recorder.playThrough(other, 0L, 5_000L)

        val pending = recorder.takePending()
        assertEquals(20_000L, pending[track])
        assertEquals(5_000L, pending[other])
    }

    @Test
    @DisplayName("switching tracks without a rebase does not move time between them")
    fun trackSwitchDoesNotLeak() {
        val recorder = PlayStatsRecorder()

        recorder.startSegment(track, 0L)
        recorder.sample(track, 10_000L)
        // Next track reports a position with no startSegment; it must be treated
        // as a baseline, not as 200 s of listening.
        recorder.sample(other, 200_000L)

        val pending = recorder.takePending()
        assertEquals(10_000L, pending[track])
        assertEquals(null, pending[other])
    }

    // --- Draining ---

    @Test
    @DisplayName("taking pending time clears it, so a flush cannot double count")
    fun takePendingDrains() {
        val recorder = PlayStatsRecorder()

        recorder.startSegment(track, 0L)
        recorder.sample(track, 3_000L)

        assertEquals(3_000L, recorder.takePending().getValue(track))
        assertTrue(recorder.takePending().isEmpty())
    }

    @Test
    @DisplayName("pending total reports what is waiting to be written")
    fun pendingTotal() {
        val recorder = PlayStatsRecorder()

        assertEquals(0L, recorder.pendingTotalMs)

        recorder.startSegment(track, 0L)
        recorder.sample(track, 2_000L)
        recorder.startSegment(other, 0L)
        recorder.sample(other, 1_000L)

        assertEquals(3_000L, recorder.pendingTotalMs)
    }

    @Test
    @DisplayName("an empty path is ignored")
    fun emptyPathIgnored() {
        val recorder = PlayStatsRecorder()

        recorder.sample("", 1_000L)
        recorder.sample("", 2_000L)

        assertTrue(recorder.takePending().isEmpty())
    }
}
