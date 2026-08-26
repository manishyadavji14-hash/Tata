#include <gtest/gtest.h>
#include "decoder/flac_decoder.h"
#include "decoder/decoder_factory.h"
#include <vector>
#include <cstring>
#include <algorithm>

using namespace bitperfect::decoder;

namespace {

/**
 * Helper to create a minimal valid FLAC file header (STREAMINFO only).
 * Creates a valid fLaC stream with STREAMINFO metadata block.
 */
std::vector<uint8_t> createFlacStreamInfo(uint32_t sampleRate, uint8_t channels,
                                           uint8_t bitsPerSample, uint64_t totalSamples,
                                           uint16_t minBlockSize = 4096,
                                           uint16_t maxBlockSize = 4096) {
    std::vector<uint8_t> flac;

    // "fLaC" marker
    flac.push_back('f');
    flac.push_back('L');
    flac.push_back('a');
    flac.push_back('C');

    // Metadata block header: last-metadata-block flag (1) | type (0 = STREAMINFO) | length (34)
    flac.push_back(0x80);  // Last block = 1, type = 0
    flac.push_back(0x00);  // Length high byte
    flac.push_back(0x00);  // Length mid byte
    flac.push_back(0x22);  // Length = 34

    // STREAMINFO (34 bytes):
    // bytes 0-1: minimum block size
    flac.push_back((minBlockSize >> 8) & 0xFF);
    flac.push_back(minBlockSize & 0xFF);

    // bytes 2-3: maximum block size
    flac.push_back((maxBlockSize >> 8) & 0xFF);
    flac.push_back(maxBlockSize & 0xFF);

    // bytes 4-6: minimum frame size (24 bits)
    flac.push_back(0x00);
    flac.push_back(0x00);
    flac.push_back(0x00);

    // bytes 7-9: maximum frame size (24 bits)
    flac.push_back(0x00);
    flac.push_back(0x00);
    flac.push_back(0x00);

    // bytes 10-17: packed sample rate (20 bits), channels-1 (3 bits),
    //              bits per sample-1 (5 bits), total samples (36 bits)
    //
    // Layout of 8 bytes (64 bits):
    // [SSSSSSSS] [SSSSSSSS] [SSSS CCCC] [BBBB BTTT] [TTTTTTTT] [TTTTTTTT] [TTTTTTTT] [TTTTTTTT]
    // S = sample rate (20 bits), C = channels-1 (3 bits) + 1 bit of bps, B = bps-1 remaining (4 bits) + first bit is in prev byte
    // Actually the layout is:
    // bits 0-19: sample rate (20 bits)
    // bits 20-22: (number of channels)-1 (3 bits)
    // bits 23-27: (bits per sample)-1 (5 bits)
    // bits 28-63: total samples in stream (36 bits)

    uint64_t packed = 0;
    packed |= (static_cast<uint64_t>(sampleRate) << 44);
    packed |= (static_cast<uint64_t>(channels - 1) << 41);
    packed |= (static_cast<uint64_t>(bitsPerSample - 1) << 36);
    packed |= (totalSamples & 0xFFFFFFFFFULL);

    flac.push_back(static_cast<uint8_t>((packed >> 56) & 0xFF));
    flac.push_back(static_cast<uint8_t>((packed >> 48) & 0xFF));
    flac.push_back(static_cast<uint8_t>((packed >> 40) & 0xFF));
    flac.push_back(static_cast<uint8_t>((packed >> 32) & 0xFF));
    flac.push_back(static_cast<uint8_t>((packed >> 24) & 0xFF));
    flac.push_back(static_cast<uint8_t>((packed >> 16) & 0xFF));
    flac.push_back(static_cast<uint8_t>((packed >> 8) & 0xFF));
    flac.push_back(static_cast<uint8_t>(packed & 0xFF));

    // bytes 18-33: MD5 signature (16 bytes, zeros for test)
    for (int i = 0; i < 16; ++i) {
        flac.push_back(0x00);
    }

    return flac;
}

} // anonymous namespace

// Helper to create a simple FLAC file with verbatim frames for testing
namespace {

/**
 * Build a minimal FLAC file with STREAMINFO and a single verbatim frame.
 * Verbatim subframes are the simplest: just raw samples with no prediction.
 */
class FlacFrameBuilder {
public:
    FlacFrameBuilder() : bitBuffer_(), bitPos_(0) {}

    void writeBits(uint32_t value, uint8_t bits) {
        for (int i = bits - 1; i >= 0; --i) {
            if (bitPos_ % 8 == 0) {
                bitBuffer_.push_back(0);
            }
            if (value & (1u << i)) {
                bitBuffer_.back() |= (1 << (7 - (bitPos_ % 8)));
            }
            bitPos_++;
        }
    }

