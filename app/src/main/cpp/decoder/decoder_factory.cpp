#include "decoder_factory.h"
#include "wav_decoder.h"
#include "flac_decoder.h"
#include <algorithm>
#include <cctype>

namespace bitperfect {
namespace decoder {

std::unique_ptr<AudioDecoder> DecoderFactory::createFromPath(const std::string& path) {
    DecoderType type = detectFromExtension(path);
    if (type == DecoderType::UNKNOWN) {
        return nullptr;
    }
    return create(type);
}

std::unique_ptr<AudioDecoder> DecoderFactory::createFromMagic(const uint8_t* data, size_t size) {
    DecoderType type = detectFromMagic(data, size);
    if (type == DecoderType::UNKNOWN) {
        return nullptr;
    }
    return create(type);
}

std::unique_ptr<AudioDecoder> DecoderFactory::create(DecoderType type) {
    switch (type) {
        case DecoderType::WAV:
            return std::make_unique<WavDecoder>();
        case DecoderType::FLAC:
            return std::make_unique<FlacDecoder>();
        // Future decoder implementations
        case DecoderType::DSF:
        case DecoderType::DFF:
        case DecoderType::AIFF:
        case DecoderType::ALAC:
        case DecoderType::APE:
        case DecoderType::MP3:
        case DecoderType::AAC:
        case DecoderType::OGG:
        case DecoderType::OPUS:
        case DecoderType::UNKNOWN:
        default:
            return nullptr;
    }
}

DecoderType DecoderFactory::detectFromExtension(const std::string& path) {
    // Find the last dot
    size_t dotPos = path.rfind('.');
    if (dotPos == std::string::npos || dotPos == path.length() - 1) {
        return DecoderType::UNKNOWN;
    }

    // Extract extension and convert to lowercase
    std::string ext = path.substr(dotPos + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(),
                   [](unsigned char c) { return std::tolower(c); });

    if (ext == "wav" || ext == "wave") return DecoderType::WAV;
    if (ext == "flac") return DecoderType::FLAC;
    if (ext == "dsf") return DecoderType::DSF;
    if (ext == "dff") return DecoderType::DFF;
    if (ext == "aiff" || ext == "aif") return DecoderType::AIFF;
    if (ext == "alac" || ext == "m4a") return DecoderType::ALAC;
    if (ext == "ape") return DecoderType::APE;
    if (ext == "mp3") return DecoderType::MP3;
    if (ext == "aac") return DecoderType::AAC;
    if (ext == "ogg" || ext == "oga") return DecoderType::OGG;
    if (ext == "opus") return DecoderType::OPUS;

    return DecoderType::UNKNOWN;
}

DecoderType DecoderFactory::detectFromMagic(const uint8_t* data, size_t size) {
    if (!data || size < 4) {
        return DecoderType::UNKNOWN;
    }

    // RIFF/WAVE: "RIFF" followed by size, then "WAVE"
    if (size >= 12 &&
        data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' &&
        data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E') {
        return DecoderType::WAV;
    }

    // FLAC: "fLaC"
    if (data[0] == 'f' && data[1] == 'L' && data[2] == 'a' && data[3] == 'C') {
        return DecoderType::FLAC;
    }

    // DSF: "DSD " (DSF file marker)
    if (data[0] == 'D' && data[1] == 'S' && data[2] == 'D' && data[3] == ' ') {
        return DecoderType::DSF;
    }

    // DFF/DSDIFF: "FRM8"
    if (data[0] == 'F' && data[1] == 'R' && data[2] == 'M' && data[3] == '8') {
        return DecoderType::DFF;
    }

    // AIFF: "FORM" + "AIFF"/"AIFC"
    if (size >= 12 &&
        data[0] == 'F' && data[1] == 'O' && data[2] == 'R' && data[3] == 'M' &&
        data[8] == 'A' && data[9] == 'I' && data[10] == 'F') {
        return DecoderType::AIFF;
    }

    return DecoderType::UNKNOWN;
}

bool DecoderFactory::isSupported(DecoderType type) {
    switch (type) {
        case DecoderType::WAV:
        case DecoderType::FLAC:
            return true;
        default:
            return false;
    }
}

const char* DecoderFactory::getExtension(DecoderType type) {
    switch (type) {
        case DecoderType::WAV: return ".wav";
        case DecoderType::FLAC: return ".flac";
        case DecoderType::DSF: return ".dsf";
        case DecoderType::DFF: return ".dff";
        case DecoderType::AIFF: return ".aiff";
        case DecoderType::ALAC: return ".m4a";
        case DecoderType::APE: return ".ape";
        case DecoderType::MP3: return ".mp3";
        case DecoderType::AAC: return ".aac";
        case DecoderType::OGG: return ".ogg";
        case DecoderType::OPUS: return ".opus";
        default: return "";
    }
}

const char* DecoderFactory::getTypeName(DecoderType type) {
    switch (type) {
        case DecoderType::WAV: return "WAV";
        case DecoderType::FLAC: return "FLAC";
        case DecoderType::DSF: return "DSF";
        case DecoderType::DFF: return "DFF";
        case DecoderType::AIFF: return "AIFF";
        case DecoderType::ALAC: return "ALAC";
        case DecoderType::APE: return "APE";
        case DecoderType::MP3: return "MP3";
        case DecoderType::AAC: return "AAC";
        case DecoderType::OGG: return "OGG";
        case DecoderType::OPUS: return "Opus";
        default: return "Unknown";
    }
}

} // namespace decoder
} // namespace bitperfect
