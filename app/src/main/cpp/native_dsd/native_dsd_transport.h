#pragma once

#include "../usb/usb_descriptors.h"
#include <cstdint>
#include <cstddef>
#include <vector>
#include <string>

namespace bitperfect {
namespace native_dsd {

/**
 * DSD interface type identifiers.
 * Used to detect DSD-capable alternate settings in USB descriptors.
 */
enum class DsdInterfaceType : uint8_t {
    NONE = 0,
    RAW_DSD,        // Raw DSD (TYPE_I_RAW_DATA format tag)
    ASIO_DSD,       // ASIO Native DSD (vendor-specific)
    DOP_MARKER      // DoP detection (not true native)
};

/**
 * DSD capability information for a USB audio interface.
 */
struct DsdCapability {
    bool supported = false;
    DsdInterfaceType type = DsdInterfaceType::NONE;
    uint8_t interfaceNumber = 0;
    uint8_t altSetting = 0;
    uint8_t endpointAddress = 0;
    uint16_t maxPacketSize = 0;
    std::vector<uint32_t> supportedDsdRates; // DSD64, DSD128, DSD256
    std::string description;
};

/**
 * Native DSD streaming state.
 */
enum class NativeDsdState : uint8_t {
    IDLE = 0,
    CONFIGURED,
    STREAMING,
    ERROR
};

/**
 * Native DSD Transport - streams raw DSD data to capable USB DACs.
 *
 * Only enables when the connected DAC genuinely supports native DSD
 * by inspecting USB Audio Class descriptors for:
 * - TYPE_I_RAW_DATA format tag (UAC1) or raw data format bit (UAC2)
 * - Appropriate alternate settings and endpoints
 *
 * This transport streams DSD without DoP encapsulation or PCM conversion.
 * It only activates when descriptor inspection confirms support.
 */
class NativeDsdTransport {
public:
    NativeDsdTransport() = default;
    ~NativeDsdTransport() = default;

    /**
     * Inspect USB descriptors to determine if DAC supports native DSD.
     * @param descriptors Parsed USB audio device descriptors
     * @return DSD capability info
     */
    DsdCapability inspectCapabilities(const usb::AudioDeviceDescriptors& descriptors);

    /**
     * Check if native DSD is available.
     */
    bool isAvailable() const { return capability_.supported; }

    /**
     * Get the detected DSD capability.
     */
    const DsdCapability& getCapability() const { return capability_; }

    /**
     * Check if a specific DSD rate is supported.
     * @param dsdRate DSD sample rate to check
     * @return true if the rate is supported in native mode
     */
    bool supportsRate(uint32_t dsdRate) const;

    /**
     * Configure for native DSD streaming.
     * @param dsdRate DSD sample rate
     * @param channels Number of channels
     * @return true if configuration succeeded
     */
    bool configure(uint32_t dsdRate, uint32_t channels);

    /**
     * Prepare a DSD data packet for USB transfer.
     * No DoP encapsulation - raw DSD bytes formatted for the endpoint.
     * @param dsdData Raw DSD bytes (interleaved if multi-channel)
     * @param dsdLength Length of DSD data
     * @param usbPacket Output USB packet buffer
     * @param maxPacketSize Maximum USB packet size
     * @return Number of bytes in the USB packet
     */
    size_t preparePacket(const uint8_t* dsdData, size_t dsdLength,
                         uint8_t* usbPacket, size_t maxPacketSize);

    /**
     * Get current transport state.
     */
    NativeDsdState getState() const { return state_; }

    /**
     * Get the interface number to use for DSD streaming.
     */
    uint8_t getInterfaceNumber() const { return capability_.interfaceNumber; }

    /**
     * Get the alternate setting for DSD streaming.
     */
    uint8_t getAltSetting() const { return capability_.altSetting; }

    /**
     * Get the endpoint address for DSD streaming.
     */
    uint8_t getEndpointAddress() const { return capability_.endpointAddress; }

    /**
     * Reset transport to idle state.
     */
    void reset();

private:
    DsdCapability capability_;
    NativeDsdState state_ = NativeDsdState::IDLE;
    uint32_t configuredRate_ = 0;
    uint32_t configuredChannels_ = 0;

    /**
     * Check UAC1 descriptors for raw DSD support.
     */
    bool checkUac1DsdSupport(const usb::AudioDeviceDescriptors& descriptors);

    /**
     * Check UAC2 descriptors for raw DSD support.
     */
    bool checkUac2DsdSupport(const usb::AudioDeviceDescriptors& descriptors);

    /**
     * Determine supported DSD rates from endpoint capabilities.
     */
    std::vector<uint32_t> determineSupportedRates(uint16_t maxPacketSize, uint8_t channels);
};

} // namespace native_dsd
} // namespace bitperfect