    void writeSignedBits(int32_t value, uint8_t bits) {
        uint32_t uval = static_cast<uint32_t>(value) & ((1u << bits) - 1);
        writeBits(uval, bits);
    }

    void alignToByte() {
        while (bitPos_ % 8 != 0) {
            writeBits(0, 1);
        }
    }

    std::vector<uint8_t> getData() const { return bitBuffer_; }
    size_t getBitPos() const { return bitPos_; }

    // Build a complete FLAC frame with verbatim subframes
    static std::vector<uint8_t> buildVerbatimFrame(
            uint32_t blockSize, uint32_t sampleRate, uint8_t channels,
            uint8_t bitsPerSample, uint32_t frameNumber,
            const std::vector<std::vector<int32_t>>& channelSamples) {

        FlacFrameBuilder builder;

        // Frame header
        builder.writeBits(0x3FFE, 14); // Sync code
        builder.writeBits(0, 1);       // Reserved
        builder.writeBits(0, 1);       // Blocking strategy: fixed

        // Block size code
        uint8_t blockSizeCode = 0;
        if (blockSize == 192) blockSizeCode = 1;
        else if (blockSize == 576) blockSizeCode = 2;
        else if (blockSize == 1152) blockSizeCode = 3;
        else if (blockSize == 2304) blockSizeCode = 4;
        else if (blockSize == 4608) blockSizeCode = 5;
        else if (blockSize <= 256) blockSizeCode = 6; // 8-bit block size follows
        else if (blockSize <= 65536) blockSizeCode = 7; // 16-bit block size follows
        else {
            // Use power-of-two code
            for (int i = 8; i <= 15; ++i) {
                if (blockSize == (256u << (i - 8))) {
                    blockSizeCode = static_cast<uint8_t>(i);
                    break;
                }
            }
        }
        builder.writeBits(blockSizeCode, 4);

        // Sample rate code
        uint8_t sampleRateCode = 0;
        if (sampleRate == 88200) sampleRateCode = 1;
        else if (sampleRate == 176400) sampleRateCode = 2;
        else if (sampleRate == 192000) sampleRateCode = 3;
        else if (sampleRate == 8000) sampleRateCode = 4;
        else if (sampleRate == 16000) sampleRateCode = 5;
        else if (sampleRate == 22050) sampleRateCode = 6;
        else if (sampleRate == 24000) sampleRateCode = 7;
        else if (sampleRate == 32000) sampleRateCode = 8;
        else if (sampleRate == 44100) sampleRateCode = 9;
        else if (sampleRate == 48000) sampleRateCode = 10;
        else if (sampleRate == 96000) sampleRateCode = 11;
        else sampleRateCode = 0; // Unknown, use STREAMINFO
        builder.writeBits(sampleRateCode, 4);

        // Channel assignment (independent channels)
        builder.writeBits(channels - 1, 4);

        // Sample size code
        uint8_t sampleSizeCode = 0;
        if (bitsPerSample == 8) sampleSizeCode = 1;
        else if (bitsPerSample == 12) sampleSizeCode = 2;
        else if (bitsPerSample == 16) sampleSizeCode = 3;
        else if (bitsPerSample == 20) sampleSizeCode = 4;
        else if (bitsPerSample == 24) sampleSizeCode = 5;
        else if (bitsPerSample == 32) sampleSizeCode = 6;
        builder.writeBits(sampleSizeCode, 3);

        builder.writeBits(0, 1); // Reserved

        // Frame number (UTF-8 coded)
        if (frameNumber < 128) {
            builder.writeBits(frameNumber, 8);
        } else {
            // For simplicity, only handle small frame numbers
            builder.writeBits(0xC0 | ((frameNumber >> 6) & 0x1F), 8);
            builder.writeBits(0x80 | (frameNumber & 0x3F), 8);
        }

        // Block size if variable-length
        if (blockSizeCode == 6) {
            builder.writeBits(blockSize - 1, 8);
        } else if (blockSizeCode == 7) {
            builder.writeBits(blockSize - 1, 16);
        }

        // Frame header CRC-8 (simplified: just write 0, decoder consumes it)
        builder.alignToByte();
        builder.writeBits(0, 8); // CRC-8 placeholder

        // Subframes (verbatim for each channel)
        for (uint8_t ch = 0; ch < channels; ++ch) {
            builder.writeBits(0, 1);    // Zero padding
            builder.writeBits(1, 6);    // Subframe type = VERBATIM (1)
            builder.writeBits(0, 1);    // No wasted bits

            // Write raw samples
            for (uint32_t i = 0; i < blockSize; ++i) {
                int32_t sample = (ch < channelSamples.size() && i < channelSamples[ch].size())
                    ? channelSamples[ch][i] : 0;
                builder.writeSignedBits(sample, bitsPerSample);
            }
        }

        // Frame footer CRC-16 (placeholder)
        builder.alignToByte();
        builder.writeBits(0, 16); // CRC-16 placeholder

        return builder.getData();
    }

private:
    std::vector<uint8_t> bitBuffer_;
    size_t bitPos_;
};

/**
 * Create a FLAC file with STREAMINFO and verbatim audio frames.
 */
std::vector<uint8_t> createFlacWithVerbatimFrames(uint32_t sampleRate, uint8_t channels,
                                                    uint8_t bitsPerSample, uint32_t totalFrames,
                                                    uint16_t blockSize = 256) {
    // Create STREAMINFO
    auto flac = createFlacStreamInfo(sampleRate, channels, bitsPerSample, totalFrames,
                                      blockSize, blockSize);

    // Generate sample data (simple ramp)
    uint32_t framesRemaining = totalFrames;
    uint32_t frameNumber = 0;
    int32_t sampleCounter = 0;

    while (framesRemaining > 0) {
        uint32_t thisBlockSize = std::min(static_cast<uint32_t>(blockSize), framesRemaining);

        std::vector<std::vector<int32_t>> channelSamples(channels);
        for (uint8_t ch = 0; ch < channels; ++ch) {
            channelSamples[ch].resize(thisBlockSize);
            for (uint32_t i = 0; i < thisBlockSize; ++i) {
                // Generate a simple ramp pattern
                int32_t maxVal = (1 << (bitsPerSample - 1)) - 1;
                channelSamples[ch][i] = (sampleCounter + ch * 100 + static_cast<int32_t>(i)) % maxVal;
            }
        }
        sampleCounter += static_cast<int32_t>(thisBlockSize);

        auto frameData = FlacFrameBuilder::buildVerbatimFrame(
            thisBlockSize, sampleRate, channels, bitsPerSample, frameNumber, channelSamples);
        flac.insert(flac.end(), frameData.begin(), frameData.end());

        framesRemaining -= thisBlockSize;
        frameNumber++;
    }

    return flac;
}

} // anonymous namespace for frame builder

