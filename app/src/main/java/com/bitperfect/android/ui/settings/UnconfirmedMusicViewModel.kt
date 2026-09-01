package com.bitperfect.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.library.MusicLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Review screen for files the scanner judged probably-not-music.
 *
 * They are quarantined, not deleted, so the only actions here are selecting
 * entries and moving them into the main library.
 */
class UnconfirmedMusicViewModel(
    private val musicLibrary: MusicLibrary
) : ViewModel() {

    data class Entry(
        val id: Long,
        val title: String,
        val folder: String,
        val format: String,
        val durationMs: Long
    )

    data class UiState(
        val isLoading: Boolean = true,
        val entries: List<Entry> = emptyList(),
        /** Ids ticked by the user. Kept separate so a reload cannot lose it. */
        val selectedIds: Set<Long> = emptySet(),
        val statusMessage: String? = null
    ) {
        val hasSelection: Boolean get() = selectedIds.isNotEmpty()
        val isEmpty: Boolean get() = entries.isEmpty()
        val allSelected: Boolean
            get() = entries.isNotEmpty() && selectedIds.size == entries.size
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val tracks = musicLibrary.getUnconfirmedTracks()
            _uiState.update { current ->
                val entries = tracks.map { track ->
                    Entry(
                        id = track.id,
                        title = track.title,
                        folder = track.folder,
                        format = track.format,
                        durationMs = track.duration
                    )
                }
                current.copy(
                    isLoading = false,
                    entries = entries,
                    // Drop selections for rows that are no longer here.
                    selectedIds = current.selectedIds.intersect(entries.map { it.id }.toSet())
                )
            }
        }
    }

    fun toggle(id: Long) {
        _uiState.update { current ->
            current.copy(
                selectedIds = if (id in current.selectedIds) {
                    current.selectedIds - id
                } else {
                    current.selectedIds + id
                }
            )
        }
    }

    fun setAllSelected(selected: Boolean) {
        _uiState.update { current ->
            current.copy(
                selectedIds = if (selected) current.entries.mapTo(mutableSetOf()) { it.id }
                else emptySet()
            )
        }
    }

    /**
     * Move the ticked entries into the main library, then reload so they leave
     * this list.
     */
    fun moveSelectedToLibrary() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            musicLibrary.confirmTracks(ids)
            _uiState.update {
                it.copy(
                    selectedIds = emptySet(),
                    statusMessage = if (ids.size == 1) {
                        "Moved 1 track to the library"
                    } else {
                        "Moved ${ids.size} tracks to the library"
                    }
                )
            }
            load()
        }
    }

    fun dismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    class Factory(private val musicLibrary: MusicLibrary) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UnconfirmedMusicViewModel(musicLibrary) as T
    }
}
