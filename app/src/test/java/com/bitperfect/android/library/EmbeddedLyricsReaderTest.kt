package com.bitperfect.android.library

import com.bitperfect.android.player.LyricsParser
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for [EmbeddedLyricsReader].
 *
 * Every fixture is a synthetic tag built byte by byte below, which is the only
 * way to cover this off-device: the reader exists precisely because no Android
 * API returns embedded lyrics, so there is nothing to compare against and no
 * real files to lean on.
 *
 * The builders are written to the specifications rather than derived from the
 * reader, so a wrong assumption in the parser shows up as a failure instead of
 * being reproduced on both sides.
 */
@DisplayName("EmbeddedLyricsReader Tests")
class EmbeddedLyricsReaderTest {

    // --- ID3v2 USLT ---

    @Test
    @DisplayName("reads a plain USLT frame from an ID3v2.3 tag")
    fun usltPlainV3() {
        val tag = id3Tag(majorVersion = 3, frames = listOf(frame("USLT", usltBody("Hello\nthere"), 3)))
        assertEquals("Hello\nthere", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("reads a UTF-8 USLT frame from an ID3v2.4 tag")
    fun usltUtf8V4() {
        val text = "Grüße\nfrom größer"
        val tag = id3Tag(majorVersion = 4, frames = listOf(frame("USLT", usltBody(text, encoding = 3), 4)))
        assertEquals(text, EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("reads UTF-16 lyrics with a byte order mark")
    fun usltUtf16WithBom() {
        val text = "Line one\nLine two"
        val tag = id3Tag(majorVersion = 3, frames = listOf(frame("USLT", usltBody(text, encoding = 1), 3)))
        assertEquals(text, EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("reads big-endian UTF-16 lyrics with no byte order mark")
    fun usltUtf16BigEndian() {
        val text = "Salut\nle monde"
        val tag = id3Tag(majorVersion = 4, frames = listOf(frame("USLT", usltBody(text, encoding = 2), 4)))
        assertEquals(text, EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("skips a UTF-16 description without stopping at the zero byte inside a character")
    fun usltUtf16DescriptionTerminator() {
        // "AB" in UTF-16BE is 00 41 00 42 — three of those bytes are zero, so a
        // single-byte terminator scan would truncate the description and return
        // the wrong slice as lyrics.
        val body = usltBody("The words", encoding = 2, description = "AB")
        val tag = id3Tag(majorVersion = 4, frames = listOf(frame("USLT", body, 4)))
        assertEquals("The words", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("drops a trailing terminator that taggers append to USLT text")
    fun usltTrailingTerminator() {
        // Caught against a real file written by mutagen. USLT text runs to the end
        // of the frame and the spec does not terminate it, but writers add a
        // terminator anyway. It decodes to U+0000, which is not whitespace, so
        // trimEnd() keeps it and it reaches the screen as a stray glyph.
        for (encoding in listOf(0, 1, 2, 3)) {
            val body = usltBody("Words", encoding = encoding) + terminator(encoding)
            val tag = id3Tag(majorVersion = 4, frames = listOf(frame("USLT", body, 4)))
            assertEquals("Words", EmbeddedLyricsReader.read(tag), "encoding $encoding")
        }
    }

    @Test
    @DisplayName("drops a byte order mark left in big-endian UTF-16 text")
    fun usltStrayByteOrderMark() {
        // Declared UTF-16BE but written with a BOM anyway; decoding as big-endian
        // leaves U+FEFF at the front of the first line.
        val body = byteArrayOf(2) + "eng".toByteArray(Charsets.ISO_8859_1) +
            terminator(2) + "\uFEFFWords".toByteArray(Charsets.UTF_16BE)
        val tag = id3Tag(majorVersion = 4, frames = listOf(frame("USLT", body, 4)))
        assertEquals("Words", EmbeddedLyricsReader.read(tag))
    }

    // --- ID3v2 frame size encodings ---

    @Test
    @DisplayName("uses plain frame sizes for ID3v2.3, so later frames are still found")
    fun v3PlainFrameSizes() {
        // A 200-byte first frame. Read as syncsafe, 200 (0xC8) has its high bit
        // set and the walk would stop or mislocate the USLT frame that follows.
        val filler = frame("TXXX", ByteArray(200) { 'x'.code.toByte() }, 3)
        val lyrics = frame("USLT", usltBody("After the filler"), 3)
        val tag = id3Tag(majorVersion = 3, frames = listOf(filler, lyrics))
        assertEquals("After the filler", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("uses syncsafe frame sizes for ID3v2.4, so later frames are still found")
    fun v4SyncsafeFrameSizes() {
        val filler = frame("TXXX", ByteArray(200) { 'x'.code.toByte() }, 4)
        val lyrics = frame("USLT", usltBody("After the filler"), 4)
        val tag = id3Tag(majorVersion = 4, frames = listOf(filler, lyrics))
        assertEquals("After the filler", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("honours an ID3v2.4 data length indicator")
    fun v4DataLengthIndicator() {
        val body = usltBody("Indicated")
        // Flag 0x01 prefixes the body with four syncsafe bytes of decoded length.
        val withIndicator = syncsafe(body.size) + body
        val tag = id3Tag(
            majorVersion = 4,
            frames = listOf(frame("USLT", withIndicator, 4, flags = 0x01))
        )
        assertEquals("Indicated", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("skips a compressed frame instead of returning its raw bytes")
    fun compressedFrameSkipped() {
        val compressed = frame("USLT", usltBody("would be garbage"), 4, flags = 0x08)
        val tag = id3Tag(majorVersion = 4, frames = listOf(compressed))
        assertNull(EmbeddedLyricsReader.read(tag))
    }

    // --- ID3v2 unsynchronisation ---

    @Test
    @DisplayName("reverses whole-tag unsynchronisation in ID3v2.3")
    fun tagUnsynchronisation() {
        val original = id3Tag(majorVersion = 3, frames = listOf(frame("USLT", usltBody("A\u00FFB"), 3)))
        val body = original.copyOfRange(10, original.size)
        val unsynced = applyUnsynchronisation(body)
        val tag = id3Header(majorVersion = 3, flags = 0x80, size = unsynced.size) + unsynced

        assertEquals("A\u00FFB", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("reverses per-frame unsynchronisation in ID3v2.4")
    fun frameUnsynchronisation() {
        val body = applyUnsynchronisation(usltBody("A\u00FFB"))
        val tag = id3Tag(
            majorVersion = 4,
            frames = listOf(frame("USLT", body, 4, flags = 0x02))
        )
        assertEquals("A\u00FFB", EmbeddedLyricsReader.read(tag))
    }

    // --- ID3v2 SYLT ---

    @Test
    @DisplayName("converts a millisecond SYLT frame into LRC text")
    fun syltToLrc() {
        val body = syltBody(listOf(0L to "\nFirst", 61_500L to "\nSecond"))
        val tag = id3Tag(majorVersion = 4, frames = listOf(frame("SYLT", body, 4)))

        assertEquals("[00:00.00]First\n[01:01.50]Second", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("joins SYLT syllable fragments into whole lines")
    fun syltSyllableFragments() {
        // Fragments without a leading newline continue the current line. Emitting
        // each one separately would show one word per line.
        val body = syltBody(
            listOf(
                1_000L to "\nHold",
                1_200L to " me",
                1_400L to " close",
                5_000L to "\nNext line"
            )
        )
        val tag = id3Tag(majorVersion = 4, frames = listOf(frame("SYLT", body, 4)))

        assertEquals("[00:01.00]Hold me close\n[00:05.00]Next line", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("refuses a SYLT frame timed in MPEG frames rather than milliseconds")
    fun syltFrameTimestampsRefused() {
        // Frame counts cannot be converted without the frame rate, which the tag
        // does not carry. Wrong timings are worse than none.
        val body = syltBody(listOf(0L to "\nFirst"), timestampFormat = 1)
        val tag = id3Tag(majorVersion = 4, frames = listOf(frame("SYLT", body, 4)))

        assertNull(EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("prefers SYLT over USLT when a file carries both")
    fun syltPreferredOverUslt() {
        val tag = id3Tag(
            majorVersion = 4,
            frames = listOf(
                frame("USLT", usltBody("untimed words"), 4),
                frame("SYLT", syltBody(listOf(2_000L to "\ntimed words")), 4)
            )
        )
        assertEquals("[00:02.00]timed words", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("falls back to USLT when SYLT is unusable")
    fun usltUsedWhenSyltUnusable() {
        val tag = id3Tag(
            majorVersion = 4,
            frames = listOf(
                frame("USLT", usltBody("untimed words"), 4),
                frame("SYLT", syltBody(listOf(0L to "\nignored"), timestampFormat = 1), 4)
            )
        )
        assertEquals("untimed words", EmbeddedLyricsReader.read(tag))
    }

    // --- ID3v2.2 and extended headers ---

    @Test
    @DisplayName("reads the three-character ULT frame of an ID3v2.2 tag")
    fun id3v22Ult() {
        val body = usltBody("Old format")
        val frame = "ULT".toByteArray(Charsets.ISO_8859_1) + be24(body.size) + body
        val tag = id3Header(majorVersion = 2, flags = 0, size = frame.size) + frame

        assertEquals("Old format", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("skips an ID3v2.3 extended header")
    fun v3ExtendedHeader() {
        // 2.3: a plain size that excludes the four size bytes themselves.
        val extended = be32(6) + ByteArray(6)
        val tag = id3Tag(
            majorVersion = 3,
            flags = 0x40,
            frames = listOf(frame("USLT", usltBody("Past the header"), 3)),
            prefix = extended
        )
        assertEquals("Past the header", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("skips an ID3v2.4 extended header")
    fun v4ExtendedHeader() {
        // 2.4: a syncsafe size that counts itself.
        val extended = syncsafe(10) + ByteArray(6)
        val tag = id3Tag(
            majorVersion = 4,
            flags = 0x40,
            frames = listOf(frame("USLT", usltBody("Past the header"), 4)),
            prefix = extended
        )
        assertEquals("Past the header", EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("stops the frame walk at padding")
    fun paddingStopsWalk() {
        val tag = id3Tag(
            majorVersion = 4,
            frames = listOf(frame("TIT2", "Title".toByteArray(), 4)),
            suffix = ByteArray(64)
        )
        assertNull(EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("returns null for a tag with no lyrics frames")
    fun noLyricsFrames() {
        val tag = id3Tag(majorVersion = 4, frames = listOf(frame("TIT2", "Title".toByteArray(), 4)))
        assertNull(EmbeddedLyricsReader.read(tag))
    }

    // --- FLAC ---

    @Test
    @DisplayName("reads a LYRICS field from a FLAC Vorbis comment block")
    fun flacLyricsField() {
        val file = flacFile(listOf("TITLE=Song", "LYRICS=Sing along\nwith me"))
        assertEquals("Sing along\nwith me", EmbeddedLyricsReader.read(file))
    }

    @Test
    @DisplayName("reads an UNSYNCEDLYRICS field and matches field names case-insensitively")
    fun flacUnsyncedLyricsField() {
        val file = flacFile(listOf("unsyncedlyrics=Lower case key"))
        assertEquals("Lower case key", EmbeddedLyricsReader.read(file))
    }

    @Test
    @DisplayName("prefers the timed field when a FLAC carries both timed and plain lyrics")
    fun flacPrefersTimedField() {
        val file = flacFile(
            listOf(
                "UNSYNCEDLYRICS=plain words",
                "LYRICS=[00:03.00]timed words"
            )
        )
        assertEquals("[00:03.00]timed words", EmbeddedLyricsReader.read(file))
    }

    @Test
    @DisplayName("walks past earlier metadata blocks to reach the comment block")
    fun flacSkipsEarlierBlocks() {
        val file = flacFile(
            comments = listOf("LYRICS=Behind a picture"),
            extraBlocks = listOf(6 to ByteArray(4_000)) // a PICTURE block
        )
        assertEquals("Behind a picture", EmbeddedLyricsReader.read(file))
    }

    @Test
    @DisplayName("returns null for a FLAC with no comment block")
    fun flacWithoutComments() {
        val file = "fLaC".toByteArray() +
            byteArrayOf((0x80 or 0).toByte()) + be24(34) + ByteArray(34)
        assertNull(EmbeddedLyricsReader.read(file))
    }

    @Test
    @DisplayName("falls through an ID3 tag with no lyrics to the FLAC comment block")
    fun id3PrependedToFlac() {
        // Some taggers prepend ID3 to FLAC. Stopping at the ID3 tag would miss
        // the lyrics that are actually in the Vorbis comments.
        val id3 = id3Tag(majorVersion = 4, frames = listOf(frame("TIT2", "Title".toByteArray(), 4)))
        val file = id3 + flacFile(listOf("LYRICS=In the comments"))
        assertEquals("In the comments", EmbeddedLyricsReader.read(file))
    }

    // --- Ogg ---

    @Test
    @DisplayName("reads lyrics from an Ogg Vorbis comment header")
    fun oggVorbisComments() {
        val identification = byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23)
        val comment = byteArrayOf(3) + "vorbis".toByteArray() +
            vorbisCommentPayload(listOf("LYRICS=Ogg lyrics here"))
        val file = oggPage(identification, sequence = 0) + oggPage(comment, sequence = 1)

        assertEquals("Ogg lyrics here", EmbeddedLyricsReader.read(file))
    }

    @Test
    @DisplayName("reads lyrics from an Opus tags header")
    fun opusTags() {
        val identification = "OpusHead".toByteArray() + ByteArray(11)
        val comment = "OpusTags".toByteArray() +
            vorbisCommentPayload(listOf("LYRICS=[00:01.00]Opus lyrics"))
        val file = oggPage(identification, sequence = 0) + oggPage(comment, sequence = 1)

        assertEquals("[00:01.00]Opus lyrics", EmbeddedLyricsReader.read(file))
    }

    @Test
    @DisplayName("reassembles a comment header split across two Ogg pages")
    fun oggCommentSpanningPages() {
        // A long comment packet is lace-split across pages. Reading only one page
        // would truncate the lyrics.
        val comment = byteArrayOf(3) + "vorbis".toByteArray() +
            vorbisCommentPayload(listOf("LYRICS=" + "long line\n".repeat(60)))
        val firstHalf = comment.copyOfRange(0, 100)
        val secondHalf = comment.copyOfRange(100, comment.size)
        val file = oggPage(firstHalf, sequence = 0) + oggPage(secondHalf, sequence = 1, continued = true)

        assertEquals("long line\n".repeat(60).trimEnd(), EmbeddedLyricsReader.read(file))
    }

    @Test
    @DisplayName("ignores pages belonging to a second multiplexed stream")
    fun oggIgnoresOtherStreams() {
        val other = byteArrayOf(3) + "vorbis".toByteArray() +
            vorbisCommentPayload(listOf("LYRICS=wrong stream"))
        val wanted = byteArrayOf(3) + "vorbis".toByteArray() +
            vorbisCommentPayload(listOf("LYRICS=right stream"))
        val file = oggPage(wanted, sequence = 0, serial = 1) +
            oggPage(other, sequence = 0, serial = 2)

        assertEquals("right stream", EmbeddedLyricsReader.read(file))
    }

    // --- MP4 ---

    @Test
    @DisplayName("reads the MP4 lyrics atom")
    fun mp4LyricsAtom() {
        val file = mp4File(lyrics = "M4A lyrics")
        assertEquals("M4A lyrics", EmbeddedLyricsReader.read(file))
    }

    @Test
    @DisplayName("reads an MP4 meta box that omits its version and flags")
    fun mp4MetaWithoutVersionFlags() {
        val file = mp4File(lyrics = "Nonconformant meta", metaVersionFlags = false)
        assertEquals("Nonconformant meta", EmbeddedLyricsReader.read(file))
    }

    @Test
    @DisplayName("finds a moov box placed after the audio data")
    fun mp4MoovAfterMdat() {
        val file = mp4File(lyrics = "Trailing moov", mdatBefore = 5_000)
        assertEquals("Trailing moov", EmbeddedLyricsReader.read(file))
    }

    @Test
    @DisplayName("returns null for an MP4 with no lyrics atom")
    fun mp4WithoutLyrics() {
        assertNull(EmbeddedLyricsReader.read(mp4File(lyrics = null)))
    }

    // --- Robustness ---

    @Test
    @DisplayName("returns null rather than throwing for empty, short or unrecognised input")
    fun rejectsGarbage() {
        assertNull(EmbeddedLyricsReader.read(ByteArray(0)))
        assertNull(EmbeddedLyricsReader.read("ID3".toByteArray()))
        assertNull(EmbeddedLyricsReader.read(ByteArray(64) { 0xFF.toByte() }))
        assertNull(EmbeddedLyricsReader.read("RIFFsomething".toByteArray()))
    }

    @Test
    @DisplayName("returns null for a tag truncated mid-frame")
    fun truncatedTag() {
        val tag = id3Tag(majorVersion = 4, frames = listOf(frame("USLT", usltBody("Cut off"), 4)))
        assertNull(EmbeddedLyricsReader.read(tag.copyOfRange(0, tag.size - 6)))
    }

    @Test
    @DisplayName("returns null for a frame whose declared size overruns the tag")
    fun overlongFrameSize() {
        val header = "USLT".toByteArray() + syncsafe(9_000) + byteArrayOf(0, 0)
        val tag = id3Header(majorVersion = 4, flags = 0, size = header.size) + header
        assertNull(EmbeddedLyricsReader.read(tag))
    }

    @Test
    @DisplayName("returns null for a blank lyrics field rather than empty lyrics")
    fun blankFieldRejected() {
        assertNull(EmbeddedLyricsReader.read(flacFile(listOf("LYRICS=   \n  "))))
    }

    // --- The file entry point ---

    @Test
    @DisplayName("reads lyrics from a real file on disk")
    fun readsFromFile(@TempDir directory: File) {
        val file = File(directory, "song.flac")
        file.writeBytes(flacFile(listOf("LYRICS=[00:02.50]From disk")))

        assertEquals("[00:02.50]From disk", EmbeddedLyricsReader.read(file.absolutePath))
    }

    @Test
    @DisplayName("returns null for a missing path, a directory and an empty file")
    fun missingFile(@TempDir directory: File) {
        val empty = File(directory, "empty.mp3").apply { writeBytes(ByteArray(0)) }

        assertNull(EmbeddedLyricsReader.read(File(directory, "absent.mp3").absolutePath))
        assertNull(EmbeddedLyricsReader.read(directory.absolutePath))
        assertNull(EmbeddedLyricsReader.read(empty.absolutePath))
    }

    // --- End to end through LyricsParser ---

    @Test
    @DisplayName("SYLT lyrics reach the player as timed lines with the original timings")
    fun syltParsesAsSynced() {
        // The whole point of emitting LRC: the player's parser, not this reader,
        // decides that these are timed, so embedded and sidecar lyrics behave the
        // same way downstream.
        val body = syltBody(listOf(0L to "\nOne", 30_250L to "\nTwo", 90_000L to "\nThree"))
        val tag = id3Tag(majorVersion = 4, frames = listOf(frame("SYLT", body, 4)))

        val lyrics = LyricsParser.parse(EmbeddedLyricsReader.read(tag))

        assertTrue(lyrics.isSynced)
        assertEquals(3, lyrics.lines.size)
        assertEquals(listOf(0L, 30_250L, 90_000L), lyrics.lines.map { it.timeMs })
        assertEquals(listOf("One", "Two", "Three"), lyrics.lines.map { it.text })
        assertEquals(1, lyrics.indexAt(45_000L))
    }

    @Test
    @DisplayName("a plain USLT frame reaches the player as unsynced lines")
    fun usltParsesAsPlain() {
        val tag = id3Tag(majorVersion = 3, frames = listOf(frame("USLT", usltBody("First\nSecond"), 3)))

        val lyrics = LyricsParser.parse(EmbeddedLyricsReader.read(tag))

        assertTrue(!lyrics.isSynced)
        assertEquals(listOf("First", "Second"), lyrics.lines.map { it.text })
        assertEquals(-1, lyrics.indexAt(10_000L))
    }

    // === Fixture builders ===

    // --- ID3 ---

    private fun id3Header(majorVersion: Int, flags: Int, size: Int): ByteArray =
        "ID3".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(majorVersion.toByte(), 0, flags.toByte()) +
            syncsafe(size)

    private fun id3Tag(
        majorVersion: Int,
        frames: List<ByteArray>,
        flags: Int = 0,
        prefix: ByteArray = ByteArray(0),
        suffix: ByteArray = ByteArray(0)
    ): ByteArray {
        val body = prefix + frames.fold(ByteArray(0)) { acc, frame -> acc + frame } + suffix
        return id3Header(majorVersion, flags, body.size) + body
    }

    private fun frame(id: String, body: ByteArray, majorVersion: Int, flags: Int = 0): ByteArray {
        val size = if (majorVersion == 4) syncsafe(body.size) else be32(body.size)
        return id.toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, flags.toByte()) + body
    }

    private fun usltBody(
        text: String,
        encoding: Int = 0,
        description: String = "",
        language: String = "eng"
    ): ByteArray = byteArrayOf(encoding.toByte()) +
        language.toByteArray(Charsets.ISO_8859_1) +
        encodeText(description, encoding) + terminator(encoding) +
        encodeText(text, encoding)

    private fun syltBody(
        fragments: List<Pair<Long, String>>,
        encoding: Int = 0,
        timestampFormat: Int = 2,
        contentType: Int = 1
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encoding)
        out.write("eng".toByteArray(Charsets.ISO_8859_1))
        out.write(timestampFormat)
        out.write(contentType)
        out.write(encodeText("", encoding))
        out.write(terminator(encoding))
        for ((timeMs, text) in fragments) {
            out.write(encodeText(text, encoding))
            out.write(terminator(encoding))
            out.write(be32(timeMs.toInt()))
        }
        return out.toByteArray()
    }

    private fun encodeText(text: String, encoding: Int): ByteArray = when (encoding) {
        1 -> text.toByteArray(Charsets.UTF_16) // Java emits a big-endian BOM
        2 -> text.toByteArray(Charsets.UTF_16BE)
        3 -> text.toByteArray(Charsets.UTF_8)
        else -> text.toByteArray(Charsets.ISO_8859_1)
    }

    private fun terminator(encoding: Int): ByteArray =
        if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)

    /** Inserts the `$00` that ID3 unsynchronisation puts after every `$FF`. */
    private fun applyUnsynchronisation(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (byte in data) {
            out.write(byte.toInt() and 0xFF)
            if (byte == 0xFF.toByte()) out.write(0)
        }
        return out.toByteArray()
    }

    // --- FLAC and Vorbis comments ---

    private fun vorbisCommentPayload(comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = "test".toByteArray(Charsets.UTF_8)
        out.write(le32(vendor.size))
        out.write(vendor)
        out.write(le32(comments.size))
        for (comment in comments) {
            val bytes = comment.toByteArray(Charsets.UTF_8)
            out.write(le32(bytes.size))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun flacFile(
        comments: List<String>,
        extraBlocks: List<Pair<Int, ByteArray>> = emptyList()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))

        // STREAMINFO, always first and never last here.
        out.write(0)
        out.write(be24(34))
        out.write(ByteArray(34))

        for ((type, payload) in extraBlocks) {
            out.write(type)
            out.write(be24(payload.size))
            out.write(payload)
        }

        val payload = vorbisCommentPayload(comments)
        out.write(0x80 or 4) // last block, VORBIS_COMMENT
        out.write(be24(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    // --- Ogg ---

    private fun oggPage(
        payload: ByteArray,
        sequence: Int,
        serial: Int = 1,
        continued: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray(Charsets.US_ASCII))
        out.write(0) // version
        out.write(if (continued) 0x01 else 0x00)
        out.write(ByteArray(8)) // granule position
        out.write(le32(serial))
        out.write(le32(sequence))
        out.write(ByteArray(4)) // CRC, not checked by the reader

        // Lacing values: 255 means the packet continues into the next segment.
        val segments = mutableListOf<Int>()
        var remaining = payload.size
        while (remaining >= 255) {
            segments.add(255)
            remaining -= 255
        }
        segments.add(remaining)

        out.write(segments.size)
        for (segment in segments) out.write(segment)
        out.write(payload)
        return out.toByteArray()
    }

    // --- MP4 ---

    private fun box(type: String, payload: ByteArray): ByteArray =
        be32(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun mp4File(
        lyrics: String?,
        metaVersionFlags: Boolean = true,
        mdatBefore: Int = 0
    ): ByteArray {
        val ilstPayload = if (lyrics == null) {
            box("\u00A9nam", box("data", be32(1) + be32(0) + "Title".toByteArray()))
        } else {
            box("\u00A9lyr", box("data", be32(1) + be32(0) + lyrics.toByteArray(Charsets.UTF_8)))
        }

        val ilst = box("ilst", ilstPayload)
        val metaPayload = if (metaVersionFlags) be32(0) + ilst else ilst
        val moov = box("moov", box("udta", box("meta", metaPayload)))
        val ftyp = box("ftyp", "M4A ".toByteArray() + ByteArray(8))
        val mdat = if (mdatBefore > 0) box("mdat", ByteArray(mdatBefore)) else ByteArray(0)

        return ftyp + mdat + moov
    }

    // --- Numbers ---

    private fun syncsafe(value: Int): ByteArray = byteArrayOf(
        (value shr 21 and 0x7F).toByte(),
        (value shr 14 and 0x7F).toByte(),
        (value shr 7 and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun be24(value: Int): ByteArray = byteArrayOf(
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte()
    )
}
