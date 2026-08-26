package com.bitperfect.android.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bitperfect.android.ui.theme.BitPerfectMotion

/**
 * Album artwork with a graceful placeholder.
 *
 * Artwork comes from MediaStore as a content URI, which Coil loads directly.
 * Many albums have no artwork and some URIs resolve to nothing, so a failed
 * load falls back to the placeholder rather than leaving an empty box.
 */
@Composable
fun AlbumArtImage(
    artworkUri: String?,
    modifier: Modifier = Modifier,
    placeholderIconSize: Dp = 48.dp,
    contentDescription: String? = "Album art"
) {
    var hasFailed by remember(artworkUri) { mutableStateOf(false) }

    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.surface
                )
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        // Crossfade between covers so a track change does not flash.
        Crossfade(
            targetState = artworkUri.takeUnless { it.isNullOrBlank() || hasFailed },
            animationSpec = BitPerfectMotion.standard(),
            label = "albumArtwork"
        ) { resolvedUri ->
            if (resolvedUri == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = contentDescription,
                        modifier = Modifier.size(placeholderIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            } else {
                AsyncImage(
                    model = resolvedUri,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    // Coil's own fade covers the load; the crossfade above
                    // covers the swap between two different covers.
                    onError = { hasFailed = true }
                )
            }
        }
    }
}
