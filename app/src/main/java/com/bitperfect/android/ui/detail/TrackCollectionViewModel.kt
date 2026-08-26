package com.bitperfect.android.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.library.model.Track
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Identifies which set of tracks a detail screen shows.
 *
 * Every case resolves to a track list plus a header, so one ViewModel serves
 * albums, artists, genres, composers, folders, favourites and playlists rather
 * than repeating the same load-and-play plumbing six times.
 */
sealed interface TrackCollection {
    data class OfAlbum(val albumId: Long) : TrackCollection
    data class OfArtist(val artistId: Long) : TrackCollection
    data class OfGenre(val name: String) : TrackCollection
    data class OfComposer(val name: String) : TrackCollection
    data class OfFolder(val path: String) : TrackCollection
    data object Favourites : TrackCollection
    data class OfPlaylist(val playlistId: Long) : TrackCollection
}

/**
 * Backs any screen that lists the tracks of one collection.
 */
class TrackCollectionViewModel(
    private val collection: TrackCollection,
    private val musicLibrary: MusicLibrary,
    private val playbackController: PlaybackController
) : ViewModel() {

    data class TrackItem(
        val id: Long,
        val path: String,
        val title: String,
        val artist: String,
        val formatInfo: String,
        val durationMs: Long,
        val trackNumber: Int,
        val discNumber: Int,
        val artworkUri: String?,
        val isFavourite: Boolean
    )

    data class DetailUiState(
        val isLoading: Boolean = true,
        val title: String = "",
        val subtitle: String = "",
        val artworkUri: String? = null,
        val tracks: List<TrackItem> = emptyList(),
        val albums: List<AlbumSummary> = emptyList(),
        val playingPath: String? = null,
        val totalDurationMs: Long = 0,
        val isMultipleDiscs: Boolean = false,
        /**
         * Whether rows show a track number instead of artwork.
         *
         * True only for an album, where every track shares one cover and the
         * number is the useful identifier. Mixed collections such as a genre,
         * folder or favourites list are far easier to scan by cover art.
         */
        val showTrackNumbers: Boolean = false,
        val statusMessage: String? = null
    ) {
        val isEmpty: Boolean get() = !isLoading && tracks.isEmpty() && albums.isEmpty()
    }

    data class AlbumSummary(
        val id: Long,
        val title: String,
        val artist: String,
        val year: Int,
        val trackCount: Int,
        val artworkUri: String?
    )

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val playbackListener: (PlaybackState) -> Unit = { state ->
        val path = when (state) {
            is PlaybackState.Playing -> state.trackPath
            is PlaybackState.Paused -> state.trackPath
            is PlaybackState.Loading -> state.trackPath
            else -> null
        }
        _uiState.update { it.copy(playingPath = path) }
    }

    init {
        playbackController.addStateListener(playbackListener)
        load()
    }

    /**
     * Reload the collection, for example after a favourite or playlist change.
     */
    fun refresh() = load()

    fun playTrackAt(index: Int) {
        val paths = _uiState.value.tracks.map { it.path }
        if (paths.isEmpty()) return
        playbackController.playQueue(paths, index)
    }

    /**
     * Play the whole collection from the start.
     */
    fun playAll() {
        val paths = _uiState.value.tracks.map { it.path }
        if (paths.isEmpty()) return
        playbackController.setShuffle(false)
        playbackController.playQueue(paths, 0)
    }

    /**
     * Shuffle the collection, starting from a random entry.
     */
    fun shuffleAll() {
        val paths = _uiState.value.tracks.map { it.path }
        if (paths.isEmpty()) return
        playbackController.playQueue(paths, paths.indices.random())
        playbackController.setShuffle(true)
    }

    fun playNext(path: String) {
        playbackController.playNext(path)
        showMessage("Playing next")
    }

    fun addToQueue(path: String) {
        playbackController.addToQueue(path)
        showMessage("Added to queue")
    }

    fun addAllToQueue() {
        val paths = _uiState.value.tracks.map { it.path }
        if (paths.isEmpty()) return
        playbackController.addAllToQueue(paths)
        showMessage("Added ${paths.size} tracks to queue")
    }

    fun toggleFavourite(trackId: Long) {
        viewModelScope.launch {
            val track = _uiState.value.tracks.firstOrNull { it.id == trackId } ?: return@launch
            musicLibrary.setFavourite(trackId, !track.isFavourite)
            load()
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    /**
     * Shows a message in this screen's snackbar.
     *
     * Public so an embedded dialog with its own ViewModel, such as
     * add-to-playlist, can surface its result here instead of dropping it.
     */
    fun showExternalMessage(message: String) = showMessage(message)

    private fun showMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val loaded = when (collection) {
                is TrackCollection.OfAlbum -> loadAlbum(collection.albumId)
                is TrackCollection.OfArtist -> loadArtist(collection.artistId)
                is TrackCollection.OfGenre -> loadSimple(
                    title = collection.name.ifBlank { "Unknown Genre" },
                    tracks = musicLibrary.getTracksByGenre(collection.name)
                )
                is TrackCollection.OfComposer -> loadSimple(
                    title = collection.name.ifBlank { "Unknown Composer" },
                    tracks = musicLibrary.getTracksByComposer(collection.name)
                )
                is TrackCollection.OfFolder -> loadSimple(
                    title = collection.path.substringAfterLast('/').ifBlank { collection.path },
                    tracks = musicLibrary.getTracksByFolder(collection.path),
                    subtitle = collection.path
                )
                TrackCollection.Favourites -> loadSimple(
                    title = "Favourites",
                    tracks = musicLibrary.getFavourites()
                )
                is TrackCollection.OfPlaylist -> loadPlaylist(collection.playlistId)
            }

            _uiState.update { current ->
                loaded.copy(
                    isLoading = false,
                    playingPath = current.playingPath,
                    showTrackNumbers = collection is TrackCollection.OfAlbum,
                    statusMessage = current.statusMessage
                )
            }
        }
    }

    private suspend fun loadAlbum(albumId: Long): DetailUiState {
        val album = musicLibrary.getAlbumById(albumId)
        val tracks = musicLibrary.getTracksByAlbum(albumId)
        val items = tracks.map(::toItem)

        return DetailUiState(
            title = album?.title ?: "Album",
            subtitle = buildString {
                append(album?.displayArtist ?: "")
                if (album != null && album.year > 0) append(" · ${album.year}")
                append(" · ${items.size} tracks")
            }.trim(' ', '·'),
            artworkUri = album?.artworkPath ?: items.firstNotNullOfOrNull { it.artworkUri },
            tracks = items,
            totalDurationMs = items.sumOf { it.durationMs },
            isMultipleDiscs = items.map { it.discNumber }.distinct().size > 1
        )
    }

    private suspend fun loadArtist(artistId: Long): DetailUiState {
        val artist = musicLibrary.getArtistById(artistId)
        val name = artist?.name ?: return DetailUiState(title = "Artist")

        val albums = musicLibrary.getAlbumsByArtist(name)
        val tracks = musicLibrary.getTracksByArtist(name)
        val items = tracks.map(::toItem)

        return DetailUiState(
            title = artist.displayName,
            subtitle = "${albums.size} albums · ${items.size} tracks",
            artworkUri = albums.firstNotNullOfOrNull { it.artworkPath }
                ?: items.firstNotNullOfOrNull { it.artworkUri },
            tracks = items,
            albums = albums.map { album ->
                AlbumSummary(
                    id = album.id,
                    title = album.title,
                    artist = album.displayArtist,
                    year = album.year,
                    trackCount = album.trackCount,
                    artworkUri = album.artworkPath
                )
            },
            totalDurationMs = items.sumOf { it.durationMs }
        )
    }

    private suspend fun loadPlaylist(playlistId: Long): DetailUiState {
        val playlist = musicLibrary.getPlaylists().firstOrNull { it.id == playlistId }
        val tracks = musicLibrary.getPlaylistTracks(playlistId)
        val items = tracks.map(::toItem)

        return DetailUiState(
            title = playlist?.name ?: "Playlist",
            subtitle = "${items.size} tracks",
            artworkUri = items.firstNotNullOfOrNull { it.artworkUri },
            tracks = items,
            totalDurationMs = items.sumOf { it.durationMs }
        )
    }

    private fun loadSimple(
        title: String,
        tracks: List<Track>,
        subtitle: String? = null
    ): DetailUiState {
        val items = tracks.map(::toItem)
        return DetailUiState(
            title = title,
            subtitle = subtitle ?: "${items.size} tracks",
            artworkUri = items.firstNotNullOfOrNull { it.artworkUri },
            tracks = items,
            totalDurationMs = items.sumOf { it.durationMs }
        )
    }

    private fun toItem(track: Track) = TrackItem(
        id = track.id,
        path = track.path,
        title = track.title,
        artist = track.artist,
        formatInfo = buildFormatInfo(track),
        durationMs = track.duration,
        trackNumber = track.trackNumber,
        discNumber = track.discNumber,
        artworkUri = track.artworkPath,
        isFavourite = track.isFavourite
    )

    /**
     * Exact format for display, omitting parts the platform did not report.
     */
    private fun buildFormatInfo(track: Track): String {
        val parts = mutableListOf<String>()
        if (track.format.isNotEmpty()) parts.add(track.format)
        if (track.bitDepth > 0) parts.add("${track.bitDepth}bit")
        if (track.sampleRate > 0) {
            val khz = track.sampleRate / 1000.0
            parts.add(if (khz % 1.0 == 0.0) "${khz.toInt()}kHz" else "%.1fkHz".format(khz))
        }
        return parts.joinToString(" · ")
    }

    override fun onCleared() {
        playbackController.removeStateListener(playbackListener)
        super.onCleared()
    }

    /**
     * Factory so each navigation destination gets a ViewModel scoped to its own
     * back stack entry, which keeps the collection argument immutable.
     */
    class Factory(
        private val collection: TrackCollection,
        private val musicLibrary: MusicLibrary,
        private val playbackController: PlaybackController
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TrackCollectionViewModel(
                collection,
                musicLibrary,
                playbackController
            ) as T
        }
    }
}
