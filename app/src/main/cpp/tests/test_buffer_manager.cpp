#include <gtest/gtest.h>
#include "../buffer/audio_buffer_manager.h"
#include <vector>
#include <cstring>

using namespace bitperfect::buffer;

class BufferManagerTest : public ::testing::Test {
protected:
    void SetUp() override {}
    void TearDown() override {}
};

// === Construction and Initialization ===

TEST_F(BufferManagerTest, ConstructionWithValidSize) {
    AudioBufferManager mgr(8192);
    EXPECT_GE(mgr.capacity(), 8192u);
    EXPECT_EQ(mgr.getState(), BufferState::EMPTY);
    EXPECT_EQ(mgr.availableRead(), 0u);
}

TEST_F(BufferManagerTest, ConstructionWithPrebufferThreshold) {
    AudioBufferManager mgr(4096, 0.75f);
    EXPECT_FLOAT_EQ(mgr.getPrebufferThreshold(), 0.75f);
}

TEST_F(BufferManagerTest, ThresholdClampedToRange) {
    AudioBufferManager mgr1(4096, 1.5f);
    EXPECT_FLOAT_EQ(mgr1.getPrebufferThreshold(), 1.0f);

    AudioBufferManager mgr2(4096, -0.5f);
    EXPECT_FLOAT_EQ(mgr2.getPrebufferThreshold(), 0.0f);
}

// === Latency Configuration ===

TEST_F(BufferManagerTest, ConfigureLatency10ms) {
    AudioBufferManager mgr(1024);
    // 44100 Hz * 2 ch * 2 bytes = 176400 bytes/sec
    // 10ms = 1764 bytes -> rounds to power of 2 = 2048
    mgr.configureLatency(10, 176400);
    EXPECT_GE(mgr.capacity(), 1764u);
}

TEST_F(BufferManagerTest, ConfigureLatency50ms) {
    AudioBufferManager mgr(1024);
    // 96000 Hz * 2 ch * 3 bytes = 576000 bytes/sec
    // 50ms = 28800 bytes
    mgr.configureLatency(50, 576000);
    EXPECT_GE(mgr.capacity(), 28800u);
}

TEST_F(BufferManagerTest, ConfigureLatencyZeroBps) {
    AudioBufferManager mgr(4096);
    size_t originalCap = mgr.capacity();
    mgr.configureLatency(10, 0);  // Should not crash
    // Capacity unchanged when bytesPerSecond is 0
    EXPECT_EQ(mgr.capacity(), originalCap);
}

TEST_F(BufferManagerTest, ConfigureLatencyResetsState) {
    AudioBufferManager mgr(4096, 0.25f);

    // Write some data first
    std::vector<uint8_t> data(2048, 0xAA);
    mgr.write(data.data(), data.size());
    EXPECT_NE(mgr.getState(), BufferState::EMPTY);

    // Reconfigure latency should reset
    mgr.configureLatency(20, 192000);
    EXPECT_EQ(mgr.getState(), BufferState::EMPTY);
    EXPECT_EQ(mgr.availableRead(), 0u);
}

// === Adaptive Pre-buffering ===

TEST_F(BufferManagerTest, PrebufferingPhaseBlocksReads) {
    AudioBufferManager mgr(4096, 0.5f);

    // Write less than threshold
    std::vector<uint8_t> data(1024, 0xAA);
    mgr.write(data.data(), data.size());

    // State should be prebuffering
    EXPECT_EQ(mgr.getState(), BufferState::PREBUFFERING);

    // Read should return 0 during prebuffering
    std::vector<uint8_t> readBuf(512);
    size_t bytesRead = mgr.read(readBuf.data(), readBuf.size());
    EXPECT_EQ(bytesRead, 0u);
}

TEST_F(BufferManagerTest, PrebufferThresholdTriggersStreaming) {
    AudioBufferManager mgr(4096, 0.5f);
    size_t cap = mgr.capacity();

    // Write enough to exceed threshold (50% of capacity)
    size_t needed = static_cast<size_t>(cap * 0.55f);
    std::vector<uint8_t> data(needed, 0xBB);
    mgr.write(data.data(), data.size());

    EXPECT_EQ(mgr.getState(), BufferState::STREAMING);
    EXPECT_TRUE(mgr.isReady());
}

