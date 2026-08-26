#include <gtest/gtest.h>
#include "decoder/wav_decoder.h"
#include "decoder/decoder_factory.h"
#include <vector>
#include <cstring>
#include <cmath>

using namespace bitperfect::decoder;

namespace {

/**
 * Helper to build a minimal valid WAV file in memory.
 */
std::vector<uint8_t> createWavFile(uint16_t bitsPerSample, uint32_t sampleRate,
                                    uint16_t channels, const std::vector<uint8_t>& pcmData) {
    uint32_t dataSize = static_cast<uint32_t>(pcmData.size());
    uint32_t fmtChunkSize = 16;
    uint32_t fileSize = 4 + (8 + fmtChunkSize) + (8 + dataSize);

    std::vector<uint8_t> wav;
    wav.reserve(12 + 8 + fmtChunkSize + 8 + dataSize);

    auto writeU16LE = [&](uint16_t v) {
        wav.push_back(v & 0xFF);
        wav.push_back((v >> 8) & 0xFF);
    };
    auto writeU32LE = [&](uint32_t v) {
        wav.push_back(v & 0xFF);
        wav.push_back((v >> 8) & 0xFF);
        wav.push_back((v >> 16) & 0xFF);
        wav.push_back((v >> 24) & 0xFF);
    };

    // RIFF header
    wav.push_back('R'); wav.push_back('I'); wav.push_back('F'); wav.push_back('F');
    writeU32LE(fileSize);
    wav.push_back('W'); wav.push_back('A'); wav.push_back('V'); wav.push_back('E');

    // fmt chunk
    wav.push_back('f'); wav.push_back('m'); wav.push_back('t'); wav.push_back(' ');
    writeU32LE(fmtChunkSize);
    writeU16LE(1);  // PCM format
    writeU16LE(channels);
    writeU32LE(sampleRate);
    uint32_t byteRate = sampleRate * channels * (bitsPerSample / 8);
    writeU32LE(byteRate);
    uint16_t blockAlign = channels * (bitsPerSample / 8);
    writeU16LE(blockAlign);
    writeU16LE(bitsPerSample);

    // data chunk
    wav.push_back('d'); wav.push_back('a'); wav.push_back('t'); wav.push_back('a');
    writeU32LE(dataSize);
    wav.insert(wav.end(), pcmData.begin(), pcmData.end());

    return wav;
}

/**
 * Create a WAV file with a LIST chunk before the data chunk.
 */
std::vector<uint8_t> createWavWithList(uint16_t bitsPerSample, uint32_t sampleRate,
                                        uint16_t channels, const std::vector<uint8_t>& pcmData) {
    uint32_t dataSize = static_cast<uint32_t>(pcmData.size());
    uint32_t fmtChunkSize = 16;
    // LIST chunk with INFO type and INAM subchunk
    const char* listData = "INFO" "INAM" "\x04\x00\x00\x00" "Test";
    uint32_t listSize = 20;  // INFO(4) + INAM(4) + size(4) + data(4) + pad(4)
    // Simpler approach: just 8 bytes of LIST content
    uint32_t listContentSize = 12;  // "INFO" + "INAM" + 4 bytes size

    std::vector<uint8_t> wav;

    auto writeU16LE = [&](uint16_t v) {
        wav.push_back(v & 0xFF);
        wav.push_back((v >> 8) & 0xFF);
    };
    auto writeU32LE = [&](uint32_t v) {
        wav.push_back(v & 0xFF);
        wav.push_back((v >> 8) & 0xFF);
        wav.push_back((v >> 16) & 0xFF);
        wav.push_back((v >> 24) & 0xFF);
    };

    uint32_t fileSize = 4 + (8 + fmtChunkSize) + (8 + 16) + (8 + dataSize);

    // RIFF header
    wav.push_back('R'); wav.push_back('I'); wav.push_back('F'); wav.push_back('F');
    writeU32LE(fileSize);
    wav.push_back('W'); wav.push_back('A'); wav.push_back('V'); wav.push_back('E');

    // fmt chunk
    wav.push_back('f'); wav.push_back('m'); wav.push_back('t'); wav.push_back(' ');
    writeU32LE(fmtChunkSize);
    writeU16LE(1);  // PCM format
    writeU16LE(channels);
    writeU32LE(sampleRate);
    uint32_t byteRate = sampleRate * channels * (bitsPerSample / 8);
    writeU32LE(byteRate);
    uint16_t blockAlign = channels * (bitsPerSample / 8);
    writeU16LE(blockAlign);
    writeU16LE(bitsPerSample);

    // LIST chunk (16 bytes of content)
    wav.push_back('L'); wav.push_back('I'); wav.push_back('S'); wav.push_back('T');
    writeU32LE(16);
    wav.push_back('I'); wav.push_back('N'); wav.push_back('F'); wav.push_back('O');
    wav.push_back('I'); wav.push_back('N'); wav.push_back('A'); wav.push_back('M');
    writeU32LE(4);
    wav.push_back('T'); wav.push_back('e'); wav.push_back('s'); wav.push_back('t');

    // data chunk
    wav.push_back('d'); wav.push_back('a'); wav.push_back('t'); wav.push_back('a');
    writeU32LE(dataSize);
    wav.insert(wav.end(), pcmData.begin(), pcmData.end());

    return wav;
}

} // anonymous namespace

