#include <gtest/gtest.h>
#include "../usb/usb_audio_device.h"
#include <vector>
#include <cstring>

using namespace bitperfect::usb;

class UsbDescriptorsTest : public ::testing::Test {
protected:
    void SetUp() override {}
    void TearDown() override {}

    // Helper to build a UAC1 configuration descriptor for a USB DAC
    std::vector<uint8_t> buildUac1DacDescriptor() {
        std::vector<uint8_t> desc;

        // Standard Interface Descriptor - Audio Control (Interface 0, Alt 0)
        appendInterfaceDescriptor(desc, 0, 0, 0, 0x01, 0x01, 0x00);

        // AC Header Descriptor (UAC1)
        // bLength=10, bDescriptorType=0x24, bDescriptorSubtype=0x01
        // bcdADC=0x0100 (UAC1), wTotalLength=XX, bInCollection=1, baInterfaceNr=1
        {
            uint8_t header[] = {
                10, 0x24, 0x01,      // len, CS_INTERFACE, HEADER
                0x00, 0x01,          // bcdADC = 1.00 (little-endian)
                38, 0x00,            // wTotalLength = 38
                0x01,                // bInCollection = 1
                0x01,                // baInterfaceNr[0] = 1 (streaming interface)
                0x00                 // padding to match length
            };
            desc.insert(desc.end(), header, header + 10);
        }

        // Input Terminal (USB Streaming)
        {
            uint8_t it[] = {
                12, 0x24, 0x02,      // len, CS_INTERFACE, INPUT_TERMINAL
                0x01,                // bTerminalID = 1
                0x01, 0x01,          // wTerminalType = USB_STREAMING (0x0101)
                0x00,                // bAssocTerminal = 0
                0x02,                // bNrChannels = 2
                0x03, 0x00,          // wChannelConfig = FL|FR
                0x00,                // iChannelNames
                0x00                 // iTerminal
            };
            desc.insert(desc.end(), it, it + 12);
        }

        // Output Terminal (Speaker)
        {
            uint8_t ot[] = {
                9, 0x24, 0x03,       // len, CS_INTERFACE, OUTPUT_TERMINAL
                0x02,                // bTerminalID = 2
                0x01, 0x03,          // wTerminalType = SPEAKER (0x0301)
                0x00,                // bAssocTerminal
                0x01,                // bSourceID = 1 (from input terminal)
                0x00                 // iTerminal
            };
            desc.insert(desc.end(), ot, ot + 9);
        }

        // Standard Interface Descriptor - Audio Streaming (Interface 1, Alt 0 - zero bandwidth)
        appendInterfaceDescriptor(desc, 1, 0, 0, 0x01, 0x02, 0x00);

        // Standard Interface Descriptor - Audio Streaming (Interface 1, Alt 1)
        appendInterfaceDescriptor(desc, 1, 1, 1, 0x01, 0x02, 0x00);

        // AS General Descriptor (UAC1)
        {
            uint8_t asg[] = {
                7, 0x24, 0x01,       // len, CS_INTERFACE, AS_GENERAL
                0x01,                // bTerminalLink = 1
                0x01,                // bDelay = 1
                0x01, 0x00           // wFormatTag = PCM (0x0001)
            };
            desc.insert(desc.end(), asg, asg + 7);
        }

        // Format Type I Descriptor (UAC1) - 16-bit stereo, 44100 and 48000 Hz
        {
            uint8_t fmt[] = {
                14, 0x24, 0x02,      // len, CS_INTERFACE, FORMAT_TYPE
                0x01,                // bFormatType = FORMAT_TYPE_I
                0x02,                // bNrChannels = 2
                0x02,                // bSubframeSize = 2 (16-bit)
                0x10,                // bBitResolution = 16
                0x02,                // bSamFreqType = 2 (discrete)
                0x44, 0xAC, 0x00,    // 44100 Hz (little-endian 3 bytes)
                0x80, 0xBB, 0x00     // 48000 Hz
            };
            desc.insert(desc.end(), fmt, fmt + 14);
        }

        // Standard Endpoint Descriptor (Isochronous OUT)
        {
            uint8_t ep[] = {
                9, 0x05,             // len, ENDPOINT
                0x01,                // bEndpointAddress = 1 OUT
                0x09,                // bmAttributes = Iso + Adaptive
                0xC0, 0x00,          // wMaxPacketSize = 192
                0x01,                // bInterval = 1
                0x00,                // bRefresh
                0x00                 // bSynchAddress
            };
            desc.insert(desc.end(), ep, ep + 9);
        }

        // Audio Endpoint Descriptor
        {
            uint8_t aep[] = {
                7, 0x25, 0x01,       // len, CS_ENDPOINT, EP_GENERAL
                0x01,                // bmAttributes: sampling freq control
                0x00,                // bLockDelayUnits
                0x00, 0x00           // wLockDelay
            };
            desc.insert(desc.end(), aep, aep + 7);
        }

        return desc;
    }

