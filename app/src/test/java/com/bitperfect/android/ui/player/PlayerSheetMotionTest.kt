package com.bitperfect.android.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Where the draggable player surface settles, and what it looks like on the way.
 *
 * The interaction is "the player follows your finger and lands where you meant",
 * and every part of that judgement lives in [PlayerSheetMotion]. Without these
 * tests it could only be checked by feel on a device, which is the one thing not
 * available here.
 *
 * Offsets run from 0 (expanded) to the collapsed offset. Downwards is positive, so
 * upward velocity is negative.
 */
@DisplayName("PlayerSheetMotion Tests")
class PlayerSheetMotionTest {

    private val travel = 1_800f
    private val velocityThreshold = 900f

    private fun target(progress: Float, velocity: Float) =
        PlayerSheetMotion.targetFor(progress, velocity, velocityThreshold)

    // --- Release behaviour: the cases that define the feel ---

    @Test
    @DisplayName("a slow drag released past halfway completes")
    fun slowDragPastHalfwayExpands() {
        assertEquals(PlayerSheetMotion.Target.EXPANDED, target(progress = 0.65f, velocity = 0f))
    }

    @Test
    @DisplayName("a slow drag released before halfway returns")
    fun slowDragBeforeHalfwayCollapses() {
        assertEquals(PlayerSheetMotion.Target.COLLAPSED, target(progress = 0.15f, velocity = 0f))
        assertEquals(PlayerSheetMotion.Target.COLLAPSED, target(progress = 0.35f, velocity = 0f))
    }

    @Test
    @DisplayName("a fast upward flick expands from barely anywhere")
    fun fastUpwardFlickExpands() {
        // The point of a flick is not having to drag far. Judged on distance alone
        // this would collapse, which is what makes a threshold-only sheet feel
        // stubborn.
        assertEquals(
            PlayerSheetMotion.Target.EXPANDED,
            target(progress = 0.05f, velocity = -3_000f)
        )
    }

    @Test
    @DisplayName("a fast downward flick collapses from nearly open")
    fun fastDownwardFlickCollapses() {
        assertEquals(
            PlayerSheetMotion.Target.COLLAPSED,
            target(progress = 0.95f, velocity = 3_000f)
        )
    }

    @Test
    @DisplayName("velocity outranks distance, both ways")
    fun velocityBeatsDistance() {
        // Flicking down from almost-open must close, and flicking up from almost-shut
        // must open, however far the finger travelled.
        assertEquals(PlayerSheetMotion.Target.COLLAPSED, target(0.9f, velocity = 2_000f))
        assertEquals(PlayerSheetMotion.Target.EXPANDED, target(0.1f, velocity = -2_000f))
    }

    @Test
    @DisplayName("a drag too slow for velocity to count falls back to distance")
    fun slowVelocityIgnored() {
        val creep = velocityThreshold * 0.5f

        // Creeping upwards from below halfway still returns: the user did not commit.
        assertEquals(PlayerSheetMotion.Target.COLLAPSED, target(0.3f, velocity = -creep))
        // And creeping downwards from above halfway still completes.
        assertEquals(PlayerSheetMotion.Target.EXPANDED, target(0.7f, velocity = creep))
    }

    @Test
    @DisplayName("exactly halfway with no speed opens rather than dithering")
    fun exactlyHalfway() {
        assertEquals(PlayerSheetMotion.Target.EXPANDED, target(0.5f, velocity = 0f))
    }

    @Test
    @DisplayName("the velocity threshold is a real flick, not an ordinary drag")
    fun velocityThresholdIsSane() {
        assertTrue(
            PlayerSheetMotion.VELOCITY_THRESHOLD_DP_PER_SECOND in 150f..800f,
            "was ${PlayerSheetMotion.VELOCITY_THRESHOLD_DP_PER_SECOND} dp/s"
        )
    }

    // --- Progress, and why the gesture is reversible ---

    @Test
    @DisplayName("progress runs from collapsed to expanded")
    fun progressEnds() {
        assertEquals(0f, PlayerSheetMotion.progressFor(travel, travel))
        assertEquals(1f, PlayerSheetMotion.progressFor(0f, travel))
        assertEquals(0.5f, PlayerSheetMotion.progressFor(travel / 2f, travel))
    }

