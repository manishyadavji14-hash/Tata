#include <gtest/gtest.h>
#include "../dsd/dsf_parser.h"
#include "../dsd/dsd_stream.h"
#include <vector>
#include <cstring>

using namespace bitperfect::dsd;

class DsfParserTest : public ::testing::Test {
protected:
    void SetUp() override {}
    void TearDown() override {}

    // Helper: build a minimal valid DSF header (DSD + fmt + data chunks)
    std::vector<uint8_t> buildDsfHeader(uint32_t channels, uint32_t sampleRate,
                                         uint32_t blockSize, uint64_t sampleCount,
                                         size_t dataSize) {
        std::vector<uint8_t> header;
        header.resize(28 + 52 + 12 + dataSize, 0);

        // DSD chunk (28 bytes)
        // "DSD " signature
        header[0] = 'D'; header[1] = 'S'; header[2] = 'D'; header[3] = ' ';
        // Chunk size = 28
        writeU64LE(header.data() + 4, 28);
        // Total file size
        writeU64LE(header.data() + 12, header.size());
        // Metadata offset (0 = none)
        writeU64LE(header.data() + 20, 0);

        // Format chunk (52 bytes)
        uint8_t* fmt = header.data() + 28;
        // "fmt " signature
        fmt[0] = 'f'; fmt[1] = 'm'; fmt[2] = 't'; fmt[3] = ' ';
        // Chunk size = 52
        writeU64LE(fmt + 4, 52);
        // Format version = 1
        writeU32LE(fmt + 12, 1);
        // Format ID = 0 (DSD raw)
        writeU32LE(fmt + 16, 0);
        // Channel type
        writeU32LE(fmt + 20, channels <= 2 ? channels : channels);
        // Channel count
        writeU32LE(fmt + 24, channels);
        // Sample rate
        writeU32LE(fmt + 28, sampleRate);
        // Bits per sample
        writeU32LE(fmt + 32, 1);
        // Sample count per channel
        writeU64LE(fmt + 36, sampleCount);
        // Block size per channel
        writeU32LE(fmt + 44, blockSize);
        // Reserved (4 bytes at offset 48)
        writeU32LE(fmt + 48, 0);

        // Data chunk header (12 bytes)
        uint8_t* data = header.data() + 28 + 52;
        // "data" signature
        data[0] = 'd'; data[1] = 'a'; data[2] = 't'; data[3] = 'a';
        // Data chunk size (header + data)
        writeU64LE(data + 4, 12 + dataSize);

        return header;
    }

    static void writeU32LE(uint8_t* p, uint32_t v) {
        p[0] = v & 0xFF;
        p[1] = (v >> 8) & 0xFF;
        p[2] = (v >> 16) & 0xFF;
        p[3] = (v >> 24) & 0xFF;
    }

    static void writeU64LE(uint8_t* p, uint64_t v) {
        writeU32LE(p, static_cast<uint32_t>(v & 0xFFFFFFFF));
        writeU32LE(p + 4, static_cast<uint32_t>(v >> 32));
    }
};

TEST_F(DsfParserTest, ParseValidDsd64Stereo) {
    uint32_t blockSize = 4096;
    uint64_t sampleCount = 4096 * 8; // blockSize bytes * 8 bits
    auto header = buildDsfHeader(2, DSD64_RATE, blockSize, sampleCount, blockSize * 2);

    DsfParser parser;
    ASSERT_TRUE(parser.parse(header.data(), header.size()));
    EXPECT_TRUE(parser.isValid());
    EXPECT_EQ(parser.getSampleRate(), DSD64_RATE);
    EXPECT_EQ(parser.getChannelCount(), 2u);
    EXPECT_EQ(parser.getBlockSizePerChannel(), blockSize);
}

TEST_F(DsfParserTest, ParseValidDsd128Stereo) {
    uint32_t blockSize = 4096;
    uint64_t sampleCount = 4096 * 8;
    auto header = buildDsfHeader(2, DSD128_RATE, blockSize, sampleCount, blockSize * 2);

    DsfParser parser;
    ASSERT_TRUE(parser.parse(header.data(), header.size()));
    EXPECT_TRUE(parser.isValid());
    EXPECT_EQ(parser.getSampleRate(), DSD128_RATE);
    EXPECT_EQ(parser.getChannelCount(), 2u);
}

TEST_F(DsfParserTest, ParseValidDsd256Mono) {
    uint32_t blockSize = 4096;
    uint64_t sampleCount = 4096 * 8;
    auto header = buildDsfHeader(1, DSD256_RATE, blockSize, sampleCount, blockSize);

    DsfParser parser;
    ASSERT_TRUE(parser.parse(header.data(), header.size()));
    EXPECT_TRUE(parser.isValid());
    EXPECT_EQ(parser.getSampleRate(), DSD256_RATE);
    EXPECT_EQ(parser.getChannelCount(), 1u);
}

