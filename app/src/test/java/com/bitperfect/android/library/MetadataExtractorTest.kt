package com.bitperfect.android.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Unit tests for MetadataExtractor.
 *
 * Tests format detection, extension mapping, and supported format list.
 */
@DisplayName("MetadataExtractor Tests")
class MetadataExtractorTest {

    private lateinit var extractor: MetadataExtractor

    @BeforeEach
    fun setUp() {
        extractor = MetadataExtractor()
    }

    // === Supported Extensions ===

    @Test
    @DisplayName("WAV extensions are supported")
    fun wavSupported() {
        assertTrue(MetadataExtractor.isSupportedExtension("wav"))
        assertTrue(MetadataExtractor.isSupportedExtension("wave"))
    }

    @Test
    @DisplayName("FLAC extension is supported")
    fun flacSupported() {
        assertTrue(MetadataExtractor.isSupportedExtension("flac"))
    }

    @Test
    @DisplayName("DSF extension is supported")
    fun dsfSupported() {
        assertTrue(MetadataExtractor.isSupportedExtension("dsf"))
    }

    @Test
    @DisplayName("DFF extension is supported")
    fun dffSupported() {
        assertTrue(MetadataExtractor.isSupportedExtension("dff"))
    }

    @Test
    @DisplayName("AIFF extensions are supported")
    fun aiffSupported() {
        assertTrue(MetadataExtractor.isSupportedExtension("aiff"))
        assertTrue(MetadataExtractor.isSupportedExtension("aif"))
    }

    @Test
    @DisplayName("ALAC/M4A extensions are supported")
    fun alacSupported() {
        assertTrue(MetadataExtractor.isSupportedExtension("alac"))
        assertTrue(MetadataExtractor.isSupportedExtension("m4a"))
    }

    @Test
    @DisplayName("MP3 extension is supported")
    fun mp3Supported() {
        assertTrue(MetadataExtractor.isSupportedExtension("mp3"))
    }

    @Test
    @DisplayName("OGG/Opus extensions are supported")
    fun oggOpusSupported() {
        assertTrue(MetadataExtractor.isSupportedExtension("ogg"))
        assertTrue(MetadataExtractor.isSupportedExtension("oga"))
        assertTrue(MetadataExtractor.isSupportedExtension("opus"))
    }

    @Test
    @DisplayName("WavPack extension is supported")
    fun wavPackSupported() {
        assertTrue(MetadataExtractor.isSupportedExtension("wv"))
    }

    @Test
    @DisplayName("Unknown extensions are not supported")
    fun unknownNotSupported() {
        assertFalse(MetadataExtractor.isSupportedExtension("txt"))
        assertFalse(MetadataExtractor.isSupportedExtension("jpg"))
        assertFalse(MetadataExtractor.isSupportedExtension("pdf"))
        assertFalse(MetadataExtractor.isSupportedExtension("exe"))
    }

    @Test
    @DisplayName("Extension check is case insensitive")
    fun caseInsensitive() {
        assertTrue(MetadataExtractor.isSupportedExtension("WAV"))
        assertTrue(MetadataExtractor.isSupportedExtension("Flac"))
        assertTrue(MetadataExtractor.isSupportedExtension("DSF"))
        assertTrue(MetadataExtractor.isSupportedExtension("Mp3"))
    }

    // === Format Detection from Path ===

    @Test
    @DisplayName("Extract detects WAV format from path")
    fun detectWavFormat() {
        val metadata = extractor.extract("/music/album/song.wav")
        assertNotNull(metadata)
        assertEquals("WAV", metadata!!.format)
    }

    @Test
    @DisplayName("Extract detects FLAC format from path")
    fun detectFlacFormat() {
        val metadata = extractor.extract("/music/album/song.flac")
        assertNotNull(metadata)
        assertEquals("FLAC", metadata!!.format)
    }

    @Test
    @DisplayName("Extract detects DSF format from path")
    fun detectDsfFormat() {
        val metadata = extractor.extract("/music/dsd/album.dsf")
        assertNotNull(metadata)
        assertEquals("DSF", metadata!!.format)
    }

    @Test
    @DisplayName("Extract detects DFF format from path")
    fun detectDffFormat() {
        val metadata = extractor.extract("/music/dsd/album.dff")
        assertNotNull(metadata)
        assertEquals("DFF", metadata!!.format)
    }