TEST_F(BufferManagerTest, StreamingPhaseAllowsReads) {
    AudioBufferManager mgr(4096, 0.5f);
    size_t cap = mgr.capacity();

    // Fill past threshold
    size_t needed = static_cast<size_t>(cap * 0.6f);
    std::vector<uint8_t> data(needed, 0xCC);
    mgr.write(data.data(), data.size());

    EXPECT_EQ(mgr.getState(), BufferState::STREAMING);

    // Read should now succeed
    std::vector<uint8_t> readBuf(256);
    size_t bytesRead = mgr.read(readBuf.data(), readBuf.size());
    EXPECT_EQ(bytesRead, 256u);

    // Verify data integrity
    for (size_t i = 0; i < 256; ++i) {
        EXPECT_EQ(readBuf[i], 0xCC);
    }
}

TEST_F(BufferManagerTest, ZeroThresholdSkipsPrebuffer) {
    AudioBufferManager mgr(4096, 0.0f);

    std::vector<uint8_t> data(100, 0xDD);
    mgr.write(data.data(), data.size());

    // With 0 threshold, should immediately be streaming
    EXPECT_EQ(mgr.getState(), BufferState::STREAMING);
}

// === Underrun Detection ===

TEST_F(BufferManagerTest, UnderrunDetectedWhenBufferEmpties) {
    AudioBufferManager mgr(4096, 0.5f);
    size_t cap = mgr.capacity();

    // Fill past threshold
    size_t fillSize = static_cast<size_t>(cap * 0.6f);
    std::vector<uint8_t> data(fillSize, 0xEE);
    mgr.write(data.data(), data.size());
    EXPECT_EQ(mgr.getState(), BufferState::STREAMING);

    // Read all data
    std::vector<uint8_t> readBuf(fillSize + 100);
    size_t bytesRead = mgr.read(readBuf.data(), readBuf.size());
    EXPECT_LE(bytesRead, fillSize);

    // Buffer should be in underrun state
    EXPECT_EQ(mgr.getState(), BufferState::UNDERRUN);
}

TEST_F(BufferManagerTest, UnderrunCountTracked) {
    AudioBufferManager mgr(4096, 0.5f);
    size_t cap = mgr.capacity();

    // Fill and drain
    size_t fillSize = static_cast<size_t>(cap * 0.6f);
    std::vector<uint8_t> data(fillSize, 0xAA);
    mgr.write(data.data(), data.size());

    std::vector<uint8_t> readBuf(fillSize + 100);
    mgr.read(readBuf.data(), readBuf.size());

    auto stats = mgr.getStatistics();
    EXPECT_GE(stats.underrunCount, 1u);
}

TEST_F(BufferManagerTest, UnderrunRecoveryViaRebuffer) {
    AudioBufferManager mgr(4096, 0.5f);
    size_t cap = mgr.capacity();

    // Fill past threshold
    size_t fillSize = static_cast<size_t>(cap * 0.6f);
    std::vector<uint8_t> data(fillSize, 0xFF);
    mgr.write(data.data(), data.size());

    // Drain completely
    std::vector<uint8_t> readBuf(fillSize + 100);
    mgr.read(readBuf.data(), readBuf.size());
    EXPECT_EQ(mgr.getState(), BufferState::UNDERRUN);

    // Re-fill past threshold should recover
    mgr.write(data.data(), data.size());
    EXPECT_EQ(mgr.getState(), BufferState::STREAMING);
}

// === Overflow Protection ===

TEST_F(BufferManagerTest, OverflowProtectionOnFullBuffer) {
    AudioBufferManager mgr(1024, 0.0f);
    size_t cap = mgr.capacity();

    // Fill completely
    std::vector<uint8_t> data(cap, 0x11);
    size_t written = mgr.write(data.data(), data.size());
    EXPECT_EQ(written, cap);

    // Write more data should partially succeed or fail
    std::vector<uint8_t> extra(100, 0x22);
    size_t extraWritten = mgr.write(extra.data(), extra.size());
    EXPECT_LT(extraWritten, 100u);

    auto stats = mgr.getStatistics();
    EXPECT_GE(stats.overrunCount, 1u);
}

TEST_F(BufferManagerTest, OverflowCountIncrementsEachTime) {
    AudioBufferManager mgr(1024, 0.0f);
    size_t cap = mgr.capacity();

    // Fill buffer
    std::vector<uint8_t> data(cap, 0x33);
    mgr.write(data.data(), data.size());

    // Attempt multiple overflows
    std::vector<uint8_t> extra(10, 0x44);
    mgr.write(extra.data(), extra.size());
    mgr.write(extra.data(), extra.size());
    mgr.write(extra.data(), extra.size());

    auto stats = mgr.getStatistics();
    EXPECT_GE(stats.overrunCount, 3u);
}

// === Fill Level Monitoring ===

