package com.bitperfect.android.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The arithmetic behind the player's motion.
 *
 * Compose itself cannot be tested here — there is no `androidT{@code est}` source
 * set — but the decisions that make motion look right or wrong are not Compose,
 * they are arithmetic, and getting them wrong is visible on every frame. So they
 * live in [PlayerMotion] and are pinned here.
 */
@DisplayName("PlayerMotion Tests")
class PlayerMotionTest {

    // --- Gliding the seek bar between position updates ---

    @Test
    @DisplayName("playing forward one update interval glides")
    fun normalAdvanceGlides() {
        // The position arrives four times a second; each step must glide or the bar
        // visibly ticks.
        assertTrue(PlayerMotion.isNaturalProgress(1_000L, 1_250L))
        assertTrue(PlayerMotion.isNaturalProgress(0L, 250L))
    }

    @Test
    @DisplayName("a late or coalesced update still glides")
    fun toleratesJitter() {
        // The update loop is a coroutine on the main dispatcher; it is not exact,
        // and a dropped tick must not make the bar jump.
        assertTrue(PlayerMotion.isNaturalProgress(1_000L, 1_500L))
        assertTrue(PlayerMotion.isNaturalProgress(1_000L, 1_900L))
    }

    @Test
    @DisplayName("a seek jumps rather than sliding across")
    fun seekSnaps() {
        // Sliding the bar over to meet a seek reads as the player being slow to
        // respond, which is the opposite of what the animation is for.
        assertTrue(!PlayerMotion.isNaturalProgress(10_000L, 90_000L))
    }

    @Test
    @DisplayName("a new track jumps back to the start")
    fun trackChangeSnaps() {
        // Backwards is always a jump: playback does not run in reverse.
        assertTrue(!PlayerMotion.isNaturalProgress(200_000L, 0L))
        assertTrue(!PlayerMotion.isNaturalProgress(5_000L, 4_000L))
    }

    @Test
    @DisplayName("a position that has not moved does not animate")
    fun pausedDoesNotAnimate() {
        assertTrue(!PlayerMotion.isNaturalProgress(7_000L, 7_000L))
    }

    @Test
    @DisplayName("the glide outlasts the update interval, so motion never stalls")
    fun glideOverlapsTheUpdateInterval() {
        // If they were equal, any jitter in the update loop would show as a stall.
        assertTrue(
            PlayerMotion.PROGRESS_GLIDE_MS > PlayerMotion.POSITION_UPDATE_INTERVAL_MS,
            "glide (${PlayerMotion.PROGRESS_GLIDE_MS}ms) must outlast the update " +
                "interval (${PlayerMotion.POSITION_UPDATE_INTERVAL_MS}ms)"
        )
    }

    @Test
    @DisplayName("the continuity tolerance spans several updates but no real seek")
    fun toleranceIsSanelyChosen() {
        assertTrue(
            PlayerMotion.CONTINUITY_TOLERANCE_MS > PlayerMotion.POSITION_UPDATE_INTERVAL_MS * 2,
            "too tight: a single dropped update would make the bar jump"
        )
        assertTrue(
            PlayerMotion.CONTINUITY_TOLERANCE_MS < 5_000L,
            "too loose: a small seek would be animated as though it were playback"
        )
    }

    // --- Progress, including the cases that produced NaN ---

    @Test
    @DisplayName("progress is the played fraction")
    fun progressFraction() {
        assertEquals(0.5f, PlayerMotion.progressOf(60_000L, 120_000L))
        assertEquals(0f, PlayerMotion.progressOf(0L, 120_000L))
        assertEquals(1f, PlayerMotion.progressOf(120_000L, 120_000L))
    }

    @Test
    @DisplayName("an unknown duration is zero progress, not NaN")
    fun unknownDuration() {
        // Duration is 0 while a track loads. Dividing by it yields NaN, which
        // Compose draws as an empty bar and which poisons any animation towards it.
        assertEquals(0f, PlayerMotion.progressOf(5_000L, 0L))
        assertEquals(0f, PlayerMotion.progressOf(5_000L, -1L))
    }

    @Test
    @DisplayName("progress past the end is clamped")
    fun progressClamped() {
        // The reported position can briefly exceed a rounded-down duration.
        assertEquals(1f, PlayerMotion.progressOf(120_500L, 120_000L))
        assertEquals(0f, PlayerMotion.progressOf(-500L, 120_000L))
    }

    // --- Which gesture a drag on the artwork turned out to be ---

    private val swipe = 80f
    private val collapse = 120f

    private fun outcome(dx: Float, dy: Float) =
        PlayerMotion.dragOutcome(dx, dy, swipe, collapse)

    @Test
    @DisplayName("a decisive sideways drag changes track, left going forward")
    fun horizontalSwipes() {
        assertEquals(PlayerMotion.DragOutcome.NEXT, outcome(dx = -200f, dy = 0f))
        assertEquals(PlayerMotion.DragOutcome.PREVIOUS, outcome(dx = 200f, dy = 0f))
    }

    @Test
    @DisplayName("a decisive downward drag minimises the player")
    fun verticalCollapse() {
        assertEquals(PlayerMotion.DragOutcome.COLLAPSE, outcome(dx = 0f, dy = 300f))
    }

