#include "ring_buffer.h"
#include <cstring>
#include <algorithm>
#include <new>

namespace bitperfect {
namespace buffer {

RingBuffer::RingBuffer(size_t capacity)
    : capacity_(nextPowerOfTwo(capacity))
    , mask_(capacity_ - 1) {
    buffer_ = new (std::nothrow) uint8_t[capacity_];
    if (!buffer_) {
        capacity_ = 0;
        mask_ = 0;
    }
}

RingBuffer::~RingBuffer() {
    delete[] buffer_;
}

size_t RingBuffer::write(const uint8_t* data, size_t length) {
    if (!data || length == 0 || !buffer_) return 0;

    // Load tail with acquire to see consumer's latest writes
    const size_t tail = tail_.load(std::memory_order_acquire);
    const size_t head = head_.load(std::memory_order_relaxed);

    // Available write space: capacity - (head - tail)
    size_t available = capacity_ - (head - tail);
    size_t toWrite = std::min(length, available);

    if (toWrite == 0) return 0;

    // Write position in the physical buffer
    size_t writePos = head & mask_;

    // Handle wrap-around: may need two memcpy calls
    size_t firstChunk = std::min(toWrite, capacity_ - writePos);
    std::memcpy(buffer_ + writePos, data, firstChunk);

    if (toWrite > firstChunk) {
        // Wrap around to beginning
        std::memcpy(buffer_, data + firstChunk, toWrite - firstChunk);
    }

    // Publish the write with release semantics
    head_.store(head + toWrite, std::memory_order_release);

    return toWrite;
}

size_t RingBuffer::read(uint8_t* data, size_t length) {
    if (!data || length == 0 || !buffer_) return 0;

    // Load head with acquire to see producer's latest writes
    const size_t head = head_.load(std::memory_order_acquire);
    const size_t tail = tail_.load(std::memory_order_relaxed);

    // Available read data: head - tail
    size_t available = head - tail;
    size_t toRead = std::min(length, available);

    if (toRead == 0) return 0;

    // Read position in the physical buffer
    size_t readPos = tail & mask_;

    // Handle wrap-around
    size_t firstChunk = std::min(toRead, capacity_ - readPos);
    std::memcpy(data, buffer_ + readPos, firstChunk);

    if (toRead > firstChunk) {
        std::memcpy(data + firstChunk, buffer_, toRead - firstChunk);
    }

    // Publish the read with release semantics
    tail_.store(tail + toRead, std::memory_order_release);

    return toRead;
}

size_t RingBuffer::peek(uint8_t* data, size_t length) const {
    if (!data || length == 0 || !buffer_) return 0;

    const size_t head = head_.load(std::memory_order_acquire);
    const size_t tail = tail_.load(std::memory_order_relaxed);

    size_t available = head - tail;
    size_t toRead = std::min(length, available);

    if (toRead == 0) return 0;

    size_t readPos = tail & mask_;
    size_t firstChunk = std::min(toRead, capacity_ - readPos);
    std::memcpy(data, buffer_ + readPos, firstChunk);

    if (toRead > firstChunk) {
        std::memcpy(data + firstChunk, buffer_, toRead - firstChunk);
    }

    // Do NOT update tail - this is just a peek
    return toRead;
}

size_t RingBuffer::skip(size_t length) {
    if (length == 0 || !buffer_) return 0;

    const size_t head = head_.load(std::memory_order_acquire);
    const size_t tail = tail_.load(std::memory_order_relaxed);

    size_t available = head - tail;
    size_t toSkip = std::min(length, available);

    if (toSkip == 0) return 0;

    tail_.store(tail + toSkip, std::memory_order_release);
    return toSkip;
}

size_t RingBuffer::availableRead() const {
    const size_t head = head_.load(std::memory_order_acquire);
    const size_t tail = tail_.load(std::memory_order_acquire);
    return head - tail;
}

size_t RingBuffer::availableWrite() const {
    const size_t head = head_.load(std::memory_order_acquire);
    const size_t tail = tail_.load(std::memory_order_acquire);
    return capacity_ - (head - tail);
}

void RingBuffer::reset() {
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
}

size_t RingBuffer::nextPowerOfTwo(size_t v) {
    if (v == 0) return 1;
    v--;
    v |= v >> 1;
    v |= v >> 2;
    v |= v >> 4;
    v |= v >> 8;
    v |= v >> 16;
    if (sizeof(size_t) > 4) {
        v |= v >> 32;
    }
    v++;
    return v;
}

} // namespace buffer
} // namespace bitperfect
