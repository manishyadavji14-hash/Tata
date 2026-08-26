package com.bitperfect.android.library.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bitperfect.android.library.model.Track

/**
 * Data Access Object for Track entities.
 *
 * Provides all query methods for browsing and searching tracks.
 *
 * These methods are synchronous and must not be called from the main thread.
 * Callers dispatch them on Dispatchers.IO via MusicLibrary.
 */
@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun getAll(): List<Track>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun getById(id: Long): Track?

    @Query("SELECT * FROM tracks WHERE path = :path LIMIT 1")
    fun getByPath(path: String): Track?

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNumber ASC, trackNumber ASC")
    fun getByAlbum(albumId: Long): List<Track>

    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY albumTitle ASC, trackNumber ASC")
    fun getByArtist(artist: String): List<Track>

    @Query("SELECT * FROM tracks WHERE genre = :genre ORDER BY title ASC")
    fun getByGenre(genre: String): List<Track>

    @Query("SELECT * FROM tracks WHERE composer = :composer ORDER BY title ASC")
    fun getByComposer(composer: String): List<Track>

    /**
     * Tracks under a directory.
     *
     * @param pathPattern Escaped LIKE pattern from [SqlPatterns.directoryPrefix].
     */
    @Query("SELECT * FROM tracks WHERE path LIKE :pathPattern ESCAPE '\\' ORDER BY path ASC")
    fun getByFolder(pathPattern: String): List<Track>

    /**
     * @param pattern Escaped LIKE pattern from [SqlPatterns.contains].
     */
    @Query(
        "SELECT * FROM tracks WHERE title LIKE :pattern ESCAPE '\\' " +
            "OR artist LIKE :pattern ESCAPE '\\' " +
            "OR albumTitle LIKE :pattern ESCAPE '\\' " +
            "ORDER BY title ASC"
    )
    fun search(pattern: String): List<Track>

    @Query("SELECT * FROM tracks WHERE isFavourite != 0 ORDER BY title ASC")
    fun getFavourites(): List<Track>

    @Query("UPDATE tracks SET isFavourite = :isFavourite WHERE id = :id")
    fun setFavourite(id: Long, isFavourite: Boolean)

    @Query("SELECT COUNT(*) FROM tracks WHERE isFavourite != 0")
    fun favouriteCount(): Int

    @Query("SELECT * FROM tracks WHERE albumArtist = :albumArtist ORDER BY albumTitle ASC, discNumber ASC, trackNumber ASC")
    fun getByAlbumArtist(albumArtist: String): List<Track>

    /**
     * Link every track of an album group to its album row in one statement.
     */
    @Query("UPDATE tracks SET albumId = :albumId WHERE albumTitle = :title AND albumArtist = :albumArtist")
    fun assignAlbumId(albumId: Long, title: String, albumArtist: String)

    // albumTitle is NOT NULL in the schema, so only the empty case can occur.
    @Query("UPDATE tracks SET albumId = 0 WHERE albumTitle = ''")
    fun clearAlbumIdForUngrouped()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(track: Track): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(tracks: List<Track>): List<Long>

    @Update
    fun update(track: Track)

    @Delete
    fun delete(track: Track)

    @Query("DELETE FROM tracks WHERE path = :path")
    fun deleteByPath(path: String)

    @Query("DELETE FROM tracks")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM tracks")
    fun count(): Int

    @Query("SELECT DISTINCT path FROM tracks WHERE path LIKE :folderPath || '%'")
    fun getPathsInFolder(folderPath: String): List<String>

    @Query("SELECT DISTINCT path FROM tracks")
    fun getAllPaths(): List<String>

    // --- Derived groupings (no separate tables) ---

    @Query(
        "SELECT genre AS name, COUNT(*) AS trackCount FROM tracks " +
            "WHERE genre IS NOT NULL AND genre != '' " +
            "GROUP BY genre ORDER BY genre ASC"
    )
    fun getGenreSummaries(): List<NameCount>

    @Query(
        "SELECT composer AS name, COUNT(*) AS trackCount FROM tracks " +
            "WHERE composer IS NOT NULL AND composer != '' " +
            "GROUP BY composer ORDER BY composer ASC"
    )
    fun getComposerSummaries(): List<NameCount>

    /**
     * Folder listing derived from track paths.
     *
     * SQLite has no dirname(), so the folder is computed by trimming the file
     * name from the end of the path.
     */
    @Query(
        "SELECT rtrim(rtrim(path, replace(path, '/', '')), '/') AS name, " +
            "COUNT(*) AS trackCount " +
            "FROM tracks GROUP BY name ORDER BY name ASC"
    )
    fun getFolderSummaries(): List<NameCount>

    /**
     * Projection for grouped name/count queries.
     */
    data class NameCount(
        val name: String,
        val trackCount: Int
    )
}
