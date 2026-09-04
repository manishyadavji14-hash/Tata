package com.bitperfect.android.library

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

/**
 * Reads cover art out of an audio file's tags.
 *
 * This exists because `MediaMetadataRetriever.embeddedPicture` was the app's only
 * source of embedded art, and its coverage does not match the formats the library
 * accepts. That mismatch is why artwork appeared for some tracks and not others,
 * with no obvious pattern from the outside:
 *
 * | Container | Where the cover lives | `MediaMetadataRetriever` |
 * |---|---|---|
 * | MP3 | ID3v2 `APIC` | reads it |
 * | M4A, ALAC, AAC | MP4 `covr` atom | reads it |
 * | FLAC | `PICTURE` metadata block | usually reads it |
 * | Ogg Vorbis, Opus | base64 `METADATA_BLOCK_PICTURE` comment | **misses it** |
 * | DSF, DFF | ID3v2 at an offset named in the header | **cannot parse the file** |
 *
 * So an Opus file or a DSD rip showed a placeholder however well it was tagged.
 * This reader parses the containers directly, and is tried before the platform
 * API so coverage no longer depends on it. It is a fallback chain, not a
 * replacement: if this finds nothing, `MediaMetadataRetriever` still gets a turn.
 *
 * Returns the stored image bytes untouched — JPEG or PNG as the tagger wrote them.
 * Decoding and downscaling belong to the caller, which knows what size it needs.
 *
 * Deliberately pure: no Android APIs, no logging, and every failure path returns
 * null. That is what makes all of it unit-testable from byte arrays.
 */
object EmbeddedArtworkReader {

    /** Random access to the bytes of an audio file. */
    interface ByteSource {
        val size: Long

        /** Reads up to [length] bytes from [offset]; empty when out of range. */
        fun read(offset: Long, length: Int): ByteArray
    }

    // --- Entry points ---

    /** Cover art from the file at [path], or null when there is none. */
    fun read(path: String): ByteArray? {
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null

        return try {
            RandomAccessFile(file, "r").use { handle ->
                read(FileByteSource(handle, file.length()))
            }
        } catch (error: Exception) {
            // A truncated or unreadable file has no art. Never propagate: this runs
            // during a library scan and on track changes.
            null
        }
    }

    /** Cover art from an in-memory file image. Used by the tests. */
    fun read(bytes: ByteArray): ByteArray? = read(ArrayByteSource(bytes))

    fun read(source: ByteSource): ByteArray? {
        if (source.size <= 0) return null

        val head = source.read(0, MAGIC_PROBE_SIZE)

        // DSF names the offset of its ID3v2 tag in the file header, so the tag is
        // at the end rather than the start. Checked first because the container
        // itself has to be understood to find the tag at all.
        if (startsWith(head, DSF_MAGIC)) {
            return readDsf(source)
        }

        // An ID3v2 tag can be prepended to any container. Try it, then fall through
        // to the container proper, so a FLAC carrying both is fully searched.
        var containerOffset = 0L
        if (startsWith(head, ID3_MAGIC)) {
            readId3(source, 0)?.let { return it }
            containerOffset = id3TagEnd(head)
        }

        val magic = source.read(containerOffset, MAGIC_PROBE_SIZE)
        return when {
            startsWith(magic, FLAC_MAGIC) -> readFlac(source, containerOffset)
            startsWith(magic, OGG_MAGIC) -> readOgg(source, containerOffset)
            isMp4(magic) -> readMp4(source, containerOffset)
            else -> null
        }
    }

    // --- DSF, where the tag is at the end ---

    /**
     * DSF keeps an ID3v2 tag at a byte offset given in its own header.
     *
     * Nothing else in the app could read a DSD cover, because the platform
     * extractor does not parse DSF at all.
     */
    private fun readDsf(source: ByteSource): ByteArray? {
        // "DSD " chunk: magic(4), chunk size(8), file size(8), metadata pointer(8).
        val header = source.read(0, DSF_HEADER_SIZE)
        if (header.size < DSF_HEADER_SIZE) return null

        val metadataOffset = leLong(header, 20)
        if (metadataOffset <= 0L || metadataOffset >= source.size) return null

        return readId3(source, metadataOffset)
    }

    // --- ID3v2 (MP3, AIFF, DSF) ---

