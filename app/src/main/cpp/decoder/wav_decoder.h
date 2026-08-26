#pragma once

#include "audio_decoder.h"
#include <cstdio>
#include <vector>

namespace bitperfect {
namespace decoder {

/**
 * WAV/RIFF PCM Decoder.
 *
 * Parses RIFF/WAVE files and extracts raw PCM audio data.
 * Supports:
 * - 16-bit, 24-bit, and 32-bit PCM
 * - Any standard sample rate (8kHz to 768kHz)
 * - Mono and stereo (up to 8 channels)
 * - Handles LIST/INFO chunks gracefully by skipping them
 *
 * The decoder provides raw PCM samples without any processing,
 * maintaining bit-perfect output.
 */
class WavDecoder : public AudioDecoder {
public:
    WavDecoder() = default;
    ~WavDecoder() override;

    bool open(const std::string& path) override;
    size_t read(uint8_t* buffer, size_t frames) override;
    bool seek(const SeekPosition& position) override;
    void close() override;
    AudioFormat getFormat() const override { return format_; }
    double getDuration() const override { return format_.durationSeconds(); }
    bool isOpen() const override { return file_ != nullptr || memMode_; }
    uint64_t getPosition() const override { return currentFrame_; }
    const char* getTypeName() const override { return "WAV"; }

    /**
     * Open from a memory buffer (for testing).
     * @param data Pointer to WAV file data
     * @param size Size of the data in bytes
     * @return true if parsed successfully
     */
    bool openFromMemory(const uint8_t* data, size_t size);

    /**
     * Read from the memory buffer.
     */
    size_t readFromMemory(uint8_t* buffer, size_t frames);

private:
    // RIFF/WAV chunk IDs
    static constexpr uint32_t kRiffId = 0x46464952;  // "RIFF"
    static constexpr uint32_t kWaveId = 0x45564157;  // "WAVE"
    static constexpr uint32_t kFmtId  = 0x20746D66;  // "fmt "
    static constexpr uint32_t kDataId = 0x61746164;  // "data"
    static constexpr uint32_t kListId = 0x5453494C;  // "LIST"
    static constexpr uint32_t kFactId = 0x74636166;  // "fact"

    // WAV format codes
    static constexpr uint16_t kFormatPcm = 1;
    static constexpr uint16_t kFormatExtensible = 0xFFFE;

    struct WavHeader {
        uint16_t audioFormat = 0;
        uint16_t numChannels = 0;
        uint32_t sampleRate = 0;
        uint32_t byteRate = 0;
        uint16_t blockAlign = 0;
        uint16_t bitsPerSample = 0;
        uint32_t dataOffset = 0;     // Byte offset to start of PCM data
        uint32_t dataSize = 0;       // Size of PCM data in bytes
    };

    bool parseHeader(const uint8_t* data, size_t size);

    static uint16_t readU16LE(const uint8_t* p) { return p[0] | (p[1] << 8); }
    static uint32_t readU32LE(const uint8_t* p) {
        return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
    }

    FILE* file_ = nullptr;
    AudioFormat format_;
    WavHeader header_;
    uint64_t currentFrame_ = 0;

    // Memory buffer mode
    const uint8_t* memData_ = nullptr;
    size_t memSize_ = 0;
    size_t memOffset_ = 0;
    bool memMode_ = false;
};

} // namespace decoder
} // namespace bitperfect
