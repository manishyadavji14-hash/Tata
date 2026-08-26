package com.bitperfect.android.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitperfect.android.ui.components.AlbumArtImage
import com.bitperfect.android.ui.theme.BitPerfectMotion
import com.bitperfect.android.ui.theme.BitPerfectShapeTokens

/**
 * LibraryScreen - Music library browser with multiple tab views.
 *
 * Features:
 * - Tabs: Folders, Artists, Albums, Genres, Composers, Tracks
 * - Album grid with artwork thumbnails
 * - Artist list with album count
 * - Track list with format info (sample rate, bit depth)
 * - Search bar at top for filtering
 * - Pull-to-refresh for library rescan
 * - Sort options per tab
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onAlbumClick: (Long) -> Unit = {},
    onArtistClick: (Long) -> Unit = {},
    onGenreClick: (String) -> Unit = {},
    onComposerClick: (String) -> Unit = {},
    onFolderClick: (String) -> Unit = {},
    onFavouritesClick: () -> Unit = {},
    onPlaylistsClick: () -> Unit = {},
    onTrackClick: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val tabs = listOf("Folders", "Artists", "Albums", "Genres", "Composers", "Tracks")

    val pullRefreshState = rememberPullToRefreshState()

    // Pull-to-refresh triggers a real rescan and is released when it finishes.
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(Unit) { viewModel.rescan() }
    }
    // Release the gesture when the scan finishes, and also when it was declined
    // outright (missing permission, or a scan already running).
    LaunchedEffect(uiState.isScanning, uiState.scanRequestRejected) {
        if (!uiState.isScanning && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
        }
        if (uiState.scanRequestRejected) {
            viewModel.acknowledgeScanRejection()
        }
    }

    if (uiState.isFolderPickerVisible) {
        FolderPickerDialog(
            folders = uiState.availableFolders,
            onToggle = viewModel::toggleFolderSelection,
            onSelectAll = { viewModel.selectAllFolders(true) },
            onSelectNone = { viewModel.selectAllFolders(false) },
            onConfirm = { viewModel.confirmFolderSelection() },
            onDismiss = { viewModel.hideFolderPicker() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = { isSearchActive = !isSearchActive }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onFavouritesClick) {
                        Icon(Icons.Default.Favorite, contentDescription = "Favourites")
                    }
                    IconButton(onClick = onPlaylistsClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Playlists"
                        )
                    }
                    IconButton(onClick = { viewModel.showFolderPicker() }) {
                        Icon(Icons.Default.Folder, contentDescription = "Choose folders")
                    }
                    IconButton(onClick = { viewModel.cycleSortOrder() }) {
                        Icon(Icons.Default.Sort, contentDescription = "Sort")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar (expandable)
            if (isSearchActive) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { query ->
                        searchQuery = query
                        viewModel.search(query)
                    },
                    onSearch = { viewModel.search(searchQuery) },
                    active = false,
                    onActiveChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text("Search library...") },
                    leadingIcon = { Icon(Icons.Default.Search, "Search") }
                ) {}
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Tab row
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            viewModel.selectTab(LibraryViewModel.LibraryTab.entries[index])
                        },
                        text = { Text(title) }
                    )
                }
            }

            // Content area with pull-to-refresh
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(pullRefreshState.nestedScrollConnection)
            ) {
                when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    uiState.isEmpty -> {
                        LibraryEmptyState(
                            hasAudioPermission = uiState.hasAudioPermission,
                            isScanning = uiState.isScanning,
                            scanStatus = uiState.scanStatus,
                            statusMessage = uiState.statusMessage,
                            onScan = { viewModel.rescan() },
                            onChooseFolders = { viewModel.showFolderPicker() }
                        )
                    }
                    else -> {
                        // Tab content slides in the direction of travel, which
                        // makes the tab row feel connected to what it changes.
                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                val forward = targetState > initialState
                                val slide = { width: Int ->
                                    if (forward) width / 6 else -width / 6
                                }
                                (slideInHorizontally(
                                    animationSpec = tween(
                                        BitPerfectMotion.DURATION_STANDARD,
                                        easing = BitPerfectMotion.EmphasisedDecelerate
                                    ),
                                    initialOffsetX = slide
                                ) + fadeIn(BitPerfectMotion.standard())) togetherWith
                                    (fadeOut(BitPerfectMotion.exiting()))
                            },
                            label = "libraryTabContent"
                        ) { tabIndex ->
                            when (LibraryViewModel.LibraryTab.entries[tabIndex]) {
                                LibraryViewModel.LibraryTab.FOLDERS -> FolderList(
                                    folders = uiState.folders,
                                    onFolderClick = onFolderClick
                                )
                                LibraryViewModel.LibraryTab.ARTISTS -> ArtistList(
                                    artists = uiState.artists,
                                    onArtistClick = onArtistClick
                                )
                                LibraryViewModel.LibraryTab.ALBUMS -> AlbumGrid(
                                    albums = uiState.albums,
                                    onAlbumClick = onAlbumClick
                                )
                                LibraryViewModel.LibraryTab.GENRES -> GenreList(
                                    genres = uiState.genres,
                                    onGenreClick = onGenreClick
                                )
                                LibraryViewModel.LibraryTab.COMPOSERS -> ComposerList(
                                    composers = uiState.composers,
                                    onComposerClick = onComposerClick
                                )
                                LibraryViewModel.LibraryTab.TRACKS -> TrackList(
                                    tracks = uiState.tracks,
                                    onTrackClick = onTrackClick
                                )
                            }
                        }
                    }
                }

                if (uiState.isScanning && !uiState.isEmpty) {
                    ScanProgressBanner(
                        scanStatus = uiState.scanStatus,
                        progress = uiState.scanProgress,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                PullToRefreshContainer(
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<LibraryViewModel.AlbumItem>,
    onAlbumClick: (Long) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumCard(album = album, onClick = { onAlbumClick(album.id) })
        }
    }
}

@Composable
private fun AlbumCard(
    album: LibraryViewModel.AlbumItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        shape = BitPerfectShapeTokens.AlbumArtCorner,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            AlbumArtImage(
                artworkUri = album.artworkUri,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = album.artist.ifEmpty { "Unknown Artist" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        if (album.year > 0) append("${album.year} · ")
                        append("${album.trackCount} tracks")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ArtistList(
    artists: List<LibraryViewModel.ArtistItem>,
    onArtistClick: (Long) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(artists) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onArtistClick(artist.id) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${artist.albumCount} albums, ${artist.trackCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<LibraryViewModel.TrackItem>,
    onTrackClick: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(tracks) { track ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTrackClick(track.path) }
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AudioFile,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${track.artist} - ${track.album}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Format info
                Text(
                    text = track.formatInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun FolderList(
    folders: List<LibraryViewModel.FolderItem>,
    onFolderClick: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(folders) { folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFolderClick(folder.path) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${folder.trackCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun GenreList(
    genres: List<LibraryViewModel.GenreItem>,
    onGenreClick: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(genres) { genre ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGenreClick(genre.name) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = genre.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${genre.trackCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerList(
    composers: List<LibraryViewModel.ComposerItem>,
    onComposerClick: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(composers) { composer ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onComposerClick(composer.name) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Piano,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = composer.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${composer.trackCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


/**
 * Shown when the library has no tracks yet.
 *
 * The message and actions depend on why it is empty: a missing permission needs
 * a grant, whereas a granted permission just needs a scan.
 */