// --- Basic header parsing tests ---

TEST(WavDecoder, ParseValid16BitStereo) {
    // Generate 100 frames of 16-bit stereo silence
    std::vector<uint8_t> pcm(100 * 2 * 2, 0);  // 100 frames * 2 channels * 2 bytes
    auto wav = createWavFile(16, 44100, 2, pcm);

    WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wav.data(), wav.size()));

    AudioFormat fmt = decoder.getFormat();
    EXPECT_EQ(fmt.sampleRate, 44100u);
    EXPECT_EQ(fmt.bitsPerSample, 16);
    EXPECT_EQ(fmt.channels, 2);
    EXPECT_EQ(fmt.totalFrames, 100u);
}

TEST(WavDecoder, ParseValid24BitStereo) {
    // Generate 50 frames of 24-bit stereo
    std::vector<uint8_t> pcm(50 * 2 * 3, 0x42);
    auto wav = createWavFile(24, 96000, 2, pcm);

    WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wav.data(), wav.size()));

    AudioFormat fmt = decoder.getFormat();
    EXPECT_EQ(fmt.sampleRate, 96000u);
    EXPECT_EQ(fmt.bitsPerSample, 24);
    EXPECT_EQ(fmt.channels, 2);
    EXPECT_EQ(fmt.totalFrames, 50u);
}

TEST(WavDecoder, ParseValid32BitMono) {
    // Generate 200 frames of 32-bit mono
    std::vector<uint8_t> pcm(200 * 1 * 4, 0);
    auto wav = createWavFile(32, 192000, 1, pcm);

    WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wav.data(), wav.size()));

    AudioFormat fmt = decoder.getFormat();
    EXPECT_EQ(fmt.sampleRate, 192000u);
    EXPECT_EQ(fmt.bitsPerSample, 32);
    EXPECT_EQ(fmt.channels, 1);
    EXPECT_EQ(fmt.totalFrames, 200u);
}

TEST(WavDecoder, ParseHighSampleRate) {
    std::vector<uint8_t> pcm(10 * 2 * 4, 0);
    auto wav = createWavFile(32, 384000, 2, pcm);

    WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wav.data(), wav.size()));

    AudioFormat fmt = decoder.getFormat();
    EXPECT_EQ(fmt.sampleRate, 384000u);
}

// --- PCM data extraction tests ---

TEST(WavDecoder, ExtractPcm16BitCorrectly) {
    // Create known 16-bit samples: +1000, -1000, +32767, -32768
    std::vector<uint8_t> pcm;
    auto pushS16 = [&](int16_t v) {
        pcm.push_back(v & 0xFF);
        pcm.push_back((v >> 8) & 0xFF);
    };
    pushS16(1000);
    pushS16(-1000);
    pushS16(32767);
    pushS16(-32768);

    auto wav = createWavFile(16, 44100, 1, pcm);

    WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wav.data(), wav.size()));

    uint8_t buffer[8];
    size_t framesRead = decoder.readFromMemory(buffer, 4);
    ASSERT_EQ(framesRead, 4u);

    auto readS16 = [](const uint8_t* p) -> int16_t {
        return static_cast<int16_t>(p[0] | (p[1] << 8));
    };

    EXPECT_EQ(readS16(buffer + 0), 1000);
    EXPECT_EQ(readS16(buffer + 2), -1000);
    EXPECT_EQ(readS16(buffer + 4), 32767);
    EXPECT_EQ(readS16(buffer + 6), -32768);
}

TEST(WavDecoder, ExtractPcm24BitCorrectly) {
    // Create known 24-bit samples
    std::vector<uint8_t> pcm = {
        0x00, 0x00, 0x01,   // +65536
        0x00, 0x00, 0xFF,   // -65536 (sign extended)
        0xFF, 0xFF, 0x7F,   // +8388607 (max positive)
        0x00, 0x00, 0x80    // -8388608 (min negative)
    };

    auto wav = createWavFile(24, 48000, 1, pcm);

    WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wav.data(), wav.size()));

    uint8_t buffer[12];
    size_t framesRead = decoder.readFromMemory(buffer, 4);
    ASSERT_EQ(framesRead, 4u);

    // Verify bytes are preserved exactly
    EXPECT_EQ(memcmp(buffer, pcm.data(), 12), 0);
}

