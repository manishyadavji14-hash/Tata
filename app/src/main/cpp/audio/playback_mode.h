#pragma once

#include "../usb/usb_descriptors.h"
#include "../native_dsd/native_dsd_transport.h"
#include "format_detector.h"
#include <cstdint>
#include <string>
#include <vector>

namespace bitperfect {
namespace audio {

/**
 * Playback modes.
 */
enum class PlaybackMode : uint8_t {
    PCM = 0,
    DOP,
    NATIVE_DSD
};

/**
 * Result of mode selection with full configuration details.
 */
struct ModeSelectionResult {
    PlaybackMode mode = PlaybackMode::PCM;
    uint32_t transportRate = 0;     // PCM sample rate or DoP transport rate
    uint8_t bitDepth = 0;           // Bit depth for PCM, 24 for DoP
    uint32_t channels = 0;
    bool valid = false;
    std::string reason;             // Human-readable reason for selection
};

/**
 * DAC capabilities summary.
 */
struct DacCapabilities {
    bool supportsNativeDsd = false;
    bool supportsDop = false;       // Supports 24-bit at DoP transport rates
    std::vector<uint32_t> nativeDsdRates;   // Supported native DSD rates
    std::vector<uint32_t> pcmSampleRates;   // Supported PCM rates
    std::vector<uint8_t> pcmBitDepths;      // Supported PCM bit depths (16, 24, 32)
    uint32_t maxSampleRate = 0;
};

/**
 * PlaybackModeSelector - automatically selects the best playback mode
 * based on source format and DAC capabilities.
 *
 * Priority order: Native DSD > DoP > PCM
 *
 * Selection rules:
 * - PCM source: always use PCM mode
 * - DSD source + Native DSD capable DAC: use Native DSD
 * - DSD source + DoP capable DAC (24-bit @ transport rate): use DoP
 * - DSD source + neither: this is an unsupported combination
 *
 * The selector never misrepresents the active mode.
 */
class PlaybackModeSelector {
public:
    PlaybackModeSelector() = default;
    ~PlaybackModeSelector() = default;

    /**
     * Select the best playback mode for a given source and DAC.
     * @param sourceInfo Detected source format information
     * @param dacCaps DAC capability information
     * @return Mode selection result with full details
     */
    ModeSelectionResult selectMode(const FormatInfo& sourceInfo,
                                    const DacCapabilities& dacCaps) const;

    /**
     * Determine DAC capabilities from USB descriptors + native DSD check.
     * @param descriptors Parsed USB audio device descriptors
     * @param nativeDsdTransport Optional native DSD transport for capability check
     * @return DAC capabilities summary
     */
    static DacCapabilities determineDacCapabilities(
            const usb::AudioDeviceDescriptors& descriptors,
            const native_dsd::NativeDsdTransport* nativeDsdTransport = nullptr);

    /**
     * Get human-readable mode name.
     */
    static const char* modeName(PlaybackMode mode);

    /**
     * Check if DoP is possible at a given DSD rate with given DAC capabilities.
     * Requires the DAC to support 24-bit at the corresponding transport rate.
     */
    static bool isDopPossible(uint32_t dsdRate, const DacCapabilities& dacCaps);

private:
    ModeSelectionResult selectForPcm(const FormatInfo& sourceInfo,
                                      const DacCapabilities& dacCaps) const;
    ModeSelectionResult selectForDsd(const FormatInfo& sourceInfo,
                                      const DacCapabilities& dacCaps) const;
};

} // namespace audio
} // namespace bitperfect
