#include <gtest/gtest.h>
#include "../audio/sample_rate_manager.h"
#include <vector>

using namespace bitperfect::audio;
using namespace bitperfect::usb;

class SampleRateTest : public ::testing::Test {
protected:
    void SetUp() override {
        manager_.setVersion(UacVersion::UAC1);
    }

    SampleRateManager manager_;
};

TEST_F(SampleRateTest, SetSupportedRates) {
    std::vector<uint32_t> rates = {44100, 48000, 88200, 96000, 192000};
    manager_.setSupportedRates(rates);

    auto supported = manager_.getSupportedRates();
    EXPECT_EQ(supported.size(), 5u);
    EXPECT_EQ(supported[0], 44100u);
    EXPECT_EQ(supported[4], 192000u);
}

TEST_F(SampleRateTest, IsRateSupported) {
    manager_.setSupportedRates({44100, 48000, 96000, 192000});

    EXPECT_TRUE(manager_.isRateSupported(44100));
    EXPECT_TRUE(manager_.isRateSupported(48000));
    EXPECT_TRUE(manager_.isRateSupported(96000));
    EXPECT_TRUE(manager_.isRateSupported(192000));
    EXPECT_FALSE(manager_.isRateSupported(88200));
    EXPECT_FALSE(manager_.isRateSupported(384000));
}

TEST_F(SampleRateTest, FindBestRateExactMatch) {
    manager_.setSupportedRates({44100, 48000, 96000, 192000});

    EXPECT_EQ(manager_.findBestRate(44100), 44100u);
    EXPECT_EQ(manager_.findBestRate(96000), 96000u);
    EXPECT_EQ(manager_.findBestRate(192000), 192000u);
}

TEST_F(SampleRateTest, FindBestRateSameFamily) {
    // 44.1kHz family available
    manager_.setSupportedRates({44100, 88200, 176400});

    // Request 96000 (48k family) - should get closest available
    uint32_t best = manager_.findBestRate(96000);
    EXPECT_EQ(best, 88200u); // Closest rate

    // Request 44100 - exact match in family
    EXPECT_EQ(manager_.findBestRate(44100), 44100u);
}

TEST_F(SampleRateTest, FindBestRateClosest) {
    manager_.setSupportedRates({44100, 48000, 96000});

    // Request 88200 (not available) - should get closest in same family
    uint32_t best = manager_.findBestRate(88200);
    EXPECT_EQ(best, 44100u); // 44.1k family, closest

    // Request 192000 (not available) - closest available
    best = manager_.findBestRate(192000);
    EXPECT_EQ(best, 96000u);
}

TEST_F(SampleRateTest, FindBestRateEmptyRates) {
    // No rates set
    EXPECT_EQ(manager_.findBestRate(44100), 0u);
}

TEST_F(SampleRateTest, NegotiateRateNoControl) {
    manager_.setSupportedRates({44100, 48000, 96000, 192000});

    // Without control (test mode), should succeed with best rate
    auto result = manager_.negotiateRate(96000);
    EXPECT_TRUE(result.success);
    EXPECT_EQ(result.selectedRate, 96000u);
}

TEST_F(SampleRateTest, NegotiateRateNoRates) {
    // Empty rates
    auto result = manager_.negotiateRate(44100);
    EXPECT_FALSE(result.success);
    EXPECT_EQ(result.selectedRate, 0u);
}

TEST_F(SampleRateTest, NegotiateRateFallback) {
    manager_.setSupportedRates({44100, 48000});

    // Request 192000 which is not available
    auto result = manager_.negotiateRate(192000);
    EXPECT_TRUE(result.success);
    // Should get closest rate (48000 is closest to 192000 in 48k family)
    EXPECT_TRUE(result.selectedRate == 48000 || result.selectedRate == 44100);
}

