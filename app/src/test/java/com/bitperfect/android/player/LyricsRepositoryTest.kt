package com.bitperfect.android.player

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for [LyricsRepository]'s two-source resolution.
 *
 * The embedded reader is injected, so these cover the ordering and caching rules
 * without needing tagged audio files. [EmbeddedLyricsReaderTest] covers the tag
 * parsing itself.
 *
 * Only paths that succeed are exercised: the failure branch calls `android.util.Log`,
 * which is not mocked in a JVM unit test.
 */
@DisplayName("LyricsRepository Tests")
class LyricsRepositoryTest {

    @Test
    @DisplayName("uses a sidecar file in preference to embedded tags")
    fun sidecarWinsOverEmbedded() {
        val audio = audioFileWithSidecar("sidecar words")
        val repository = LyricsRepository { "embedded words" }

        val lyrics = runBlocking { repository.load(audio) }

        assertEquals(listOf("sidecar words"), lyrics.lines.map { it.text })
    }

    @Test
    @DisplayName("falls back to embedded tags when there is no sidecar")
    fun embeddedUsedWithoutSidecar(@TempDir directory: File) {
        val audio = File(directory, "song.mp3").apply { writeBytes(ByteArray(4)) }.absolutePath
        val repository = LyricsRepository { "embedded words" }

        val lyrics = runBlocking { repository.load(audio) }

        assertEquals(listOf("embedded words"), lyrics.lines.map { it.text })
    }

    @Test
    @DisplayName("falls through to embedded tags when a sidecar parses to nothing")
    fun blankSidecarFallsThrough() {
        // A stray whitespace-only .txt must not mask real embedded lyrics.
        val audio = audioFileWithSidecar("   \n\t\n  ")
        val repository = LyricsRepository { "embedded words" }

        val lyrics = runBlocking { repository.load(audio) }

        assertEquals(listOf("embedded words"), lyrics.lines.map { it.text })
    }

    @Test
    @DisplayName("keeps timing information from embedded LRC text")
    fun embeddedTimedLyrics(@TempDir directory: File) {
        val audio = File(directory, "song.flac").apply { writeBytes(ByteArray(4)) }.absolutePath
        val repository = LyricsRepository { "[00:01.00]One\n[00:04.50]Two" }

        val lyrics = runBlocking { repository.load(audio) }

        assertTrue(lyrics.isSynced)
        assertEquals(listOf(1_000L, 4_500L), lyrics.lines.map { it.timeMs })
    }

    @Test
    @DisplayName("returns empty lyrics when neither source has any")
    fun noSources(@TempDir directory: File) {
        val audio = File(directory, "song.wav").apply { writeBytes(ByteArray(4)) }.absolutePath
        val repository = LyricsRepository { null }

        assertTrue(runBlocking { repository.load(audio) }.isEmpty)
    }

    @Test
    @DisplayName("returns empty lyrics for a blank path without touching the reader")
    fun blankPath() {
        var reads = 0
        val repository = LyricsRepository { reads++; "embedded words" }

        assertTrue(runBlocking { repository.load("") }.isEmpty)
        assertEquals(0, reads)
    }

    @Test
    @DisplayName("reads the file once and serves later requests from the cache")
    fun cachesResults(@TempDir directory: File) {
        // The player asks again on every track change and every panel toggle, so a
        // second read here would be disk I/O behind a UI interaction.
        val audio = File(directory, "song.mp3").apply { writeBytes(ByteArray(4)) }.absolutePath
        var reads = 0
        val repository = LyricsRepository { reads++; "embedded words" }

        runBlocking {
            repository.load(audio)
            repository.load(audio)
            repository.load(audio)
        }

        assertEquals(1, reads)
    }

    @Test
    @DisplayName("caches the absence of lyrics too")
    fun cachesNegativeResults(@TempDir directory: File) {
        val audio = File(directory, "song.mp3").apply { writeBytes(ByteArray(4)) }.absolutePath
        var reads = 0
        val repository = LyricsRepository { reads++; null }

        runBlocking {
            repository.load(audio)
            repository.load(audio)
        }

        assertEquals(1, reads)
    }

    @Test
    @DisplayName("re-reads after the cache is invalidated")
    fun invalidateForcesReread(@TempDir directory: File) {
        val audio = File(directory, "song.mp3").apply { writeBytes(ByteArray(4)) }.absolutePath
        var reads = 0
        val repository = LyricsRepository { reads++; "embedded words" }

        runBlocking {
            repository.load(audio)
            repository.invalidate()
            repository.load(audio)
        }

        assertEquals(2, reads)
    }

    @Test
    @DisplayName("finds a sidecar in a Lyrics subfolder")
    fun sidecarInSubfolder(@TempDir directory: File) {
        val audio = File(directory, "song.flac").apply { writeBytes(ByteArray(4)) }
        val folder = File(directory, "Lyrics").apply { mkdirs() }
        File(folder, "song.lrc").writeText("[00:02.00]Subfolder words")

        val repository = LyricsRepository { "embedded words" }
        val lyrics = runBlocking { repository.load(audio.absolutePath) }

        assertTrue(lyrics.isSynced)
        assertEquals(listOf("Subfolder words"), lyrics.lines.map { it.text })
        assertFalse(lyrics.isEmpty)
    }

    @TempDir
    lateinit var sidecarDirectory: File

    /** Writes `song.flac` plus a `song.lrc` holding [sidecarText]. */
    private fun audioFileWithSidecar(sidecarText: String): String {
        val audio = File(sidecarDirectory, "song.flac")
        audio.writeBytes(ByteArray(4))
        File(sidecarDirectory, "song.lrc").writeText(sidecarText)
        return audio.absolutePath
    }
}
