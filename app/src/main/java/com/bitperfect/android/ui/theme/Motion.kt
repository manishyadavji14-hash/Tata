package com.bitperfect.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Shared motion tokens.
 *
 * Durations and easings live here so screens stay consistent and the whole
 * app's feel can be tuned in one place rather than through scattered literals.
 *
 * Nothing here touches the audio path: animation runs on the UI frame clock,
 * while decoding and AudioTrack writes happen on their own worker thread.
 */
object BitPerfectMotion {

    /** Small state changes: colour, alpha, icon swaps. */
    const val DURATION_QUICK = 150

    /** Standard element entrance and exit. */
    const val DURATION_STANDARD = 250

    /** Screen-level transitions. */
    const val DURATION_SCREEN = 320

    /**
     * Decelerating easing for entering content, so items arrive gently rather
     * than snapping into place.
     */
    val EmphasisedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** Accelerating easing for content leaving the screen. */
    val EmphasisedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** Standard easing for changes that both start and end on screen. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    fun <T> quick(): FiniteAnimationSpec<T> =
        tween(durationMillis = DURATION_QUICK, easing = Standard)

    fun <T> standard(): FiniteAnimationSpec<T> =
        tween(durationMillis = DURATION_STANDARD, easing = Standard)

    fun <T> entering(): FiniteAnimationSpec<T> =
        tween(durationMillis = DURATION_STANDARD, easing = EmphasisedDecelerate)

    fun <T> exiting(): FiniteAnimationSpec<T> =
        tween(durationMillis = DURATION_QUICK, easing = EmphasisedAccelerate)

    /**
     * Springy spec for controls that respond to touch, such as the play button.
     */
    fun <T> responsive(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}
