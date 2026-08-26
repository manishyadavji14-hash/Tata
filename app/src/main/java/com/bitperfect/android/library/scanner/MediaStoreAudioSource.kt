package com.bitperfect.android.library.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.bitperfect.android.library.MetadataExtractor

/**
 * Enumerates on-device audio through MediaStore.
 *
 * MediaStore is used rather than walking the filesystem for two reasons:
 * scoped storage makes direct directory traversal unreliable from Android 11,
 * and the media index already carries the tags the library needs, so a scan
 * does not have to open every file.
 *
 * Crucially, MediaStore also exposes an absolute file path, which the native
 * WAV/FLAC decoders require since they open files with fopen().
 */
class MediaStoreAudioSource(private val context: Context) {

    companion object {
        private const val TAG = "MediaStoreAudioSource"

        /** MediaStore packs disc and track number as disc * 1000 + track. */
        private const val DISC_MULTIPLIER = 1000
    }

    /**
     * One audio file as indexed by MediaStore.
     *
     * @param mediaStoreAlbumId MediaStore's own album id, used to build the
     *   album artwork content URI. Distinct from the library's album row id.
     */
    data class AudioFileEntry(
        val path: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val mediaStoreAlbumId: Long,
        val composer: String,
        val genre: String,
        val year: Int,
        val durationMs: Long,
        val trackNumber: Int,
        val discNumber: Int,
        val sampleRate: Int,
        val bitDepth: Int,
        val fileSize: Long,
        val lastModified: Long,
        val format: String
    ) {
        /**
         * Album artwork content URI, loadable directly by Coil.
         */
        val artworkUri: String
            get() = ContentUris.withAppendedId(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                mediaStoreAlbumId
            ).toString()
    }

    /**
     * Query MediaStore for audio files.
     *
     * @param folderPrefixes Restrict results to files under these absolute
     *   directory paths. Empty means every indexed audio file.
     */
    fun query(folderPrefixes: List<String> = emptyList()): List<AudioFileEntry> {
        val projection = buildProjection()
        val (selection, selectionArgs) = buildSelection(folderPrefixes)

        val entries = mutableListOf<AudioFileEntry>()

        // Failures deliberately propagate. An empty result is indistinguishable
        // from "this device has no audio", and the caller treats that as a
        // successful scan which then deletes every stored track.
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.DATA} ASC"
        ) ?: throw QueryFailedException("MediaStore returned no cursor for audio")

        cursor.use { activeCursor ->
            while (activeCursor.moveToNext()) {
                readEntry(activeCursor)?.let(entries::add)
            }
        }

        return entries
    }

    /**
     * Raised when the media index cannot be read, so a scan can fail loudly
     * instead of being mistaken for an empty device.
     */
    class QueryFailedException(message: String) : Exception(message)

    private fun buildProjection(): Array<String> {
        val columns = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // GENRE and ALBUM_ARTIST were both added in API 30. MediaProvider
            // rejects a projection naming a column it does not know, which
            // fails the whole query, so neither may be requested below R.
            columns.add(MediaStore.Audio.Media.GENRE)
            columns.add(MediaStore.Audio.Media.ALBUM_ARTIST)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            columns.add(MediaStore.Audio.Media.BITS_PER_SAMPLE)
            columns.add(MediaStore.Audio.Media.SAMPLERATE)
        }

        return columns.toTypedArray()
    }

    private fun buildSelection(folderPrefixes: List<String>): Pair<String, Array<String>> {
        val clauses = mutableListOf("${MediaStore.Audio.Media.IS_MUSIC} != 0")
        val args = mutableListOf<String>()

        if (folderPrefixes.isNotEmpty()) {
            val pathClause = folderPrefixes.joinToString(" OR ") {
                "${MediaStore.Audio.Media.DATA} LIKE ?"
            }
            clauses.add("($pathClause)")
            folderPrefixes.forEach { prefix ->
                args.add("${prefix.trimEnd('/')}/%")
            }
        }

        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    private fun readEntry(cursor: Cursor): AudioFileEntry? {
        val path = cursor.stringOrNull(MediaStore.Audio.Media.DATA) ?: return null
        if (path.isBlank()) return null

        // MediaStore indexes formats this player cannot decode; keep only the
        // extensions the engine recognises.
        val extension = path.substringAfterLast('.', "")
        if (!MetadataExtractor.isSupportedExtension(extension)) return null

        val packedTrack = cursor.intOrZero(MediaStore.Audio.Media.TRACK)
        val discNumber = if (packedTrack >= DISC_MULTIPLIER) packedTrack / DISC_MULTIPLIER else 1
        val trackNumber =
            if (packedTrack >= DISC_MULTIPLIER) packedTrack % DISC_MULTIPLIER else packedTrack

        // Both of these columns only exist from API 30, and are only present in
        // the projection there. Below R the tag reader supplies the album
        // artist instead, and genre stays blank.
        val genre: String
        val albumArtist: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            genre = cursor.stringOrNull(MediaStore.Audio.Media.GENRE).orEmpty()
            albumArtist = cursor.normalisedTag(MediaStore.Audio.Media.ALBUM_ARTIST)
        } else {
            genre = ""
            albumArtist = ""
        }

        val sampleRate: Int
        val bitDepth: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sampleRate = cursor.intOrZero(MediaStore.Audio.Media.SAMPLERATE)
            bitDepth = cursor.intOrZero(MediaStore.Audio.Media.BITS_PER_SAMPLE)
        } else {
            sampleRate = 0
            bitDepth = 0
        }

        // MediaStore stores DATE_MODIFIED in seconds.
        val modifiedSeconds = cursor.longOrZero(MediaStore.Audio.Media.DATE_MODIFIED)

        return AudioFileEntry(
            path = path,
            title = cursor.stringOrNull(MediaStore.Audio.Media.TITLE).orEmpty(),
            artist = cursor.normalisedTag(MediaStore.Audio.Media.ARTIST),
            album = cursor.normalisedTag(MediaStore.Audio.Media.ALBUM),
            albumArtist = albumArtist,
            mediaStoreAlbumId = cursor.longOrZero(MediaStore.Audio.Media.ALBUM_ID),
            composer = cursor.normalisedTag(MediaStore.Audio.Media.COMPOSER),
            genre = genre,
            year = cursor.intOrZero(MediaStore.Audio.Media.YEAR),
            durationMs = cursor.longOrZero(MediaStore.Audio.Media.DURATION),
            trackNumber = trackNumber,
            discNumber = discNumber,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            fileSize = cursor.longOrZero(MediaStore.Audio.Media.SIZE),
            lastModified = modifiedSeconds * 1000L,
            format = MetadataExtractor.formatForExtension(extension)
        )
    }

    // --- Cursor helpers: tolerate absent columns across API levels ---

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.intOrZero(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    private fun Cursor.longOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

    /**
     * MediaStore uses the literal string "<unknown>" for missing tags.
     */
    private fun Cursor.normalisedTag(column: String): String {
        val value = stringOrNull(column).orEmpty()
        return if (value == MediaStore.UNKNOWN_STRING) "" else value
    }
}
