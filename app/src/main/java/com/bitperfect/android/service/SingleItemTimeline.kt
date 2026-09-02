package com.bitperfect.android.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

/**
 * A timeline of exactly one window, describing the track being played.
 *
 * This exists because of where media3 actually gets the facts it publishes to the
 * system. The lock screen, the notification panel and vendor media widgets are fed
 * from the session's `PlaybackStateCompat` and `MediaMetadataCompat`, and media3
 * builds both from the **current timeline window** — not from `Player.getDuration()`
 * and not from `Player.getMediaMetadata()`. A player reporting `Timeline.EMPTY` has
 * no current window at all, so there is nothing to publish: the panel falls back to
 * "Unknown song", shows no cover, and prints `--:--` at both ends of a scrubber that
 * cannot move.
 *
 * Note in particular that media3 1.2.1's `MediaMetadata` has **no duration field**.
 * The window is the only route by which a track length can reach the system.
 *
 * Written by hand rather than reusing `SinglePeriodTimeline`, which lives in
 * media3-exoplayer; this app depends only on media3-common and media3-session.
 */
@UnstableApi
internal class SingleItemTimeline(
    private val mediaItem: MediaItem,
    durationMs: Long
) : Timeline() {

    /**
     * Window duration in microseconds, or [C.TIME_UNSET] when unknown.
     *
     * `TIME_UNSET` is the honest answer before a file has been opened, and the
     * system renders it as `--:--` rather than as a zero-length track.
     */
    private val durationUs: Long =
        if (durationMs > 0L) durationMs * 1000L else C.TIME_UNSET

    override fun getWindowCount(): Int = 1

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long
    ): Window = window.set(
        /* uid= */ WINDOW_UID,
        /* mediaItem= */ mediaItem,
        /* manifest= */ null,
        /* presentationStartTimeMs= */ C.TIME_UNSET,
        /* windowStartTimeMs= */ C.TIME_UNSET,
        /* elapsedRealtimeEpochOffsetMs= */ C.TIME_UNSET,
        // Seekable, so the system draws a draggable scrubber. A local file always
        // is; this app plays nothing else.
        /* isSeekable= */ true,
        /* isDynamic= */ false,
        /* liveConfiguration= */ null,
        /* defaultPositionUs= */ 0L,
        /* durationUs= */ durationUs,
        /* firstPeriodIndex= */ 0,
        /* lastPeriodIndex= */ 0,
        /* positionInFirstPeriodUs= */ 0L
    )

    override fun getPeriodCount(): Int = 1

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
        period.set(
            /* id= */ if (setIds) WINDOW_UID else null,
            /* uid= */ if (setIds) WINDOW_UID else null,
            /* windowIndex= */ 0,
            /* durationUs= */ durationUs,
            /* positionInWindowUs= */ 0L
        )

    override fun getIndexOfPeriod(uid: Any): Int =
        if (uid == WINDOW_UID) 0 else C.INDEX_UNSET

    override fun getUidOfPeriod(periodIndex: Int): Any = WINDOW_UID

    companion object {
        /**
         * Identity of the single window and period.
         *
         * Deliberately constant rather than per-track. media3 uses it to match a
         * reported position against a window, and it must equal the uid handed
         * back by [getUidOfPeriod] or position reports are discarded. Which track
         * is playing is carried by `MediaItem.mediaId`, not by this.
         */
        val WINDOW_UID: Any = "BitPerfectWindow"
    }
}
