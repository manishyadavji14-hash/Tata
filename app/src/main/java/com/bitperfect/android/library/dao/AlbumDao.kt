package com.bitperfect.android.library.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bitperfect.android.library.model.Album

/**
 * Data Access Object for Album entities.
 *
 * Provides query methods for browsing and searching albums.
 * Synchronous; callers dispatch on Dispatchers.IO.
 */
@Dao
interface AlbumDao {

    @Query("SELECT * FROM albums ORDER BY title ASC")
    fun getAll(): List<Album>

    @Query("SELECT * FROM albums WHERE id = :id")
    fun getById(id: Long): Album?

    /**
     * Albums credited to an artist, matching either the album artist or the
     * per-track artist so appearances on compilations are still found.
     */
    @Query(
        "SELECT * FROM albums WHERE albumArtist = :artist OR artist = :artist " +
            "ORDER BY year DESC, title ASC"
    )
    fun getByArtist(artist: String): List<Album>

    /**
     * @param pattern Escaped LIKE pattern from [SqlPatterns.contains].
     */
    @Query(
        "SELECT * FROM albums WHERE title LIKE :pattern ESCAPE '\\' " +
            "OR albumArtist LIKE :pattern ESCAPE '\\' " +
            "OR artist LIKE :pattern ESCAPE '\\' " +
            "ORDER BY title ASC"
    )
    fun search(pattern: String): List<Album>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(album: Album): Long

    @Update
    fun update(album: Album)

    @Delete
    fun delete(album: Album)

    @Query("DELETE FROM albums")
    fun deleteAll()

    @Query("SELECT * FROM albums WHERE title = :title AND albumArtist = :albumArtist LIMIT 1")
    fun findByTitleAndAlbumArtist(title: String, albumArtist: String): Album?

    /**
     * Remove albums that no longer correspond to any track.
     */
    @Query("DELETE FROM albums WHERE id NOT IN (SELECT DISTINCT albumId FROM tracks WHERE albumId != 0)")
    fun deleteOrphans()

    @Query("SELECT COUNT(*) FROM albums")
    fun count(): Int
}
