#include <gtest/gtest.h>
#include "../dop/dop_encoder.h"
#include <vector>
#include <cstring>
#include <numeric>

using namespace bitperfect::dop;

class DopEncoderTest : public ::testing::Test {
protected:
    DopEncoder encoder;

    void SetUp() override {
        encoder.configure(2, 2822400); // Stereo DSD64
    }

    void TearDown() override {}
};

// === Marker Sequence Verification ===

TEST_F(DopEncoderTest, MarkerSequenceAlternates) {
    // Each DoP frame should have alternating markers 0x05, 0xFA
    std::vector<uint8_t> dsd(20, 0xAA); // 10 frames worth of DSD
    std::vector<uint8_t> dop(30); // 10 frames * 3 bytes

    size_t written = encoder.encode(dsd.data(), dsd.size(), dop.data(), dop.size());
    ASSERT_EQ(written, 30u); // 10 DSD byte pairs -> 10 DoP frames -> 30 bytes

    // Check markers at byte 2 of each 3-byte frame
    for (size_t i = 0; i < 10; ++i) {
        uint8_t expected = (i % 2 == 0) ? DOP_MARKER_A : DOP_MARKER_B;
        EXPECT_EQ(dop[i * 3 + 2], expected)
            << "Frame " << i << " marker incorrect";
    }
}

TEST_F(DopEncoderTest, MarkerStartsAt0x05) {
    std::vector<uint8_t> dsd = {0x11, 0x22};
    std::vector<uint8_t> dop(3);

    size_t written = encoder.encode(dsd.data(), dsd.size(), dop.data(), dop.size());
    ASSERT_EQ(written, 3u);
    EXPECT_EQ(dop[2], DOP_MARKER_A); // First frame marker is 0x05
}

TEST_F(DopEncoderTest, MarkerSequenceLongStream) {
    // Verify marker alternation over 10000 frames
    std::vector<uint8_t> dsd(20000, 0x55);
    std::vector<uint8_t> dop(30000);

    size_t written = encoder.encode(dsd.data(), dsd.size(), dop.data(), dop.size());
    ASSERT_EQ(written, 30000u);

    for (size_t i = 0; i < 10000; ++i) {
        uint8_t expected = (i % 2 == 0) ? DOP_MARKER_A : DOP_MARKER_B;
        EXPECT_EQ(dop[i * 3 + 2], expected)
            << "Marker mismatch at frame " << i;
    }
}

// === Payload Preservation ===

TEST_F(DopEncoderTest, PayloadPreservation) {
    // DSD bytes should be preserved in DoP frame payload
    std::vector<uint8_t> dsd = {0xAB, 0xCD, 0xEF, 0x12};
    std::vector<uint8_t> dop(6);

    size_t written = encoder.encode(dsd.data(), dsd.size(), dop.data(), dop.size());
    ASSERT_EQ(written, 6u);

    // Frame 0: DSD bytes 0xAB (first/upper), 0xCD (second/lower)
    EXPECT_EQ(dop[0], 0xCD); // LSB = second DSD byte
    EXPECT_EQ(dop[1], 0xAB); // middle = first DSD byte
    EXPECT_EQ(dop[2], DOP_MARKER_A); // MSB = marker

    // Frame 1: DSD bytes 0xEF (first/upper), 0x12 (second/lower)
    EXPECT_EQ(dop[3], 0x12); // LSB = second DSD byte
    EXPECT_EQ(dop[4], 0xEF); // middle = first DSD byte
    EXPECT_EQ(dop[5], DOP_MARKER_B); // MSB = marker
}

