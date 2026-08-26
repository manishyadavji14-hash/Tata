#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

namespace bitperfect {
namespace dop {

/**
 * DoP (DSD over PCM) marker bytes.
 * These alternate per frame to allow DAC detection of DoP content.
 */
constexpr uint8_t DOP_MARKER_A = 0x05;
constexpr uint8_t DOP_MARKER_B = 0xFA;

/**
 * DoP transport rates mapping.
 * Each DoP frame carries 16 DSD bits (2 bytes) in a 24-bit PCM container.
 * DSD64 (2.8224 MHz) -> 176400 Hz PCM rate
 * DSD128 (5.6448 MHz) -> 352800 Hz PCM rate
 * DSD256 (11.2896 MHz) -> 705600 Hz PCM rate
 */
constexpr uint32_t DOP_RATE_DSD64 = 176400;
constexpr uint32_t DOP_RATE_DSD128 = 352800;
constexpr uint32_t DOP_RATE_DSD256 = 705600;

/**
 * DoP frame size in bytes (24-bit container = 3 bytes per sample).
 */
constexpr size_t DOP_FRAME_BYTES = 3;

/**
 * DSD bytes consumed per DoP frame (2 DSD bytes per 24-bit DoP sample).
 */
constexpr size_t DSD_BYTES_PER_DOP_FRAME = 2;

/**
 * DoP Encoder - encodes raw DSD data into DoP (DSD over PCM) frames.
 *
 * Standard DoP frame format (24-bit, little-endian as stored):
 *   Byte 0 (LSB): DSD sample byte (second / lower)
 *   Byte 1:       DSD sample byte (first / upper)
 *   Byte 2 (MSB): Marker (0x05 or 0xFA, alternating per frame)
 *
 * Critical requirements:
 * - Markers alternate per FRAME: 0x05, 0xFA, 0x05, 0xFA...
 * - Marker state PERSISTS across encode() calls (buffer boundaries)
 * - Every DSD bit is preserved byte-for-byte
 * - Stereo: Left DSD -> Left DoP channel, Right DSD -> Right DoP channel
 * - Each channel encoded independently with shared frame counter
 */
class DopEncoder {
public:
    DopEncoder() = default;
    ~DopEncoder() = default;

    /**
     * Configure the encoder.
     * @param channels Number of audio channels (typically 2 for stereo)
     * @param dsdRate DSD sample rate (e.g., 2822400 for DSD64)
     */
    void configure(uint32_t channels, uint32_t dsdRate);

    /**
     * Encode DSD data for a single channel into DoP frames.
     * Marker state persists across calls.
     *
     * @param dsdData Raw DSD bytes for one channel
     * @param dsdLength Number of DSD bytes to encode
     * @param dopOutput Output buffer for DoP frames (must be >= (dsdLength/2)*3)
     * @param dopMaxLength Maximum output buffer size
     * @return Number of bytes written to dopOutput
     */
    size_t encode(const uint8_t* dsdData, size_t dsdLength,
                  uint8_t* dopOutput, size_t dopMaxLength);

    /**
     * Encode stereo DSD data into interleaved DoP frames.
     * Takes separate left and right DSD channels.
     *
     * @param leftDsd Left channel DSD bytes
     * @param rightDsd Right channel DSD bytes
     * @param dsdLength Number of DSD bytes per channel
     * @param dopOutput Output buffer for interleaved stereo DoP frames
     * @param dopMaxLength Maximum output buffer size
     * @return Number of bytes written to dopOutput
     */
    size_t encodeStereo(const uint8_t* leftDsd, const uint8_t* rightDsd,
                        size_t dsdLength,
                        uint8_t* dopOutput, size_t dopMaxLength);

    /**
     * Reset encoder state (marker sequence restarts from 0x05).
     */
    void reset();

    /**
     * Get the DoP transport rate for the configured DSD rate.
     * @return PCM transport rate in Hz
     */
    uint32_t getTransportRate() const { return transportRate_; }

    /**
     * Get the current marker state (true = next marker is 0x05).
     */
    bool getMarkerState() const { return markerIsA_; }

    /**
     * Get total DoP frames encoded since last reset.
     */
    uint64_t getTotalFramesEncoded() const { return totalFramesEncoded_; }

    /**
     * Get the configured channel count.
     */
    uint32_t getChannelCount() const { return channels_; }

    /**
     * Calculate DoP transport rate from DSD sample rate.
     * @param dsdRate DSD sample rate
     * @return PCM transport rate in Hz, or 0 if not a valid DSD rate
     */
    static uint32_t calculateTransportRate(uint32_t dsdRate);

    /**
     * Calculate required output buffer size for given DSD input.
     * @param dsdBytes Number of DSD bytes per channel
     * @return Required DoP output bytes per channel
     */
    static size_t calculateOutputSize(size_t dsdBytes);

    /**
     * Check if there are pending (odd) DSD bytes from a previous encode call.
     */
    bool hasPendingByte() const { return hasPendingByte_; }

private:
    uint32_t channels_ = 2;
    uint32_t dsdRate_ = 0;
    uint32_t transportRate_ = 0;
    bool markerIsA_ = true;  // true = next marker is 0x05
    uint64_t totalFramesEncoded_ = 0;

    // Handle partial input: if odd number of DSD bytes, save last byte
    bool hasPendingByte_ = false;
    uint8_t pendingByte_ = 0;
};

} // namespace dop
} // namespace bitperfect
