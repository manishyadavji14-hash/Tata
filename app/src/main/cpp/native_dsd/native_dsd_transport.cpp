#include "native_dsd_transport.h"
#include <algorithm>
#include <cstring>

namespace bitperfect {
namespace native_dsd {

DsdCapability NativeDsdTransport::inspectCapabilities(
        const usb::AudioDeviceDescriptors& descriptors) {
    capability_ = DsdCapability{};

    // Check based on UAC version
    if (descriptors.version == usb::UacVersion::UAC1) {
        checkUac1DsdSupport(descriptors);
    } else if (descriptors.version == usb::UacVersion::UAC2) {
        checkUac2DsdSupport(descriptors);
    }

    return capability_;
}

bool NativeDsdTransport::supportsRate(uint32_t dsdRate) const {
    if (!capability_.supported) return false;
    return std::find(capability_.supportedDsdRates.begin(),
                     capability_.supportedDsdRates.end(),
                     dsdRate) != capability_.supportedDsdRates.end();
}

bool NativeDsdTransport::configure(uint32_t dsdRate, uint32_t channels) {
    if (!capability_.supported) {
        state_ = NativeDsdState::ERROR;
        return false;
    }

    if (!supportsRate(dsdRate)) {
        state_ = NativeDsdState::ERROR;
        return false;
    }

    configuredRate_ = dsdRate;
    configuredChannels_ = channels;
    state_ = NativeDsdState::CONFIGURED;
    return true;
}

size_t NativeDsdTransport::preparePacket(const uint8_t* dsdData, size_t dsdLength,
                                          uint8_t* usbPacket, size_t maxPacketSize) {
    if (!dsdData || dsdLength == 0 || !usbPacket || maxPacketSize == 0) {
        return 0;
    }

    if (state_ != NativeDsdState::CONFIGURED && state_ != NativeDsdState::STREAMING) {
        return 0;
    }

    // For native DSD, we pass raw DSD bytes directly to the endpoint
    // No DoP markers or PCM encapsulation
    size_t toCopy = std::min(dsdLength, maxPacketSize);
    std::memcpy(usbPacket, dsdData, toCopy);

    state_ = NativeDsdState::STREAMING;
    return toCopy;
}

void NativeDsdTransport::reset() {
    state_ = NativeDsdState::IDLE;
    configuredRate_ = 0;
    configuredChannels_ = 0;
}

bool NativeDsdTransport::checkUac1DsdSupport(
        const usb::AudioDeviceDescriptors& descriptors) {
    // Look for alternate settings with TYPE_I_RAW_DATA format tag
    for (const auto& iface : descriptors.streamingInterfaces) {
        for (const auto& alt : iface.altSettings) {
            if (!alt.is_valid) continue;

            // Check if format tag indicates raw data (DSD)
            if (alt.as_general_uac1.wFormatTag == usb::FormatTag::TYPE_I_RAW_DATA) {
                capability_.supported = true;
                capability_.type = DsdInterfaceType::RAW_DSD;
                capability_.interfaceNumber = iface.interfaceNumber;
                capability_.altSetting = alt.interface_desc.bAlternateSetting;
                capability_.endpointAddress = alt.endpoint.bEndpointAddress;
                capability_.maxPacketSize = alt.endpoint.wMaxPacketSize;
                capability_.description = "UAC1 Native DSD (RAW_DATA format)";

                // Determine supported rates from packet size
                uint8_t channels = alt.format_uac1.bNrChannels;
                if (channels == 0) channels = 2;
                capability_.supportedDsdRates = determineSupportedRates(
                    alt.endpoint.wMaxPacketSize, channels);

                return true;
            }
        }
    }
    return false;
}

bool NativeDsdTransport::checkUac2DsdSupport(
        const usb::AudioDeviceDescriptors& descriptors) {
    // Look for alternate settings with TYPE_I_RAW_DATA format bit in UAC2
    for (const auto& iface : descriptors.streamingInterfaces) {
        for (const auto& alt : iface.altSettings) {
            if (!alt.is_valid) continue;

            // UAC2: check bmFormats for raw data bit (bit 31)
            if (alt.as_general_uac2.bmFormats &
                static_cast<uint32_t>(usb::Uac2FormatBit::TYPE_I_RAW_DATA)) {
                capability_.supported = true;
                capability_.type = DsdInterfaceType::RAW_DSD;
                capability_.interfaceNumber = iface.interfaceNumber;
                capability_.altSetting = alt.interface_desc.bAlternateSetting;
                capability_.endpointAddress = alt.endpoint.bEndpointAddress;
                capability_.maxPacketSize = alt.endpoint.wMaxPacketSize;
                capability_.description = "UAC2 Native DSD (RAW_DATA format)";

                uint8_t channels = alt.as_general_uac2.bNrChannels;
                if (channels == 0) channels = 2;
                capability_.supportedDsdRates = determineSupportedRates(
                    alt.endpoint.wMaxPacketSize, channels);

                return true;
            }
        }
    }
    return false;
}

std::vector<uint32_t> NativeDsdTransport::determineSupportedRates(
        uint16_t maxPacketSize, uint8_t channels) {
    std::vector<uint32_t> rates;

    // DSD byte rate = DSD_sample_rate / 8 (since DSD is 1-bit)
    // Packets per second at USB high-speed = 8000 (1 per microframe)
    // Required bytes per packet = (dsd_rate / 8) * channels / 8000

    // DSD64: (2822400 / 8) * 2 / 8000 = 88.2 bytes/packet
    // DSD128: (5644800 / 8) * 2 / 8000 = 176.4 bytes/packet
    // DSD256: (11289600 / 8) * 2 / 8000 = 352.8 bytes/packet

    auto requiredBytesPerPacket = [channels](uint32_t dsdRate) -> uint32_t {
        // Ceiling of (dsdRate / 8 * channels / 8000)
        uint64_t byteRate = static_cast<uint64_t>(dsdRate) / 8 * channels;
        return static_cast<uint32_t>((byteRate + 7999) / 8000);
    };

    // Check DSD64
    if (maxPacketSize >= requiredBytesPerPacket(2822400)) {
        rates.push_back(2822400);
    }

    // Check DSD128
    if (maxPacketSize >= requiredBytesPerPacket(5644800)) {
        rates.push_back(5644800);
    }

    // Check DSD256
    if (maxPacketSize >= requiredBytesPerPacket(11289600)) {
        rates.push_back(11289600);
    }

    return rates;
}

} // namespace native_dsd
} // namespace bitperfect