TEST_F(DopEncoderTest, ByteForByteReconstruction) {
    // Encode and then extract DSD data from DoP frames
    // Verify original DSD data can be perfectly reconstructed
    std::vector<uint8_t> original(1000);
    for (size_t i = 0; i < original.size(); ++i) {
        original[i] = static_cast<uint8_t>(i & 0xFF);
    }

    std::vector<uint8_t> dop(1500); // 500 frames * 3 bytes
    size_t written = encoder.encode(original.data(), original.size(), dop.data(), dop.size());
    ASSERT_EQ(written, 1500u);

    // Reconstruct DSD from DoP
    std::vector<uint8_t> reconstructed;
    for (size_t i = 0; i < 500; ++i) {
        reconstructed.push_back(dop[i * 3 + 1]); // first/upper DSD byte
        reconstructed.push_back(dop[i * 3 + 0]); // second/lower DSD byte
    }

    ASSERT_EQ(reconstructed.size(), original.size());
    EXPECT_EQ(reconstructed, original);
}

// === One-buffer vs Many-buffer Equivalence ===

TEST_F(DopEncoderTest, OneBufferVsManyBufferEquivalence) {
    std::vector<uint8_t> dsd(100);
    for (size_t i = 0; i < dsd.size(); ++i) {
        dsd[i] = static_cast<uint8_t>((i * 7 + 13) & 0xFF);
    }

    // Encode all at once
    DopEncoder encoder1;
    encoder1.configure(2, 2822400);
    std::vector<uint8_t> dopOnce(150);
    size_t writtenOnce = encoder1.encode(dsd.data(), dsd.size(), dopOnce.data(), dopOnce.size());

    // Encode in many small chunks (2 bytes at a time)
    DopEncoder encoder2;
    encoder2.configure(2, 2822400);
    std::vector<uint8_t> dopMany;
    for (size_t i = 0; i < dsd.size(); i += 2) {
        uint8_t frame[3];
        size_t w = encoder2.encode(dsd.data() + i, 2, frame, 3);
        ASSERT_EQ(w, 3u);
        dopMany.insert(dopMany.end(), frame, frame + 3);
    }

    ASSERT_EQ(writtenOnce, dopMany.size());
    EXPECT_EQ(std::vector<uint8_t>(dopOnce.begin(), dopOnce.begin() + writtenOnce), dopMany);
}

TEST_F(DopEncoderTest, OneByteAtATimeEquivalence) {
    // Process N bytes at once vs 1 byte at a time should give same output
    std::vector<uint8_t> dsd(50);
    for (size_t i = 0; i < dsd.size(); ++i) {
        dsd[i] = static_cast<uint8_t>(i * 3 + 1);
    }

    // All at once
    DopEncoder encoderAll;
    encoderAll.configure(2, 2822400);
    std::vector<uint8_t> dopAll(75);
    size_t writtenAll = encoderAll.encode(dsd.data(), dsd.size(), dopAll.data(), dopAll.size());

    // One byte at a time (uses pending byte mechanism)
    DopEncoder encoderOne;
    encoderOne.configure(2, 2822400);
    std::vector<uint8_t> dopOne;
    for (size_t i = 0; i < dsd.size(); ++i) {
        uint8_t frame[3];
        size_t w = encoderOne.encode(dsd.data() + i, 1, frame, 3);
        if (w > 0) {
            dopOne.insert(dopOne.end(), frame, frame + w);
        }
    }

    ASSERT_EQ(writtenAll, dopOne.size());
    EXPECT_EQ(std::vector<uint8_t>(dopAll.begin(), dopAll.begin() + writtenAll), dopOne);
}

// === Irregular Chunk Sizes ===