TEST_F(SampleRateTest, ContinuousRangeCheck) {
    // Rate within range with resolution=0 (any rate)
    EXPECT_TRUE(SampleRateManager::isInContinuousRange(96000, 44100, 192000, 0));
    EXPECT_TRUE(SampleRateManager::isInContinuousRange(44100, 44100, 192000, 0));
    EXPECT_TRUE(SampleRateManager::isInContinuousRange(192000, 44100, 192000, 0));

    // Outside range
    EXPECT_FALSE(SampleRateManager::isInContinuousRange(384000, 44100, 192000, 0));
    EXPECT_FALSE(SampleRateManager::isInContinuousRange(22050, 44100, 192000, 0));

    // With resolution
    EXPECT_TRUE(SampleRateManager::isInContinuousRange(48000, 44100, 192000, 100));
    // 48000 - 44100 = 3900, 3900 % 100 = 0
    EXPECT_FALSE(SampleRateManager::isInContinuousRange(48001, 44100, 192000, 100));
}

TEST_F(SampleRateTest, AddClockRangesDiscrete) {
    manager_.setVersion(UacVersion::UAC2);

    // Simulate UAC2 clock range response with discrete rates
    std::vector<UsbControl::FrequencyRange> ranges = {
        {44100, 44100, 0},
        {48000, 48000, 0},
        {88200, 88200, 0},
        {96000, 96000, 0},
        {176400, 176400, 0},
        {192000, 192000, 0}
    };

    manager_.addClockRanges(9, ranges);

    auto supported = manager_.getSupportedRates();
    EXPECT_EQ(supported.size(), 6u);
    EXPECT_TRUE(manager_.isRateSupported(44100));
    EXPECT_TRUE(manager_.isRateSupported(192000));
}

TEST_F(SampleRateTest, AddClockRangesContinuous) {
    manager_.setVersion(UacVersion::UAC2);

    // Continuous range from 44100 to 768000
    std::vector<UsbControl::FrequencyRange> ranges = {
        {44100, 768000, 0}
    };

    manager_.addClockRanges(9, ranges);

    // Should enumerate standard rates within range
    auto supported = manager_.getSupportedRates();
    EXPECT_GE(supported.size(), 8u); // All standard rates from 44.1k to 768k

    EXPECT_TRUE(manager_.isRateSupported(44100));
    EXPECT_TRUE(manager_.isRateSupported(48000));
    EXPECT_TRUE(manager_.isRateSupported(192000));
    EXPECT_TRUE(manager_.isRateSupported(384000));
    EXPECT_TRUE(manager_.isRateSupported(768000));
}

TEST_F(SampleRateTest, StandardRates) {
    auto rates = SampleRateManager::getStandardRates();
    EXPECT_EQ(rates.size(), 10u);
    EXPECT_EQ(rates[0], 44100u);
    EXPECT_EQ(rates[1], 48000u);
    EXPECT_EQ(rates[9], 768000u);
}

TEST_F(SampleRateTest, InitFromUac1Descriptors) {
    // Create minimal descriptors with sample rates
    AudioDeviceDescriptors desc;
    desc.version = UacVersion::UAC1;

    AudioStreamingInterface si;
    si.interfaceNumber = 1;

    AlternateSettingInfo alt;
    alt.is_valid = true;
    alt.interface_desc.bAlternateSetting = 1;
    alt.format_uac1.bSamFreqType = 3;
    alt.format_uac1.sampleRates = {44100, 48000, 96000};
    si.altSettings.push_back(alt);

    desc.streamingInterfaces.push_back(si);

    manager_.initFromDescriptors(desc);

    EXPECT_TRUE(manager_.isRateSupported(44100));
    EXPECT_TRUE(manager_.isRateSupported(48000));
    EXPECT_TRUE(manager_.isRateSupported(96000));
    EXPECT_FALSE(manager_.isRateSupported(192000));
}

TEST_F(SampleRateTest, InitFromUac2Descriptors) {
    AudioDeviceDescriptors desc;
    desc.version = UacVersion::UAC2;

    ClockSourceDescriptor cs;
    cs.bClockID = 5;
    cs.bmAttributes = 0x01; // Internal fixed
    desc.clockSources.push_back(cs);

    manager_.initFromDescriptors(desc);

    // UAC2 rates are discovered via RANGE requests, so initially empty
    // but clock source should be set
    EXPECT_EQ(manager_.getCurrentClockSource(), 5);
    EXPECT_EQ(manager_.getVersion(), UacVersion::UAC2);
}
