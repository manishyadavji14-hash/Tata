package com.bitperfect.android.player

import android.util.Log
import com.bitperfect.android.library.EmbeddedLyricsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds lyrics for a track, from three sources in order of authority.
 *
 * 1. **What the user typed**, via [LyricsOverrideStore] — including an explicit
 *    "remove lyrics", which wins over everything and shows none.
 * 2. **Sidecar files.** `song.flac` is matched by `song.lrc`, `song.txt` or the
 *    same name in a `Lyrics/` subfolder. This is how synced lyrics are normally
 *    distributed, and it lets the user add or correct lyrics without the app
 *    needing to write tags.
 * 3. **Embedded tags**, via [EmbeddedLyricsReader]: ID3 `USLT`/`SYLT`, Vorbis
 *    `LYRICS`/`UNSYNCEDLYRICS`, and the MP4 `©lyr` atom.
 *
 * The order is "most deliberate first". Something the user pasted in beats a
 * sidecar someone shipped with the album, which in turn beats whatever a tagger
 * baked into the file — a hand-corrected `.lrc` must not be overruled by wrong
 * embedded timings. An empty parse at any level falls through to the next, so a
 * stray zero-value `.txt` does not mask real embedded lyrics.
 *
 * `Track.lyrics` in the database is still not populated, and that is deliberate:
 * lyrics are read from the file here, on demand, so editing a file's tags takes
 * effect on the next play. A copy in the library row would go stale, and only
 * files that went through the single-file scan path would ever have one.
 */
class LyricsRepository(
    /**
     * User-supplied lyrics and removals. Null means the feature is unavailable —
     * the app-private directory could not be resolved — and the repository falls
     * back to sidecars and tags rather than failing.
     */
    private val overrides: LyricsOverrideStore? = null,
    /**
     * Injected so the source ordering can be unit tested without files. The
     * default reads the real file.
     *
     * Deliberately last, so `LyricsRepository { ... }` keeps binding the trailing
     * lambda to this and not to a parameter added later.
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
            // An explicit removal is an answer, not an absence: stop here rather
            // than falling through and re-reading the lyrics out of the file the
            // user just hid them from.
            if (overrides?.isSuppressed(audioPath) == true) {
                Lyrics.EMPTY
            } else {
                val userSupplied = overrides?.read(audioPath)
                    ?.let { LyricsParser.parse(it) }
                    ?.takeIf { !it.isEmpty }

                val sidecar = userSupplied ?: findSidecar(audioPath)
                    ?.let { LyricsParser.parse(it.readText()) }
                    ?.takeIf { !it.isEmpty }

                // Every source ends up as text in LyricsParser, so timed and
                // plain lyrics behave identically whichever they came from.
                sidecar ?: LyricsParser.parse(readEmbedded(audioPath))
            }
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
