package com.bitperfect.android.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitperfect.android.R
import com.bitperfect.android.ui.player.PlayerViewModel
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import com.bitperfect.android.ui.player.PlayerMotion
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.bitperfect.android.ui.player.PlayerSheetDrag

/**
 * MiniPlayerBar - the now-playing bar on every screen except the full Player.
 *
 * - Album art, title, artist, play/pause.
 * - Background is a gradient built from a vivid colour extracted from the album
 *   art, and it animates as the track changes.
 * - Horizontal swipe: left goes to the previous track, right goes to the next
 *   one and wraps to the first track when already at the end.
 * - Tapping opens the full Player.
 */
@Composable
fun MiniPlayerBar(
    uiState: PlayerViewModel.PlayerUiState,
    onBarClick: () -> Unit,
    onExpand: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The draggable player surface this bar is the collapsed face of, when there is
     * one. Null keeps the original behaviour, where a decisive pull-up simply opens
     * the player — which is still what happens if this bar is ever used outside the
     * surface.
     */
    sheetDrag: PlayerSheetDrag? = null
) {
    if (!uiState.isPlaying && !uiState.isPaused) return

    val view = LocalView.current

    // Glides between the four-a-second position updates, for the same reason the
    // player's seek bar does — see PlayerMotion. The bar is only a few pixels tall,
    // which makes stepping more obvious here rather than less.
    val progressTarget = PlayerMotion.progressOf(uiState.positionMs, uiState.durationMs)
    val glide = remember { Animatable(0f) }
    var lastPositionMs by remember { mutableLongStateOf(uiState.positionMs) }

    LaunchedEffect(uiState.positionMs, uiState.durationMs) {
        val previousMs = lastPositionMs
        lastPositionMs = uiState.positionMs
        if (PlayerMotion.isNaturalProgress(previousMs, uiState.positionMs)) {
            glide.animateTo(
                targetValue = progressTarget,
                animationSpec = tween(
                    durationMillis = PlayerMotion.PROGRESS_GLIDE_MS,
                    easing = LinearEasing
                )
            )
        } else {
            glide.snapTo(progressTarget)
        }
    }

    val progress = glide.value

    // The punchy accent from the current cover, animated across track changes.
    val colors = rememberAlbumColorScheme(
        artworkUri = uiState.artworkUri,
        fallbackAccent = MaterialTheme.colorScheme.primary
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.background(
                Brush.horizontalGradient(
                    colors = listOf(colors.accent, colors.accent.copy(alpha = 0.82f))
                )
            )
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = colors.onAccent,
                trackColor = colors.onAccent.copy(alpha = 0.25f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    // Tap and drag are separate pointerInput blocks rather than
                    // clickable + a drag detector on one node. Mixing clickable
                    // with a drag detector let the drag swallow the tap, which is
                    // why tapping the bar did nothing.
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onBarClick() })
                    }
                    .pointerInput(sheetDrag) {
                        var dx = 0f
                        var dy = 0f
                        // Whether this drag has been handed to the player surface.
                        // Once it has, the surface has moved and must be settled on
                        // release; a drag that never reached that point is still
                        // free to be read as a tap, which is what keeps a slightly
                        // smudged tap on the bar working.
                        var drivingSheet = false
                        val velocity = VelocityTracker()

                        detectDragGestures(
                            onDragStart = {
                                dx = 0f
                                dy = 0f
                                drivingSheet = false
                                velocity.resetTracking()
                            },
                            onDragEnd = {
                                // Decide by the dominant axis so a diagonal drag
                                // resolves to one intent, never both.
                                if (drivingSheet) {
                                    // Velocity and distance decide where it settles;
                                    // see PlayerSheetMotion.targetFor.
                                    sheetDrag?.onRelease(velocity.calculateVelocity().y)
                                } else if (abs(dy) > abs(dx) && dy < -DRAG_UP_THRESHOLD_PX) {
                                    // No surface to drive, so the old behaviour:
                                    // a decisive pull-up just opens the player.
                                    onExpand()
                                } else if (abs(dx) > abs(dy) &&
                                    abs(dx) > SWIPE_THRESHOLD_PX
                                ) {
                                    TransportHaptics.tick(view)
                                    if (dx < 0) onSwipeNext() else onSwipePrevious()
                                } else {
                                    // Moved, but not far enough to mean anything.
                                    //
                                    // This is why tapping the bar sometimes did
                                    // nothing: the tap detector gives up as soon as
                                    // the finger passes touch slop (a few pixels),
                                    // while the thresholds above need 80 px sideways
                                    // or 40 px up. A slightly smudged tap fell in
                                    // between and was silently discarded, and so was
                                    // any short diagonal drag.
                                    onBarClick()
                                }
                                dx = 0f; dy = 0f
                                drivingSheet = false
                            },
                            onDragCancel = {
                                // Still has to settle: the surface has moved.
                                if (drivingSheet) sheetDrag?.onRelease(0f)
                                dx = 0f; dy = 0f
                                drivingSheet = false
                            }
                        ) { change, drag ->
                            dx += drag.x
                            dy += drag.y
                            velocity.addPosition(change.uptimeMillis, change.position)

                            // Handed over only once this is clearly a vertical drag
                            // and clearly a drag at all, so a sideways track change
                            // never nudges the surface and a tap never moves it.
                            if (sheetDrag != null &&
                                abs(dy) > abs(dx) &&
                                abs(dy) > SHEET_DRAG_SLOP_PX
                            ) {
                                drivingSheet = true
                            }
                            if (drivingSheet) sheetDrag?.onDrag(drag.y)

                            change.consume()
                        }
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtImage(
                    artworkUri = uiState.artworkUri,
                    placeholderIconSize = 20.dp,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Title and artist slide in the direction of travel when the
                // track changes, echoing the swipe.
                AnimatedContent(
                    targetState = uiState.trackTitle to uiState.artist,
                    transitionSpec = {
                        (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 3 } + fadeOut()) using
                            SizeTransform(clip = false)
                    },
                    modifier = Modifier.weight(1f),
                    label = "miniPlayerTrack"
                ) { (title, artist) ->
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = colors.onAccent
                        )
                        if (artist.isNotEmpty()) {
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = colors.onAccent.copy(alpha = 0.75f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        TransportHaptics.confirm(view)
                        onPlayPauseClick()
                    }
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (uiState.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        ),
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(30.dp),
                        tint = colors.onAccent
                    )
                }
            }
        }
    }
}

/**
 * Height of the collapsed bar: its content plus the progress line above it.
 *
 * Public because the player surface needs it to know how much of itself to leave
 * showing when collapsed. Declared next to the layout it describes so the two cannot
 * drift apart.
 */
val MINI_PLAYER_HEIGHT = 62.dp

private const val SWIPE_THRESHOLD_PX = 80f
private const val DRAG_UP_THRESHOLD_PX = 40f

/**
 * Vertical travel before the drag is handed to the player surface.
 *
 * Small — this is only there to keep a tap or a sideways swipe from nudging the
 * surface. The decision about where it settles is made on release, not here, so
 * there is no reason for this to be large.
 */
private const val SHEET_DRAG_SLOP_PX = 8f
