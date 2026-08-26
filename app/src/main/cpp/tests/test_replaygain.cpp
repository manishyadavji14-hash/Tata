#include <gtest/gtest.h>
#include "audio/replaygain.h"
#include <vector>
#include <cmath>
#include <cstring>
#include <climits>

using namespace bitperfect::audio;

// --- Basic gain application ---

TEST(ReplayGain, ApplyPositiveGain16Bit) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 6.0f;  // +6 dB (approximately double amplitude)
    config.enabled = true;
    config.enableLimiter = false;
    processor.configure(config);

    EXPECT_TRUE(processor.isActive());

    // Create a 16-bit sample at half scale
    int16_t samples[2] = {8192, -8192};

    processor.process16(samples, 2);

    // +6 dB is approximately 2x, so 8192 * 2 ~ 16384
    EXPECT_NEAR(samples[0], 16384, 200);
    EXPECT_NEAR(samples[1], -16384, 200);
}

TEST(ReplayGain, ApplyNegativeGain16Bit) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = -6.0f;  // -6 dB (approximately half amplitude)
    config.enabled = true;
    config.enableLimiter = false;
    processor.configure(config);

    int16_t samples[2] = {16384, -16384};

    processor.process16(samples, 2);

    // -6 dB is approximately 0.5x, so 16384 * 0.5 ~ 8192
    EXPECT_NEAR(samples[0], 8192, 200);
    EXPECT_NEAR(samples[1], -8192, 200);
}

TEST(ReplayGain, ZeroGainNoChange) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 0.0f;
    config.enabled = true;
    config.enableLimiter = false;
    processor.configure(config);

    int16_t samples[4] = {1000, -1000, 32767, -32768};
    int16_t original[4];
    memcpy(original, samples, sizeof(samples));

    processor.process16(samples, 4);

    // With 0 dB gain, values should be unchanged (within rounding)
    for (int i = 0; i < 4; ++i) {
        EXPECT_NEAR(samples[i], original[i], 1);
    }
}

// --- Clipping prevention ---

TEST(ReplayGain, ClippingPrevention16Bit) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 12.0f;  // +12 dB (4x amplitude)
    config.enabled = true;
    config.enableLimiter = true;
    processor.configure(config);

    // Near-full-scale sample - will clip with +12dB
    int16_t samples[2] = {16384, -16384};

    processor.process16(samples, 2);

    // Should be clamped to max values
    EXPECT_LE(samples[0], 32767);
    EXPECT_GE(samples[1], -32768);
    EXPECT_GT(processor.getStats().samplesClipped, 0u);
}

TEST(ReplayGain, ClippingPrevention24Bit) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 12.0f;
    config.enabled = true;
    config.enableLimiter = true;
    processor.configure(config);

    // Create 24-bit packed sample near full scale (4194304 = 0x400000)
    uint8_t samples[6];
    // Sample 1: +4194304 (0x00, 0x00, 0x40)
    samples[0] = 0x00;
    samples[1] = 0x00;
    samples[2] = 0x40;
    // Sample 2: -4194304 (0x00, 0x00, 0xC0)
    samples[3] = 0x00;
    samples[4] = 0x00;
    samples[5] = 0xC0;

    processor.process24(samples, 2);

    // Read back - values should be clamped
    int32_t val1 = samples[0] | (samples[1] << 8) | (samples[2] << 16);
    if (val1 & 0x800000) val1 |= static_cast<int32_t>(0xFF000000);

    int32_t val2 = samples[3] | (samples[4] << 8) | (samples[5] << 16);
    if (val2 & 0x800000) val2 |= static_cast<int32_t>(0xFF000000);

    EXPECT_LE(val1, 8388607);
    EXPECT_GE(val2, -8388608);
}

TEST(ReplayGain, ClippingPrevention32Bit) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 12.0f;
    config.enabled = true;
    config.enableLimiter = true;
    processor.configure(config);

    // Near full-scale 32-bit samples
    int32_t samples[2] = {1073741824, -1073741824};  // ~50% of full scale

    processor.process32(samples, 2);

    // With +12dB (4x), these should clip
    EXPECT_LE(samples[0], INT32_MAX);
    EXPECT_GE(samples[1], INT32_MIN);
}

// --- BitPerfect mode bypass ---

TEST(ReplayGain, BypassInBitPerfectMode) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 12.0f;
    config.enabled = true;
    config.bitPerfectMode = true;
    config.forceInBitPerfect = false;
    processor.configure(config);

    EXPECT_FALSE(processor.isActive());

    int16_t samples[2] = {1000, -1000};
    int16_t original[2] = {1000, -1000};

    processor.process16(samples, 2);

    // Should not be modified in bit-perfect mode
    EXPECT_EQ(samples[0], original[0]);
    EXPECT_EQ(samples[1], original[1]);
}

