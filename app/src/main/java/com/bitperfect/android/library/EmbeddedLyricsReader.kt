package com.bitperfect.android.library

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads lyrics stored inside an audio file's own tags.
 *
 * This exists because `MediaMetadataRetriever` exposes no lyrics field at all —
 * there is no `METADATA_KEY_LYRICS` — so the only way to reach embedded lyrics is
 * to walk the tag structures by hand. That is what this object does, for the four
 * places lyrics are actually found in the wild:
 *
 * | Container | Where lyrics live |
 * |---|---|
 * | MP3, AIFF, DSF | ID3v2 `USLT` (unsynchronised) and `SYLT` (synchronised) |
 * | FLAC | `VORBIS_COMMENT` metadata block, `LYRICS` / `UNSYNCEDLYRICS` |
 * | Ogg Vorbis, Opus | comment header packet, same field names |
 * | M4A, ALAC, AAC | `moov.udta.meta.ilst.©lyr` |
 *
 * **Everything is returned as LRC text**, even `SYLT`, whose timings are binary.
 * Synthesising LRC means [com.bitperfect.android.player.LyricsParser] stays the
 * single place that decides whether lyrics are timed or plain, so embedded and
 * sidecar lyrics cannot drift apart in how they behave.
 *
 * Deliberately pure: it touches no Android API and no logging, reads through a
 * [ByteSource] it does not own, and returns null rather than throwing. That is
 * what makes the whole of it unit-testable on the JVM from byte arrays, with no
 * device and no temp files.
 */
object EmbeddedLyricsReader {

    /**
     * Random access to the bytes of an audio file.
     *
     * An interface rather than a `File` because MP4 keeps its tags in a `moov`
     * box that may sit after the audio data, so the reader has to seek, and
     * because tests supply byte arrays.
     */
    interface ByteSource {
        val size: Long

        /**
         * Reads up to [length] bytes from [offset]. Returns a shorter array only
         * at end of input, and an empty array if [offset] is out of range.
         */
        fun read(offset: Long, length: Int): ByteArray
    }

    // --- Entry points ---

    /** Reads lyrics from the file at [path], or null when there are none. */
    fun read(path: String): String? {
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null

        return try {
            RandomAccessFile(file, "r").use { handle ->
                read(FileByteSource(handle, file.length()))
            }
        } catch (error: Exception) {
            // A truncated, locked or unreadable file means no lyrics. It must
            // never propagate: this runs on a track change.
            null
        }
    }

    /** Reads lyrics from an in-memory file image. Used by the tests. */
    fun read(bytes: ByteArray): String? = read(ArrayByteSource(bytes))

    fun read(source: ByteSource): String? {
        if (source.size <= 0) return null

        // An ID3v2 tag can be prepended to any container, including FLAC. Try it
        // first, then look for a container signature after it, so a FLAC with
        // both an ID3 tag and a Vorbis comment yields whichever actually has the
        // lyrics instead of only ever the first one.
        var containerOffset = 0L
        val head = source.read(0, ID3_HEADER_SIZE)
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

    // --- ID3v2 (MP3, AIFF, DSF) ---

    /**
     * Total size of the ID3v2 tag at the start of [header], including its own
     * header and footer, i.e. the offset at which the audio container begins.
     */
    private fun id3TagEnd(header: ByteArray): Long {
        if (header.size < ID3_HEADER_SIZE) return 0L
        val declaredSize = syncSafeInt(header, 6)
        if (declaredSize < 0) return 0L // malformed size: do not seek on a guess
        val flags = header[5].toInt() and 0xFF
        val footer = if (flags and ID3_FLAG_FOOTER != 0) ID3_HEADER_SIZE else 0
        return ID3_HEADER_SIZE.toLong() + declaredSize + footer
    }

    private fun readId3(source: ByteSource, offset: Long): String? {
        val header = source.read(offset, ID3_HEADER_SIZE)
        if (header.size < ID3_HEADER_SIZE || !startsWith(header, ID3_MAGIC)) return null

        val majorVersion = header[3].toInt() and 0xFF
        if (majorVersion < 2 || majorVersion > 4) return null

        val flags = header[5].toInt() and 0xFF
        val declaredSize = syncSafeInt(header, 6)
        if (declaredSize <= 0) return null

        // Tags carrying cover art run to several megabytes. Read a bounded prefix
        // rather than the whole thing: lyrics frames are tiny, and pulling an
        // unbounded amount into memory behind a track change is not acceptable.
        val body = source.read(
            offset + ID3_HEADER_SIZE,
            minOf(declaredSize, MAX_ID3_BODY_BYTES).toInt()
        )
        if (body.isEmpty()) return null

        // In 2.2 and 2.3 unsynchronisation applies to the entire tag; in 2.4 it is
        // a per-frame flag handled during the frame walk.
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
            readId3v2Frames(data, cursor)
        } else {
            readId3v3Frames(data, cursor, majorVersion)
        }
    }

