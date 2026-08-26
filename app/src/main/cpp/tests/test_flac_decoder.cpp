#include <gtest/gtest.h>
#include "decoder/flac_decoder.h"
#include "decoder/decoder_factory.h"
#include <vector>
#include <cstring>

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
    auto flac = createFlacStreamInfo(44100, 2, 16, 1000);

    FlacDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(flac.data(), flac.size()));

    uint8_t buffer[4096];
    size_t framesRead = decoder.read(buffer, 100);
    EXPECT_EQ(framesRead, 100u);
    EXPECT_EQ(decoder.getPosition(), 100u);
}

TEST(FlacDecoder, ReadClampsToTotalFrames) {
    auto flac = createFlacStreamInfo(44100, 2, 16, 50);

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
    auto flac = createFlacStreamInfo(44100, 2, 16, 44100);

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
