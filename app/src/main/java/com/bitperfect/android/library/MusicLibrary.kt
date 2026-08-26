package com.bitperfect.android.library

import android.content.Context
import com.bitperfect.android.library.model.Album
import com.bitperfect.android.library.model.Artist
import com.bitperfect.android.library.model.Composer
import com.bitperfect.android.library.model.Genre
import com.bitperfect.android.library.model.Playlist
import com.bitperfect.android.library.model.Track
import com.bitperfect.android.library.scanner.LibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MusicLibrary - facade over the persisted music library.
 *
 * Coordinates the scanner and the Room database, and exposes browse, search
 * and playlist operations to the UI.
 *
 * Every method is a suspend function that switches to Dispatchers.IO, because
 * Room rejects synchronous queries on the main thread. Callers can invoke them
 * from any coroutine without thinking about dispatchers.
 *
 * Genres and composers are derived from the tracks table rather than stored,
 * so they cannot drift out of sync with the library.
 */
class MusicLibrary(
    context: Context,
    private val database: LibraryDatabase = LibraryDatabase.getInstance(context)
) {

    private val applicationContext = context.applicationContext
    private val metadataExtractor = MetadataExtractor()
    private val scanner = LibraryScanner(applicationContext, metadataExtractor)

    private val trackDao get() = database.trackDao()
    private val albumDao get() = database.albumDao()
    private val artistDao get() = database.artistDao()
    private val playlistDao get() = database.playlistDao()

    /**
     * Trigger a library scan and persist the result.
     *
     * @param directories Absolute folder paths to restrict the scan to.
     *   Empty scans all indexed audio on the device.
     */
    suspend fun triggerScan(
        directories: List<String> = emptyList(),
        progressCallback: ((LibraryScanner.ScanProgress) -> Unit)? = null
    ): LibraryScanner.ScanResult = withContext(Dispatchers.IO) {
        if (progressCallback != null) {
            scanner.setProgressCallback(progressCallback)
        }

        val existing = trackDao.getAll().associateBy { it.path }
        val result = scanner.scan(directories, existing)

        if (result.success) {
            persistScanResult(result.tracks, result.removedPaths)
        }
        result
    }

    /**
     * Cancel an ongoing scan.
     */
    fun cancelScan() {
        scanner.cancel()
    }

    /**
     * Folders on the device that contain audio, regardless of what has been
     * imported so far. Used for folder selection before a scan.
     */
    suspend fun discoverAvailableFolders(): List<LibraryScanner.FolderSummary> =
        withContext(Dispatchers.IO) { scanner.discoverFolders() }

    /**
     * Add a single file that MediaStore may not have indexed.
     *
     * @return The persisted track, or null if unsupported or unreadable.
     */
    suspend fun addSingleFile(path: String): Track? = withContext(Dispatchers.IO) {
        trackDao.getByPath(path)?.let { return@withContext it }

        val track = scanner.scanSingleFile(path) ?: return@withContext null

        var insertedId = 0L
        database.runInTransaction {
            insertedId = trackDao.insert(track)
            rebuildAggregates()
        }
        trackDao.getById(insertedId)
    }

    // --- Browse by Folders ---

    suspend fun getFolders(): List<String> = withContext(Dispatchers.IO) {
        trackDao.getFolderSummaries().map { it.name }
    }

    suspend fun getFolderSummaries(): List<LibraryScanner.FolderSummary> =
        withContext(Dispatchers.IO) {
            trackDao.getFolderSummaries().map {
                LibraryScanner.FolderSummary(it.name, it.trackCount)
            }
        }

    suspend fun getTracksByFolder(folderPath: String): List<Track> =
        withContext(Dispatchers.IO) { trackDao.getByFolder(folderPath) }

    // --- Browse by Artists ---

    suspend fun getArtists(): List<Artist> = withContext(Dispatchers.IO) { artistDao.getAll() }

    suspend fun getTracksByArtist(artistName: String): List<Track> =
        withContext(Dispatchers.IO) { trackDao.getByArtist(artistName) }

    suspend fun getAlbumsByArtist(artistName: String): List<Album> =
        withContext(Dispatchers.IO) { albumDao.getByArtist(artistName) }

    // --- Browse by Albums ---

    suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) { albumDao.getAll() }

    suspend fun getAlbumById(albumId: Long): Album? =
        withContext(Dispatchers.IO) { albumDao.getById(albumId) }

    suspend fun getTracksByAlbum(albumId: Long): List<Track> =
        withContext(Dispatchers.IO) { trackDao.getByAlbum(albumId) }

    // --- Browse by Genres / Composers (derived) ---

    suspend fun getGenres(): List<Genre> = withContext(Dispatchers.IO) {
        trackDao.getGenreSummaries().mapIndexed { index, summary ->
            Genre(id = index.toLong(), name = summary.name, trackCount = summary.trackCount)
        }
    }

    suspend fun getTracksByGenre(genreName: String): List<Track> =
        withContext(Dispatchers.IO) { trackDao.getByGenre(genreName) }

    suspend fun getComposers(): List<Composer> = withContext(Dispatchers.IO) {
        trackDao.getComposerSummaries().mapIndexed { index, summary ->
            Composer(id = index.toLong(), name = summary.name, trackCount = summary.trackCount)
        }
    }

    suspend fun getTracksByComposer(composerName: String): List<Track> =
        withContext(Dispatchers.IO) { trackDao.getByComposer(composerName) }

    // --- Browse all tracks ---

    suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.IO) { trackDao.getAll() }

    suspend fun getTrackById(id: Long): Track? = withContext(Dispatchers.IO) { trackDao.getById(id) }

    suspend fun getTrackByPath(path: String): Track? =
        withContext(Dispatchers.IO) { trackDao.getByPath(path) }

    /**
     * Resolve display details for a file being played.
     *
     * Library tracks answer from the database. Files opened directly are not in
     * the library, so their tags are read from the file and any embedded cover
     * is written to the cache so the UI has something loadable to point at.
     */
    suspend fun getTrackDetails(path: String): TrackDetails = withContext(Dispatchers.IO) {
        val fallbackTitle = path.substringAfterLast('/').substringBeforeLast('.')

        trackDao.getByPath(path)?.let { track ->
            return@withContext TrackDetails(
                title = track.title.ifBlank { fallbackTitle },
                artist = track.artist,
                album = track.albumTitle,
                year = track.year,
                artworkUri = track.artworkPath
            )
        }

        val metadata = metadataExtractor.extract(path)
            ?: return@withContext TrackDetails(title = fallbackTitle)

        val artwork = if (metadata.hasArtwork) {
            metadataExtractor.extractArtwork(path, artworkCacheDirectory())
        } else {
            null
        }

        TrackDetails(
            title = metadata.title.ifBlank { fallbackTitle },
            artist = metadata.artist,
            album = metadata.album,
            year = metadata.year,
            artworkUri = artwork
        )
    }

    private fun artworkCacheDirectory(): String =
        java.io.File(applicationContext.cacheDir, "artwork").absolutePath

    /**
     * Display details for the currently playing file.
     */
    data class TrackDetails(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val year: Int = 0,
        val artworkUri: String? = null
    )

    // --- Search ---

    suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext SearchResults()
        SearchResults(
            tracks = trackDao.search(query),
            albums = albumDao.search(query),
            artists = artistDao.search(query)
        )
    }

    // --- Playlists ---

    suspend fun getPlaylists(): List<Playlist> =
        withContext(Dispatchers.IO) { playlistDao.getAll() }

    suspend fun createPlaylist(name: String, trackIds: List<Long> = emptyList()): Playlist =
        withContext(Dispatchers.IO) {
            val playlist = Playlist(
                name = name,
                trackIds = Playlist.trackIdsToJson(trackIds)
            )
            val id = playlistDao.insert(playlist)
            playlist.copy(id = id)
        }

    suspend fun updatePlaylist(playlist: Playlist) = withContext(Dispatchers.IO) {
        playlistDao.update(playlist.copy(modifiedAt = System.currentTimeMillis()))
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        playlistDao.deleteById(id)
    }

    /**
     * Add tracks to the end of a playlist, ignoring ones already present.
     */
    suspend fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>) =
        withContext(Dispatchers.IO) {
            val playlist = playlistDao.getById(playlistId) ?: return@withContext
            val merged = playlist.getTrackIdList() + trackIds.filterNot { it in playlist.getTrackIdList() }
            playlistDao.update(
                playlist.copy(
                    trackIds = Playlist.trackIdsToJson(merged),
                    modifiedAt = System.currentTimeMillis()
                )
            )
        }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) =
        withContext(Dispatchers.IO) {
            val playlist = playlistDao.getById(playlistId) ?: return@withContext
            val remaining = playlist.getTrackIdList().filterNot { it == trackId }
            playlistDao.update(
                playlist.copy(
                    trackIds = Playlist.trackIdsToJson(remaining),
                    modifiedAt = System.currentTimeMillis()
                )
            )
        }

    /**
     * Tracks in a playlist, in the playlist's own order.
     */
    suspend fun getPlaylistTracks(playlistId: Long): List<Track> = withContext(Dispatchers.IO) {
        val playlist = playlistDao.getById(playlistId) ?: return@withContext emptyList()
        playlist.getTrackIdList().mapNotNull { trackDao.getById(it) }
    }

    // --- Library statistics ---

    suspend fun getStats(): LibraryStats = withContext(Dispatchers.IO) {
        val tracks = trackDao.getAll()
        LibraryStats(
            totalTracks = tracks.size,
            totalAlbums = albumDao.count(),
            totalArtists = artistDao.count(),
            totalGenres = trackDao.getGenreSummaries().size,
            totalComposers = trackDao.getComposerSummaries().size,
            totalPlaylists = playlistDao.count(),
            totalDuration = tracks.sumOf { it.duration },
            highResCount = tracks.count { it.isHighRes },
            dsdCount = tracks.count { it.isDsd }
        )
    }

    // --- Persistence internals ---

    /**
     * Reconcile the track table with the scan result and rebuild aggregates.
     *
     * Track rows are updated in place rather than cleared and reinserted, so
     * their ids survive a rescan. Playlists reference tracks by id, so wiping
     * the table would silently empty every playlist. For the same reason an
     * INSERT ... ON CONFLICT REPLACE is not usable here: SQLite implements it
     * as delete-then-insert, which also allocates a new id.
     *
     * All of it runs in one transaction so the library is never observed
     * half-scanned.
     */
    private fun persistScanResult(tracks: List<Track>, removedPaths: List<String>) {
        database.runInTransaction {
            removedPaths.forEach { path -> trackDao.deleteByPath(path) }

            tracks.forEach { track ->
                // Resolve the id inside the transaction. Inserting a path that
                // already exists would hit the unique index and REPLACE, which
                // SQLite implements as delete-then-insert and would allocate a
                // new id - the exact thing this reconcile exists to avoid.
                val existingId = if (track.id != 0L) {
                    track.id
                } else {
                    trackDao.getByPath(track.path)?.id ?: 0L
                }

                if (existingId != 0L) {
                    trackDao.update(track.copy(id = existingId))
                } else {
                    trackDao.insert(track)
                }
            }

            rebuildAggregates()
        }
    }

    /**
     * Derive albums and artists from the current tracks and link tracks to
     * their album row.
     */
    private fun rebuildAggregates() {
        val tracks = trackDao.getAll()

        albumDao.deleteAll()
        artistDao.deleteAll()

        // Albums are keyed by title + artist, matching the unique index.
        val albumGroups = tracks
            .filter { it.albumTitle.isNotBlank() }
            .groupBy { it.albumTitle to it.artist }

        for ((key, albumTracks) in albumGroups) {
            val (title, artist) = key
            val albumId = albumDao.insert(
                Album(
                    title = title,
                    artist = artist,
                    artworkPath = albumTracks.firstNotNullOfOrNull { it.artworkPath },
                    year = albumTracks.maxOfOrNull { it.year } ?: 0,
                    trackCount = albumTracks.size,
                    totalDuration = albumTracks.sumOf { it.duration }
                )
            )
            albumTracks.forEach { track ->
                trackDao.update(track.copy(albumId = albumId))
            }
        }

        val artistGroups = tracks
            .filter { it.artist.isNotBlank() }
            .groupBy { it.artist }

        for ((name, artistTracks) in artistGroups) {
            artistDao.insert(
                Artist(
                    name = name,
                    albumCount = artistTracks.map { it.albumTitle }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .size,
                    trackCount = artistTracks.size
                )
            )
        }
    }

    /**
     * Search results container.
     */
    data class SearchResults(
        val tracks: List<Track> = emptyList(),
        val albums: List<Album> = emptyList(),
        val artists: List<Artist> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()

        val totalCount: Int
            get() = tracks.size + albums.size + artists.size
    }

    /**
     * Library statistics.
     */
    data class LibraryStats(
        val totalTracks: Int = 0,
        val totalAlbums: Int = 0,
        val totalArtists: Int = 0,
        val totalGenres: Int = 0,
        val totalComposers: Int = 0,
        val totalPlaylists: Int = 0,
        val totalDuration: Long = 0,
        val highResCount: Int = 0,
        val dsdCount: Int = 0
    )
}
