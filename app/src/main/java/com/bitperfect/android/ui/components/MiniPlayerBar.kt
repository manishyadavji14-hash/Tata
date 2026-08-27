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
    modifier: Modifier = Modifier
) {
    if (!uiState.isPlaying && !uiState.isPaused) return

    val progress = if (uiState.durationMs > 0) {
        (uiState.positionMs.toFloat() / uiState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

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
                    .pointerInput(Unit) {
                        var dx = 0f
                        var dy = 0f
                        detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f },
                            onDragEnd = {
                                // Decide by the dominant axis so a diagonal drag
                                // resolves to one intent, never both.
                                if (abs(dy) > abs(dx) && dy < -DRAG_UP_THRESHOLD_PX) {
                                    onExpand()
                                } else if (abs(dx) > abs(dy) &&
                                    abs(dx) > SWIPE_THRESHOLD_PX
                                ) {
                                    if (dx < 0) onSwipeNext() else onSwipePrevious()
                                }
                                dx = 0f; dy = 0f
                            },
                            onDragCancel = { dx = 0f; dy = 0f }
                        ) { change, drag ->
                            dx += drag.x
                            dy += drag.y
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

                IconButton(onClick = onPlayPauseClick) {
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

private const val SWIPE_THRESHOLD_PX = 80f
private const val DRAG_UP_THRESHOLD_PX = 40f