    // Helper to build a UAC2 configuration descriptor
    std::vector<uint8_t> buildUac2DacDescriptor() {
        std::vector<uint8_t> desc;

        // Standard Interface Descriptor - Audio Control (Interface 0)
        appendInterfaceDescriptor(desc, 0, 0, 0, 0x01, 0x01, 0x20);

        // AC Header Descriptor (UAC2)
        // bcdADC=0x0200, bCategory, wTotalLength, bmControls
        {
            uint8_t header[] = {
                9, 0x24, 0x01,       // len, CS_INTERFACE, HEADER
                0x00, 0x02,          // bcdADC = 2.00
                0x0A,                // bCategory = Desktop speaker
                30, 0x00,            // wTotalLength
                0x00                 // bmControls
            };
            desc.insert(desc.end(), header, header + 9);
        }

        // Clock Source Descriptor (UAC2)
        {
            uint8_t cs[] = {
                8, 0x24, 0x0A,       // len, CS_INTERFACE, CLOCK_SOURCE
                0x09,                // bClockID = 9
                0x01,                // bmAttributes = Internal fixed
                0x07,                // bmControls = freq r/w, validity read
                0x00,                // bAssocTerminal
                0x00                 // iClockSource
            };
            desc.insert(desc.end(), cs, cs + 8);
        }

        // Input Terminal (USB Streaming, UAC2)
        {
            uint8_t it[] = {
                17, 0x24, 0x02,      // len, CS_INTERFACE, INPUT_TERMINAL
                0x01,                // bTerminalID = 1
                0x01, 0x01,          // wTerminalType = USB_STREAMING
                0x00,                // bAssocTerminal
                0x09,                // bCSourceID = 9 (clock source)
                0x02,                // bNrChannels = 2
                0x03, 0x00, 0x00, 0x00, // bmChannelConfig = FL|FR
                0x00,                // iChannelNames
                0x00, 0x00,          // bmControls
                0x00                 // iTerminal
            };
            desc.insert(desc.end(), it, it + 17);
        }

        // Output Terminal (Speaker, UAC2)
        {
            uint8_t ot[] = {
                12, 0x24, 0x03,      // len, CS_INTERFACE, OUTPUT_TERMINAL
                0x02,                // bTerminalID = 2
                0x01, 0x03,          // wTerminalType = SPEAKER
                0x00,                // bAssocTerminal
                0x01,                // bSourceID = 1
                0x09,                // bCSourceID = 9
                0x00, 0x00,          // bmControls
                0x00                 // iTerminal
            };
            desc.insert(desc.end(), ot, ot + 12);
        }

        // Standard Interface Descriptor - Audio Streaming (Interface 1, Alt 0 - zero bandwidth)
        appendInterfaceDescriptor(desc, 1, 0, 0, 0x01, 0x02, 0x20);

        // Standard Interface Descriptor - Audio Streaming (Interface 1, Alt 1 - 24-bit)
        appendInterfaceDescriptor(desc, 1, 1, 1, 0x01, 0x02, 0x20);

        // AS General Descriptor (UAC2)
        {
            uint8_t asg[] = {
                16, 0x24, 0x01,      // len, CS_INTERFACE, AS_GENERAL
                0x01,                // bTerminalLink = 1
                0x00,                // bmControls
                0x01,                // bFormatType = FORMAT_TYPE_I
                0x01, 0x00, 0x00, 0x00, // bmFormats = PCM
                0x02,                // bNrChannels = 2
                0x03, 0x00, 0x00, 0x00, // bmChannelConfig = FL|FR
                0x00                 // iChannelNames
            };
            desc.insert(desc.end(), asg, asg + 16);
        }

        // Format Type I Descriptor (UAC2) - 24-bit in 4-byte container
        {
            uint8_t fmt[] = {
                6, 0x24, 0x02,       // len, CS_INTERFACE, FORMAT_TYPE
                0x01,                // bFormatType = FORMAT_TYPE_I
                0x04,                // bSubslotSize = 4 bytes
                0x18                 // bBitResolution = 24
            };
            desc.insert(desc.end(), fmt, fmt + 6);
        }

        // Standard Endpoint Descriptor (Isochronous OUT, Async)
        {
            uint8_t ep[] = {
                7, 0x05,             // len, ENDPOINT
                0x01,                // bEndpointAddress = 1 OUT
                0x05,                // bmAttributes = Iso + Async
                0x00, 0x04,          // wMaxPacketSize = 1024
                0x01                 // bInterval = 1
            };
            desc.insert(desc.end(), ep, ep + 7);
        }

        // Audio Endpoint Descriptor (UAC2)
        {
            uint8_t aep[] = {
                8, 0x25, 0x01,       // len, CS_ENDPOINT, EP_GENERAL
                0x00,                // bmAttributes
                0x00,                // bmControls
                0x00,                // bLockDelayUnits
                0x00, 0x00           // wLockDelay
            };
            desc.insert(desc.end(), aep, aep + 8);
        }

        return desc;
    }

