#include "dsf_parser.h"

namespace bitperfect {
namespace dsd {

bool DsfParser::parse(const uint8_t* data, size_t length) {
    fileInfo_ = DsfFileInfo{};

    if (!data || length < 28) {
        fileInfo_.errorMessage = "Data too short for DSD chunk";
        return false;
    }

    // Parse DSD chunk (must be first)
    if (!parseDsdChunk(data, length)) {
        return false;
    }

    // Parse format chunk (follows DSD chunk at offset 28)
    size_t fmtOffset = 28;
    if (length < fmtOffset + 52) {
        fileInfo_.errorMessage = "Data too short for format chunk";
        return false;
    }
    if (!parseFmtChunk(data + fmtOffset, length - fmtOffset)) {
        return false;
    }

    // Parse data chunk header (follows format chunk)
    size_t dataChunkOffset = fmtOffset + 52;
    if (length < dataChunkOffset + 12) {
        fileInfo_.errorMessage = "Data too short for data chunk header";
        return false;
    }
    if (!parseDataChunkHeader(data + dataChunkOffset, length - dataChunkOffset)) {
        return false;
    }

    fileInfo_.dataOffset = dataChunkOffset + 12; // 12 bytes for data chunk header
    fileInfo_.isValid = true;
    return true;
}

bool DsfParser::parseDsdChunk(const uint8_t* data, size_t length) {
    if (length < 28) {
        fileInfo_.errorMessage = "DSD chunk too short";
        return false;
    }

    // Check "DSD " signature
    uint32_t chunkId = readU32LE(data);
    if (chunkId != DSF_DSD_CHUNK_ID) {
        fileInfo_.errorMessage = "Not a DSF file (missing DSD chunk signature)";
        return false;
    }

    // DSD chunk size should be 28
    uint64_t chunkSize = readU64LE(data + 4);
    if (chunkSize != 28) {
        fileInfo_.errorMessage = "Invalid DSD chunk size";
        return false;
    }

    fileInfo_.totalFileSize = readU64LE(data + 12);
    fileInfo_.metadataOffset = readU64LE(data + 20);

    return true;
}

bool DsfParser::parseFmtChunk(const uint8_t* data, size_t length) {
    if (length < 52) {
        fileInfo_.errorMessage = "Format chunk too short";
        return false;
    }

    // Check "fmt " signature
    uint32_t chunkId = readU32LE(data);
    if (chunkId != DSF_FMT_CHUNK_ID) {
        fileInfo_.errorMessage = "Missing format chunk signature";
        return false;
    }

    // Format chunk size should be 52
    uint64_t chunkSize = readU64LE(data + 4);
    if (chunkSize != 52) {
        fileInfo_.errorMessage = "Invalid format chunk size";
        return false;
    }

    fileInfo_.formatVersion = readU32LE(data + 12);
    fileInfo_.formatId = readU32LE(data + 16);
    fileInfo_.channelType = static_cast<DsfChannelType>(readU32LE(data + 20));
    fileInfo_.channelCount = readU32LE(data + 24);
    fileInfo_.sampleRate = readU32LE(data + 28);
    fileInfo_.bitsPerSample = readU32LE(data + 32);
    fileInfo_.sampleCount = readU64LE(data + 36);
    fileInfo_.blockSizePerChannel = readU32LE(data + 44);

    // Validate
    if (fileInfo_.formatVersion != DSF_FORMAT_VERSION) {
        fileInfo_.errorMessage = "Unsupported DSF format version";
        return false;
    }

    if (fileInfo_.formatId != DSF_FORMAT_DSD_RAW) {
        fileInfo_.errorMessage = "Unsupported format ID (expected DSD raw)";
        return false;
    }

    if (fileInfo_.channelCount == 0 || fileInfo_.channelCount > 6) {
        fileInfo_.errorMessage = "Invalid channel count";
        return false;
    }

    if (!isStandardDsdRate(fileInfo_.sampleRate)) {
        fileInfo_.errorMessage = "Unsupported DSD sample rate";
        return false;
    }

    if (fileInfo_.bitsPerSample != 1) {
        fileInfo_.errorMessage = "Invalid bits per sample (expected 1 for DSD)";
        return false;
    }

    if (fileInfo_.blockSizePerChannel == 0) {
        fileInfo_.errorMessage = "Invalid block size per channel";
        return false;
    }

    return true;
}

bool DsfParser::parseDataChunkHeader(const uint8_t* data, size_t length) {
    if (length < 12) {
        fileInfo_.errorMessage = "Data chunk header too short";
        return false;
    }

    uint32_t chunkId = readU32LE(data);
    if (chunkId != DSF_DATA_CHUNK_ID) {
        fileInfo_.errorMessage = "Missing data chunk signature";
        return false;
    }

    fileInfo_.dataChunkSize = readU64LE(data + 4);
    return true;
}

bool DsfParser::extractChannelData(const uint8_t* rawData, size_t rawLength,
                                    std::vector<std::vector<uint8_t>>& channelData) const {
    if (!fileInfo_.isValid || !rawData || rawLength == 0) {
        return false;
    }

    uint32_t channels = fileInfo_.channelCount;
    uint32_t blockSize = fileInfo_.blockSizePerChannel;

    if (channels == 0 || blockSize == 0) {
        return false;
    }

    // Calculate number of full interleaved blocks
    size_t interleavedBlockSize = static_cast<size_t>(blockSize) * channels;
    size_t numFullBlocks = rawLength / interleavedBlockSize;

    // Total DSD bytes per channel expected
    uint64_t expectedBytesPerChannel = getDsdBytesPerChannel();

    channelData.resize(channels);
    for (uint32_t ch = 0; ch < channels; ++ch) {
        channelData[ch].reserve(static_cast<size_t>(expectedBytesPerChannel));
    }

    // DSF interleaving: [block_ch0][block_ch1]...[block_chN] repeating
    for (size_t block = 0; block < numFullBlocks; ++block) {
        size_t blockStart = block * interleavedBlockSize;
        for (uint32_t ch = 0; ch < channels; ++ch) {
            size_t channelBlockStart = blockStart + static_cast<size_t>(ch) * blockSize;

            // Determine how many bytes to copy (handle last block padding)
            size_t bytesToCopy = blockSize;
            size_t currentChannelBytes = channelData[ch].size();
            if (currentChannelBytes + bytesToCopy > expectedBytesPerChannel) {
                bytesToCopy = static_cast<size_t>(expectedBytesPerChannel - currentChannelBytes);
            }

            if (bytesToCopy > 0) {
                channelData[ch].insert(channelData[ch].end(),
                                       rawData + channelBlockStart,
                                       rawData + channelBlockStart + bytesToCopy);
            }
        }
    }

    // Handle partial last block if any data remains
    size_t processedBytes = numFullBlocks * interleavedBlockSize;
    if (processedBytes < rawLength) {
        size_t remaining = rawLength - processedBytes;
        for (uint32_t ch = 0; ch < channels && remaining > 0; ++ch) {
            size_t bytesToCopy = (remaining >= blockSize) ? blockSize : remaining;
            size_t currentChannelBytes = channelData[ch].size();
            if (currentChannelBytes + bytesToCopy > expectedBytesPerChannel) {
                bytesToCopy = static_cast<size_t>(expectedBytesPerChannel - currentChannelBytes);
            }
            if (bytesToCopy > 0) {
                channelData[ch].insert(channelData[ch].end(),
                                       rawData + processedBytes,
                                       rawData + processedBytes + bytesToCopy);
            }
            processedBytes += blockSize;
            remaining = (remaining > blockSize) ? remaining - blockSize : 0;
        }
    }

    return true;
}

bool DsfParser::isStandardDsdRate(uint32_t rate) {
    return rate == DSD64_RATE || rate == DSD128_RATE || rate == DSD256_RATE;
}

uint32_t DsfParser::getDsdMultiplier(uint32_t rate) {
    if (rate == DSD64_RATE) return 64;
    if (rate == DSD128_RATE) return 128;
    if (rate == DSD256_RATE) return 256;
    return 0;
}

} // namespace dsd
} // namespace bitperfect
