package com.bitperfect.android.library

import com.bitperfect.android.library.dao.AlbumDao
import com.bitperfect.android.library.dao.ArtistDao
import com.bitperfect.android.library.dao.PlaylistDao
import com.bitperfect.android.library.dao.TrackDao

/**
 * Room Database for the music library.
 *
 * Entities: Track, Album, Artist, Genre, Composer, Playlist
 *
 * When Room is integrated, this would extend RoomDatabase and be
 * annotated with @Database listing all entity classes and version.
 *
 * Schema version history:
 * - v1: Initial schema with all entities
 */
// @Database(
//     entities = [Track::class, Album::class, Artist::class, Genre::class, Composer::class, Playlist::class],
//     version = 1,
//     exportSchema = true
// )
abstract class LibraryDatabase /* : RoomDatabase() */ {

    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val DATABASE_NAME = "bitperfect_library.db"

        // Room instance would be created here with:
        // Room.databaseBuilder(context, LibraryDatabase::class.java, DATABASE_NAME)
        //     .fallbackToDestructiveMigration()
        //     .build()
    }
}