TEST(WavDecoder, ExtractPcm32BitCorrectly) {
    // Create known 32-bit samples
    std::vector<uint8_t> pcm;
    auto pushS32 = [&](int32_t v) {
        pcm.push_back(v & 0xFF);
        pcm.push_back((v >> 8) & 0xFF);
        pcm.push_back((v >> 16) & 0xFF);
        pcm.push_back((v >> 24) & 0xFF);
    };
    pushS32(1000000);
    pushS32(-1000000);
    pushS32(2147483647);   // INT32_MAX
    pushS32(-2147483647);  // INT32_MIN + 1

    auto wav = createWavFile(32, 96000, 1, pcm);

    WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wav.data(), wav.size()));

    uint8_t buffer[16];
    size_t framesRead = decoder.readFromMemory(buffer, 4);
    ASSERT_EQ(framesRead, 4u);

    // Verify bytes are preserved exactly
    EXPECT_EQ(memcmp(buffer, pcm.data(), 16), 0);
}

// --- Edge cases ---

TEST(WavDecoder, HandleListChunkGracefully) {
    std::vector<uint8_t> pcm(100 * 2 * 2, 0x55);
    auto wav = createWavWithList(16, 44100, 2, pcm);

    WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wav.data(), wav.size()));

    AudioFormat fmt = decoder.getFormat();
    EXPECT_EQ(fmt.sampleRate, 44100u);
    EXPECT_EQ(fmt.bitsPerSample, 16);
    EXPECT_EQ(fmt.channels, 2);
    EXPECT_EQ(fmt.totalFrames, 100u);
}

TEST(WavDecoder, RejectInvalidMagic) {
    uint8_t garbage[44] = {0};
    WavDecoder decoder;
    EXPECT_FALSE(decoder.openFromMemory(garbage, 44));
}

TEST(WavDecoder, RejectTooShortData) {
    uint8_t shortData[10] = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A'};
    WavDecoder decoder;
    EXPECT_FALSE(decoder.openFromMemory(shortData, 10));
}

TEST(WavDecoder, RejectNonPcmFormat) {
    // Create a WAV with format=3 (IEEE float)
    std::vector<uint8_t> pcm(100, 0);
    auto wav = createWavFile(32, 44100, 2, pcm);
    // Patch audioFormat to 3 (float)
    wav[20] = 3;
    wav[21] = 0;

    WavDecoder decoder;
    EXPECT_FALSE(decoder.openFromMemory(wav.data(), wav.size()));
}

// --- Seek tests ---

TEST(WavDecoder, SeekToMiddle) {
    // Create 1000 frames of 16-bit mono with incrementing values
    std::vector<uint8_t> pcm;
    for (int i = 0; i < 1000; ++i) {
        int16_t val = static_cast<int16_t>(i);
        pcm.push_back(val & 0xFF);
        pcm.push_back((val >> 8) & 0xFF);
    }
    auto wav = createWavFile(16, 44100, 1, pcm);

    WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wav.data(), wav.size()));

    // Seek to frame 500
    SeekPosition pos;
    pos.frameIndex = 500;
    ASSERT_TRUE(decoder.seek(pos));
    EXPECT_EQ(decoder.getPosition(), 500u);

    // Read one frame
    uint8_t buffer[2];
    size_t framesRead = decoder.readFromMemory(buffer, 1);
    ASSERT_EQ(framesRead, 1u);

    int16_t val = static_cast<int16_t>(buffer[0] | (buffer[1] << 8));
    EXPECT_EQ(val, 500);
}

TEST(WavDecoder, ReadReturnsZeroAtEnd) {
    std::vector<uint8_t> pcm(10 * 2, 0);  // 10 frames of 16-bit mono
    auto wav = createWavFile(16, 44100, 1, pcm);

    WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wav.data(), wav.size()));

    // Read all frames
    uint8_t buffer[20];
    size_t framesRead = decoder.readFromMemory(buffer, 10);
    ASSERT_EQ(framesRead, 10u);

    // Try reading more - should return 0
    framesRead = decoder.readFromMemory(buffer, 10);
    EXPECT_EQ(framesRead, 0u);
}

// --- Duration test ---

TEST(WavDecoder, DurationCalculation) {
    // 44100 frames at 44100 Hz = 1.0 second
    std::vector<uint8_t> pcm(44100 * 2 * 2, 0);  // 44100 frames, stereo, 16-bit
    auto wav = createWavFile(16, 44100, 2, pcm);

    WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wav.data(), wav.size()));

    EXPECT_NEAR(decoder.getDuration(), 1.0, 0.001);
}

// --- Factory test ---

TEST(WavDecoder, FactoryDetectsWavExtension) {
    EXPECT_EQ(DecoderFactory::detectFromExtension("test.wav"), DecoderType::WAV);
    EXPECT_EQ(DecoderFactory::detectFromExtension("test.WAV"), DecoderType::WAV);
    EXPECT_EQ(DecoderFactory::detectFromExtension("test.wave"), DecoderType::WAV);
    EXPECT_EQ(DecoderFactory::detectFromExtension("/path/to/file.wav"), DecoderType::WAV);
}

TEST(WavDecoder, FactoryDetectsWavMagic) {
    std::vector<uint8_t> pcm(10 * 4, 0);
    auto wav = createWavFile(16, 44100, 2, pcm);

    EXPECT_EQ(DecoderFactory::detectFromMagic(wav.data(), wav.size()), DecoderType::WAV);
}