    private fun id3TagEnd(header: ByteArray): Long {
        if (header.size < ID3_HEADER_SIZE) return 0L
        val declared = syncSafeInt(header, 6)
        if (declared < 0) return 0L
        val flags = header[5].toInt() and 0xFF
        val footer = if (flags and ID3_FLAG_FOOTER != 0) ID3_HEADER_SIZE else 0
        return ID3_HEADER_SIZE.toLong() + declared + footer
    }

    private fun readId3(source: ByteSource, offset: Long): ByteArray? {
        val header = source.read(offset, ID3_HEADER_SIZE)
        if (header.size < ID3_HEADER_SIZE || !startsWith(header, ID3_MAGIC)) return null

        val majorVersion = header[3].toInt() and 0xFF
        if (majorVersion < 2 || majorVersion > 4) return null

        val flags = header[5].toInt() and 0xFF
        val declaredSize = syncSafeInt(header, 6)
        if (declaredSize <= 0) return null

        val body = source.read(
            offset + ID3_HEADER_SIZE,
            minOf(declaredSize.toLong(), MAX_TAG_BYTES).toInt()
        )
        if (body.isEmpty()) return null

        val data = if (flags and ID3_FLAG_UNSYNCHRONISED != 0 && majorVersion < 4) {
            removeUnsynchronisation(body)
        } else {
            body
        }

        var cursor = 0
        if (flags and ID3_FLAG_EXTENDED_HEADER != 0) {
            cursor = skipExtendedHeader(data, majorVersion) ?: return null
        }

        return if (majorVersion == 2) {
            readId3v2Pictures(data, cursor)
        } else {
            readId3v3Pictures(data, cursor, majorVersion)
        }
    }

    private fun skipExtendedHeader(data: ByteArray, majorVersion: Int): Int? {
        if (data.size < 4) return null
        return if (majorVersion == 4) {
            syncSafeInt(data, 0).takeIf { it > 0 }
        } else {
            beInt(data, 0).takeIf { it >= 0 }?.let { 4 + it }
        }
    }

    /** ID3v2.2: three-character ids, three-byte sizes. The picture frame is `PIC`. */
    private fun readId3v2Pictures(data: ByteArray, start: Int): ByteArray? {
        var offset = start
        val candidates = mutableListOf<Picture>()

        while (offset + 6 <= data.size) {
            if (data[offset] == 0.toByte()) break // padding

            val id = ascii(data, offset, 3)
            val size = beInt24(data, offset + 3)
            val body = offset + 6
            if (size <= 0 || body + size > data.size) break

            if (id == "PIC") {
                // encoding(1), image format(3 chars), picture type(1), description.
                parseId3Picture(data, body, body + size, formatFieldLength = 3)
                    ?.let { candidates.add(it) }
            }
            offset = body + size
        }
        return pickBest(candidates)
    }

    /** ID3v2.3 and 2.4: four-character ids, ten-byte frame headers, `APIC`. */
    private fun readId3v3Pictures(data: ByteArray, start: Int, majorVersion: Int): ByteArray? {
        var offset = start
        val candidates = mutableListOf<Picture>()

        while (offset + ID3_FRAME_HEADER_SIZE <= data.size) {
            if (data[offset] == 0.toByte()) break // padding

            val id = ascii(data, offset, 4)
            val size = if (majorVersion == 4) {
                syncSafeInt(data, offset + 4)
            } else {
                beInt(data, offset + 4)
            }
            val frameFlags = data[offset + 9].toInt() and 0xFF

            var body = offset + ID3_FRAME_HEADER_SIZE
            var length = size
            if (size <= 0 || body + size > data.size) break

            val readable = frameFlags and (ID3_FRAME_COMPRESSED or ID3_FRAME_ENCRYPTED) == 0
            if (majorVersion == 4 && frameFlags and ID3_FRAME_DATA_LENGTH != 0) {
                body += 4
                length -= 4
            }

            if (id == "APIC" && readable && length > 0 && body + length <= data.size) {
                val frame = if (majorVersion == 4 && frameFlags and ID3_FRAME_UNSYNCHRONISED != 0) {
                    removeUnsynchronisation(data.copyOfRange(body, body + length))
                } else {
                    data
                }
                val from = if (frame === data) body else 0
                val to = if (frame === data) body + length else frame.size
                // MIME is a null-terminated string rather than a fixed field.
                parseId3Picture(frame, from, to, formatFieldLength = null)
                    ?.let { candidates.add(it) }
            }

            offset = body + maxOf(length, 0)
        }
        return pickBest(candidates)
    }

