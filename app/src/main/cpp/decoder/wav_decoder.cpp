#include "wav_decoder.h"
#include <cstring>
#include <algorithm>

namespace bitperfect {
namespace decoder {

WavDecoder::~WavDecoder() {
    close();
}

bool WavDecoder::open(const std::string& path) {
    close();

    file_ = fopen(path.c_str(), "rb");
    if (!file_) {
        return false;
    }

    // Read enough header data to parse (up to 4KB for headers with LIST chunks)
    uint8_t headerBuf[4096];
    size_t bytesRead = fread(headerBuf, 1, sizeof(headerBuf), file_);
    if (bytesRead < 44) {
        close();
        return false;
    }

    if (!parseHeader(headerBuf, bytesRead)) {
        close();
        return false;
    }

    // Seek to data start
    fseek(file_, static_cast<long>(header_.dataOffset), SEEK_SET);
    currentFrame_ = 0;
    memMode_ = false;

    return true;
}

bool WavDecoder::openFromMemory(const uint8_t* data, size_t size) {
    close();

    if (!data || size < 44) {
        return false;
    }

    if (!parseHeader(data, size)) {
        return false;
    }

    memData_ = data;
    memSize_ = size;
    memOffset_ = header_.dataOffset;
    memMode_ = true;
    currentFrame_ = 0;

    return true;
}

bool WavDecoder::parseHeader(const uint8_t* data, size_t size) {
    if (size < 44) return false;

    // Check RIFF header
    uint32_t riffId = readU32LE(data);
    if (riffId != kRiffId) return false;

    // uint32_t fileSize = readU32LE(data + 4);  // Not strictly needed

    uint32_t waveId = readU32LE(data + 8);
    if (waveId != kWaveId) return false;

    // Parse chunks
    size_t offset = 12;
    bool foundFmt = false;
    bool foundData = false;

    while (offset + 8 <= size) {
        uint32_t chunkId = readU32LE(data + offset);
        uint32_t chunkSize = readU32LE(data + offset + 4);

        if (chunkId == kFmtId) {
            if (offset + 8 + chunkSize > size) break;
            if (chunkSize < 16) return false;

            const uint8_t* fmt = data + offset + 8;
            header_.audioFormat = readU16LE(fmt);
            header_.numChannels = readU16LE(fmt + 2);
            header_.sampleRate = readU32LE(fmt + 4);
            header_.byteRate = readU32LE(fmt + 8);
            header_.blockAlign = readU16LE(fmt + 12);
            header_.bitsPerSample = readU16LE(fmt + 14);

            // Handle WAVE_FORMAT_EXTENSIBLE
            if (header_.audioFormat == kFormatExtensible && chunkSize >= 40) {
                // Valid bits per sample at offset 18 (cbSize) + 2 (validBitsPerSample)
                // SubFormat GUID starts at offset 24
                uint16_t validBits = readU16LE(fmt + 18);
                if (validBits > 0 && validBits <= header_.bitsPerSample) {
                    header_.bitsPerSample = validBits;
                }
                // Check SubFormat - first two bytes should be 0x0001 for PCM
                uint16_t subFormat = readU16LE(fmt + 24);
                if (subFormat != kFormatPcm) {
                    return false;  // Not PCM
                }
                header_.audioFormat = kFormatPcm;
            }

            // Only support PCM
            if (header_.audioFormat != kFormatPcm) {
                return false;
            }

            // Validate bit depth
            if (header_.bitsPerSample != 16 &&
                header_.bitsPerSample != 24 &&
                header_.bitsPerSample != 32) {
                return false;
            }

            foundFmt = true;
        } else if (chunkId == kDataId) {
            header_.dataOffset = static_cast<uint32_t>(offset + 8);
            header_.dataSize = chunkSize;
            foundData = true;
            break;  // Data chunk found - stop parsing
        }
        // Skip other chunks (LIST, INFO, fact, etc.)

        offset += 8 + chunkSize;
        // Chunks are word-aligned (pad byte if odd size)
        if (chunkSize & 1) offset++;
    }

    if (!foundFmt || !foundData) {
        return false;
    }

    // Validate
    if (header_.numChannels == 0 || header_.numChannels > 8) return false;
    if (header_.sampleRate == 0 || header_.sampleRate > 768000) return false;

    // Populate format
    format_.sampleRate = header_.sampleRate;
    format_.bitsPerSample = static_cast<uint8_t>(header_.bitsPerSample);
    format_.channels = static_cast<uint8_t>(header_.numChannels);

    uint32_t bytesPerFrame = (header_.bitsPerSample / 8) * header_.numChannels;
    if (bytesPerFrame > 0) {
        format_.totalFrames = header_.dataSize / bytesPerFrame;
    }

    return true;
}

size_t WavDecoder::read(uint8_t* buffer, size_t frames) {
    if (memMode_) {
        return readFromMemory(buffer, frames);
    }

    if (!file_ || frames == 0) return 0;

    uint32_t bytesPerFrame = format_.bytesPerFrame();
    if (bytesPerFrame == 0) return 0;

    // Clamp to remaining frames
    uint64_t remaining = format_.totalFrames - currentFrame_;
    size_t framesToRead = std::min(frames, static_cast<size_t>(remaining));
    if (framesToRead == 0) return 0;

    size_t bytesToRead = framesToRead * bytesPerFrame;
    size_t bytesRead = fread(buffer, 1, bytesToRead, file_);
    size_t framesRead = bytesRead / bytesPerFrame;

    currentFrame_ += framesRead;
    return framesRead;
}

size_t WavDecoder::readFromMemory(uint8_t* buffer, size_t frames) {
    if (!memMode_ || !memData_ || frames == 0) return 0;

    uint32_t bytesPerFrame = format_.bytesPerFrame();
    if (bytesPerFrame == 0) return 0;

    // Calculate available data
    size_t dataEnd = header_.dataOffset + header_.dataSize;
    if (memOffset_ >= dataEnd || memOffset_ >= memSize_) return 0;

    size_t availableBytes = std::min(dataEnd, memSize_) - memOffset_;
    size_t maxFrames = availableBytes / bytesPerFrame;
    size_t framesToRead = std::min(frames, maxFrames);
    if (framesToRead == 0) return 0;

    size_t bytesToCopy = framesToRead * bytesPerFrame;
    memcpy(buffer, memData_ + memOffset_, bytesToCopy);
    memOffset_ += bytesToCopy;
    currentFrame_ += framesToRead;

    return framesToRead;
}

bool WavDecoder::seek(const SeekPosition& position) {
    if (!isOpen()) return false;

    uint64_t targetFrame = std::min(position.frameIndex, format_.totalFrames);
    uint32_t bytesPerFrame = format_.bytesPerFrame();

    if (memMode_) {
        memOffset_ = header_.dataOffset + static_cast<size_t>(targetFrame * bytesPerFrame);
        currentFrame_ = targetFrame;
        return true;
    }

    if (file_) {
        long offset = static_cast<long>(header_.dataOffset + targetFrame * bytesPerFrame);
        if (fseek(file_, offset, SEEK_SET) == 0) {
            currentFrame_ = targetFrame;
            return true;
        }
    }

    return false;
}

void WavDecoder::close() {
    if (file_) {
        fclose(file_);
        file_ = nullptr;
    }
    memData_ = nullptr;
    memSize_ = 0;
    memOffset_ = 0;
    memMode_ = false;
    currentFrame_ = 0;
    format_ = AudioFormat{};
    header_ = WavHeader{};
}

} // namespace decoder
} // namespace bitperfect
