#include <gtest/gtest.h>
#include "../decoder/wav_decoder.h"
#include "../dsd/dsf_parser.h"
#include "../dop/dop_encoder.h"
#include "../pcm/pcm_engine.h"
#include "../audio/format_detector.h"
#include "../audio/playback_mode.h"
#include "../buffer/ring_buffer.h"
#include <vector>
#include <cstring>
#include <numeric>

using namespace bitperfect;

class FullPipelineTest : public ::testing::Test {
protected:
    void SetUp() override {}
    void TearDown() override {}

    // Helper: build a minimal valid WAV file in memory
    std::vector<uint8_t> buildWavFile(uint32_t sampleRate, uint16_t bitsPerSample,
                                       uint16_t channels, const std::vector<uint8_t>& pcmData) {
        uint32_t bytesPerSample = bitsPerSample / 8;
        uint32_t blockAlign = channels * bytesPerSample;
        uint32_t byteRate = sampleRate * blockAlign;
        uint32_t dataSize = static_cast<uint32_t>(pcmData.size());
        uint32_t fileSize = 36 + dataSize;

        std::vector<uint8_t> wav;
        wav.reserve(44 + pcmData.size());

        // RIFF header
        wav.push_back('R'); wav.push_back('I'); wav.push_back('F'); wav.push_back('F');
        pushU32LE(wav, fileSize);
        wav.push_back('W'); wav.push_back('A'); wav.push_back('V'); wav.push_back('E');

        // fmt chunk
        wav.push_back('f'); wav.push_back('m'); wav.push_back('t'); wav.push_back(' ');
        pushU32LE(wav, 16); // chunk size
        pushU16LE(wav, 1);  // PCM format
        pushU16LE(wav, channels);
        pushU32LE(wav, sampleRate);
        pushU32LE(wav, byteRate);
        pushU16LE(wav, static_cast<uint16_t>(blockAlign));
        pushU16LE(wav, bitsPerSample);

        // data chunk
        wav.push_back('d'); wav.push_back('a'); wav.push_back('t'); wav.push_back('a');
        pushU32LE(wav, dataSize);
        wav.insert(wav.end(), pcmData.begin(), pcmData.end());

        return wav;
    }

    // Helper: build a minimal DSF file header + data
    std::vector<uint8_t> buildDsfFile(uint32_t sampleRate, uint32_t channels,
                                       uint32_t blockSizePerChannel,
                                       const std::vector<uint8_t>& dsdData) {
        std::vector<uint8_t> dsf;
        uint64_t dataChunkSize = 12 + dsdData.size(); // 12 byte header + data
        uint64_t totalFileSize = 28 + 52 + dataChunkSize; // DSD + fmt + data chunks
        uint64_t sampleCount = dsdData.size() / channels * 8; // 8 bits per byte

        // DSD chunk (28 bytes)
        dsf.push_back('D'); dsf.push_back('S'); dsf.push_back('D'); dsf.push_back(' ');
        pushU64LE(dsf, 28); // chunk size
        pushU64LE(dsf, totalFileSize);
        pushU64LE(dsf, 0);  // metadata offset

        // fmt chunk (52 bytes total)
        dsf.push_back('f'); dsf.push_back('m'); dsf.push_back('t'); dsf.push_back(' ');
        pushU64LE(dsf, 52); // chunk size
        pushU32LE(dsf, 1);  // format version
        pushU32LE(dsf, 0);  // format ID (DSD raw)
        pushU32LE(dsf, channels == 1 ? 1 : 2); // channel type
        pushU32LE(dsf, channels);
        pushU32LE(dsf, sampleRate);
        pushU32LE(dsf, 1);  // bits per sample
        pushU64LE(dsf, sampleCount);
        pushU32LE(dsf, blockSizePerChannel);
        pushU32LE(dsf, 0);  // reserved (padding to reach 52 bytes)

        // data chunk
        dsf.push_back('d'); dsf.push_back('a'); dsf.push_back('t'); dsf.push_back('a');
        pushU64LE(dsf, dataChunkSize);
        dsf.insert(dsf.end(), dsdData.begin(), dsdData.end());

        return dsf;
    }