TEST_F(BufferManagerTest, FillLevelEmpty) {
    AudioBufferManager mgr(4096);
    EXPECT_FLOAT_EQ(mgr.getFillLevel(), 0.0f);
}

TEST_F(BufferManagerTest, FillLevelAfterWrite) {
    AudioBufferManager mgr(4096, 0.0f);
    size_t cap = mgr.capacity();

    std::vector<uint8_t> data(cap / 2, 0xAA);
    mgr.write(data.data(), data.size());

    float level = mgr.getFillLevel();
    EXPECT_NEAR(level, 0.5f, 0.01f);
}

TEST_F(BufferManagerTest, FillLevelFull) {
    AudioBufferManager mgr(4096, 0.0f);
    size_t cap = mgr.capacity();

    std::vector<uint8_t> data(cap, 0xBB);
    mgr.write(data.data(), data.size());

    float level = mgr.getFillLevel();
    EXPECT_NEAR(level, 1.0f, 0.01f);
}

// === Statistics ===

TEST_F(BufferManagerTest, StatisticsTotalBytesWritten) {
    AudioBufferManager mgr(4096, 0.0f);

    std::vector<uint8_t> data(100, 0xAA);
    mgr.write(data.data(), data.size());
    mgr.write(data.data(), data.size());

    auto stats = mgr.getStatistics();
    EXPECT_EQ(stats.totalBytesWritten, 200u);
}

TEST_F(BufferManagerTest, StatisticsTotalBytesRead) {
    AudioBufferManager mgr(4096, 0.0f);

    std::vector<uint8_t> data(200, 0xBB);
    mgr.write(data.data(), data.size());

    std::vector<uint8_t> readBuf(100);
    mgr.read(readBuf.data(), readBuf.size());

    auto stats = mgr.getStatistics();
    EXPECT_EQ(stats.totalBytesRead, 100u);
}

// === Reset ===

TEST_F(BufferManagerTest, ResetClearsEverything) {
    AudioBufferManager mgr(4096, 0.5f);
    size_t cap = mgr.capacity();

    // Fill and read
    std::vector<uint8_t> data(static_cast<size_t>(cap * 0.6f), 0xCC);
    mgr.write(data.data(), data.size());
    std::vector<uint8_t> readBuf(100);
    mgr.read(readBuf.data(), readBuf.size());

    mgr.reset();

    EXPECT_EQ(mgr.getState(), BufferState::EMPTY);
    EXPECT_EQ(mgr.availableRead(), 0u);
    EXPECT_FLOAT_EQ(mgr.getFillLevel(), 0.0f);

    auto stats = mgr.getStatistics();
    EXPECT_EQ(stats.totalBytesWritten, 0u);
    EXPECT_EQ(stats.totalBytesRead, 0u);
    EXPECT_EQ(stats.underrunCount, 0u);
    EXPECT_EQ(stats.overrunCount, 0u);
}

// === Thread Safety (basic) ===

TEST_F(BufferManagerTest, SetPrebufferThreshold) {
    AudioBufferManager mgr(4096, 0.5f);
    mgr.setPrebufferThreshold(0.3f);
    EXPECT_FLOAT_EQ(mgr.getPrebufferThreshold(), 0.3f);

    mgr.setPrebufferThreshold(0.9f);
    EXPECT_FLOAT_EQ(mgr.getPrebufferThreshold(), 0.9f);
}

TEST_F(BufferManagerTest, NullDataWriteReturnsZero) {
    AudioBufferManager mgr(4096);
    size_t written = mgr.write(nullptr, 100);
    EXPECT_EQ(written, 0u);
}

TEST_F(BufferManagerTest, NullDataReadReturnsZero) {
    AudioBufferManager mgr(4096, 0.0f);
    std::vector<uint8_t> data(100, 0xAA);
    mgr.write(data.data(), data.size());

    size_t bytesRead = mgr.read(nullptr, 100);
    EXPECT_EQ(bytesRead, 0u);
}

TEST_F(BufferManagerTest, ZeroLengthWriteReturnsZero) {
    AudioBufferManager mgr(4096);
    uint8_t data = 0xAA;
    size_t written = mgr.write(&data, 0);
    EXPECT_EQ(written, 0u);
}

TEST_F(BufferManagerTest, ZeroLengthReadReturnsZero) {
    AudioBufferManager mgr(4096, 0.0f);
    std::vector<uint8_t> data(100, 0xBB);
    mgr.write(data.data(), data.size());

    uint8_t readBuf;
    size_t bytesRead = mgr.read(&readBuf, 0);
    EXPECT_EQ(bytesRead, 0u);
}
