#include <gtest/gtest.h>
#include "../native_dsd/native_dsd_transport.h"
#include "../usb/usb_descriptors.h"
#include <vector>
#include <cstring>

using namespace bitperfect::native_dsd;
using namespace bitperfect::usb;

class NativeDsdTest : public ::testing::Test {
protected:
    NativeDsdTransport transport;

    void SetUp() override {}
    void TearDown() override {}

    // Helper: create a UAC1 device with RAW_DATA format tag (DSD-capable)
    AudioDeviceDescriptors makeUac1DsdCapable() {
        AudioDeviceDescriptors desc;
        desc.version = UacVersion::UAC1;

        AudioStreamingInterface iface;
        iface.interfaceNumber = 1;

        AlternateSettingInfo alt;
        alt.is_valid = true;
        alt.interface_desc.bInterfaceNumber = 1;
        alt.interface_desc.bAlternateSetting = 1;
        alt.as_general_uac1.wFormatTag = FormatTag::TYPE_I_RAW_DATA;
        alt.endpoint.bEndpointAddress = 0x01;
        alt.endpoint.wMaxPacketSize = 512;
        alt.format_uac1.bNrChannels = 2;

        iface.altSettings.push_back(alt);
        desc.streamingInterfaces.push_back(iface);

        return desc;
    }

    // Helper: create a UAC2 device with RAW_DATA format bit (DSD-capable)
    AudioDeviceDescriptors makeUac2DsdCapable() {
        AudioDeviceDescriptors desc;
        desc.version = UacVersion::UAC2;

        AudioStreamingInterface iface;
        iface.interfaceNumber = 1;

        AlternateSettingInfo alt;
        alt.is_valid = true;
        alt.interface_desc.bInterfaceNumber = 1;
        alt.interface_desc.bAlternateSetting = 2;
        alt.as_general_uac2.bmFormats = static_cast<uint32_t>(Uac2FormatBit::TYPE_I_RAW_DATA);
        alt.as_general_uac2.bNrChannels = 2;
        alt.endpoint.bEndpointAddress = 0x02;
        alt.endpoint.wMaxPacketSize = 1024;

        iface.altSettings.push_back(alt);
        desc.streamingInterfaces.push_back(iface);

        return desc;
    }

    // Helper: create a standard PCM-only DAC (no DSD support)
    AudioDeviceDescriptors makePcmOnlyDac() {
        AudioDeviceDescriptors desc;
        desc.version = UacVersion::UAC2;

        AudioStreamingInterface iface;
        iface.interfaceNumber = 1;

        AlternateSettingInfo alt;
        alt.is_valid = true;
        alt.interface_desc.bInterfaceNumber = 1;
        alt.interface_desc.bAlternateSetting = 1;
        alt.as_general_uac2.bmFormats = static_cast<uint32_t>(Uac2FormatBit::PCM);
        alt.as_general_uac2.bNrChannels = 2;
        alt.format_uac2.bBitResolution = 24;
        alt.format_uac2.bSubslotSize = 4;
        alt.endpoint.bEndpointAddress = 0x01;
        alt.endpoint.wMaxPacketSize = 576;

        iface.altSettings.push_back(alt);
        desc.streamingInterfaces.push_back(iface);

        return desc;
    }
};

// === Descriptor Inspection ===

TEST_F(NativeDsdTest, DetectsUac1DsdCapability) {
    auto desc = makeUac1DsdCapable();
    auto caps = transport.inspectCapabilities(desc);

    EXPECT_TRUE(caps.supported);
    EXPECT_EQ(caps.type, DsdInterfaceType::RAW_DSD);
    EXPECT_EQ(caps.interfaceNumber, 1u);
    EXPECT_EQ(caps.altSetting, 1u);
    EXPECT_EQ(caps.endpointAddress, 0x01u);
}

TEST_F(NativeDsdTest, DetectsUac2DsdCapability) {
    auto desc = makeUac2DsdCapable();
    auto caps = transport.inspectCapabilities(desc);

    EXPECT_TRUE(caps.supported);
    EXPECT_EQ(caps.type, DsdInterfaceType::RAW_DSD);
    EXPECT_EQ(caps.interfaceNumber, 1u);
    EXPECT_EQ(caps.altSetting, 2u);
    EXPECT_EQ(caps.endpointAddress, 0x02u);
}

