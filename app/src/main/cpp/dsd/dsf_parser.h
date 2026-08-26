#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>
#include <string>

namespace bitperfect {
namespace dsd {

/**
 * DSD sample rates.
 */
constexpr uint32_t DSD64_RATE = 2822400;
constexpr uint32_t DSD128_RATE = 5644800;
constexpr uint32_t DSD256_RATE = 11289600;

/**
 * DSF file chunk identifiers.
 */
constexpr uint32_t DSF_DSD_CHUNK_ID = 0x20445344;  // "DSD " in LE
constexpr uint32_t DSF_FMT_CHUNK_ID = 0x20746D66;  // "fmt " in LE
constexpr uint32_t DSF_DATA_CHUNK_ID = 0x61746164; // "data" in LE

/**
 * DSF format version.
 */
constexpr uint32_t DSF_FORMAT_VERSION = 1;

/**
 * DSF format type (DSD raw).
 */
constexpr uint32_t DSF_FORMAT_DSD_RAW = 0;

/**
 * DSF channel types.
 */
enum class DsfChannelType : uint32_t {
    MONO = 1,
    STEREO = 2,
    THREE_CHANNELS = 3,
    QUAD = 4,
    FOUR_CHANNELS = 5,
    FIVE_CHANNELS = 6,
    FIVE_ONE = 7
};

/**
 * Parsed DSF file header information.
 */
struct DsfFileInfo {
    // DSD chunk
    uint64_t totalFileSize = 0;
    uint64_t metadataOffset = 0;

    // Format chunk
    uint32_t formatVersion = 0;
    uint32_t formatId = 0;
    DsfChannelType channelType = DsfChannelType::STEREO;
    uint32_t channelCount = 0;
    uint32_t sampleRate = 0;
    uint32_t bitsPerSample = 0;
    uint64_t sampleCount = 0;      // Total DSD samples per channel
    uint32_t blockSizePerChannel = 0;

    // Data chunk
    uint64_t dataChunkSize = 0;
    uint64_t dataOffset = 0;       // Offset to actual DSD data within file

    // Derived
    bool isValid = false;
    std::string errorMessage;
};

/**
 * DSF Parser - parses DSF file format headers to extract DSD stream metadata.
 *
 * DSF file structure:
 * 1. DSD Chunk: File signature and sizes
 * 2. Format Chunk: DSD format info (rate, channels, block size)
 * 3. Data Chunk: Interleaved DSD blocks per channel
 *
 * This parser does NOT convert DSD to PCM. It exposes raw DSD data
 * for use with DoP or Native DSD transport.
 */
class DsfParser {
public:
    DsfParser() = default;
    ~DsfParser() = default;

    /**
     * Parse DSF file headers from a buffer.
     * @param data Pointer to DSF file data (at least headers)
     * @param length Available data length
     * @return true if parsing succeeded
     */
    bool parse(const uint8_t* data, size_t length);

    /**
     * Get parsed file info.
     */
    const DsfFileInfo& getFileInfo() const { return fileInfo_; }

    /**
     * Check if the file is a valid DSF file.
     */
    bool isValid() const { return fileInfo_.isValid; }

    /**
     * Get DSD sample rate (e.g., 2822400 for DSD64).
     */
    uint32_t getSampleRate() const { return fileInfo_.sampleRate; }

    /**
     * Get channel count.
     */
    uint32_t getChannelCount() const { return fileInfo_.channelCount; }

    /**
     * Get block size per channel in bytes.
     */
    uint32_t getBlockSizePerChannel() const { return fileInfo_.blockSizePerChannel; }

    /**
     * Get the total number of DSD sample bytes per channel.
     * Each byte contains 8 DSD bits (1-bit samples).
     */
    uint64_t getDsdBytesPerChannel() const {
        return (fileInfo_.sampleCount + 7) / 8;
    }

    /**
     * Get offset to the DSD data within the file.
     */
    uint64_t getDataOffset() const { return fileInfo_.dataOffset; }

    /**
     * Extract deinterleaved DSD data for each channel from raw DSF data block.
     * DSF stores data in interleaved blocks: [block_ch0][block_ch1]...[block_chN] repeating.
     * @param rawData Pointer to raw DSF data section
     * @param rawLength Length of raw data
     * @param channelData Output: vector of per-channel DSD byte vectors
     * @return true if extraction succeeded
     */
    bool extractChannelData(const uint8_t* rawData, size_t rawLength,
                            std::vector<std::vector<uint8_t>>& channelData) const;

    /**
     * Check if a DSD rate is a standard DSD rate (DSD64/128/256).
     */
    static bool isStandardDsdRate(uint32_t rate);

    /**
     * Get the DSD multiplier (64, 128, 256) for a given rate.
     * @return 0 if not a recognized DSD rate
     */
    static uint32_t getDsdMultiplier(uint32_t rate);

private:
    DsfFileInfo fileInfo_;

    bool parseDsdChunk(const uint8_t* data, size_t length);
    bool parseFmtChunk(const uint8_t* data, size_t length);
    bool parseDataChunkHeader(const uint8_t* data, size_t length);

    static uint32_t readU32LE(const uint8_t* p) {
        return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
    }
    static uint64_t readU64LE(const uint8_t* p) {
        return static_cast<uint64_t>(readU32LE(p)) |
               (static_cast<uint64_t>(readU32LE(p + 4)) << 32);
    }
};

} // namespace dsd
} // namespace bitperfect
