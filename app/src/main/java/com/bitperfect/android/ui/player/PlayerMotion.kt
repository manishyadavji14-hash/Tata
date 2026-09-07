package com.bitperfect.android.ui.player

import kotlin.math.abs

/**
 * The decisions behind the player's motion, separated from the drawing of it.
 *
 * Compose code cannot be unit tested here (there is no `androidTest` source set),
 * but the choices that make motion look right or wrong are arithmetic, and those
 * can be. Everything in this file is pure.
 */
object PlayerMotion {

    // --- Seek bar ---

    /**
     * How often the player publishes a position, from
     * `PlayerViewModel`'s update loop.
     */
    const val POSITION_UPDATE_INTERVAL_MS = 250

    /**
     * How long the bar takes to travel to a newly reported position.
     *
     * Deliberately **longer** than [POSITION_UPDATE_INTERVAL_MS]. If it were equal
     * the bar would arrive exactly as the next position lands and any jitter in the
     * update loop would show as a stall; overlapping means it is always still
     * moving when the next target arrives, so the motion never stops. The cost is
     * that the bar trails real playback by a few tens of milliseconds, which is far
     * below what anyone can see on a progress bar.
     */
    const val PROGRESS_GLIDE_MS = 320

    /**
     * The largest forward jump still treated as playback advancing.
     *
     * Comfortably above one update interval, so a late or coalesced update still
     * glides, and well below any seek worth animating.
     */
    const val CONTINUITY_TOLERANCE_MS = 900L

    /**
     * Whether the position moved because the track is playing, rather than jumping.
     *
     * Gliding is only right for playback. A seek, a track change, or a restored
     * session moves the position by an arbitrary amount, and sliding the bar across
     * to meet it reads as the player being slow to respond — so those snap.
     * Backwards movement is always a jump: playback does not run in reverse.
     */
    fun isNaturalProgress(previousMs: Long, nextMs: Long): Boolean {
        val delta = nextMs - previousMs
        return delta > 0L && delta <= CONTINUITY_TOLERANCE_MS
    }

    /**
     * Fraction of the track played, clamped and safe for an unknown duration.
     *
     * Duration is 0 while a track loads and for streams that never report one, and
     * dividing by it produced a NaN that Compose renders as an empty bar.
     */
    fun progressOf(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    // --- Gestures on the album art ---

    /**
     * What a drag on the album art turned out to mean.
     */
    enum class DragOutcome { NEXT, PREVIOUS, COLLAPSE, NONE }

    /**
     * Resolve a completed drag.
     *
     * The art covers most of the screen and carries two gestures, so the axis has
     * to be arbitrated: the dominant one wins, and only then is its threshold
     * checked. Judging thresholds first would let a mostly-vertical drag with a
     * little sideways travel change track.
     *
     * Extracted from the gesture callback because arbitration like this has already
     * cost a bug — a slightly smudged tap on the mini player fell between the tap
     * and swipe thresholds and was discarded — and because a rule this fiddly
     * should be readable on its own.
     *
     * @param dx total horizontal travel; negative is leftwards, which means forward.
     * @param dy total vertical travel; positive is downwards.
     */
    fun dragOutcome(
        dx: Float,
        dy: Float,
        swipeThresholdPx: Float,
        collapseThresholdPx: Float
    ): DragOutcome {
        val horizontal = abs(dx) > abs(dy)

        if (horizontal) {
            if (abs(dx) <= swipeThresholdPx) return DragOutcome.NONE
            // Left goes forward, matching the mini player and the rest of the app.
            return if (dx < 0f) DragOutcome.NEXT else DragOutcome.PREVIOUS
        }

        // Only downwards dismisses. An upward drag on the player has nowhere to go.
        return if (dy > collapseThresholdPx) DragOutcome.COLLAPSE else DragOutcome.NONE
    }

    /**
     * Which way the artwork should travel when the track changes.
     *
     * Taken from the action rather than from queue positions, because the queue
     * index is not the whole story: shuffle, repeat-one and a wrap from the last
     * track to the first all move "forward" while the index does something else.
     * Reaching the end of a track advances, so forward is the default.
     */
    enum class SlideDirection { FORWARD, BACKWARD }

    /**
     * Horizontal offset an entering cover starts from, as a fraction of its width.
     *
     * Not a full width: starting just off the edge keeps the two covers visually
     * connected, where a full-width slide reads as two unrelated images.
     */
    const val SLIDE_FRACTION = 0.35f

    fun enterOffset(direction: SlideDirection, width: Int): Int = when (direction) {
        SlideDirection.FORWARD -> (width * SLIDE_FRACTION).toInt()
        SlideDirection.BACKWARD -> -(width * SLIDE_FRACTION).toInt()
    }

    fun exitOffset(direction: SlideDirection, width: Int): Int = when (direction) {
        SlideDirection.FORWARD -> -(width * SLIDE_FRACTION).toInt()
        SlideDirection.BACKWARD -> (width * SLIDE_FRACTION).toInt()
    }

    // --- Following the finger ---

    /** How much of a horizontal drag the artwork actually travels. */
    private const val HORIZONTAL_FOLLOW = 0.55f

    /** Maximum tilt, in degrees, at a full-width horizontal drag. */
    private const val MAX_TILT_DEGREES = 5f

    /** How much the artwork shrinks at a full-height downward drag. */
    private const val MAX_PULL_SHRINK = 0.18f

    /** Floor on opacity, so the artwork never disappears mid-gesture. */
    private const val MIN_ALPHA = 0.45f

    /**
     * How the artwork should be drawn part-way through a drag.
     *
     * The art follows the finger at less than 1:1 and tilts slightly, so a sideways
     * drag previews the track change instead of only reporting it once released; a
     * downward drag shrinks it towards the mini player it is about to become.
     *
     * Guarded against a zero-sized layout, which happens on the first frame.
     */
    fun dragTransform(dx: Float, dy: Float, width: Int, height: Int): DragTransform {
        if (width <= 0 || height <= 0) return DragTransform()

        val horizontalFraction = (dx / width).coerceIn(-1f, 1f)
        // Upward travel is not a gesture here, so it must not shrink the art.
        val pullFraction = (dy / height).coerceIn(0f, 1f)

        val scale = 1f - pullFraction * MAX_PULL_SHRINK
        val alpha = (1f - abs(horizontalFraction) * 0.5f - pullFraction * 0.35f)
            .coerceIn(MIN_ALPHA, 1f)

        return DragTransform(
            translationX = dx * HORIZONTAL_FOLLOW,
            scale = scale,
            alpha = alpha,
            rotationZ = horizontalFraction * MAX_TILT_DEGREES
        )
    }

    data class DragTransform(
        val translationX: Float = 0f,
        val scale: Float = 1f,
        val alpha: Float = 1f,
        val rotationZ: Float = 0f
    )
}