    /**
     * An `APIC`/`PIC` payload.
     *
     * @param formatFieldLength 3 for ID3v2.2's fixed image-format field, null for
     *   the null-terminated MIME string of 2.3 and 2.4.
     */
    private fun parseId3Picture(
        data: ByteArray,
        from: Int,
        to: Int,
        formatFieldLength: Int?
    ): Picture? {
        if (to - from < 4) return null

        val encoding = data[from].toInt() and 0xFF
        var cursor = from + 1

        cursor = if (formatFieldLength != null) {
            cursor + formatFieldLength
        } else {
            // MIME is always ISO-8859-1, whatever the description encoding is.
            val end = findTerminator(data, cursor, to, 0) ?: return null
            end + 1
        }
        if (cursor >= to) return null

        val pictureType = data[cursor].toInt() and 0xFF
        cursor++

        val descriptionEnd = findTerminator(data, cursor, to, encoding) ?: return null
        val imageStart = descriptionEnd + terminatorSize(encoding)
        if (imageStart >= to) return null

        return Picture(pictureType, data.copyOfRange(imageStart, minOf(to, data.size)))
    }

    // --- FLAC ---

    private fun readFlac(source: ByteSource, base: Long): ByteArray? {
        var offset = base + FLAC_MAGIC.size
        var blocks = 0
        val candidates = mutableListOf<Picture>()

        while (blocks < MAX_FLAC_BLOCKS) {
            val header = source.read(offset, 4)
            if (header.size < 4) break

            val flags = header[0].toInt() and 0xFF
            val isLast = flags and 0x80 != 0
            val type = flags and 0x7F
            val length = beInt24(header, 1)
            if (length < 0) break

            when (type) {
                FLAC_BLOCK_PICTURE -> {
                    val block = source.read(offset + 4, minOf(length.toLong(), MAX_IMAGE_BYTES).toInt())
                    parseFlacPicture(block)?.let { candidates.add(it) }
                }
                FLAC_BLOCK_VORBIS_COMMENT -> {
                    // Some taggers only write the base64 comment form, even in FLAC.
                    val block = source.read(offset + 4, minOf(length.toLong(), MAX_TAG_BYTES).toInt())
                    candidates.addAll(picturesFromVorbisComments(block, 0))
                }
            }

            if (isLast) break
            offset += 4L + length
            blocks++
        }
        return pickBest(candidates)
    }

    /**
     * The FLAC PICTURE structure, which is also what a base64
     * `METADATA_BLOCK_PICTURE` comment contains.
     *
     * All fields big-endian: type, MIME length + MIME, description length +
     * description, width, height, depth, colours, data length + data.
     */
    private fun parseFlacPicture(data: ByteArray): Picture? {
        if (data.size < 32) return null

        var cursor = 0
        val pictureType = beInt(data, cursor)
        if (pictureType < 0) return null
        cursor += 4

        val mimeLength = beInt(data, cursor)
        if (mimeLength < 0 || cursor + 4 + mimeLength > data.size) return null
        cursor += 4 + mimeLength

        if (cursor + 4 > data.size) return null
        val descriptionLength = beInt(data, cursor)
        if (descriptionLength < 0 || cursor + 4 + descriptionLength > data.size) return null
        cursor += 4 + descriptionLength

        // width, height, colour depth, colours used.
        cursor += 16
        if (cursor + 4 > data.size) return null

        val dataLength = beInt(data, cursor)
        cursor += 4
        if (dataLength <= 0 || cursor >= data.size) return null

        val end = minOf(cursor + dataLength, data.size)
        return Picture(pictureType, data.copyOfRange(cursor, end))
    }

    // --- Ogg (Vorbis and Opus) ---

