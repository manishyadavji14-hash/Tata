#include <gtest/gtest.h>
#include "../pcm/pcm_engine.h"
#include <vector>
#include <cstring>
#include <cmath>

using namespace bitperfect::pcm;
using namespace bitperfect::usb;

class PcmEngineTest : public ::testing::Test {
protected:
    void SetUp() override {}
    void TearDown() override {}
};

TEST_F(PcmEngineTest, BytesPerSample) {
    EXPECT_EQ(PcmEngine::getBytesPerSample(PcmFormat::S16_LE), 2);
    EXPECT_EQ(PcmEngine::getBytesPerSample(PcmFormat::S24_3LE), 3);
    EXPECT_EQ(PcmEngine::getBytesPerSample(PcmFormat::S24_LE), 4);
    EXPECT_EQ(PcmEngine::getBytesPerSample(PcmFormat::S32_LE), 4);
    EXPECT_EQ(PcmEngine::getBytesPerSample(PcmFormat::FLOAT_LE), 4);
}

TEST_F(PcmEngineTest, BitsPerSample) {
    EXPECT_EQ(PcmEngine::getBitsPerSample(PcmFormat::S16_LE), 16);
    EXPECT_EQ(PcmEngine::getBitsPerSample(PcmFormat::S24_3LE), 24);
    EXPECT_EQ(PcmEngine::getBitsPerSample(PcmFormat::S24_LE), 24);
    EXPECT_EQ(PcmEngine::getBitsPerSample(PcmFormat::S32_LE), 32);
    EXPECT_EQ(PcmEngine::getBitsPerSample(PcmFormat::FLOAT_LE), 32);
}

TEST_F(PcmEngineTest, Passthrough16Bit) {
    // 16-bit stereo: ensure bit-perfect passthrough
    uint8_t src[] = {0x34, 0x12, 0x78, 0x56, 0xBC, 0x9A, 0xF0, 0xDE};
    uint8_t dst[8];

    size_t result = PcmEngine::passthrough(src, 8, dst, 8);
    EXPECT_EQ(result, 8u);
    EXPECT_EQ(std::memcmp(src, dst, 8), 0);
}

TEST_F(PcmEngineTest, Passthrough24Bit) {
    // 24-bit packed stereo: 6 bytes per frame
    uint8_t src[] = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
                     0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C};
    uint8_t dst[12];

    size_t result = PcmEngine::passthrough(src, 12, dst, 12);
    EXPECT_EQ(result, 12u);
    EXPECT_EQ(std::memcmp(src, dst, 12), 0);
}

TEST_F(PcmEngineTest, Passthrough32Bit) {
    // 32-bit stereo: 8 bytes per frame
    uint8_t src[] = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
    uint8_t dst[8];

    size_t result = PcmEngine::passthrough(src, 8, dst, 8);
    EXPECT_EQ(result, 8u);
    EXPECT_EQ(std::memcmp(src, dst, 8), 0);
}

TEST_F(PcmEngineTest, SameFormatPassthrough) {
    // Converting between same format should be bit-perfect passthrough
    uint8_t src[] = {0xAB, 0xCD, 0xEF, 0x12, 0x34, 0x56, 0x78, 0x9A};
    uint8_t dst[8];

    size_t result = PcmEngine::convert(src, 8, dst, 8, PcmFormat::S16_LE, PcmFormat::S16_LE, 2);
    EXPECT_EQ(result, 8u);
    EXPECT_EQ(std::memcmp(src, dst, 8), 0);
}

TEST_F(PcmEngineTest, Convert16to24_3BitPerfect) {
    // 16-bit sample: 0x1234 (little-endian: 0x34, 0x12)
    // Expected 24-bit: the 16-bit data in upper bits, zero-pad LSB
    // Result: 0x00, 0x34, 0x12
    uint8_t src[] = {0x34, 0x12};  // One mono sample: value 0x1234
    uint8_t dst[3];

    size_t result = PcmEngine::convert(src, 2, dst, 3, PcmFormat::S16_LE, PcmFormat::S24_3LE, 1);
    EXPECT_EQ(result, 3u);
    EXPECT_EQ(dst[0], 0x00);  // Zero-padded LSB
    EXPECT_EQ(dst[1], 0x34);  // Original low byte
    EXPECT_EQ(dst[2], 0x12);  // Original high byte (MSB)
}

