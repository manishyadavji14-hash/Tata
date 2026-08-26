#include "usb_audio_device.h"
#include <algorithm>
#include <cstring>

namespace bitperfect {
namespace usb {

bool UsbAudioDevice::parseDescriptors(const uint8_t* data, size_t length) {
    if (!data || length < 4) return false;

    // Clear previous state
    descriptors_ = AudioDeviceDescriptors{};

    size_t offset = 0;
    uint8_t currentInterface = 0xFF;
    uint8_t currentAltSetting = 0;
    AudioInterfaceSubclass currentSubclass = AudioInterfaceSubclass::UNDEFINED;
    bool inAudioStreaming = false;
    AlternateSettingInfo currentAlt;
    AudioStreamingInterface* currentStreamIface = nullptr;

    while (offset + 1 < length) {
        uint8_t bLength = data[offset];
        if (bLength < 2 || offset + bLength > length) break;

        uint8_t bDescriptorType = data[offset + 1];

        if (bDescriptorType == static_cast<uint8_t>(DescriptorType::INTERFACE)) {
            // Standard Interface Descriptor: save previous alt setting if valid
            if (inAudioStreaming && currentAlt.is_valid && currentStreamIface) {
                currentStreamIface->altSettings.push_back(currentAlt);
            }

            if (bLength >= 9) {
                currentInterface = data[offset + 2];
                currentAltSetting = data[offset + 3];
                uint8_t bInterfaceClass = data[offset + 5];
                uint8_t bInterfaceSubClass = data[offset + 6];

                currentAlt = AlternateSettingInfo{};
                currentAlt.interface_desc.bInterfaceNumber = currentInterface;
                currentAlt.interface_desc.bAlternateSetting = currentAltSetting;
                currentAlt.interface_desc.bNumEndpoints = data[offset + 4];
                currentAlt.interface_desc.bInterfaceClass = bInterfaceClass;
                currentAlt.interface_desc.bInterfaceSubClass = bInterfaceSubClass;
                currentAlt.interface_desc.bInterfaceProtocol = data[offset + 7];

                if (bInterfaceClass == 0x01) { // AUDIO
                    currentSubclass = static_cast<AudioInterfaceSubclass>(bInterfaceSubClass);

                    if (currentSubclass == AudioInterfaceSubclass::AUDIO_STREAMING) {
                        inAudioStreaming = true;
                        // Find or create streaming interface
                        currentStreamIface = nullptr;
                        for (auto& si : descriptors_.streamingInterfaces) {
                            if (si.interfaceNumber == currentInterface) {
                                currentStreamIface = &si;
                                break;
                            }
                        }
                        if (!currentStreamIface) {
                            descriptors_.streamingInterfaces.push_back(AudioStreamingInterface{});
                            descriptors_.streamingInterfaces.back().interfaceNumber = currentInterface;
                            currentStreamIface = &descriptors_.streamingInterfaces.back();
                        }
                    } else {
                        inAudioStreaming = false;
                        currentStreamIface = nullptr;
                    }
                } else {
                    currentSubclass = AudioInterfaceSubclass::UNDEFINED;
                    inAudioStreaming = false;
                    currentStreamIface = nullptr;
                }
            }
        } else if (bDescriptorType == static_cast<uint8_t>(DescriptorType::CS_INTERFACE)) {
            if (bLength >= 3) {
                uint8_t bDescriptorSubtype = data[offset + 2];

                if (currentSubclass == AudioInterfaceSubclass::AUDIO_CONTROL) {
                    // Audio Control descriptors
                    auto subtype = static_cast<AcDescriptorSubtype>(bDescriptorSubtype);
                    switch (subtype) {
                        case AcDescriptorSubtype::HEADER:
                            parseAcHeader(data + offset, bLength);
                            break;
                        case AcDescriptorSubtype::INPUT_TERMINAL:
                            parseInputTerminal(data + offset, bLength);
                            break;
                        case AcDescriptorSubtype::OUTPUT_TERMINAL:
                            parseOutputTerminal(data + offset, bLength);
                            break;
                        case AcDescriptorSubtype::FEATURE_UNIT:
                            parseFeatureUnit(data + offset, bLength);
                            break;
                        case AcDescriptorSubtype::CLOCK_SOURCE:
                            if (descriptors_.version == UacVersion::UAC2) {
                                parseClockSource(data + offset, bLength);
                            }
                            break;
                        case AcDescriptorSubtype::CLOCK_SELECTOR:
                            if (descriptors_.version == UacVersion::UAC2) {
                                parseClockSelector(data + offset, bLength);
                            }
                            break;
                        case AcDescriptorSubtype::CLOCK_MULTIPLIER:
                            if (descriptors_.version == UacVersion::UAC2) {
                                parseClockMultiplier(data + offset, bLength);
                            }
                            break;
                        default:
                            break;
                    }
                } else if (currentSubclass == AudioInterfaceSubclass::AUDIO_STREAMING) {
                    // Audio Streaming descriptors
                    auto subtype = static_cast<AsDescriptorSubtype>(bDescriptorSubtype);
                    switch (subtype) {
                        case AsDescriptorSubtype::AS_GENERAL:
                            parseAsGeneral(data + offset, bLength, currentAlt);
                            break;
                        case AsDescriptorSubtype::FORMAT_TYPE:
                            parseFormatType(data + offset, bLength, currentAlt);
                            break;
                        default:
                            break;
                    }
                }
            }
        } else if (bDescriptorType == static_cast<uint8_t>(DescriptorType::ENDPOINT)) {
            if (inAudioStreaming) {
                parseEndpoint(data + offset, bLength, currentAlt);
            }
        } else if (bDescriptorType == static_cast<uint8_t>(DescriptorType::CS_ENDPOINT)) {
            if (inAudioStreaming) {
                parseAudioEndpoint(data + offset, bLength, currentAlt);
            }
        }

        offset += bLength;
    }

    // Save last alt setting
    if (inAudioStreaming && currentAlt.is_valid && currentStreamIface) {
        currentStreamIface->altSettings.push_back(currentAlt);
    }

    return !descriptors_.streamingInterfaces.empty();
}

bool UsbAudioDevice::parseAcHeader(const uint8_t* data, size_t length) {
    if (length < 8) return false;

    descriptors_.acHeader.bcdADC = readU16LE(data + 3);

    // Detect UAC version
    uint8_t majorVersion = (descriptors_.acHeader.bcdADC >> 8) & 0xFF;
    if (majorVersion >= 2) {
        descriptors_.version = UacVersion::UAC2;
    } else {
        descriptors_.version = UacVersion::UAC1;
    }

    if (descriptors_.version == UacVersion::UAC1) {
        if (length < 8) return false;
        descriptors_.acHeader.wTotalLength = readU16LE(data + 5);
        descriptors_.acHeader.bInCollection = data[7];
        for (size_t i = 0; i < descriptors_.acHeader.bInCollection && (8 + i) < length; ++i) {
            descriptors_.acHeader.baInterfaceNr.push_back(data[8 + i]);
        }
    } else {
        // UAC2 header is different
        if (length >= 9) {
            // bCategory at offset 5, wTotalLength at offset 6-7, bmControls at offset 8
            descriptors_.acHeader.wTotalLength = readU16LE(data + 6);
        }
    }

    return true;
}

bool UsbAudioDevice::parseInputTerminal(const uint8_t* data, size_t length) {
    InputTerminalDescriptor it;

    if (descriptors_.version == UacVersion::UAC1) {
        if (length < 12) return false;
        it.bTerminalID = data[3];
        it.wTerminalType = static_cast<TerminalType>(readU16LE(data + 4));
        it.bAssocTerminal = data[6];
        it.bNrChannels = data[7];
        it.wChannelConfig = readU16LE(data + 8);
    } else {
        // UAC2
        if (length < 17) return false;
        it.bTerminalID = data[3];
        it.wTerminalType = static_cast<TerminalType>(readU16LE(data + 4));
        it.bAssocTerminal = data[6];
        it.bCSourceID = data[7];  // Clock source ID
        it.bNrChannels = data[8];
        it.wChannelConfig = readU16LE(data + 9);  // Actually 4 bytes in UAC2
    }

    descriptors_.inputTerminals.push_back(it);
    return true;
}

bool UsbAudioDevice::parseOutputTerminal(const uint8_t* data, size_t length) {
    OutputTerminalDescriptor ot;

    if (descriptors_.version == UacVersion::UAC1) {
        if (length < 9) return false;
        ot.bTerminalID = data[3];
        ot.wTerminalType = static_cast<TerminalType>(readU16LE(data + 4));
        ot.bAssocTerminal = data[6];
        ot.bSourceID = data[7];
    } else {
        // UAC2
        if (length < 12) return false;
        ot.bTerminalID = data[3];
        ot.wTerminalType = static_cast<TerminalType>(readU16LE(data + 4));
        ot.bAssocTerminal = data[6];
        ot.bSourceID = data[7];
        ot.bCSourceID = data[8];  // Clock source ID
    }

    descriptors_.outputTerminals.push_back(ot);
    return true;
}

bool UsbAudioDevice::parseFeatureUnit(const uint8_t* data, size_t length) {
    if (length < 7) return false;

    FeatureUnitDescriptor fu;
    fu.bUnitID = data[3];
    fu.bSourceID = data[4];

    if (descriptors_.version == UacVersion::UAC1) {
        uint8_t bControlSize = data[5];
        if (bControlSize == 0) bControlSize = 1;
        size_t pos = 6;
        while (pos + bControlSize <= length - 1) {
            uint32_t control = 0;
            for (uint8_t i = 0; i < bControlSize && (pos + i) < length; ++i) {
                control |= (data[pos + i] << (i * 8));
            }
            fu.bmaControls.push_back(control);
            pos += bControlSize;
        }
    } else {
        // UAC2: bmaControls are 4 bytes each
        size_t pos = 5;
        while (pos + 4 <= length - 1) {
            fu.bmaControls.push_back(readU32LE(data + pos));
            pos += 4;
        }
    }

    descriptors_.featureUnits.push_back(fu);
    return true;
}

bool UsbAudioDevice::parseClockSource(const uint8_t* data, size_t length) {
    if (length < 8) return false;

    ClockSourceDescriptor cs;
    cs.bClockID = data[3];
    cs.bmAttributes = data[4];
    cs.bmControls = data[5];
    cs.bAssocTerminal = data[6];

    descriptors_.clockSources.push_back(cs);
    return true;
}

bool UsbAudioDevice::parseClockSelector(const uint8_t* data, size_t length) {
    if (length < 7) return false;

    ClockSelectorDescriptor csel;
    csel.bClockID = data[3];
    csel.bNrInPins = data[4];

    size_t pos = 5;
    for (uint8_t i = 0; i < csel.bNrInPins && pos < length; ++i, ++pos) {
        csel.baCSourceID.push_back(data[pos]);
    }
    if (pos < length) {
        csel.bmControls = data[pos];
    }

    descriptors_.clockSelectors.push_back(csel);
    return true;
}

bool UsbAudioDevice::parseClockMultiplier(const uint8_t* data, size_t length) {
    if (length < 7) return false;

    ClockMultiplierDescriptor cm;
    cm.bClockID = data[3];
    cm.bCSourceID = data[4];
    cm.bmControls = data[5];

    descriptors_.clockMultipliers.push_back(cm);
    return true;
}

bool UsbAudioDevice::parseAsGeneral(const uint8_t* data, size_t length, AlternateSettingInfo& alt) {
    if (descriptors_.version == UacVersion::UAC1) {
        if (length < 7) return false;
        alt.as_general_uac1.bTerminalLink = data[3];
        alt.as_general_uac1.bDelay = data[4];
        alt.as_general_uac1.wFormatTag = static_cast<FormatTag>(readU16LE(data + 5));
    } else {
        // UAC2
        if (length < 16) return false;
        alt.as_general_uac2.bTerminalLink = data[3];
        alt.as_general_uac2.bmControls = data[4];
        alt.as_general_uac2.bFormatType = data[5];
        alt.as_general_uac2.bmFormats = readU32LE(data + 6);
        alt.as_general_uac2.bNrChannels = data[10];
        alt.as_general_uac2.bmChannelConfig = readU32LE(data + 11);
    }
    return true;
}

bool UsbAudioDevice::parseFormatType(const uint8_t* data, size_t length, AlternateSettingInfo& alt) {
    if (length < 4) return false;

    uint8_t bFormatType = data[3];
    if (bFormatType != static_cast<uint8_t>(AudioFormatType::FORMAT_TYPE_I)) {
        return false; // We only handle Type I (PCM) for now
    }

    if (descriptors_.version == UacVersion::UAC1) {
        if (length < 8) return false;
        alt.format_uac1.bNrChannels = data[4];
        alt.format_uac1.bSubframeSize = data[5];
        alt.format_uac1.bBitResolution = data[6];
        alt.format_uac1.bSamFreqType = data[7];

        if (alt.format_uac1.bSamFreqType == 0) {
            // Continuous: lower and upper bounds (3 bytes each)
            if (length >= 14) {
                uint32_t lower = readU24LE(data + 8);
                uint32_t upper = readU24LE(data + 11);
                alt.format_uac1.sampleRates.push_back(lower);
                alt.format_uac1.sampleRates.push_back(upper);
            }
        } else {
            // Discrete sample rates
            for (uint8_t i = 0; i < alt.format_uac1.bSamFreqType; ++i) {
                size_t pos = 8 + i * 3;
                if (pos + 3 <= length) {
                    alt.format_uac1.sampleRates.push_back(readU24LE(data + pos));
                }
            }
        }
        alt.is_valid = true;
    } else {
        // UAC2 Format Type I descriptor is very short
        if (length < 6) return false;
        alt.format_uac2.bSubslotSize = data[4];
        alt.format_uac2.bBitResolution = data[5];
        alt.is_valid = true;
    }

    return true;
}

bool UsbAudioDevice::parseEndpoint(const uint8_t* data, size_t length, AlternateSettingInfo& alt) {
    if (length < 7) return false;

    alt.endpoint.bEndpointAddress = data[2];
    alt.endpoint.bmAttributes = data[3];
    alt.endpoint.wMaxPacketSize = readU16LE(data + 4);
    alt.endpoint.bInterval = data[6];

    if (length >= 8) alt.endpoint.bRefresh = data[7];
    if (length >= 9) alt.endpoint.bSynchAddress = data[8];

    return true;
}

bool UsbAudioDevice::parseAudioEndpoint(const uint8_t* data, size_t length, AlternateSettingInfo& alt) {
    if (length < 7) return false;

    // Subtype should be EP_GENERAL (0x01)
    if (data[2] != static_cast<uint8_t>(EpDescriptorSubtype::EP_GENERAL)) return false;

    alt.audio_endpoint.bmAttributes = data[3];

    if (descriptors_.version == UacVersion::UAC1) {
        alt.audio_endpoint.bLockDelayUnits = data[4];
        alt.audio_endpoint.wLockDelay = readU16LE(data + 5);
    } else {
        // UAC2
        alt.audio_endpoint.bmControls = data[4];
        alt.audio_endpoint.bLockDelayUnits = data[5];
        alt.audio_endpoint.wLockDelay = readU16LE(data + 6);
    }

    return true;
}

std::vector<uint32_t> UsbAudioDevice::getSupportedSampleRates(uint8_t interfaceNum,
                                                               uint8_t altSetting) const {
    for (const auto& si : descriptors_.streamingInterfaces) {
        if (si.interfaceNumber != interfaceNum) continue;
        for (const auto& alt : si.altSettings) {
            if (alt.interface_desc.bAlternateSetting != altSetting) continue;
            if (descriptors_.version == UacVersion::UAC1) {
                return alt.format_uac1.sampleRates;
            }
            // UAC2: sample rates are obtained via RANGE request at runtime
            // Return empty - caller should use USB control transfers
            break;
        }
    }
    return {};
}

std::optional<uint8_t> UsbAudioDevice::findBestAltSetting(uint8_t interfaceNum,
                                                            uint32_t sampleRate,
                                                            PcmFormat format) const {
    uint8_t targetSubframe = 0;
    uint8_t targetBits = 0;

    switch (format) {
        case PcmFormat::S16_LE: targetSubframe = 2; targetBits = 16; break;
        case PcmFormat::S24_3LE: targetSubframe = 3; targetBits = 24; break;
        case PcmFormat::S24_LE: targetSubframe = 4; targetBits = 24; break;
        case PcmFormat::S32_LE: targetSubframe = 4; targetBits = 32; break;
        case PcmFormat::FLOAT_LE: targetSubframe = 4; targetBits = 32; break;
    }

    for (const auto& si : descriptors_.streamingInterfaces) {
        if (si.interfaceNumber != interfaceNum) continue;

        for (const auto& alt : si.altSettings) {
            if (alt.interface_desc.bAlternateSetting == 0) continue; // Skip zero-bandwidth

            if (descriptors_.version == UacVersion::UAC1) {
                if (alt.format_uac1.bSubframeSize == targetSubframe &&
                    alt.format_uac1.bBitResolution == targetBits) {
                    // Check if sample rate is supported
                    if (alt.format_uac1.bSamFreqType == 0) {
                        // Continuous range
                        if (alt.format_uac1.sampleRates.size() >= 2) {
                            if (sampleRate >= alt.format_uac1.sampleRates[0] &&
                                sampleRate <= alt.format_uac1.sampleRates[1]) {
                                return alt.interface_desc.bAlternateSetting;
                            }
                        }
                    } else {
                        // Discrete
                        for (uint32_t rate : alt.format_uac1.sampleRates) {
                            if (rate == sampleRate) {
                                return alt.interface_desc.bAlternateSetting;
                            }
                        }
                    }
                }
            } else {
                // UAC2
                if (alt.format_uac2.bSubslotSize == targetSubframe &&
                    alt.format_uac2.bBitResolution == targetBits) {
                    // UAC2 doesn't embed sample rates in descriptors
                    return alt.interface_desc.bAlternateSetting;
                }
            }
        }
    }
    return std::nullopt;
}

bool UsbAudioDevice::supportsFormat(PcmFormat format) const {
    uint8_t targetSubframe = 0;
    uint8_t targetBits = 0;

    switch (format) {
        case PcmFormat::S16_LE: targetSubframe = 2; targetBits = 16; break;
        case PcmFormat::S24_3LE: targetSubframe = 3; targetBits = 24; break;
        case PcmFormat::S24_LE: targetSubframe = 4; targetBits = 24; break;
        case PcmFormat::S32_LE: targetSubframe = 4; targetBits = 32; break;
        case PcmFormat::FLOAT_LE: targetSubframe = 4; targetBits = 32; break;
    }

    for (const auto& si : descriptors_.streamingInterfaces) {
        for (const auto& alt : si.altSettings) {
            if (descriptors_.version == UacVersion::UAC1) {
                if (alt.format_uac1.bSubframeSize == targetSubframe &&
                    alt.format_uac1.bBitResolution == targetBits) {
                    return true;
                }
            } else {
                if (alt.format_uac2.bSubslotSize == targetSubframe &&
                    alt.format_uac2.bBitResolution == targetBits) {
                    return true;
                }
            }
        }
    }
    return false;
}

std::optional<uint8_t> UsbAudioDevice::getOutputStreamingInterface() const {
    // Find a streaming interface with an output endpoint (data going to device)
    for (const auto& si : descriptors_.streamingInterfaces) {
        for (const auto& alt : si.altSettings) {
            if (alt.endpoint.isOutput() && alt.endpoint.isIsochronous()) {
                return si.interfaceNumber;
            }
        }
    }
    return std::nullopt;
}

} // namespace usb
} // namespace bitperfect
