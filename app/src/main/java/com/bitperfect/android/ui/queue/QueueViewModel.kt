package com.bitperfect.android.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * QueueViewModel - ViewModel for the queue management screen.
 *
 * Responsibilities:
 * - Observes PlayQueue state from PlaybackController
 * - Exposes queue items as StateFlow for Compose observation
 * - Handles user interactions: jump to track, remove, reorder
 * - Updates automatically when tracks change or playback advances
 */
class QueueViewModel(
    private val playbackController: PlaybackController
) : ViewModel() {

    /**
     * A single item in the queue display list.
     */
    data class QueueItem(
        val index: Int,
        val title: String,
        val path: String,
        val isCurrent: Boolean
    )

    /**
     * UI state for the queue screen.
     */
    data class QueueUiState(
        val tracks: List<QueueItem> = emptyList(),
        val currentIndex: Int = -1,
        val totalTracks: Int = 0,
        val isPlaying: Boolean = false
    )

    private val _uiState = MutableStateFlow(QueueUiState())
    val uiState: StateFlow<QueueUiState> = _uiState.asStateFlow()

    // Store the listener reference so we can properly unregister it
    private val stateListener: (PlaybackState) -> Unit = { _ ->
        updateQueueState()
    }

    init {
        // Listen for playback state changes to update queue display
        playbackController.addStateListener(stateListener)

        // Initial state
        updateQueueState()

        // Periodic refresh to catch queue changes not triggered by state events
        viewModelScope.launch {
            while (isActive) {
                updateQueueState()
                delay(500L)
            }
        }
    }

    /**
     * Jump to a specific track in the queue.
     */
    fun jumpToTrack(index: Int) {
        playbackController.jumpToQueueIndex(index)
    }

    /**
     * Remove a track from the queue at the given index.
     */
    fun removeTrack(index: Int) {
        playbackController.removeFromQueue(index)
        updateQueueState()
    }

    /**
     * Move a track from one position to another (drag-to-reorder).
     */
    fun moveTrack(fromIndex: Int, toIndex: Int) {
        playbackController.moveInQueue(fromIndex, toIndex)
        updateQueueState()
    }

    /**
     * Refresh the queue state from PlaybackController.
     */
    private fun updateQueueState() {
        val queue = playbackController.queue
        val currentPosition = queue.position
        val tracks = queue.tracks

        val queueItems = tracks.mapIndexed { index, path ->
            QueueItem(
                index = index,
                title = extractTitle(path),
                path = path,
                isCurrent = index == currentPosition
            )
        }

        val isPlaying = playbackController.state is PlaybackState.Playing

        _uiState.value = QueueUiState(
            tracks = queueItems,
            currentIndex = currentPosition,
            totalTracks = tracks.size,
            isPlaying = isPlaying
        )
    }

    /**
     * Extract a display title from a file path.
     * Uses the filename without extension.
     */
    private fun extractTitle(path: String): String {
        return path.substringAfterLast('/').substringBeforeLast('.')
    }

    override fun onCleared() {
        super.onCleared()
        playbackController.removeStateListener(stateListener)
    }
}