    void appendInterfaceDescriptor(std::vector<uint8_t>& desc, uint8_t ifNum,
                                    uint8_t altSetting, uint8_t numEndpoints,
                                    uint8_t ifClass, uint8_t ifSubClass, uint8_t ifProtocol) {
        uint8_t iface[] = {
            9, 0x04,             // len, INTERFACE
            ifNum,               // bInterfaceNumber
            altSetting,          // bAlternateSetting
            numEndpoints,        // bNumEndpoints
            ifClass,             // bInterfaceClass
            ifSubClass,          // bInterfaceSubClass
            ifProtocol,          // bInterfaceProtocol
            0x00                 // iInterface
        };
        desc.insert(desc.end(), iface, iface + 9);
    }
};

TEST_F(UsbDescriptorsTest, ParseUac1Device) {
    auto descriptorBytes = buildUac1DacDescriptor();

    UsbAudioDevice device;
    bool result = device.parseDescriptors(descriptorBytes.data(), descriptorBytes.size());

    EXPECT_TRUE(result);
    EXPECT_EQ(device.getVersion(), UacVersion::UAC1);

    const auto& desc = device.getDescriptors();

    // Should have found the AC header
    EXPECT_EQ(desc.acHeader.bcdADC, 0x0100);
    EXPECT_EQ(desc.acHeader.bInCollection, 1);

    // Should have found input and output terminals
    EXPECT_EQ(desc.inputTerminals.size(), 1u);
    EXPECT_EQ(desc.inputTerminals[0].bTerminalID, 1);
    EXPECT_EQ(desc.inputTerminals[0].wTerminalType, TerminalType::USB_STREAMING);
    EXPECT_EQ(desc.inputTerminals[0].bNrChannels, 2);

    EXPECT_EQ(desc.outputTerminals.size(), 1u);
    EXPECT_EQ(desc.outputTerminals[0].bTerminalID, 2);
    EXPECT_EQ(desc.outputTerminals[0].wTerminalType, TerminalType::SPEAKER);
    EXPECT_EQ(desc.outputTerminals[0].bSourceID, 1);

    // Should have found streaming interface with alt setting
    EXPECT_GE(desc.streamingInterfaces.size(), 1u);

    // Find the streaming interface with valid alt settings
    bool foundAlt1 = false;
    for (const auto& si : desc.streamingInterfaces) {
        for (const auto& alt : si.altSettings) {
            if (alt.interface_desc.bAlternateSetting == 1) {
                foundAlt1 = true;
                EXPECT_EQ(alt.format_uac1.bNrChannels, 2);
                EXPECT_EQ(alt.format_uac1.bSubframeSize, 2);
                EXPECT_EQ(alt.format_uac1.bBitResolution, 16);
                EXPECT_EQ(alt.format_uac1.bSamFreqType, 2);
                ASSERT_GE(alt.format_uac1.sampleRates.size(), 2u);
                EXPECT_EQ(alt.format_uac1.sampleRates[0], 44100u);
                EXPECT_EQ(alt.format_uac1.sampleRates[1], 48000u);

                // Endpoint should be isochronous OUT
                EXPECT_TRUE(alt.endpoint.isOutput());
                EXPECT_TRUE(alt.endpoint.isIsochronous());
                EXPECT_EQ(alt.endpoint.syncType(), SyncType::ADAPTIVE);

                // Audio endpoint attributes
                EXPECT_TRUE(alt.audio_endpoint.hasSamplingFreqControl());
            }
        }
    }
    EXPECT_TRUE(foundAlt1);
}