    static void pushU16LE(std::vector<uint8_t>& v, uint16_t val) {
        v.push_back(val & 0xFF);
        v.push_back((val >> 8) & 0xFF);
    }

    static void pushU32LE(std::vector<uint8_t>& v, uint32_t val) {
        v.push_back(val & 0xFF);
        v.push_back((val >> 8) & 0xFF);
        v.push_back((val >> 16) & 0xFF);
        v.push_back((val >> 24) & 0xFF);
    }

    static void pushU64LE(std::vector<uint8_t>& v, uint64_t val) {
        for (int i = 0; i < 8; ++i) {
            v.push_back(static_cast<uint8_t>((val >> (i * 8)) & 0xFF));
        }
    }
};

// === WAV Pipeline: WAV decode -> PCM format -> bit-perfect output ===

TEST_F(FullPipelineTest, WavDecodeBitPerfect16bit) {
    // Create known PCM data
    std::vector<uint8_t> originalPcm(1000);
    for (size_t i = 0; i < originalPcm.size(); ++i) {
        originalPcm[i] = static_cast<uint8_t>((i * 7 + 13) & 0xFF);
    }

    // Build WAV file
    auto wavData = buildWavFile(44100, 16, 2, originalPcm);

    // Decode using WavDecoder
    decoder::WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wavData.data(), wavData.size()));

    auto format = decoder.getFormat();
    EXPECT_EQ(format.sampleRate, 44100u);
    EXPECT_EQ(format.bitsPerSample, 16u);
    EXPECT_EQ(format.channels, 2u);

    // Read all frames
    uint32_t frameSize = format.bytesPerFrame();
    size_t totalFrames = originalPcm.size() / frameSize;
    std::vector<uint8_t> decodedPcm(originalPcm.size());
    size_t framesRead = decoder.readFromMemory(decodedPcm.data(), totalFrames);

    EXPECT_EQ(framesRead, totalFrames);

    // Verify bit-perfect: decoded output matches original PCM data
    EXPECT_EQ(decodedPcm, originalPcm);
}

TEST_F(FullPipelineTest, WavDecodeBitPerfect24bit) {
    // 24-bit, stereo, 96kHz
    std::vector<uint8_t> originalPcm(600); // 100 frames * 6 bytes/frame
    for (size_t i = 0; i < originalPcm.size(); ++i) {
        originalPcm[i] = static_cast<uint8_t>((i * 11 + 3) & 0xFF);
    }

    auto wavData = buildWavFile(96000, 24, 2, originalPcm);

    decoder::WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wavData.data(), wavData.size()));

    auto format = decoder.getFormat();
    EXPECT_EQ(format.sampleRate, 96000u);
    EXPECT_EQ(format.bitsPerSample, 24u);
    EXPECT_EQ(format.channels, 2u);

    uint32_t frameSize = format.bytesPerFrame();
    size_t totalFrames = originalPcm.size() / frameSize;
    std::vector<uint8_t> decoded(originalPcm.size());
    size_t framesRead = decoder.readFromMemory(decoded.data(), totalFrames);

    EXPECT_EQ(framesRead, totalFrames);
    EXPECT_EQ(decoded, originalPcm);
}

TEST_F(FullPipelineTest, WavDecodeThroughPcmPassthrough) {
    // Full pipeline: WAV -> decode -> PCM passthrough -> output
    std::vector<uint8_t> originalPcm(512);
    for (size_t i = 0; i < originalPcm.size(); ++i) {
        originalPcm[i] = static_cast<uint8_t>(i & 0xFF);
    }

    auto wavData = buildWavFile(48000, 16, 2, originalPcm);

    decoder::WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wavData.data(), wavData.size()));

    size_t totalFrames = originalPcm.size() / decoder.getFormat().bytesPerFrame();
    std::vector<uint8_t> decoded(originalPcm.size());
    decoder.readFromMemory(decoded.data(), totalFrames);

    // Run through PCM passthrough (format match = no conversion)
    std::vector<uint8_t> usbOutput(originalPcm.size());
    size_t passthroughBytes = pcm::PcmEngine::passthrough(
        decoded.data(), decoded.size(), usbOutput.data(), usbOutput.size());

    EXPECT_EQ(passthroughBytes, originalPcm.size());
    EXPECT_EQ(usbOutput, originalPcm);
}