TEST_F(DsfParserTest, RejectsInvalidSignature) {
    auto header = buildDsfHeader(2, DSD64_RATE, 4096, 32768, 8192);
    // Corrupt signature
    header[0] = 'X';

    DsfParser parser;
    EXPECT_FALSE(parser.parse(header.data(), header.size()));
    EXPECT_FALSE(parser.isValid());
}

TEST_F(DsfParserTest, RejectsTooShortData) {
    DsfParser parser;
    uint8_t tiny[10] = {};
    EXPECT_FALSE(parser.parse(tiny, sizeof(tiny)));
}

TEST_F(DsfParserTest, RejectsInvalidSampleRate) {
    auto header = buildDsfHeader(2, 44100, 4096, 32768, 8192); // PCM rate is invalid for DSD

    DsfParser parser;
    EXPECT_FALSE(parser.parse(header.data(), header.size()));
}

TEST_F(DsfParserTest, DsdMultiplierDetection) {
    EXPECT_EQ(DsfParser::getDsdMultiplier(DSD64_RATE), 64u);
    EXPECT_EQ(DsfParser::getDsdMultiplier(DSD128_RATE), 128u);
    EXPECT_EQ(DsfParser::getDsdMultiplier(DSD256_RATE), 256u);
    EXPECT_EQ(DsfParser::getDsdMultiplier(44100), 0u);
}

TEST_F(DsfParserTest, IsStandardDsdRate) {
    EXPECT_TRUE(DsfParser::isStandardDsdRate(DSD64_RATE));
    EXPECT_TRUE(DsfParser::isStandardDsdRate(DSD128_RATE));
    EXPECT_TRUE(DsfParser::isStandardDsdRate(DSD256_RATE));
    EXPECT_FALSE(DsfParser::isStandardDsdRate(44100));
    EXPECT_FALSE(DsfParser::isStandardDsdRate(0));
}

TEST_F(DsfParserTest, ExtractChannelDataStereo) {
    uint32_t blockSize = 4;
    uint64_t sampleCount = 4 * 8; // 4 bytes * 8 bits
    auto header = buildDsfHeader(2, DSD64_RATE, blockSize, sampleCount, blockSize * 2);

    // Fill data section: [ch0 block: AA BB CC DD][ch1 block: 11 22 33 44]
    uint8_t* dataSection = header.data() + 28 + 52 + 12;
    dataSection[0] = 0xAA; dataSection[1] = 0xBB;
    dataSection[2] = 0xCC; dataSection[3] = 0xDD;
    dataSection[4] = 0x11; dataSection[5] = 0x22;
    dataSection[6] = 0x33; dataSection[7] = 0x44;

    DsfParser parser;
    ASSERT_TRUE(parser.parse(header.data(), header.size()));

    std::vector<std::vector<uint8_t>> channelData;
    ASSERT_TRUE(parser.extractChannelData(dataSection, blockSize * 2, channelData));

    ASSERT_EQ(channelData.size(), 2u);
    ASSERT_EQ(channelData[0].size(), 4u);
    ASSERT_EQ(channelData[1].size(), 4u);

    EXPECT_EQ(channelData[0][0], 0xAA);
    EXPECT_EQ(channelData[0][1], 0xBB);
    EXPECT_EQ(channelData[0][2], 0xCC);
    EXPECT_EQ(channelData[0][3], 0xDD);

    EXPECT_EQ(channelData[1][0], 0x11);
    EXPECT_EQ(channelData[1][1], 0x22);
    EXPECT_EQ(channelData[1][2], 0x33);
    EXPECT_EQ(channelData[1][3], 0x44);
}

