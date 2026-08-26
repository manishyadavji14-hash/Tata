package com.bitperfect.android.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.bitperfect.android.engine.NativeAudioEngine
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioDecodeThread - Background thread that decodes audio files and feeds
 * raw PCM data into the native engine's ring buffer via writeAudioData().
 *
 * Supports two decode paths:
 * 1. WAV files: Parse RIFF header to find the data chunk, then read raw PCM bytes.
 * 2. All other formats (FLAC, MP3, AAC, OGG, etc.): Use Android MediaExtractor + MediaCodec.
 *
 * Features:
 * - Pause/resume (thread waits in a loop when paused)
 * - Seek (repositions extractor + flushes codec, or repositions file offset for WAV)
 * - Stop (clean exit from decode loop)
 * - Position tracking (reports decoded position every ~250ms)
 * - End-of-file detection (triggers track completion callback)
 * - Backpressure handling: when writeAudioData returns 0 (buffer full), sleeps briefly
 */
class AudioDecodeThread(
    private val engine: NativeAudioEngine,
    private val trackPath: String,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitDepth: Int,
    private val onPositionUpdate: (positionMs: Long) -> Unit,
    private val onDurationDetected: (durationMs: Long) -> Unit,
    private val onTrackComplete: () -> Unit,
    private val onError: (String) -> Unit
) : Thread("AudioDecodeThread") {

    companion object {
        private const val TAG = "AudioDecodeThread"

        // Chunk size for reading/writing PCM data
        private const val BUFFER_SIZE = 4096

        // How often to report position updates (in bytes decoded)
        private const val POSITION_UPDATE_INTERVAL_MS = 250L

        // Sleep time when buffer is full (backpressure)
        private const val BACKPRESSURE_SLEEP_MS = 5L

        // Sleep time when paused
        private const val PAUSE_SLEEP_MS = 50L

        // Timeout for MediaCodec dequeue operations (microseconds)
        private const val CODEC_TIMEOUT_US = 10_000L
    }

    // Thread control flags - volatile for cross-thread visibility
    @Volatile
    private var isPaused = false

    @Volatile
    private var isStopped = false

    @Volatile
    private var seekRequested = false

    @Volatile
    private var seekTargetUs: Long = 0L

    // Expose current decoded position
    @Volatile
    var currentPositionMs: Long = 0L
        private set

    @Volatile
    var detectedDurationMs: Long = 0L
        private set

    // Lock for pause/resume synchronization
    private val pauseLock = Object()

    override fun run() {
        try {
            val extension = trackPath.substringAfterLast('.', "").lowercase()
            if (extension == "wav") {
                decodeWav()
            } else {
                decodeWithMediaCodec()
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "Decode thread interrupted")
        } catch (e: Exception) {
            if (!isStopped) {
                Log.e(TAG, "Decode error: ${e.message}", e)
                onError("Decode error: ${e.message}")
            }
        }
    }

    /**
     * Pause the decode loop. Thread will wait until resumed or stopped.
     */
    fun pauseDecoding() {
        isPaused = true
    }

    /**
     * Resume the decode loop from paused state.
     */
    fun resumeDecoding() {
        isPaused = false
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
    }

    /**
     * Request a seek to the given position.
     * @param positionMs Target position in milliseconds
     */
    fun seekTo(positionMs: Long) {
        seekTargetUs = positionMs * 1000L
        seekRequested = true
        // Wake up if paused so the seek is handled
        if (isPaused) {
            resumeDecoding()
        }
    }

    /**
     * Stop the decode thread. Interrupts the thread and waits for termination.
     */
    fun stopDecoding() {
        isStopped = true
        isPaused = false
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
        interrupt()
    }

    // --- WAV Decoder ---

    private fun decodeWav() {
        val file = RandomAccessFile(File(trackPath), "r")
        try {
            val header = parseWavHeader(file) ?: run {
                onError("Invalid WAV file: $trackPath")
                return
            }

            // Calculate duration
            val bytesPerSample = header.bitsPerSample / 8
            val bytesPerSecond = header.sampleRate * header.channels * bytesPerSample
            val durationMs = if (bytesPerSecond > 0) {
                (header.dataSize.toLong() * 1000L) / bytesPerSecond
            } else 0L

            detectedDurationMs = durationMs
            onDurationDetected(durationMs)

            // Seek to data start
            file.seek(header.dataOffset.toLong())

            val buffer = ByteArray(BUFFER_SIZE)
            var totalBytesRead = 0L
            var lastPositionUpdateTime = System.currentTimeMillis()

            while (!isStopped && totalBytesRead < header.dataSize) {
                // Handle pause
                waitIfPaused()
                if (isStopped) break

                // Handle seek
                if (seekRequested) {
                    seekRequested = false
                    val seekPositionBytes = (seekTargetUs / 1_000_000L) * bytesPerSecond
                    val alignedPosition = (seekPositionBytes / (header.channels * bytesPerSample)) *
                            (header.channels * bytesPerSample)
                    val clampedPosition = alignedPosition.coerceIn(0L, header.dataSize.toLong())
                    file.seek(header.dataOffset + clampedPosition)
                    totalBytesRead = clampedPosition
                    currentPositionMs = (totalBytesRead * 1000L) / bytesPerSecond
                }

                // Read data
                val remaining = (header.dataSize - totalBytesRead).coerceAtMost(BUFFER_SIZE.toLong()).toInt()
                val bytesRead = file.read(buffer, 0, remaining)
                if (bytesRead <= 0) break

                // Feed to engine with backpressure handling
                var offset = 0
                while (offset < bytesRead && !isStopped) {
                    waitIfPaused()
                    if (isStopped) break

                    val written = engine.writeAudioData(buffer, offset, bytesRead - offset)
                    if (written > 0) {
                        offset += written
                    } else {
                        // Buffer full - apply backpressure
                        sleep(BACKPRESSURE_SLEEP_MS)
                    }
                }

                totalBytesRead += bytesRead

                // Update position periodically
                val now = System.currentTimeMillis()
                if (now - lastPositionUpdateTime >= POSITION_UPDATE_INTERVAL_MS) {
                    currentPositionMs = if (bytesPerSecond > 0) {
                        (totalBytesRead * 1000L) / bytesPerSecond
                    } else 0L
                    onPositionUpdate(currentPositionMs)
                    lastPositionUpdateTime = now
                }
            }

            // End of file reached
            if (!isStopped) {
                currentPositionMs = durationMs
                onPositionUpdate(currentPositionMs)
                onTrackComplete()
            }
        } finally {
            file.close()
        }
    }

    /**
     * Parse WAV/RIFF header to find the fmt and data chunks.
     */
    private fun parseWavHeader(file: RandomAccessFile): WavHeader? {
        // Read RIFF header
        val riff = ByteArray(4)
        file.readFully(riff)
        if (String(riff) != "RIFF") return null

        file.skipBytes(4) // file size

        val wave = ByteArray(4)
        file.readFully(wave)
        if (String(wave) != "WAVE") return null

        var wavSampleRate = 0
        var wavChannels = 0
        var wavBitsPerSample = 0
        var dataOffset = 0L
        var dataSize = 0L

        // Parse chunks
        while (file.filePointer < file.length()) {
            val chunkId = ByteArray(4)
            if (file.read(chunkId) < 4) break

            val chunkSizeBytes = ByteArray(4)
            if (file.read(chunkSizeBytes) < 4) break
            val chunkSize = ByteBuffer.wrap(chunkSizeBytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

            val chunkIdStr = String(chunkId)

            when (chunkIdStr) {
                "fmt " -> {
                    val fmtData = ByteArray(chunkSize.coerceAtMost(40).toInt())
                    file.readFully(fmtData)
                    val fmt = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                    fmt.short // audio format (1=PCM, 3=IEEE float)
                    wavChannels = fmt.short.toInt() and 0xFFFF
                    wavSampleRate = fmt.int
                    fmt.int // byte rate
                    fmt.short // block align
                    wavBitsPerSample = fmt.short.toInt() and 0xFFFF

                    // Skip remaining fmt data if chunk is larger
                    val remaining = chunkSize - fmtData.size
                    if (remaining > 0) {
                        file.skipBytes(remaining.toInt())
                    }
                }
                "data" -> {
                    dataOffset = file.filePointer
                    dataSize = chunkSize
                    break // Found data chunk, stop parsing
                }
                else -> {
                    // Skip unknown chunks
                    file.skipBytes(chunkSize.toInt())
                }
            }
        }

        if (dataOffset == 0L || dataSize == 0L || wavSampleRate == 0) {
            return null
        }

        return WavHeader(
            sampleRate = wavSampleRate,
            channels = wavChannels,
            bitsPerSample = wavBitsPerSample,
            dataOffset = dataOffset,
            dataSize = dataSize
        )
    }

    // --- MediaExtractor + MediaCodec Decoder ---

    private fun decodeWithMediaCodec() {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(trackPath)

            // Find audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                onError("No audio track found in: $trackPath")
                return
            }

            extractor.selectTrack(audioTrackIndex)

            // Get duration
            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            detectedDurationMs = durationUs / 1000L
            onDurationDetected(detectedDurationMs)

            // Create and configure decoder
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            var lastPositionUpdateTime = System.currentTimeMillis()

            while (!isStopped) {
                // Handle pause
                waitIfPaused()
                if (isStopped) break

                // Handle seek
                if (seekRequested) {
                    seekRequested = false
                    extractor.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    isEos = false
                    currentPositionMs = seekTargetUs / 1000L
                    onPositionUpdate(currentPositionMs)
                }

                // Feed input to codec
                if (!isEos) {
                    val inputBufferIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            // End of stream
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isEos = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize, presentationTimeUs, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Read output from codec
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outputBufferIndex >= 0 -> {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            // End of stream in output
                            codec.releaseOutputBuffer(outputBufferIndex, false)
                            if (!isStopped) {
                                currentPositionMs = detectedDurationMs
                                onPositionUpdate(currentPositionMs)
                                onTrackComplete()
                            }
                            break
                        }

                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val pcmData = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcmData)
                            outputBuffer.clear()

                            // Feed PCM to engine with backpressure
                            var offset = 0
                            while (offset < pcmData.size && !isStopped) {
                                waitIfPaused()
                                if (isStopped) break

                                val written = engine.writeAudioData(pcmData, offset, pcmData.size - offset)
                                if (written > 0) {
                                    offset += written
                                } else {
                                    // Buffer full - apply backpressure
                                    sleep(BACKPRESSURE_SLEEP_MS)
                                }
                            }

                            // Update position from presentation time
                            val now = System.currentTimeMillis()
                            if (now - lastPositionUpdateTime >= POSITION_UPDATE_INTERVAL_MS) {
                                currentPositionMs = bufferInfo.presentationTimeUs / 1000L
                                onPositionUpdate(currentPositionMs)
                                lastPositionUpdateTime = now
                            }
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Output format changed, this is normal during start
                        Log.d(TAG, "Output format changed: ${codec.outputFormat}")
                    }
                    // INFO_TRY_AGAIN_LATER (-1): no output available yet, loop again
                }
            }
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing codec: ${e.message}")
            }
            extractor.release()
        }
    }

    // --- Utility ---

    /**
     * Wait in a loop if the thread is paused. Returns when resumed or stopped.
     */
    private fun waitIfPaused() {
        while (isPaused && !isStopped) {
            synchronized(pauseLock) {
                if (isPaused && !isStopped) {
                    try {
                        pauseLock.wait(PAUSE_SLEEP_MS)
                    } catch (e: InterruptedException) {
                        // Check stop flag on interrupt
                    }
                }
            }
        }
    }

    /**
     * WAV header information.
     */
    private data class WavHeader(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    )
}
