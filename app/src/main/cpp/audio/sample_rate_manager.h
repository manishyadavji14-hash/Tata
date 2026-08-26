#pragma once

#include "../usb/usb_types.h"
#include "../usb/usb_descriptors.h"
#include "../usb/usb_control.h"
#include <vector>
#include <cstdint>
#include <optional>

namespace bitperfect {
namespace audio {

using usb::UacVersion;
using usb::PcmFormat;
using usb::AudioDeviceDescriptors;
using usb::UsbControl;

/**
 * Represents a supported rate with its source.
 */
struct SampleRateEntry {
    uint32_t rate = 0;
    bool isExact = true;     // True if explicitly listed, false if within continuous range
    uint8_t clockSourceId = 0;  // UAC2: which clock source provides this rate
};

/**
 * Result of rate negotiation.
 */
struct RateNegotiationResult {
    bool success = false;
    uint32_t selectedRate = 0;
    uint8_t altSetting = 0;
    uint8_t clockSourceId = 0;
    std::string errorMessage;
};

/**
 * SampleRateManager - handles sample rate discovery and negotiation.
 *
 * For UAC1: discovers rates from Format Type I descriptors and configures
 * via endpoint SET_CUR.
 *
 * For UAC2: discovers rates via clock source RANGE requests and configures
 * via clock entity SET_CUR.
 */
class SampleRateManager {
public:
    SampleRateManager() = default;
    ~SampleRateManager() = default;

    /**
     * Initialize from parsed device descriptors.
     */
    void initFromDescriptors(const AudioDeviceDescriptors& descriptors);

    /**
     * Set supported rates directly (for testing or when descriptors provide rates).
     */
    void setSupportedRates(const std::vector<uint32_t>& rates);

    /**
     * Add rates from a UAC2 clock range query.
     */
    void addClockRanges(uint8_t clockId, const std::vector<UsbControl::FrequencyRange>& ranges);

    /**
     * Get all supported sample rates.
     */
    std::vector<uint32_t> getSupportedRates() const;

    /**
     * Check if a specific rate is supported.
     */
    bool isRateSupported(uint32_t rate) const;

    /**
     * Find the best matching rate for a requested rate.
     * Prefers exact match, then closest standard rate, then closest available.
     */
    uint32_t findBestRate(uint32_t requestedRate) const;

    /**
     * Negotiate the rate: select best match and configure the device.
     * For UAC1: uses endpoint sampling frequency control.
     * For UAC2: configures clock source frequency.
     * @param requestedRate The desired sample rate
     * @param control USB control transfer interface (can be null for testing)
     * @param endpointAddress UAC1: endpoint address for rate control
     * @return Negotiation result
     */
    RateNegotiationResult negotiateRate(uint32_t requestedRate,
                                         UsbControl* control = nullptr,
                                         uint8_t endpointAddress = 0);

    /**
     * Verify the rate was accepted by reading it back.
     */
    bool verifyRate(uint32_t expectedRate, UsbControl* control,
                    uint8_t endpointAddress = 0);

    /**
     * Get the UAC version being used.
     */
    UacVersion getVersion() const { return version_; }

    /**
     * Set the UAC version.
     */
    void setVersion(UacVersion version) { version_ = version; }

    /**
     * Get the current clock source ID (UAC2).
     */
    uint8_t getCurrentClockSource() const { return currentClockSource_; }

    /**
     * Check if a rate falls within a continuous range.
     */
    static bool isInContinuousRange(uint32_t rate, uint32_t min, uint32_t max, uint32_t res);

    /**
     * Get standard audiophile sample rates.
     */
    static std::vector<uint32_t> getStandardRates();

private:
    UacVersion version_ = UacVersion::UAC1;
    std::vector<SampleRateEntry> supportedRates_;
    uint8_t currentClockSource_ = 0;

    // Standard rates in order of priority for matching
    static constexpr uint32_t kStandardRates[] = {
        44100, 48000, 88200, 96000, 176400, 192000,
        352800, 384000, 705600, 768000
    };
    static constexpr size_t kNumStandardRates = 10;
};

} // namespace audio
} // namespace bitperfect
