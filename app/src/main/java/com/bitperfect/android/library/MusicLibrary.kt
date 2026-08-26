package com.bitperfect.android.library

import com.bitperfect.android.library.model.*
import com.bitperfect.android.library.scanner.LibraryScanner

/**
 * MusicLibrary - facade for the music library system.
 *
 * Provides a unified API for:
 * - Triggering library scans
 * - Browsing by folders, artists, albums, genres, composers, tracks
 * - Searching across all entities
 * - Managing playlists
 *
 * This class coordinates between the scanner, metadata extractor,
 * and database layer to provide a clean API to the UI layer.
 */
class MusicLibrary {

    private val scanner = LibraryScanner()
    private val metadataExtractor = MetadataExtractor()

    // In-memory cache for fast access
    private val trackCache = mutableListOf<Track>()
    private val albumCache = mutableListOf<Album>()
    private val artistCache = mutableListOf<Artist>()
    private val genreCache = mutableListOf<Genre>()
    private val composerCache = mutableListOf<Composer>()
    private val playlistCache = mutableListOf<Playlist>()

    /**
     * Trigger a full library scan.
     * @param directories Directories to scan for audio files
     * @param progressCallback Optional progress callback
     * @return Scan result
     */
    fun triggerScan(
        directories: List<String>,
        progressCallback: ((LibraryScanner.ScanProgress) -> Unit)? = null
    ): LibraryScanner.ScanResult {
        if (progressCallback != null) {
            scanner.setProgressCallback(progressCallback)
        }
        val existingPaths = trackCache.map { it.path }.toSet()
        return scanner.scan(directories, existingPaths)
    }

    /**
     * Cancel an ongoing scan.
     */
    fun cancelScan() {
        scanner.cancel()
    }

    // --- Browse by Folders ---

    /**
     * Get all unique folder paths in the library.
     */
    fun getFolders(): List<String> {
        return trackCache.map { it.folder }.distinct().sorted()
    }

    /**
     * Get tracks in a specific folder.
     */
    fun getTracksByFolder(folderPath: String): List<Track> {
        return trackCache.filter { it.folder == folderPath }
            .sortedBy { it.path }
    }

    // --- Browse by Artists ---

    /**
     * Get all artists.
     */
    fun getArtists(): List<Artist> {
        return artistCache.sortedBy { it.name }
    }

    /**
     * Get tracks by a specific artist.
     */
    fun getTracksByArtist(artistName: String): List<Track> {
        return trackCache.filter { it.artist == artistName }
            .sortedWith(compareBy({ it.albumTitle }, { it.discNumber }, { it.trackNumber }))
    }

    /**
     * Get albums by a specific artist.
     */
    fun getAlbumsByArtist(artistName: String): List<Album> {
        return albumCache.filter { it.artist == artistName }
            .sortedByDescending { it.year }
    }

    // --- Browse by Albums ---

    /**
     * Get all albums.
     */
    fun getAlbums(): List<Album> {
        return albumCache.sortedBy { it.title }
    }

    /**
     * Get tracks in a specific album.
     */
    fun getTracksByAlbum(albumId: Long): List<Track> {
        return trackCache.filter { it.albumId == albumId }
            .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
    }

    // --- Browse by Genres ---

    /**
     * Get all genres.
     */
    fun getGenres(): List<Genre> {
        return genreCache.sortedBy { it.name }
    }

    /**
     * Get tracks in a specific genre.
     */
    fun getTracksByGenre(genreName: String): List<Track> {
        return trackCache.filter { it.genre == genreName }
            .sortedBy { it.title }
    }

    // --- Browse by Composers ---

    /**
     * Get all composers.
     */
    fun getComposers(): List<Composer> {
        return composerCache.sortedBy { it.name }
    }

    /**
     * Get tracks by a specific composer.
     */
    fun getTracksByComposer(composerName: String): List<Track> {
        return trackCache.filter { it.composer == composerName }
            .sortedBy { it.title }
    }

    // --- Browse all tracks ---

    /**
     * Get all tracks.
     */
    fun getAllTracks(): List<Track> {
        return trackCache.sortedBy { it.title }
    }

    /**
     * Get a track by its ID.
     */
    fun getTrackById(id: Long): Track? {
        return trackCache.find { it.id == id }
    }

    /**
     * Get a track by its file path.
     */
    fun getTrackByPath(path: String): Track? {
        return trackCache.find { it.path == path }
    }

    // --- Search ---

    /**
     * Search across all entities.
     * @param query Search query string
     * @return Search results grouped by type
     */
    fun search(query: String): SearchResults {
        val lowerQuery = query.lowercase()
        return SearchResults(
            tracks = trackCache.filter {
                it.title.lowercase().contains(lowerQuery) ||
                it.artist.lowercase().contains(lowerQuery) ||
                it.albumTitle.lowercase().contains(lowerQuery)
            },
            albums = albumCache.filter {
                it.title.lowercase().contains(lowerQuery) ||
                it.artist.lowercase().contains(lowerQuery)
            },
            artists = artistCache.filter {
                it.name.lowercase().contains(lowerQuery)
            }
        )
    }

    // --- Playlists ---

    /**
     * Get all playlists.
     */
    fun getPlaylists(): List<Playlist> {
        return playlistCache.sortedByDescending { it.modifiedAt }
    }

    /**
     * Create a new playlist.
     */
    fun createPlaylist(name: String, trackIds: List<Long> = emptyList()): Playlist {
        val playlist = Playlist(
            id = (playlistCache.maxOfOrNull { it.id } ?: 0) + 1,
            name = name,
            trackIds = Playlist.trackIdsToJson(trackIds)
        )
        playlistCache.add(playlist)
        return playlist
    }

    /**
     * Update a playlist.
     */
    fun updatePlaylist(playlist: Playlist) {
        val index = playlistCache.indexOfFirst { it.id == playlist.id }
        if (index >= 0) {
            playlistCache[index] = playlist.copy(modifiedAt = System.currentTimeMillis())
        }
    }

    /**
     * Delete a playlist.
     */
    fun deletePlaylist(id: Long) {
        playlistCache.removeAll { it.id == id }
    }

    /**
     * Get tracks in a playlist.
     */
    fun getPlaylistTracks(playlistId: Long): List<Track> {
        val playlist = playlistCache.find { it.id == playlistId } ?: return emptyList()
        val trackIds = playlist.getTrackIdList()
        return trackIds.mapNotNull { id -> trackCache.find { it.id == id } }
    }

    // --- Library statistics ---

    /**
     * Get library statistics.
     */
    fun getStats(): LibraryStats {
        return LibraryStats(
            totalTracks = trackCache.size,
            totalAlbums = albumCache.size,
            totalArtists = artistCache.size,
            totalGenres = genreCache.size,
            totalComposers = composerCache.size,
            totalPlaylists = playlistCache.size,
            totalDuration = trackCache.sumOf { it.duration },
            highResCount = trackCache.count { it.isHighRes },
            dsdCount = trackCache.count { it.isDsd }
        )
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