    private fun skipExtendedHeader(data: ByteArray, majorVersion: Int): Int? {
        if (data.size < 4) return null
        return if (majorVersion == 4) {
            // 2.4: a syncsafe size that counts itself.
            val size = syncSafeInt(data, 0)
            if (size <= 0) null else size
        } else {
            // 2.3: a plain size that does not count the four size bytes.
            val size = beInt(data, 0)
            if (size < 0) null else 4 + size
        }
    }

    /** ID3v2.2: three-character frame IDs, three-byte sizes, no frame flags. */
    private fun readId3v2Frames(data: ByteArray, start: Int): String? {
        var offset = start
        var unsynced: String? = null

        while (offset + 6 <= data.size) {
            if (data[offset] == 0.toByte()) break // padding

            val id = ascii(data, offset, 3)
            val size = beInt24(data, offset + 3)
            val body = offset + 6
            if (size <= 0 || body + size > data.size) break

            when (id) {
                "SLT" -> readSylt(data, body, body + size)?.let { return it }
                "ULT" -> if (unsynced == null) unsynced = readUslt(data, body, body + size)
            }
            offset = body + size
        }
        return unsynced?.takeIf { it.isNotBlank() }
    }

    /** ID3v2.3 and 2.4: four-character frame IDs, ten-byte frame headers. */
    private fun readId3v3Frames(data: ByteArray, start: Int, majorVersion: Int): String? {
        var offset = start
        var unsynced: String? = null

        while (offset + ID3_FRAME_HEADER_SIZE <= data.size) {
            if (data[offset] == 0.toByte()) break // padding

            val id = ascii(data, offset, 4)
            // 2.4 sizes are syncsafe; 2.3 sizes are plain big-endian. Reading a
            // 2.3 tag as syncsafe silently mislocates every frame past the first
            // one that exceeds 127 bytes.
            val size = if (majorVersion == 4) syncSafeInt(data, offset + 4) else beInt(data, offset + 4)
            val frameFlags = data[offset + 9].toInt() and 0xFF

            var body = offset + ID3_FRAME_HEADER_SIZE
            var length = size
            if (size <= 0 || body + size > data.size) break

            // Compressed or encrypted frames cannot be read here. Skip them
            // rather than emit the raw bytes as text.
            val readable = frameFlags and (ID3_FRAME_COMPRESSED or ID3_FRAME_ENCRYPTED) == 0

            if (majorVersion == 4 && frameFlags and ID3_FRAME_DATA_LENGTH != 0) {
                // A data length indicator prefixes the frame body with four more
                // syncsafe bytes that are not part of the content.
                body += 4
                length -= 4
            }

            if (readable && length > 0 && body + length <= data.size) {
                val frame = if (majorVersion == 4 && frameFlags and ID3_FRAME_UNSYNCHRONISED != 0) {
                    removeUnsynchronisation(data.copyOfRange(body, body + length))
                } else {
                    data
                }
                val from = if (frame === data) body else 0
                val to = if (frame === data) body + length else frame.size

                when (id) {
                    // Synchronised lyrics win: they can follow playback.
                    "SYLT" -> readSylt(frame, from, to)?.let { return it }
                    "USLT" -> if (unsynced == null) unsynced = readUslt(frame, from, to)
                }
            }

            offset = body + maxOf(length, 0)
        }
        return unsynced?.takeIf { it.isNotBlank() }
    }

    /**
     * `USLT`: encoding byte, three-byte language, null-terminated description,
     * then the lyrics. The text is frequently LRC even in this "unsynchronised"
     * frame, which is why it goes through the same parser.
     */
    private fun readUslt(data: ByteArray, from: Int, to: Int): String? {
        if (to - from < 5) return null
        val encoding = data[from].toInt() and 0xFF
        val afterLanguage = from + 4

        val descriptionEnd = findTerminator(data, afterLanguage, to, encoding) ?: return null
        val textStart = descriptionEnd + terminatorSize(encoding)
        if (textStart >= to) return null

        return decodeText(data, textStart, to, encoding).trimEnd().takeIf { it.isNotBlank() }
    }

