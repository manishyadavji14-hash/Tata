#include <gtest/gtest.h>
#include "../audio/playback_mode.h"
#include "../audio/format_detector.h"
#include "../dop/dop_encoder.h"
#include <vector>

using namespace bitperfect::audio;
using namespace bitperfect::dop;
using namespace bitperfect::native_dsd;

class PlaybackModeTest : public ::testing::Test {
protected:
    PlaybackModeSelector selector;

    void SetUp() override {}
    void TearDown() override {}

    // Helper: create DAC caps with common PCM support
    DacCapabilities makePcmOnlyDac(std::vector<uint32_t> rates,
                                    std::vector<uint8_t> bitDepths) {
        DacCapabilities caps;
        caps.pcmSampleRates = rates;
        caps.pcmBitDepths = bitDepths;
        caps.supportsNativeDsd = false;
        caps.supportsDop = false;
        for (uint32_t rate : rates) {
            if (rate > caps.maxSampleRate) caps.maxSampleRate = rate;
        }
        // Check DoP support
        bool has24 = false;
        for (uint8_t bd : bitDepths) {
            if (bd == 24) has24 = true;
        }
        if (has24) {
            for (uint32_t rate : rates) {
                if (rate == 176400 || rate == 352800 || rate == 705600) {
                    caps.supportsDop = true;
                    break;
                }
            }
        }
        return caps;
    }

    DacCapabilities makeNativeDsdDac(std::vector<uint32_t> dsdRates,
                                      std::vector<uint32_t> pcmRates,
                                      std::vector<uint8_t> bitDepths) {
        DacCapabilities caps = makePcmOnlyDac(pcmRates, bitDepths);
        caps.supportsNativeDsd = true;
        caps.nativeDsdRates = dsdRates;
        return caps;
    }

    FormatInfo makePcmSource(uint32_t rate, uint8_t bitDepth, uint32_t channels = 2) {
        FormatInfo info;
        info.fileType = AudioFileType::FLAC;
        info.contentType = AudioContentType::PCM;
        info.sampleRate = rate;
        info.bitDepth = bitDepth;
        info.channels = channels;
        info.isValid = true;
        return info;
    }

    FormatInfo makeDsdSource(uint32_t rate, uint32_t channels = 2) {
        FormatInfo info;
        info.fileType = AudioFileType::DSF;
        info.contentType = AudioContentType::DSD;
        info.sampleRate = rate;
        info.bitDepth = 1;
        info.channels = channels;
        info.isValid = true;
        return info;
    }
};

// === PCM Source Tests ===

TEST_F(PlaybackModeTest, Flac24_192SelectsPcm) {
    auto source = makePcmSource(192000, 24);
    auto dac = makePcmOnlyDac({44100, 48000, 96000, 192000}, {16, 24, 32});

    auto result = selector.selectMode(source, dac);
    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, PlaybackMode::PCM);
    EXPECT_EQ(result.transportRate, 192000u);
    EXPECT_EQ(result.bitDepth, 24u);
}

TEST_F(PlaybackModeTest, Wav16_44100SelectsPcm) {
    auto source = makePcmSource(44100, 16);
    auto dac = makePcmOnlyDac({44100, 48000, 96000}, {16, 24});

    auto result = selector.selectMode(source, dac);
    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, PlaybackMode::PCM);
    EXPECT_EQ(result.transportRate, 44100u);
}

TEST_F(PlaybackModeTest, PcmSourceNeverSelectsDop) {
    // Even with DoP-capable DAC, PCM source should use PCM mode
    auto source = makePcmSource(176400, 24);
    auto dac = makePcmOnlyDac({44100, 48000, 96000, 176400, 352800}, {16, 24});

    auto result = selector.selectMode(source, dac);
    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, PlaybackMode::PCM);
}

// === DSD Source with Native DSD ===

TEST_F(PlaybackModeTest, Dsd64NativeCapableSelectsNativeDsd) {
    auto source = makeDsdSource(2822400); // DSD64
    auto dac = makeNativeDsdDac({2822400, 5644800}, {44100, 48000, 176400}, {16, 24});

    auto result = selector.selectMode(source, dac);
    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, PlaybackMode::NATIVE_DSD);
    EXPECT_EQ(result.transportRate, 2822400u);
}

TEST_F(PlaybackModeTest, Dsd128NativeCapableSelectsNativeDsd) {
    auto source = makeDsdSource(5644800); // DSD128
    auto dac = makeNativeDsdDac({2822400, 5644800}, {44100, 48000, 176400, 352800}, {16, 24});

    auto result = selector.selectMode(source, dac);
    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, PlaybackMode::NATIVE_DSD);
    EXPECT_EQ(result.transportRate, 5644800u);
}

// === DSD Source with DoP Only ===