// --- STREAMINFO parsing tests ---

TEST(FlacDecoder, ParseStreamInfo44100_16Bit) {
    auto flac = createFlacStreamInfo(44100, 2, 16, 441000);

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    AudioFormat fmt = decoder.getFormat();
    EXPECT_EQ(fmt.sampleRate, 44100u);
    EXPECT_EQ(fmt.bitsPerSample, 16);
    EXPECT_EQ(fmt.channels, 2);
    EXPECT_EQ(fmt.totalFrames, 441000u);

    const auto& info = decoder.getStreamInfo();
    EXPECT_EQ(info.sampleRate, 44100u);
    EXPECT_EQ(info.channels, 2);
    EXPECT_EQ(info.bitsPerSample, 16);
    EXPECT_EQ(info.totalSamples, 441000u);
    EXPECT_EQ(info.minBlockSize, 4096u);
    EXPECT_EQ(info.maxBlockSize, 4096u);
}

TEST(FlacDecoder, ParseStreamInfo96000_24Bit) {
    auto flac = createFlacStreamInfo(96000, 2, 24, 960000);

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    AudioFormat fmt = decoder.getFormat();
    EXPECT_EQ(fmt.sampleRate, 96000u);
    EXPECT_EQ(fmt.bitsPerSample, 24);
    EXPECT_EQ(fmt.channels, 2);
    EXPECT_EQ(fmt.totalFrames, 960000u);
}

TEST(FlacDecoder, ParseStreamInfo192000_32Bit) {
    auto flac = createFlacStreamInfo(192000, 2, 32, 1920000);

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    AudioFormat fmt = decoder.getFormat();
    EXPECT_EQ(fmt.sampleRate, 192000u);
    EXPECT_EQ(fmt.bitsPerSample, 32);
    EXPECT_EQ(fmt.channels, 2);
    EXPECT_EQ(fmt.totalFrames, 1920000u);
}

TEST(FlacDecoder, ParseStreamInfo384000) {
    auto flac = createFlacStreamInfo(384000, 2, 24, 3840000);

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    AudioFormat fmt = decoder.getFormat();
    EXPECT_EQ(fmt.sampleRate, 384000u);
}