TEST(ReplayGain, ForceInBitPerfectMode) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 6.0f;
    config.enabled = true;
    config.bitPerfectMode = true;
    config.forceInBitPerfect = true;  // User explicitly enables
    processor.configure(config);

    EXPECT_TRUE(processor.isActive());

    int16_t samples[1] = {8192};
    processor.process16(samples, 1);

    // Should be processed (approximately doubled)
    EXPECT_NEAR(samples[0], 16384, 200);
}

TEST(ReplayGain, BypassWhenDisabled) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 12.0f;
    config.enabled = false;
    processor.configure(config);

    EXPECT_FALSE(processor.isActive());

    int16_t samples[2] = {5000, -5000};
    processor.process(reinterpret_cast<uint8_t*>(samples), 1, 16, 2);

    // Should not be modified
    EXPECT_EQ(samples[0], 5000);
    EXPECT_EQ(samples[1], -5000);
}

// --- Album gain ---

TEST(ReplayGain, UseAlbumGain) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 3.0f;
    config.albumGainDb = -3.0f;
    config.useAlbumGain = true;
    config.enabled = true;
    config.enableLimiter = false;
    processor.configure(config);

    float effectiveGain = processor.getEffectiveGainDb();
    EXPECT_FLOAT_EQ(effectiveGain, -3.0f);
}

TEST(ReplayGain, UseTrackGainByDefault) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 3.0f;
    config.albumGainDb = -3.0f;
    config.useAlbumGain = false;
    config.enabled = true;
    processor.configure(config);

    float effectiveGain = processor.getEffectiveGainDb();
    EXPECT_FLOAT_EQ(effectiveGain, 3.0f);
}

// --- Preamp ---

TEST(ReplayGain, PreampAddsToGain) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = -3.0f;
    config.preampDb = 5.0f;
    config.enabled = true;
    processor.configure(config);

    float effectiveGain = processor.getEffectiveGainDb();
    EXPECT_FLOAT_EQ(effectiveGain, 2.0f);  // -3 + 5 = 2
}

// --- dB / linear conversion ---

TEST(ReplayGain, DbToLinearConversion) {
    EXPECT_NEAR(ReplayGainProcessor::dbToLinear(0.0f), 1.0f, 0.001f);
    EXPECT_NEAR(ReplayGainProcessor::dbToLinear(6.0f), 1.995f, 0.01f);
    EXPECT_NEAR(ReplayGainProcessor::dbToLinear(-6.0f), 0.501f, 0.01f);
    EXPECT_NEAR(ReplayGainProcessor::dbToLinear(20.0f), 10.0f, 0.01f);
    EXPECT_NEAR(ReplayGainProcessor::dbToLinear(-20.0f), 0.1f, 0.01f);
}

TEST(ReplayGain, LinearToDbConversion) {
    EXPECT_NEAR(ReplayGainProcessor::linearToDb(1.0f), 0.0f, 0.01f);
    EXPECT_NEAR(ReplayGainProcessor::linearToDb(2.0f), 6.02f, 0.1f);
    EXPECT_NEAR(ReplayGainProcessor::linearToDb(0.5f), -6.02f, 0.1f);
    EXPECT_NEAR(ReplayGainProcessor::linearToDb(10.0f), 20.0f, 0.01f);
}

// --- Statistics ---

TEST(ReplayGain, TracksPeakLevel) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 0.0f;
    config.enabled = true;
    config.enableLimiter = false;
    processor.configure(config);
    processor.resetStats();

    // Full-scale 16-bit sample
    int16_t samples[1] = {32767};
    processor.process16(samples, 1);

    EXPECT_GT(processor.getStats().peakLevel, 0.99f);
}

TEST(ReplayGain, CountsSamplesProcessed) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 3.0f;
    config.enabled = true;
    processor.configure(config);
    processor.resetStats();

    int16_t samples[100];
    memset(samples, 0, sizeof(samples));
    processor.process16(samples, 100);

    EXPECT_EQ(processor.getStats().samplesProcessed, 100u);
}

// --- Process via generic interface ---

TEST(ReplayGain, ProcessGenericInterface) {
    ReplayGainProcessor processor;
    ReplayGainConfig config;
    config.trackGainDb = 6.0f;
    config.enabled = true;
    config.enableLimiter = false;
    processor.configure(config);

    int16_t samples[4] = {4096, -4096, 4096, -4096};  // 2 frames, stereo
    processor.process(reinterpret_cast<uint8_t*>(samples), 2, 16, 2);

    // All 4 samples should be approximately doubled
    EXPECT_NEAR(samples[0], 8192, 200);
    EXPECT_NEAR(samples[1], -8192, 200);
    EXPECT_NEAR(samples[2], 8192, 200);
    EXPECT_NEAR(samples[3], -8192, 200);
}
