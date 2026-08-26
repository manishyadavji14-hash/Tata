#pragma once

#include "../usb/usb_types.h"
#include <cstdint>
#include <cstddef>
#include <vector>

namespace bitperfect {
namespace pcm {

using usb::PcmFormat;

/**
 * PCM format information.
 */
struct PcmFormatInfo {
    PcmFormat format = PcmFormat::S16_LE;
    uint8_t bitsPerSample = 16;
    uint8_t bytesPerSample = 2;   // Container size per channel
    uint8_t channels = 2;
    uint32_t sampleRate = 44100;

    uint32_t bytesPerFrame() const { return bytesPerSample * channels; }
    uint32_t bytesPerSecond() const { return bytesPerFrame() * sampleRate; }
    uint32_t bitsPerSecond() const { return bitsPerSample * channels * sampleRate; }
};

/**
 * PCM Engine - handles bit-perfect PCM format processing.
 *
 * Key principle: NEVER alter the audio data path. The engine performs format
 * packing/unpacking only when required by the USB endpoint format, preserving
 * every bit of the source audio.
 */
class PcmEngine {
public:
    PcmEngine() = default;
    ~PcmEngine() = default;

    /**
     * Configure the engine for a specific format.
     */
    void configure(const PcmFormatInfo& format);

    /**
     * Get the current format configuration.
     */
    const PcmFormatInfo& getFormat() const { return format_; }

    /**
     * Convert from source format to USB endpoint format (bit-perfect).
     * @param src Source buffer in source format
     * @param srcSize Size of source buffer in bytes
     * @param dst Destination buffer for USB format
     * @param dstSize Available size in destination buffer
     * @param srcFormat Source PCM format
     * @param dstFormat Destination PCM format (USB endpoint)
     * @return Number of bytes written to dst, or 0 on error
     */
    static size_t convert(const uint8_t* src, size_t srcSize,
                          uint8_t* dst, size_t dstSize,
                          PcmFormat srcFormat, PcmFormat dstFormat,
                          uint8_t channels);

    /**
     * Pack samples directly into USB transfer buffer (no conversion, bit-perfect passthrough).
     * This is the preferred path when source format matches endpoint format.
     * @param src Source audio data
     * @param srcSize Source data size in bytes
     * @param dst USB transfer buffer
     * @param dstSize Available space in USB buffer
     * @return Number of bytes copied
     */
    static size_t passthrough(const uint8_t* src, size_t srcSize,
                              uint8_t* dst, size_t dstSize);

    /**
     * Convert 16-bit to 24-bit packed (3 bytes per sample).
     * Zero-pads the least significant byte.
     */
    static size_t convert16to24_3(const uint8_t* src, size_t srcFrames,
                                   uint8_t* dst, size_t dstSize, uint8_t channels);

    /**
     * Convert 16-bit to 32-bit (4 bytes per sample).
     * Zero-pads the two least significant bytes. Bit-perfect in upper bits.
     */
    static size_t convert16to32(const uint8_t* src, size_t srcFrames,
                                 uint8_t* dst, size_t dstSize, uint8_t channels);

    /**
     * Convert 24-bit packed (3 bytes) to 32-bit (4 bytes per sample).
     * Zero-pads the least significant byte. Bit-perfect in upper 24 bits.
     */
    static size_t convert24_3to32(const uint8_t* src, size_t srcFrames,
                                   uint8_t* dst, size_t dstSize, uint8_t channels);

    /**
     * Convert 32-bit to 24-bit packed (3 bytes per sample).
     * Truncates the least significant byte. Bit-perfect in upper 24 bits.
     */
    static size_t convert32to24_3(const uint8_t* src, size_t srcFrames,
                                   uint8_t* dst, size_t dstSize, uint8_t channels);

    /**
     * Convert 24-bit packed (3 bytes) to 24-in-32 container.
     * Zero-pads the least significant byte.
     */
    static size_t convert24_3to24in32(const uint8_t* src, size_t srcFrames,
                                       uint8_t* dst, size_t dstSize, uint8_t channels);

    /**
     * Convert 24-in-32 container to 24-bit packed (3 bytes).
     * Strips the padding byte.
     */
    static size_t convert24in32to24_3(const uint8_t* src, size_t srcFrames,
                                       uint8_t* dst, size_t dstSize, uint8_t channels);

    /**
     * Get bytes per sample for a given format.
     */
    static uint8_t getBytesPerSample(PcmFormat format);

    /**
     * Get bits of actual audio data per sample.
     */
    static uint8_t getBitsPerSample(PcmFormat format);

    /**
     * Check if two formats are compatible for bit-perfect passthrough.
     */
    static bool isPassthroughCompatible(PcmFormat src, PcmFormat dst);

    /**
     * Verify bit-perfect integrity by comparing source and destination.
     * Used for testing and diagnostics.
     */
    static bool verifyBitPerfect(const uint8_t* src, size_t srcSize,
                                  const uint8_t* dst, size_t dstSize,
                                  PcmFormat srcFormat, PcmFormat dstFormat,
                                  uint8_t channels);

private:
    PcmFormatInfo format_;
};

} // namespace pcm
} // namespace bitperfect
