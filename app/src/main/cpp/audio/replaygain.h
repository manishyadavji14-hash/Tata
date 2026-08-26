#pragma once

#include <cstdint>
#include <cstddef>
#include <cmath>

namespace bitperfect {
namespace audio {

/**
 * ReplayGain configuration.
 */
struct ReplayGainConfig {
    float trackGainDb = 0.0f;       // Track gain in dB
    float albumGainDb = 0.0f;       // Album gain in dB
    float preampDb = 0.0f;          // Additional preamp in dB
    bool useAlbumGain = false;      // True to use album gain instead of track gain
    bool enableLimiter = true;      // Prevent clipping
    bool enabled = true;            // Master enable
    bool bitPerfectMode = true;     // When true, bypasses all processing (default: true for audiophile use)
    bool forceInBitPerfect = false; // User override to force in bit-perfect mode
};

/**
 * ReplayGain statistics.
 */
struct ReplayGainStats {
    float appliedGainDb = 0.0f;     // Total applied gain
    uint64_t samplesProcessed = 0;
    uint64_t samplesClipped = 0;    // Number of samples that hit the limiter
    float peakLevel = 0.0f;         // Peak level (0.0 to 1.0+)
};

/**
 * ReplayGain Processor.
 *
 * Applies ReplayGain normalization to PCM audio data.
 *
 * Design principles:
 * - All gain computation happens in floating point for precision
 * - Converts integer samples to float, applies gain, converts back
 * - Limiter prevents clipping by soft-capping at maximum value
 * - When BitPerfect mode is enabled, completely bypasses processing
 *   (unless user explicitly forces it with forceInBitPerfect)
 * - Supports 16-bit, 24-bit, and 32-bit PCM samples
 * - Thread-safe configuration updates
 */
class ReplayGainProcessor {
public:
    ReplayGainProcessor() = default;
    ~ReplayGainProcessor() = default;

    /**
     * Set the ReplayGain configuration.
     */
    void configure(const ReplayGainConfig& config);

    /**
     * Get the current configuration.
     */
    const ReplayGainConfig& getConfig() const { return config_; }

    /**
     * Process audio buffer in place.
     * @param buffer PCM audio data
     * @param frames Number of frames
     * @param bitsPerSample 16, 24, or 32
     * @param channels Number of channels
     */
    void process(uint8_t* buffer, size_t frames, uint8_t bitsPerSample, uint8_t channels);

    /**
     * Process 16-bit samples.
     */
    void process16(int16_t* samples, size_t count);

    /**
     * Process 24-bit packed samples (3 bytes per sample).
     */
    void process24(uint8_t* samples, size_t count);

    /**
     * Process 32-bit samples.
     */
    void process32(int32_t* samples, size_t count);

    /**
     * Check if processing is active (not bypassed).
     */
    bool isActive() const;

    /**
     * Get the effective gain in dB.
     */
    float getEffectiveGainDb() const;

    /**
     * Get the linear gain multiplier.
     */
    float getLinearGain() const;

    /**
     * Get processing statistics.
     */
    const ReplayGainStats& getStats() const { return stats_; }

    /**
     * Reset statistics.
     */
    void resetStats();

    /**
     * Convert dB to linear gain factor.
     */
    static float dbToLinear(float db) {
        return std::pow(10.0f, db / 20.0f);
    }

    /**
     * Convert linear gain factor to dB.
     */
    static float linearToDb(float linear) {
        if (linear <= 0.0f) return -100.0f;
        return 20.0f * std::log10(linear);
    }

private:
    /**
     * Apply soft limiter to prevent clipping.
     * Uses simple hard clip at +/- 1.0.
     */
    float applyLimiter(float sample);

    ReplayGainConfig config_;
    ReplayGainStats stats_;
    float linearGain_ = 1.0f;   // Cached linear gain
};

} // namespace audio
} // namespace bitperfect