TEST(FlacDecoder, ParseStreamInfoMono) {
    auto flac = createFlacStreamInfo(44100, 1, 16, 44100);

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    AudioFormat fmt = decoder.getFormat();
    EXPECT_EQ(fmt.channels, 1);
}

TEST(FlacDecoder, ParseStreamInfoMultichannel) {
    auto flac = createFlacStreamInfo(48000, 6, 24, 480000);  // 5.1 channel

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    AudioFormat fmt = decoder.getFormat();
    EXPECT_EQ(fmt.channels, 6);
}

// --- Duration ---

TEST(FlacDecoder, DurationCalculation) {
    // 44100 samples at 44100 Hz = 1 second
    auto flac = createFlacStreamInfo(44100, 2, 16, 44100);

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    EXPECT_NEAR(decoder.getDuration(), 1.0, 0.001);
}

TEST(FlacDecoder, LongDuration) {
    // 10 minutes at 192kHz
    uint64_t totalSamples = 192000ULL * 600;  // 600 seconds
    auto flac = createFlacStreamInfo(192000, 2, 24, totalSamples);

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    EXPECT_NEAR(decoder.getDuration(), 600.0, 0.001);
}

// --- Read interface ---

TEST(FlacDecoder, ReadReturnsFrames) {
    auto flac = createFlacWithVerbatimFrames(44100, 2, 16, 1000, 256);

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    uint8_t buffer[4096];
    size_t framesRead = decoder.read(buffer, 100);
    EXPECT_GT(framesRead, 0u);
    EXPECT_EQ(decoder.getPosition(), framesRead);
}

TEST(FlacDecoder, ReadClampsToTotalFrames) {
    auto flac = createFlacWithVerbatimFrames(44100, 2, 16, 50, 50);

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    uint8_t buffer[4096];
    size_t framesRead = decoder.read(buffer, 100);  // Request more than available
    EXPECT_EQ(framesRead, 50u);  // Should only get 50

    framesRead = decoder.read(buffer, 100);
    EXPECT_EQ(framesRead, 0u);  // No more data
}

// --- Seek ---

TEST(FlacDecoder, SeekToPosition) {
    auto flac = createFlacWithVerbatimFrames(44100, 2, 16, 44100, 4096);

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    SeekPosition pos;
    pos.frameIndex = 22050;
    ASSERT_TRUE(decoder.seek(pos));
    EXPECT_EQ(decoder.getPosition(), 22050u);
}

// --- Error handling ---

TEST(FlacDecoder, RejectInvalidMagic) {
    uint8_t garbage[44] = {0};
    FlacDecoder decoder;
    EXPECT_FALSE(decoder.openFromMemory(garbage, 44));
}

TEST(FlacDecoder, RejectTooShortData) {
    uint8_t shortData[4] = {'f', 'L', 'a', 'C'};
    FlacDecoder decoder;
    EXPECT_FALSE(decoder.openFromMemory(shortData, 4));
}

TEST(FlacDecoder, RejectWrongMarker) {
    auto flac = createFlacStreamInfo(44100, 2, 16, 44100);
    flac[0] = 'X';  // Corrupt marker

    FlacDecoder decoder;
    EXPECT_FALSE(decoder.openFromMemory(flac.data(), flac.size()));
}

// --- Factory detection ---

TEST(FlacDecoder, FactoryDetectsFlacExtension) {
    EXPECT_EQ(DecoderFactory::detectFromExtension("test.flac"), DecoderType::FLAC);
    EXPECT_EQ(DecoderFactory::detectFromExtension("test.FLAC"), DecoderType::FLAC);
    EXPECT_EQ(DecoderFactory::detectFromExtension("/path/to/album/track.flac"), DecoderType::FLAC);
}

TEST(FlacDecoder, FactoryDetectsFlacMagic) {
    auto flac = createFlacStreamInfo(44100, 2, 16, 44100);
    EXPECT_EQ(DecoderFactory::detectFromMagic(flac.data(), flac.size()), DecoderType::FLAC);
}

TEST(FlacDecoder, FactoryCreatesFlacDecoder) {
    auto decoder = DecoderFactory::create(DecoderType::FLAC);
    ASSERT_NE(decoder, nullptr);
    EXPECT_STREQ(decoder->getTypeName(), "FLAC");
}

// --- IsOpen / Close ---

TEST(FlacDecoder, IsOpenAfterOpen) {
    auto flac = createFlacStreamInfo(44100, 2, 16, 44100);

    FlacDecoder decoder;
    EXPECT_FALSE(decoder.isOpen());
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));
    EXPECT_TRUE(decoder.isOpen());
    decoder.close();
    EXPECT_FALSE(decoder.isOpen());
}
