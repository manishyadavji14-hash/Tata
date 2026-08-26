#pragma once

#include "usb_descriptors.h"
#include <vector>
#include <cstdint>
#include <optional>

namespace bitperfect {
namespace usb {

/**
 * USB Audio Device descriptor parser.
 * Parses raw USB configuration descriptors to extract USB Audio Class
 * (UAC1 and UAC2) information including streaming interfaces, formats,
 * sample rates, and clock topology.
 */
class UsbAudioDevice {
public:
    UsbAudioDevice() = default;
    ~UsbAudioDevice() = default;

    /**
     * Parse raw configuration descriptor bytes.
     * @param data Pointer to raw descriptor bytes
     * @param length Length of descriptor data
     * @return true if parsing succeeded and audio device was found
     */
    bool parseDescriptors(const uint8_t* data, size_t length);

    /**
     * Get the parsed device descriptors.
     */
    const AudioDeviceDescriptors& getDescriptors() const { return descriptors_; }

    /**
     * Get the UAC version detected.
     */
    UacVersion getVersion() const { return descriptors_.version; }

    /**
     * Get all supported sample rates for a given interface and alt setting.
     */
    std::vector<uint32_t> getSupportedSampleRates(uint8_t interfaceNum, uint8_t altSetting) const;

    /**
     * Get the best alternate setting for a given format/rate combination.
     * @return Alternate setting index, or nullopt if no match
     */
    std::optional<uint8_t> findBestAltSetting(uint8_t interfaceNum,
                                               uint32_t sampleRate,
                                               PcmFormat format) const;

    /**
     * Check if the device supports a specific PCM format.
     */
    bool supportsFormat(PcmFormat format) const;

    /**
     * Get output streaming interface number (for playback).
     */
    std::optional<uint8_t> getOutputStreamingInterface() const;

private:
    AudioDeviceDescriptors descriptors_;

    // Parsing helpers
    bool parseAudioControlInterface(const uint8_t* data, size_t length);
    bool parseAcHeader(const uint8_t* data, size_t length);
    bool parseInputTerminal(const uint8_t* data, size_t length);
    bool parseOutputTerminal(const uint8_t* data, size_t length);
    bool parseFeatureUnit(const uint8_t* data, size_t length);
    bool parseClockSource(const uint8_t* data, size_t length);
    bool parseClockSelector(const uint8_t* data, size_t length);
    bool parseClockMultiplier(const uint8_t* data, size_t length);
    bool parseAudioStreamingInterface(const uint8_t* data, size_t length,
                                       AlternateSettingInfo& altSetting);
    bool parseAsGeneral(const uint8_t* data, size_t length, AlternateSettingInfo& alt);
    bool parseFormatType(const uint8_t* data, size_t length, AlternateSettingInfo& alt);
    bool parseEndpoint(const uint8_t* data, size_t length, AlternateSettingInfo& alt);
    bool parseAudioEndpoint(const uint8_t* data, size_t length, AlternateSettingInfo& alt);

    // Utility
    static uint16_t readU16LE(const uint8_t* p) { return p[0] | (p[1] << 8); }
    static uint32_t readU24LE(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16); }
    static uint32_t readU32LE(const uint8_t* p) {
        return p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24);
    }
};

} // namespace usb
} // namespace bitperfect
