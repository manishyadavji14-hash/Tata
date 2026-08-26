#include "audio_buffer_manager.h"
#include <algorithm>

namespace bitperfect {
namespace buffer {

AudioBufferManager::AudioBufferManager(size_t bufferSizeBytes, float prebufferThreshold)
    : ringBuffer_(std::make_unique<RingBuffer>(bufferSizeBytes))
    , prebufferThreshold_(std::clamp(prebufferThreshold, 0.0f, 1.0f)) {
}

size_t AudioBufferManager::write(const uint8_t* data, size_t length) {
    if (!ringBuffer_ || !data || length == 0) return 0;

    size_t written = ringBuffer_->write(data, length);

    if (written < length) {
        overrunCount_.fetch_add(1, std::memory_order_relaxed);
    }

    if (written > 0) {
        totalBytesWritten_.fetch_add(written, std::memory_order_relaxed);

        // Check if we've crossed the prebuffer threshold
        BufferState current = state_.load(std::memory_order_acquire);
        if (current == BufferState::EMPTY || current == BufferState::PREBUFFERING ||
            current == BufferState::UNDERRUN) {
            float level = getFillLevel();
            if (level >= prebufferThreshold_) {
                state_.store(BufferState::STREAMING, std::memory_order_release);
            } else if (current == BufferState::EMPTY) {
                state_.store(BufferState::PREBUFFERING, std::memory_order_release);
            }
        }
    }

    return written;
}

size_t AudioBufferManager::read(uint8_t* data, size_t length) {
    if (!ringBuffer_ || !data || length == 0) return 0;

    BufferState current = state_.load(std::memory_order_acquire);

    // During prebuffering, don't provide data yet
    if (current == BufferState::PREBUFFERING || current == BufferState::EMPTY) {
        return 0;
    }

    size_t bytesRead = ringBuffer_->read(data, length);

    if (bytesRead > 0) {
        totalBytesRead_.fetch_add(bytesRead, std::memory_order_relaxed);
    }

    if (bytesRead < length && current == BufferState::STREAMING) {
        // Underrun detected
        underrunCount_.fetch_add(1, std::memory_order_relaxed);
        if (ringBuffer_->isEmpty()) {
            state_.store(BufferState::UNDERRUN, std::memory_order_release);
        }
    }

    return bytesRead;
}

float AudioBufferManager::getFillLevel() const {
    if (!ringBuffer_) return 0.0f;
    return ringBuffer_->fillLevel();
}

BufferStatistics AudioBufferManager::getStatistics() const {
    BufferStatistics stats;
    stats.totalBytesWritten = totalBytesWritten_.load(std::memory_order_relaxed);
    stats.totalBytesRead = totalBytesRead_.load(std::memory_order_relaxed);
    stats.underrunCount = underrunCount_.load(std::memory_order_relaxed);
    stats.overrunCount = overrunCount_.load(std::memory_order_relaxed);
    stats.currentFillLevel = getFillLevel();
    stats.state = state_.load(std::memory_order_relaxed);
    return stats;
}

void AudioBufferManager::reset() {
    if (ringBuffer_) {
        ringBuffer_->reset();
    }
    state_.store(BufferState::EMPTY, std::memory_order_release);
    totalBytesWritten_.store(0, std::memory_order_relaxed);
    totalBytesRead_.store(0, std::memory_order_relaxed);
    underrunCount_.store(0, std::memory_order_relaxed);
    overrunCount_.store(0, std::memory_order_relaxed);
}

void AudioBufferManager::setPrebufferThreshold(float threshold) {
    prebufferThreshold_ = std::clamp(threshold, 0.0f, 1.0f);
}

void AudioBufferManager::configureLatency(uint32_t latencyMs, uint32_t bytesPerSecond) {
    if (bytesPerSecond == 0) return;

    // Calculate buffer size for desired latency
    // buffer_bytes = (latencyMs / 1000) * bytesPerSecond
    size_t desiredSize = (static_cast<size_t>(latencyMs) * bytesPerSecond) / 1000;
    if (desiredSize == 0) desiredSize = 4096;

    // Recreate the ring buffer with new size
    ringBuffer_ = std::make_unique<RingBuffer>(desiredSize);
    reset();
}

} // namespace buffer
} // namespace bitperfect
