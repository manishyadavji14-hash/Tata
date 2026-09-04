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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.bitperfect.android.ui.components.AlphabetIndexBar
import com.bitperfect.android.ui.components.EditTrackDetailsDialog
import com.bitperfect.android.ui.components.LyricsEditorDialog
import com.bitperfect.android.ui.components.TrackInfo
import com.bitperfect.android.ui.components.TrackInfoDialog
import com.bitperfect.android.ui.components.TrackRow
import com.bitperfect.android.ui.components.TrackRowActions
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
    /** Opens the system document picker for a .zip; result routes to importZip. */
    onPickZip: () -> Unit = {},
    /** Receives the full visible track list and the index that was tapped. */
    onTrackClick: (tracks: List<String>, index: Int) -> Unit = { _, _ -> },
    /**
     * Opens the playlist picker for one track. Hosted by the navigation graph,
     * which owns the shared PlaylistsViewModel.
     */
    onAddToPlaylist: (String) -> Unit = {},
    /**
     * File currently loaded in the player, so its row can be marked and brought
     * into view. Empty when nothing is playing.
     */
    nowPlayingPath: String = ""
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(uiState.currentTab.ordinal) }

    // Follow programmatic tab changes (e.g. the player's album-art tap asking
    // for the Tracks tab). User taps set both, so this never fights them.
    LaunchedEffect(uiState.currentTab) {
        if (selectedTab != uiState.currentTab.ordinal) {
            selectedTab = uiState.currentTab.ordinal
        }
    }

    // Shared so a scroll-to-track request can drive it.
    val trackListState = rememberLazyListState()

    // Only under a name order: see SortOrder.isByName. Recomputed when the list or
    // the order changes, not on every recomposition — it walks every row.
    val trackAlphabetIndex = remember(uiState.tracks, uiState.sortOrder) {
        if (uiState.sortOrder.isByName) {
            AlphabetIndex.build(uiState.tracks.map { it.title })
        } else {
            emptyList()
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // Order must match LibraryTab.entries, since the tab index maps to it.
    val tabs = listOf("Tracks", "Albums", "Artists", "Folders", "Genres", "Composers")

    val pullRefreshState = rememberPullToRefreshState()
    var isScanSheetVisible by remember { mutableStateOf(false) }
    var isSortMenuVisible by remember { mutableStateOf(false) }

    // One selected track per dialog, rather than a shared "current track" that
    // could be left pointing at a row the user has since removed.
    var infoTrack by remember { mutableStateOf<LibraryViewModel.TrackItem?>(null) }
    var editTrack by remember { mutableStateOf<LibraryViewModel.TrackItem?>(null) }
    var lyricsTrack by remember { mutableStateOf<LibraryViewModel.TrackItem?>(null) }
    var removeTrack by remember { mutableStateOf<LibraryViewModel.TrackItem?>(null) }

    TrackActionDialogs(
        viewModel = viewModel,
        infoTrack = infoTrack,
        editTrack = editTrack,
        lyricsTrack = lyricsTrack,
        removeTrack = removeTrack,
        onDismissInfo = { infoTrack = null },
        onDismissEdit = { editTrack = null },
        onDismissLyrics = { lyricsTrack = null },
        onDismissRemove = { removeTrack = null }
    )

    // The gesture triggers a scan exactly once, keyed on the refreshing edge so
    // it cannot re-fire while the finger is held.
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.rescan()
        }
    }
    // The indicator is driven purely by the scan's busy flag: when scanning
    // stops, the indicator stops. This is the single source of truth, replacing
    // the earlier two-way handshake whose races left the spinner running.
    LaunchedEffect(uiState.isScanning) {
        if (!uiState.isScanning && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
        }
    }
    LaunchedEffect(uiState.scanRequestRejected) {
        if (uiState.scanRequestRejected) {
            if (pullRefreshState.isRefreshing) pullRefreshState.endRefresh()
            viewModel.acknowledgeScanRejection()
        }
    }

    // Jump to the playing track when the player's album art was tapped. Waits
    // for the tracks list to be populated, scrolls to it, then clears the
    // request so it fires once.
    // Open the list at the song that is playing rather than at the top.
    //
    // Once per visit, not on every track change: following playback would yank the
    // list out from under someone who has scrolled somewhere else. The flag lives
    // in the composition, so it resets when the screen is next opened.
    var hasScrolledToNowPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(nowPlayingPath, uiState.tracks) {
        if (hasScrolledToNowPlaying || nowPlayingPath.isEmpty()) return@LaunchedEffect
        val index = uiState.tracks.indexOfFirst { it.path == nowPlayingPath }
        if (index >= 0) {
            trackListState.scrollToItem(index)
            hasScrolledToNowPlaying = true
        }
    }

    LaunchedEffect(uiState.scrollToPath, uiState.tracks) {
        val target = uiState.scrollToPath ?: return@LaunchedEffect
        val index = uiState.tracks.indexOfFirst { it.path == target }
        if (index >= 0) {
            trackListState.scrollToItem(index)
            viewModel.consumeScrollTarget()
        } else if (uiState.tracks.isNotEmpty()) {
            // The list is loaded but the track is not in it; do not keep waiting.
            viewModel.consumeScrollTarget()
        }
    }

    if (isScanSheetVisible) {
        ScanOptionsSheet(
            onScanAll = { viewModel.scanAll() },
            onScanFolders = { viewModel.showFolderPicker() },
            onScanZip = { onPickZip() },
            onScanByFormat = { formats -> viewModel.scanByFormats(formats) },
            onDismiss = { isScanSheetVisible = false }
        )
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
                    // A labelled menu rather than a button that silently advanced
                    // through five orders with no way to see which was active.
                    Box {
                        IconButton(onClick = { isSortMenuVisible = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = isSortMenuVisible,
                            onDismissRequest = { isSortMenuVisible = false }
                        ) {
                            Text(
                                text = "Sort by",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            // Only the orders that mean something on this tab.
                            LibraryViewModel.SortOrder.optionsFor(uiState.currentTab)
                                .forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        leadingIcon = {
                                            RadioButton(
                                                selected = uiState.sortOrder == option,
                                                onClick = null
                                            )
                                        },
                                        onClick = {
                                            isSortMenuVisible = false
                                            viewModel.setSortOrder(option)
                                        }
                                    )
                                }
                        }
                    }
                    // The single entry point for every scan mode.
                    IconButton(onClick = { isScanSheetVisible = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan for music")
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

            // Library totals. Hidden while empty, where the empty state already
            // explains what to do, and while loading, where every count is 0.
            if (!uiState.isEmpty && !uiState.isLoading) {
                LibraryStatsHeader(uiState = uiState)
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
                                    listState = trackListState,
                                    alphabetIndex = trackAlphabetIndex,
                                    nowPlayingPath = nowPlayingPath,
                                    onTrackClick = onTrackClick,
                                    onToggleFavourite = viewModel::toggleFavourite,
                                    onAddToPlaylist = onAddToPlaylist,
                                    onShowInfo = { infoTrack = it },
                                    onEditDetails = { editTrack = it },
                                    onEditLyrics = { lyricsTrack = it },
                                    onRemove = { removeTrack = it }
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

                // Only while the gesture is active. PullToRefreshContainer paints
                // its circular surface unconditionally — the indicator inside
                // scales with progress, but the container does not — so composing
                // it at rest left a grey disc sitting over the tab row.
                if (pullRefreshState.progress > 0f || pullRefreshState.isRefreshing) {
                    PullToRefreshContainer(
                        state = pullRefreshState,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
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

/**
 * Library-wide totals, shown above the tabs.
 *
 * The hi-res and DSD counts are omitted when zero rather than shown as "0",
 * so the row stays meaningful for a library that has neither.
 */
@Composable
private fun LibraryStatsHeader(uiState: LibraryViewModel.LibraryUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LibraryStatItem(count = uiState.totalTracks, label = "Tracks")
            LibraryStatItem(count = uiState.totalAlbums, label = "Albums")
            LibraryStatItem(count = uiState.totalArtists, label = "Artists")
            if (uiState.highResCount > 0) {
                LibraryStatItem(count = uiState.highResCount, label = "Hi-Res")
            }
            if (uiState.dsdCount > 0) {
                LibraryStatItem(count = uiState.dsdCount, label = "DSD")
            }
        }
    }
}

@Composable
private fun LibraryStatItem(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The Tracks tab.
 *
 * Uses the shared [TrackRow] rather than a private layout, so the library gets
 * the same artwork, favourite marker and overflow menu as every album, playlist
 * and folder list — and so there is one row to fix rather than two.
 */
@Composable
private fun TrackList(
    tracks: List<LibraryViewModel.TrackItem>,
    onTrackClick: (tracks: List<String>, index: Int) -> Unit,
    onToggleFavourite: (Long) -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onShowInfo: (LibraryViewModel.TrackItem) -> Unit,
    onEditDetails: (LibraryViewModel.TrackItem) -> Unit,
    onEditLyrics: (LibraryViewModel.TrackItem) -> Unit,
    onRemove: (LibraryViewModel.TrackItem) -> Unit,
    nowPlayingPath: String = "",
    listState: LazyListState = rememberLazyListState(),
    /**
     * A-Z jump targets, or empty to show no strip. Empty when the list is not in
     * name order, where a letter strip would point at nothing in particular.
     */
    alphabetIndex: List<AlphabetIndex.Entry> = emptyList()
) {
    Box(modifier = Modifier.fillMaxSize()) {
        TrackListContent(
            tracks = tracks,
            onTrackClick = onTrackClick,
            onToggleFavourite = onToggleFavourite,
            onAddToPlaylist = onAddToPlaylist,
            onShowInfo = onShowInfo,
            onEditDetails = onEditDetails,
            onEditLyrics = onEditLyrics,
            onRemove = onRemove,
            nowPlayingPath = nowPlayingPath,
            listState = listState
        )

        AlphabetIndexBar(
            entries = alphabetIndex,
            listState = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun TrackListContent(
    tracks: List<LibraryViewModel.TrackItem>,
    onTrackClick: (tracks: List<String>, index: Int) -> Unit,
    onToggleFavourite: (Long) -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onShowInfo: (LibraryViewModel.TrackItem) -> Unit,
    onEditDetails: (LibraryViewModel.TrackItem) -> Unit,
    onEditLyrics: (LibraryViewModel.TrackItem) -> Unit,
    onRemove: (LibraryViewModel.TrackItem) -> Unit,
    nowPlayingPath: String,
    listState: LazyListState
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Keyed so removing a row animates the rest rather than recomposing the
        // whole list, and so Compose does not reuse state across rows.
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                title = track.title,
                artist = track.artist,
                formatInfo = track.formatInfo,
                durationMs = track.durationMs,
                artworkUri = track.artworkUri,
                isFavourite = track.isFavourite,
                // Tints and bolds the row, so the song playing is findable in a
                // long list at a glance.
                isPlaying = track.path == nowPlayingPath,
                actions = TrackRowActions(
                    // Reports the whole visible list, not just this path, so
                    // playback continues through the list the user tapped in.
                    onPlay = { onTrackClick(tracks.map { it.path }, index) },
                    onAddToPlaylist = { onAddToPlaylist(track.path) },
                    onToggleFavourite = { onToggleFavourite(track.id) },
                    onShowInfo = { onShowInfo(track) },
                    onEditDetails = { onEditDetails(track) },
                    onEditLyrics = { onEditLyrics(track) },
                    onRemove = { onRemove(track) },
                    removeLabel = "Remove from library"
                )
            )
        }
    }
}

/**
 * The dialogs the track overflow menu opens.
 *
 * Hoisted out of the screen body so the Scaffold stays readable, and kept in one
 * composable so only the selected-track state has to be threaded through.
 */
@Composable
private fun TrackActionDialogs(
    viewModel: LibraryViewModel,
    infoTrack: LibraryViewModel.TrackItem?,
    editTrack: LibraryViewModel.TrackItem?,
    lyricsTrack: LibraryViewModel.TrackItem?,
    removeTrack: LibraryViewModel.TrackItem?,
    onDismissInfo: () -> Unit,
    onDismissEdit: () -> Unit,
    onDismissLyrics: () -> Unit,
    onDismissRemove: () -> Unit
) {
    infoTrack?.let { track ->
        TrackInfoDialog(info = track.toTrackInfo(), onDismiss = onDismissInfo)
    }

    editTrack?.let { track ->
        EditTrackDetailsDialog(
            info = track.toTrackInfo(),
            onSave = { edited ->
                viewModel.updateTrackDetails(
                    trackId = track.id,
                    title = edited.title,
                    artist = edited.artist,
                    album = edited.album,
                    albumArtist = edited.albumArtist,
                    genre = edited.genre,
                    year = edited.year,
                    trackNumber = edited.trackNumber
                )
                onDismissEdit()
            },
            onDismiss = onDismissEdit
        )
    }

    lyricsTrack?.let { track ->
        // Loaded rather than passed in: the text may come from a sidecar file or
        // the file's own tags, which means disk I/O that must not run on the main
        // thread or on every recomposition.
        var loaded by remember(track.path) { mutableStateOf<String?>(null) }
        LaunchedEffect(track.path) {
            loaded = viewModel.loadEditableLyrics(track.path)
        }

        loaded?.let { existing ->
            LyricsEditorDialog(
                trackTitle = track.title,
                initialLyrics = existing,
                hasExistingLyrics = existing.isNotBlank(),
                onSave = {
                    viewModel.saveLyrics(track.path, it)
                    onDismissLyrics()
                },
                onRemove = {
                    viewModel.saveLyrics(track.path, "")
                    onDismissLyrics()
                },
                onDismiss = onDismissLyrics
            )
        }
    }

    removeTrack?.let { track ->
        // Confirmed, because it is destructive to the library even though the file
        // survives, and the wording says exactly that.
        AlertDialog(
            onDismissRequest = onDismissRemove,
            title = { Text("Remove from library?") },
            text = {
                Text(
                    "\"${track.title}\" will be removed from BitPerfect's library. " +
                        "The file stays on your device, and a future scan of that " +
                        "folder will find it again."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeTrackFromLibrary(track.id)
                        onDismissRemove()
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = onDismissRemove) { Text("Cancel") }
            }
        )
    }
}

/** Library row facts as the neutral shape the shared dialogs take. */
private fun LibraryViewModel.TrackItem.toTrackInfo() = TrackInfo(
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    genre = genre,
    composer = composer,
    year = year,
    trackNumber = trackNumber,
    formatBadge = formatInfo,
    sampleRate = sampleRate,
    bitDepth = bitDepth,
    channels = channels,
    durationMs = durationMs,
    fileSize = fileSize,
    folder = folder,
    path = path,
    playedPercent = playedPercent,
    playedMs = playedMs,
    isUserEdited = isUserEdited
)

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


/**
 * The four scan modes, in a bottom sheet.
 *
 * "Scan all" and "By folder" and "From ZIP" act immediately; "By format" opens
 * a second step of format chips because it needs a selection first.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScanOptionsSheet(
    onScanAll: () -> Unit,
    onScanFolders: () -> Unit,
    onScanZip: () -> Unit,
    onScanByFormat: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var showFormatPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = if (showFormatPicker) "Scan by format" else "Scan for music",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )

            if (!showFormatPicker) {
                ScanOption(
                    icon = Icons.Default.LibraryMusic,
                    title = "Scan all",
                    subtitle = "Every audio file on the device"
                ) { onScanAll(); onDismiss() }

                ScanOption(
                    icon = Icons.Default.Folder,
                    title = "Choose folders",
                    subtitle = "Pick which folders to include"
                ) { onScanFolders(); onDismiss() }

                ScanOption(
                    icon = Icons.Default.FolderZip,
                    title = "Import from ZIP",
                    subtitle = "Extract audio from a .zip archive"
                ) { onScanZip(); onDismiss() }

                ScanOption(
                    icon = Icons.Default.GraphicEq,
                    title = "Scan by format",
                    subtitle = "Only add chosen formats, e.g. FLAC or Opus"
                ) { showFormatPicker = true }
            } else {
                FormatPicker(
                    onConfirm = { formats ->
                        onScanByFormat(formats)
                        onDismiss()
                    },
                    onBack = { showFormatPicker = false }
                )
            }
        }
    }
}

@Composable
private fun ScanOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/**
 * Format chooser for the "scan by format" mode.
 *
 * The list is the formats the player can actually decode, so it never offers one
 * that would scan to nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatPicker(
    onConfirm: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    // Grouped so related extensions toggle together and the user picks a format,
    // not a file suffix.
    val formatGroups = remember {
        listOf(
            "FLAC" to setOf("flac"),
            "WAV" to setOf("wav", "wave"),
            "MP3" to setOf("mp3"),
            "AAC / M4A" to setOf("aac", "m4a", "mp4"),
            "Opus" to setOf("opus"),
            "OGG Vorbis" to setOf("ogg", "oga"),
            "ALAC" to setOf("alac"),
            "AIFF" to setOf("aiff", "aif")
        )
    }
    val selected = remember { mutableStateListOf<String>() }

    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            formatGroups.forEach { (label, exts) ->
                val isOn = selected.containsAll(exts)
                FilterChip(
                    selected = isOn,
                    onClick = {
                        if (isOn) selected.removeAll(exts) else selected.addAll(exts)
                    },
                    label = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onConfirm(selected.toSet()) },
                enabled = selected.isNotEmpty()
            ) { Text("Scan") }
        }
    }
}
