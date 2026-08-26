#pragma once

#include "usb_types.h"
#include <vector>
#include <string>
#include <cstring>

namespace bitperfect {
namespace usb {

// Standard USB Interface Descriptor (parsed)
struct InterfaceDescriptor {
    uint8_t bInterfaceNumber = 0;
    uint8_t bAlternateSetting = 0;
    uint8_t bNumEndpoints = 0;
    uint8_t bInterfaceClass = 0;
    uint8_t bInterfaceSubClass = 0;
    uint8_t bInterfaceProtocol = 0;
};

// Standard USB Endpoint Descriptor (parsed)
struct EndpointDescriptor {
    uint8_t bEndpointAddress = 0;
    uint8_t bmAttributes = 0;
    uint16_t wMaxPacketSize = 0;
    uint8_t bInterval = 0;
    uint8_t bRefresh = 0;
    uint8_t bSynchAddress = 0;

    bool isInput() const { return (bEndpointAddress & 0x80) != 0; }
    bool isOutput() const { return (bEndpointAddress & 0x80) == 0; }
    uint8_t endpointNumber() const { return bEndpointAddress & 0x0F; }
    SyncType syncType() const { return static_cast<SyncType>((bmAttributes >> 2) & 0x03); }
    bool isIsochronous() const { return (bmAttributes & 0x03) == 0x01; }
};

// UAC1 Audio Control Header
struct AcHeaderDescriptor {
    uint16_t bcdADC = 0;       // Audio class spec version
    uint16_t wTotalLength = 0;
    uint8_t bInCollection = 0;  // Number of streaming interfaces
    std::vector<uint8_t> baInterfaceNr;  // Interface numbers
};

// Input Terminal Descriptor
struct InputTerminalDescriptor {
    uint8_t bTerminalID = 0;
    TerminalType wTerminalType = TerminalType::USB_UNDEFINED;
    uint8_t bAssocTerminal = 0;
    uint8_t bNrChannels = 0;
    uint16_t wChannelConfig = 0;
    uint8_t bCSourceID = 0;  // UAC2: clock source ID
};

// Output Terminal Descriptor
struct OutputTerminalDescriptor {
    uint8_t bTerminalID = 0;
    TerminalType wTerminalType = TerminalType::USB_UNDEFINED;
    uint8_t bAssocTerminal = 0;
    uint8_t bSourceID = 0;
    uint8_t bCSourceID = 0;  // UAC2: clock source ID
};

// Feature Unit Descriptor
struct FeatureUnitDescriptor {
    uint8_t bUnitID = 0;
    uint8_t bSourceID = 0;
    std::vector<uint32_t> bmaControls;
};

// UAC2 Clock Source Descriptor
struct ClockSourceDescriptor {
    uint8_t bClockID = 0;
    uint8_t bmAttributes = 0;
    uint8_t bmControls = 0;
    uint8_t bAssocTerminal = 0;

    ClockAttributes clockType() const {
        return static_cast<ClockAttributes>(bmAttributes & 0x03);
    }
    bool isSyncedToSOF() const { return (bmAttributes & 0x04) != 0; }
};

// UAC2 Clock Selector Descriptor
struct ClockSelectorDescriptor {
    uint8_t bClockID = 0;
    uint8_t bNrInPins = 0;
    std::vector<uint8_t> baCSourceID;
    uint8_t bmControls = 0;
};

// UAC2 Clock Multiplier Descriptor
struct ClockMultiplierDescriptor {
    uint8_t bClockID = 0;
    uint8_t bCSourceID = 0;
    uint8_t bmControls = 0;
};

// Audio Streaming General Descriptor (UAC1)
struct AsGeneralDescriptorUac1 {
    uint8_t bTerminalLink = 0;
    uint8_t bDelay = 0;
    FormatTag wFormatTag = FormatTag::PCM;
};

// Audio Streaming General Descriptor (UAC2)
struct AsGeneralDescriptorUac2 {
    uint8_t bTerminalLink = 0;
    uint8_t bmControls = 0;
    uint8_t bFormatType = 0;
    uint32_t bmFormats = 0;
    uint8_t bNrChannels = 0;
    uint32_t bmChannelConfig = 0;
};

// Format Type I Descriptor (UAC1)
struct FormatTypeIDescriptorUac1 {
    uint8_t bNrChannels = 0;
    uint8_t bSubframeSize = 0;  // Bytes per sample (2, 3, or 4)
    uint8_t bBitResolution = 0; // Bits per sample
    uint8_t bSamFreqType = 0;   // 0 = continuous, otherwise discrete count
    std::vector<uint32_t> sampleRates;  // Discrete rates or min/max for continuous
};

// Format Type I Descriptor (UAC2)
struct FormatTypeIDescriptorUac2 {
    uint8_t bSubslotSize = 0;   // Bytes per subslot (sample container)
    uint8_t bBitResolution = 0; // Bits of audio data per subslot
};

// Class-Specific AS Isochronous Audio Data Endpoint Descriptor
struct AudioEndpointDescriptor {
    uint8_t bmAttributes = 0;   // Sampling freq, pitch, max packets only
    uint8_t bmControls = 0;     // UAC2
    uint8_t bLockDelayUnits = 0;
    uint16_t wLockDelay = 0;

    bool hasSamplingFreqControl() const { return (bmAttributes & EP_ATTR_SAMPLING_FREQ_CONTROL) != 0; }
    bool hasPitchControl() const { return (bmAttributes & EP_ATTR_PITCH_CONTROL) != 0; }
    bool maxPacketsOnly() const { return (bmAttributes & EP_ATTR_MAX_PACKETS_ONLY) != 0; }
};

// Complete alternate setting info
struct AlternateSettingInfo {
    InterfaceDescriptor interface_desc;
    EndpointDescriptor endpoint;
    AudioEndpointDescriptor audio_endpoint;

    // UAC1
    AsGeneralDescriptorUac1 as_general_uac1;
    FormatTypeIDescriptorUac1 format_uac1;

    // UAC2
    AsGeneralDescriptorUac2 as_general_uac2;
    FormatTypeIDescriptorUac2 format_uac2;

    bool is_valid = false;
};

// Complete audio streaming interface
struct AudioStreamingInterface {
    uint8_t interfaceNumber = 0;
    std::vector<AlternateSettingInfo> altSettings;
};

// Parsed USB Audio Device
struct AudioDeviceDescriptors {
    UacVersion version = UacVersion::UAC1;

    // Audio Control
    AcHeaderDescriptor acHeader;
    std::vector<InputTerminalDescriptor> inputTerminals;
    std::vector<OutputTerminalDescriptor> outputTerminals;
    std::vector<FeatureUnitDescriptor> featureUnits;

    // UAC2 clock topology
    std::vector<ClockSourceDescriptor> clockSources;
    std::vector<ClockSelectorDescriptor> clockSelectors;
    std::vector<ClockMultiplierDescriptor> clockMultipliers;

    // Streaming interfaces
    std::vector<AudioStreamingInterface> streamingInterfaces;

    // Device info
    uint16_t vendorId = 0;
    uint16_t productId = 0;
    std::string deviceName;
};

} // namespace usb
} // namespace bitperfect
