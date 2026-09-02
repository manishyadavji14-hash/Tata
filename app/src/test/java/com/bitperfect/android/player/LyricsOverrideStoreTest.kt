package com.bitperfect.android.player

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * User-supplied lyrics, and the record that the user removed lyrics.
 *
 * The second state is the one worth testing hardest. "Remove lyrics" on a file
 * with an embedded `USLT` frame has to be remembered, or the lyrics reappear on
 * the next play because they are still inside the file.
 */
@DisplayName("LyricsOverrideStore Tests")
class LyricsOverrideStoreTest {

    private val audio = "/storage/emulated/0/Music/song.flac"

    @Test
    @DisplayName("saved lyrics come back")
    fun saveAndRead(@TempDir directory: File) {
        val store = LyricsOverrideStore(directory)

        assertTrue(store.save(audio, "[00:01.00]First line"))
        assertEquals("[00:01.00]First line", store.read(audio))
    }

    @Test
    @DisplayName("a file with no override reads as none")
    fun noOverride(@TempDir directory: File) {
        val store = LyricsOverrideStore(directory)

        assertNull(store.read(audio))
        assertFalse(store.isSuppressed(audio))
    }

    @Test
    @DisplayName("removal is recorded, so the file's own lyrics stay hidden")
    fun suppression(@TempDir directory: File) {
        val store = LyricsOverrideStore(directory)

        assertTrue(store.suppress(audio))
        assertTrue(store.isSuppressed(audio))
        assertNull(store.read(audio))
    }

    @Test
    @DisplayName("saving after a removal brings lyrics back")
    fun saveClearsSuppression(@TempDir directory: File) {
        val store = LyricsOverrideStore(directory)

        store.suppress(audio)
        store.save(audio, "New words")

        assertFalse(store.isSuppressed(audio))
        assertEquals("New words", store.read(audio))
    }

    @Test
    @DisplayName("removing discards a previous override")
    fun suppressClearsOverride(@TempDir directory: File) {
        val store = LyricsOverrideStore(directory)

        store.save(audio, "Old words")
        store.suppress(audio)

        assertNull(store.read(audio))
        assertTrue(store.isSuppressed(audio))
    }

    @Test
    @DisplayName("clearing restores the file's own lyrics as the source")
    fun clearRemovesBoth(@TempDir directory: File) {
        val store = LyricsOverrideStore(directory)

        store.save(audio, "Words")
        store.clear(audio)
        assertNull(store.read(audio))
        assertFalse(store.isSuppressed(audio))

        store.suppress(audio)
        store.clear(audio)
        assertFalse(store.isSuppressed(audio))
    }

    @Test
    @DisplayName("blank lyrics are refused rather than stored as empty")
    fun blankNotSaved(@TempDir directory: File) {
        // Blank means "remove", which is suppress()'s job. Storing it as an
        // override would read back as no override and quietly do nothing.
        val store = LyricsOverrideStore(directory)

        assertFalse(store.save(audio, "   \n\t "))
        assertNull(store.read(audio))
    }

    @Test
    @DisplayName("each file gets its own lyrics")
    fun perFileIsolation(@TempDir directory: File) {
        val store = LyricsOverrideStore(directory)
        val other = "/storage/emulated/0/Music/other.flac"

        store.save(audio, "First song")
        store.save(other, "Second song")
        store.suppress("/storage/emulated/0/Music/third.flac")

        assertEquals("First song", store.read(audio))
        assertEquals("Second song", store.read(other))
        assertFalse(store.isSuppressed(audio))
    }

    @Test
    @DisplayName("paths that differ only late in the string do not collide")
    fun longSimilarPaths(@TempDir directory: File) {
        // Names are hashed, so this checks the hash covers the whole path rather
        // than a truncated prefix.
        val store = LyricsOverrideStore(directory)
        val base = "/storage/emulated/0/Music/Some Long Artist Name/Album Name/track-"

        store.save("$base 01.flac", "one")
        store.save("$base 02.flac", "two")

        assertEquals("one", store.read("$base 01.flac"))
        assertEquals("two", store.read("$base 02.flac"))
    }

    @Test
    @DisplayName("a blank path is rejected without touching the directory")
    fun blankPath(@TempDir directory: File) {
        val store = LyricsOverrideStore(directory)

        assertFalse(store.save("", "Words"))
        assertFalse(store.suppress(""))
        assertNull(store.read(""))
        assertFalse(store.isSuppressed(""))
    }

    @Test
    @DisplayName("writes create the directory if it does not exist yet")
    fun createsDirectoryOnDemand(@TempDir parent: File) {
        // The store is constructed at app start with a directory that has never
        // been written to, so this is the first-run path.
        val store = LyricsOverrideStore(File(parent, "lyrics"))

        assertTrue(store.save(audio, "Words"))
        assertEquals("Words", store.read(audio))
    }

    @Test
    @DisplayName("an unreadable store reports no lyrics instead of throwing")
    fun unreadableStoreIsSafe(@TempDir parent: File) {
        // A file where the directory should be: every read must still answer, since
        // this runs on a track change.
        val notADirectory = File(parent, "lyrics").apply { writeText("not a directory") }
        val store = LyricsOverrideStore(notADirectory)

        assertNull(store.read(audio))
        assertFalse(store.isSuppressed(audio))
        assertFalse(store.save(audio, "Words"))
    }
}
