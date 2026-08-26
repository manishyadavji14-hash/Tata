#pragma once

#include <cstdint>
#include <cstddef>

namespace bitperfect {
namespace usb {

// USB Audio Class specification version
enum class UacVersion : uint8_t {
    UAC1 = 0x01,
    UAC2 = 0x02,
    UAC3 = 0x03
};

// USB descriptor types (USB spec Table 9-5)
enum class DescriptorType : uint8_t {
    DEVICE = 0x01,
    CONFIGURATION = 0x02,
    STRING = 0x03,
    INTERFACE = 0x04,
    ENDPOINT = 0x05,
    DEVICE_QUALIFIER = 0x06,
    INTERFACE_ASSOCIATION = 0x0B,
    CS_INTERFACE = 0x24,
    CS_ENDPOINT = 0x25
};

// Audio Interface Subclass codes (Audio Device Class spec)
enum class AudioInterfaceSubclass : uint8_t {
    UNDEFINED = 0x00,
    AUDIO_CONTROL = 0x01,
    AUDIO_STREAMING = 0x02,
    MIDI_STREAMING = 0x03
};

// Audio Class-Specific AC Interface Descriptor Subtypes (UAC1)
enum class AcDescriptorSubtype : uint8_t {
    AC_DESCRIPTOR_UNDEFINED = 0x00,
    HEADER = 0x01,
    INPUT_TERMINAL = 0x02,
    OUTPUT_TERMINAL = 0x03,
    MIXER_UNIT = 0x04,
    SELECTOR_UNIT = 0x05,
    FEATURE_UNIT = 0x06,
    PROCESSING_UNIT = 0x07,
    EXTENSION_UNIT = 0x08,
    // UAC2 additions
    EFFECT_UNIT = 0x07,
    CLOCK_SOURCE = 0x0A,
    CLOCK_SELECTOR = 0x0B,
    CLOCK_MULTIPLIER = 0x0C,
    SAMPLE_RATE_CONVERTER = 0x0D
};

// Audio Class-Specific AS Interface Descriptor Subtypes
enum class AsDescriptorSubtype : uint8_t {
    AS_DESCRIPTOR_UNDEFINED = 0x00,
    AS_GENERAL = 0x01,
    FORMAT_TYPE = 0x02,
    ENCODER = 0x03,
    DECODER = 0x04
};

// Audio Class-Specific Endpoint Descriptor Subtypes
enum class EpDescriptorSubtype : uint8_t {
    EP_DESCRIPTOR_UNDEFINED = 0x00,
    EP_GENERAL = 0x01
};

// Audio Data Format Type I codes
enum class AudioFormatType : uint8_t {
    FORMAT_TYPE_UNDEFINED = 0x00,
    FORMAT_TYPE_I = 0x01,
    FORMAT_TYPE_II = 0x02,
    FORMAT_TYPE_III = 0x03,
    FORMAT_TYPE_IV = 0x04
};

// PCM Format Tags (UAC1 wFormatTag)
enum class FormatTag : uint16_t {
    PCM = 0x0001,
    PCM8 = 0x0002,
    IEEE_FLOAT = 0x0003,
    ALAW = 0x0004,
    MULAW = 0x0005,
    TYPE_I_RAW_DATA = 0x8000
};

// UAC2 Audio Data Format Type I Bit Allocations (bmFormats)
enum class Uac2FormatBit : uint32_t {
    PCM = (1u << 0),
    PCM8 = (1u << 1),
    IEEE_FLOAT = (1u << 2),
    ALAW = (1u << 3),
    MULAW = (1u << 4),
    TYPE_I_RAW_DATA = (1u << 31)
};

// Terminal Types
enum class TerminalType : uint16_t {
    USB_UNDEFINED = 0x0100,
    USB_STREAMING = 0x0101,
    USB_VENDOR_SPECIFIC = 0x01FF,
    INPUT_UNDEFINED = 0x0200,
    MICROPHONE = 0x0201,
    OUTPUT_UNDEFINED = 0x0300,
    SPEAKER = 0x0301,
    HEADPHONES = 0x0302,
    SPDIF = 0x0605,
    DIGITAL_AUDIO_INTERFACE = 0x0601
};

// USB Audio Class request codes
enum class AudioRequest : uint8_t {
    SET_CUR = 0x01,
    GET_CUR = 0x81,
    SET_MIN = 0x02,
    GET_MIN = 0x82,
    SET_MAX = 0x03,
    GET_MAX = 0x83,
    SET_RES = 0x04,
    GET_RES = 0x84,
    SET_MEM = 0x05,
    GET_MEM = 0x85,
    // UAC2 uses CUR and RANGE
    RC_UNDEFINED = 0x00,
    CUR = 0x01,
    RANGE = 0x02
};

// UAC2 Clock Source attributes
enum class ClockAttributes : uint8_t {
    EXTERNAL_CLOCK = 0x00,
    INTERNAL_FIXED = 0x01,
    INTERNAL_VARIABLE = 0x02,
    INTERNAL_PROGRAMMABLE = 0x03
};

// Endpoint synchronization types
enum class SyncType : uint8_t {
    NONE = 0x00,
    ASYNC = 0x01,
    ADAPTIVE = 0x02,
    SYNC = 0x03
};

// PCM sample format
enum class PcmFormat : uint8_t {
    S16_LE = 0,    // 16-bit signed little-endian
    S24_3LE = 1,   // 24-bit packed (3 bytes)
    S24_LE = 2,    // 24-bit in 32-bit container
    S32_LE = 3,    // 32-bit signed little-endian
    FLOAT_LE = 4   // 32-bit IEEE float
};

// Common sample rates
constexpr uint32_t SAMPLE_RATE_44100 = 44100;
constexpr uint32_t SAMPLE_RATE_48000 = 48000;
constexpr uint32_t SAMPLE_RATE_88200 = 88200;
constexpr uint32_t SAMPLE_RATE_96000 = 96000;
constexpr uint32_t SAMPLE_RATE_176400 = 176400;
constexpr uint32_t SAMPLE_RATE_192000 = 192000;
constexpr uint32_t SAMPLE_RATE_352800 = 352800;
constexpr uint32_t SAMPLE_RATE_384000 = 384000;
constexpr uint32_t SAMPLE_RATE_705600 = 705600;
constexpr uint32_t SAMPLE_RATE_768000 = 768000;

// Maximum values
constexpr size_t MAX_CHANNELS = 32;
constexpr size_t MAX_SAMPLE_RATES = 64;
constexpr size_t MAX_INTERFACES = 16;
constexpr size_t MAX_ALT_SETTINGS = 16;
constexpr size_t MAX_ENDPOINTS = 8;

// USB Audio Class endpoint attributes
constexpr uint8_t EP_ATTR_SAMPLING_FREQ_CONTROL = 0x01;
constexpr uint8_t EP_ATTR_PITCH_CONTROL = 0x02;
constexpr uint8_t EP_ATTR_MAX_PACKETS_ONLY = 0x80;

// Isochronous transfer constants
constexpr size_t ISO_PACKETS_PER_URB = 8;
constexpr size_t DEFAULT_URB_COUNT = 4;
constexpr size_t MAX_PACKET_SIZE_HS = 1024;  // High-speed max
constexpr size_t MAX_PACKET_SIZE_SS = 1024 * 48;  // SuperSpeed max

} // namespace usb
} // namespace bitperfect