TEST_F(UsbDescriptorsTest, ParseUac2Device) {
    auto descriptorBytes = buildUac2DacDescriptor();

    UsbAudioDevice device;
    bool result = device.parseDescriptors(descriptorBytes.data(), descriptorBytes.size());

    EXPECT_TRUE(result);
    EXPECT_EQ(device.getVersion(), UacVersion::UAC2);

    const auto& desc = device.getDescriptors();

    // Clock source
    EXPECT_EQ(desc.clockSources.size(), 1u);
    EXPECT_EQ(desc.clockSources[0].bClockID, 9);
    EXPECT_EQ(desc.clockSources[0].clockType(), ClockAttributes::INTERNAL_FIXED);

    // Input terminal with clock reference
    EXPECT_EQ(desc.inputTerminals.size(), 1u);
    EXPECT_EQ(desc.inputTerminals[0].bCSourceID, 9);
    EXPECT_EQ(desc.inputTerminals[0].wTerminalType, TerminalType::USB_STREAMING);

    // Output terminal
    EXPECT_EQ(desc.outputTerminals.size(), 1u);
    EXPECT_EQ(desc.outputTerminals[0].wTerminalType, TerminalType::SPEAKER);
    EXPECT_EQ(desc.outputTerminals[0].bCSourceID, 9);

    // Streaming interface
    EXPECT_GE(desc.streamingInterfaces.size(), 1u);

    bool foundAlt1 = false;
    for (const auto& si : desc.streamingInterfaces) {
        for (const auto& alt : si.altSettings) {
            if (alt.interface_desc.bAlternateSetting == 1) {
                foundAlt1 = true;
                // UAC2 format info
                EXPECT_EQ(alt.format_uac2.bSubslotSize, 4);
                EXPECT_EQ(alt.format_uac2.bBitResolution, 24);

                // AS General UAC2
                EXPECT_EQ(alt.as_general_uac2.bTerminalLink, 1);
                EXPECT_EQ(alt.as_general_uac2.bNrChannels, 2);

                // Endpoint: async isochronous
                EXPECT_TRUE(alt.endpoint.isOutput());
                EXPECT_TRUE(alt.endpoint.isIsochronous());
                EXPECT_EQ(alt.endpoint.syncType(), SyncType::ASYNC);
            }
        }
    }
    EXPECT_TRUE(foundAlt1);
}

TEST_F(UsbDescriptorsTest, EmptyDescriptor) {
    UsbAudioDevice device;
    EXPECT_FALSE(device.parseDescriptors(nullptr, 0));

    uint8_t empty[] = {0};
    EXPECT_FALSE(device.parseDescriptors(empty, 1));
}

TEST_F(UsbDescriptorsTest, TruncatedDescriptor) {
    // A descriptor that is too short
    uint8_t truncated[] = {9, 0x04, 0x00};
    UsbAudioDevice device;
    EXPECT_FALSE(device.parseDescriptors(truncated, 3));
}

TEST_F(UsbDescriptorsTest, GetSupportedSampleRatesUac1) {
    auto descriptorBytes = buildUac1DacDescriptor();
    UsbAudioDevice device;
    device.parseDescriptors(descriptorBytes.data(), descriptorBytes.size());

    // Interface 1, alt setting 1 should have 44100 and 48000
    auto rates = device.getSupportedSampleRates(1, 1);
    ASSERT_EQ(rates.size(), 2u);
    EXPECT_EQ(rates[0], 44100u);
    EXPECT_EQ(rates[1], 48000u);
}

TEST_F(UsbDescriptorsTest, FindBestAltSettingUac1) {
    auto descriptorBytes = buildUac1DacDescriptor();
    UsbAudioDevice device;
    device.parseDescriptors(descriptorBytes.data(), descriptorBytes.size());

    // Should find alt setting for 16-bit at 44100
    auto alt = device.findBestAltSetting(1, 44100, PcmFormat::S16_LE);
    ASSERT_TRUE(alt.has_value());
    EXPECT_EQ(alt.value(), 1);

    // 24-bit not available in this descriptor
    auto alt24 = device.findBestAltSetting(1, 44100, PcmFormat::S24_3LE);
    EXPECT_FALSE(alt24.has_value());
}

