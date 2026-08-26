package com.bitperfect.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.library.model.Album
import com.bitperfect.android.library.model.Artist
import com.bitperfect.android.library.model.Composer
import com.bitperfect.android.library.model.Genre
import com.bitperfect.android.library.model.Track
import com.bitperfect.android.ui.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * LibraryViewModel - ViewModel for the music library browser screen.
 *
 * Responsibilities:
 * - Loads library data from Room database via MusicLibrary/DAOs
 * - Implements search filtering across all library items
 * - Provides sort options (by name, date added, track count, etc.)
 * - Exposes library items as StateFlow for reactive Compose observation
 * - Triggers library rescan on pull-to-refresh
 * - Handles tab selection and content switching
 */
class LibraryViewModel(
    private val musicLibrary: MusicLibrary,
    private val settingsRepository: SettingsRepository? = null
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
        YEAR
    }

    /**
     * UI state for the library screen.
     */
    data class LibraryUiState(
        val isLoading: Boolean = true,
        val isScanning: Boolean = false,
        val isEmpty: Boolean = false,
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
        val scanProgress: Float = 0f
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
        val artworkUri: String? = null
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
        val formatInfo: String
    )

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var allFolders: List<FolderItem> = emptyList()
    private var allArtists: List<ArtistItem> = emptyList()
    private var allAlbums: List<AlbumItem> = emptyList()
    private var allGenres: List<GenreItem> = emptyList()
    private var allComposers: List<ComposerItem> = emptyList()
    private var allTracks: List<TrackItem> = emptyList()

    init {
        loadLibrary()
        // Auto-scan on first launch when library is empty
        viewModelScope.launch {
            // Check if library is empty after initial load attempt
            if (musicLibrary.isEmpty()) {
                rescan()
            }
        }
    }

    /**
     * Select a tab to display.
     */
    fun selectTab(tab: LibraryTab) {
        _uiState.value = _uiState.value.copy(currentTab = tab)
    }

    /**
     * Perform a search across the current tab's items.
     */
    fun search(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyFilter(query)
    }

    /**
     * Cycle through sort orders.
     */
    fun cycleSortOrder() {
        val current = _uiState.value.sortOrder
        val next = when (current) {
            SortOrder.NAME_ASC -> SortOrder.NAME_DESC
            SortOrder.NAME_DESC -> SortOrder.DATE_ADDED
            SortOrder.DATE_ADDED -> SortOrder.TRACK_COUNT
            SortOrder.TRACK_COUNT -> SortOrder.YEAR
            SortOrder.YEAR -> SortOrder.NAME_ASC
        }
        _uiState.value = _uiState.value.copy(sortOrder = next)
        applySort(next)
    }

    /**
     * Trigger a library rescan (pull-to-refresh).
     * Reads scan directories from SettingsRepository or uses defaults.
     */
    fun rescan() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isScanning = true)

            // Get scan directories from settings, or use defaults
            val scanDirs = if (settingsRepository != null) {
                settingsRepository.scanDirectories.first().toList()
            } else {
                listOf(
                    "/storage/emulated/0",
                    "/storage/emulated/0/Music",
                    "/storage/emulated/0/Download"
                )
            }

            musicLibrary.triggerScan(
                directories = scanDirs,
                progressCallback = { progress ->
                    _uiState.value = _uiState.value.copy(scanProgress = progress.progressPercent)
                }
            )
            loadLibrary()
            _uiState.value = _uiState.value.copy(isScanning = false)
        }
    }

    private fun loadLibrary() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // Load all data from library using existing API
            allAlbums = musicLibrary.getAlbums().map { album ->
                AlbumItem(
                    id = album.id,
                    title = album.title,
                    artist = album.artist,
                    year = album.year,
                    trackCount = album.trackCount,
                    artworkUri = album.artworkPath
                )
            }

            allArtists = musicLibrary.getArtists().map { artist ->
                ArtistItem(
                    id = artist.id,
                    name = artist.name,
                    albumCount = artist.albumCount,
                    trackCount = artist.trackCount
                )
            }

            allTracks = musicLibrary.getAllTracks().map { track ->
                TrackItem(
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
                    formatInfo = formatTrackInfo(track.sampleRate, track.bitDepth, track.format)
                )
            }

            allGenres = musicLibrary.getGenres().map { genre ->
                GenreItem(
                    id = genre.id,
                    name = genre.name,
                    trackCount = genre.trackCount
                )
            }

            allComposers = musicLibrary.getComposers().map { composer ->
                ComposerItem(
                    id = composer.id,
                    name = composer.name,
                    trackCount = composer.trackCount
                )
            }

            // Build folder list from tracks
            val folderGroups = musicLibrary.getAllTracks().groupBy { it.folder }
            allFolders = folderGroups.map { (path, tracks) ->
                FolderItem(
                    path = path,
                    name = path.substringAfterLast('/').ifEmpty { path },
                    trackCount = tracks.size
                )
            }.sortedBy { it.name }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isEmpty = allTracks.isEmpty(),
                folders = allFolders,
                artists = allArtists,
                albums = allAlbums,
                genres = allGenres,
                composers = allComposers,
                tracks = allTracks,
                totalTracks = allTracks.size
            )
        }
    }

    private fun applyFilter(query: String) {
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                folders = allFolders,
                artists = allArtists,
                albums = allAlbums,
                genres = allGenres,
                composers = allComposers,
                tracks = allTracks
            )
            return
        }

        val lowerQuery = query.lowercase()
        _uiState.value = _uiState.value.copy(
            folders = allFolders.filter { it.name.lowercase().contains(lowerQuery) },
            artists = allArtists.filter { it.name.lowercase().contains(lowerQuery) },
            albums = allAlbums.filter {
                it.title.lowercase().contains(lowerQuery) ||
                it.artist.lowercase().contains(lowerQuery)
            },
            genres = allGenres.filter { it.name.lowercase().contains(lowerQuery) },
            composers = allComposers.filter { it.name.lowercase().contains(lowerQuery) },
            tracks = allTracks.filter {
                it.title.lowercase().contains(lowerQuery) ||
                it.artist.lowercase().contains(lowerQuery) ||
                it.album.lowercase().contains(lowerQuery)
            }
        )
    }

    private fun applySort(order: SortOrder) {
        _uiState.value = _uiState.value.copy(
            albums = when (order) {
                SortOrder.NAME_ASC -> _uiState.value.albums.sortedBy { it.title }
                SortOrder.NAME_DESC -> _uiState.value.albums.sortedByDescending { it.title }
                SortOrder.DATE_ADDED -> _uiState.value.albums // Keep current order
                SortOrder.TRACK_COUNT -> _uiState.value.albums.sortedByDescending { it.trackCount }
                SortOrder.YEAR -> _uiState.value.albums.sortedByDescending { it.year }
            },
            artists = when (order) {
                SortOrder.NAME_ASC -> _uiState.value.artists.sortedBy { it.name }
                SortOrder.NAME_DESC -> _uiState.value.artists.sortedByDescending { it.name }
                SortOrder.TRACK_COUNT -> _uiState.value.artists.sortedByDescending { it.trackCount }
                else -> _uiState.value.artists
            },
            tracks = when (order) {
                SortOrder.NAME_ASC -> _uiState.value.tracks.sortedBy { it.title }
                SortOrder.NAME_DESC -> _uiState.value.tracks.sortedByDescending { it.title }
                else -> _uiState.value.tracks
            }
        )
    }

    private fun formatTrackInfo(sampleRate: Int, bitDepth: Int, codec: String): String {
        val rateStr = if (sampleRate >= 1000) "${sampleRate / 1000}kHz" else "${sampleRate}Hz"
        return "$codec ${bitDepth}b/$rateStr"
    }
}
