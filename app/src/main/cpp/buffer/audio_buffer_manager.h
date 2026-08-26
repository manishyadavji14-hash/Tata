#pragma once

#include "ring_buffer.h"
#include <cstdint>
#include <memory>
#include <atomic>

namespace bitperfect {
namespace buffer {

/**
 * Buffer state for monitoring.
 */
enum class BufferState : uint8_t {
    EMPTY = 0,
    PREBUFFERING = 1,
    STREAMING = 2,
    UNDERRUN = 3,
    FULL = 4
};

/**
 * Buffer statistics.
 */
struct BufferStatistics {
    uint64_t totalBytesWritten = 0;
    uint64_t totalBytesRead = 0;
    uint32_t underrunCount = 0;
    uint32_t overrunCount = 0;
    float currentFillLevel = 0.0f;
    BufferState state = BufferState::EMPTY;
};

/**
 * AudioBufferManager - Higher-level buffer management for audio streaming.
 *
 * Wraps RingBuffer with additional features:
 * - Pre-buffering threshold (fill before starting playback)
 * - Underflow/overflow detection and counting
 * - Fill level monitoring
 * - Adaptive latency control
 */
class AudioBufferManager {
public:
    /**
     * Create buffer manager.
     * @param bufferSizeBytes Total buffer size in bytes
     * @param prebufferThreshold Pre-buffer fill ratio (0.0 - 1.0)
     */
    explicit AudioBufferManager(size_t bufferSizeBytes, float prebufferThreshold = 0.5f);
    ~AudioBufferManager() = default;

    /**
     * Write audio data to the buffer (producer).
     * @return Bytes written
     */
    size_t write(const uint8_t* data, size_t length);

    /**
     * Read audio data from the buffer (consumer).
     * During prebuffering, returns 0 until threshold is met.
     * @return Bytes read
     */
    size_t read(uint8_t* data, size_t length);

    /**
     * Check if the buffer is ready for playback (prebuffer threshold met).
     */
    bool isReady() const { return state_.load() == BufferState::STREAMING; }

    /**
     * Get current buffer state.
     */
    BufferState getState() const { return state_.load(); }

    /**
     * Get current fill level (0.0 - 1.0).
     */
    float getFillLevel() const;

    /**
     * Get buffer statistics.
     */
    BufferStatistics getStatistics() const;

    /**
     * Reset the buffer and statistics.
     */
    void reset();

    /**
     * Set the pre-buffer threshold.
     * @param threshold Fill ratio (0.0 - 1.0) required before streaming starts
     */
    void setPrebufferThreshold(float threshold);

    /**
     * Get the pre-buffer threshold.
     */
    float getPrebufferThreshold() const { return prebufferThreshold_; }

    /**
     * Get total buffer capacity in bytes.
     */
    size_t capacity() const { return ringBuffer_ ? ringBuffer_->capacity() : 0; }

    /**
     * Get available bytes for reading.
     */
    size_t availableRead() const { return ringBuffer_ ? ringBuffer_->availableRead() : 0; }

    /**
     * Get available bytes for writing.
     */
    size_t availableWrite() const { return ringBuffer_ ? ringBuffer_->availableWrite() : 0; }

    /**
     * Configure latency in milliseconds for a given format.
     * @param latencyMs Desired latency in milliseconds
     * @param bytesPerSecond Bytes per second for the audio format
     */
    void configureLatency(uint32_t latencyMs, uint32_t bytesPerSecond);

private:
    std::unique_ptr<RingBuffer> ringBuffer_;
    float prebufferThreshold_;
    std::atomic<BufferState> state_{BufferState::EMPTY};

    // Statistics (not atomic since only one writer per counter)
    std::atomic<uint64_t> totalBytesWritten_{0};
    std::atomic<uint64_t> totalBytesRead_{0};
    std::atomic<uint32_t> underrunCount_{0};
    std::atomic<uint32_t> overrunCount_{0};
};

} // namespace buffer
} // namespace bitperfect
