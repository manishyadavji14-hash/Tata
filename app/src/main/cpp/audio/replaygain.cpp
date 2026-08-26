#include "replaygain.h"
#include <cstring>
#include <algorithm>
#include <climits>

namespace bitperfect {
namespace audio {

void ReplayGainProcessor::configure(const ReplayGainConfig& config) {
    config_ = config;
    linearGain_ = dbToLinear(getEffectiveGainDb());
}

void ReplayGainProcessor::process(uint8_t* buffer, size_t frames,
                                   uint8_t bitsPerSample, uint8_t channels) {
    if (!isActive() || frames == 0 || !buffer) return;

    size_t totalSamples = frames * channels;

    switch (bitsPerSample) {
        case 16:
            process16(reinterpret_cast<int16_t*>(buffer), totalSamples);
            break;
        case 24:
            process24(buffer, totalSamples);
            break;
        case 32:
            process32(reinterpret_cast<int32_t*>(buffer), totalSamples);
            break;
        default:
            break;
    }
}

void ReplayGainProcessor::process16(int16_t* samples, size_t count) {
    float gain = linearGain_;

    for (size_t i = 0; i < count; ++i) {
        float sample = static_cast<float>(samples[i]) / 32768.0f;
        sample *= gain;

        // Track peak
        float absSample = std::abs(sample);
        if (absSample > stats_.peakLevel) {
            stats_.peakLevel = absSample;
        }

        // Apply limiter
        if (config_.enableLimiter) {
            sample = applyLimiter(sample);
        }

        // Convert back to 16-bit
        float scaled = sample * 32768.0f;
        int32_t rounded = static_cast<int32_t>(scaled);
        if (rounded > 32767) {
            rounded = 32767;
            stats_.samplesClipped++;
        } else if (rounded < -32768) {
            rounded = -32768;
            stats_.samplesClipped++;
        }
        samples[i] = static_cast<int16_t>(rounded);
    }

    stats_.samplesProcessed += count;
}

void ReplayGainProcessor::process24(uint8_t* samples, size_t count) {
    float gain = linearGain_;
    static constexpr float kScale = 8388608.0f;   // 2^23
    static constexpr int32_t kMax = 8388607;       // 2^23 - 1
    static constexpr int32_t kMin = -8388608;      // -(2^23)

    for (size_t i = 0; i < count; ++i) {
        // Read 24-bit packed little-endian sample
        uint8_t* p = samples + i * 3;
        int32_t raw = p[0] | (p[1] << 8) | (p[2] << 16);
        // Sign extend
        if (raw & 0x800000) {
            raw |= static_cast<int32_t>(0xFF000000);
        }

        float sample = static_cast<float>(raw) / kScale;
        sample *= gain;

        // Track peak
        float absSample = std::abs(sample);
        if (absSample > stats_.peakLevel) {
            stats_.peakLevel = absSample;
        }

        // Apply limiter
        if (config_.enableLimiter) {
            sample = applyLimiter(sample);
        }

        // Convert back to 24-bit
        int32_t rounded = static_cast<int32_t>(sample * kScale);
        if (rounded > kMax) {
            rounded = kMax;
            stats_.samplesClipped++;
        } else if (rounded < kMin) {
            rounded = kMin;
            stats_.samplesClipped++;
        }

        p[0] = static_cast<uint8_t>(rounded & 0xFF);
        p[1] = static_cast<uint8_t>((rounded >> 8) & 0xFF);
        p[2] = static_cast<uint8_t>((rounded >> 16) & 0xFF);
    }

    stats_.samplesProcessed += count;
}

void ReplayGainProcessor::process32(int32_t* samples, size_t count) {
    float gain = linearGain_;
    static constexpr double kScale = 2147483648.0;  // 2^31

    for (size_t i = 0; i < count; ++i) {
        float sample = static_cast<float>(static_cast<double>(samples[i]) / kScale);
        sample *= gain;

        // Track peak
        float absSample = std::abs(sample);
        if (absSample > stats_.peakLevel) {
            stats_.peakLevel = absSample;
        }

        // Apply limiter
        if (config_.enableLimiter) {
            sample = applyLimiter(sample);
        }

        // Convert back to 32-bit
        double scaled = static_cast<double>(sample) * kScale;
        int64_t rounded = static_cast<int64_t>(scaled);
        if (rounded > INT32_MAX) {
            rounded = INT32_MAX;
            stats_.samplesClipped++;
        } else if (rounded < INT32_MIN) {
            rounded = INT32_MIN;
            stats_.samplesClipped++;
        }
        samples[i] = static_cast<int32_t>(rounded);
    }

    stats_.samplesProcessed += count;
}

bool ReplayGainProcessor::isActive() const {
    // Bypass completely in bit-perfect mode unless user explicitly enables
    if (config_.bitPerfectMode && !config_.forceInBitPerfect) {
        return false;
    }
    return config_.enabled;
}

float ReplayGainProcessor::getEffectiveGainDb() const {
    if (!isActive()) return 0.0f;

    float gain = config_.useAlbumGain ? config_.albumGainDb : config_.trackGainDb;
    gain += config_.preampDb;
    return gain;
}

float ReplayGainProcessor::getLinearGain() const {
    return linearGain_;
}

void ReplayGainProcessor::resetStats() {
    stats_ = ReplayGainStats{};
    stats_.appliedGainDb = getEffectiveGainDb();
}

float ReplayGainProcessor::applyLimiter(float sample) {
    // Hard limiter: clamp to [-1.0, 1.0]
    if (sample > 1.0f) return 1.0f;
    if (sample < -1.0f) return -1.0f;
    return sample;
}

} // namespace audio
} // namespace bitperfect
