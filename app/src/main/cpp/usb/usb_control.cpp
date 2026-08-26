#include "usb_control.h"
#include <cstring>

namespace bitperfect {
namespace usb {

UsbControl::UsbControl(ControlTransferFunc transferFunc)
    : transferFunc_(std::move(transferFunc)) {}

bool UsbControl::setInterface(uint8_t interfaceNum, uint8_t altSetting) {
    auto result = transferFunc_(
        USB_DIR_OUT | USB_TYPE_STANDARD | USB_RECIP_INTERFACE,
        USB_REQ_SET_INTERFACE,
        altSetting,      // wValue
        interfaceNum,    // wIndex
        nullptr, 0,
        timeout_ms_
    );
    return result.success;
}

bool UsbControl::setSamplingFrequencyUac1(uint8_t endpointAddress, uint32_t sampleRate) {
    uint8_t data[3];
    data[0] = sampleRate & 0xFF;
    data[1] = (sampleRate >> 8) & 0xFF;
    data[2] = (sampleRate >> 16) & 0xFF;

    auto result = transferFunc_(
        USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_ENDPOINT,
        static_cast<uint8_t>(AudioRequest::SET_CUR),
        (CS_SAM_FREQ_CONTROL << 8),  // wValue: CS in high byte
        endpointAddress,              // wIndex: endpoint
        data, 3,
        timeout_ms_
    );
    return result.success;
}

uint32_t UsbControl::getSamplingFrequencyUac1(uint8_t endpointAddress) {
    uint8_t data[3] = {0};

    auto result = transferFunc_(
        USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_ENDPOINT,
        static_cast<uint8_t>(AudioRequest::GET_CUR),
        (CS_SAM_FREQ_CONTROL << 8),
        endpointAddress,
        data, 3,
        timeout_ms_
    );

    if (!result.success) return 0;
    return data[0] | (data[1] << 8) | (data[2] << 16);
}

bool UsbControl::setClockFrequencyUac2(uint8_t clockId, uint32_t sampleRate) {
    uint8_t data[4];
    data[0] = sampleRate & 0xFF;
    data[1] = (sampleRate >> 8) & 0xFF;
    data[2] = (sampleRate >> 16) & 0xFF;
    data[3] = (sampleRate >> 24) & 0xFF;

    auto result = transferFunc_(
        USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE,
        static_cast<uint8_t>(AudioRequest::CUR),
        (CS_SAM_FREQ_CONTROL << 8),  // wValue: CS in high byte
        (clockId << 8),               // wIndex: entity ID in high byte
        data, 4,
        timeout_ms_
    );
    return result.success;
}

uint32_t UsbControl::getClockFrequencyUac2(uint8_t clockId) {
    uint8_t data[4] = {0};

    auto result = transferFunc_(
        USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE,
        static_cast<uint8_t>(AudioRequest::CUR),
        (CS_SAM_FREQ_CONTROL << 8),
        (clockId << 8),
        data, 4,
        timeout_ms_
    );

    if (!result.success) return 0;
    return data[0] | (data[1] << 8) | (data[2] << 16) | (data[3] << 24);
}

std::vector<UsbControl::FrequencyRange> UsbControl::getClockFrequencyRangeUac2(uint8_t clockId) {
    std::vector<FrequencyRange> ranges;

    // First, get the number of sub-ranges (2 bytes)
    uint8_t countData[2] = {0};
    auto result = transferFunc_(
        USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE,
        static_cast<uint8_t>(AudioRequest::RANGE),
        (CS_SAM_FREQ_CONTROL << 8),
        (clockId << 8),
        countData, 2,
        timeout_ms_
    );

    if (!result.success) return ranges;

    uint16_t numRanges = countData[0] | (countData[1] << 8);
    if (numRanges == 0 || numRanges > MAX_SAMPLE_RATES) return ranges;

    // Each sub-range is 12 bytes: min(4) + max(4) + res(4)
    size_t totalSize = 2 + numRanges * 12;
    std::vector<uint8_t> rangeData(totalSize, 0);

    result = transferFunc_(
        USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE,
        static_cast<uint8_t>(AudioRequest::RANGE),
        (CS_SAM_FREQ_CONTROL << 8),
        (clockId << 8),
        rangeData.data(), static_cast<uint16_t>(totalSize),
        timeout_ms_
    );

    if (!result.success) return ranges;

    // Parse sub-ranges
    for (uint16_t i = 0; i < numRanges; ++i) {
        size_t offset = 2 + i * 12;
        if (offset + 12 > rangeData.size()) break;

        FrequencyRange range;
        range.min = rangeData[offset] | (rangeData[offset + 1] << 8) |
                    (rangeData[offset + 2] << 16) | (rangeData[offset + 3] << 24);
        range.max = rangeData[offset + 4] | (rangeData[offset + 5] << 8) |
                    (rangeData[offset + 6] << 16) | (rangeData[offset + 7] << 24);
        range.res = rangeData[offset + 8] | (rangeData[offset + 9] << 8) |
                    (rangeData[offset + 10] << 16) | (rangeData[offset + 11] << 24);
        ranges.push_back(range);
    }

    return ranges;
}

bool UsbControl::setVolume(uint8_t unitId, uint8_t channel, int16_t volume) {
    uint8_t data[2];
    data[0] = volume & 0xFF;
    data[1] = (volume >> 8) & 0xFF;

    auto result = transferFunc_(
        USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE,
        static_cast<uint8_t>(AudioRequest::SET_CUR),
        (FU_VOLUME_CONTROL << 8) | channel,
        (unitId << 8),
        data, 2,
        timeout_ms_
    );
    return result.success;
}

int16_t UsbControl::getVolume(uint8_t unitId, uint8_t channel) {
    uint8_t data[2] = {0};

    auto result = transferFunc_(
        USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE,
        static_cast<uint8_t>(AudioRequest::GET_CUR),
        (FU_VOLUME_CONTROL << 8) | channel,
        (unitId << 8),
        data, 2,
        timeout_ms_
    );

    if (!result.success) return 0;
    return static_cast<int16_t>(data[0] | (data[1] << 8));
}

bool UsbControl::setMute(uint8_t unitId, uint8_t channel, bool mute) {
    uint8_t data[1] = { static_cast<uint8_t>(mute ? 1 : 0) };

    auto result = transferFunc_(
        USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE,
        static_cast<uint8_t>(AudioRequest::SET_CUR),
        (FU_MUTE_CONTROL << 8) | channel,
        (unitId << 8),
        data, 1,
        timeout_ms_
    );
    return result.success;
}

bool UsbControl::getMute(uint8_t unitId, uint8_t channel) {
    uint8_t data[1] = {0};

    auto result = transferFunc_(
        USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE,
        static_cast<uint8_t>(AudioRequest::GET_CUR),
        (FU_MUTE_CONTROL << 8) | channel,
        (unitId << 8),
        data, 1,
        timeout_ms_
    );

    return result.success && data[0] != 0;
}

bool UsbControl::selectClock(uint8_t selectorId, uint8_t inputPin) {
    uint8_t data[1] = { inputPin };

    auto result = transferFunc_(
        USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE,
        static_cast<uint8_t>(AudioRequest::CUR),
        (CX_CLOCK_SELECTOR_CONTROL << 8),
        (selectorId << 8),
        data, 1,
        timeout_ms_
    );
    return result.success;
}

uint8_t UsbControl::getSelectedClock(uint8_t selectorId) {
    uint8_t data[1] = {0};

    auto result = transferFunc_(
        USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE,
        static_cast<uint8_t>(AudioRequest::CUR),
        (CX_CLOCK_SELECTOR_CONTROL << 8),
        (selectorId << 8),
        data, 1,
        timeout_ms_
    );

    return result.success ? data[0] : 0;
}

} // namespace usb
} // namespace bitperfect