TEST_F(PcmEngineTest, Convert16to32BitPerfect) {
    // 16-bit: 0x1234 -> 32-bit: 0x12340000 (little-endian: 0x00, 0x00, 0x34, 0x12)
    uint8_t src[] = {0x34, 0x12};
    uint8_t dst[4];

    size_t result = PcmEngine::convert(src, 2, dst, 4, PcmFormat::S16_LE, PcmFormat::S32_LE, 1);
    EXPECT_EQ(result, 4u);
    EXPECT_EQ(dst[0], 0x00);  // Zero padding
    EXPECT_EQ(dst[1], 0x00);  // Zero padding
    EXPECT_EQ(dst[2], 0x34);  // Original low byte
    EXPECT_EQ(dst[3], 0x12);  // Original high byte
}

TEST_F(PcmEngineTest, Convert24_3to32BitPerfect) {
    // 24-bit packed: 0xAB, 0xCD, 0xEF -> 32-bit: MSB in upper 24 bits
    // Expected: 0x00, 0xAB, 0xCD, 0xEF
    uint8_t src[] = {0xAB, 0xCD, 0xEF};
    uint8_t dst[4];

    size_t result = PcmEngine::convert(src, 3, dst, 4, PcmFormat::S24_3LE, PcmFormat::S32_LE, 1);
    EXPECT_EQ(result, 4u);
    EXPECT_EQ(dst[0], 0x00);  // Zero padding LSB
    EXPECT_EQ(dst[1], 0xAB);  // Original byte 0
    EXPECT_EQ(dst[2], 0xCD);  // Original byte 1
    EXPECT_EQ(dst[3], 0xEF);  // Original byte 2 (MSB)
}

TEST_F(PcmEngineTest, Convert32to24_3BitPerfect) {
    // 32-bit: 0x00, 0xAB, 0xCD, 0xEF -> 24-bit packed: 0xAB, 0xCD, 0xEF
    uint8_t src[] = {0x00, 0xAB, 0xCD, 0xEF};
    uint8_t dst[3];

    size_t result = PcmEngine::convert(src, 4, dst, 3, PcmFormat::S32_LE, PcmFormat::S24_3LE, 1);
    EXPECT_EQ(result, 3u);
    EXPECT_EQ(dst[0], 0xAB);  // Upper 24-bit byte 0
    EXPECT_EQ(dst[1], 0xCD);
    EXPECT_EQ(dst[2], 0xEF);  // MSB
}

TEST_F(PcmEngineTest, Convert24_3to24in32) {
    // 24-bit packed: 0x56, 0x78, 0x12 -> 24-in-32: 0x56, 0x78, 0x12, sign-ext
    uint8_t src[] = {0x56, 0x78, 0x12}; // Positive value (MSB bit 7 = 0)
    uint8_t dst[4];

    size_t result = PcmEngine::convert(src, 3, dst, 4, PcmFormat::S24_3LE, PcmFormat::S24_LE, 1);
    EXPECT_EQ(result, 4u);
    EXPECT_EQ(dst[0], 0x56);
    EXPECT_EQ(dst[1], 0x78);
    EXPECT_EQ(dst[2], 0x12);
    EXPECT_EQ(dst[3], 0x00);  // Sign extension (positive)

    // Negative value
    uint8_t src_neg[] = {0x56, 0x78, 0x92}; // Negative (MSB bit 7 = 1)
    size_t result_neg = PcmEngine::convert(src_neg, 3, dst, 4, PcmFormat::S24_3LE, PcmFormat::S24_LE, 1);
    EXPECT_EQ(result_neg, 4u);
    EXPECT_EQ(dst[0], 0x56);
    EXPECT_EQ(dst[1], 0x78);
    EXPECT_EQ(dst[2], 0x92);
    EXPECT_EQ(dst[3], 0xFF);  // Sign extension (negative)
}

