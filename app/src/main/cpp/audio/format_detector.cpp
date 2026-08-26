#include "format_detector.h"
#include "../dsd/dsf_parser.h"
#include <algorithm>
#include <cctype>

namespace bitperfect {
namespace audio {

FormatInfo FormatDetector::detect(const uint8_t* data, size_t length) const {
    FormatInfo info;

    if (!data || length < 12) {
        info.errorMessage = "Insufficient data for format detection";
        return info;
    }

    // Check WAV: "RIFF" + "WAVE"
    if (length >= 12 &&
        data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' &&
        data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E') {
        return detectWav(data, length);
    }

    // Check FLAC: "fLaC"
    if (length >= 4 &&
        data[0] == 'f' && data[1] == 'L' && data[2] == 'a' && data[3] == 'C') {
        return detectFlac(data, length);
    }

    // Check DSF: "DSD "
    if (length >= 4 &&
        data[0] == 'D' && data[1] == 'S' && data[2] == 'D' && data[3] == ' ') {
        return detectDsf(data, length);
    }

    info.errorMessage = "Unrecognized file format";
    return info;
}

AudioFileType FormatDetector::detectFromExtension(const std::string& filename) {
    // Find last dot
    size_t dotPos = filename.rfind('.');
    if (dotPos == std::string::npos || dotPos == filename.size() - 1) {
        return AudioFileType::UNKNOWN;
    }

    std::string ext = filename.substr(dotPos + 1);
    // Convert to lowercase
    std::transform(ext.begin(), ext.end(), ext.begin(),
                   [](unsigned char c) { return std::tolower(c); });

    if (ext == "wav") return AudioFileType::WAV;
    if (ext == "flac") return AudioFileType::FLAC;
    if (ext == "dsf") return AudioFileType::DSF;
    if (ext == "dff") return AudioFileType::DFF;
    if (ext == "aiff" || ext == "aif") return AudioFileType::AIFF;

    return AudioFileType::UNKNOWN;
}

bool FormatDetector::isDsdFormat(AudioFileType type) {
    return type == AudioFileType::DSF || type == AudioFileType::DFF;
}

bool FormatDetector::isPcmFormat(AudioFileType type) {
    return type == AudioFileType::WAV || type == AudioFileType::FLAC ||
           type == AudioFileType::AIFF;
}

FormatInfo FormatDetector::detectWav(const uint8_t* data, size_t length) const {
    FormatInfo info;
    info.fileType = AudioFileType::WAV;
    info.contentType = AudioContentType::PCM;

    // Need at least enough for RIFF header + fmt chunk
    if (length < 44) {
        info.errorMessage = "WAV header too short";
        return info;
    }

    // Find fmt chunk
    size_t pos = 12; // After RIFF header
    while (pos + 8 <= length) {
        uint32_t chunkId = readU32LE(data + pos);
        uint32_t chunkSize = readU32LE(data + pos + 4);

        if (chunkId == 0x20746D66) { // "fmt "
            if (pos + 8 + 16 > length) {
                info.errorMessage = "fmt chunk too short";
                return info;
            }

            uint16_t audioFormat = readU16LE(data + pos + 8);
            info.channels = readU16LE(data + pos + 10);
            info.sampleRate = readU32LE(data + pos + 12);
            info.bitDepth = static_cast<uint8_t>(readU16LE(data + pos + 22));

            // Format 1 = PCM, 3 = IEEE float, 0xFFFE = extensible
            if (audioFormat == 1 || audioFormat == 3 || audioFormat == 0xFFFE) {
                info.isValid = true;
            } else {
                info.errorMessage = "Unsupported WAV format code";
            }
            return info;
        }

        pos += 8 + chunkSize;
        if (chunkSize % 2 != 0) pos++; // Word alignment
    }

    info.errorMessage = "fmt chunk not found";
    return info;
}

FormatInfo FormatDetector::detectFlac(const uint8_t* data, size_t length) const {
    FormatInfo info;
    info.fileType = AudioFileType::FLAC;
    info.contentType = AudioContentType::PCM;

    // FLAC STREAMINFO is mandatory first metadata block
    // Offset 4: metadata block header (1 byte type + 3 bytes length)
    if (length < 42) { // 4 (magic) + 4 (block header) + 34 (STREAMINFO)
        info.errorMessage = "FLAC header too short";
        return info;
    }

    // Check STREAMINFO block (type 0)
    uint8_t blockType = data[4] & 0x7F;
    if (blockType != 0) {
        info.errorMessage = "First block is not STREAMINFO";
        return info;
    }

    // STREAMINFO starts at offset 8
    const uint8_t* si = data + 8;

    // Bytes 10-13 of STREAMINFO: sample rate (20 bits), channels (3 bits), bits per sample (5 bits)
    // At offset 10 in STREAMINFO:
    // [rate: 20 bits][channels-1: 3 bits][bps-1: 5 bits][total samples: 36 bits]
    uint32_t word = readU32BE(si + 10);
    info.sampleRate = (word >> 12) & 0xFFFFF;
    info.channels = ((word >> 9) & 0x07) + 1;
    info.bitDepth = static_cast<uint8_t>(((word >> 4) & 0x1F) + 1);

    // Total samples (36 bits): lower 4 bits of word + next 32 bits
    uint64_t totalSamples = static_cast<uint64_t>(word & 0x0F) << 32;
    if (length >= 42) {
        totalSamples |= readU32BE(si + 14);
    }
    info.totalSamples = totalSamples;

    if (info.sampleRate > 0 && info.channels > 0 && info.bitDepth > 0) {
        info.isValid = true;
    } else {
        info.errorMessage = "Invalid FLAC STREAMINFO values";
    }

    return info;
}

FormatInfo FormatDetector::detectDsf(const uint8_t* data, size_t length) const {
    FormatInfo info;
    info.fileType = AudioFileType::DSF;
    info.contentType = AudioContentType::DSD;
    info.bitDepth = 1; // DSD is always 1-bit

    dsd::DsfParser parser;
    if (parser.parse(data, length)) {
        info.sampleRate = parser.getSampleRate();
        info.channels = parser.getChannelCount();
        info.totalSamples = parser.getFileInfo().sampleCount;
        info.isValid = true;
    } else {
        info.errorMessage = parser.getFileInfo().errorMessage;
    }

    return info;
}

} // namespace audio
} // namespace bitperfect
