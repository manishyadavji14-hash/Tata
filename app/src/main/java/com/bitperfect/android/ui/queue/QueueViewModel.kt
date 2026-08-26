package com.bitperfect.android.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.library.model.Track
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState
import com.bitperfect.android.player.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the Now Playing queue screen.
 *
 * The queue itself holds only file paths, so this resolves them against the
 * library for display. Paths that are not in the library - a file opened
 * straight from the picker, for instance - still appear, using their file name.
 */
class QueueViewModel(
    private val musicLibrary: MusicLibrary,
    private val playbackController: PlaybackController
) : ViewModel() {

    data class QueueEntry(
        val queueIndex: Int,
        val path: String,
        val title: String,
        val artist: String,
        val formatInfo: String,
        val durationMs: Long,
        val artworkUri: String?,
        val isCurrent: Boolean
    )

    data class QueueUiState(
        val isLoading: Boolean = true,
        val entries: List<QueueEntry> = emptyList(),
        val currentIndex: Int = -1,
        val isShuffleEnabled: Boolean = false,
        val repeatMode: RepeatMode = RepeatMode.OFF,
        val totalDurationMs: Long = 0,
        val statusMessage: String? = null
    ) {
        val isEmpty: Boolean get() = !isLoading && entries.isEmpty()
    }

    private val _uiState = MutableStateFlow(QueueUiState())
    val uiState: StateFlow<QueueUiState> = _uiState.asStateFlow()

    /**
     * Metadata for paths already resolved, so reordering does not re-query.
     */
    private val trackCache = mutableMapOf<String, Track?>()

    private val playbackListener: (PlaybackState) -> Unit = { refresh() }

    init {
        playbackController.addStateListener(playbackListener)
        refresh()
    }

    /**
     * Rebuild the visible queue from the controller.
     */
    fun refresh() {
        viewModelScope.launch {
            val paths = playbackController.queue.tracks
            val currentIndex = playbackController.queue.position

            // Resolve only paths not already known.
            paths.filterNot { trackCache.containsKey(it) }.forEach { path ->
                trackCache[path] = musicLibrary.getTrackByPath(path)
            }

            val entries = paths.mapIndexed { index, path ->
                val track = trackCache[path]
                QueueEntry(
                    queueIndex = index,
                    path = path,
                    title = track?.title?.takeIf { it.isNotBlank() } ?: fileNameOf(path),
                    artist = track?.artist.orEmpty(),
                    formatInfo = track?.let(::buildFormatInfo).orEmpty(),
                    durationMs = track?.duration ?: 0L,
                    artworkUri = track?.artworkPath,
                    isCurrent = index == currentIndex
                )
            }

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    entries = entries,
                    currentIndex = currentIndex,
                    isShuffleEnabled = playbackController.isShuffleEnabled(),
                    repeatMode = playbackController.getRepeatMode(),
                    totalDurationMs = entries.sumOf { it.durationMs }
                )
            }
        }
    }

    fun playAt(index: Int) {
        playbackController.playQueueIndex(index)
        refresh()
    }

    fun removeAt(index: Int) {
        playbackController.removeFromQueue(index)
        refresh()
    }

    /**
     * Move an entry, used by the reorder controls.
     */
    fun move(fromIndex: Int, toIndex: Int) {
        val lastIndex = _uiState.value.entries.lastIndex
        if (fromIndex !in 0..lastIndex || toIndex !in 0..lastIndex) return
        playbackController.moveInQueue(fromIndex, toIndex)
        refresh()
    }

    fun toggleShuffle() {
        playbackController.toggleShuffle()
        refresh()
    }

    fun cycleRepeatMode() {
        val next = when (playbackController.getRepeatMode()) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        playbackController.setRepeatMode(next)
        refresh()
    }

    fun clearQueue() {
        playbackController.clearQueue()
        trackCache.clear()
        refresh()
        showMessage("Queue cleared")
    }

    /**
     * Save the current queue as a new playlist.
     *
     * Only library tracks can be saved, because a playlist entry needs a stable
     * track id. That is reported rather than silently dropping entries.
     */
    fun saveQueueAsPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            showMessage("Enter a playlist name")
            return
        }

        viewModelScope.launch {
            val paths = _uiState.value.entries.map { it.path }
            val trackIds = musicLibrary.resolveTrackIds(paths)

            if (trackIds.isEmpty()) {
                showMessage("These tracks are not in your library yet")
                return@launch
            }

            if (musicLibrary.findPlaylistByName(trimmed) != null) {
                showMessage("A playlist called \"$trimmed\" already exists")
                return@launch
            }

            musicLibrary.createPlaylist(trimmed, trackIds)

            // A playlist holds each track once, so report both reasons the
            // saved count can be lower than the queue length.
            val savedCount = trackIds.distinct().size
            val notInLibrary = paths.size - trackIds.size
            val duplicates = trackIds.size - savedCount
            val notes = buildList {
                if (notInLibrary > 0) add("$notInLibrary not in your library")
                if (duplicates > 0) add("$duplicates repeated")
            }
            showMessage(
                if (notes.isEmpty()) {
                    "Saved \"$trimmed\""
                } else {
                    "Saved $savedCount tracks, skipped ${notes.joinToString(" and ")}"
                }
            )
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private fun fileNameOf(path: String) =
        path.substringAfterLast('/').substringBeforeLast('.')

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

    class Factory(
        private val musicLibrary: MusicLibrary,
        private val playbackController: PlaybackController
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QueueViewModel(musicLibrary, playbackController) as T
        }
    }
}