TEST_F(DsfParserTest, ExtractChannelDataMultipleBlocks) {
    uint32_t blockSize = 2;
    uint64_t sampleCount = 4 * 8; // 4 bytes per channel * 8 bits = 32 DSD samples
    size_t dataSize = blockSize * 2 * 2; // 2 blocks of interleaved data
    auto header = buildDsfHeader(2, DSD64_RATE, blockSize, sampleCount, dataSize);

    // Fill data: [block0_ch0: AA BB][block0_ch1: 11 22][block1_ch0: CC DD][block1_ch1: 33 44]
    uint8_t* dataSection = header.data() + 28 + 52 + 12;
    dataSection[0] = 0xAA; dataSection[1] = 0xBB; // ch0 block 0
    dataSection[2] = 0x11; dataSection[3] = 0x22; // ch1 block 0
    dataSection[4] = 0xCC; dataSection[5] = 0xDD; // ch0 block 1
    dataSection[6] = 0x33; dataSection[7] = 0x44; // ch1 block 1

    DsfParser parser;
    ASSERT_TRUE(parser.parse(header.data(), header.size()));

    std::vector<std::vector<uint8_t>> channelData;
    ASSERT_TRUE(parser.extractChannelData(dataSection, dataSize, channelData));

    ASSERT_EQ(channelData.size(), 2u);
    ASSERT_EQ(channelData[0].size(), 4u); // 2 blocks * 2 bytes
    ASSERT_EQ(channelData[1].size(), 4u);

    // Channel 0: AA BB CC DD
    EXPECT_EQ(channelData[0][0], 0xAA);
    EXPECT_EQ(channelData[0][1], 0xBB);
    EXPECT_EQ(channelData[0][2], 0xCC);
    EXPECT_EQ(channelData[0][3], 0xDD);

    // Channel 1: 11 22 33 44
    EXPECT_EQ(channelData[1][0], 0x11);
    EXPECT_EQ(channelData[1][1], 0x22);
    EXPECT_EQ(channelData[1][2], 0x33);
    EXPECT_EQ(channelData[1][3], 0x44);
}

// === DSD Stream Tests ===

TEST_F(DsfParserTest, DsdStreamInit) {
    uint32_t blockSize = 4;
    uint64_t sampleCount = 4 * 8;
    auto header = buildDsfHeader(2, DSD64_RATE, blockSize, sampleCount, blockSize * 2);

    // Fill data
    uint8_t* dataSection = header.data() + 28 + 52 + 12;
    for (int i = 0; i < 8; ++i) {
        dataSection[i] = static_cast<uint8_t>(i + 1);
    }

    DsfParser parser;
    ASSERT_TRUE(parser.parse(header.data(), header.size()));

    DsdStream stream;
    ASSERT_TRUE(stream.initialize(parser.getFileInfo(), dataSection, blockSize * 2));
    EXPECT_EQ(stream.getSampleRate(), DSD64_RATE);
    EXPECT_EQ(stream.getChannelCount(), 2u);
    EXPECT_EQ(stream.getTotalBytesPerChannel(), 4u);
    EXPECT_EQ(stream.getState(), DsdStreamState::READY);
}

TEST_F(DsfParserTest, DsdStreamReadInterleaved) {
    // Setup 2 channels with known data
    std::vector<std::vector<uint8_t>> channelData = {
        {0xAA, 0xBB, 0xCC, 0xDD},
        {0x11, 0x22, 0x33, 0x44}
    };

    DsdStream stream;
    ASSERT_TRUE(stream.initializeFromChannelData(channelData, DSD64_RATE));

    std::vector<uint8_t> buffer(8);
    size_t read = stream.readInterleaved(buffer.data(), buffer.size());
    ASSERT_EQ(read, 8u);

    // Interleaved: ch0[0], ch1[0], ch0[1], ch1[1], ...
    EXPECT_EQ(buffer[0], 0xAA);
    EXPECT_EQ(buffer[1], 0x11);
    EXPECT_EQ(buffer[2], 0xBB);
    EXPECT_EQ(buffer[3], 0x22);
    EXPECT_EQ(buffer[4], 0xCC);
    EXPECT_EQ(buffer[5], 0x33);
    EXPECT_EQ(buffer[6], 0xDD);
    EXPECT_EQ(buffer[7], 0x44);
}

TEST_F(DsfParserTest, DsdStreamSeekAndReset) {
    std::vector<std::vector<uint8_t>> channelData = {
        {0x01, 0x02, 0x03, 0x04},
        {0x0A, 0x0B, 0x0C, 0x0D}
    };

    DsdStream stream;
    ASSERT_TRUE(stream.initializeFromChannelData(channelData, DSD64_RATE));

    // Seek to byte 2
    ASSERT_TRUE(stream.seek(2));
    EXPECT_EQ(stream.getPosition(), 2u);

    std::vector<uint8_t> buffer(4);
    size_t read = stream.readInterleaved(buffer.data(), buffer.size());
    ASSERT_EQ(read, 4u);
    EXPECT_EQ(buffer[0], 0x03);
    EXPECT_EQ(buffer[1], 0x0C);
    EXPECT_EQ(buffer[2], 0x04);
    EXPECT_EQ(buffer[3], 0x0D);

    // Reset
    stream.reset();
    EXPECT_EQ(stream.getPosition(), 0u);
}
