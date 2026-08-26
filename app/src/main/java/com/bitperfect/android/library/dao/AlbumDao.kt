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

    @Query("SELECT * FROM albums WHERE artist = :artist ORDER BY year DESC, title ASC")
    fun getByArtist(artist: String): List<Album>

    @Query(
        "SELECT * FROM albums WHERE title LIKE '%' || :query || '%' " +
            "OR artist LIKE '%' || :query || '%'"
    )
    fun search(query: String): List<Album>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(album: Album): Long

    @Update
    fun update(album: Album)

    @Delete
    fun delete(album: Album)

    @Query("DELETE FROM albums")
    fun deleteAll()

    @Query("SELECT * FROM albums WHERE title = :title AND artist = :artist LIMIT 1")
    fun findByTitleAndArtist(title: String, artist: String): Album?

    @Query("SELECT COUNT(*) FROM albums")
    fun count(): Int
}