TEST_F(FullPipelineTest, WavDecodeWithRingBuffer) {
    // Full pipeline: WAV -> decode -> ring buffer -> read -> verify bit-perfect
    std::vector<uint8_t> originalPcm(2048);
    for (size_t i = 0; i < originalPcm.size(); ++i) {
        originalPcm[i] = static_cast<uint8_t>((i * 3) & 0xFF);
    }

    auto wavData = buildWavFile(44100, 16, 2, originalPcm);

    decoder::WavDecoder decoder;
    ASSERT_TRUE(decoder.openFromMemory(wavData.data(), wavData.size()));

    // Decode
    size_t totalFrames = originalPcm.size() / decoder.getFormat().bytesPerFrame();
    std::vector<uint8_t> decoded(originalPcm.size());
    decoder.readFromMemory(decoded.data(), totalFrames);

    // Write to ring buffer
    buffer::RingBuffer ring(4096);
    size_t written = ring.write(decoded.data(), decoded.size());
    EXPECT_EQ(written, decoded.size());

    // Read from ring buffer
    std::vector<uint8_t> output(decoded.size());
    size_t readBytes = ring.read(output.data(), output.size());
    EXPECT_EQ(readBytes, decoded.size());

    // Verify bit-perfect
    EXPECT_EQ(output, originalPcm);
}

// === DSF Pipeline: DSF parse -> DSD extract -> DoP encode -> verify ===

TEST_F(FullPipelineTest, DsfParseToDopEncode) {
    // Create DSD data (2 channels, interleaved blocks)
    const uint32_t blockSize = 4096;
    const uint32_t channels = 2;
    std::vector<uint8_t> dsdData(blockSize * channels);
    for (size_t i = 0; i < dsdData.size(); ++i) {
        dsdData[i] = static_cast<uint8_t>(i & 0xFF);
    }

    auto dsfFile = buildDsfFile(dsd::DSD64_RATE, channels, blockSize, dsdData);

    // Parse DSF
    dsd::DsfParser parser;
    ASSERT_TRUE(parser.parse(dsfFile.data(), dsfFile.size()));
    EXPECT_EQ(parser.getSampleRate(), dsd::DSD64_RATE);
    EXPECT_EQ(parser.getChannelCount(), 2u);
    EXPECT_EQ(parser.getBlockSizePerChannel(), blockSize);

    // Extract channel data
    std::vector<std::vector<uint8_t>> channelData;
    ASSERT_TRUE(parser.extractChannelData(dsdData.data(), dsdData.size(), channelData));
    ASSERT_EQ(channelData.size(), 2u);
    EXPECT_EQ(channelData[0].size(), blockSize);
    EXPECT_EQ(channelData[1].size(), blockSize);

    // DoP encode left channel
    dop::DopEncoder encoder;
    encoder.configure(2, dsd::DSD64_RATE);
    EXPECT_EQ(encoder.getTransportRate(), dop::DOP_RATE_DSD64);

    size_t dopOutputSize = dop::DopEncoder::calculateOutputSize(channelData[0].size());
    std::vector<uint8_t> dopOutput(dopOutputSize);
    size_t dopWritten = encoder.encode(channelData[0].data(), channelData[0].size(),
                                        dopOutput.data(), dopOutput.size());
    EXPECT_EQ(dopWritten, dopOutputSize);

    // Verify DoP markers are correct
    size_t numFrames = channelData[0].size() / 2;
    for (size_t i = 0; i < std::min(numFrames, (size_t)100); ++i) {
        uint8_t expectedMarker = (i % 2 == 0) ? dop::DOP_MARKER_A : dop::DOP_MARKER_B;
        EXPECT_EQ(dopOutput[i * 3 + 2], expectedMarker);
    }
}

