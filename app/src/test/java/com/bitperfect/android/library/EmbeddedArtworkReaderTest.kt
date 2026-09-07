package com.bitperfect.android.library

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for [EmbeddedArtworkReader].
 *
 * This reader exists because `MediaMetadataRetriever.embeddedPicture` does not
 * cover the containers the library accepts, which is why album art appeared for
 * some tracks and not others with no visible pattern. The cases that matter most
 * are therefore the ones the platform misses: the base64
 * `METADATA_BLOCK_PICTURE` comment used by Ogg Vorbis and Opus, and DSF, whose
 * ID3 tag sits at an offset named in its header.
 *
 * Every fixture is built byte by byte to the specifications rather than derived
 * from the reader, so a wrong assumption shows up as a failure. The layouts were
 * additionally checked against real files written by `flac`, `oggenc` and mutagen
 * — see TESTING.md for that recipe.
 */
@DisplayName("EmbeddedArtworkReader Tests")
class EmbeddedArtworkReaderTest {

    /** Stands in for a JPEG. The reader returns bytes untouched, so content is free. */
    private val cover = ByteArray(4_096) { (it % 251).toByte() }
    private val otherImage = ByteArray(2_048) { 0x5A }

    // --- FLAC ---

    @Test
    @DisplayName("reads a FLAC PICTURE metadata block")
    fun flacPictureBlock() {
        val file = flacFile(pictureBlocks = listOf(flacPicture(cover)))

        assertArrayEquals(cover, EmbeddedArtworkReader.read(file))
    }

    @Test
    @DisplayName("reads a cover stored only as a base64 comment in a FLAC")
    fun flacCommentOnly() {
        // Some taggers write only this form, even in FLAC, and it is the form the
        // platform extractor does not look at.
        val file = flacFile(comments = listOf(pictureComment(cover)))

        assertArrayEquals(cover, EmbeddedArtworkReader.read(file))
    }

    @Test
    @DisplayName("walks past earlier metadata blocks to reach the picture")
    fun flacSkipsEarlierBlocks() {
        val file = flacFile(
            pictureBlocks = listOf(flacPicture(cover)),
            extraBlocks = listOf(1 to ByteArray(2_000)) // a PADDING block
        )

        assertArrayEquals(cover, EmbeddedArtworkReader.read(file))
    }

    @Test
    @DisplayName("prefers the front cover over other picture types")
    fun prefersFrontCover() {
        // A well-tagged album can carry a back cover and a disc label too; picking
        // the first would sometimes show the wrong side of the sleeve.
        val file = flacFile(
            pictureBlocks = listOf(
                flacPicture(otherImage, type = 4), // back cover
                flacPicture(cover, type = 3) // front cover
            )
        )

        assertArrayEquals(cover, EmbeddedArtworkReader.read(file))
    }

    @Test
    @DisplayName("returns null for a FLAC with no picture anywhere")
    fun flacWithoutPictures() {
        assertNull(EmbeddedArtworkReader.read(flacFile(comments = listOf("TITLE=Song"))))
    }

    // --- Ogg Vorbis and Opus, the cases the platform misses ---

    @Test
    @DisplayName("reads a cover from an Ogg Vorbis comment header")
    fun oggVorbis() {
        val identification = byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23)
        val comment = byteArrayOf(3) + "vorbis".toByteArray() +
            vorbisCommentPayload(listOf(pictureComment(cover)))
        val file = oggPage(identification, 0) + oggPages(comment, startSequence = 1)

