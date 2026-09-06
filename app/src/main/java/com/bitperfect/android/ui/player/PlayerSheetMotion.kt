package com.bitperfect.android.ui.player

/**
 * The arithmetic of the draggable player surface.
 *
 * Separated from the composable for the usual reason — Compose cannot be tested in
 * this project — but here it matters more than usual, because "does the sheet settle
 * where the user meant it to" is entirely decided by these few functions and is
 * otherwise only checkable by hand on a device.
 *
 * Offsets are in pixels from the expanded position: `0` is fully expanded and
 * [progressFor]'s `collapsedOffsetPx` is fully collapsed. Downwards is positive,
 * matching Android's y axis, which is why upward velocity is negative throughout.
 */
object PlayerSheetMotion {

    /**
     * How far through the travel a released drag has to be to complete rather than
     * return, when it was too slow for velocity to decide.
     *
     * Half: the sheet goes wherever it is closest to, which is the least surprising
     * rule and the one that makes a deliberate half-drag feel neutral rather than
     * biased.
     */
    const val POSITIONAL_THRESHOLD = 0.5f

    /**
     * Speed above which direction alone decides, in dp per second.
     *
     * A flick has to work without dragging far — that is the whole point of a flick
     * — so past this speed the distance covered is ignored. Low enough that an
     * ordinary quick swipe qualifies, high enough that the slow drags which should
     * be judged on distance do not.
     */
    const val VELOCITY_THRESHOLD_DP_PER_SECOND = 350f

    /** Progress by which the collapsed bar has completely faded out. */
    const val MINI_FADE_END = 0.22f

    /** Progress at which the expanded player begins to appear. */
    const val FULL_FADE_START = 0.12f

    /**
     * Progress by which the expanded player is fully opaque.
     *
     * Well before the end, and that matters: fading it across the whole travel left it
     * translucent for most of the gesture, so the library showed through the player
     * for the entire drag and the whole thing looked washed out. It should become a
     * solid surface early and then simply slide.
     */
    const val FULL_FADE_END = 0.45f

    /** Where a released drag should settle. */
    enum class Target { COLLAPSED, EXPANDED }

    /**
     * How far open the sheet is: `0` collapsed, `1` expanded.
     *
     * A zero or negative travel distance means there is nowhere to collapse to —
     * true on the first frame before layout, and on a screen shorter than the
     * collapsed bar. Reported as fully expanded rather than dividing by it, because
     * the resulting NaN propagates into a translation and the sheet disappears.
     */
    fun progressFor(offsetPx: Float, collapsedOffsetPx: Float): Float {
        if (collapsedOffsetPx <= 0f) return 1f
        return (1f - offsetPx / collapsedOffsetPx).coerceIn(0f, 1f)
    }

    /** Inverse of [progressFor]: the offset that draws a given progress. */
    fun offsetFor(progress: Float, collapsedOffsetPx: Float): Float {
        if (collapsedOffsetPx <= 0f) return 0f
        return collapsedOffsetPx * (1f - progress.coerceIn(0f, 1f))
    }

    /**
     * Where the sheet should go when the finger lifts.
     *
     * Velocity is considered before distance, so a fast flick completes even from a
     * few pixels in — which is what makes the gesture feel responsive rather than
     * demanding. Only when the release was slow does the distance covered decide.
     *
     * @param velocityPxPerSecond negative upwards, positive downwards.
     */
    fun targetFor(
        progress: Float,
        velocityPxPerSecond: Float,
        velocityThresholdPxPerSecond: Float
    ): Target {
        if (velocityPxPerSecond <= -velocityThresholdPxPerSecond) return Target.EXPANDED
        if (velocityPxPerSecond >= velocityThresholdPxPerSecond) return Target.COLLAPSED

        return if (progress >= POSITIONAL_THRESHOLD) Target.EXPANDED else Target.COLLAPSED
    }

    /**
     * Opacity of the collapsed bar.
     *
     * Gone early, well before the sheet is open, so the two representations are not
     * both legible at once — overlapping them at half strength looks like a mistake
     * rather than a transition.
     */
    fun miniAlpha(progress: Float): Float =
        (1f - progress / MINI_FADE_END).coerceIn(0f, 1f)

    /**
     * Opacity of the expanded player.
     *
     * Starts just after the drag begins rather than at zero, so a small accidental
     * movement does not flash the full layout behind the bar.
     */
    fun fullAlpha(progress: Float): Float =
        ((progress - FULL_FADE_START) / (FULL_FADE_END - FULL_FADE_START)).coerceIn(0f, 1f)

    /**
     * Whether the artwork should be drawn by the surface rather than by either face.
     *
     * Only while actually in motion. At either end each face draws its own cover, so
     * if the measured positions the morph interpolates between were ever wrong, the
     * damage is confined to the gesture and the player is still correct at rest —
     * which is the only reason this is safe to do at all.
     */
    fun isMorphingArtwork(progress: Float): Boolean =
        progress > MORPH_EPSILON && progress < 1f - MORPH_EPSILON

    /**
     * Slack at each end of the travel.
     *
     * Guards against a spring settling on 0.9999 and leaving the surface drawing an
     * artwork forever while both faces hide theirs.
     */
    private const val MORPH_EPSILON = 0.002f
}
