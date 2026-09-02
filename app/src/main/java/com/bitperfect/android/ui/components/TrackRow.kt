package com.bitperfect.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitperfect.android.ui.theme.BitPerfectShapeTokens

/**
 * Actions offered for a single track.
 *
 * Passing null for an action hides its menu entry, so the same row works in
 * the library, an album, a playlist and the queue.
 */
data class TrackRowActions(
    val onPlay: () -> Unit,
    val onPlayNext: (() -> Unit)? = null,
    val onAddToQueue: (() -> Unit)? = null,
    val onAddToPlaylist: (() -> Unit)? = null,
    val onToggleFavourite: (() -> Unit)? = null,
    /** Opens the full tag and quality readout. */
    val onShowInfo: (() -> Unit)? = null,
    /** Opens the library-only tag editor. */
    val onEditDetails: (() -> Unit)? = null,
    /** Opens the LRC editor, which also removes lyrics. */
    val onEditLyrics: (() -> Unit)? = null,
    val onRemove: (() -> Unit)? = null,
    val removeLabel: String = "Remove"
)

/**
 * A track in a list.
 *
 * Shows leading artwork or a track number, the title and artist, the exact
 * audio format, and an overflow menu. The row currently playing is tinted and
 * marked so it can be found at a glance in a long list.
 */
@Composable
fun TrackRow(
    title: String,
    artist: String,
    formatInfo: String,
    durationMs: Long,
    actions: TrackRowActions,
    modifier: Modifier = Modifier,
    artworkUri: String? = null,
    trackNumber: Int = 0,
    isPlaying: Boolean = false,
    isFavourite: Boolean = false,
    showArtwork: Boolean = true
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    val titleColour by animateColorAsState(
        targetValue = if (isPlaying) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "trackTitleColour"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = actions.onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showArtwork) {
            AlbumArtImage(
                artworkUri = artworkUri,
                modifier = Modifier
                    .size(48.dp)
                    .clip(BitPerfectShapeTokens.AlbumArtCorner),
                placeholderIconSize = 22.dp
            )
        } else {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Now playing",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        text = if (trackNumber > 0) trackNumber.toString() else "-",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                color = titleColour,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = artist.ifEmpty { "Unknown Artist" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (formatInfo.isNotEmpty()) {
                    Text(
                        text = " · $formatInfo",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                }
            }
        }

        if (isFavourite) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Favourite",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (durationMs > 0) {
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            IconButton(onClick = { isMenuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Track actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = { isMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Play") },
                    onClick = {
                        isMenuExpanded = false
                        actions.onPlay()
                    }
                )
                actions.onPlayNext?.let { action ->
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        onClick = {
                            isMenuExpanded = false
                            action()
                        }
                    )
                }
                actions.onAddToQueue?.let { action ->
                    DropdownMenuItem(
                        text = { Text("Add to queue") },
                        onClick = {
                            isMenuExpanded = false
                            action()
                        }
                    )
                }
                actions.onAddToPlaylist?.let { action ->
                    DropdownMenuItem(
                        text = { Text("Add to playlist") },
                        onClick = {
                            isMenuExpanded = false
                            action()
                        }
                    )
                }
                actions.onToggleFavourite?.let { action ->
                    DropdownMenuItem(
                        text = {
                            Text(if (isFavourite) "Remove favourite" else "Add favourite")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isFavourite) {
                                    Icons.Default.Favorite
                                } else {
                                    Icons.Default.FavoriteBorder
                                },
                                contentDescription = null
                            )
                        },
                        onClick = {
                            isMenuExpanded = false
                            action()
                        }
                    )
                }
                actions.onShowInfo?.let { action ->
                    DropdownMenuItem(
                        text = { Text("Info / Tags") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            action()
                        }
                    )
                }
                actions.onEditDetails?.let { action ->
                    DropdownMenuItem(
                        text = { Text("Edit tags") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            action()
                        }
                    )
                }
                actions.onEditLyrics?.let { action ->
                    DropdownMenuItem(
                        text = { Text("Lyrics") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Subtitles, contentDescription = null)
                        },
                        onClick = {
                            isMenuExpanded = false
                            action()
                        }
                    )
                }
                actions.onRemove?.let { action ->
                    DropdownMenuItem(
                        text = { Text(actions.removeLabel) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            isMenuExpanded = false
                            action()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Format a duration as m:ss, or h:mm:ss beyond an hour.
 */
fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0) return "0:00"
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
