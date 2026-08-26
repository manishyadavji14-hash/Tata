#include "sample_rate_manager.h"
#include <algorithm>
#include <cmath>

namespace bitperfect {
namespace audio {

void SampleRateManager::initFromDescriptors(const AudioDeviceDescriptors& descriptors) {
    version_ = descriptors.version;
    supportedRates_.clear();

    if (version_ == UacVersion::UAC1) {
        // Extract sample rates from all streaming interfaces' format descriptors
        for (const auto& si : descriptors.streamingInterfaces) {
            for (const auto& alt : si.altSettings) {
                if (alt.format_uac1.bSamFreqType == 0 && alt.format_uac1.sampleRates.size() >= 2) {
                    // Continuous range: enumerate standard rates within range
                    uint32_t lower = alt.format_uac1.sampleRates[0];
                    uint32_t upper = alt.format_uac1.sampleRates[1];
                    for (uint32_t rate : kStandardRates) {
                        if (rate >= lower && rate <= upper) {
                            SampleRateEntry entry;
                            entry.rate = rate;
                            entry.isExact = false;
                            supportedRates_.push_back(entry);
                        }
                    }
                } else {
                    // Discrete rates
                    for (uint32_t rate : alt.format_uac1.sampleRates) {
                        SampleRateEntry entry;
                        entry.rate = rate;
                        entry.isExact = true;
                        supportedRates_.push_back(entry);
                    }
                }
            }
        }
    } else {
        // UAC2: clock sources provide rates via RANGE requests
        // At init time, store clock source IDs; actual rates loaded via addClockRanges()
        for (const auto& cs : descriptors.clockSources) {
            currentClockSource_ = cs.bClockID;  // Use first available
        }
    }

    // Remove duplicates
    std::sort(supportedRates_.begin(), supportedRates_.end(),
              [](const SampleRateEntry& a, const SampleRateEntry& b) {
                  return a.rate < b.rate;
              });
    auto last = std::unique(supportedRates_.begin(), supportedRates_.end(),
                            [](const SampleRateEntry& a, const SampleRateEntry& b) {
                                return a.rate == b.rate;
                            });
    supportedRates_.erase(last, supportedRates_.end());
}

void SampleRateManager::setSupportedRates(const std::vector<uint32_t>& rates) {
    supportedRates_.clear();
    for (uint32_t rate : rates) {
        SampleRateEntry entry;
        entry.rate = rate;
        entry.isExact = true;
        supportedRates_.push_back(entry);
    }
}

void SampleRateManager::addClockRanges(uint8_t clockId,
                                         const std::vector<UsbControl::FrequencyRange>& ranges) {
    for (const auto& range : ranges) {
        if (range.min == range.max) {
            // Discrete rate
            SampleRateEntry entry;
            entry.rate = range.min;
            entry.isExact = true;
            entry.clockSourceId = clockId;
            supportedRates_.push_back(entry);
        } else {
            // Continuous range: enumerate standard rates within
            for (uint32_t rate : kStandardRates) {
                if (isInContinuousRange(rate, range.min, range.max, range.res)) {
                    SampleRateEntry entry;
                    entry.rate = rate;
                    entry.isExact = false;
                    entry.clockSourceId = clockId;
                    supportedRates_.push_back(entry);
                }
            }
        }
    }

    // Remove duplicates
    std::sort(supportedRates_.begin(), supportedRates_.end(),
              [](const SampleRateEntry& a, const SampleRateEntry& b) {
                  return a.rate < b.rate;
              });
    auto last = std::unique(supportedRates_.begin(), supportedRates_.end(),
                            [](const SampleRateEntry& a, const SampleRateEntry& b) {
                                return a.rate == b.rate;
                            });
    supportedRates_.erase(last, supportedRates_.end());
}

std::vector<uint32_t> SampleRateManager::getSupportedRates() const {
    std::vector<uint32_t> rates;
    rates.reserve(supportedRates_.size());
    for (const auto& entry : supportedRates_) {
        rates.push_back(entry.rate);
    }
    return rates;
}

bool SampleRateManager::isRateSupported(uint32_t rate) const {
    for (const auto& entry : supportedRates_) {
        if (entry.rate == rate) return true;
    }
    return false;
}

uint32_t SampleRateManager::findBestRate(uint32_t requestedRate) const {
    if (supportedRates_.empty()) return 0;

    // First try exact match
    for (const auto& entry : supportedRates_) {
        if (entry.rate == requestedRate) return requestedRate;
    }

    // Try multiples/divisions of the requested rate (same clock family)
    // 44.1kHz family: 44100, 88200, 176400, 352800, 705600
    // 48kHz family: 48000, 96000, 192000, 384000, 768000
    bool is44Family = (requestedRate % 44100 == 0) || (requestedRate == 44100);
    std::vector<uint32_t> familyRates;

    for (const auto& entry : supportedRates_) {
        if (is44Family) {
            if (entry.rate % 44100 == 0) familyRates.push_back(entry.rate);
        } else {
            if (entry.rate % 48000 == 0) familyRates.push_back(entry.rate);
        }
    }

    // Find closest rate in same family
    if (!familyRates.empty()) {
        uint32_t closest = familyRates[0];
        uint32_t minDiff = (requestedRate > closest) ? requestedRate - closest : closest - requestedRate;
        for (uint32_t rate : familyRates) {
            uint32_t diff = (requestedRate > rate) ? requestedRate - rate : rate - requestedRate;
            if (diff < minDiff) {
                minDiff = diff;
                closest = rate;
            }
        }
        return closest;
    }

    // Fall back to closest available rate
    uint32_t closest = supportedRates_[0].rate;
    uint32_t minDiff = (requestedRate > closest) ? requestedRate - closest : closest - requestedRate;
    for (const auto& entry : supportedRates_) {
        uint32_t diff = (requestedRate > entry.rate) ? requestedRate - entry.rate : entry.rate - requestedRate;
        if (diff < minDiff) {
            minDiff = diff;
            closest = entry.rate;
        }
    }
    return closest;
}

RateNegotiationResult SampleRateManager::negotiateRate(uint32_t requestedRate,
                                                        UsbControl* control,
                                                        uint8_t endpointAddress) {
    RateNegotiationResult result;

    // Find best rate
    uint32_t selectedRate = findBestRate(requestedRate);
    if (selectedRate == 0) {
        result.errorMessage = "No supported rates available";
        return result;
    }

    result.selectedRate = selectedRate;

    // If no control interface (testing mode), just return the selected rate
    if (!control) {
        result.success = true;
        return result;
    }

    // Configure the rate on the device
    if (version_ == UacVersion::UAC1) {
        // UAC1: Set sampling frequency on the endpoint
        if (control->setSamplingFrequencyUac1(endpointAddress, selectedRate)) {
            result.success = true;
        } else {
            result.errorMessage = "Failed to set sampling frequency on endpoint";
        }
    } else {
        // UAC2: Set clock frequency
        uint8_t clockId = currentClockSource_;
        // Find the clock source for this rate
        for (const auto& entry : supportedRates_) {
            if (entry.rate == selectedRate && entry.clockSourceId != 0) {
                clockId = entry.clockSourceId;
                break;
            }
        }

        if (control->setClockFrequencyUac2(clockId, selectedRate)) {
            result.success = true;
            result.clockSourceId = clockId;
        } else {
            result.errorMessage = "Failed to set clock frequency";
        }
    }

    return result;
}

bool SampleRateManager::verifyRate(uint32_t expectedRate, UsbControl* control,
                                    uint8_t endpointAddress) {
    if (!control) return false;

    uint32_t actualRate = 0;
    if (version_ == UacVersion::UAC1) {
        actualRate = control->getSamplingFrequencyUac1(endpointAddress);
    } else {
        actualRate = control->getClockFrequencyUac2(currentClockSource_);
    }

    return actualRate == expectedRate;
}

bool SampleRateManager::isInContinuousRange(uint32_t rate, uint32_t min, uint32_t max,
                                             uint32_t res) {
    if (rate < min || rate > max) return false;
    if (res == 0) return true;  // Resolution of 0 means any value in range

    // Check if rate is a valid step from min
    uint32_t diff = rate - min;
    return (diff % res) == 0;
}

std::vector<uint32_t> SampleRateManager::getStandardRates() {
    return {44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000};
}

} // namespace audio
} // namespace bitperfect