TEST_F(FullPipelineTest, DsfDopRoundTrip) {
    // Verify: DSD data -> DoP encode -> extract DSD from DoP -> matches original
    std::vector<uint8_t> originalDsd(500);
    for (size_t i = 0; i < originalDsd.size(); ++i) {
        originalDsd[i] = static_cast<uint8_t>((i * 17 + 5) & 0xFF);
    }

    // DoP encode
    dop::DopEncoder encoder;
    encoder.configure(2, dsd::DSD64_RATE);

    size_t dopSize = dop::DopEncoder::calculateOutputSize(originalDsd.size());
    std::vector<uint8_t> dopOutput(dopSize);
    size_t written = encoder.encode(originalDsd.data(), originalDsd.size(),
                                     dopOutput.data(), dopOutput.size());
    ASSERT_EQ(written, dopSize);

    // Decode DoP back to DSD
    size_t numFrames = originalDsd.size() / 2;
    std::vector<uint8_t> reconstructed;
    reconstructed.reserve(originalDsd.size());
    for (size_t i = 0; i < numFrames; ++i) {
        reconstructed.push_back(dopOutput[i * 3 + 1]); // upper/first DSD byte
        reconstructed.push_back(dopOutput[i * 3 + 0]); // lower/second DSD byte
    }

    ASSERT_EQ(reconstructed.size(), originalDsd.size());
    EXPECT_EQ(reconstructed, originalDsd);
}

TEST_F(FullPipelineTest, DsfStereoDoP) {
    // Full stereo pipeline: L/R DSD -> DoP encode stereo -> extract -> verify
    std::vector<uint8_t> leftDsd(200);
    std::vector<uint8_t> rightDsd(200);
    for (size_t i = 0; i < 200; ++i) {
        leftDsd[i] = static_cast<uint8_t>(i & 0xFF);
        rightDsd[i] = static_cast<uint8_t>((255 - i) & 0xFF);
    }

    dop::DopEncoder encoder;
    encoder.configure(2, dsd::DSD64_RATE);

    // Encode stereo
    size_t stereoOutputSize = (leftDsd.size() / 2) * 6; // 2 channels * 3 bytes per DoP frame
    std::vector<uint8_t> stereoDoP(stereoOutputSize);
    size_t written = encoder.encodeStereo(leftDsd.data(), rightDsd.data(),
                                           leftDsd.size(), stereoDoP.data(), stereoDoP.size());
    ASSERT_EQ(written, stereoOutputSize);

    // Extract and verify left channel
    size_t numFrames = leftDsd.size() / 2;
    for (size_t i = 0; i < numFrames; ++i) {
        size_t offset = i * 6; // Each stereo frame pair is 6 bytes
        uint8_t leftUpper = stereoDoP[offset + 1];
        uint8_t leftLower = stereoDoP[offset + 0];
        EXPECT_EQ(leftUpper, leftDsd[i * 2]);
        EXPECT_EQ(leftLower, leftDsd[i * 2 + 1]);

        uint8_t rightUpper = stereoDoP[offset + 4];
        uint8_t rightLower = stereoDoP[offset + 3];
        EXPECT_EQ(rightUpper, rightDsd[i * 2]);
        EXPECT_EQ(rightLower, rightDsd[i * 2 + 1]);
    }
}

// === Format Detection -> Mode Selection Pipeline ===

TEST_F(FullPipelineTest, FormatDetectionWav) {
    std::vector<uint8_t> pcmData(100, 0xAA);
    auto wavData = buildWavFile(44100, 16, 2, pcmData);

    audio::FormatDetector detector;
    auto info = detector.detect(wavData.data(), wavData.size());

    EXPECT_TRUE(info.isValid);
    EXPECT_EQ(info.fileType, audio::AudioFileType::WAV);
    EXPECT_EQ(info.contentType, audio::AudioContentType::PCM);
    EXPECT_EQ(info.sampleRate, 44100u);
    EXPECT_EQ(info.bitDepth, 16u);
    EXPECT_EQ(info.channels, 2u);
}

