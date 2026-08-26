package com.bitperfect.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.library.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LibraryViewModel - ViewModel for the music library browser screen.
 *
 * Responsibilities:
 * - Loads persisted library data through MusicLibrary
 * - Requests and reflects the audio read permission state
 * - Lets the user choose which folders a scan covers
 * - Applies search filtering and sorting as one pipeline over the full data
 * - Exposes library items as StateFlow for Compose observation
 */
class LibraryViewModel(
    private val musicLibrary: MusicLibrary
) : ViewModel() {

    /**
     * Library browser tabs.
     */
    enum class LibraryTab {
        FOLDERS,
        ARTISTS,
        ALBUMS,
        GENRES,
        COMPOSERS,
        TRACKS
    }

    /**
     * Sort options for library items.
     */
    enum class SortOrder {
        NAME_ASC,
        NAME_DESC,
        DATE_ADDED,
        TRACK_COUNT,
        YEAR;

        val label: String
            get() = when (this) {
                NAME_ASC -> "Name A-Z"
                NAME_DESC -> "Name Z-A"
                DATE_ADDED -> "Recently added"
                TRACK_COUNT -> "Track count"
                YEAR -> "Year"
            }
    }

    /**
     * UI state for the library screen.
     */
    data class LibraryUiState(
        val isLoading: Boolean = true,
        val isScanning: Boolean = false,
        val isEmpty: Boolean = false,
        val hasAudioPermission: Boolean = true,
        val currentTab: LibraryTab = LibraryTab.ALBUMS,
        val sortOrder: SortOrder = SortOrder.NAME_ASC,
        val searchQuery: String = "",
        val folders: List<FolderItem> = emptyList(),
        val artists: List<ArtistItem> = emptyList(),
        val albums: List<AlbumItem> = emptyList(),
        val genres: List<GenreItem> = emptyList(),
        val composers: List<ComposerItem> = emptyList(),
        val tracks: List<TrackItem> = emptyList(),
        val totalTracks: Int = 0,
        val scanProgress: Float = 0f,
        val scanStatus: String = "",
        /**
         * Set when a scan request was declined, so a pull-to-refresh gesture can
         * be released instead of spinning forever.
         */
        val scanRequestRejected: Boolean = false,
        val statusMessage: String? = null,
        val availableFolders: List<SelectableFolder> = emptyList(),
        val isFolderPickerVisible: Boolean = false
    )

    // Data items for UI
    data class FolderItem(
        val path: String,
        val name: String,
        val trackCount: Int
    )

    data class ArtistItem(
        val id: Long,
        val name: String,
        val albumCount: Int,
        val trackCount: Int
    )

    data class AlbumItem(
        val id: Long,
        val title: String,
        val artist: String,
        val year: Int,
        val trackCount: Int,
        val artworkUri: String? = null,
        val dateAdded: Long = 0L
    )

    data class GenreItem(
        val id: Long,
        val name: String,
        val trackCount: Int
    )

    data class ComposerItem(
        val id: Long,
        val name: String,
        val trackCount: Int
    )

    data class TrackItem(
        val id: Long,
        val path: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
        val codec: String,
        val formatInfo: String,
        val artworkUri: String? = null,
        val trackNumber: Int = 0,
        val dateAdded: Long = 0L
    )

    /**
     * A device folder that can be included in a scan.
     */
    data class SelectableFolder(
        val path: String,
        val name: String,
        val trackCount: Int,
        val isSelected: Boolean
    )

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Unfiltered, unsorted source data. Filter and sort always derive from
    // these, so neither operation can destroy data the other needs.
    private var allFolders: List<FolderItem> = emptyList()
    private var allArtists: List<ArtistItem> = emptyList()
    private var allAlbums: List<AlbumItem> = emptyList()
    private var allGenres: List<GenreItem> = emptyList()
    private var allComposers: List<ComposerItem> = emptyList()
    private var allTracks: List<TrackItem> = emptyList()

    /** Empty means "scan everything". */
    private var selectedFolderPaths: Set<String> = emptySet()

    private var scanJob: Job? = null

    init {
        loadLibrary()
    }

    /**
     * Report the current audio read permission state from the host activity.
     */
    fun setAudioPermissionGranted(granted: Boolean) {
        val previous = _uiState.value.hasAudioPermission
        _uiState.update { it.copy(hasAudioPermission = granted) }
        if (granted && !previous) {
            // Permission was just granted; the library is worth scanning now.
            rescan()
        }
    }

    fun selectTab(tab: LibraryTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        refreshVisibleItems()
    }

    fun cycleSortOrder() {
        val next = when (_uiState.value.sortOrder) {
            SortOrder.NAME_ASC -> SortOrder.NAME_DESC
            SortOrder.NAME_DESC -> SortOrder.DATE_ADDED
            SortOrder.DATE_ADDED -> SortOrder.TRACK_COUNT
            SortOrder.TRACK_COUNT -> SortOrder.YEAR
            SortOrder.YEAR -> SortOrder.NAME_ASC
        }
        setSortOrder(next)
    }

    fun setSortOrder(order: SortOrder) {
        _uiState.update { it.copy(sortOrder = order) }
        refreshVisibleItems()
    }

    fun dismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    /**
     * Acknowledge a declined scan so the refresh gesture can be released.
     */
    fun acknowledgeScanRejection() {
        _uiState.update { it.copy(scanRequestRejected = false) }
    }

    // --- Folder selection ---

    /**
     * Load the folders on the device that contain audio and show the picker.
     */
    fun showFolderPicker() {
        viewModelScope.launch {
            if (!_uiState.value.hasAudioPermission) {
                _uiState.update {
                    it.copy(statusMessage = "Allow music access first, then choose folders")
                }
                return@launch
            }

            _uiState.update { it.copy(isFolderPickerVisible = true) }
            val discovered = musicLibrary.discoverAvailableFolders()
            val selectable = discovered.map { folder ->
                SelectableFolder(
                    path = folder.path,
                    name = folder.name,
                    trackCount = folder.trackCount,
                    isSelected = selectedFolderPaths.isEmpty() ||
                        folder.path in selectedFolderPaths
                )
            }
            _uiState.update { it.copy(availableFolders = selectable) }
        }
    }

    fun hideFolderPicker() {
        _uiState.update { it.copy(isFolderPickerVisible = false) }
    }

    fun toggleFolderSelection(path: String) {
        _uiState.update { state ->
            state.copy(
                availableFolders = state.availableFolders.map { folder ->
                    if (folder.path == path) {
                        folder.copy(isSelected = !folder.isSelected)
                    } else {
                        folder
                    }
                }
            )
        }
    }

    fun selectAllFolders(selected: Boolean) {
        _uiState.update { state ->
            state.copy(
                availableFolders = state.availableFolders.map { it.copy(isSelected = selected) }
            )
        }
    }

    /**
     * Apply the folder picker choice and rescan.
     */
    fun confirmFolderSelection() {
        val chosen = _uiState.value.availableFolders.filter { it.isSelected }
        if (chosen.isEmpty()) {
            _uiState.update {
                it.copy(statusMessage = "Select at least one folder to scan")
            }
            return
        }

        // All folders selected is equivalent to scanning everything.
        selectedFolderPaths = if (chosen.size == _uiState.value.availableFolders.size) {
            emptySet()
        } else {
            chosen.mapTo(mutableSetOf()) { it.path }
        }

        hideFolderPicker()
        rescan()
    }

    /**
     * Rescan the library, honouring the current folder selection.
     */
    fun rescan() {
        if (scanJob?.isActive == true) {
            _uiState.update { it.copy(scanRequestRejected = true) }
            return
        }

        if (!_uiState.value.hasAudioPermission) {
            _uiState.update {
                it.copy(
                    isScanning = false,
                    scanRequestRejected = true,
                    statusMessage = "Allow music access to scan for tracks"
                )
            }
            return
        }

        scanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    scanRequestRejected = false,
                    scanProgress = 0f,
                    scanStatus = "Looking for music…",
                    statusMessage = null
                )
            }

            val result = musicLibrary.triggerScan(
                directories = selectedFolderPaths.toList(),
                progressCallback = { progress ->
                    // Invoked from the scanner's IO thread, so this must be
                    // an atomic update rather than a read-modify-write.
                    _uiState.update { state ->
                        state.copy(
                            scanProgress = progress.progressPercent,
                            scanStatus = if (progress.filesFound > 0) {
                                "Reading ${progress.filesProcessed} of ${progress.filesFound}"
                            } else {
                                "Looking for music…"
                            }
                        )
                    }
                }
            )

            loadLibrary()

            _uiState.update { state ->
                state.copy(
                    isScanning = false,
                    scanProgress = 0f,
                    scanStatus = "",
                    statusMessage = when {
                        !result.success -> result.error ?: "Scan failed"
                        result.totalTracks == 0 -> "No supported audio files found"
                        else -> "Found ${result.totalTracks} tracks"
                    }
                )
            }
        }
    }

    fun cancelScan() {
        musicLibrary.cancelScan()
    }

    // --- Loading ---

    private fun loadLibrary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Recover albums if a schema upgrade dropped them. This is a no-op
            // in the normal case and needs no permission, since albums are
            // derived from the tracks already in the database.
            musicLibrary.ensureAggregates()

            // Mapping a large library is CPU work, so it stays off the main
            // dispatcher. Only the resulting state write happens on Main.
            val snapshot = withContext(Dispatchers.Default) { buildSnapshot() }

            allAlbums = snapshot.albums
            allArtists = snapshot.artists
            allTracks = snapshot.tracks
            allGenres = snapshot.genres
            allComposers = snapshot.composers
            allFolders = snapshot.folders

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    isEmpty = allTracks.isEmpty(),
                    totalTracks = allTracks.size
                )
            }
            refreshVisibleItems()
        }
    }

    /**
     * Everything the browser needs, mapped in one pass.
     */
    private data class LibrarySnapshot(
        val albums: List<AlbumItem>,
        val artists: List<ArtistItem>,
        val tracks: List<TrackItem>,
        val genres: List<GenreItem>,
        val composers: List<ComposerItem>,
        val folders: List<FolderItem>
    )

    private suspend fun buildSnapshot(): LibrarySnapshot {
        val tracks = musicLibrary.getAllTracks()

        // One grouping pass instead of scanning all tracks per album.
        val newestByAlbum = tracks
            .groupingBy { it.albumId }
            .fold(0L) { newest, track -> maxOf(newest, track.lastModified) }

        return LibrarySnapshot(
            albums = musicLibrary.getAlbums().map { album ->
                AlbumItem(
                    id = album.id,
                    title = album.title,
                    // displayArtist, not artist: `artist` is deliberately left
                    // blank for multi-artist albums to mark them as
                    // compilations, and it falls back to the album artist.
                    artist = album.displayArtist,
                    year = album.year,
                    trackCount = album.trackCount,
                    artworkUri = album.artworkPath,
                    dateAdded = newestByAlbum[album.id] ?: 0L
                )
            },
            artists = musicLibrary.getArtists().map { artist ->
                ArtistItem(
                    id = artist.id,
                    name = artist.name,
                    albumCount = artist.albumCount,
                    trackCount = artist.trackCount
                )
            },
            tracks = tracks.map(::toTrackItem),
            genres = musicLibrary.getGenres().map { genre ->
                GenreItem(id = genre.id, name = genre.name, trackCount = genre.trackCount)
            },
            composers = musicLibrary.getComposers().map { composer ->
                ComposerItem(
                    id = composer.id,
                    name = composer.name,
                    trackCount = composer.trackCount
                )
            },
            folders = musicLibrary.getFolderSummaries().map { folder ->
                FolderItem(
                    path = folder.path,
                    name = folder.name,
                    trackCount = folder.trackCount
                )
            }
        )
    }

    private fun toTrackItem(track: Track) = TrackItem(
        id = track.id,
        path = track.path,
        title = track.title,
        artist = track.artist,
        album = track.albumTitle,
        durationMs = track.duration,
        sampleRate = track.sampleRate,
        bitDepth = track.bitDepth,
        channels = track.channels,
        codec = track.format,
        formatInfo = formatTrackInfo(track.sampleRate, track.bitDepth, track.format),
        artworkUri = track.artworkPath,
        trackNumber = track.trackNumber,
        dateAdded = track.lastModified
    )

    /**
     * Apply search filtering and sorting together, always starting from the
     * full source lists so the two operations cannot interfere.
     */
    private fun refreshVisibleItems() {
        val state = _uiState.value
        val query = state.searchQuery.trim().lowercase()
        val order = state.sortOrder

        val folders = allFolders
            .filter { query.isEmpty() || it.name.lowercase().contains(query) }
            .let { items ->
                when (order) {
                    SortOrder.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }
                    SortOrder.TRACK_COUNT -> items.sortedByDescending { it.trackCount }
                    else -> items.sortedBy { it.name.lowercase() }
                }
            }

        val artists = allArtists
            .filter { query.isEmpty() || it.name.lowercase().contains(query) }
            .let { items ->
                when (order) {
                    SortOrder.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }
                    SortOrder.TRACK_COUNT -> items.sortedByDescending { it.trackCount }
                    else -> items.sortedBy { it.name.lowercase() }
                }
            }

        val albums = allAlbums
            .filter {
                query.isEmpty() ||
                    it.title.lowercase().contains(query) ||
                    it.artist.lowercase().contains(query)
            }
            .let { items ->
                when (order) {
                    SortOrder.NAME_DESC -> items.sortedByDescending { it.title.lowercase() }
                    SortOrder.DATE_ADDED -> items.sortedByDescending { it.dateAdded }
                    SortOrder.TRACK_COUNT -> items.sortedByDescending { it.trackCount }
                    SortOrder.YEAR -> items.sortedByDescending { it.year }
                    SortOrder.NAME_ASC -> items.sortedBy { it.title.lowercase() }
                }
            }

        val genres = allGenres
            .filter { query.isEmpty() || it.name.lowercase().contains(query) }
            .let { items ->
                when (order) {
                    SortOrder.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }
                    SortOrder.TRACK_COUNT -> items.sortedByDescending { it.trackCount }
                    else -> items.sortedBy { it.name.lowercase() }
                }
            }

        val composers = allComposers
            .filter { query.isEmpty() || it.name.lowercase().contains(query) }
            .let { items ->
                when (order) {
                    SortOrder.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }
                    SortOrder.TRACK_COUNT -> items.sortedByDescending { it.trackCount }
                    else -> items.sortedBy { it.name.lowercase() }
                }
            }

        val tracks = allTracks
            .filter {
                query.isEmpty() ||
                    it.title.lowercase().contains(query) ||
                    it.artist.lowercase().contains(query) ||
                    it.album.lowercase().contains(query)
            }
            .let { items ->
                when (order) {
                    SortOrder.NAME_DESC -> items.sortedByDescending { it.title.lowercase() }
                    SortOrder.DATE_ADDED -> items.sortedByDescending { it.dateAdded }
                    // Within an album, disc/track order is the meaningful order.
                    SortOrder.TRACK_COUNT -> items.sortedWith(
                        compareBy({ it.album.lowercase() }, { it.trackNumber })
                    )
                    SortOrder.YEAR -> items.sortedWith(
                        compareBy({ it.album.lowercase() }, { it.trackNumber })
                    )
                    SortOrder.NAME_ASC -> items.sortedBy { it.title.lowercase() }
                }
            }

        _uiState.update { state ->
            state.copy(
                folders = folders,
                artists = artists,
                albums = albums,
                genres = genres,
                composers = composers,
                tracks = tracks
            )
        }
    }

    private fun formatTrackInfo(sampleRate: Int, bitDepth: Int, codec: String): String {
        if (sampleRate <= 0 && bitDepth <= 0) return codec
        val rateText = if (sampleRate >= 1000) {
            val khz = sampleRate / 1000.0
            if (khz % 1.0 == 0.0) "${khz.toInt()}kHz" else "%.1fkHz".format(khz)
        } else if (sampleRate > 0) {
            "${sampleRate}Hz"
        } else {
            ""
        }
        val depthText = if (bitDepth > 0) "${bitDepth}bit" else ""
        return listOf(codec, depthText, rateText)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    }
}