TEST_F(DopEncoderTest, IrregularChunkSizes) {
    std::vector<uint8_t> dsd(100);
    for (size_t i = 0; i < dsd.size(); ++i) {
        dsd[i] = static_cast<uint8_t>(i);
    }

    // Encode with regular 2-byte chunks as reference
    DopEncoder refEncoder;
    refEncoder.configure(2, 2822400);
    std::vector<uint8_t> dopRef(150);
    size_t refWritten = refEncoder.encode(dsd.data(), dsd.size(), dopRef.data(), dopRef.size());

    // Encode with irregular chunks: 3, 7, 1, 11, 5, ...
    DopEncoder irregEncoder;
    irregEncoder.configure(2, 2822400);
    std::vector<uint8_t> dopIrreg;
    size_t chunks[] = {3, 7, 1, 11, 5, 13, 2, 9, 4, 6, 8, 15, 16};
    size_t pos = 0;
    for (size_t chunk : chunks) {
        if (pos >= dsd.size()) break;
        size_t toProcess = std::min(chunk, dsd.size() - pos);
        uint8_t buf[75];
        size_t w = irregEncoder.encode(dsd.data() + pos, toProcess, buf, sizeof(buf));
        if (w > 0) {
            dopIrreg.insert(dopIrreg.end(), buf, buf + w);
        }
        pos += toProcess;
    }

    ASSERT_EQ(refWritten, dopIrreg.size());
    EXPECT_EQ(std::vector<uint8_t>(dopRef.begin(), dopRef.begin() + refWritten), dopIrreg);
}

// === Stereo Ordering ===

TEST_F(DopEncoderTest, StereoOrdering) {
    // Left and right channels should be independently encoded
    std::vector<uint8_t> leftDsd = {0x11, 0x22, 0x33, 0x44};
    std::vector<uint8_t> rightDsd = {0xAA, 0xBB, 0xCC, 0xDD};
    std::vector<uint8_t> dop(12); // 2 stereo frames * 6 bytes

    size_t written = encoder.encodeStereo(leftDsd.data(), rightDsd.data(),
                                           leftDsd.size(), dop.data(), dop.size());
    ASSERT_EQ(written, 12u);

    // Frame 0 Left: [0x22 (lower)] [0x11 (upper)] [marker_A]
    EXPECT_EQ(dop[0], 0x22);
    EXPECT_EQ(dop[1], 0x11);
    EXPECT_EQ(dop[2], DOP_MARKER_A);

    // Frame 0 Right: [0xBB (lower)] [0xAA (upper)] [marker_A]
    EXPECT_EQ(dop[3], 0xBB);
    EXPECT_EQ(dop[4], 0xAA);
    EXPECT_EQ(dop[5], DOP_MARKER_A);

    // Frame 1 Left: [0x44 (lower)] [0x33 (upper)] [marker_B]
    EXPECT_EQ(dop[6], 0x44);
    EXPECT_EQ(dop[7], 0x33);
    EXPECT_EQ(dop[8], DOP_MARKER_B);

    // Frame 1 Right: [0xDD (lower)] [0xCC (upper)] [marker_B]
    EXPECT_EQ(dop[9], 0xDD);
    EXPECT_EQ(dop[10], 0xCC);
    EXPECT_EQ(dop[11], DOP_MARKER_B);
}

// === Reset Behavior ===

TEST_F(DopEncoderTest, ResetRestoresMarkerState) {
    std::vector<uint8_t> dsd = {0x11, 0x22};
    std::vector<uint8_t> dop(3);

    // Encode one frame (marker should now be B)
    encoder.encode(dsd.data(), dsd.size(), dop.data(), dop.size());
    EXPECT_FALSE(encoder.getMarkerState()); // Next would be B

    // Reset
    encoder.reset();
    EXPECT_TRUE(encoder.getMarkerState()); // Back to A

    // Next frame should start with marker A again
    encoder.encode(dsd.data(), dsd.size(), dop.data(), dop.size());
    EXPECT_EQ(dop[2], DOP_MARKER_A);
}

TEST_F(DopEncoderTest, ResetClearsPendingByte) {
    std::vector<uint8_t> dsd = {0x11}; // Odd byte -> pending
    std::vector<uint8_t> dop(3);

    size_t written = encoder.encode(dsd.data(), dsd.size(), dop.data(), dop.size());
    EXPECT_EQ(written, 0u); // No complete frame yet
    EXPECT_TRUE(encoder.hasPendingByte());

    encoder.reset();
    EXPECT_FALSE(encoder.hasPendingByte());
}

