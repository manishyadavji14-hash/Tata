package com.bitperfect.android.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The player as one surface that slides between collapsed and expanded, rather than
 * two screens that replace each other.
 *
 * Previously the collapsed bar and the full player were sibling navigation
 * destinations. A `NavHost` composes one destination at a time, so the two were
 * never on screen together and no continuous gesture between them was possible —
 * the bar could only wait for a release and then run a canned slide. Hosting the
 * player here, layered over the navigation host, is what makes the drag possible at
 * all; it is the smallest change that does, and it is why this file exists.
 *
 * Both representations are the existing composables, unchanged. They are stacked in
 * one surface that translates as a whole: the collapsed bar sits at the top of the
 * surface so it is the part left visible when the surface is pushed down, and the
 * expanded player fills it. Position does the work; the cross-fade between the two
 * layouts only hides the fact that they are different arrangements rather than one
 * morphing layout.
 *
 * Playback state is untouched. This owns exactly one thing — how far open the
 * surface is — and reads nothing about what is playing.
 */
@Stable
class PlayerSheetState internal constructor(initiallyExpanded: Boolean) {

    /** Pixels from the expanded position. Downwards is positive. */
    internal val offset = Animatable(0f)

    /** Offset at which only the collapsed bar shows. Known after layout. */
    internal var collapsedOffsetPx by mutableFloatStateOf(0f)

    private var hasPlaced = false
    private val startExpanded = initiallyExpanded

    /** 0 collapsed, 1 expanded. Safe before layout. */
    val progress: Float
        get() = PlayerSheetMotion.progressFor(offset.value, collapsedOffsetPx)

    /**
     * Whether the surface is more open than shut.
     *
     * Used to decide whether the back gesture should collapse it, so it deliberately
     * reads the live position rather than a target: a sheet the user has dragged
     * most of the way open should answer back, even mid-gesture.
     */
    val isExpanded: Boolean
        get() = progress >= PlayerSheetMotion.POSITIONAL_THRESHOLD

    suspend fun expand() {
        offset.animateTo(0f, SETTLE_SPEC)
    }

    suspend fun collapse() {
        offset.animateTo(collapsedOffsetPx, SETTLE_SPEC)
    }

    /**
     * Move by a finger delta.
     *
     * `snapTo` rather than an animation, so the surface is exactly under the finger,
     * and because it cancels any settle still running — which is what makes the
     * gesture interruptible and reversible at any point without special handling.
     */
    internal suspend fun dragBy(deltaPx: Float) {
        val bounded = (offset.value + deltaPx).coerceIn(0f, collapsedOffsetPx)
        offset.snapTo(bounded)
    }

    /** Settle after a release, honouring the fling. */
    internal suspend fun settle(velocityPxPerSecond: Float, velocityThresholdPx: Float) {
        val target = PlayerSheetMotion.targetFor(
            progress = progress,
            velocityPxPerSecond = velocityPxPerSecond,
            velocityThresholdPxPerSecond = velocityThresholdPx
        )
        when (target) {
            PlayerSheetMotion.Target.EXPANDED -> expand()
            PlayerSheetMotion.Target.COLLAPSED -> collapse()
        }
    }

    /**
     * Re-place the surface when the travel distance becomes known or changes.
     *
     * Called on layout and on rotation. Without it the surface keeps a pixel offset
     * measured against a different screen height, which on a shorter screen leaves
     * it hanging part-open.
     */
    internal suspend fun onTravelChanged(newCollapsedOffsetPx: Float) {
        val wasExpanded = if (hasPlaced) isExpanded else startExpanded
        collapsedOffsetPx = newCollapsedOffsetPx
        hasPlaced = true
        offset.snapTo(if (wasExpanded) 0f else newCollapsedOffsetPx)
    }

    private companion object {
        /**
         * Settling spec. No bounce: this is a large surface carrying text, and
         * overshoot on something this size reads as sloppy rather than lively.
         */
        val SETTLE_SPEC = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    }
}

@Composable
fun rememberPlayerSheetState(initiallyExpanded: Boolean = true): PlayerSheetState =
    remember { PlayerSheetState(initiallyExpanded) }

/**
 * Host for the collapsed and expanded player.
 *
 * @param peekHeight how much of the surface shows when collapsed. Zero hides it
 *   entirely, which is how "nothing is playing" and the full-screen queue are
 *   handled without unmounting the player and losing its state.
 * @param miniPlayer the collapsed bar. Given the drag callbacks so its own gesture
 *   detector can drive the surface — see [PlayerSheetDrag].
 * @param fullPlayer the expanded player, given the same callbacks.
 */
@Composable
fun PlayerSheet(
    state: PlayerSheetState,
    peekHeight: Dp,
    modifier: Modifier = Modifier,
    miniPlayer: @Composable (PlayerSheetDrag) -> Unit,
    fullPlayer: @Composable (PlayerSheetDrag) -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val peekHeightPx = with(density) { peekHeight.toPx() }
        val travelPx = (containerHeightPx - peekHeightPx).coerceAtLeast(0f)
        val velocityThresholdPx = with(density) {
            PlayerSheetMotion.VELOCITY_THRESHOLD_DP_PER_SECOND.dp.toPx()
        }

        LaunchedEffect(travelPx) { state.onTravelChanged(travelPx) }

        val drag = remember(state, velocityThresholdPx) {
            PlayerSheetDrag(
                onDrag = { delta -> scope.launch { state.dragBy(delta) } },
                onRelease = { velocity ->
                    scope.launch { state.settle(velocity, velocityThresholdPx) }
                },
                onExpand = { scope.launch { state.expand() } },
                onCollapse = { scope.launch { state.collapse() } }
            )
        }

        // Nothing to show yet: keep the player composed so it holds its state and
        // its effects keep running, but leave it off screen.
        val isHidden = peekHeight <= 0.dp && !state.isExpanded

        Box(
            modifier = Modifier
                .fillMaxSize()
                // graphicsLayer, not offset: translation here is a draw-time
                // property, so following the finger does not relayout the player on
                // every frame.
                .graphicsLayer {
                    translationY = if (isHidden) containerHeightPx else state.offset.value
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = PlayerSheetMotion.fullAlpha(state.progress) }
            ) {
                fullPlayer(drag)
            }

            // Above the expanded player and at the top of the surface, so it is what
            // remains visible once the surface is pushed down.
            if (peekHeight > 0.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(peekHeight)
                        .graphicsLayer { alpha = PlayerSheetMotion.miniAlpha(state.progress) }
                ) {
                    miniPlayer(drag)
                }
            }
        }
    }
}

/**
 * Hooks that let a child's existing gesture detector drive the surface.
 *
 * Given to the children rather than the surface taking the gesture for itself,
 * because both children already arbitrate carefully between several gestures on the
 * same pixels — the collapsed bar between tap, sideways track change and pull-up,
 * the expanded player between sideways track change and pull-down — and a drag
 * handler wrapped around them would either steal from those or be starved by them.
 * Feeding the surface from inside the detector that already won the arbitration
 * keeps every existing gesture working exactly as before.
 */
@Stable
class PlayerSheetDrag(
    /** A finger delta in pixels, positive downwards. */
    val onDrag: (Float) -> Unit,
    /** Released, with the fling velocity in pixels per second. */
    val onRelease: (Float) -> Unit,
    val onExpand: () -> Unit,
    val onCollapse: () -> Unit
)
