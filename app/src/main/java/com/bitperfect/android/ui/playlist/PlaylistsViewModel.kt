package com.bitperfect.android.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.player.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the playlist list screen and the "add to playlist" picker.
 *
 * Playlists are the one part of the library that is authored rather than
 * scanned, so they are never rebuilt from disk and every edit is persisted
 * immediately.
 */
class PlaylistsViewModel(
    private val musicLibrary: MusicLibrary,
    private val playbackController: PlaybackController
) : ViewModel() {

    data class PlaylistItem(
        val id: Long,
        val name: String,
        val trackCount: Int,
        val artworkUri: String?,
        val totalDurationMs: Long
    )

    data class PlaylistsUiState(
        val isLoading: Boolean = true,
        val playlists: List<PlaylistItem> = emptyList(),
        val statusMessage: String? = null
    ) {
        val isEmpty: Boolean get() = !isLoading && playlists.isEmpty()
    }

    private val _uiState = MutableStateFlow(PlaylistsUiState())
    val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun refresh() = load()

    /**
     * Create a playlist, optionally seeding it with tracks.
     *
     * Names are not unique in the schema, but reusing an existing name is
     * almost always a mistake, so it is reported rather than silently creating
     * a duplicate.
     */
    fun createPlaylist(
        name: String,
        seedTrackPaths: List<String> = emptyList(),
        onResult: (String) -> Unit = {}
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            report("Enter a playlist name", onResult)
            return
        }

        viewModelScope.launch {
            if (musicLibrary.findPlaylistByName(trimmed) != null) {
                report("A playlist called \"$trimmed\" already exists", onResult)
                return@launch
            }

            val trackIds = musicLibrary.resolveTrackIds(seedTrackPaths)
            musicLibrary.createPlaylist(trimmed, trackIds)
            load()
            report(
                if (trackIds.isEmpty()) {
                    "Created \"$trimmed\""
                } else {
                    "Created \"$trimmed\" with ${trackIds.size} tracks"
                },
                onResult
            )
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            showMessage("Enter a playlist name")
            return
        }

        viewModelScope.launch {
            musicLibrary.renamePlaylist(playlistId, trimmed)
            load()
            showMessage("Renamed to \"$trimmed\"")
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            val name = _uiState.value.playlists.firstOrNull { it.id == playlistId }?.name
            musicLibrary.deletePlaylist(playlistId)
            load()
            showMessage(name?.let { "Deleted \"$it\"" } ?: "Playlist deleted")
        }
    }

    /**
     * Add a track to an existing playlist, used by the picker sheet.
     */
    /**
     * Adds a track to a playlist.
     *
     * @param onResult receives the outcome text. Hosts that do not display this
     *   ViewModel's own state - the album and artist screens embed the
     *   add-to-playlist dialog but show their own snackbar - would otherwise
     *   drop every message, including the two failure cases.
     */
    fun addTrackToPlaylist(
        playlistId: Long,
        trackPath: String,
        onResult: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val trackIds = musicLibrary.resolveTrackIds(listOf(trackPath))
            if (trackIds.isEmpty()) {
                // Files played straight from the picker are not in the library,
                // so there is no stable id to reference from a playlist.
                report("Add this file to your library first", onResult)
                return@launch
            }

            val added = musicLibrary.addTracksToPlaylist(playlistId, trackIds)
            load()
            report(if (added > 0) "Added to playlist" else "Already in playlist", onResult)
        }
    }

    fun playPlaylist(playlistId: Long) {
        viewModelScope.launch {
            val paths = musicLibrary.getPlaylistTracks(playlistId).map { it.path }
            if (paths.isEmpty()) {
                showMessage("This playlist is empty")
                return@launch
            }
            playbackController.setShuffle(false)
            playbackController.playQueue(paths, 0)
        }
    }

    fun shufflePlaylist(playlistId: Long) {
        viewModelScope.launch {
            val paths = musicLibrary.getPlaylistTracks(playlistId).map { it.path }
            if (paths.isEmpty()) {
                showMessage("This playlist is empty")
                return@launch
            }
            playbackController.playQueue(paths, paths.indices.random())
            playbackController.setShuffle(true)
        }
    }

    /**
     * Remove a track from a playlist.
     *
     * @param onRemoved Invoked after the change is persisted, so a detail screen
     *   showing this playlist can reload.
     */
    fun removeTrackFromPlaylist(
        playlistId: Long,
        trackId: Long,
        onRemoved: () -> Unit = {}
    ) {
        viewModelScope.launch {
            musicLibrary.removeTrackFromPlaylist(playlistId, trackId)
            load()
            onRemoved()
            showMessage("Removed from playlist")
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    /** Publishes a message to this ViewModel's own state and to the caller. */
    private fun report(message: String, onResult: (String) -> Unit) {
        showMessage(message)
        onResult(message)
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val items = musicLibrary.getPlaylists().map { playlist ->
                val tracks = musicLibrary.getPlaylistTracks(playlist.id)
                PlaylistItem(
                    id = playlist.id,
                    name = playlist.name,
                    trackCount = tracks.size,
                    artworkUri = tracks.firstNotNullOfOrNull { it.artworkPath },
                    totalDurationMs = tracks.sumOf { it.duration }
                )
            }

            _uiState.update { it.copy(isLoading = false, playlists = items) }
        }
    }

    class Factory(
        private val musicLibrary: MusicLibrary,
        private val playbackController: PlaybackController
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlaylistsViewModel(musicLibrary, playbackController) as T
        }
    }
}
