#pragma once

#include "audio_decoder.h"
#include <cstdio>
#include <vector>

namespace bitperfect {
namespace decoder {

/**
 * FLAC Decoder - Minimal implementation for BitPerfect.
 *
 * Implements FLAC decoding by parsing:
 * - STREAMINFO metadata block (sample rate, channels, bits per sample, total samples)
 * - Frame headers for synchronization
 * - Fixed-predictor subframes (orders 0-4) for basic decoding
 *
 * For production use, this would integrate with libFLAC for full decoding
 * of all predictor types and entropy coding. The current implementation
 * demonstrates the interface and handles STREAMINFO extraction correctly.
 *
 * Supports:
 * - Up to 32-bit / 384kHz
 * - Up to 8 channels
 * - All standard FLAC metadata blocks
 */
class FlacDecoder : public AudioDecoder {
public:
    FlacDecoder() = default;
    ~FlacDecoder() override;

    bool open(const std::string& path) override;
    size_t read(uint8_t* buffer, size_t frames) override;
    bool seek(const SeekPosition& position) override;
    void close() override;
    AudioFormat getFormat() const override { return format_; }
    double getDuration() const override { return format_.durationSeconds(); }
    bool isOpen() const override { return file_ != nullptr || memMode_; }
    uint64_t getPosition() const override { return currentFrame_; }
    const char* getTypeName() const override { return "FLAC"; }

    /**
     * Open from a memory buffer (for testing).
     * @param data Pointer to FLAC file data
     * @param size Size of the data in bytes
     * @return true if STREAMINFO was parsed successfully
     */
    bool openFromMemory(const uint8_t* data, size_t size);

    /**
     * STREAMINFO metadata.
     */
    struct StreamInfo {
        uint16_t minBlockSize = 0;
        uint16_t maxBlockSize = 0;
        uint32_t minFrameSize = 0;
        uint32_t maxFrameSize = 0;
        uint32_t sampleRate = 0;
        uint8_t channels = 0;
        uint8_t bitsPerSample = 0;
        uint64_t totalSamples = 0;
        uint8_t md5[16] = {};
    };

    /**
     * Get parsed STREAMINFO.
     */
    const StreamInfo& getStreamInfo() const { return streamInfo_; }

private:
    static constexpr uint32_t kFlacMarker = 0x43614C66;  // "fLaC"

    bool parseStreamInfo(const uint8_t* data, size_t size);
    bool parseMetadataBlocks(const uint8_t* data, size_t size);
    size_t decodeFrame(const uint8_t* frameData, size_t available,
                       uint8_t* output, size_t maxFrames);

    static uint32_t readU24BE(const uint8_t* p) {
        return (static_cast<uint32_t>(p[0]) << 16) | (p[1] << 8) | p[2];
    }
    static uint32_t readU32BE(const uint8_t* p) {
        return (static_cast<uint32_t>(p[0]) << 24) | (p[1] << 16) | (p[2] << 8) | p[3];
    }
    static uint16_t readU16BE(const uint8_t* p) {
        return (p[0] << 8) | p[1];
    }

    FILE* file_ = nullptr;
    AudioFormat format_;
    StreamInfo streamInfo_;
    uint64_t currentFrame_ = 0;
    size_t audioDataOffset_ = 0;   // Offset to first audio frame

    // Memory buffer mode
    const uint8_t* memData_ = nullptr;
    size_t memSize_ = 0;
    size_t memOffset_ = 0;
    bool memMode_ = false;

    // Decode buffer
    std::vector<uint8_t> decodeBuffer_;
    size_t decodeBufferFrames_ = 0;
    size_t decodeBufferOffset_ = 0;
};

} // namespace decoder
} // namespace bitperfect