    @Test
    @DisplayName("Extract detects AIFF format from path")
    fun detectAiffFormat() {
        val metadata = extractor.extract("/music/album/song.aiff")
        assertNotNull(metadata)
        assertEquals("AIFF", metadata!!.format)
    }

    @Test
    @DisplayName("Extract detects MP3 format from path")
    fun detectMp3Format() {
        val metadata = extractor.extract("/music/album/song.mp3")
        assertNotNull(metadata)
        assertEquals("MP3", metadata!!.format)
    }

    @Test
    @DisplayName("Extract detects APE format from path")
    fun detectApeFormat() {
        val metadata = extractor.extract("/music/album/song.ape")
        assertNotNull(metadata)
        assertEquals("APE", metadata!!.format)
    }

    @Test
    @DisplayName("Extract detects OGG format from path")
    fun detectOggFormat() {
        val metadata = extractor.extract("/music/album/song.ogg")
        assertNotNull(metadata)
        assertEquals("OGG", metadata!!.format)
    }

    @Test
    @DisplayName("Extract detects Opus format from path")
    fun detectOpusFormat() {
        val metadata = extractor.extract("/music/album/song.opus")
        assertNotNull(metadata)
        assertEquals("OPUS", metadata!!.format)
    }

    @Test
    @DisplayName("Extract returns null for unsupported format")
    fun unsupportedFormatReturnsNull() {
        val metadata = extractor.extract("/document/file.txt")
        assertNull(metadata)
    }

    // === Title Extraction from Path ===

    @Test
    @DisplayName("Title extracted from filename")
    fun titleFromFilename() {
        val metadata = extractor.extract("/music/album/My Song.flac")
        assertNotNull(metadata)
        assertEquals("My Song", metadata!!.title)
    }

    @Test
    @DisplayName("Title handles nested paths")
    fun titleNestedPath() {
        val metadata = extractor.extract("/storage/emulated/0/Music/Artist/Album/01 Track.wav")
        assertNotNull(metadata)
        assertEquals("01 Track", metadata!!.title)
    }

    // === Supported Extensions List ===

    @Test
    @DisplayName("SUPPORTED_EXTENSIONS contains all expected formats")
    fun supportedExtensionsList() {
        val expected = setOf(
            "wav", "wave", "flac", "dsf", "dff", "aiff", "aif",
            "alac", "m4a", "ape", "mp3", "aac", "ogg", "oga", "opus", "wv"
        )
        assertEquals(expected, MetadataExtractor.SUPPORTED_EXTENSIONS)
    }

    @Test
    @DisplayName("SUPPORTED_EXTENSIONS has expected count")
    fun supportedExtensionsCount() {
        // Should have at least 16 supported extensions
        assertTrue(MetadataExtractor.SUPPORTED_EXTENSIONS.size >= 16)
    }

    // === Build Track ===

    @Test
    @DisplayName("buildTrack creates valid Track entity")
    fun buildTrackFromMetadata() {
        val metadata = MetadataExtractor.Metadata(
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            genre = "Classical",
            sampleRate = 96000,
            bitDepth = 24,
            channels = 2,
            format = "FLAC",
            duration = 300000 // 5 minutes
        )

        val track = extractor.buildTrack("/music/test.flac", metadata, albumId = 42)

        assertEquals("/music/test.flac", track.path)
        assertEquals("Test Song", track.title)
        assertEquals("Test Artist", track.artist)
        assertEquals(42L, track.albumId)
        assertEquals("Test Album", track.albumTitle)
        assertEquals("Classical", track.genre)
        assertEquals(96000, track.sampleRate)
        assertEquals(24, track.bitDepth)
        assertEquals(2, track.channels)
        assertEquals("FLAC", track.format)
        assertEquals(300000, track.duration)
    }

    @Test
    @DisplayName("buildTrack uses filename as title when metadata title is empty")
    fun buildTrackFallbackTitle() {
        val metadata = MetadataExtractor.Metadata(title = "", format = "WAV")

        val track = extractor.buildTrack("/music/My Great Song.wav", metadata)
        assertEquals("My Great Song", track.title)
    }
}
