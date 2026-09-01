package com.bitperfect.android.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.bitperfect.android.player.Lyrics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitperfect.android.R
import com.bitperfect.android.player.RepeatMode
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.bitperfect.android.ui.components.AlbumArtImage
import com.bitperfect.android.ui.components.rememberDynamicAlbumColor
import com.bitperfect.android.ui.theme.BitPerfectGreen
import com.bitperfect.android.ui.theme.BitPerfectMotion
import com.bitperfect.android.ui.theme.BitPerfectShapeTokens
import com.bitperfect.android.ui.theme.DopPurple
import com.bitperfect.android.ui.theme.DsdBlue
import com.bitperfect.android.ui.theme.SeekBarActive

/**
 * PlayerScreen - Main player interface built with Jetpack Compose.
 *
 * Displays:
 * - Large album artwork (center, rounded corners)
 * - Track title, artist, album text
 * - Seek bar with current time / total time
 * - Play/Pause, Previous, Next, Shuffle, Repeat buttons
 * - Output format badge: "BITPERFECT" header with format detail
 *   (e.g., "FLAC . 24-bit . 192 kHz . 2ch" or "DSD64 . DoP . 176.4 kHz . 2ch"
 *    or "DSD64 . Native DSD . 2.8224 MHz . 2ch")
 * - Queue access button
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onOpenFile: () -> Unit = {},
    onCollapse: () -> Unit = {},
    onAlbumArtClick: () -> Unit = {},
    onEqualizerClick: () -> Unit = {},
    onQueueClick: () -> Unit = {},
    onAddToPlaylist: (String) -> Unit = {},
    onGoToAlbum: (Long) -> Unit = {},
    onGoToArtist: (Long) -> Unit = {},
    onGoToFolder: (String) -> Unit = {},
    onGoToGenre: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var isSleepTimerSheetVisible by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissStatusMessage()
    }

    if (isSleepTimerSheetVisible) {
        SleepTimerDialog(
            remainingMs = uiState.sleepTimerRemainingMs,
            onSelectMinutes = { minutes ->
                viewModel.setSleepTimer(minutes)
                isSleepTimerSheetVisible = false
            },
            onExtend = { minutes ->
                viewModel.extendSleepTimer(minutes)
                isSleepTimerSheetVisible = false
            },
            onDismiss = { isSleepTimerSheetVisible = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        // Accent from the current cover, animated across track changes, painted
        // as a top-down wash that fades into the normal background so the
        // controls below stay legible.
        val accent by rememberDynamicAlbumColor(
            artworkUri = uiState.artworkUri,
            fallback = MaterialTheme.colorScheme.primary
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                // Pull down from the top portion of the screen to dismiss,
                // mirroring the mini player's pull-up to open. Anchored to the
                // top half so it does not fight the seek slider or transport
                // controls lower down.
                .pointerInput(Unit) {
                    val topZone = size.height * 0.5f
                    var startY = 0f
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { offset -> startY = offset.y; dy = 0f },
                        onDragEnd = {
                            if (startY <= topZone && dy > COLLAPSE_THRESHOLD_PX) onCollapse()
                            dy = 0f
                        },
                        onDragCancel = { dy = 0f }
                    ) { change, drag ->
                        dy += drag
                        change.consume()
                    }
                }
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // The format badge and the file-open button used to sit here. The
            // badge is now a single line under the title, and opening a file is
            // reachable from the library, so the artwork leads the screen.
            uiState.errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Album artwork, which lifts slightly while playing
            val artworkScale by animateFloatAsState(
                targetValue = if (uiState.isPlaying) 1f else 0.92f,
                animationSpec = BitPerfectMotion.responsive(),
                label = "artworkScale"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(1f)
            ) {
                AlbumArtwork(
                    artworkUri = uiState.artworkUri,
                    isPlaying = uiState.isPlaying,
                    onClick = onAlbumArtClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(artworkScale)
                )

                // Lyrics toggle and overflow, over the lower-right of the art.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Only offered when there is something to show, so it never
                    // opens an empty panel.
                    if (!uiState.lyrics.isEmpty) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                        ) {
                            IconButton(onClick = { viewModel.toggleLyrics() }) {
                                Icon(
                                    imageVector = Icons.Default.Subtitles,
                                    contentDescription = if (uiState.isLyricsVisible) {
                                        "Hide lyrics"
                                    } else {
                                        "Show lyrics"
                                    },
                                    tint = if (uiState.isLyricsVisible) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    AlbumArtActions(
                        uiState = uiState,
                        onAddToPlaylist = onAddToPlaylist,
                        onGoToAlbum = onGoToAlbum,
                        onGoToArtist = onGoToArtist,
                        onGoToFolder = onGoToFolder,
                        onGoToGenre = onGoToGenre
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Lyrics take the place of the title block when switched on, which is
            // what keeps the layout from growing and pushing the transport
            // controls off smaller screens.
            if (uiState.isLyricsVisible) {
                LyricsPanel(
                    lyrics = uiState.lyrics,
                    currentIndex = uiState.currentLyricIndex,
                    offsetMs = uiState.lyricsOffsetMs,
                    onNudge = viewModel::nudgeLyrics,
                    onResetOffset = viewModel::resetLyricsOffset
                )
            } else {
                TrackInfo(
                    title = uiState.trackTitle,
                    artist = uiState.artist,
                    album = uiState.album,
                    formatLine = uiState.formatDetail,
                    outputMode = uiState.outputMode
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Seek bar
            SeekBar(
                positionMs = uiState.positionMs,
                durationMs = uiState.durationMs,
                positionText = uiState.positionText,
                durationText = uiState.durationText,
                onSeek = { viewModel.seekTo(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Transport controls
            TransportControls(
                isPlaying = uiState.isPlaying,
                isLoading = uiState.isLoading,
                isShuffleEnabled = uiState.isShuffleEnabled,
                repeatMode = uiState.repeatMode,
                hasNext = uiState.hasNext,
                hasPrevious = uiState.hasPrevious,
                onPlayPause = { viewModel.togglePlayPause() },
                onNext = { viewModel.next() },
                onPrevious = { viewModel.previous() },
                onShuffle = { viewModel.toggleShuffle() },
                onRepeat = { viewModel.cycleRepeatMode() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom row: device info and queue button
            BottomRow(
                deviceName = uiState.deviceName,
                isFavourite = uiState.isFavourite,
                sleepTimerRemainingMs = uiState.sleepTimerRemainingMs,
                onToggleFavourite = { viewModel.toggleFavourite() },
                onSleepTimerClick = { isSleepTimerSheetVisible = true },
                onEqualizerClick = onEqualizerClick,
                onQueueClick = onQueueClick
            )
        }
        } // accent-gradient Box
    }
}

@Composable
private fun AlbumArtwork(
    artworkUri: String?,
    isPlaying: Boolean,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = BitPerfectShapeTokens.AlbumArtCorner,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        AlbumArtImage(
            artworkUri = artworkUri,
            modifier = Modifier.fillMaxSize(),
            placeholderIconSize = 96.dp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackInfo(
    title: String,
    artist: String,
    album: String,
    /** Sample rate, depth and codec, shown quietly instead of as a top badge. */
    formatLine: String = "",
    outputMode: PlayerViewModel.OutputMode = PlayerViewModel.OutputMode.PCM
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Long titles scroll rather than truncate, so the full name is readable.
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (album.isNotEmpty()) {
            Text(
                text = album,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        if (formatLine.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = formatLine,
                style = MaterialTheme.typography.labelSmall,
                // Tinted by output mode, so bit-perfect USB still reads as
                // distinct from the Android mixer without a whole badge.
                color = when (outputMode) {
                    PlayerViewModel.OutputMode.BITPERFECT -> BitPerfectGreen
                    PlayerViewModel.OutputMode.DOP -> DopPurple
                    PlayerViewModel.OutputMode.NATIVE_DSD -> DsdBlue
                    PlayerViewModel.OutputMode.PCM ->
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    positionText: String,
    durationText: String,
    onSeek: (Long) -> Unit
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    val progress = if (durationMs > 0) {
        if (isSeeking) seekPosition else positionMs.toFloat() / durationMs.toFloat()
    } else {
        0f
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = { value ->
                isSeeking = true
                seekPosition = value
            },
            onValueChangeFinished = {
                isSeeking = false
                onSeek((seekPosition * durationMs).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = SeekBarActive,
                activeTrackColor = SeekBarActive,
                inactiveTrackColor = SeekBarActive.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = positionText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = durationText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    hasNext: Boolean,
    hasPrevious: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Shuffle
        IconButton(onClick = onShuffle) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (isShuffleEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        // Previous
        IconButton(
            onClick = onPrevious,
            enabled = hasPrevious
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                modifier = Modifier.size(36.dp)
            )
        }

        // Play/Pause
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
            onClick = onPlayPause
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Cross-fade between loading, play and pause so the primary
                // control never jumps between states.
                AnimatedContent(
                    targetState = when {
                        isLoading -> TransportIconState.LOADING
                        isPlaying -> TransportIconState.PAUSE
                        else -> TransportIconState.PLAY
                    },
                    transitionSpec = {
                        (fadeIn(BitPerfectMotion.quick()) +
                            scaleIn(BitPerfectMotion.quick(), initialScale = 0.75f)) togetherWith
                            (fadeOut(BitPerfectMotion.quick()) +
                                scaleOut(BitPerfectMotion.quick(), targetScale = 0.75f))
                    },
                    label = "transportIcon"
                ) { iconState ->
                    when (iconState) {
                        TransportIconState.LOADING -> CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp
                        )

                        TransportIconState.PAUSE -> Icon(
                            painter = painterResource(id = R.drawable.ic_pause),
                            contentDescription = "Pause",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )

                        TransportIconState.PLAY -> Icon(
                            painter = painterResource(id = R.drawable.ic_play),
                            contentDescription = "Play",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // Next
        IconButton(
            onClick = onNext,
            enabled = hasNext
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                modifier = Modifier.size(36.dp)
            )
        }

        // Repeat
        IconButton(onClick = onRepeat) {
            val repeatIcon = when (repeatMode) {
                RepeatMode.ONE -> Icons.Default.RepeatOne
                else -> Icons.Default.Repeat
            }
            val repeatTint = when (repeatMode) {
                RepeatMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            }
            Icon(
                imageVector = repeatIcon,
                contentDescription = "Repeat",
                tint = repeatTint
            )
        }
    }
}

@Composable
private fun BottomRow(
    deviceName: String,
    isFavourite: Boolean,
    sleepTimerRemainingMs: Long?,
    onToggleFavourite: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onEqualizerClick: () -> Unit,
    onQueueClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Device info. No longer a shortcut to diagnostics: that screen is a
        // developer tool and belongs in Settings, not on the player.
        Surface(
            shape = BitPerfectShapeTokens.FormatBadgeCorner,
            color = Color.Transparent
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_usb),
                    contentDescription = "USB DAC",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = deviceName.ifEmpty { "No device" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleFavourite) {
                Icon(
                    imageVector = if (isFavourite) {
                        Icons.Default.Favorite
                    } else {
                        Icons.Default.FavoriteBorder
                    },
                    contentDescription = if (isFavourite) "Remove favourite" else "Add favourite",
                    tint = if (isFavourite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = onSleepTimerClick) {
                Icon(
                    imageVector = Icons.Default.Bedtime,
                    contentDescription = "Sleep timer",
                    tint = if (sleepTimerRemainingMs != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = onEqualizerClick) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Equalizer",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onQueueClick) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = "Queue",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


/**
 * Sleep timer picker.
 *
 * Offers the common presets rather than a free-form duration, and when a timer
 * is already running it shows the remaining time and offers to extend it.
 */
@Composable
private fun SleepTimerDialog(
    remainingMs: Long?,
    onSelectMinutes: (Int) -> Unit,
    onExtend: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val presets = listOf(15, 30, 45, 60, 90)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                if (remainingMs != null) {
                    Text(
                        text = "Pausing in ${formatRemaining(remainingMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onExtend(15) }) { Text("+15 min") }
                        OutlinedButton(onClick = { onExtend(30) }) { Text("+30 min") }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Or choose a new duration",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Pause playback after",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                presets.forEach { minutes ->
                    Surface(
                        onClick = { onSelectMinutes(minutes) },
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "$minutes minutes",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (remainingMs != null) {
                TextButton(onClick = { onSelectMinutes(0) }) { Text("Turn off") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Remaining sleep time, rounded up to the minute above one minute.
 */
private fun formatRemaining(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    return if (totalSeconds < 60) {
        "$totalSeconds sec"
    } else {
        "${(totalSeconds + 59) / 60} min"
    }
}


/**
 * The three states the primary transport button animates between.
 */
private enum class TransportIconState { LOADING, PLAY, PAUSE }


/** How far the top of the player must be dragged down to dismiss it. */
private const val COLLAPSE_THRESHOLD_PX = 120f


/**
 * Overflow actions, overlaid on the lower-right corner of the album art.
 *
 * Only entries that can actually do something are shown. Navigation targets are
 * hidden when the file is not in the library or the tag is missing, so the menu
 * never offers a dead action — an item that opens an empty screen is worse than
 * no item at all.
 *
 * Deliberately absent for now, rather than shown and inert:
 *  - **Lyrics**, which needs the synced-lyrics view. It gets its own icon here
 *    once that exists.
 *  - **Delete**, because removing a user's file on Android 11+ requires a
 *    MediaStore consent flow, and a half-built destructive action is the worst
 *    thing to ship.
 *  - **Album art** and **Bookmark**, whose intended behaviour is not yet defined.
 */
@Composable
private fun AlbumArtActions(
    uiState: PlayerViewModel.PlayerUiState,
    onAddToPlaylist: (String) -> Unit,
    onGoToAlbum: (Long) -> Unit,
    onGoToArtist: (Long) -> Unit,
    onGoToFolder: (String) -> Unit,
    onGoToGenre: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isMenuOpen by remember { mutableStateOf(false) }
    var isInfoOpen by remember { mutableStateOf(false) }

    val hasTrack = uiState.trackPath.isNotEmpty()
    if (!hasTrack) return

    Box(modifier = modifier) {
        // Tinted surface so the button stays visible over both bright and dark
        // artwork.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
        ) {
            IconButton(onClick = { isMenuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Track options",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        DropdownMenu(
            expanded = isMenuOpen,
            onDismissRequest = { isMenuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text("Info / Tags") },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = {
                    isMenuOpen = false
                    isInfoOpen = true
                }
            )

            if (uiState.isInLibrary) {
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                    },
                    onClick = {
                        isMenuOpen = false
                        onAddToPlaylist(uiState.trackPath)
                    }
                )
            }

            if (uiState.albumId != 0L) {
                DropdownMenuItem(
                    text = { Text("Go to album") },
                    leadingIcon = { Icon(Icons.Default.Album, contentDescription = null) },
                    onClick = {
                        isMenuOpen = false
                        onGoToAlbum(uiState.albumId)
                    }
                )
            }

            if (uiState.artistId != 0L) {
                DropdownMenuItem(
                    text = { Text("Go to artist") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    onClick = {
                        isMenuOpen = false
                        onGoToArtist(uiState.artistId)
                    }
                )
            }

            if (uiState.genre.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Go to genre") },
                    leadingIcon = { Icon(Icons.Default.Category, contentDescription = null) },
                    onClick = {
                        isMenuOpen = false
                        onGoToGenre(uiState.genre)
                    }
                )
            }

            if (uiState.folder.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Go to folder") },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    onClick = {
                        isMenuOpen = false
                        onGoToFolder(uiState.folder)
                    }
                )
            }
        }
    }

    if (isInfoOpen) {
        TrackInfoDialog(uiState = uiState, onDismiss = { isInfoOpen = false })
    }
}

/**
 * Everything known about the current file, including the facts the cleaned-up
 * player no longer shows on screen.
 */
@Composable
private fun TrackInfoDialog(
    uiState: PlayerViewModel.PlayerUiState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Info / Tags") },
        text = {
            Column {
                InfoLine("Title", uiState.trackTitle)
                InfoLine("Artist", uiState.artist)
                InfoLine("Album", uiState.album)
                InfoLine("Genre", uiState.genre)
                if (uiState.year > 0) InfoLine("Year", uiState.year.toString())
                if (uiState.trackNumber > 0) InfoLine("Track", uiState.trackNumber.toString())
                InfoLine("Format", uiState.formatBadge)
                if (uiState.sampleRate > 0) {
                    InfoLine("Sample rate", "${uiState.sampleRate} Hz")
                }
                if (uiState.bitDepth > 0) InfoLine("Bit depth", "${uiState.bitDepth}-bit")
                if (uiState.channels > 0) InfoLine("Channels", uiState.channels.toString())
                if (uiState.durationMs > 0) InfoLine("Duration", uiState.durationText)
                if (uiState.fileSize > 0) {
                    InfoLine("File size", formatFileSize(uiState.fileSize))
                }
                InfoLine("Folder", uiState.folder)
                // Full path last: it is the longest and the least often wanted,
                // but it is the only way to identify a file unambiguously.
                InfoLine("Path", uiState.trackPath)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}


/**
 * Lyrics in the space the title block normally occupies.
 *
 * Fixed to three rows on purpose. It replaces the title rather than adding to the
 * column, so the artwork, seek bar and transport controls do not move when it is
 * switched on, and it cannot push them off a short screen.
 *
 * Timed lyrics scroll themselves and centre the current line. Untimed lyrics are
 * scrolled by hand, which is the only thing that can be done without timings.
 */
@Composable
private fun LyricsPanel(
    lyrics: Lyrics,
    currentIndex: Int,
    offsetMs: Long,
    onNudge: (Long) -> Unit,
    onResetOffset: () -> Unit
) {
    val listState = rememberLazyListState()

    // Follow the song. Scrolling to index - 1 puts the current line in the middle
    // of three visible rows rather than at the top edge.
    LaunchedEffect(currentIndex, lyrics) {
        if (lyrics.isSynced && currentIndex >= 0) {
            listState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyColumn(
            state = listState,
            // Three rows of body text. Enough to read a line in context without
            // taking space from the controls.
            modifier = Modifier
                .fillMaxWidth()
                .height(LYRICS_PANEL_HEIGHT),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Untimed lyrics are free to be flung; timed ones are driven by
            // playback, and letting the user fight the auto-scroll feels broken.
            userScrollEnabled = !lyrics.isSynced
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                val isCurrent = lyrics.isSynced && index == currentIndex
                Text(
                    text = line.text,
                    style = if (isCurrent) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        // Dimmed so the eye lands on the current line immediately.
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (lyrics.isSynced) {
            // Timing nudge, for files whose stamps run early or late.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onNudge(-500L) }) { Text("-0.5s") }
                TextButton(onClick = onResetOffset) {
                    Text(
                        text = if (offsetMs == 0L) {
                            "in sync"
                        } else {
                            "%+.1fs".format(offsetMs / 1000.0)
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                TextButton(onClick = { onNudge(500L) }) { Text("+0.5s") }
            }
        } else {
            Text(
                // Says plainly why it is not following the song, rather than
                // looking like broken sync.
                text = "No timings in this file — scroll to follow",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/** Three rows of body text plus padding. */
private val LYRICS_PANEL_HEIGHT = 96.dp
