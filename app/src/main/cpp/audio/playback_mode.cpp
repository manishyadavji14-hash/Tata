#include "playback_mode.h"
#include "../dop/dop_encoder.h"
#include <algorithm>

namespace bitperfect {
namespace audio {

ModeSelectionResult PlaybackModeSelector::selectMode(const FormatInfo& sourceInfo,
                                                      const DacCapabilities& dacCaps) const {
    if (!sourceInfo.isValid) {
        ModeSelectionResult result;
        result.valid = false;
        result.reason = "Invalid source format";
        return result;
    }

    if (sourceInfo.contentType == AudioContentType::PCM) {
        return selectForPcm(sourceInfo, dacCaps);
    } else if (sourceInfo.contentType == AudioContentType::DSD) {
        return selectForDsd(sourceInfo, dacCaps);
    }

    ModeSelectionResult result;
    result.valid = false;
    result.reason = "Unknown content type";
    return result;
}

ModeSelectionResult PlaybackModeSelector::selectForPcm(const FormatInfo& sourceInfo,
                                                        const DacCapabilities& dacCaps) const {
    ModeSelectionResult result;
    result.mode = PlaybackMode::PCM;
    result.transportRate = sourceInfo.sampleRate;
    result.bitDepth = sourceInfo.bitDepth;
    result.channels = sourceInfo.channels;

    // Check if the DAC supports this rate
    bool rateSupported = std::find(dacCaps.pcmSampleRates.begin(),
                                    dacCaps.pcmSampleRates.end(),
                                    sourceInfo.sampleRate) != dacCaps.pcmSampleRates.end();

    if (rateSupported) {
        result.valid = true;
        result.reason = "PCM playback at native rate";
    } else if (!dacCaps.pcmSampleRates.empty()) {
        // Find closest supported rate
        uint32_t closest = dacCaps.pcmSampleRates[0];
        uint32_t minDiff = (sourceInfo.sampleRate > closest) ?
                           (sourceInfo.sampleRate - closest) : (closest - sourceInfo.sampleRate);
        for (uint32_t rate : dacCaps.pcmSampleRates) {
            uint32_t diff = (sourceInfo.sampleRate > rate) ?
                           (sourceInfo.sampleRate - rate) : (rate - sourceInfo.sampleRate);
            if (diff < minDiff) {
                minDiff = diff;
                closest = rate;
            }
        }
        result.transportRate = closest;
        result.valid = true;
        result.reason = "PCM playback at nearest supported rate";
    } else {
        result.valid = false;
        result.reason = "No supported PCM rates available";
    }

    return result;
}

ModeSelectionResult PlaybackModeSelector::selectForDsd(const FormatInfo& sourceInfo,
                                                        const DacCapabilities& dacCaps) const {
    ModeSelectionResult result;
    result.channels = sourceInfo.channels;

    // Priority 1: Native DSD
    if (dacCaps.supportsNativeDsd) {
        bool rateSupported = std::find(dacCaps.nativeDsdRates.begin(),
                                        dacCaps.nativeDsdRates.end(),
                                        sourceInfo.sampleRate) != dacCaps.nativeDsdRates.end();
        if (rateSupported) {
            result.mode = PlaybackMode::NATIVE_DSD;
            result.transportRate = sourceInfo.sampleRate;
            result.bitDepth = 1;
            result.valid = true;
            result.reason = "Native DSD - DAC supports this rate natively";
            return result;
        }
    }

    // Priority 2: DoP
    if (isDopPossible(sourceInfo.sampleRate, dacCaps)) {
        uint32_t dopRate = dop::DopEncoder::calculateTransportRate(sourceInfo.sampleRate);
        result.mode = PlaybackMode::DOP;
        result.transportRate = dopRate;
        result.bitDepth = 24;
        result.valid = true;
        result.reason = "DoP - DSD over PCM at " + std::to_string(dopRate) + " Hz";
        return result;
    }

    // No DSD playback possible
    result.mode = PlaybackMode::PCM;
    result.valid = false;
    result.reason = "DSD playback not supported - DAC lacks native DSD and DoP capability";
    return result;
}

DacCapabilities PlaybackModeSelector::determineDacCapabilities(
        const usb::AudioDeviceDescriptors& descriptors,
        const native_dsd::NativeDsdTransport* nativeDsdTransport) {
    DacCapabilities caps;

    // Check native DSD
    if (nativeDsdTransport && nativeDsdTransport->isAvailable()) {
        caps.supportsNativeDsd = true;
        caps.nativeDsdRates = nativeDsdTransport->getCapability().supportedDsdRates;
    }

    // Extract PCM capabilities from streaming interfaces
    for (const auto& iface : descriptors.streamingInterfaces) {
        for (const auto& alt : iface.altSettings) {
            if (!alt.is_valid) continue;

            if (descriptors.version == usb::UacVersion::UAC1) {
                // Get bit depth
                uint8_t bitRes = alt.format_uac1.bBitResolution;
                if (bitRes > 0 && std::find(caps.pcmBitDepths.begin(),
                    caps.pcmBitDepths.end(), bitRes) == caps.pcmBitDepths.end()) {
                    caps.pcmBitDepths.push_back(bitRes);
                }

                // Get sample rates
                for (uint32_t rate : alt.format_uac1.sampleRates) {
                    if (std::find(caps.pcmSampleRates.begin(),
                        caps.pcmSampleRates.end(), rate) == caps.pcmSampleRates.end()) {
                        caps.pcmSampleRates.push_back(rate);
                    }
                    if (rate > caps.maxSampleRate) {
                        caps.maxSampleRate = rate;
                    }
                }
            } else {
                // UAC2: bit depth from format descriptor
                uint8_t bitRes = alt.format_uac2.bBitResolution;
                if (bitRes > 0 && std::find(caps.pcmBitDepths.begin(),
                    caps.pcmBitDepths.end(), bitRes) == caps.pcmBitDepths.end()) {
                    caps.pcmBitDepths.push_back(bitRes);
                }
            }
        }
    }

    // Check if DoP is possible (need 24-bit support at DoP transport rates)
    caps.supportsDop = false;
    bool has24Bit = std::find(caps.pcmBitDepths.begin(), caps.pcmBitDepths.end(),
                              24) != caps.pcmBitDepths.end();
    if (has24Bit) {
        // Check if any DoP transport rate is supported
        std::vector<uint32_t> dopRates = {176400, 352800, 705600};
        for (uint32_t rate : dopRates) {
            if (std::find(caps.pcmSampleRates.begin(), caps.pcmSampleRates.end(),
                          rate) != caps.pcmSampleRates.end()) {
                caps.supportsDop = true;
                break;
            }
        }
    }

    return caps;
}

const char* PlaybackModeSelector::modeName(PlaybackMode mode) {
    switch (mode) {
        case PlaybackMode::PCM: return "PCM";
        case PlaybackMode::DOP: return "DoP";
        case PlaybackMode::NATIVE_DSD: return "Native DSD";
        default: return "Unknown";
    }
}

bool PlaybackModeSelector::isDopPossible(uint32_t dsdRate, const DacCapabilities& dacCaps) {
    // Check if DAC supports 24-bit at the DoP transport rate
    bool has24Bit = std::find(dacCaps.pcmBitDepths.begin(), dacCaps.pcmBitDepths.end(),
                              24) != dacCaps.pcmBitDepths.end();
    if (!has24Bit) return false;

    uint32_t transportRate = dop::DopEncoder::calculateTransportRate(dsdRate);
    return std::find(dacCaps.pcmSampleRates.begin(), dacCaps.pcmSampleRates.end(),
                     transportRate) != dacCaps.pcmSampleRates.end();
}

} // namespace audio
} // namespace bitperfect