@Composable
private fun LibraryEmptyState(
    hasAudioPermission: Boolean,
    isScanning: Boolean,
    scanStatus: String,
    statusMessage: String?,
    onScan: () -> Unit,
    onChooseFolders: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (isScanning) {
                Text(
                    text = scanStatus.ifEmpty { "Scanning…" },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator()
                return@Column
            }

            Text(
                text = if (hasAudioPermission) "No music found" else "Music access needed",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (hasAudioPermission) {
                    "Scan this device for audio files, or pick specific folders."
                } else {
                    "Allow access to audio files so your music can be listed here. " +
                        "You can grant it in Settings › Apps › BitPerfect › Permissions."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            if (hasAudioPermission) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onScan) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan for music")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onChooseFolders) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose folders")
                }
            }
        }
    }
}

/**
 * Progress shown while rescanning a library that already has content.
 */
@Composable
private fun ScanProgressBanner(
    scanStatus: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = scanStatus.ifEmpty { "Scanning…" },
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Lets the user restrict scanning to specific folders on the device.
 */
@Composable
private fun FolderPickerDialog(
    folders: List<LibraryViewModel.SelectableFolder>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folders to scan") },
        text = {
            if (folders.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Looking for folders containing audio…",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Column {
                    Row {
                        TextButton(onClick = onSelectAll) { Text("All") }
                        TextButton(onClick = onSelectNone) { Text("None") }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(folders) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(folder.path) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = folder.isSelected,
                                    onCheckedChange = { onToggle(folder.path) }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folder.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${folder.trackCount} tracks · ${folder.path}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = folders.any { it.isSelected }
            ) {
                Text("Scan selected")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
