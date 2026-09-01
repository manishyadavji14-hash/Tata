package com.bitperfect.android.player

import android.util.Log
import com.bitperfect.android.library.EmbeddedLyricsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds lyrics for a track, from two sources in order of authority.
 *
 * 1. **Sidecar files.** `song.flac` is matched by `song.lrc`, `song.txt` or the
 *    same name in a `Lyrics/` subfolder. This is how synced lyrics are normally
 *    distributed, and it lets the user add or correct lyrics without the app
 *    needing to write tags.
 * 2. **Embedded tags**, via [EmbeddedLyricsReader]: ID3 `USLT`/`SYLT`, Vorbis
 *    `LYRICS`/`UNSYNCEDLYRICS`, and the MP4 `©lyr` atom.
 *
 * Sidecars are checked first precisely because they are the user's own file: a
 * hand-corrected `.lrc` must override whatever the tagger baked into the track.
 * An empty parse from a sidecar falls through to the tags, so a stray zero-value
 * `.txt` does not mask real embedded lyrics.
 *
 * `Track.lyrics` in the database is still not populated, and that is deliberate:
 * lyrics are read from the file here, on demand, so editing a file's tags takes
 * effect on the next play. A copy in the library row would go stale, and only
 * files that went through the single-file scan path would ever have one.
 */
class LyricsRepository(
    /**
     * Injected so the two-source ordering can be unit tested without files. The
     * default reads the real file.
     */
    private val readEmbedded: (String) -> String? = { path -> EmbeddedLyricsReader.read(path) }
) {

    /**
     * Load lyrics for an audio file, or [Lyrics.EMPTY] when there are none.
     *
     * Results are cached by path: the player asks again on every track change and
     * on every toggle of the lyrics panel, and re-reading the file each time would
     * put disk I/O behind a UI interaction.
     */
    suspend fun load(audioPath: String): Lyrics = withContext(Dispatchers.IO) {
        if (audioPath.isBlank()) return@withContext Lyrics.EMPTY

        cache[audioPath]?.let { return@withContext it }

        val parsed = try {
            val sidecar = findSidecar(audioPath)
                ?.let { LyricsParser.parse(it.readText()) }
                ?.takeIf { !it.isEmpty }

            // Both sources end up as text in LyricsParser, so timed and plain
            // lyrics behave identically whichever they came from.
            sidecar ?: LyricsParser.parse(readEmbedded(audioPath))
        } catch (error: Exception) {
            // Unreadable or absurdly large file: no lyrics is the right outcome,
            // never a crash on a track change.
            Log.w(TAG, "Could not read lyrics for $audioPath: ${error.message}")
            Lyrics.EMPTY
        }

        // Negative results are cached too, so a track with no lyrics does not hit
        // the filesystem on every position tick.
        cache[audioPath] = parsed
        parsed
    }

    /** Forget cached lyrics, for example after the user adds an .lrc file. */
    fun invalidate() = cache.clear()

    private fun findSidecar(audioPath: String): File? {
        val audioFile = File(audioPath)
        val parent = audioFile.parentFile ?: return null
        val baseName = audioFile.nameWithoutExtension

        val candidates = buildList {
            // Preferred first: .lrc can be timed, .txt never is.
            for (extension in SIDECAR_EXTENSIONS) {
                add(File(parent, "$baseName.$extension"))
            }
            // Some collections keep lyrics in a subfolder beside the audio.
            for (folder in SIDECAR_FOLDERS) {
                for (extension in SIDECAR_EXTENSIONS) {
                    add(File(File(parent, folder), "$baseName.$extension"))
                }
            }
        }

        return candidates.firstOrNull { file ->
            file.isFile && file.length() in 1..MAX_SIDECAR_BYTES
        }
    }

    private companion object {
        const val TAG = "LyricsRepository"

        /** .lrc before .txt, because only .lrc carries timestamps. */
        val SIDECAR_EXTENSIONS = listOf("lrc", "txt")
        val SIDECAR_FOLDERS = listOf("Lyrics", "lyrics")

        /**
         * Lyrics are a few kilobytes. Anything larger is not lyrics, and reading
         * it would block a track change.
         */
        const val MAX_SIDECAR_BYTES = 512L * 1024L
    }

    private val cache = mutableMapOf<String, Lyrics>()
}