    /**
     * `SYLT`: encoding, language, timestamp format, content type, description,
     * then repeated `text\0` + 32-bit timestamp pairs.
     *
     * Timestamps may be milliseconds or MPEG frame counts. Frame counts cannot be
     * converted without the frame rate, which is not in the frame, so those are
     * refused — showing lyrics against timings known to be wrong would be worse
     * than showing none.
     */
    private fun readSylt(data: ByteArray, from: Int, to: Int): String? {
        if (to - from < 7) return null

        val encoding = data[from].toInt() and 0xFF
        val timestampFormat = data[from + 4].toInt() and 0xFF
        if (timestampFormat != SYLT_TIMESTAMP_MILLIS) return null

        val descriptionEnd = findTerminator(data, from + 6, to, encoding) ?: return null
        var cursor = descriptionEnd + terminatorSize(encoding)

        // Fragments can be per syllable. The spec's convention is that a fragment
        // beginning with a newline starts a new line, so anything else continues
        // the line in progress and must be joined to it, not shown on its own.
        val lines = mutableListOf<Pair<Long, StringBuilder>>()

        while (cursor < to && lines.size < MAX_LYRIC_LINES) {
            val textEnd = findTerminator(data, cursor, to, encoding) ?: break
            val timestampAt = textEnd + terminatorSize(encoding)
            if (timestampAt + 4 > to) break

            val raw = decodeText(data, cursor, textEnd, encoding)
            val timeMs = beInt(data, timestampAt).toLong()
            val startsLine = lines.isEmpty() || raw.startsWith("\n") || raw.startsWith("\r")
            val text = raw.trim('\n', '\r')

            if (startsLine) {
                lines.add(timeMs to StringBuilder(text))
            } else {
                lines.last().second.append(text)
            }

            cursor = timestampAt + 4
        }

        val usable = lines.filter { it.second.isNotBlank() }
        if (usable.isEmpty()) return null

        return usable.joinToString("\n") { (timeMs, text) ->
            "${lrcTimestamp(timeMs)}${text.toString().trim()}"
        }
    }

    /** Formats milliseconds as an LRC `[mm:ss.cc]` stamp. */
    private fun lrcTimestamp(timeMs: Long): String {
        val safe = if (timeMs < 0) 0L else timeMs
        val minutes = safe / 60_000L
        val seconds = safe % 60_000L / 1_000L
        val hundredths = safe % 1_000L / 10L
        return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
    }

    // --- FLAC ---

    private fun readFlac(source: ByteSource, base: Long): String? {
        var offset = base + FLAC_MAGIC.size
        var blocks = 0

        while (blocks < MAX_FLAC_BLOCKS) {
            val header = source.read(offset, 4)
            if (header.size < 4) return null

            val flags = header[0].toInt() and 0xFF
            val isLast = flags and 0x80 != 0
            val type = flags and 0x7F
            val length = beInt24(header, 1)
            if (length < 0) return null

            if (type == FLAC_BLOCK_VORBIS_COMMENT) {
                val block = source.read(offset + 4, minOf(length, MAX_COMMENT_BLOCK_BYTES))
                return pickBest(parseVorbisComments(block, 0))
            }

            if (isLast) return null
            offset += 4L + length
            blocks++
        }
        return null
    }

    // --- Ogg (Vorbis and Opus) ---

