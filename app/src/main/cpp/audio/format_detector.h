#pragma once

#include <cstdint>
#include <cstddef>
#include <string>

namespace bitperfect {
namespace audio {

/**
 * Audio file types.
 */
enum class AudioFileType : uint8_t {
    UNKNOWN = 0,
    WAV,
    FLAC,
    DSF,
    DFF,     // DSDIFF (future support)
    AIFF
};

/**
 * Audio content type (PCM vs DSD).
 */
enum class AudioContentType : uint8_t {
    UNKNOWN = 0,
    PCM,
    DSD
};

/**
 * Detected format information.
 */
struct FormatInfo {
    AudioFileType fileType = AudioFileType::UNKNOWN;
    AudioContentType contentType = AudioContentType::UNKNOWN;
    uint32_t sampleRate = 0;       // PCM rate or DSD rate
    uint8_t bitDepth = 0;          // PCM bit depth (16, 24, 32) or 1 for DSD
    uint32_t channels = 0;
    uint64_t totalSamples = 0;     // Total samples per channel
    bool isValid = false;
    std::string errorMessage;
};

/**
 * Format Detector - identifies audio file type and extracts parameters.
 *
 * Detects WAV, FLAC, and DSF files from header signatures.
 * Determines whether content is PCM or DSD.
 * Extracts sample rate, bit depth, and channel count.
 */
class FormatDetector {
public:
    FormatDetector() = default;
    ~FormatDetector() = default;

    /**
     * Detect format from file header bytes.
     * @param data Pointer to file header data (first 128 bytes is usually enough)
     * @param length Available data length
     * @return Detected format info
     */
    FormatInfo detect(const uint8_t* data, size_t length) const;

    /**
     * Detect format from file extension (less reliable).
     * @param filename File name or path
     * @return File type based on extension
     */
    static AudioFileType detectFromExtension(const std::string& filename);

    /**
     * Check if a file type represents DSD content.
     */
    static bool isDsdFormat(AudioFileType type);

    /**
     * Check if a file type represents PCM content.
     */
    static bool isPcmFormat(AudioFileType type);

private:
    FormatInfo detectWav(const uint8_t* data, size_t length) const;
    FormatInfo detectFlac(const uint8_t* data, size_t length) const;
    FormatInfo detectDsf(const uint8_t* data, size_t length) const;

    static uint16_t readU16LE(const uint8_t* p) { return p[0] | (p[1] << 8); }
    static uint32_t readU32LE(const uint8_t* p) {
        return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
    }
    static uint32_t readU32BE(const uint8_t* p) {
        return (static_cast<uint32_t>(p[0]) << 24) | (p[1] << 16) | (p[2] << 8) | p[3];
    }
};

} // namespace audio
} // namespace bitperfect