TEST_F(FullPipelineTest, FormatDetectionDsf) {
    std::vector<uint8_t> dsdData(8192, 0x55);
    auto dsfData = buildDsfFile(dsd::DSD64_RATE, 2, 4096, dsdData);

    audio::FormatDetector detector;
    auto info = detector.detect(dsfData.data(), dsfData.size());

    EXPECT_TRUE(info.isValid);
    EXPECT_EQ(info.fileType, audio::AudioFileType::DSF);
    EXPECT_EQ(info.contentType, audio::AudioContentType::DSD);
    EXPECT_EQ(info.sampleRate, dsd::DSD64_RATE);
}

TEST_F(FullPipelineTest, ModeSelectionPcmSource) {
    audio::FormatInfo sourceInfo;
    sourceInfo.fileType = audio::AudioFileType::WAV;
    sourceInfo.contentType = audio::AudioContentType::PCM;
    sourceInfo.sampleRate = 96000;
    sourceInfo.bitDepth = 24;
    sourceInfo.channels = 2;
    sourceInfo.isValid = true;

    audio::DacCapabilities dacCaps;
    dacCaps.pcmSampleRates = {44100, 48000, 96000, 192000};
    dacCaps.pcmBitDepths = {16, 24, 32};
    dacCaps.supportsNativeDsd = false;
    dacCaps.supportsDop = true;

    audio::PlaybackModeSelector selector;
    auto result = selector.selectMode(sourceInfo, dacCaps);

    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, audio::PlaybackMode::PCM);
    EXPECT_EQ(result.transportRate, 96000u);
    EXPECT_EQ(result.bitDepth, 24u);
}

TEST_F(FullPipelineTest, ModeSelectionDsdSourceWithNativeDsd) {
    audio::FormatInfo sourceInfo;
    sourceInfo.fileType = audio::AudioFileType::DSF;
    sourceInfo.contentType = audio::AudioContentType::DSD;
    sourceInfo.sampleRate = dsd::DSD64_RATE;
    sourceInfo.bitDepth = 1;
    sourceInfo.channels = 2;
    sourceInfo.isValid = true;

    audio::DacCapabilities dacCaps;
    dacCaps.supportsNativeDsd = true;
    dacCaps.nativeDsdRates = {dsd::DSD64_RATE, dsd::DSD128_RATE};
    dacCaps.supportsDop = true;
    dacCaps.pcmSampleRates = {44100, 96000, 176400};
    dacCaps.pcmBitDepths = {16, 24};

    audio::PlaybackModeSelector selector;
    auto result = selector.selectMode(sourceInfo, dacCaps);

    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, audio::PlaybackMode::NATIVE_DSD);
}

TEST_F(FullPipelineTest, ModeSelectionDsdSourceDoPFallback) {
    audio::FormatInfo sourceInfo;
    sourceInfo.fileType = audio::AudioFileType::DSF;
    sourceInfo.contentType = audio::AudioContentType::DSD;
    sourceInfo.sampleRate = dsd::DSD64_RATE;
    sourceInfo.bitDepth = 1;
    sourceInfo.channels = 2;
    sourceInfo.isValid = true;

    audio::DacCapabilities dacCaps;
    dacCaps.supportsNativeDsd = false;  // No native DSD
    dacCaps.supportsDop = true;          // But supports DoP
    dacCaps.pcmSampleRates = {44100, 96000, 176400};
    dacCaps.pcmBitDepths = {16, 24};

    audio::PlaybackModeSelector selector;
    auto result = selector.selectMode(sourceInfo, dacCaps);

    EXPECT_TRUE(result.valid);
    EXPECT_EQ(result.mode, audio::PlaybackMode::DOP);
    EXPECT_EQ(result.transportRate, dop::DOP_RATE_DSD64);
}