TEST_F(PcmEngineTest, Convert24in32to24_3) {
    // 24-in-32: 0x56, 0x78, 0x12, 0x00 -> 24-bit packed: 0x56, 0x78, 0x12
    uint8_t src[] = {0x56, 0x78, 0x12, 0x00};
    uint8_t dst[3];

    size_t result = PcmEngine::convert(src, 4, dst, 3, PcmFormat::S24_LE, PcmFormat::S24_3LE, 1);
    EXPECT_EQ(result, 3u);
    EXPECT_EQ(dst[0], 0x56);
    EXPECT_EQ(dst[1], 0x78);
    EXPECT_EQ(dst[2], 0x12);
}

TEST_F(PcmEngineTest, StereoConversion) {
    // Stereo 16-bit to 32-bit
    // L: 0x1234, R: 0x5678
    uint8_t src[] = {0x34, 0x12, 0x78, 0x56};
    uint8_t dst[8];

    size_t result = PcmEngine::convert(src, 4, dst, 8, PcmFormat::S16_LE, PcmFormat::S32_LE, 2);
    EXPECT_EQ(result, 8u);

    // Left channel
    EXPECT_EQ(dst[0], 0x00);
    EXPECT_EQ(dst[1], 0x00);
    EXPECT_EQ(dst[2], 0x34);
    EXPECT_EQ(dst[3], 0x12);

    // Right channel
    EXPECT_EQ(dst[4], 0x00);
    EXPECT_EQ(dst[5], 0x00);
    EXPECT_EQ(dst[6], 0x78);
    EXPECT_EQ(dst[7], 0x56);
}

TEST_F(PcmEngineTest, MultiFrameConversion) {
    // Multiple frames of stereo 24-bit to 32-bit
    uint8_t src[] = {
        0x01, 0x02, 0x03,   // L frame 0
        0x04, 0x05, 0x06,   // R frame 0
        0x07, 0x08, 0x09,   // L frame 1
        0x0A, 0x0B, 0x0C    // R frame 1
    };
    uint8_t dst[16];

    size_t result = PcmEngine::convert(src, 12, dst, 16, PcmFormat::S24_3LE, PcmFormat::S32_LE, 2);
    EXPECT_EQ(result, 16u);

    // Verify each sample
    // L0: 0x01, 0x02, 0x03 -> 0x00, 0x01, 0x02, 0x03
    EXPECT_EQ(dst[0], 0x00);
    EXPECT_EQ(dst[1], 0x01);
    EXPECT_EQ(dst[2], 0x02);
    EXPECT_EQ(dst[3], 0x03);

    // R0: 0x04, 0x05, 0x06 -> 0x00, 0x04, 0x05, 0x06
    EXPECT_EQ(dst[4], 0x00);
    EXPECT_EQ(dst[5], 0x04);
    EXPECT_EQ(dst[6], 0x05);
    EXPECT_EQ(dst[7], 0x06);
}

TEST_F(PcmEngineTest, VerifyBitPerfectSameFormat) {
    uint8_t data[] = {0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0};
    EXPECT_TRUE(PcmEngine::verifyBitPerfect(data, 8, data, 8, PcmFormat::S16_LE, PcmFormat::S16_LE, 2));
}

TEST_F(PcmEngineTest, VerifyBitPerfect16to32) {
    // 16-bit -> 32-bit conversion should preserve all 16 bits
    uint8_t src[] = {0x34, 0x12, 0x78, 0x56};  // 2 mono samples
    uint8_t dst[8];

    PcmEngine::convert(src, 4, dst, 8, PcmFormat::S16_LE, PcmFormat::S32_LE, 1);
    EXPECT_TRUE(PcmEngine::verifyBitPerfect(src, 4, dst, 8, PcmFormat::S16_LE, PcmFormat::S32_LE, 1));
}

