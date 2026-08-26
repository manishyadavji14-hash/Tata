#pragma once

#include "usb_types.h"
#include <cstdint>
#include <vector>
#include <functional>

namespace bitperfect {
namespace usb {

/**
 * Result of a USB control transfer.
 */
struct ControlTransferResult {
    bool success = false;
    int transferred = 0;
    int error_code = 0;
    std::vector<uint8_t> data;
};

/**
 * Callback type for USB control transfers.
 * In the actual Android implementation, this wraps UsbDeviceConnection.controlTransfer().
 */
using ControlTransferFunc = std::function<ControlTransferResult(
    uint8_t requestType, uint8_t request, uint16_t value,
    uint16_t index, uint8_t* data, uint16_t length, uint32_t timeout)>;

/**
 * USB Audio Class control transfer helpers.
 * Provides high-level methods for UAC1/UAC2 control requests.
 */
class UsbControl {
public:
    explicit UsbControl(ControlTransferFunc transferFunc);
    ~UsbControl() = default;

    // Set the active interface alternate setting
    bool setInterface(uint8_t interfaceNum, uint8_t altSetting);

    // UAC1: Set sampling frequency
    bool setSamplingFrequencyUac1(uint8_t endpointAddress, uint32_t sampleRate);

    // UAC1: Get current sampling frequency
    uint32_t getSamplingFrequencyUac1(uint8_t endpointAddress);

    // UAC2: Set clock frequency (SET_CUR)
    bool setClockFrequencyUac2(uint8_t clockId, uint32_t sampleRate);

    // UAC2: Get current clock frequency (GET_CUR)
    uint32_t getClockFrequencyUac2(uint8_t clockId);

    // UAC2: Get supported frequency range (GET_RANGE)
    struct FrequencyRange {
        uint32_t min;
        uint32_t max;
        uint32_t res;
    };
    std::vector<FrequencyRange> getClockFrequencyRangeUac2(uint8_t clockId);

    // Feature Unit volume control
    bool setVolume(uint8_t unitId, uint8_t channel, int16_t volume);
    int16_t getVolume(uint8_t unitId, uint8_t channel);

    // Feature Unit mute control
    bool setMute(uint8_t unitId, uint8_t channel, bool mute);
    bool getMute(uint8_t unitId, uint8_t channel);

    // Clock selector
    bool selectClock(uint8_t selectorId, uint8_t inputPin);
    uint8_t getSelectedClock(uint8_t selectorId);

    // Set transfer timeout
    void setTimeout(uint32_t timeoutMs) { timeout_ms_ = timeoutMs; }

private:
    ControlTransferFunc transferFunc_;
    uint32_t timeout_ms_ = 1000;

    // USB request type directions
    static constexpr uint8_t USB_DIR_OUT = 0x00;
    static constexpr uint8_t USB_DIR_IN = 0x80;
    static constexpr uint8_t USB_TYPE_CLASS = 0x20;
    static constexpr uint8_t USB_TYPE_STANDARD = 0x00;
    static constexpr uint8_t USB_RECIP_INTERFACE = 0x01;
    static constexpr uint8_t USB_RECIP_ENDPOINT = 0x02;

    // UAC2 control selectors
    static constexpr uint8_t CS_SAM_FREQ_CONTROL = 0x01;
    static constexpr uint8_t FU_MUTE_CONTROL = 0x01;
    static constexpr uint8_t FU_VOLUME_CONTROL = 0x02;
    static constexpr uint8_t CX_CLOCK_SELECTOR_CONTROL = 0x01;

    // Standard USB requests
    static constexpr uint8_t USB_REQ_SET_INTERFACE = 0x0B;
};

} // namespace usb
} // namespace bitperfect