    private fun readOgg(source: ByteSource, base: Long): ByteArray? {
        var offset = base
        var pages = 0
        var serial: Int? = null
        val stream = ByteArrayOutputStream()

        // The comment header is the second packet, but a cover pushes it across
        // many pages, so this reads further than the lyrics reader needs to.
        while (pages < MAX_OGG_PAGES && stream.size() < MAX_OGG_SCAN_BYTES) {
            val header = source.read(offset, OGG_HEADER_SIZE)
            if (header.size < OGG_HEADER_SIZE || !startsWith(header, OGG_MAGIC)) break

            val segmentCount = header[26].toInt() and 0xFF
            val segments = source.read(offset + OGG_HEADER_SIZE, segmentCount)
            if (segments.size < segmentCount) break

            var payloadSize = 0
            for (segment in segments) payloadSize += segment.toInt() and 0xFF

            val pageSerial = leInt(header, 14)
            if (serial == null) serial = pageSerial
            if (pageSerial == serial) {
                stream.write(source.read(offset + OGG_HEADER_SIZE + segmentCount, payloadSize))
            }

            offset += OGG_HEADER_SIZE + segmentCount + payloadSize
            pages++
        }

        val bytes = stream.toByteArray()
        if (bytes.isEmpty()) return null

        indexOf(bytes, VORBIS_COMMENT_SIGNATURE)?.let { at ->
            return pickBest(picturesFromVorbisComments(bytes, at + VORBIS_COMMENT_SIGNATURE.size))
        }
        indexOf(bytes, OPUS_TAGS_SIGNATURE)?.let { at ->
            return pickBest(picturesFromVorbisComments(bytes, at + OPUS_TAGS_SIGNATURE.size))
        }
        return null
    }

    /**
     * Pictures held in Vorbis comments.
     *
     * `METADATA_BLOCK_PICTURE` carries a base64-encoded FLAC PICTURE block. This is
     * how Ogg Vorbis and Opus store covers, and it is the case the platform
     * extractor misses entirely.
     */
    private fun picturesFromVorbisComments(data: ByteArray, start: Int): List<Picture> {
        var cursor = start
        if (cursor + 4 > data.size) return emptyList()

        val vendorLength = leInt(data, cursor)
        if (vendorLength < 0) return emptyList()
        cursor += 4 + vendorLength
        if (cursor + 4 > data.size) return emptyList()

        val count = leInt(data, cursor)
        cursor += 4
        if (count <= 0 || count > MAX_VORBIS_COMMENTS) return emptyList()

        val found = mutableListOf<Picture>()
        for (index in 0 until count) {
            if (cursor + 4 > data.size) break
            val length = leInt(data, cursor)
            cursor += 4
            if (length < 0 || cursor + length > data.size) break

            val separator = indexOfByte(data, '='.code.toByte(), cursor, cursor + length)
            if (separator > 0) {
                val key = String(data, cursor, separator - cursor, Charsets.US_ASCII).uppercase()
                if (key == PICTURE_COMMENT_KEY) {
                    val valueStart = separator + 1
                    val encoded = String(
                        data,
                        valueStart,
                        minOf(cursor + length, data.size) - valueStart,
                        Charsets.US_ASCII
                    )
                    decodeBase64(encoded)
                        ?.let { parseFlacPicture(it) }
                        ?.let { found.add(it) }
                }
            }
            cursor += length
        }
        return found
    }

    // --- MP4 (M4A, ALAC, AAC) ---

    private fun isMp4(magic: ByteArray): Boolean =
        magic.size >= 8 && ascii(magic, 4, 4) == "ftyp"

    private fun readMp4(source: ByteSource, base: Long): ByteArray? =
        findMp4Cover(source, base, source.size, 0)

    private fun findMp4Cover(
        source: ByteSource,
        start: Long,
        end: Long,
        depth: Int
    ): ByteArray? {
        if (depth > MAX_MP4_DEPTH) return null
        var offset = start

        while (offset + 8 <= end) {
            val header = source.read(offset, 8)
            if (header.size < 8) return null

            var boxSize = beInt(header, 0).toLong() and 0xFFFFFFFFL
            val type = ascii(header, 4, 4)
            var content = offset + 8

            when (boxSize) {
                1L -> {
                    val extended = source.read(offset + 8, 8)
                    if (extended.size < 8) return null
                    boxSize = beLong(extended, 0)
                    content = offset + 16
                }
                0L -> boxSize = end - offset
            }
            if (boxSize < 8) return null

            val boxEnd = minOf(offset + boxSize, end)
            when (type) {
                "moov", "udta", "ilst" ->
                    findMp4Cover(source, content, boxEnd, depth + 1)?.let { return it }
                "meta" -> {
                    // Four bytes of version and flags, which some writers omit.
                    findMp4Cover(source, content + 4, boxEnd, depth + 1)?.let { return it }
                    findMp4Cover(source, content, boxEnd, depth + 1)?.let { return it }
                }
                MP4_COVER_ATOM -> readMp4DataAtom(source, content, boxEnd)?.let { return it }
            }

            offset = boxEnd
        }
        return null
    }