    private fun readOgg(source: ByteSource, base: Long): String? {
        var offset = base
        var pages = 0
        var serial: Int? = null
        val stream = ByteArrayOutputStream()

        // The comment header is the second packet, so only the first few pages
        // are worth reading. Packets can span pages, so page payloads are
        // concatenated for one logical stream before being searched.
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
            return pickBest(parseVorbisComments(bytes, at + VORBIS_COMMENT_SIGNATURE.size))
        }
        indexOf(bytes, OPUS_TAGS_SIGNATURE)?.let { at ->
            return pickBest(parseVorbisComments(bytes, at + OPUS_TAGS_SIGNATURE.size))
        }
        return null
    }

    /**
     * Parses a Vorbis comment payload: little-endian vendor string, count, then
     * `KEY=value` entries in UTF-8.
     */
    private fun parseVorbisComments(data: ByteArray, start: Int): List<String> {
        var cursor = start
        if (cursor + 4 > data.size) return emptyList()

        val vendorLength = leInt(data, cursor)
        if (vendorLength < 0) return emptyList()
        cursor += 4 + vendorLength
        if (cursor + 4 > data.size) return emptyList()

        val count = leInt(data, cursor)
        cursor += 4
        if (count <= 0 || count > MAX_VORBIS_COMMENTS) return emptyList()

        val found = mutableListOf<String>()
        for (index in 0 until count) {
            if (cursor + 4 > data.size) break
            val length = leInt(data, cursor)
            cursor += 4
            if (length < 0 || cursor + length > data.size) break

            val separator = data.indexOfByte('='.code.toByte(), cursor, cursor + length)
            if (separator > 0) {
                val key = String(data, cursor, separator - cursor, Charsets.US_ASCII).uppercase()
                if (key in LYRIC_KEYS) {
                    val valueStart = separator + 1
                    val value = String(
                        data,
                        valueStart,
                        minOf(cursor + length, data.size) - valueStart,
                        Charsets.UTF_8
                    )
                    if (value.isNotBlank()) found.add(value)
                }
            }
            cursor += length
        }
        return found
    }

    // --- MP4 (M4A, ALAC, AAC) ---

    private fun isMp4(magic: ByteArray): Boolean =
        magic.size >= 8 && ascii(magic, 4, 4) == "ftyp"

    private fun readMp4(source: ByteSource, base: Long): String? =
        findMp4Lyrics(source, base, source.size, 0)

    private fun findMp4Lyrics(source: ByteSource, start: Long, end: Long, depth: Int): String? {
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
                    // Extended 64-bit size, for boxes over 4 GiB.
                    val extended = source.read(offset + 8, 8)
                    if (extended.size < 8) return null
                    boxSize = beLong(extended, 0)
                    content = offset + 16
                }
                0L -> boxSize = end - offset // runs to the end of its parent
            }
            if (boxSize < 8) return null

            val boxEnd = minOf(offset + boxSize, end)
            when (type) {
                "moov", "udta", "ilst" ->
                    findMp4Lyrics(source, content, boxEnd, depth + 1)?.let { return it }
                "meta" -> {
                    // `meta` normally carries four bytes of version and flags
                    // before its children, but some writers omit them, so try
                    // both rather than miss the tag list.
                    findMp4Lyrics(source, content + 4, boxEnd, depth + 1)?.let { return it }
                    findMp4Lyrics(source, content, boxEnd, depth + 1)?.let { return it }
                }
                MP4_LYRICS_ATOM ->
                    readMp4DataAtom(source, content, boxEnd)?.let { return it }
            }

            offset = boxEnd
        }
        return null
    }

    /** Reads the `data` child of an iTunes metadata atom. */
    private fun readMp4DataAtom(source: ByteSource, start: Long, end: Long): String? {
        var offset = start
        while (offset + 8 <= end) {
            val header = source.read(offset, 8)
            if (header.size < 8) return null

            val boxSize = beInt(header, 0).toLong() and 0xFFFFFFFFL
            val type = ascii(header, 4, 4)
            if (boxSize < 8) return null

            if (type == "data") {
                // Four bytes of type indicator, four of locale, then the text.
                val textStart = offset + 16
                val length = minOf(offset + boxSize, end) - textStart
                if (length <= 0) return null

                val bytes = source.read(textStart, minOf(length, MAX_LYRICS_BYTES.toLong()).toInt())
                return String(bytes, Charsets.UTF_8).trimEnd().takeIf { it.isNotBlank() }
            }
            offset += boxSize
        }
        return null
    }

    // --- Choosing between candidates ---

    /**
     * Prefers a candidate that carries LRC timestamps, so a file with both a
     * plain and a timed field displays the timed one.
     */
    private fun pickBest(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val timed = candidates.firstOrNull { LRC_TIMESTAMP.containsMatchIn(it) }
        return (timed ?: candidates.first()).trimEnd().takeIf { it.isNotBlank() }
    }

    // --- Text decoding ---

    private fun terminatorSize(encoding: Int): Int =
        if (encoding == ID3_UTF16_BOM || encoding == ID3_UTF16_BE) 2 else 1

    /**
     * Offset of the string terminator at or after [from], or null when the string
     * is not terminated before [to].
     */
    private fun findTerminator(data: ByteArray, from: Int, to: Int, encoding: Int): Int? {
        val step = terminatorSize(encoding)
        var offset = from
        if (step == 1) {
            while (offset < to) {
                if (data[offset] == 0.toByte()) return offset
                offset++
            }
        } else {
            // UTF-16 terminators are two zero bytes on an even boundary relative
            // to the start of the string; a single zero byte is half of a
            // character such as 'A' and must not end it.
            while (offset + 1 < to) {
                if (data[offset] == 0.toByte() && data[offset + 1] == 0.toByte()) return offset
                offset += 2
            }
        }
        return null
    }

    private fun decodeText(data: ByteArray, from: Int, to: Int, encoding: Int): String {
        if (from >= to) return ""
        val length = minOf(to, data.size) - from
        if (length <= 0) return ""

        val charset = when (encoding) {
            ID3_UTF16_BOM -> Charsets.UTF_16 // honours the byte order mark
            ID3_UTF16_BE -> Charsets.UTF_16BE
            ID3_UTF8 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val decoded = String(data, from, minOf(length, MAX_LYRICS_BYTES), charset)

        // A USLT frame's text runs to the end of the frame and the spec does not
        // terminate it, but real taggers append a terminator anyway. Those bytes
        // decode to U+0000, which is not whitespace, so trimEnd() leaves it in
        // place and it reaches the screen as a stray glyph. A byte order mark can
        // also survive when the encoding said big-endian and the writer emitted
        // one regardless. Neither is ever content.
        return decoded.trim('\u0000', '\uFEFF')
    }

    // --- Byte helpers ---

    /**
     * Reverses ID3 unsynchronisation: a `$FF 00` pair stands for a literal `$FF`
     * that would otherwise look like an MPEG frame sync to a decoder.
     */
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

    /** Reads a 28-bit syncsafe integer: four bytes, seven usable bits each. */
    private fun syncSafeInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        var value = 0
        for (index in 0 until 4) {
            val byte = data[offset + index].toInt() and 0xFF
            if (byte and 0x80 != 0) return -1 // not syncsafe; refuse to guess
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

    private fun ByteArray.indexOfByte(target: Byte, from: Int, to: Int): Int {
        var index = from
        val limit = minOf(to, size)
        while (index < limit) {
            if (this[index] == target) return index
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
            val to = minOf(from + length, bytes.size)
            return bytes.copyOfRange(from, to)
        }
    }

    // --- Constants ---

    private val EMPTY_BYTES = ByteArray(0)

    private val ID3_MAGIC = "ID3".toByteArray(Charsets.US_ASCII)
    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val OGG_MAGIC = "OggS".toByteArray(Charsets.US_ASCII)

    /** Vorbis comment header packet: type 3 followed by "vorbis". */
    private val VORBIS_COMMENT_SIGNATURE =
        byteArrayOf(3) + "vorbis".toByteArray(Charsets.US_ASCII)
    private val OPUS_TAGS_SIGNATURE = "OpusTags".toByteArray(Charsets.US_ASCII)

    /** `©lyr`, the iTunes lyrics atom. */
    private const val MP4_LYRICS_ATOM = "\u00A9lyr"

    /** Enough for the longest signature check, `ftyp` at offset 4. */
    private const val MAGIC_PROBE_SIZE = 12

    private const val ID3_HEADER_SIZE = 10
    private const val ID3_FRAME_HEADER_SIZE = 10

    private const val ID3_FLAG_UNSYNCHRONISED = 0x80
    private const val ID3_FLAG_EXTENDED_HEADER = 0x40
    private const val ID3_FLAG_FOOTER = 0x10

    private const val ID3_FRAME_COMPRESSED = 0x08
    private const val ID3_FRAME_ENCRYPTED = 0x04
    private const val ID3_FRAME_UNSYNCHRONISED = 0x02
    private const val ID3_FRAME_DATA_LENGTH = 0x01

    private const val ID3_UTF16_BOM = 1
    private const val ID3_UTF16_BE = 2
    private const val ID3_UTF8 = 3

    /** `SYLT` timestamp format 2 is milliseconds; 1 is MPEG frames. */
    private const val SYLT_TIMESTAMP_MILLIS = 2

    private const val FLAC_BLOCK_VORBIS_COMMENT = 4

    private const val OGG_HEADER_SIZE = 27
    private const val MAX_OGG_PAGES = 8
    private const val MAX_OGG_SCAN_BYTES = 512 * 1024

    private const val MAX_MP4_DEPTH = 6
    private const val MAX_FLAC_BLOCKS = 64
    private const val MAX_VORBIS_COMMENTS = 512
    private const val MAX_COMMENT_BLOCK_BYTES = 512 * 1024
    private const val MAX_ID3_BODY_BYTES = 4 * 1024 * 1024
    private const val MAX_LYRICS_BYTES = 512 * 1024
    private const val MAX_LYRIC_LINES = 4_000

    /** Field names used for lyrics in Vorbis comments, across taggers. */
    private val LYRIC_KEYS = setOf(
        "LYRICS",
        "UNSYNCEDLYRICS",
        "UNSYNCED LYRICS",
        "SYNCEDLYRICS",
        "SYNCED LYRICS",
        "USLT",
        "LRC"
    )

    /** Just enough to tell a timed candidate from a plain one. */
    private val LRC_TIMESTAMP = Regex("""\[\d{1,3}:\d{1,2}""")
}
