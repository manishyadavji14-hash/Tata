package com.bitperfect.android.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitperfect.android.ui.components.AlbumArtImage
import com.bitperfect.android.ui.components.TrackRow
import com.bitperfect.android.ui.components.TrackRowActions
import com.bitperfect.android.ui.components.formatDuration
import com.bitperfect.android.ui.theme.BitPerfectMotion
import com.bitperfect.android.ui.theme.BitPerfectShapeTokens

/**
 * Lists the tracks of one collection: an album, artist, genre, composer,
 * folder, playlist or the favourites.
 *
 * Artist collections also show their albums, so the same screen covers both
 * levels of library navigation.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrackCollectionScreen(
    viewModel: TrackCollectionViewModel,
    onBackClick: () -> Unit,
    onAlbumClick: (Long) -> Unit = {},
    onAddToPlaylist: ((String) -> Unit)? = null,
    onRemoveFromCollection: ((Long) -> Unit)? = null,
    removeLabel: String = "Remove"
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(appBarState)

    // Surface transient confirmations such as "Added to queue".
    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (uiState.tracks.isNotEmpty()) {
                        IconButton(onClick = { viewModel.addAllToQueue() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = "Add all to queue"
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.isEmpty -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nothing here yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    item(key = "header") {
                        CollectionHeader(
                            title = uiState.title,
                            subtitle = uiState.subtitle,
                            artworkUri = uiState.artworkUri,
                            totalDurationMs = uiState.totalDurationMs,
                            hasTracks = uiState.tracks.isNotEmpty(),
                            onPlayAll = { viewModel.playAll() },
                            onShuffleAll = { viewModel.shuffleAll() }
                        )
                    }

                    if (uiState.albums.isNotEmpty()) {
                        item(key = "albumsHeading") {
                            SectionHeading(text = "Albums")
                        }
                        item(key = "albumsRow") {
                            LazyRow(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 16.dp
                                ),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(uiState.albums, key = { it.id }) { album ->
                                    AlbumChipCard(
                                        album = album,
                                        onClick = { onAlbumClick(album.id) }
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.tracks.isNotEmpty()) {
                        item(key = "tracksHeading") {
                            SectionHeading(text = "Tracks")
                        }
                    }

                    itemsIndexed(
                        items = uiState.tracks,
                        key = { _, track -> track.id.takeIf { it != 0L } ?: track.path }
                    ) { index, track ->
                        // Rows settle into place when the list changes, so
                        // removing or reordering reads as movement.
                        TrackRow(
                            modifier = Modifier.animateItemPlacement(
                                animationSpec = BitPerfectMotion.standard()
                            ),
                            title = trackTitleWithDisc(track, uiState.isMultipleDiscs),
                            artist = track.artist,
                            formatInfo = track.formatInfo,
                            durationMs = track.durationMs,
                            trackNumber = track.trackNumber,
                            artworkUri = track.artworkUri,
                            isPlaying = track.path == uiState.playingPath,
                            isFavourite = track.isFavourite,
                            showArtwork = !uiState.showTrackNumbers,
                            actions = TrackRowActions(
                                onPlay = { viewModel.playTrackAt(index) },
                                onPlayNext = { viewModel.playNext(track.path) },
                                onAddToQueue = { viewModel.addToQueue(track.path) },
                                onAddToPlaylist = onAddToPlaylist?.let { add ->
                                    { add(track.path) }
                                },
                                onToggleFavourite = { viewModel.toggleFavourite(track.id) },
                                onRemove = onRemoveFromCollection?.let { remove ->
                                    { remove(track.id) }
                                },
                                removeLabel = removeLabel
                            )
                        )
                    }


                    item(key = "footerSpace") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/**
 * Artwork, counts and the play actions for the collection.
 */
@Composable
private fun CollectionHeader(
    title: String,
    subtitle: String,
    artworkUri: String?,
    totalDurationMs: Long,
    hasTracks: Boolean,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AlbumArtImage(
            artworkUri = artworkUri,
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .aspectRatio(1f)
                .clip(BitPerfectShapeTokens.AlbumArtCorner),
            placeholderIconSize = 64.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = buildString {
                append(subtitle)
                if (totalDurationMs > 0) append(" · ${formatDuration(totalDurationMs)}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        AnimatedVisibility(
            visible = hasTracks,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 },
            exit = fadeOut(tween(120))
        ) {
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onPlayAll) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = onShuffleAll) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Shuffle")
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun AlbumChipCard(
    album: TrackCollectionViewModel.AlbumSummary,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
        shape = BitPerfectShapeTokens.AlbumArtCorner
    ) {
        Column {
            AlbumArtImage(
                artworkUri = album.artworkUri,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                placeholderIconSize = 36.dp
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        if (album.year > 0) append("${album.year} · ")
                        append("${album.trackCount} tracks")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Prefix the disc number when a collection spans more than one disc.
 */
private fun trackTitleWithDisc(
    track: TrackCollectionViewModel.TrackItem,
    isMultipleDiscs: Boolean
): String = if (isMultipleDiscs && track.discNumber > 0) {
    "${track.discNumber}-${track.trackNumber}  ${track.title}"
} else {
    track.title
}