    /** Reads the `data` child of the `covr` atom. */
    private fun readMp4DataAtom(source: ByteSource, start: Long, end: Long): ByteArray? {
        var offset = start
        while (offset + 8 <= end) {
            val header = source.read(offset, 8)
            if (header.size < 8) return null

            val boxSize = beInt(header, 0).toLong() and 0xFFFFFFFFL
            val type = ascii(header, 4, 4)
            if (boxSize < 8) return null

            if (type == "data") {
                // Four bytes of type indicator, four of locale, then the image.
                val imageStart = offset + 16
                val length = minOf(offset + boxSize, end) - imageStart
                if (length <= 0) return null
                return source.read(imageStart, minOf(length, MAX_IMAGE_BYTES).toInt())
                    .takeIf { it.isNotEmpty() }
            }
            offset += boxSize
        }
        return null
    }

    // --- Choosing between pictures ---

    private data class Picture(val type: Int, val bytes: ByteArray)

    /**
     * Prefers the front cover.
     *
     * A well-tagged file can carry a back cover, a disc label and an artist photo
     * as well; picking the first would sometimes show the wrong side of the sleeve.
     */
    private fun pickBest(candidates: List<Picture>): ByteArray? {
        if (candidates.isEmpty()) return null
        val usable = candidates.filter { it.bytes.size >= MIN_IMAGE_BYTES }
        if (usable.isEmpty()) return null

        return (usable.firstOrNull { it.type == PICTURE_TYPE_FRONT_COVER } ?: usable.first()).bytes
    }

    private fun decodeBase64(value: String): ByteArray? = try {
        // Vorbis comments wrap long values, so whitespace has to go first.
        Base64.getMimeDecoder().decode(value.trim())
    } catch (error: Exception) {
        null
    }

    // --- Text and byte helpers ---

    private fun terminatorSize(encoding: Int): Int =
        if (encoding == 1 || encoding == 2) 2 else 1

    private fun findTerminator(data: ByteArray, from: Int, to: Int, encoding: Int): Int? {
        val step = terminatorSize(encoding)
        var offset = from
        if (step == 1) {
            while (offset < to) {
                if (data[offset] == 0.toByte()) return offset
                offset++
            }
        } else {
            while (offset + 1 < to) {
                if (data[offset] == 0.toByte() && data[offset + 1] == 0.toByte()) return offset
                offset += 2
            }
        }
        return null
    }

    private fun removeUnsynchronisation(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var index = 0
        while (index < data.size) {
            val byte = data[index]
            out.write(byte.toInt())
            if (byte == 0xFF.toByte() && index + 1 < data.size && data[index + 1] == 0.toByte()) {
                index += 2
            } else {
                index++
            }
        }
        return out.toByteArray()
    }

