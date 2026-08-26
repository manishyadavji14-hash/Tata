#include "dsd_stream.h"
#include <algorithm>
#include <cstring>

namespace bitperfect {
namespace dsd {

bool DsdStream::initialize(const DsfFileInfo& info, const uint8_t* rawData, size_t rawLength) {
    if (!info.isValid || !rawData || rawLength == 0) {
        state_ = DsdStreamState::ERROR;
        return false;
    }

    // Use parser to extract channel data
    DsfParser parser;
    // We need to reconstruct enough info for extraction
    channelCount_ = info.channelCount;
    sampleRate_ = info.sampleRate;

    uint32_t blockSize = info.blockSizePerChannel;
    uint64_t expectedBytesPerChannel = (info.sampleCount + 7) / 8;

    channelData_.resize(channelCount_);
    for (uint32_t ch = 0; ch < channelCount_; ++ch) {
        channelData_[ch].reserve(static_cast<size_t>(expectedBytesPerChannel));
    }

    // DSF interleaving: [block_ch0][block_ch1]...[block_chN] repeating
    size_t interleavedBlockSize = static_cast<size_t>(blockSize) * channelCount_;
    size_t offset = 0;

    while (offset + interleavedBlockSize <= rawLength) {
        for (uint32_t ch = 0; ch < channelCount_; ++ch) {
            size_t channelBlockStart = offset + static_cast<size_t>(ch) * blockSize;
            size_t bytesToCopy = blockSize;
            size_t currentSize = channelData_[ch].size();
            if (currentSize + bytesToCopy > expectedBytesPerChannel) {
                bytesToCopy = static_cast<size_t>(expectedBytesPerChannel - currentSize);
            }
            if (bytesToCopy > 0) {
                channelData_[ch].insert(channelData_[ch].end(),
                                        rawData + channelBlockStart,
                                        rawData + channelBlockStart + bytesToCopy);
            }
        }
        offset += interleavedBlockSize;
    }

    totalBytesPerChannel_ = channelData_.empty() ? 0 : channelData_[0].size();
    position_ = 0;
    state_ = DsdStreamState::READY;
    return true;
}

bool DsdStream::initializeFromChannelData(const std::vector<std::vector<uint8_t>>& channelData,
                                           uint32_t sampleRate) {
    if (channelData.empty() || sampleRate == 0) {
        state_ = DsdStreamState::ERROR;
        return false;
    }

    channelData_ = channelData;
    channelCount_ = static_cast<uint32_t>(channelData.size());
    sampleRate_ = sampleRate;
    totalBytesPerChannel_ = channelData_[0].size();
    position_ = 0;
    state_ = DsdStreamState::READY;
    return true;
}

size_t DsdStream::read(uint32_t channel, uint8_t* buffer, size_t maxBytes) {
    if (channel >= channelCount_ || !buffer || maxBytes == 0) {
        return 0;
    }

    if (state_ == DsdStreamState::READY) {
        state_ = DsdStreamState::STREAMING;
    }

    if (state_ != DsdStreamState::STREAMING) {
        return 0;
    }

    size_t available = static_cast<size_t>(totalBytesPerChannel_ - position_);
    size_t toRead = std::min(maxBytes, available);

    if (toRead == 0) {
        state_ = DsdStreamState::END_OF_STREAM;
        return 0;
    }

    std::memcpy(buffer, channelData_[channel].data() + position_, toRead);

    // Note: position advances only after all channels are read via readInterleaved
    // For single-channel read, caller must manage position manually or use seek
    return toRead;
}

size_t DsdStream::readInterleaved(uint8_t* buffer, size_t maxBytes) {
    if (!buffer || maxBytes == 0 || channelCount_ == 0) {
        return 0;
    }

    if (state_ == DsdStreamState::READY) {
        state_ = DsdStreamState::STREAMING;
    }

    if (state_ != DsdStreamState::STREAMING) {
        return 0;
    }

    // Round down to channel boundary
    size_t bytesPerRound = channelCount_;
    size_t maxRounds = maxBytes / bytesPerRound;
    size_t available = static_cast<size_t>(totalBytesPerChannel_ - position_);
    size_t rounds = std::min(maxRounds, available);

    if (rounds == 0) {
        state_ = DsdStreamState::END_OF_STREAM;
        return 0;
    }

    size_t written = 0;
    for (size_t i = 0; i < rounds; ++i) {
        for (uint32_t ch = 0; ch < channelCount_; ++ch) {
            buffer[written++] = channelData_[ch][static_cast<size_t>(position_) + i];
        }
    }

    position_ += rounds;
    if (position_ >= totalBytesPerChannel_) {
        state_ = DsdStreamState::END_OF_STREAM;
    }

    return written;
}

bool DsdStream::seek(uint64_t byteOffset) {
    if (byteOffset > totalBytesPerChannel_) {
        return false;
    }
    position_ = byteOffset;
    if (position_ < totalBytesPerChannel_) {
        state_ = DsdStreamState::READY;
    } else {
        state_ = DsdStreamState::END_OF_STREAM;
    }
    return true;
}

void DsdStream::reset() {
    position_ = 0;
    if (!channelData_.empty() && totalBytesPerChannel_ > 0) {
        state_ = DsdStreamState::READY;
    } else {
        state_ = DsdStreamState::IDLE;
    }
}

} // namespace dsd
} // namespace bitperfect