    @Test
    @DisplayName("the dominant axis wins before any threshold is judged")
    fun dominantAxisDecidesFirst() {
        // A mostly-downward drag with plenty of sideways travel must minimise, not
        // change track — judging thresholds first would do the opposite, because
        // both are exceeded.
        assertEquals(PlayerMotion.DragOutcome.COLLAPSE, outcome(dx = 100f, dy = 300f))
        // And the reverse: mostly sideways with enough vertical travel to collapse.
        assertEquals(PlayerMotion.DragOutcome.NEXT, outcome(dx = -300f, dy = 130f))
    }

    @Test
    @DisplayName("a drag too short to mean anything does nothing")
    fun shortDragIsIgnored() {
        assertEquals(PlayerMotion.DragOutcome.NONE, outcome(dx = -20f, dy = 5f))
        assertEquals(PlayerMotion.DragOutcome.NONE, outcome(dx = 5f, dy = 20f))
    }

    @Test
    @DisplayName("dragging the artwork upwards does nothing")
    fun upwardDragIsIgnored() {
        // There is nowhere above the player to go, and it must not be read as a
        // collapse just because it is vertical.
        assertEquals(PlayerMotion.DragOutcome.NONE, outcome(dx = 0f, dy = -300f))
    }

    @Test
    @DisplayName("exactly at a threshold does not trigger")
    fun thresholdsAreExclusive() {
        assertEquals(PlayerMotion.DragOutcome.NONE, outcome(dx = -swipe, dy = 0f))
        assertEquals(PlayerMotion.DragOutcome.NONE, outcome(dx = 0f, dy = collapse))
    }

    // --- Which way a new cover travels ---

    @Test
    @DisplayName("a cover enters from the side the gesture came from")
    fun slideDirections() {
        val width = 1000
        val forwardEnter = PlayerMotion.enterOffset(PlayerMotion.SlideDirection.FORWARD, width)
        val backwardEnter = PlayerMotion.enterOffset(PlayerMotion.SlideDirection.BACKWARD, width)

        // Going forward, the new cover comes from the right and the old leaves left.
        assertTrue(forwardEnter > 0, "a forward cover must enter from the right")
        assertTrue(backwardEnter < 0, "a backward cover must enter from the left")
    }

    @Test
    @DisplayName("the outgoing cover leaves opposite the incoming one")
    fun exitOpposesEnter() {
        val width = 1000
        for (direction in PlayerMotion.SlideDirection.entries) {
            val enter = PlayerMotion.enterOffset(direction, width)
            val exit = PlayerMotion.exitOffset(direction, width)
            assertTrue(
                enter > 0 != exit > 0,
                "$direction: both covers travel the same way, so they would collide"
            )
        }
    }

    @Test
    @DisplayName("covers slide partway, not a whole width")
    fun slideIsPartial() {
        // A full-width slide reads as two unrelated images rather than one changing.
        assertTrue(PlayerMotion.SLIDE_FRACTION in 0.1f..0.6f)
    }

    // --- Following the finger ---

    @Test
    @DisplayName("at rest the artwork is untransformed")
    fun restingTransform() {
        val t = PlayerMotion.dragTransform(dx = 0f, dy = 0f, width = 1000, height = 1000)

        assertEquals(0f, t.translationX)
        assertEquals(1f, t.scale)
        assertEquals(1f, t.alpha)
        assertEquals(0f, t.rotationZ)
    }

    @Test
    @DisplayName("a sideways drag moves and tilts the artwork, but not the full distance")
    fun horizontalFollow() {
        val t = PlayerMotion.dragTransform(dx = 300f, dy = 0f, width = 1000, height = 1000)

        assertTrue(t.translationX > 0f && t.translationX < 300f, "was ${t.translationX}")
        assertTrue(t.rotationZ > 0f, "the artwork should tilt into the drag")
        assertTrue(t.alpha < 1f, "the artwork should fade as it leaves")
    }

    @Test
    @DisplayName("a downward drag shrinks the artwork towards the mini player")
    fun verticalShrink() {
        val t = PlayerMotion.dragTransform(dx = 0f, dy = 400f, width = 1000, height = 1000)

        assertTrue(t.scale < 1f, "was ${t.scale}")
        assertTrue(t.scale > 0.5f, "shrinking this far would look broken: ${t.scale}")
    }

    @Test
    @DisplayName("dragging upwards does not shrink the artwork")
    fun upwardDoesNotShrink() {
        // Upward is not a gesture on the player, so it must not deform anything.
        val t = PlayerMotion.dragTransform(dx = 0f, dy = -400f, width = 1000, height = 1000)

        assertEquals(1f, t.scale)
    }

    @Test
    @DisplayName("the artwork never fades out completely, however far it is dragged")
    fun alphaHasAFloor() {
        val extreme = PlayerMotion.dragTransform(
            dx = 5_000f,
            dy = 5_000f,
            width = 1000,
            height = 1000
        )

        assertTrue(extreme.alpha > 0.3f, "artwork vanished mid-gesture: ${extreme.alpha}")
    }

    @Test
    @DisplayName("a zero-sized layout does not divide by zero")
    fun zeroSizedLayout() {
        // True on the first frame, before measurement.
        val t = PlayerMotion.dragTransform(dx = 50f, dy = 50f, width = 0, height = 0)

        assertEquals(1f, t.scale)
        assertEquals(1f, t.alpha)
        assertEquals(0f, t.translationX)
    }
}