    private fun syncSafeInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        var value = 0
        for (index in 0 until 4) {
            val byte = data[offset + index].toInt() and 0xFF
            if (byte and 0x80 != 0) return -1
            value = value shl 7 or byte
        }
        return value
    }

    private fun beInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        return (data[offset].toInt() and 0xFF shl 24) or
            (data[offset + 1].toInt() and 0xFF shl 16) or
            (data[offset + 2].toInt() and 0xFF shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun beLong(data: ByteArray, offset: Int): Long {
        if (offset + 8 > data.size) return -1
        var value = 0L
        for (index in 0 until 8) {
            value = value shl 8 or (data[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private fun beInt24(data: ByteArray, offset: Int): Int {
        if (offset + 3 > data.size) return -1
        return (data[offset].toInt() and 0xFF shl 16) or
            (data[offset + 1].toInt() and 0xFF shl 8) or
            (data[offset + 2].toInt() and 0xFF)
    }

    private fun leInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        return (data[offset].toInt() and 0xFF) or
            (data[offset + 1].toInt() and 0xFF shl 8) or
            (data[offset + 2].toInt() and 0xFF shl 16) or
            (data[offset + 3].toInt() and 0xFF shl 24)
    }

    private fun leLong(data: ByteArray, offset: Int): Long {
        if (offset + 8 > data.size) return -1
        var value = 0L
        for (index in 7 downTo 0) {
            value = value shl 8 or (data[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private fun ascii(data: ByteArray, offset: Int, length: Int): String {
        if (offset + length > data.size) return ""
        return String(data, offset, length, Charsets.ISO_8859_1)
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (index in prefix.indices) if (data[index] != prefix[index]) return false
        return true
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int? {
        if (pattern.isEmpty() || data.size < pattern.size) return null
        outer@ for (start in 0..data.size - pattern.size) {
            for (index in pattern.indices) {
                if (data[start + index] != pattern[index]) continue@outer
            }
            return start
        }
        return null
    }

    private fun indexOfByte(data: ByteArray, target: Byte, from: Int, to: Int): Int {
        var index = from
        val limit = minOf(to, data.size)
        while (index < limit) {
            if (data[index] == target) return index
            index++
        }
        return -1
    }

    // --- Byte sources ---

    private class FileByteSource(
        private val handle: RandomAccessFile,
        override val size: Long
    ) : ByteSource {
        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= size || length <= 0) return EMPTY_BYTES
            val available = minOf(length.toLong(), size - offset).toInt()
            val buffer = ByteArray(available)
            handle.seek(offset)
            handle.readFully(buffer)
            return buffer
        }
    }

    private class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
        override val size: Long get() = bytes.size.toLong()

        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= bytes.size || length <= 0) return EMPTY_BYTES
            val from = offset.toInt()
            return bytes.copyOfRange(from, minOf(from + length, bytes.size))
        }
    }

    // --- Constants ---

    private val EMPTY_BYTES = ByteArray(0)

    private val ID3_MAGIC = "ID3".toByteArray(Charsets.US_ASCII)
    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val OGG_MAGIC = "OggS".toByteArray(Charsets.US_ASCII)
    private val DSF_MAGIC = "DSD ".toByteArray(Charsets.US_ASCII)

    private val VORBIS_COMMENT_SIGNATURE =
        byteArrayOf(3) + "vorbis".toByteArray(Charsets.US_ASCII)
    private val OPUS_TAGS_SIGNATURE = "OpusTags".toByteArray(Charsets.US_ASCII)

    /** `©art` would be the artist; the cover is `covr`. */
    private const val MP4_COVER_ATOM = "covr"

    private const val PICTURE_COMMENT_KEY = "METADATA_BLOCK_PICTURE"

    /** Picture type 3 in both ID3 and FLAC. */
    private const val PICTURE_TYPE_FRONT_COVER = 3

    private const val MAGIC_PROBE_SIZE = 12
    private const val ID3_HEADER_SIZE = 10
    private const val ID3_FRAME_HEADER_SIZE = 10
    private const val DSF_HEADER_SIZE = 28

    private const val ID3_FLAG_UNSYNCHRONISED = 0x80
    private const val ID3_FLAG_EXTENDED_HEADER = 0x40
    private const val ID3_FLAG_FOOTER = 0x10

    private const val ID3_FRAME_COMPRESSED = 0x08
    private const val ID3_FRAME_ENCRYPTED = 0x04
    private const val ID3_FRAME_UNSYNCHRONISED = 0x02
    private const val ID3_FRAME_DATA_LENGTH = 0x01

    private const val FLAC_BLOCK_VORBIS_COMMENT = 4
    private const val FLAC_BLOCK_PICTURE = 6

    private const val OGG_HEADER_SIZE = 27

    /**
     * Covers are large, so a comment packet spans many Ogg pages — far more than
     * finding lyrics needs.
     */
    private const val MAX_OGG_PAGES = 64
    private const val MAX_OGG_SCAN_BYTES = 12 * 1024 * 1024

    private const val MAX_MP4_DEPTH = 6
    private const val MAX_FLAC_BLOCKS = 64
    private const val MAX_VORBIS_COMMENTS = 512

    /** Tags with a cover run to megabytes; this bounds a single read. */
    private const val MAX_TAG_BYTES = 12L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 12L * 1024 * 1024

    /** Smaller than this is a stub or a corrupt entry, not a cover. */
    private const val MIN_IMAGE_BYTES = 64
}