TEST_F(PlaybackModeTest, Dsd64DopOnlySelectsDop176k) {
    auto source = makeDsdSource(2822400); // DSD64
    // DAC supports 24-bit at 176400 but no native DSD
    auto dac = makePcmOnlyDac({44100, 48000, 96000, 176400}, {16, 24});

    auto result = selector.selectMode(source, dac);
    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, PlaybackMode::DOP);
    EXPECT_EQ(result.transportRate, 176400u);
    EXPECT_EQ(result.bitDepth, 24u);
}

TEST_F(PlaybackModeTest, Dsd128DopSelectsDop352k) {
    auto source = makeDsdSource(5644800); // DSD128
    auto dac = makePcmOnlyDac({44100, 48000, 96000, 176400, 352800}, {16, 24});

    auto result = selector.selectMode(source, dac);
    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, PlaybackMode::DOP);
    EXPECT_EQ(result.transportRate, 352800u);
}

TEST_F(PlaybackModeTest, Dsd256DopSelectsDop705k) {
    auto source = makeDsdSource(11289600); // DSD256
    auto dac = makePcmOnlyDac({44100, 48000, 96000, 176400, 352800, 705600}, {16, 24});

    auto result = selector.selectMode(source, dac);
    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, PlaybackMode::DOP);
    EXPECT_EQ(result.transportRate, 705600u);
}

// === Priority: Native DSD > DoP ===

TEST_F(PlaybackModeTest, NativeDsdPrioritizedOverDop) {
    auto source = makeDsdSource(2822400);
    // DAC supports both native DSD and DoP
    auto dac = makeNativeDsdDac({2822400}, {44100, 48000, 96000, 176400}, {16, 24});

    auto result = selector.selectMode(source, dac);
    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, PlaybackMode::NATIVE_DSD);
}

// === Fallback Cases ===

TEST_F(PlaybackModeTest, DsdWithNoSupportFails) {
    auto source = makeDsdSource(2822400);
    // DAC only supports PCM up to 96kHz, 16-bit (no DoP, no native)
    auto dac = makePcmOnlyDac({44100, 48000, 96000}, {16});

    auto result = selector.selectMode(source, dac);
    EXPECT_FALSE(result.valid);
    EXPECT_EQ(result.mode, PlaybackMode::PCM); // Fallback mode indicated
}

TEST_F(PlaybackModeTest, DsdWithoutTransportRateFails) {
    auto source = makeDsdSource(2822400); // Needs 176400 for DoP
    // DAC has 24-bit but not at 176400
    auto dac = makePcmOnlyDac({44100, 48000, 96000}, {16, 24});

    auto result = selector.selectMode(source, dac);
    EXPECT_FALSE(result.valid);
}

TEST_F(PlaybackModeTest, InvalidSourceReturnsInvalid) {
    FormatInfo source; // Default: invalid
    auto dac = makePcmOnlyDac({44100, 48000}, {16, 24});

    auto result = selector.selectMode(source, dac);
    EXPECT_FALSE(result.valid);
}

// === Mode Name ===

TEST_F(PlaybackModeTest, ModeNames) {
    EXPECT_STREQ(PlaybackModeSelector::modeName(PlaybackMode::PCM), "PCM");
    EXPECT_STREQ(PlaybackModeSelector::modeName(PlaybackMode::DOP), "DoP");
    EXPECT_STREQ(PlaybackModeSelector::modeName(PlaybackMode::NATIVE_DSD), "Native DSD");
}

// === Format Detector Integration ===

TEST_F(PlaybackModeTest, FormatDetectorDsf) {
    FormatDetector detector;
    EXPECT_EQ(FormatDetector::detectFromExtension("test.dsf"), AudioFileType::DSF);
    EXPECT_EQ(FormatDetector::detectFromExtension("test.flac"), AudioFileType::FLAC);
    EXPECT_EQ(FormatDetector::detectFromExtension("test.wav"), AudioFileType::WAV);
    EXPECT_EQ(FormatDetector::detectFromExtension("test.txt"), AudioFileType::UNKNOWN);
}

TEST_F(PlaybackModeTest, IsDsdFormat) {
    EXPECT_TRUE(FormatDetector::isDsdFormat(AudioFileType::DSF));
    EXPECT_TRUE(FormatDetector::isDsdFormat(AudioFileType::DFF));
    EXPECT_FALSE(FormatDetector::isDsdFormat(AudioFileType::WAV));
    EXPECT_FALSE(FormatDetector::isDsdFormat(AudioFileType::FLAC));
}

TEST_F(PlaybackModeTest, IsPcmFormat) {
    EXPECT_TRUE(FormatDetector::isPcmFormat(AudioFileType::WAV));
    EXPECT_TRUE(FormatDetector::isPcmFormat(AudioFileType::FLAC));
    EXPECT_TRUE(FormatDetector::isPcmFormat(AudioFileType::AIFF));
    EXPECT_FALSE(FormatDetector::isPcmFormat(AudioFileType::DSF));
}