TEST_F(FullPipelineTest, NoDsdToPcmConversionInBitPerfectMode) {
    // Critical: When DSD source is selected for DoP or Native DSD,
    // there must NOT be an accidental DSD->PCM conversion path.
    // Verify that DSD content type always routes to DSD transport modes.

    audio::FormatInfo dsdSource;
    dsdSource.fileType = audio::AudioFileType::DSF;
    dsdSource.contentType = audio::AudioContentType::DSD;
    dsdSource.sampleRate = dsd::DSD64_RATE;
    dsdSource.bitDepth = 1;
    dsdSource.channels = 2;
    dsdSource.isValid = true;

    // DAC supports both native DSD and DoP
    audio::DacCapabilities fullCaps;
    fullCaps.supportsNativeDsd = true;
    fullCaps.nativeDsdRates = {dsd::DSD64_RATE};
    fullCaps.supportsDop = true;
    fullCaps.pcmSampleRates = {44100, 176400};
    fullCaps.pcmBitDepths = {16, 24};

    audio::PlaybackModeSelector selector;
    auto result = selector.selectMode(dsdSource, fullCaps);

    EXPECT_TRUE(result.valid);
    // Must NOT be PCM when DSD content is detected
    EXPECT_NE(result.mode, audio::PlaybackMode::PCM);
}

// === End-to-end PCM verification ===

TEST_F(FullPipelineTest, EndToEndPcm16Bit44100) {
    // Complete pipeline: create WAV -> decode -> buffer -> read -> verify matches input
    const size_t numSamples = 500; // 500 stereo frames
    std::vector<uint8_t> pcmData(numSamples * 4); // 16-bit stereo = 4 bytes/frame
    for (size_t i = 0; i < pcmData.size(); ++i) {
        pcmData[i] = static_cast<uint8_t>((i * 37) & 0xFF);
    }

    auto wavFile = buildWavFile(44100, 16, 2, pcmData);

    // Step 1: Decode
    decoder::WavDecoder wavDecoder;
    ASSERT_TRUE(wavDecoder.openFromMemory(wavFile.data(), wavFile.size()));

    std::vector<uint8_t> decoded(pcmData.size());
    size_t framesRead = wavDecoder.readFromMemory(decoded.data(), numSamples);
    ASSERT_EQ(framesRead, numSamples);

    // Step 2: PCM passthrough (same format)
    std::vector<uint8_t> processed(decoded.size());
    size_t processedBytes = pcm::PcmEngine::passthrough(
        decoded.data(), decoded.size(), processed.data(), processed.size());
    ASSERT_EQ(processedBytes, decoded.size());

    // Step 3: Buffer
    buffer::RingBuffer ring(8192);
    ring.write(processed.data(), processedBytes);

    std::vector<uint8_t> finalOutput(processedBytes);
    ring.read(finalOutput.data(), processedBytes);

    // Step 4: Verify bit-perfect
    EXPECT_EQ(finalOutput, pcmData);
}

TEST_F(FullPipelineTest, FormatDetectorExtension) {
    EXPECT_EQ(audio::FormatDetector::detectFromExtension("test.wav"), audio::AudioFileType::WAV);
    EXPECT_EQ(audio::FormatDetector::detectFromExtension("test.flac"), audio::AudioFileType::FLAC);
    EXPECT_EQ(audio::FormatDetector::detectFromExtension("test.dsf"), audio::AudioFileType::DSF);
    EXPECT_EQ(audio::FormatDetector::detectFromExtension("test.mp3"), audio::AudioFileType::UNKNOWN);
}

TEST_F(FullPipelineTest, IsDsdFormat) {
    EXPECT_TRUE(audio::FormatDetector::isDsdFormat(audio::AudioFileType::DSF));
    EXPECT_TRUE(audio::FormatDetector::isDsdFormat(audio::AudioFileType::DFF));
    EXPECT_FALSE(audio::FormatDetector::isDsdFormat(audio::AudioFileType::WAV));
    EXPECT_FALSE(audio::FormatDetector::isDsdFormat(audio::AudioFileType::FLAC));
}
