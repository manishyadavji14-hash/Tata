#pragma once

#include "audio_decoder.h"
#include <memory>
#include <string>

namespace bitperfect {
namespace decoder {

/**
 * Supported decoder types.
 */
enum class DecoderType : uint8_t {
    UNKNOWN = 0,
    WAV,
    FLAC,
    DSF,
    DFF,     // Future: DSDIFF
    AIFF,    // Future: AIFF/AIFC
    ALAC,    // Future: Apple Lossless
    APE,     // Future: Monkey's Audio
    MP3,     // Future: MPEG Layer 3
    AAC,     // Future: Advanced Audio Coding
    OGG,     // Future: Ogg Vorbis
    OPUS     // Future: Opus
};

/**
 * DecoderFactory - creates appropriate decoder instances based on file type.
 *
 * Detection priority:
 * 1. Magic bytes (most reliable)
 * 2. File extension (fallback)
 *
 * Architecture note: New decoder types can be added by implementing
 * AudioDecoder and registering here. The factory pattern allows the
 * rest of the engine to remain format-agnostic.
 */
class DecoderFactory {
public:
    /**
     * Create a decoder from file path.
     * Detects type from extension, then validates with magic bytes on open.
     * @param path File path
     * @return Decoder instance or nullptr if type not supported
     */
    static std::unique_ptr<AudioDecoder> createFromPath(const std::string& path);

    /**
     * Create a decoder from magic bytes.
     * @param data First bytes of the file (at least 12 bytes recommended)
     * @param size Available bytes
     * @return Decoder instance or nullptr if type not recognized
     */
    static std::unique_ptr<AudioDecoder> createFromMagic(const uint8_t* data, size_t size);

    /**
     * Create a decoder of a specific type.
     * @param type Decoder type to create
     * @return Decoder instance or nullptr if type not implemented
     */
    static std::unique_ptr<AudioDecoder> create(DecoderType type);

    /**
     * Detect decoder type from file extension.
     * @param path File path or name
     * @return Detected type or UNKNOWN
     */
    static DecoderType detectFromExtension(const std::string& path);

    /**
     * Detect decoder type from magic bytes.
     * @param data First bytes of the file
     * @param size Available bytes (need at least 4)
     * @return Detected type or UNKNOWN
     */
    static DecoderType detectFromMagic(const uint8_t* data, size_t size);

    /**
     * Check if a decoder type is currently supported (implemented).
     */
    static bool isSupported(DecoderType type);

    /**
     * Get file extensions for a decoder type.
     */
    static const char* getExtension(DecoderType type);

    /**
     * Get a human-readable name for a decoder type.
     */
    static const char* getTypeName(DecoderType type);
};

} // namespace decoder
} // namespace bitperfect