        assertArrayEquals(cover, EmbeddedArtworkReader.read(file))
    }

    @Test
    @DisplayName("reads a cover from an Opus tags header")
    fun opus() {
        val identification = "OpusHead".toByteArray() + ByteArray(11)
        val comment = "OpusTags".toByteArray() +
            vorbisCommentPayload(listOf(pictureComment(cover)))
        val file = oggPage(identification, 0) + oggPages(comment, startSequence = 1)

        assertArrayEquals(cover, EmbeddedArtworkReader.read(file))
    }

    @Test
    @DisplayName("reassembles a cover split across many Ogg pages")
    fun oggAcrossManyPages() {
        // A cover is far larger than one 255-byte lacing segment, so this is the
        // normal case rather than an edge case.
        val big = ByteArray(60_000) { (it % 97).toByte() }
        val comment = byteArrayOf(3) + "vorbis".toByteArray() +
            vorbisCommentPayload(listOf(pictureComment(big)))
        val file = oggPages(comment, startSequence = 0)

        assertArrayEquals(big, EmbeddedArtworkReader.read(file))
    }

    @Test
    @DisplayName("ignores a second multiplexed stream")
    fun oggIgnoresOtherStreams() {
        val wanted = byteArrayOf(3) + "vorbis".toByteArray() +
            vorbisCommentPayload(listOf(pictureComment(cover)))
        val other = byteArrayOf(3) + "vorbis".toByteArray() +
            vorbisCommentPayload(listOf(pictureComment(otherImage)))
        val file = oggPages(wanted, startSequence = 0, serial = 1) +
            oggPages(other, startSequence = 0, serial = 2)

        assertArrayEquals(cover, EmbeddedArtworkReader.read(file))
    }

    // --- ID3v2 ---

    @Test
    @DisplayName("reads an ID3v2.3 APIC frame")
    fun id3v23Apic() {
        val tag = id3Tag(3, listOf(frame("APIC", apicBody(cover), 3)))

        assertArrayEquals(cover, EmbeddedArtworkReader.read(tag))
    }

    @Test
    @DisplayName("reads an ID3v2.4 APIC frame with syncsafe sizes")
    fun id3v24Apic() {
        // 2.4 sizes are syncsafe; reading them as plain integers mislocates every
        // frame past the first one over 127 bytes — which a cover always is.
        val tag = id3Tag(
            4,
            listOf(frame("TIT2", "Title".toByteArray(), 4), frame("APIC", apicBody(cover), 4))
        )

        assertArrayEquals(cover, EmbeddedArtworkReader.read(tag))
    }

    @Test
    @DisplayName("reads an ID3v2.2 PIC frame with its fixed format field")
    fun id3v22Pic() {
        val body = picBody(cover)
        val frameBytes = "PIC".toByteArray(Charsets.ISO_8859_1) + be24(body.size) + body
        val tag = id3Header(2, 0, frameBytes.size) + frameBytes

        assertArrayEquals(cover, EmbeddedArtworkReader.read(tag))
    }

    @Test
    @DisplayName("handles a UTF-16 APIC description without truncating at a zero byte")
    fun apicUtf16Description() {
        // "AB" in UTF-16BE contains zero bytes; a single-byte terminator scan would
        // end the description early and return the wrong slice as the image.
        val tag = id3Tag(4, listOf(frame("APIC", apicBody(cover, encoding = 2, description = "AB"), 4)))

        assertArrayEquals(cover, EmbeddedArtworkReader.read(tag))
    }

    @Test
    @DisplayName("prefers the front cover among several APIC frames")
    fun apicPrefersFrontCover() {
        val tag = id3Tag(
            4,
            listOf(
                frame("APIC", apicBody(otherImage, pictureType = 4), 4),
                frame("APIC", apicBody(cover, pictureType = 3), 4)
            )
        )

        assertArrayEquals(cover, EmbeddedArtworkReader.read(tag))
    }

    @Test
    @DisplayName("skips a compressed APIC frame rather than returning its raw bytes")
    fun compressedApicSkipped() {
        val tag = id3Tag(4, listOf(frame("APIC", apicBody(cover), 4, flags = 0x08)))

        assertNull(EmbeddedArtworkReader.read(tag))
    }

    @Test
    @DisplayName("returns null for a tag with no picture frame")
    fun id3WithoutPicture() {
        assertNull(EmbeddedArtworkReader.read(id3Tag(4, listOf(frame("TIT2", "Title".toByteArray(), 4)))))
    }

    // --- DSF, which the platform cannot parse at all ---

    @Test
    @DisplayName("reads a DSF cover from the ID3 tag its header points at")
    fun dsf() {
        // The tag is at the end of the file, at an offset named in the header —
        // which is why nothing else in the app could read a DSD cover.
        val tag = id3Tag(3, listOf(frame("APIC", apicBody(cover), 3)))
        val file = dsfFile(tag)

        assertArrayEquals(cover, EmbeddedArtworkReader.read(file))
    }

    @Test
    @DisplayName("a DSF with no metadata pointer has no cover")
    fun dsfWithoutMetadata() {
        assertNull(EmbeddedArtworkReader.read(dsfFile(ByteArray(0))))
    }

    @Test
    @DisplayName("a DSF whose metadata pointer is past the end of the file is ignored")
    fun dsfWithBadPointer() {
        val file = dsfFile(id3Tag(3, listOf(frame("APIC", apicBody(cover), 3))))
        // Corrupt the pointer to well beyond the file.
        val broken = file.copyOf()
        writeLe64(broken, 20, 9_000_000L)

        assertNull(EmbeddedArtworkReader.read(broken))
    }

    // --- MP4 ---

    @Test
    @DisplayName("reads an MP4 covr atom")
    fun mp4Covr() {
        assertArrayEquals(cover, EmbeddedArtworkReader.read(mp4File(cover)))
    }

    @Test
    @DisplayName("finds a moov box placed after the audio data")
    fun mp4TrailingMoov() {
        assertArrayEquals(cover, EmbeddedArtworkReader.read(mp4File(cover, mdatBefore = 5_000)))
    }

    @Test
    @DisplayName("reads a meta box that omits its version and flags")
    fun mp4MetaWithoutVersionFlags() {
        assertArrayEquals(cover, EmbeddedArtworkReader.read(mp4File(cover, metaVersionFlags = false)))
    }

    @Test
    @DisplayName("returns null for an MP4 with no cover atom")
    fun mp4WithoutCover() {
        assertNull(EmbeddedArtworkReader.read(mp4File(null)))
    }

    // --- Robustness ---

    @Test
    @DisplayName("returns null rather than throwing for empty or unrecognised input")
    fun rejectsGarbage() {
        assertNull(EmbeddedArtworkReader.read(ByteArray(0)))
        assertNull(EmbeddedArtworkReader.read("ID3".toByteArray()))
        assertNull(EmbeddedArtworkReader.read("fLaC".toByteArray()))
        assertNull(EmbeddedArtworkReader.read(ByteArray(128) { 0xFF.toByte() }))
        assertNull(EmbeddedArtworkReader.read("RIFFWAVEfmt ".toByteArray()))
    }

    @Test
    @DisplayName("returns null for a tag truncated mid-picture")
    fun truncatedTag() {
        val tag = id3Tag(4, listOf(frame("APIC", apicBody(cover), 4)))

        assertNull(EmbeddedArtworkReader.read(tag.copyOfRange(0, 40)))
    }

    @Test
    @DisplayName("a tiny payload is not treated as a cover")
    fun tinyPayloadRejected() {
        // A few bytes is a stub or a corrupt entry; showing it would be a broken
        // image rather than art.
        val tag = id3Tag(4, listOf(frame("APIC", apicBody(ByteArray(8)), 4)))

        assertNull(EmbeddedArtworkReader.read(tag))
    }

    @Test
    @DisplayName("unparseable base64 in a comment is ignored")
    fun badBase64Ignored() {
        val file = flacFile(comments = listOf("METADATA_BLOCK_PICTURE=not base64 at all!!"))

        assertNull(EmbeddedArtworkReader.read(file))
    }

    @Test
    @DisplayName("falls through an ID3 tag with no picture to the FLAC blocks")
    fun id3PrependedToFlac() {
        val id3 = id3Tag(4, listOf(frame("TIT2", "Title".toByteArray(), 4)))
        val file = id3 + flacFile(pictureBlocks = listOf(flacPicture(cover)))

        assertArrayEquals(cover, EmbeddedArtworkReader.read(file))
    }

    // --- The file entry point ---

    @Test
    @DisplayName("reads a cover from a real file on disk")
    fun readsFromFile(@TempDir directory: File) {
        val file = File(directory, "song.flac")
        file.writeBytes(flacFile(pictureBlocks = listOf(flacPicture(cover))))

        assertArrayEquals(cover, EmbeddedArtworkReader.read(file.absolutePath))
    }

    @Test
    @DisplayName("returns null for a missing path, a directory and an empty file")
    fun missingFile(@TempDir directory: File) {
        val empty = File(directory, "empty.flac").apply { writeBytes(ByteArray(0)) }

        assertNull(EmbeddedArtworkReader.read(File(directory, "absent.flac").absolutePath))
        assertNull(EmbeddedArtworkReader.read(directory.absolutePath))
        assertNull(EmbeddedArtworkReader.read(empty.absolutePath))
    }

    // === Fixture builders ===

    /** The FLAC PICTURE structure, also what a base64 comment contains. */
    private fun flacPicture(
        image: ByteArray,
        type: Int = 3,
        mime: String = "image/jpeg",
        description: String = ""
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(be32(type))
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        out.write(be32(mimeBytes.size)); out.write(mimeBytes)
        val descriptionBytes = description.toByteArray(Charsets.UTF_8)
        out.write(be32(descriptionBytes.size)); out.write(descriptionBytes)
        out.write(be32(300)) // width
        out.write(be32(300)) // height
        out.write(be32(24)) // colour depth
        out.write(be32(0)) // colours used
        out.write(be32(image.size)); out.write(image)
        return out.toByteArray()
    }

    private fun pictureComment(image: ByteArray, type: Int = 3): String =
        "METADATA_BLOCK_PICTURE=" +
            Base64.getEncoder().encodeToString(flacPicture(image, type = type))

    private fun vorbisCommentPayload(comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = "test".toByteArray(Charsets.UTF_8)
        out.write(le32(vendor.size)); out.write(vendor)
        out.write(le32(comments.size))
        for (comment in comments) {
            val bytes = comment.toByteArray(Charsets.UTF_8)
            out.write(le32(bytes.size)); out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun flacFile(
        comments: List<String> = emptyList(),
        pictureBlocks: List<ByteArray> = emptyList(),
        extraBlocks: List<Pair<Int, ByteArray>> = emptyList()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))

        // STREAMINFO, always first.
        out.write(0); out.write(be24(34)); out.write(ByteArray(34))

        for ((type, payload) in extraBlocks) {
            out.write(type); out.write(be24(payload.size)); out.write(payload)
        }

        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        if (comments.isNotEmpty()) blocks.add(4 to vorbisCommentPayload(comments))
        pictureBlocks.forEach { blocks.add(6 to it) }
        if (blocks.isEmpty()) blocks.add(4 to vorbisCommentPayload(listOf("TITLE=Song")))

        blocks.forEachIndexed { index, (type, payload) ->
            val isLast = index == blocks.lastIndex
            out.write(if (isLast) 0x80 or type else type)
            out.write(be24(payload.size))
            out.write(payload)
        }
        return out.toByteArray()
    }

    /**
     * Splits a packet across as many pages as its lacing requires.
     *
     * A page carries at most 255 lacing values, so 255 × 255 = 65025 bytes. A
     * cover is bigger than that, which is why this path matters: continuation
     * pages must be exactly full and must **not** carry a terminating lacing
     * value, or the page has 256 values and is malformed.
     */
    private fun oggPages(
        payload: ByteArray,
        startSequence: Int,
        serial: Int = 1
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val maxPerPage = 255 * 255
        var offset = 0
        var sequence = startSequence

        while (offset < payload.size) {
            val remaining = payload.size - offset
            val take = minOf(maxPerPage, remaining)
            val isPacketEnd = offset + take >= payload.size
            out.write(
                oggPage(
                    payload = payload.copyOfRange(offset, offset + take),
                    sequence = sequence,
                    serial = serial,
                    continued = offset > 0,
                    isPacketEnd = isPacketEnd
                )
            )
            offset += take
            sequence++
        }
        return out.toByteArray()
    }

    private fun oggPage(
        payload: ByteArray,
        sequence: Int,
        serial: Int = 1,
        continued: Boolean = false,
        isPacketEnd: Boolean = true
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray(Charsets.US_ASCII))
        out.write(0)
        out.write(if (continued) 0x01 else 0x00)
        out.write(ByteArray(8)) // granule position
        out.write(le32(serial))
        out.write(le32(sequence))
        out.write(ByteArray(4)) // CRC, not checked by the reader

        val segments = mutableListOf<Int>()
        var remaining = payload.size
        while (remaining >= 255) { segments.add(255); remaining -= 255 }
        // The final short value is what marks the end of a packet. A continuation
        // page must not have one, and its length is always a multiple of 255.
        if (isPacketEnd) segments.add(remaining)

        out.write(segments.size)
        segments.forEach { out.write(it) }
        out.write(payload)
        return out.toByteArray()
    }

    // --- ID3 ---

    private fun id3Header(majorVersion: Int, flags: Int, size: Int): ByteArray =
        "ID3".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(majorVersion.toByte(), 0, flags.toByte()) +
            syncsafe(size)

    private fun id3Tag(majorVersion: Int, frames: List<ByteArray>, flags: Int = 0): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
        return id3Header(majorVersion, flags, body.size) + body
    }

    private fun frame(id: String, body: ByteArray, majorVersion: Int, flags: Int = 0): ByteArray {
        val size = if (majorVersion == 4) syncsafe(body.size) else be32(body.size)
        return id.toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, flags.toByte()) + body
    }

    /** `APIC`: encoding, MIME (null-terminated), picture type, description, image. */
    private fun apicBody(
        image: ByteArray,
        encoding: Int = 0,
        mime: String = "image/jpeg",
        pictureType: Int = 3,
        description: String = ""
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encoding)
        out.write(mime.toByteArray(Charsets.ISO_8859_1)); out.write(0)
        out.write(pictureType)
        out.write(encodeText(description, encoding)); out.write(terminator(encoding))
        out.write(image)
        return out.toByteArray()
    }

    /** `PIC`: encoding, a fixed three-character format, picture type, description. */
    private fun picBody(
        image: ByteArray,
        encoding: Int = 0,
        format: String = "JPG",
        pictureType: Int = 3
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encoding)
        out.write(format.toByteArray(Charsets.ISO_8859_1))
        out.write(pictureType)
        out.write(terminator(encoding)) // empty description
        out.write(image)
        return out.toByteArray()
    }

    private fun encodeText(text: String, encoding: Int): ByteArray = when (encoding) {
        1 -> text.toByteArray(Charsets.UTF_16)
        2 -> text.toByteArray(Charsets.UTF_16BE)
        3 -> text.toByteArray(Charsets.UTF_8)
        else -> text.toByteArray(Charsets.ISO_8859_1)
    }

    private fun terminator(encoding: Int): ByteArray =
        if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)

    // --- DSF ---

    /**
     * "DSD " chunk: magic, chunk size, total file size, metadata pointer — then the
     * format and data chunks, then the ID3 tag the pointer names.
     */
    private fun dsfFile(id3: ByteArray): ByteArray {
        val fmtChunk = "fmt ".toByteArray(Charsets.US_ASCII) + le64(52) + ByteArray(36)
        val audio = ByteArray(1_024)
        val dataChunk = "data".toByteArray(Charsets.US_ASCII) + le64(12L + audio.size) + audio
        val metadataOffset = 28L + fmtChunk.size + dataChunk.size

        val out = ByteArrayOutputStream()
        out.write("DSD ".toByteArray(Charsets.US_ASCII))
        out.write(le64(28))
        out.write(le64(metadataOffset + id3.size))
        out.write(le64(if (id3.isEmpty()) 0L else metadataOffset))
        out.write(fmtChunk)
        out.write(dataChunk)
        out.write(id3)
        return out.toByteArray()
    }

    // --- MP4 ---

    private fun box(type: String, payload: ByteArray): ByteArray =
        be32(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun mp4File(
        image: ByteArray?,
        metaVersionFlags: Boolean = true,
        mdatBefore: Int = 0
    ): ByteArray {
        val ilstPayload = if (image == null) {
            box("\u00A9nam", box("data", be32(1) + be32(0) + "Title".toByteArray()))
        } else {
            // Type indicator 13 is JPEG.
            box("covr", box("data", be32(13) + be32(0) + image))
        }
        val meta = box("meta", if (metaVersionFlags) be32(0) + box("ilst", ilstPayload) else box("ilst", ilstPayload))
        val moov = box("moov", box("udta", meta))
        val ftyp = box("ftyp", "M4A ".toByteArray() + ByteArray(8))
        val mdat = if (mdatBefore > 0) box("mdat", ByteArray(mdatBefore)) else ByteArray(0)
        return ftyp + mdat + moov
    }

    // --- Numbers ---

    private fun syncsafe(value: Int) = byteArrayOf(
        (value shr 21 and 0x7F).toByte(),
        (value shr 14 and 0x7F).toByte(),
        (value shr 7 and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )

    private fun be32(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(),
        (value ushr 8).toByte(), value.toByte()
    )

    private fun be24(value: Int) = byteArrayOf(
        (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
    )

    private fun le32(value: Int) = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(),
        (value ushr 16).toByte(), (value ushr 24).toByte()
    )

    private fun le64(value: Long) = ByteArray(8) { ((value shr (it * 8)) and 0xFF).toByte() }

    private fun writeLe64(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            target[offset + index] = ((value shr (index * 8)) and 0xFF).toByte()
        }
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray?) {
        assertEquals(expected.size, actual?.size, "wrong number of bytes")
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual)
    }
}
