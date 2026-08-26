#pragma once

#include <atomic>
#include <cstdint>
#include <cstddef>
#include <memory>

namespace bitperfect {
namespace buffer {

/**
 * Lock-free ring buffer for audio streaming.
 *
 * Design principles:
 * - Single producer, single consumer (SPSC)
 * - Zero allocation in the hot path
 * - Uses atomic operations for thread safety
 * - Power-of-two capacity for fast modulo via bitmask
 * - Cache-line aligned head/tail to avoid false sharing
 *
 * The buffer stores raw bytes. The producer writes audio data and
 * the consumer reads it, typically on different threads.
 */
class RingBuffer {
public:
    /**
     * Construct a ring buffer with given capacity.
     * Capacity will be rounded up to the next power of two.
     * @param capacity Desired capacity in bytes (will be rounded up to power of 2)
     */
    explicit RingBuffer(size_t capacity);
    ~RingBuffer();

    // Non-copyable, non-movable (atomics)
    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;
    RingBuffer(RingBuffer&&) = delete;
    RingBuffer& operator=(RingBuffer&&) = delete;

    /**
     * Write data to the buffer (producer side).
     * @param data Pointer to data to write
     * @param length Number of bytes to write
     * @return Number of bytes actually written (may be less if buffer is full)
     */
    size_t write(const uint8_t* data, size_t length);

    /**
     * Read data from the buffer (consumer side).
     * @param data Pointer to destination buffer
     * @param length Maximum number of bytes to read
     * @return Number of bytes actually read (may be less if buffer is empty)
     */
    size_t read(uint8_t* data, size_t length);

    /**
     * Peek at data without consuming it.
     * @param data Destination buffer
     * @param length Maximum bytes to peek
     * @return Bytes actually peeked
     */
    size_t peek(uint8_t* data, size_t length) const;

    /**
     * Skip (discard) bytes from the read side.
     * @param length Bytes to skip
     * @return Bytes actually skipped
     */
    size_t skip(size_t length);

    /**
     * Get current number of bytes available for reading.
     */
    size_t availableRead() const;

    /**
     * Get current number of bytes available for writing.
     */
    size_t availableWrite() const;

    /**
     * Get total capacity of the buffer.
     */
    size_t capacity() const { return capacity_; }

    /**
     * Check if the buffer is empty.
     */
    bool isEmpty() const { return availableRead() == 0; }

    /**
     * Check if the buffer is full.
     */
    bool isFull() const { return availableWrite() == 0; }

    /**
     * Get the fill level as a fraction (0.0 to 1.0).
     */
    float fillLevel() const {
        return static_cast<float>(availableRead()) / capacity_;
    }

    /**
     * Reset the buffer to empty state.
     * NOT thread-safe - only call when no concurrent access.
     */
    void reset();

private:
    // Buffer storage
    uint8_t* buffer_;
    size_t capacity_;     // Always power of 2
    size_t mask_;         // capacity_ - 1 for fast modulo

    // Cache-line separation to prevent false sharing
    // Head: written by producer, read by consumer
    alignas(64) std::atomic<size_t> head_{0};
    // Tail: written by consumer, read by producer
    alignas(64) std::atomic<size_t> tail_{0};

    // Round up to next power of two
    static size_t nextPowerOfTwo(size_t v);
};

} // namespace buffer
} // namespace bitperfect