    @Test
    @DisplayName("progress depends only on position, which is what makes reversal work")
    fun progressIsMemoryless() {
        // No hysteresis and no notion of which way the finger was going: the same
        // offset always draws the same thing. That is why changing direction
        // mid-drag simply follows, with nothing to unwind or restart.
        val approachingFromBelow = PlayerSheetMotion.progressFor(700f, travel)
        val approachingFromAbove = PlayerSheetMotion.progressFor(700f, travel)

        assertEquals(approachingFromBelow, approachingFromAbove)
    }

    @Test
    @DisplayName("progress and offset are inverses of each other")
    fun progressOffsetRoundTrip() {
        for (progress in listOf(0f, 0.13f, 0.5f, 0.87f, 1f)) {
            val offset = PlayerSheetMotion.offsetFor(progress, travel)
            assertEquals(
                progress,
                PlayerSheetMotion.progressFor(offset, travel),
                0.0001f,
                "round trip failed at $progress"
            )
        }
    }

    @Test
    @DisplayName("progress is clamped outside the travel")
    fun progressClamped() {
        assertEquals(1f, PlayerSheetMotion.progressFor(-200f, travel))
        assertEquals(0f, PlayerSheetMotion.progressFor(travel + 200f, travel))
    }

    @Test
    @DisplayName("no travel yet reports fully expanded rather than dividing by zero")
    fun noTravelIsSafe() {
        // True on the first frame, before the surface has been measured. A NaN here
        // becomes a NaN translation, and the whole player disappears.
        assertEquals(1f, PlayerSheetMotion.progressFor(0f, 0f))
        assertEquals(1f, PlayerSheetMotion.progressFor(500f, 0f))
        assertEquals(1f, PlayerSheetMotion.progressFor(0f, -100f))
        assertEquals(0f, PlayerSheetMotion.offsetFor(0.5f, 0f))
    }

    // --- The cross-fade between the two faces ---

    @Test
    @DisplayName("collapsed shows only the bar, expanded only the player")
    fun facesAtTheEnds() {
        assertEquals(1f, PlayerSheetMotion.miniAlpha(0f))
        assertEquals(0f, PlayerSheetMotion.fullAlpha(0f))

        assertEquals(0f, PlayerSheetMotion.miniAlpha(1f))
        assertEquals(1f, PlayerSheetMotion.fullAlpha(1f))
    }

    @Test
    @DisplayName("the two faces are never both close to opaque")
    fun noMuddleInTheMiddle() {
        // Both legible at once looks like a rendering fault rather than a transition.
        var progress = 0f
        while (progress <= 1f) {
            val total = PlayerSheetMotion.miniAlpha(progress) +
                PlayerSheetMotion.fullAlpha(progress)
            assertTrue(total <= 1.35f, "at progress=$progress the faces summed to $total")
            progress += 0.02f
        }
    }

    @Test
    @DisplayName("the bar has gone before the player is halfway in")
    fun barLeavesEarly() {
        assertEquals(0f, PlayerSheetMotion.miniAlpha(PlayerSheetMotion.MINI_FADE_END))
        assertTrue(
            PlayerSheetMotion.fullAlpha(PlayerSheetMotion.MINI_FADE_END) < 0.5f,
            "the player was already prominent while the bar was still fading"
        )
    }

    @Test
    @DisplayName("both faces change monotonically, so neither flickers")
    fun alphasAreMonotonic() {
        var previousMini = PlayerSheetMotion.miniAlpha(0f)
        var previousFull = PlayerSheetMotion.fullAlpha(0f)
        var progress = 0.02f

        while (progress <= 1f) {
            val mini = PlayerSheetMotion.miniAlpha(progress)
            val full = PlayerSheetMotion.fullAlpha(progress)
            assertTrue(mini <= previousMini + 0.0001f, "bar brightened at $progress")
            assertTrue(full >= previousFull - 0.0001f, "player dimmed at $progress")
            previousMini = mini
            previousFull = full
            progress += 0.02f
        }
    }

    @Test
    @DisplayName("alphas stay legal outside 0..1 progress")
    fun alphasClamped() {
        for (progress in listOf(-1f, -0.2f, 1.4f, 3f)) {
            assertTrue(PlayerSheetMotion.miniAlpha(progress) in 0f..1f, "mini at $progress")
            assertTrue(PlayerSheetMotion.fullAlpha(progress) in 0f..1f, "full at $progress")
        }
    }
}