TEST_F(DopEncoderTest, ResetClearsFrameCount) {
    std::vector<uint8_t> dsd(10, 0xAA);
    std::vector<uint8_t> dop(15);

    encoder.encode(dsd.data(), dsd.size(), dop.data(), dop.size());
    EXPECT_EQ(encoder.getTotalFramesEncoded(), 5u);

    encoder.reset();
    EXPECT_EQ(encoder.getTotalFramesEncoded(), 0u);
}

// === Buffer Boundary Crossing ===

TEST_F(DopEncoderTest, MarkerStatePersistsAcrossBufferBoundaries) {
    std::vector<uint8_t> dsd1 = {0xAA, 0xBB}; // Frame 0
    std::vector<uint8_t> dsd2 = {0xCC, 0xDD}; // Frame 1
    std::vector<uint8_t> dsd3 = {0xEE, 0xFF}; // Frame 2
    std::vector<uint8_t> dop(3);

    // Frame 0 -> marker A
    encoder.encode(dsd1.data(), dsd1.size(), dop.data(), dop.size());
    EXPECT_EQ(dop[2], DOP_MARKER_A);

    // Frame 1 -> marker B (state persists!)
    encoder.encode(dsd2.data(), dsd2.size(), dop.data(), dop.size());
    EXPECT_EQ(dop[2], DOP_MARKER_B);

    // Frame 2 -> marker A again
    encoder.encode(dsd3.data(), dsd3.size(), dop.data(), dop.size());
    EXPECT_EQ(dop[2], DOP_MARKER_A);
}

TEST_F(DopEncoderTest, PendingByteCrossesBufferBoundary) {
    // Send 3 bytes: first 2 make a frame, third is pending
    std::vector<uint8_t> dsd1 = {0xAA, 0xBB, 0xCC};
    std::vector<uint8_t> dop(6);

    size_t w1 = encoder.encode(dsd1.data(), dsd1.size(), dop.data(), dop.size());
    EXPECT_EQ(w1, 3u); // One complete frame
    EXPECT_TRUE(encoder.hasPendingByte());

    // Send 1 more byte to complete the pending frame
    std::vector<uint8_t> dsd2 = {0xDD};
    size_t w2 = encoder.encode(dsd2.data(), dsd2.size(), dop.data(), dop.size());
    EXPECT_EQ(w2, 3u); // Pending + new byte = complete frame
    EXPECT_FALSE(encoder.hasPendingByte());

    // Verify the second frame has correct content
    // Pending byte 0xCC was first, 0xDD is second
    EXPECT_EQ(dop[0], 0xDD); // second/lower
    EXPECT_EQ(dop[1], 0xCC); // first/upper (pending)
    EXPECT_EQ(dop[2], DOP_MARKER_B); // Second frame marker
}

// === Long Stream Stability ===

TEST_F(DopEncoderTest, LongStreamStability) {
    // Process > 1M DSD samples and verify markers stay correct
    const size_t dsdBytes = 2000000; // 1M DoP frames
    std::vector<uint8_t> dsd(dsdBytes);
    for (size_t i = 0; i < dsdBytes; ++i) {
        dsd[i] = static_cast<uint8_t>(i & 0xFF);
    }

    std::vector<uint8_t> dop(3000000);
    size_t written = encoder.encode(dsd.data(), dsd.size(), dop.data(), dop.size());
    ASSERT_EQ(written, 3000000u); // 1M frames * 3 bytes

    // Sample check markers at various positions
    size_t checkPoints[] = {0, 1, 100, 1000, 10000, 100000, 500000, 999999};
    for (size_t frame : checkPoints) {
        uint8_t expected = (frame % 2 == 0) ? DOP_MARKER_A : DOP_MARKER_B;
        EXPECT_EQ(dop[frame * 3 + 2], expected)
            << "Marker mismatch at frame " << frame;
    }

    EXPECT_EQ(encoder.getTotalFramesEncoded(), 1000000u);
}

