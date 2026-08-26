package com.bitperfect.android.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bitperfect.android.library.dao.AlbumDao
import com.bitperfect.android.library.dao.ArtistDao
import com.bitperfect.android.library.dao.PlaylistDao
import com.bitperfect.android.library.dao.TrackDao
import com.bitperfect.android.library.model.Album
import com.bitperfect.android.library.model.Artist
import com.bitperfect.android.library.model.Playlist
import com.bitperfect.android.library.model.Track

/**
 * Room database for the music library.
 *
 * Tables: tracks, albums, artists, playlists.
 *
 * Genres and composers are intentionally not tables. They are derived from
 * the tracks table with GROUP BY queries (see TrackDao), which keeps them
 * consistent with the library without extra bookkeeping during scans.
 *
 * Schema version history:
 * - v1: tracks, albums, artists, playlists
 * - v2: album artist grouping (Album.albumArtist, Track.albumArtist) and
 *   Track.isFavourite
 */
@Database(
    entities = [Track::class, Album::class, Artist::class, Playlist::class],
    version = 2,
    exportSchema = true
)
abstract class LibraryDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val DATABASE_NAME = "bitperfect_library.db"

        /**
         * v1 to v2: album artist grouping and favourites.
         *
         * Tracks and playlists are migrated rather than dropped. Playlists
         * reference tracks by id and favourites are user data, so neither can
         * be rebuilt by rescanning.
         *
         * The albums table is derived entirely from tracks and is rebuilt on
         * every scan, so it is recreated outright - its unique index changes
         * from (title, artist) to (title, albumArtist), which SQLite cannot
         * express as an ALTER.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `albumArtist` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `isFavourite` INTEGER NOT NULL DEFAULT 0"
                )

                // Seed the new grouping key from the existing track artist so
                // the library stays browsable before the next rescan.
                db.execSQL("UPDATE `tracks` SET `albumArtist` = `artist`")

                db.execSQL("DROP TABLE IF EXISTS `albums`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `albums` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`albumArtist` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`artworkPath` TEXT, " +
                        "`year` INTEGER NOT NULL, " +
                        "`trackCount` INTEGER NOT NULL, " +
                        "`totalDuration` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_albums_title_albumArtist` " +
                        "ON `albums` (`title`, `albumArtist`)"
                )

                // Album ids were just discarded; a rescan re-links them.
                db.execSQL("UPDATE `tracks` SET `albumId` = 0")
            }
        }

        @Volatile
        private var instance: LibraryDatabase? = null

        /**
         * Get the process-wide database instance.
         *
         * There is deliberately no destructive fallback. Tracks are a
         * rebuildable cache, but playlists and favourites are not, so schema
         * changes must ship a migration rather than silently wiping user data.
         */
        fun getInstance(context: Context): LibraryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
