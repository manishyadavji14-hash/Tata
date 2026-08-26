#pragma once

#include "dsf_parser.h"
#include <cstdint>
#include <cstddef>
#include <vector>

namespace bitperfect {
namespace dsd {

/**
 * DSD stream state.
 */
enum class DsdStreamState : uint8_t {
    IDLE = 0,
    READY,
    STREAMING,
    END_OF_STREAM,
    ERROR
};

/**
 * DSD Stream - provides raw DSD bytes per channel from a parsed DSF file.
 *
 * This class handles the block deinterleaving from DSF format and provides
 * a linear stream of DSD bytes for each channel. It supports DSD64, DSD128,
 * and DSD256 rates.
 *
 * Key principle: NO PCM conversion is performed. The stream exposes the
 * original 1-bit DSD data as-is for DoP or Native DSD transport.
 */
class DsdStream {
public:
    DsdStream() = default;
    ~DsdStream() = default;

    /**
     * Initialize from parsed DSF file info and raw data.
     * @param info Parsed DSF file information
     * @param rawData Pointer to the DSF data section (after data chunk header)
     * @param rawLength Length of raw data
     * @return true if initialization succeeded
     */
    bool initialize(const DsfFileInfo& info, const uint8_t* rawData, size_t rawLength);

    /**
     * Initialize directly with per-channel data (for testing or other sources).
     * @param channelData Per-channel DSD byte vectors
     * @param sampleRate DSD sample rate
     * @return true if initialization succeeded
     */
    bool initializeFromChannelData(const std::vector<std::vector<uint8_t>>& channelData,
                                    uint32_t sampleRate);

    /**
     * Read DSD bytes from a specific channel.
     * @param channel Channel index (0-based)
     * @param buffer Output buffer
     * @param maxBytes Maximum bytes to read
     * @return Number of bytes actually read
     */
    size_t read(uint32_t channel, uint8_t* buffer, size_t maxBytes);

    /**
     * Read interleaved DSD bytes (ch0, ch1, ch0, ch1, ...).
     * Returns pairs of bytes (one per channel) in order.
     * @param buffer Output buffer
     * @param maxBytes Maximum bytes to read (should be multiple of channelCount)
     * @return Number of bytes actually read
     */
    size_t readInterleaved(uint8_t* buffer, size_t maxBytes);

    /**
     * Seek to a position in the stream (byte offset per channel).
     * @param byteOffset Byte offset from start
     * @return true if seek succeeded
     */
    bool seek(uint64_t byteOffset);

    /**
     * Reset stream to beginning.
     */
    void reset();

    /**
     * Get current stream state.
     */
    DsdStreamState getState() const { return state_; }

    /**
     * Get the DSD sample rate.
     */
    uint32_t getSampleRate() const { return sampleRate_; }

    /**
     * Get channel count.
     */
    uint32_t getChannelCount() const { return channelCount_; }

    /**
     * Get total DSD bytes available per channel.
     */
    uint64_t getTotalBytesPerChannel() const { return totalBytesPerChannel_; }

    /**
     * Get current read position (bytes per channel).
     */
    uint64_t getPosition() const { return position_; }

    /**
     * Check if stream has more data.
     */
    bool hasMore() const { return position_ < totalBytesPerChannel_; }

    /**
     * Get remaining bytes per channel.
     */
    uint64_t remaining() const {
        return (position_ < totalBytesPerChannel_) ?
               (totalBytesPerChannel_ - position_) : 0;
    }

private:
    std::vector<std::vector<uint8_t>> channelData_;
    uint32_t sampleRate_ = 0;
    uint32_t channelCount_ = 0;
    uint64_t totalBytesPerChannel_ = 0;
    uint64_t position_ = 0;
    DsdStreamState state_ = DsdStreamState::IDLE;
};

} // namespace dsd
} // namespace bitperfect