TEST_F(PcmEngineTest, InsufficientDestBuffer) {
    uint8_t src[] = {0x34, 0x12};
    uint8_t dst[1]; // Too small

    size_t result = PcmEngine::convert(src, 2, dst, 1, PcmFormat::S16_LE, PcmFormat::S32_LE, 1);
    EXPECT_EQ(result, 0u);
}

TEST_F(PcmEngineTest, NullInput) {
    uint8_t dst[8];
    EXPECT_EQ(PcmEngine::convert(nullptr, 4, dst, 8, PcmFormat::S16_LE, PcmFormat::S32_LE, 1), 0u);
    EXPECT_EQ(PcmEngine::passthrough(nullptr, 4, dst, 8), 0u);
}

TEST_F(PcmEngineTest, FormatConfigure) {
    PcmEngine engine;
    PcmFormatInfo info;
    info.format = PcmFormat::S24_3LE;
    info.bitsPerSample = 24;
    info.bytesPerSample = 3;
    info.channels = 2;
    info.sampleRate = 96000;

    engine.configure(info);

    EXPECT_EQ(engine.getFormat().format, PcmFormat::S24_3LE);
    EXPECT_EQ(engine.getFormat().channels, 2);
    EXPECT_EQ(engine.getFormat().sampleRate, 96000u);
    EXPECT_EQ(engine.getFormat().bytesPerFrame(), 6u);
    EXPECT_EQ(engine.getFormat().bytesPerSecond(), 576000u);
}

TEST_F(PcmEngineTest, IsPassthroughCompatible) {
    EXPECT_TRUE(PcmEngine::isPassthroughCompatible(PcmFormat::S16_LE, PcmFormat::S16_LE));
    EXPECT_TRUE(PcmEngine::isPassthroughCompatible(PcmFormat::S24_3LE, PcmFormat::S24_3LE));
    EXPECT_FALSE(PcmEngine::isPassthroughCompatible(PcmFormat::S16_LE, PcmFormat::S24_3LE));
    EXPECT_FALSE(PcmEngine::isPassthroughCompatible(PcmFormat::S24_3LE, PcmFormat::S32_LE));
}

TEST_F(PcmEngineTest, LargeBufferBitPerfect) {
    // Test with a large realistic buffer (1024 stereo 24-bit samples)
    constexpr size_t SAMPLE_COUNT = 1024;
    constexpr size_t CHANNELS = 2;
    std::vector<uint8_t> src(SAMPLE_COUNT * CHANNELS * 3);
    std::vector<uint8_t> dst(SAMPLE_COUNT * CHANNELS * 4);

    // Fill with pseudo-random audio-like data
    for (size_t i = 0; i < src.size(); ++i) {
        src[i] = static_cast<uint8_t>((i * 7 + 13) & 0xFF);
    }

    size_t result = PcmEngine::convert(src.data(), src.size(), dst.data(), dst.size(),
                                        PcmFormat::S24_3LE, PcmFormat::S32_LE, CHANNELS);
    EXPECT_EQ(result, dst.size());

    // Verify bit-perfect: each 3-byte sample should appear in upper 3 bytes of 4-byte output
    for (size_t i = 0; i < SAMPLE_COUNT * CHANNELS; ++i) {
        EXPECT_EQ(dst[i * 4 + 0], 0x00) << "Sample " << i << " padding incorrect";
        EXPECT_EQ(dst[i * 4 + 1], src[i * 3 + 0]) << "Sample " << i << " byte 0 mismatch";
        EXPECT_EQ(dst[i * 4 + 2], src[i * 3 + 1]) << "Sample " << i << " byte 1 mismatch";
        EXPECT_EQ(dst[i * 4 + 3], src[i * 3 + 2]) << "Sample " << i << " byte 2 mismatch";
    }
}