TEST_F(DopEncoderTest, LongStreamChunkedStability) {
    // Process > 1M samples in chunks, verify markers
    const size_t totalDsdBytes = 2000000;
    const size_t chunkSize = 512; // Realistic buffer size

    std::vector<uint8_t> dsd(totalDsdBytes);
    for (size_t i = 0; i < totalDsdBytes; ++i) {
        dsd[i] = static_cast<uint8_t>(i & 0xFF);
    }

    std::vector<uint8_t> fullDop;
    fullDop.reserve(3000000);

    DopEncoder chunkedEncoder;
    chunkedEncoder.configure(2, 2822400);

    for (size_t pos = 0; pos < totalDsdBytes; pos += chunkSize) {
        size_t remaining = std::min(chunkSize, totalDsdBytes - pos);
        uint8_t buf[768]; // chunkSize/2 * 3
        size_t w = chunkedEncoder.encode(dsd.data() + pos, remaining, buf, sizeof(buf));
        fullDop.insert(fullDop.end(), buf, buf + w);
    }

    ASSERT_EQ(fullDop.size(), 3000000u);

    // Check markers
    for (size_t frame = 0; frame < 100; ++frame) {
        uint8_t expected = (frame % 2 == 0) ? DOP_MARKER_A : DOP_MARKER_B;
        EXPECT_EQ(fullDop[frame * 3 + 2], expected);
    }
    // Also check near the 1M boundary
    for (size_t frame = 999990; frame < 1000000; ++frame) {
        uint8_t expected = (frame % 2 == 0) ? DOP_MARKER_A : DOP_MARKER_B;
        EXPECT_EQ(fullDop[frame * 3 + 2], expected);
    }
}

// === Transport Rate ===

TEST_F(DopEncoderTest, TransportRateDsd64) {
    DopEncoder e;
    e.configure(2, 2822400);
    EXPECT_EQ(e.getTransportRate(), 176400u);
}

TEST_F(DopEncoderTest, TransportRateDsd128) {
    DopEncoder e;
    e.configure(2, 5644800);
    EXPECT_EQ(e.getTransportRate(), 352800u);
}

TEST_F(DopEncoderTest, TransportRateDsd256) {
    DopEncoder e;
    e.configure(2, 11289600);
    EXPECT_EQ(e.getTransportRate(), 705600u);
}

// === Edge Cases ===

TEST_F(DopEncoderTest, EmptyInput) {
    std::vector<uint8_t> dop(10);
    size_t written = encoder.encode(nullptr, 0, dop.data(), dop.size());
    EXPECT_EQ(written, 0u);
}

TEST_F(DopEncoderTest, OutputBufferTooSmall) {
    std::vector<uint8_t> dsd = {0x11, 0x22, 0x33, 0x44};
    std::vector<uint8_t> dop(2); // Too small for even one frame

    size_t written = encoder.encode(dsd.data(), dsd.size(), dop.data(), dop.size());
    EXPECT_EQ(written, 0u);
}

TEST_F(DopEncoderTest, OutputSizeCalculation) {
    EXPECT_EQ(DopEncoder::calculateOutputSize(2), 3u);
    EXPECT_EQ(DopEncoder::calculateOutputSize(10), 15u);
    EXPECT_EQ(DopEncoder::calculateOutputSize(100), 150u);
    EXPECT_EQ(DopEncoder::calculateOutputSize(1), 0u); // Odd: only 0 complete frames
}

TEST_F(DopEncoderTest, SingleByteProducesPending) {
    std::vector<uint8_t> dsd = {0xFF};
    std::vector<uint8_t> dop(3);

    size_t written = encoder.encode(dsd.data(), dsd.size(), dop.data(), dop.size());
    EXPECT_EQ(written, 0u);
    EXPECT_TRUE(encoder.hasPendingByte());
}
