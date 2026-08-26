package com.bitperfect.android.library.dao

import com.bitperfect.android.library.model.Track

/**
 * Data Access Object for Track entities.
 *
 * Provides all query methods for browsing and searching tracks.
 * When Room is integrated, uncomment the annotations.
 */
// @Dao
interface TrackDao {

    // @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun getAll(): List<Track>

    // @Query("SELECT * FROM tracks WHERE id = :id")
    fun getById(id: Long): Track?

    // @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNumber ASC, trackNumber ASC")
    fun getByAlbum(albumId: Long): List<Track>

    // @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY albumTitle ASC, trackNumber ASC")
    fun getByArtist(artist: String): List<Track>

    // @Query("SELECT * FROM tracks WHERE genre = :genre ORDER BY title ASC")
    fun getByGenre(genre: String): List<Track>

    // @Query("SELECT * FROM tracks WHERE composer = :composer ORDER BY title ASC")
    fun getByComposer(composer: String): List<Track>

    // @Query("SELECT * FROM tracks WHERE path LIKE :folderPath || '%' ORDER BY path ASC")
    fun getByFolder(folderPath: String): List<Track>

    // @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR albumTitle LIKE '%' || :query || '%'")
    fun search(query: String): List<Track>

    // @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(track: Track): Long

    // @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(tracks: List<Track>): List<Long>

    // @Update
    fun update(track: Track)

    // @Delete
    fun delete(track: Track)

    // @Query("DELETE FROM tracks WHERE path = :path")
    fun deleteByPath(path: String)

    // @Query("SELECT COUNT(*) FROM tracks")
    fun count(): Int

    // @Query("SELECT DISTINCT path FROM tracks WHERE path LIKE :folderPath || '%'")
    fun getPathsInFolder(folderPath: String): List<String>
}
