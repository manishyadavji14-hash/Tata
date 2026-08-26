package com.bitperfect.android.library.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bitperfect.android.library.model.Playlist

/**
 * Data Access Object for Playlist entities.
 *
 * Provides CRUD operations for user-created playlists.
 * Synchronous; callers dispatch on Dispatchers.IO.
 */
@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY modifiedAt DESC")
    fun getAll(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun getById(id: Long): Playlist?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(playlist: Playlist): Long

    @Update
    fun update(playlist: Playlist)

    @Delete
    fun delete(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :id")
    fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM playlists")
    fun count(): Int

    /**
     * @param pattern Escaped LIKE pattern from [SqlPatterns.contains].
     */
    @Query("SELECT * FROM playlists WHERE name LIKE :pattern ESCAPE '\\' ORDER BY name ASC")
    fun search(pattern: String): List<Playlist>

    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    fun findByName(name: String): Playlist?
}
