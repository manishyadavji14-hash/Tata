package com.bitperfect.android.library

import android.content.Context
import com.bitperfect.android.library.model.Album
import com.bitperfect.android.library.model.Artist
import com.bitperfect.android.library.model.Composer
import com.bitperfect.android.library.model.Genre
import com.bitperfect.android.library.model.Playlist
import com.bitperfect.android.engine.NativeAudioFormatProbe
import com.bitperfect.android.library.dao.SqlPatterns
import com.bitperfect.android.library.scanner.AudioFormatProbe
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
    private val database: LibraryDatabase = LibraryDatabase.getInstance(context),
    /**
     * Supplies exact sample rate and bit depth for files the media index does
     * not describe, which is all of them below Android 12.
     */
    formatProbe: AudioFormatProbe? = NativeAudioFormatProbe()
) {

    private val applicationContext = context.applicationContext
    private val metadataExtractor = MetadataExtractor()
    private val scanner = LibraryScanner(
        context = applicationContext,
        metadataExtractor = metadataExtractor,
        formatProbe = formatProbe
    )

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
        withContext(Dispatchers.IO) {
            trackDao.getByFolder(SqlPatterns.directoryPrefix(folderPath))
        }

    // --- Browse by Artists ---

    suspend fun getArtists(): List<Artist> = withContext(Dispatchers.IO) { artistDao.getAll() }

    suspend fun getTracksByArtist(artistName: String): List<Track> =
        withContext(Dispatchers.IO) { trackDao.getByArtist(artistName) }

    suspend fun getAlbumsByArtist(artistName: String): List<Album> =
        withContext(Dispatchers.IO) { albumDao.getByArtist(artistName) }

    suspend fun getArtistById(artistId: Long): Artist? =
        withContext(Dispatchers.IO) { artistDao.getById(artistId) }

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
                artworkUri = track.artworkPath,
                isFavourite = track.isFavourite,
                isInLibrary = true
            )
        }

        val metadata = metadataExtractor.extract(path)
            ?: return@withContext TrackDetails(title = fallbackTitle)

        val artwork = if (metadata.hasArtwork) {
            metadataExtractor.extractArtwork(path, artworkCache)
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

    /**
     * Cache for covers extracted from files the media index does not describe.
     */
    private val artworkCache = ArtworkCache(
        java.io.File(applicationContext.cacheDir, "artwork")
    )

    /**
     * Drop cached artwork, for example from a settings action.
     */
    suspend fun clearArtworkCache() = withContext(Dispatchers.IO) { artworkCache.clear() }

    /**
     * Display details for the currently playing file.
     */
    data class TrackDetails(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val year: Int = 0,
        val artworkUri: String? = null,
        val isFavourite: Boolean = false,
        /**
         * False for files opened directly, which have no library row and so
         * cannot be favourited or added to a playlist.
         */
        val isInLibrary: Boolean = false
    )

    // --- Search ---

    suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext SearchResults()
        val pattern = SqlPatterns.contains(query)
        SearchResults(
            tracks = trackDao.search(pattern),
            albums = albumDao.search(pattern),
            artists = artistDao.search(pattern)
        )
    }

    // --- Favourites ---

    suspend fun getFavourites(): List<Track> =
        withContext(Dispatchers.IO) { trackDao.getFavourites() }

    suspend fun setFavourite(trackId: Long, isFavourite: Boolean) =
        withContext(Dispatchers.IO) { trackDao.setFavourite(trackId, isFavourite) }

    /**
     * Toggle the favourite flag for a file path.
     *
     * @return The new state, or null when the path is not in the library.
     */
    suspend fun toggleFavouriteByPath(path: String): Boolean? = withContext(Dispatchers.IO) {
        val track = trackDao.getByPath(path) ?: return@withContext null
        val updated = !track.isFavourite
        trackDao.setFavourite(track.id, updated)
        updated
    }

    suspend fun isFavouriteByPath(path: String): Boolean =
        withContext(Dispatchers.IO) { trackDao.getByPath(path)?.isFavourite == true }

    // --- Playlists ---

    suspend fun getPlaylists(): List<Playlist> =
        withContext(Dispatchers.IO) { playlistDao.getAll() }

    /**
     * Creates a playlist, discarding repeated track ids.
     *
     * A playlist must hold each track at most once. The queue may legitimately
     * contain the same track twice, so saving a queue as a playlist would
     * otherwise store a duplicate, and the detail screen keys its rows by track
     * id - two identical keys crash the list. [addTracksToPlaylist] enforces the
     * same rule, so both insert paths agree.
     */
    suspend fun createPlaylist(name: String, trackIds: List<Long> = emptyList()): Playlist =
        withContext(Dispatchers.IO) {
            val playlist = Playlist(
                name = name,
                trackIds = Playlist.trackIdsToJson(trackIds.distinct())
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

    suspend fun findPlaylistByName(name: String): Playlist? =
        withContext(Dispatchers.IO) { playlistDao.findByName(name) }

    suspend fun renamePlaylist(playlistId: Long, newName: String) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val playlist = playlistDao.getById(playlistId) ?: return@runInTransaction
            playlistDao.update(
                playlist.copy(name = newName, modifiedAt = System.currentTimeMillis())
            )
        }
    }

    /**
     * Resolve file paths to library track ids, preserving order.
     *
     * Paths that are not in the library are dropped: a playlist entry needs a
     * stable id, which only scanned tracks have.
     */
    suspend fun resolveTrackIds(paths: List<String>): List<Long> = withContext(Dispatchers.IO) {
        paths.mapNotNull { path -> trackDao.getByPath(path)?.id }
    }

    /**
     * Add tracks to the end of a playlist, ignoring ones already present.
     *
     * The read-modify-write runs in a transaction so two concurrent edits
     * cannot each overwrite the other's additions.
     *
     * @return How many tracks were actually added.
     */
    suspend fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>): Int =
        withContext(Dispatchers.IO) {
            var addedCount = 0
            database.runInTransaction {
                val playlist = playlistDao.getById(playlistId) ?: return@runInTransaction

                // Parse once rather than per candidate.
                val existing = playlist.getTrackIdList()
                val existingSet = existing.toHashSet()
                val additions = trackIds.filter { existingSet.add(it) }
                if (additions.isEmpty()) return@runInTransaction

                addedCount = additions.size
                playlistDao.update(
                    playlist.copy(
                        trackIds = Playlist.trackIdsToJson(existing + additions),
                        modifiedAt = System.currentTimeMillis()
                    )
                )
            }
            addedCount
        }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) =
        withContext(Dispatchers.IO) {
            database.runInTransaction {
                val playlist = playlistDao.getById(playlistId) ?: return@runInTransaction
                val remaining = playlist.getTrackIdList().filterNot { it == trackId }
                playlistDao.update(
                    playlist.copy(
                        trackIds = Playlist.trackIdsToJson(remaining),
                        modifiedAt = System.currentTimeMillis()
                    )
                )
            }
        }

    /**
     * Reorder a playlist entry.
     */
    suspend fun movePlaylistTrack(playlistId: Long, fromIndex: Int, toIndex: Int) =
        withContext(Dispatchers.IO) {
            database.runInTransaction {
                val playlist = playlistDao.getById(playlistId) ?: return@runInTransaction
                val ids = playlist.getTrackIdList().toMutableList()
                if (fromIndex !in ids.indices || toIndex !in ids.indices) return@runInTransaction

                ids.add(toIndex, ids.removeAt(fromIndex))
                playlistDao.update(
                    playlist.copy(
                        trackIds = Playlist.trackIdsToJson(ids),
                        modifiedAt = System.currentTimeMillis()
                    )
                )
            }
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
     * Rebuilds albums and artists if they are missing while tracks exist.
     *
     * The v1 to v2 migration drops the albums table, because the unique index
     * moved to album artist and SQLite cannot alter an index in place. Albums
     * are derived data, so they are recoverable from the tracks table alone -
     * no storage permission and no file reads, unlike a full rescan. Without
     * this, an upgrading user opens an empty Albums tab and has no way to know
     * a reindex is needed.
     */
    suspend fun ensureAggregates(): Boolean = withContext(Dispatchers.IO) {
        if (trackDao.count() == 0 || albumDao.count() > 0) return@withContext false
        database.runInTransaction { rebuildAggregates() }
        true
    }

    /**
     * Derive albums and artists from the current tracks and link tracks to
     * their album row.
     *
     * Rows are upserted on their unique key rather than cleared and
     * reinserted, so album and artist ids stay stable across rescans. Album
     * ids appear in navigation routes, so reassigning them would leave an open
     * album screen pointing at a different album.
     */
    private fun rebuildAggregates() {
        val tracks = trackDao.getAll()

        // Albums group on album artist, which is what identifies an album.
        // Grouping on the per-track artist splits compilations into one album
        // per featured artist.
        val albumGroups = tracks
            .filter { it.albumTitle.isNotBlank() }
            .groupBy { it.albumTitle to it.albumArtist }

        for ((key, albumTracks) in albumGroups) {
            val (title, albumArtist) = key

            // Only credit a single artist when every track agrees; otherwise
            // leave it blank to mark the album as a compilation.
            val distinctArtists = albumTracks.map { it.artist }.distinct()
            val creditedArtist = distinctArtists.singleOrNull().orEmpty()

            val existing = albumDao.findByTitleAndAlbumArtist(title, albumArtist)
            val album = Album(
                id = existing?.id ?: 0L,
                title = title,
                albumArtist = albumArtist,
                artist = creditedArtist,
                artworkPath = albumTracks.firstNotNullOfOrNull { it.artworkPath },
                year = albumTracks.maxOfOrNull { it.year } ?: 0,
                trackCount = albumTracks.size,
                totalDuration = albumTracks.sumOf { it.duration }
            )

            val albumId = if (existing != null) {
                albumDao.update(album)
                existing.id
            } else {
                albumDao.insert(album)
            }

            // One statement per album instead of one per track.
            trackDao.assignAlbumId(albumId, title, albumArtist)
        }

        trackDao.clearAlbumIdForUngrouped()
        albumDao.deleteOrphans()

        val artistGroups = tracks
            .filter { it.artist.isNotBlank() }
            .groupBy { it.artist }

        for ((name, artistTracks) in artistGroups) {
            val existing = artistDao.findByName(name)
            val artist = Artist(
                id = existing?.id ?: 0L,
                name = name,
                albumCount = artistTracks.map { it.albumTitle }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .size,
                trackCount = artistTracks.size
            )
            if (existing != null) artistDao.update(artist) else artistDao.insert(artist)
        }

        artistDao.deleteOrphans()
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
