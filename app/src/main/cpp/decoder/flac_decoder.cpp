#include "flac_decoder.h"
#include <cstring>
#include <algorithm>
#include <climits>

namespace bitperfect {
namespace decoder {

// ========================================================================
// Bit reader for FLAC frame/subframe parsing
// ========================================================================

class FlacBitReader {
public:
    FlacBitReader(const uint8_t* data, size_t size)
        : data_(data), size_(size), bytePos_(0), bitPos_(0) {}

    bool hasRemaining(size_t bits) const {
        size_t totalBitsRead = bytePos_ * 8 + bitPos_;
        size_t totalBits = size_ * 8;
        return (totalBitsRead + bits) <= totalBits;
    }

    uint32_t readBits(uint8_t n) {
        if (n == 0) return 0;
        uint32_t result = 0;
        for (uint8_t i = 0; i < n; ++i) {
            if (bytePos_ >= size_) return result;
            result <<= 1;
            result |= (data_[bytePos_] >> (7 - bitPos_)) & 1;
            bitPos_++;
            if (bitPos_ == 8) {
                bitPos_ = 0;
                bytePos_++;
            }
        }
        return result;
    }

    int32_t readSignedBits(uint8_t n) {
        uint32_t val = readBits(n);
        // Sign extend
        if (n > 0 && (val & (1u << (n - 1)))) {
            val |= ~((1u << n) - 1);
        }
        return static_cast<int32_t>(val);
    }

    // Read unary (count of leading zeros before a 1-bit)
    uint32_t readUnary() {
        uint32_t count = 0;
        while (bytePos_ < size_) {
            uint8_t bit = (data_[bytePos_] >> (7 - bitPos_)) & 1;
            bitPos_++;
            if (bitPos_ == 8) {
                bitPos_ = 0;
                bytePos_++;
            }
            if (bit) return count;
            count++;
            if (count > 100000) return count; // Safety limit
        }
        return count;
    }

    // Read a UTF-8 coded value (for frame/sample number)
    uint64_t readUtf8() {
        uint8_t first = static_cast<uint8_t>(readBits(8));
        uint64_t val;
        int extra;

        if ((first & 0x80) == 0) {
            val = first;
            extra = 0;
        } else if ((first & 0xE0) == 0xC0) {
            val = first & 0x1F;
            extra = 1;
        } else if ((first & 0xF0) == 0xE0) {
            val = first & 0x0F;
            extra = 2;
        } else if ((first & 0xF8) == 0xF0) {
            val = first & 0x07;
            extra = 3;
        } else if ((first & 0xFC) == 0xF8) {
            val = first & 0x03;
            extra = 4;
        } else if ((first & 0xFE) == 0xFC) {
            val = first & 0x01;
            extra = 5;
        } else {
            val = first;
            extra = 0;
        }

        for (int i = 0; i < extra; i++) {
            uint8_t b = static_cast<uint8_t>(readBits(8));
            val = (val << 6) | (b & 0x3F);
        }
        return val;
    }

    void alignToByte() {
        if (bitPos_ != 0) {
            bitPos_ = 0;
            bytePos_++;
        }
    }