TEST_F(NativeDsdTest, DetectsSupportedRates) {
    auto desc = makeUac2DsdCapable(); // 1024 byte max packet
    auto caps = transport.inspectCapabilities(desc);

    EXPECT_TRUE(caps.supported);
    // With 1024 byte packets, should support at least DSD64 and DSD128
    EXPECT_FALSE(caps.supportedDsdRates.empty());

    // DSD64 requires about 89 bytes/packet for stereo - should be supported
    bool hasDsd64 = false;
    for (uint32_t rate : caps.supportedDsdRates) {
        if (rate == 2822400) hasDsd64 = true;
    }
    EXPECT_TRUE(hasDsd64);
}

// === Correct Rejection ===

TEST_F(NativeDsdTest, RejectsPcmOnlyDac) {
    auto desc = makePcmOnlyDac();
    auto caps = transport.inspectCapabilities(desc);

    EXPECT_FALSE(caps.supported);
    EXPECT_EQ(caps.type, DsdInterfaceType::NONE);
    EXPECT_TRUE(caps.supportedDsdRates.empty());
}

TEST_F(NativeDsdTest, RejectsEmptyDescriptors) {
    AudioDeviceDescriptors emptyDesc;
    auto caps = transport.inspectCapabilities(emptyDesc);

    EXPECT_FALSE(caps.supported);
}

TEST_F(NativeDsdTest, IsAvailableReflectsInspection) {
    EXPECT_FALSE(transport.isAvailable());

    auto desc = makeUac2DsdCapable();
    transport.inspectCapabilities(desc);
    EXPECT_TRUE(transport.isAvailable());
}

// === Configuration ===

TEST_F(NativeDsdTest, ConfigureSucceedsForSupportedRate) {
    auto desc = makeUac2DsdCapable();
    transport.inspectCapabilities(desc);

    if (!transport.getCapability().supportedDsdRates.empty()) {
        uint32_t rate = transport.getCapability().supportedDsdRates[0];
        EXPECT_TRUE(transport.configure(rate, 2));
        EXPECT_EQ(transport.getState(), NativeDsdState::CONFIGURED);
    }
}

TEST_F(NativeDsdTest, ConfigureFailsForUnsupportedRate) {
    auto desc = makeUac1DsdCapable();
    desc.streamingInterfaces[0].altSettings[0].endpoint.wMaxPacketSize = 32; // Very small
    transport.inspectCapabilities(desc);

    // DSD256 (11289600) likely not supported with 32-byte packets
    EXPECT_FALSE(transport.configure(11289600, 2));
    EXPECT_EQ(transport.getState(), NativeDsdState::ERROR);
}

TEST_F(NativeDsdTest, ConfigureFailsWithoutInspection) {
    // No inspection done, so no capability
    EXPECT_FALSE(transport.configure(2822400, 2));
}

// === Packet Preparation ===

TEST_F(NativeDsdTest, PreparePacketPassesThroughRawDsd) {
    auto desc = makeUac2DsdCapable();
    transport.inspectCapabilities(desc);

    if (!transport.getCapability().supportedDsdRates.empty()) {
        uint32_t rate = transport.getCapability().supportedDsdRates[0];
        ASSERT_TRUE(transport.configure(rate, 2));

        uint8_t dsdData[] = {0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF};
        uint8_t packet[64];

        size_t packetSize = transport.preparePacket(dsdData, 6, packet, sizeof(packet));
        ASSERT_EQ(packetSize, 6u);

        // Raw DSD should be passed through without modification
        EXPECT_EQ(std::memcmp(dsdData, packet, 6), 0);
        EXPECT_EQ(transport.getState(), NativeDsdState::STREAMING);
    }
}

TEST_F(NativeDsdTest, PreparePacketRejectsWithoutConfiguration) {
    uint8_t dsdData[] = {0xAA, 0xBB};
    uint8_t packet[64];

    size_t packetSize = transport.preparePacket(dsdData, 2, packet, sizeof(packet));
    EXPECT_EQ(packetSize, 0u);
}

// === Reset ===

TEST_F(NativeDsdTest, ResetClearsState) {
    auto desc = makeUac2DsdCapable();
    transport.inspectCapabilities(desc);

    if (!transport.getCapability().supportedDsdRates.empty()) {
        transport.configure(transport.getCapability().supportedDsdRates[0], 2);
        EXPECT_EQ(transport.getState(), NativeDsdState::CONFIGURED);

        transport.reset();
        EXPECT_EQ(transport.getState(), NativeDsdState::IDLE);
    }
}

// === SupportsRate ===

TEST_F(NativeDsdTest, SupportsRateChecksCorrectly) {
    auto desc = makeUac2DsdCapable();
    transport.inspectCapabilities(desc);

    // Check that unsupported arbitrary rate returns false
    EXPECT_FALSE(transport.supportsRate(44100)); // Not a DSD rate
}
