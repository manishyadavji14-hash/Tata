package com.bitperfect.android.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.BitPerfectApp
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.library.model.Track
import com.bitperfect.android.ui.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val musicLibrary: MusicLibrary,
    /**
     * Persists the folder-picker choice. Optional so the ViewModel stays
     * constructible in a test without a DataStore; a null repository just means
     * the selection lives for the session only.
     */
    private val settingsRepository: SettingsRepository? = null
) : ViewModel() {

    internal companion object {
        /** Anything above Red Book counts as high resolution. */
        private const val CD_SAMPLE_RATE_HZ = 48_000
        private const val CD_BIT_DEPTH = 16
        private val DSD_CODECS = setOf("DSF", "DFF")

        /**
         * Order the track list.
         *
         * Pulled out of the ViewModel body and given no dependencies so it can be
         * unit tested directly — the "most played" rule in particular is a claim
         * about ranking that is worth proving rather than eyeballing.
         *
         * Every order breaks ties by title, so the list has one definite order
         * rather than depending on whatever the database returned. Without that,
         * two tracks played the same amount could swap places between visits.
         */
        internal fun sortTracks(items: List<TrackItem>, order: SortOrder): List<TrackItem> {
            val byTitle = compareBy<TrackItem> { it.title.lowercase() }

            return when (order) {
                SortOrder.NAME_ASC -> items.sortedWith(byTitle)
                SortOrder.NAME_DESC -> items.sortedWith(
                    compareByDescending<TrackItem> { it.title.lowercase() }
                )
                SortOrder.DATE_ADDED_NEWEST -> items.sortedWith(
                    compareByDescending<TrackItem> { it.dateAdded }.then(byTitle)
                )
                SortOrder.DATE_ADDED_OLDEST -> items.sortedWith(
                    compareBy<TrackItem> { it.dateAdded }.then(byTitle)
                )
                // Group by container, then read naturally within each group. The
                // codec is compared case-insensitively so "flac" and "FLAC" from
                // different scan paths do not split into two groups.
                SortOrder.FORMAT -> items.sortedWith(
                    compareBy<TrackItem> { it.codec.lowercase() }.then(byTitle)
                )
                // The share of the track actually listened to, summed over every
                // play, so it can exceed 100% — a track heard twice outranks one
                // heard once. See Track.playedMs.
                SortOrder.MOST_PLAYED -> items.sortedWith(
                    compareByDescending<TrackItem> { it.playedPercent }
                        .thenByDescending { it.playedMs }
                        .then(byTitle)
                )
                // Neither applies to a flat track list; album/disc order is the
                // meaningful reading order there.
                SortOrder.TRACK_COUNT, SortOrder.YEAR -> items.sortedWith(
                    compareBy({ it.album.lowercase() }, { it.trackNumber })
                )
            }
        }
    }

    /**
     * Library browser tabs.
     */
    enum class LibraryTab {
        // Tracks first: it is the most direct way to reach a song and the one
        // people reach for most, so it opens the library.
        TRACKS,
        ALBUMS,
        ARTISTS,
        FOLDERS,
        GENRES,
        COMPOSERS
    }

    /**
     * Sort options for library items.
     *
     * Not every order means something on every tab — "format" is meaningless for
     * a list of artists — so each one declares where it applies and the sort menu
     * only offers those. Picking an order that does not apply is not an error; it
     * falls back to name order rather than showing an unsorted list.
     */
    enum class SortOrder {
        NAME_ASC,
        NAME_DESC,
        DATE_ADDED_NEWEST,
        DATE_ADDED_OLDEST,
        FORMAT,
        MOST_PLAYED,
        TRACK_COUNT,
        YEAR;

        val label: String
            get() = when (this) {
                NAME_ASC -> "Name A-Z"
                NAME_DESC -> "Name Z-A"
                DATE_ADDED_NEWEST -> "Date added — newest"
                DATE_ADDED_OLDEST -> "Date added — oldest"
                FORMAT -> "Format"
                MOST_PLAYED -> "Most played"
                TRACK_COUNT -> "Track count"
                YEAR -> "Year"
            }

        /** Whether this order changes anything on [tab]. */
        fun appliesTo(tab: LibraryTab): Boolean = when (this) {
            NAME_ASC, NAME_DESC -> true
            // Tracks carry their own added date; albums derive one from their
            // newest track. The name-only tabs have nothing to date.
            DATE_ADDED_NEWEST, DATE_ADDED_OLDEST ->
                tab == LibraryTab.TRACKS || tab == LibraryTab.ALBUMS
            // Both are per-file facts, so only the track list can use them.
            FORMAT, MOST_PLAYED -> tab == LibraryTab.TRACKS
            TRACK_COUNT -> tab != LibraryTab.TRACKS
            YEAR -> tab == LibraryTab.ALBUMS
        }

        companion object {
            /** Orders worth offering for [tab], in menu order. */
            fun optionsFor(tab: LibraryTab): List<SortOrder> =
                entries.filter { it.appliesTo(tab) }
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
        val currentTab: LibraryTab = LibraryTab.TRACKS,
        val sortOrder: SortOrder = SortOrder.NAME_ASC,
        val searchQuery: String = "",
        val folders: List<FolderItem> = emptyList(),
        val artists: List<ArtistItem> = emptyList(),
        val albums: List<AlbumItem> = emptyList(),
        val genres: List<GenreItem> = emptyList(),
        val composers: List<ComposerItem> = emptyList(),
        val tracks: List<TrackItem> = emptyList(),
        /**
         * Library-wide totals for the stats header.
         *
         * These count the whole library, not the visible list, so searching or
         * switching tabs does not change them.
         */
        val totalTracks: Int = 0,
        val totalAlbums: Int = 0,
        val totalArtists: Int = 0,
        val highResCount: Int = 0,
        val dsdCount: Int = 0,
        val scanProgress: Float = 0f,
        val scanStatus: String = "",
        /**
         * Set when a scan request was declined, so a pull-to-refresh gesture can
         * be released instead of spinning forever.
         */
        val scanRequestRejected: Boolean = false,
        val statusMessage: String? = null,
        val availableFolders: List<SelectableFolder> = emptyList(),
        val isFolderPickerVisible: Boolean = false,
        /**
         * When set, the Tracks list should scroll to this path and then clear it
         * via consumeScrollTarget(). Set by openTrackInList when the user taps
         * the player's album art.
         */
        val scrollToPath: String? = null
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
        val dateAdded: Long = 0L,
        // The rest back the row's overflow menu and the Info dialog, which would
        // otherwise have to re-read the row from the database on every tap.
        val albumArtist: String = "",
        val genre: String = "",
        val composer: String = "",
        val year: Int = 0,
        val fileSize: Long = 0L,
        val folder: String = "",
        val isFavourite: Boolean = false,
        val isUserEdited: Boolean = false,
        val playedMs: Long = 0L,
        val playedPercent: Int = 0
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

    /** Set once the background artwork repair has been started this session. */
    @Volatile
    private var hasStartedArtworkRepair = false

    // Unfiltered, unsorted source data. Filter and sort always derive from
    // these, so neither operation can destroy data the other needs.
    private var allFolders: List<FolderItem> = emptyList()
    private var allArtists: List<ArtistItem> = emptyList()
    private var allAlbums: List<AlbumItem> = emptyList()
    private var allGenres: List<GenreItem> = emptyList()
    private var allComposers: List<ComposerItem> = emptyList()
    private var allTracks: List<TrackItem> = emptyList()

    /** Empty means "scan everything". */
    @Volatile
    private var selectedFolderPaths: Set<String> = emptySet()

    private var scanJob: Job? = null

    init {
        restoreFolderSelection()
        loadLibrary()
    }

    /**
     * Load the persisted folder selection before the first scan can run.
     *
     * Without this the picker choice was session-only: the user narrowed the
     * scan to two folders, and the next launch scanned everything again.
     */
    private fun restoreFolderSelection() {
        val repository = settingsRepository ?: return
        viewModelScope.launch {
            selectedFolderPaths = repository.scanDirectories.first()
        }
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
        _uiState.update { state ->
            // An order that means nothing on the new tab would silently show an
            // unsorted list, so fall back to name order when it does not apply.
            val order = if (state.sortOrder.appliesTo(tab)) {
                state.sortOrder
            } else {
                SortOrder.NAME_ASC
            }
            state.copy(currentTab = tab, sortOrder = order)
        }
        refreshVisibleItems()
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        refreshVisibleItems()
    }

    /**
     * Step to the next order that applies to the current tab.
     *
     * Kept for completeness, but the screen now shows a labelled menu instead:
     * cycling blindly gave no way to tell which of several orders was active.
     */
    fun cycleSortOrder() {
        val options = SortOrder.optionsFor(_uiState.value.currentTab)
        if (options.isEmpty()) return
        val index = options.indexOf(_uiState.value.sortOrder)
        setSortOrder(options[(index + 1) % options.size])
    }

    fun setSortOrder(order: SortOrder) {
        _uiState.update { it.copy(sortOrder = order) }
        refreshVisibleItems()
    }

    fun dismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    /**
     * Show a message produced elsewhere, such as the playlist picker hosted by
     * the navigation graph. Without this the outcome of "add to playlist" —
     * including a failure — would never be seen on this screen.
     */
    fun showStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
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
        val chosenPaths = if (chosen.size == _uiState.value.availableFolders.size) {
            emptySet()
        } else {
            chosen.mapTo(mutableSetOf()) { it.path }
        }
        selectedFolderPaths = chosenPaths
        persistFolderSelection(chosenPaths)

        hideFolderPicker()
        rescan()
    }

    /**
     * Write the folder selection out.
     *
     * Runs on the application scope, not viewModelScope: navigating away from
     * the Library screen clears this ViewModel, which would cancel the write
     * and silently lose the choice the user just confirmed.
     */
    private fun persistFolderSelection(paths: Set<String>) {
        val repository = settingsRepository ?: return
        BitPerfectApp.applicationScope.launch {
            repository.setScanDirectories(paths)
        }
    }

    /**
     * Rescan honouring the saved folder selection. This is the pull-to-refresh
     * and generic entry point; the scan menu calls the typed variants below.
     */
    fun rescan() {
        launchScan(directories = selectedFolderPaths.toList())
    }

    /**
     * Scan the whole device, clearing any folder restriction.
     */
    fun scanAll() {
        selectedFolderPaths = emptySet()
        persistFolderSelection(emptySet())
        launchScan(directories = emptyList())
    }

    /**
     * Scan the whole device but only take the given formats. Additive: it will
     * not remove tracks of other formats already in the library.
     *
     * @param formats lowercase extensions without the dot, e.g. {"flac","wav"}
     */
    fun scanByFormats(formats: Set<String>) {
        if (formats.isEmpty()) return
        launchScan(directories = emptyList(), formats = formats)
    }

    /**
     * Single scan entry point. All modes funnel through here so the busy state
     * is managed in exactly one place, which is what stops the refresh spinner
     * from being left running: isScanning is set true before the work and false
     * in a finally, so no early return or exception can strand it.
     */
    private fun launchScan(
        directories: List<String>,
        formats: Set<String>? = null
    ) {
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

            try {
                runScan(directories, formats)
            } finally {
                // Whatever happened — success, empty result, cancellation, or a
                // thrown exception — the busy flag is cleared here, so the UI
                // spinner always stops.
                _uiState.update { it.copy(isScanning = false, scanProgress = 0f, scanStatus = "") }
            }
        }
    }

    private suspend fun runScan(directories: List<String>, formats: Set<String>?) {
        val result = musicLibrary.triggerScan(
            directories = directories,
            formats = formats,
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
                statusMessage = when {
                    !result.success -> result.error ?: "Scan failed"
                    result.totalTracks == 0 -> "No supported audio files found"
                    else -> "Found ${result.totalTracks} tracks"
                }
            )
        }
    }

    /**
     * Import audio from a .zip chosen in the document picker, then refresh.
     *
     * Runs on the busy flag like a scan, so the same spinner and the same
     * single-place cleanup apply.
     */
    fun importZip(uri: Uri) {
        if (scanJob?.isActive == true) {
            _uiState.update { it.copy(scanRequestRejected = true) }
            return
        }
        scanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isScanning = true, scanStatus = "Extracting archive…", statusMessage = null)
            }
            try {
                val result = musicLibrary.importZip(uri)
                loadLibrary()
                _uiState.update {
                    it.copy(
                        statusMessage = when {
                            !result.isSuccess -> result.error ?: "Could not import the archive"
                            result.imported == 0 -> "No playable audio in the archive"
                            else -> "Imported ${result.imported} tracks" +
                                if (result.skipped > 0) " (${result.skipped} skipped)" else ""
                        }
                    )
                }
            } finally {
                _uiState.update { it.copy(isScanning = false, scanStatus = "") }
            }
        }
    }

    fun cancelScan() {
        musicLibrary.cancelScan()
    }

    /**
     * Switch to the Tracks tab and request a scroll to [path]. Backs the
     * player's album-art tap, which jumps the library to the playing song.
     */
    fun openTrackInList(path: String) {
        _uiState.update { it.copy(currentTab = LibraryTab.TRACKS, scrollToPath = path) }
    }

    /** Clear the scroll request once the list has acted on it. */
    fun consumeScrollTarget() {
        _uiState.update { it.copy(scrollToPath = null) }
    }

    // --- Per-track actions, from the row's overflow menu ---

    /**
     * Flip a track's favourite flag.
     *
     * The visible list is patched in place rather than reloaded. A full
     * loadLibrary() re-reads and re-sorts everything, which on a large library
     * makes the heart icon respond visibly late and can scroll the list.
     */
    fun toggleFavourite(trackId: Long) {
        viewModelScope.launch {
            val current = allTracks.firstOrNull { it.id == trackId } ?: return@launch
            val updated = !current.isFavourite
            musicLibrary.setFavourite(trackId, updated)
            patchTrack(trackId) { it.copy(isFavourite = updated) }
        }
    }

    /**
     * Drop a track from the library index. The file is left alone — see
     * [MusicLibrary.removeTrackFromLibrary].
     */
    fun removeTrackFromLibrary(trackId: Long) {
        viewModelScope.launch {
            val title = allTracks.firstOrNull { it.id == trackId }?.title
            musicLibrary.removeTrackFromLibrary(trackId)

            allTracks = allTracks.filterNot { it.id == trackId }
            _uiState.update { state ->
                state.copy(
                    totalTracks = allTracks.size,
                    isEmpty = allTracks.isEmpty(),
                    statusMessage = if (title != null) {
                        "Removed \"$title\" from the library. The file is still on the device."
                    } else {
                        "Removed from the library. The file is still on the device."
                    }
                )
            }
            refreshVisibleItems()
        }
    }

    /**
     * Save corrected tags for a track. Library-only; the file is not rewritten.
     */
    fun updateTrackDetails(
        trackId: Long,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        genre: String,
        year: Int,
        trackNumber: Int
    ) {
        viewModelScope.launch {
            val saved = musicLibrary.updateTrackDetails(
                trackId = trackId,
                title = title,
                artist = artist,
                albumTitle = album,
                albumArtist = albumArtist,
                genre = genre,
                year = year,
                trackNumber = trackNumber
            )

            if (saved == null) {
                _uiState.update { it.copy(statusMessage = "That track is no longer in the library") }
                return@launch
            }

            if (saved.isUnconfirmed) {
                // It has just left the main library, so reload rather than patch a
                // row that should no longer be listed.
                loadLibrary()
                _uiState.update {
                    it.copy(
                        statusMessage = "Saved. With no artist it moved to " +
                            "\"Review unconfirmed music\"."
                    )
                }
                return@launch
            }

            // Album and artist groupings may have changed, so the aggregate lists
            // need rebuilding, not just this row.
            loadLibrary()
            _uiState.update { it.copy(statusMessage = "Tags saved for \"${saved.title}\"") }
        }
    }

    /**
     * The lyrics text to show in the editor: the user's own if they have any,
     * otherwise whatever the file itself carries.
     */
    suspend fun loadEditableLyrics(path: String): String =
        musicLibrary.getEditableLyrics(path)

    /**
     * Save or remove lyrics for a track. A blank [lyrics] removes them, which is
     * recorded so the file's embedded lyrics do not simply come back.
     */
    fun saveLyrics(path: String, lyrics: String) {
        viewModelScope.launch {
            val accepted = musicLibrary.setLyrics(path, lyrics)
            _uiState.update { state ->
                state.copy(
                    statusMessage = when {
                        !accepted -> "Could not save the lyrics"
                        lyrics.isBlank() -> "Lyrics removed"
                        else -> "Lyrics saved"
                    }
                )
            }
        }
    }

    /**
     * Fill in album art that cannot be displayed, in the background.
     *
     * A library scanned before covers were extracted at scan time holds MediaStore
     * album-art URIs that no longer resolve, so its rows show placeholders. Rather
     * than making the user find "Rebuild album art" in Settings, each row repairs
     * itself here and appears as soon as its cover is read.
     *
     * Once per session: the pass reads a tag header for every track that still has
     * no cover, and most of those genuinely have none, so repeating it on every
     * library reload would be work with no result.
     */
    private fun startArtworkRepair() {
        if (hasStartedArtworkRepair) return
        hasStartedArtworkRepair = true

        viewModelScope.launch {
            try {
                musicLibrary.repairMissingArtwork { trackId, artworkPath ->
                    patchTrack(trackId) { it.copy(artworkUri = artworkPath) }
                }
            } catch (error: Exception) {
                // Cosmetic work; a failure must not disturb the library.
            }
        }
    }

    /** Replace one visible row without reloading and re-sorting the library. */
    private fun patchTrack(trackId: Long, transform: (TrackItem) -> TrackItem) {
        allTracks = allTracks.map { if (it.id == trackId) transform(it) else it }
        _uiState.update { state ->
            state.copy(tracks = state.tracks.map { if (it.id == trackId) transform(it) else it })
        }
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

            startArtworkRepair()

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    isEmpty = allTracks.isEmpty(),
                    totalTracks = allTracks.size,
                    totalAlbums = allAlbums.size,
                    totalArtists = allArtists.size,
                    highResCount = allTracks.count {
                        it.sampleRate > CD_SAMPLE_RATE_HZ || it.bitDepth > CD_BIT_DEPTH
                    },
                    // Same rule as Track.isDsd: the codec column holds the
                    // container name, so DSD shows up as DSF or DFF.
                    dsdCount = allTracks.count { it.codec in DSD_CODECS }
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

        // One grouping pass instead of scanning all tracks per album. An album is
        // as recent as its newest track, so a later addition to an album moves the
        // whole album up "recently added".
        val newestByAlbum = tracks
            .groupingBy { it.albumId }
            .fold(0L) { newest, track ->
                maxOf(newest, track.addedAt.takeIf { it > 0L } ?: track.lastModified)
            }

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
        // Fall back to the file timestamp only for rows written before addedAt
        // existed and somehow missed the migration's backfill, so date-added
        // order never collapses into an all-zero tie.
        dateAdded = track.addedAt.takeIf { it > 0L } ?: track.lastModified,
        albumArtist = track.albumArtist,
        genre = track.genre,
        composer = track.composer,
        year = track.year,
        fileSize = track.fileSize,
        folder = track.folder,
        isFavourite = track.isFavourite,
        isUserEdited = track.isUserEdited,
        playedMs = track.playedMs,
        playedPercent = track.playedPercent
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
                    SortOrder.DATE_ADDED_NEWEST -> items.sortedByDescending { it.dateAdded }
                    SortOrder.DATE_ADDED_OLDEST -> items.sortedBy { it.dateAdded }
                    SortOrder.TRACK_COUNT -> items.sortedByDescending { it.trackCount }
                    SortOrder.YEAR -> items.sortedByDescending { it.year }
                    // Per-file orders mean nothing for an album; fall back to
                    // name rather than leaving the list in database order.
                    SortOrder.FORMAT,
                    SortOrder.MOST_PLAYED,
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
            .let { items -> sortTracks(items, order) }

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