    size_t getBytePosition() const { return bytePos_; }
    uint8_t getBitPosition() const { return bitPos_; }
    size_t getBitOffset() const { return bytePos_ * 8 + bitPos_; }

private:
    const uint8_t* data_;
    size_t size_;
    size_t bytePos_;
    uint8_t bitPos_;
};

// ========================================================================
// FlacDecoder implementation
// ========================================================================

FlacDecoder::~FlacDecoder() {
    close();
}

bool FlacDecoder::open(const std::string& path) {
    close();

    file_ = fopen(path.c_str(), "rb");
    if (!file_) {
        return false;
    }

    // Read header to parse STREAMINFO (first 8KB should be enough for metadata)
    uint8_t headerBuf[8192];
    size_t bytesRead = fread(headerBuf, 1, sizeof(headerBuf), file_);
    if (bytesRead < 42) {  // Minimum: 4 (fLaC) + 4 (block header) + 34 (STREAMINFO)
        close();
        return false;
    }

    if (!parseMetadataBlocks(headerBuf, bytesRead)) {
        close();
        return false;
    }

    // Seek to audio data
    fseek(file_, static_cast<long>(audioDataOffset_), SEEK_SET);
    currentFrame_ = 0;
    memMode_ = false;

    return true;
}

bool FlacDecoder::openFromMemory(const uint8_t* data, size_t size) {
    close();

    if (!data || size < 42) {
        return false;
    }

    if (!parseMetadataBlocks(data, size)) {
        return false;
    }

    memData_ = data;
    memSize_ = size;
    memOffset_ = audioDataOffset_;
    memMode_ = true;
    currentFrame_ = 0;

    return true;
}

bool FlacDecoder::parseMetadataBlocks(const uint8_t* data, size_t size) {
    if (size < 4) return false;

    // Check "fLaC" marker
    if (data[0] != 'f' || data[1] != 'L' || data[2] != 'a' || data[3] != 'C') {
        return false;
    }

    size_t offset = 4;
    bool foundStreamInfo = false;
    bool lastBlock = false;

    while (!lastBlock && offset + 4 <= size) {
        uint8_t blockHeader = data[offset];
        lastBlock = (blockHeader & 0x80) != 0;
        uint8_t blockType = blockHeader & 0x7F;
        uint32_t blockLength = readU24BE(data + offset + 1);
        offset += 4;

        if (offset + blockLength > size) break;

        if (blockType == 0) {  // STREAMINFO
            if (blockLength >= 34) {
                if (!parseStreamInfo(data + offset, blockLength)) {
                    return false;
                }
                foundStreamInfo = true;
            }
        }
        // Skip other metadata blocks (PADDING, APPLICATION, SEEKTABLE, VORBIS_COMMENT, CUESHEET, PICTURE)

        offset += blockLength;
    }

    if (!foundStreamInfo) return false;

    audioDataOffset_ = offset;
    return true;
}

bool FlacDecoder::parseStreamInfo(const uint8_t* data, size_t size) {
    if (size < 34) return false;

    streamInfo_.minBlockSize = readU16BE(data);
    streamInfo_.maxBlockSize = readU16BE(data + 2);
    streamInfo_.minFrameSize = readU24BE(data + 4);
    streamInfo_.maxFrameSize = readU24BE(data + 7);

    // Bits 0-19: sample rate, bits 20-22: channels-1, bits 23-27: bps-1, bits 28-63: total samples
    // Packed in 8 bytes starting at offset 10
    uint32_t word1 = readU32BE(data + 10);
    uint32_t word2 = readU32BE(data + 14);

    streamInfo_.sampleRate = word1 >> 12;
    streamInfo_.channels = ((word1 >> 9) & 0x07) + 1;
    streamInfo_.bitsPerSample = ((word1 >> 4) & 0x1F) + 1;

    // Total samples is 36 bits: low 4 bits of word1 + all 32 bits of word2
    streamInfo_.totalSamples = (static_cast<uint64_t>(word1 & 0x0F) << 32) | word2;

    // MD5 signature
    memcpy(streamInfo_.md5, data + 18, 16);

    // Validate
    if (streamInfo_.sampleRate == 0 || streamInfo_.sampleRate > 655350) return false;
    if (streamInfo_.channels == 0 || streamInfo_.channels > 8) return false;
    if (streamInfo_.bitsPerSample < 4 || streamInfo_.bitsPerSample > 32) return false;

    // Populate format
    format_.sampleRate = streamInfo_.sampleRate;
    format_.bitsPerSample = streamInfo_.bitsPerSample;
    format_.channels = streamInfo_.channels;
    format_.totalFrames = streamInfo_.totalSamples;

    return true;
}

size_t FlacDecoder::read(uint8_t* buffer, size_t frames) {
    if (!isOpen() || frames == 0) return 0;

    uint64_t remaining = format_.totalFrames - currentFrame_;
    size_t framesToRead = std::min(frames, static_cast<size_t>(remaining));
    if (framesToRead == 0) return 0;

    size_t bytesPerFrame = format_.bytesPerFrame();
    size_t totalFramesDecoded = 0;
    uint8_t* outPtr = buffer;

    // If we have leftover decoded data in decodeBuffer_, use that first
    while (totalFramesDecoded < framesToRead && decodeBufferOffset_ < decodeBufferFrames_) {
        size_t availableFrames = decodeBufferFrames_ - decodeBufferOffset_;
        size_t needed = framesToRead - totalFramesDecoded;
        size_t toCopy = std::min(availableFrames, needed);
        size_t byteOffset = decodeBufferOffset_ * bytesPerFrame;
        memcpy(outPtr, decodeBuffer_.data() + byteOffset, toCopy * bytesPerFrame);
        outPtr += toCopy * bytesPerFrame;
        decodeBufferOffset_ += toCopy;
        totalFramesDecoded += toCopy;
    }

    // Decode more frames as needed
    while (totalFramesDecoded < framesToRead) {
        // Get raw frame data
        const uint8_t* frameData = nullptr;
        size_t available = 0;

        if (memMode_) {
            if (memOffset_ >= memSize_) break;
            frameData = memData_ + memOffset_;
            available = memSize_ - memOffset_;
        } else if (file_) {
            // Read up to maxFrameSize or a fixed chunk
            size_t chunkSize = streamInfo_.maxFrameSize;
            if (chunkSize == 0 || chunkSize > 65536) chunkSize = 65536;
            fileReadBuffer_.resize(chunkSize);
            size_t bytesRead = fread(fileReadBuffer_.data(), 1, chunkSize, file_);
            if (bytesRead == 0) break;
            frameData = fileReadBuffer_.data();
            available = bytesRead;
        } else {
            break;
        }

        // Attempt to decode a frame
        size_t consumed = decodeFrame(frameData, available, nullptr, 0);
        if (consumed == 0) {
            // Cannot decode; for safety, advance one byte (frame sync search)
            if (memMode_) {
                memOffset_++;
            } else if (file_) {
                // Seek back to one byte past where we started
                long seekBack = -static_cast<long>(available) + 1;
                fseek(file_, seekBack, SEEK_CUR);
            }
            break; // Give up on this read call
        }

        if (memMode_) {
            memOffset_ += consumed;
        } else if (file_) {
            // Seek back unused bytes
            long seekBack = -static_cast<long>(available - consumed);
            if (seekBack != 0) {
                fseek(file_, seekBack, SEEK_CUR);
            }
        }

        // Copy from decode buffer
        size_t needed = framesToRead - totalFramesDecoded;
        size_t toCopy = std::min(decodeBufferFrames_, needed);
        memcpy(outPtr, decodeBuffer_.data(), toCopy * bytesPerFrame);
        outPtr += toCopy * bytesPerFrame;
        totalFramesDecoded += toCopy;
        decodeBufferOffset_ = toCopy;
    }

    currentFrame_ += totalFramesDecoded;
    return totalFramesDecoded;
}

size_t FlacDecoder::decodeFrame(const uint8_t* frameData, size_t available,
                                 uint8_t* /*output*/, size_t /*maxFrames*/) {
    // FLAC frame structure:
    // - Frame header (variable length)
    // - Subframes (one per channel)
    // - Frame footer (CRC-16)

    if (available < 6) return 0;

    // Check sync code: 0xFFF8 or 0xFFF9 (14 bits = 0x3FFE, blocking strategy bit)
    if (frameData[0] != 0xFF || (frameData[1] & 0xFC) != 0xF8) {
        return 0;
    }

    FlacBitReader reader(frameData, available);

    // Read frame header
    reader.readBits(14); // Sync code (0x3FFE)
    reader.readBits(1);  // Reserved (must be 0)
    uint8_t blockingStrategy = static_cast<uint8_t>(reader.readBits(1)); // 0=fixed, 1=variable

    uint8_t blockSizeCode = static_cast<uint8_t>(reader.readBits(4));
    uint8_t sampleRateCode = static_cast<uint8_t>(reader.readBits(4));
    uint8_t channelAssignment = static_cast<uint8_t>(reader.readBits(4));
    uint8_t sampleSizeCode = static_cast<uint8_t>(reader.readBits(3));
    reader.readBits(1); // Reserved

    // UTF-8 coded frame/sample number
    reader.readUtf8();

    // Determine block size
    uint32_t blockSize = 0;
    switch (blockSizeCode) {
        case 0: return 0; // reserved
        case 1: blockSize = 192; break;
        case 2: blockSize = 576; break;
        case 3: blockSize = 1152; break;
        case 4: blockSize = 2304; break;
        case 5: blockSize = 4608; break;
        case 6: blockSize = reader.readBits(8) + 1; break;
        case 7: blockSize = reader.readBits(16) + 1; break;
        default: blockSize = 256u << (blockSizeCode - 8); break;
    }

    // Sample rate (we use STREAMINFO so just consume the bits)
    switch (sampleRateCode) {
        case 12: reader.readBits(8); break;
        case 13: reader.readBits(16); break;
        case 14: reader.readBits(16); break;
        default: break;
    }

    // Frame header CRC-8
    reader.alignToByte();
    reader.readBits(8);  // CRC-8

    // Determine bits per sample for this frame
    uint8_t bps = streamInfo_.bitsPerSample;
    switch (sampleSizeCode) {
        case 0: bps = streamInfo_.bitsPerSample; break;
        case 1: bps = 8; break;
        case 2: bps = 12; break;
        case 3: bps = 16; break;
        case 4: bps = 20; break;
        case 5: bps = 24; break;
        case 6: bps = 32; break;
        default: break;
    }

    // Determine number of channels and decorrelation
    uint8_t numChannels = streamInfo_.channels;
    enum class ChannelMode { INDEPENDENT, LEFT_SIDE, RIGHT_SIDE, MID_SIDE };
    ChannelMode channelMode = ChannelMode::INDEPENDENT;

    if (channelAssignment < 8) {
        numChannels = channelAssignment + 1;
    } else if (channelAssignment == 8) {
        numChannels = 2;
        channelMode = ChannelMode::LEFT_SIDE;
    } else if (channelAssignment == 9) {
        numChannels = 2;
        channelMode = ChannelMode::RIGHT_SIDE;
    } else if (channelAssignment == 10) {
        numChannels = 2;
        channelMode = ChannelMode::MID_SIDE;
    }

    // Decode subframes
    std::vector<std::vector<int32_t>> channelData(numChannels);
    for (uint8_t ch = 0; ch < numChannels; ++ch) {
        channelData[ch].resize(blockSize, 0);

        // Effective bits per sample (side channels get +1 bit)
        uint8_t effectiveBps = bps;
        if (channelMode == ChannelMode::LEFT_SIDE && ch == 1) effectiveBps++;
        else if (channelMode == ChannelMode::RIGHT_SIDE && ch == 0) effectiveBps++;
        else if (channelMode == ChannelMode::MID_SIDE && ch == 1) effectiveBps++;

        if (!decodeSubframe(reader, channelData[ch], blockSize, effectiveBps)) {
            // Decoding failed, fill with zeros
            std::fill(channelData[ch].begin(), channelData[ch].end(), 0);
        }
    }

    // Apply stereo decorrelation
    if (channelMode == ChannelMode::LEFT_SIDE) {
        // channel 0 = left, channel 1 = left - right (side)
        for (uint32_t i = 0; i < blockSize; ++i) {
            channelData[1][i] = channelData[0][i] - channelData[1][i];
        }
    } else if (channelMode == ChannelMode::RIGHT_SIDE) {
        // channel 0 = left - right (side), channel 1 = right
        for (uint32_t i = 0; i < blockSize; ++i) {
            channelData[0][i] = channelData[0][i] + channelData[1][i];
        }
    } else if (channelMode == ChannelMode::MID_SIDE) {
        // channel 0 = mid = (left + right)/2, channel 1 = side = left - right
        for (uint32_t i = 0; i < blockSize; ++i) {
            int32_t mid = channelData[0][i];
            int32_t side = channelData[1][i];
            mid = (mid << 1) | (side & 1); // Restore lost bit
            channelData[0][i] = (mid + side) >> 1; // left
            channelData[1][i] = (mid - side) >> 1; // right
        }
    }

    // Interleave channels into decode buffer
    size_t bytesPerSample = (bps + 7) / 8;
    // Use the format's bytesPerFrame for output
    size_t outputBytesPerSample = format_.bitsPerSample / 8;
    size_t outputBytesPerFrame = outputBytesPerSample * format_.channels;
    decodeBuffer_.resize(blockSize * outputBytesPerFrame);
    decodeBufferFrames_ = blockSize;
    decodeBufferOffset_ = 0;

    for (uint32_t i = 0; i < blockSize; ++i) {
        for (uint8_t ch = 0; ch < format_.channels; ++ch) {
            int32_t sample = (ch < numChannels) ? channelData[ch][i] : 0;
            size_t byteIdx = (i * format_.channels + ch) * outputBytesPerSample;

            // Write sample in little-endian format
            switch (outputBytesPerSample) {
                case 1:
                    decodeBuffer_[byteIdx] = static_cast<uint8_t>(sample & 0xFF);
                    break;
                case 2:
                    decodeBuffer_[byteIdx] = static_cast<uint8_t>(sample & 0xFF);
                    decodeBuffer_[byteIdx + 1] = static_cast<uint8_t>((sample >> 8) & 0xFF);
                    break;
                case 3:
                    decodeBuffer_[byteIdx] = static_cast<uint8_t>(sample & 0xFF);
                    decodeBuffer_[byteIdx + 1] = static_cast<uint8_t>((sample >> 8) & 0xFF);
                    decodeBuffer_[byteIdx + 2] = static_cast<uint8_t>((sample >> 16) & 0xFF);
                    break;
                case 4:
                    decodeBuffer_[byteIdx] = static_cast<uint8_t>(sample & 0xFF);
                    decodeBuffer_[byteIdx + 1] = static_cast<uint8_t>((sample >> 8) & 0xFF);
                    decodeBuffer_[byteIdx + 2] = static_cast<uint8_t>((sample >> 16) & 0xFF);
                    decodeBuffer_[byteIdx + 3] = static_cast<uint8_t>((sample >> 24) & 0xFF);
                    break;
            }
        }
    }

    // Calculate consumed bytes (align reader, skip CRC-16)
    reader.alignToByte();
    size_t consumed = reader.getBytePosition() + 2; // +2 for CRC-16
    if (consumed > available) consumed = available;

    return consumed;
}

bool FlacDecoder::decodeSubframe(FlacBitReader& reader, std::vector<int32_t>& output,
                                  uint32_t blockSize, uint8_t bps) {
    // Subframe header
    reader.readBits(1); // Zero padding bit

    uint8_t subframeType = static_cast<uint8_t>(reader.readBits(6));
    bool hasWastedBits = reader.readBits(1) != 0;
    uint8_t wastedBits = 0;
    if (hasWastedBits) {
        wastedBits = 1;
        while (reader.readBits(1) == 0) {
            wastedBits++;
            if (wastedBits > 32) return false; // Safety
        }
        bps -= wastedBits;
    }

    bool success = false;
    if (subframeType == 0) {
        // CONSTANT subframe
        int32_t value = reader.readSignedBits(bps);
        std::fill(output.begin(), output.end(), value);
        success = true;
    } else if (subframeType == 1) {
        // VERBATIM subframe - raw uncompressed samples
        for (uint32_t i = 0; i < blockSize; ++i) {
            output[i] = reader.readSignedBits(bps);
        }
        success = true;
    } else if (subframeType >= 8 && subframeType <= 12) {
        // FIXED predictor (order = subframeType - 8)
        uint8_t order = subframeType - 8;
        success = decodeFixedSubframe(reader, output, blockSize, bps, order);
    } else if (subframeType >= 32 && subframeType <= 63) {
        // LPC predictor (order = subframeType - 31)
        uint8_t order = subframeType - 31;
        success = decodeLpcSubframe(reader, output, blockSize, bps, order);
    }

    // Apply wasted bits
    if (success && wastedBits > 0) {
        for (uint32_t i = 0; i < blockSize; ++i) {
            output[i] <<= wastedBits;
        }
    }

    return success;
}

bool FlacDecoder::decodeFixedSubframe(FlacBitReader& reader, std::vector<int32_t>& output,
                                       uint32_t blockSize, uint8_t bps, uint8_t order) {
    if (order > 4 || blockSize == 0) return false;

    // Read warm-up samples
    for (uint8_t i = 0; i < order; ++i) {
        output[i] = reader.readSignedBits(bps);
    }

    // Read residual
    std::vector<int32_t> residual(blockSize - order, 0);
    if (!decodeResidual(reader, residual, blockSize, order)) {
        return false;
    }

    // Apply fixed prediction
    for (uint32_t i = order; i < blockSize; ++i) {
        int32_t prediction = 0;
        switch (order) {
            case 0:
                prediction = 0;
                break;
            case 1:
                prediction = output[i - 1];
                break;
            case 2:
                prediction = 2 * output[i - 1] - output[i - 2];
                break;
            case 3:
                prediction = 3 * output[i - 1] - 3 * output[i - 2] + output[i - 3];
                break;
            case 4:
                prediction = 4 * output[i - 1] - 6 * output[i - 2] + 4 * output[i - 3] - output[i - 4];
                break;
        }
        output[i] = prediction + residual[i - order];
    }

    return true;
}

bool FlacDecoder::decodeLpcSubframe(FlacBitReader& reader, std::vector<int32_t>& output,
                                     uint32_t blockSize, uint8_t bps, uint8_t order) {
    if (order == 0 || blockSize == 0) return false;

    // Read warm-up samples
    for (uint8_t i = 0; i < order; ++i) {
        output[i] = reader.readSignedBits(bps);
    }

    // Read LPC precision and shift
    uint8_t precision = static_cast<uint8_t>(reader.readBits(4)) + 1;
    int8_t shift = static_cast<int8_t>(reader.readSignedBits(5));

    // Read LPC coefficients
    std::vector<int32_t> coeffs(order);
    for (uint8_t i = 0; i < order; ++i) {
        coeffs[i] = reader.readSignedBits(precision);
    }

    // Read residual
    std::vector<int32_t> residual(blockSize - order, 0);
    if (!decodeResidual(reader, residual, blockSize, order)) {
        return false;
    }

    // Apply LPC prediction
    for (uint32_t i = order; i < blockSize; ++i) {
        int64_t prediction = 0;
        for (uint8_t j = 0; j < order; ++j) {
            prediction += static_cast<int64_t>(coeffs[j]) * output[i - 1 - j];
        }
        if (shift >= 0) {
            prediction >>= shift;
        } else {
            prediction <<= (-shift);
        }
        output[i] = static_cast<int32_t>(prediction) + residual[i - order];
    }

    return true;
}

bool FlacDecoder::decodeResidual(FlacBitReader& reader, std::vector<int32_t>& residual,
                                  uint32_t blockSize, uint8_t predictorOrder) {
    uint8_t codingMethod = static_cast<uint8_t>(reader.readBits(2));
    bool isRice2 = (codingMethod == 1);
    if (codingMethod > 1) return false; // Only Rice and Rice2 are defined

    uint8_t partitionOrder = static_cast<uint8_t>(reader.readBits(4));
    uint32_t numPartitions = 1u << partitionOrder;
    uint8_t paramBits = isRice2 ? 5 : 4;
    uint8_t escapeCode = isRice2 ? 31 : 15;

    uint32_t residualIdx = 0;
    uint32_t samplesInPartition;

    for (uint32_t partition = 0; partition < numPartitions; ++partition) {
        if (partition == 0) {
            samplesInPartition = (blockSize >> partitionOrder) - predictorOrder;
        } else {
            samplesInPartition = blockSize >> partitionOrder;
        }

        uint8_t riceParam = static_cast<uint8_t>(reader.readBits(paramBits));

        if (riceParam == escapeCode) {
            // Escape: raw encoding
            uint8_t rawBits = static_cast<uint8_t>(reader.readBits(5));
            for (uint32_t i = 0; i < samplesInPartition; ++i) {
                if (residualIdx < residual.size()) {
                    residual[residualIdx++] = reader.readSignedBits(rawBits);
                }
            }
        } else {
            // Rice-coded residual
            for (uint32_t i = 0; i < samplesInPartition; ++i) {
                if (residualIdx >= residual.size()) break;
                uint32_t q = reader.readUnary();
                uint32_t r = (riceParam > 0) ? reader.readBits(riceParam) : 0;
                uint32_t val = (q << riceParam) | r;
                // Zig-zag decode: even -> positive, odd -> negative
                int32_t decoded = (val & 1) ? -static_cast<int32_t>((val + 1) >> 1)
                                            : static_cast<int32_t>(val >> 1);
                residual[residualIdx++] = decoded;
            }
        }
    }

    return true;
}

bool FlacDecoder::seek(const SeekPosition& position) {
    if (!isOpen()) return false;

    uint64_t targetFrame = std::min(position.frameIndex, format_.totalFrames);
    currentFrame_ = targetFrame;

    // Reset decode buffer
    decodeBufferFrames_ = 0;
    decodeBufferOffset_ = 0;

    // For seek, we reset to the start of audio data and scan forward.
    // A full implementation would use the SEEKTABLE for efficiency.
    if (memMode_) {
        memOffset_ = audioDataOffset_;
    } else if (file_) {
        fseek(file_, static_cast<long>(audioDataOffset_), SEEK_SET);
    }

    // Skip frames until we reach targetFrame
    if (targetFrame > 0) {
        size_t bytesPerFrame = format_.bytesPerFrame();
        std::vector<uint8_t> skipBuf(4096 * bytesPerFrame);
        uint64_t skipped = 0;
        while (skipped < targetFrame) {
            size_t toSkip = std::min(static_cast<size_t>(targetFrame - skipped), size_t(4096));
            size_t got = readInternal(skipBuf.data(), toSkip);
            if (got == 0) break;
            skipped += got;
        }
        currentFrame_ = skipped;
    }

    return true;
}

size_t FlacDecoder::readInternal(uint8_t* buffer, size_t frames) {
    // Internal read that does not update currentFrame_ (used by seek)
    if (!isOpen() || frames == 0) return 0;

    size_t bytesPerFrame = format_.bytesPerFrame();
    size_t totalFramesDecoded = 0;
    uint8_t* outPtr = buffer;

    // Use leftover decoded data
    while (totalFramesDecoded < frames && decodeBufferOffset_ < decodeBufferFrames_) {
        size_t availableFrames = decodeBufferFrames_ - decodeBufferOffset_;
        size_t needed = frames - totalFramesDecoded;
        size_t toCopy = std::min(availableFrames, needed);
        size_t byteOffset = decodeBufferOffset_ * bytesPerFrame;
        memcpy(outPtr, decodeBuffer_.data() + byteOffset, toCopy * bytesPerFrame);
        outPtr += toCopy * bytesPerFrame;
        decodeBufferOffset_ += toCopy;
        totalFramesDecoded += toCopy;
    }

    while (totalFramesDecoded < frames) {
        const uint8_t* frameData = nullptr;
        size_t available = 0;

        if (memMode_) {
            if (memOffset_ >= memSize_) break;
            frameData = memData_ + memOffset_;
            available = memSize_ - memOffset_;
        } else if (file_) {
            size_t chunkSize = streamInfo_.maxFrameSize;
            if (chunkSize == 0 || chunkSize > 65536) chunkSize = 65536;
            fileReadBuffer_.resize(chunkSize);
            size_t bytesRead = fread(fileReadBuffer_.data(), 1, chunkSize, file_);
            if (bytesRead == 0) break;
            frameData = fileReadBuffer_.data();
            available = bytesRead;
        } else {
            break;
        }

        size_t consumed = decodeFrame(frameData, available, nullptr, 0);
        if (consumed == 0) {
            if (memMode_) memOffset_++;
            else if (file_) {
                long seekBack = -static_cast<long>(available) + 1;
                fseek(file_, seekBack, SEEK_CUR);
            }
            break;
        }

        if (memMode_) {
            memOffset_ += consumed;
        } else if (file_) {
            long seekBack = -static_cast<long>(available - consumed);
            if (seekBack != 0) fseek(file_, seekBack, SEEK_CUR);
        }

        size_t needed = frames - totalFramesDecoded;
        size_t toCopy = std::min(decodeBufferFrames_, needed);
        memcpy(outPtr, decodeBuffer_.data(), toCopy * bytesPerFrame);
        outPtr += toCopy * bytesPerFrame;
        totalFramesDecoded += toCopy;
        decodeBufferOffset_ = toCopy;
    }

    return totalFramesDecoded;
}

void FlacDecoder::close() {
    if (file_) {
        fclose(file_);
        file_ = nullptr;
    }
    memData_ = nullptr;
    memSize_ = 0;
    memOffset_ = 0;
    memMode_ = false;
    currentFrame_ = 0;
    audioDataOffset_ = 0;
    format_ = AudioFormat{};
    streamInfo_ = StreamInfo{};
    decodeBuffer_.clear();
    decodeBufferFrames_ = 0;
    decodeBufferOffset_ = 0;
    fileReadBuffer_.clear();
}

} // namespace decoder
} // namespace bitperfect