TEST_F(UsbDescriptorsTest, FindBestAltSettingUac2) {
    auto descriptorBytes = buildUac2DacDescriptor();
    UsbAudioDevice device;
    device.parseDescriptors(descriptorBytes.data(), descriptorBytes.size());

    // 24-bit in 32-bit container should match
    auto alt = device.findBestAltSetting(1, 96000, PcmFormat::S24_LE);
    ASSERT_TRUE(alt.has_value());
    EXPECT_EQ(alt.value(), 1);

    // 16-bit not available
    auto alt16 = device.findBestAltSetting(1, 44100, PcmFormat::S16_LE);
    EXPECT_FALSE(alt16.has_value());
}

TEST_F(UsbDescriptorsTest, SupportsFormat) {
    auto descriptorBytes = buildUac1DacDescriptor();
    UsbAudioDevice device;
    device.parseDescriptors(descriptorBytes.data(), descriptorBytes.size());

    EXPECT_TRUE(device.supportsFormat(PcmFormat::S16_LE));
    EXPECT_FALSE(device.supportsFormat(PcmFormat::S24_3LE));
    EXPECT_FALSE(device.supportsFormat(PcmFormat::S32_LE));
}

TEST_F(UsbDescriptorsTest, GetOutputStreamingInterface) {
    auto descriptorBytes = buildUac1DacDescriptor();
    UsbAudioDevice device;
    device.parseDescriptors(descriptorBytes.data(), descriptorBytes.size());

    auto iface = device.getOutputStreamingInterface();
    ASSERT_TRUE(iface.has_value());
    EXPECT_EQ(iface.value(), 1);
}

TEST_F(UsbDescriptorsTest, Uac1ContinuousRates) {
    // Build a UAC1 descriptor with continuous sample rate range
    std::vector<uint8_t> desc;

    // Interface 0 - Audio Control
    appendInterfaceDescriptor(desc, 0, 0, 0, 0x01, 0x01, 0x00);

    // AC Header
    uint8_t header[] = {10, 0x24, 0x01, 0x00, 0x01, 30, 0x00, 0x01, 0x01, 0x00};
    desc.insert(desc.end(), header, header + 10);

    // Input Terminal
    uint8_t it[] = {12, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00};
    desc.insert(desc.end(), it, it + 12);

    // Output Terminal
    uint8_t ot[] = {9, 0x24, 0x03, 0x02, 0x01, 0x03, 0x00, 0x01, 0x00};
    desc.insert(desc.end(), ot, ot + 9);

    // Interface 1 - Streaming, Alt 0
    appendInterfaceDescriptor(desc, 1, 0, 0, 0x01, 0x02, 0x00);
    // Interface 1 - Streaming, Alt 1
    appendInterfaceDescriptor(desc, 1, 1, 1, 0x01, 0x02, 0x00);

    // AS General
    uint8_t asg[] = {7, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00};
    desc.insert(desc.end(), asg, asg + 7);

    // Format Type I - Continuous: 44100 to 192000
    uint8_t fmt[] = {
        14, 0x24, 0x02, 0x01,   // FORMAT_TYPE_I
        0x02,                    // 2 channels
        0x03,                    // 3 bytes (24-bit)
        0x18,                    // 24 bit resolution
        0x00,                    // bSamFreqType = 0 (continuous)
        0x44, 0xAC, 0x00,       // lower = 44100
        0x00, 0xEE, 0x02        // upper = 192000
    };
    desc.insert(desc.end(), fmt, fmt + 14);

    // Endpoint
    uint8_t ep[] = {9, 0x05, 0x01, 0x09, 0x00, 0x02, 0x01, 0x00, 0x00};
    desc.insert(desc.end(), ep, ep + 9);

    // Audio Endpoint
    uint8_t aep[] = {7, 0x25, 0x01, 0x01, 0x00, 0x00, 0x00};
    desc.insert(desc.end(), aep, aep + 7);

    UsbAudioDevice device;
    bool result = device.parseDescriptors(desc.data(), desc.size());
    EXPECT_TRUE(result);

    // Check continuous range was stored
    auto rates = device.getSupportedSampleRates(1, 1);
    ASSERT_GE(rates.size(), 2u);
    EXPECT_EQ(rates[0], 44100u);
    EXPECT_EQ(rates[1], 192000u);
}
