#include <gtest/gtest.h>
#include "../buffer/ring_buffer.h"
#include <thread>
#include <vector>
#include <numeric>
#include <atomic>
#include <cstring>

using namespace bitperfect::buffer;

class RingBufferTest : public ::testing::Test {
protected:
    void SetUp() override {}
    void TearDown() override {}
};

TEST_F(RingBufferTest, Construction) {
    RingBuffer rb(1024);
    EXPECT_GE(rb.capacity(), 1024u);
    EXPECT_TRUE(rb.isEmpty());
    EXPECT_FALSE(rb.isFull());
    EXPECT_EQ(rb.availableRead(), 0u);
    EXPECT_EQ(rb.availableWrite(), rb.capacity());
}

TEST_F(RingBufferTest, PowerOfTwoRounding) {
    // Capacity should be rounded up to power of 2
    RingBuffer rb(1000);
    EXPECT_EQ(rb.capacity(), 1024u);

    RingBuffer rb2(1025);
    EXPECT_EQ(rb2.capacity(), 2048u);

    RingBuffer rb3(4096);
    EXPECT_EQ(rb3.capacity(), 4096u);
}

TEST_F(RingBufferTest, BasicWriteRead) {
    RingBuffer rb(256);
    uint8_t writeData[100];
    uint8_t readData[100];

    // Fill with pattern
    for (int i = 0; i < 100; ++i) writeData[i] = static_cast<uint8_t>(i);

    // Write
    size_t written = rb.write(writeData, 100);
    EXPECT_EQ(written, 100u);
    EXPECT_EQ(rb.availableRead(), 100u);
    EXPECT_FALSE(rb.isEmpty());

    // Read
    size_t bytesRead = rb.read(readData, 100);
    EXPECT_EQ(bytesRead, 100u);
    EXPECT_TRUE(rb.isEmpty());
    EXPECT_EQ(std::memcmp(writeData, readData, 100), 0);
}

TEST_F(RingBufferTest, PartialRead) {
    RingBuffer rb(256);
    uint8_t writeData[100];
    uint8_t readData[50];

    for (int i = 0; i < 100; ++i) writeData[i] = static_cast<uint8_t>(i);

    rb.write(writeData, 100);

    // Read only half
    size_t bytesRead = rb.read(readData, 50);
    EXPECT_EQ(bytesRead, 50u);
    EXPECT_EQ(rb.availableRead(), 50u);
    EXPECT_EQ(std::memcmp(writeData, readData, 50), 0);

    // Read second half
    bytesRead = rb.read(readData, 50);
    EXPECT_EQ(bytesRead, 50u);
    EXPECT_TRUE(rb.isEmpty());
    EXPECT_EQ(std::memcmp(writeData + 50, readData, 50), 0);
}

TEST_F(RingBufferTest, OverflowProtection) {
    RingBuffer rb(64);  // Will be 64 bytes (already power of 2)
    uint8_t data[100];
    std::memset(data, 0xAA, sizeof(data));

    // Try to write more than capacity
    size_t written = rb.write(data, 100);
    EXPECT_EQ(written, 64u);  // Only capacity can be written
    EXPECT_TRUE(rb.isFull());
    EXPECT_EQ(rb.availableWrite(), 0u);
}

TEST_F(RingBufferTest, WrapAround) {
    RingBuffer rb(128);
    uint8_t writeData[80];
    uint8_t readData[80];

    // Fill with first pattern
    for (int i = 0; i < 80; ++i) writeData[i] = static_cast<uint8_t>(i);

    // Write 80 bytes
    rb.write(writeData, 80);
    // Read 60 bytes (advancing tail past midpoint)
    rb.read(readData, 60);
    EXPECT_EQ(rb.availableRead(), 20u);

    // Write another 80 bytes - this will wrap around the buffer
    for (int i = 0; i < 80; ++i) writeData[i] = static_cast<uint8_t>(i + 100);
    size_t written = rb.write(writeData, 80);
    EXPECT_EQ(written, 80u);

    // Read remaining 20 from first write
    rb.read(readData, 20);
    for (int i = 0; i < 20; ++i) {
        EXPECT_EQ(readData[i], static_cast<uint8_t>(i + 60));
    }

    // Read 80 from second write (which wrapped around)
    size_t bytesRead = rb.read(readData, 80);
    EXPECT_EQ(bytesRead, 80u);
    for (int i = 0; i < 80; ++i) {
        EXPECT_EQ(readData[i], static_cast<uint8_t>(i + 100));
    }
}

TEST_F(RingBufferTest, Peek) {
    RingBuffer rb(256);
    uint8_t writeData[50];
    uint8_t peekData[50];
    uint8_t readData[50];

    for (int i = 0; i < 50; ++i) writeData[i] = static_cast<uint8_t>(i * 2);

    rb.write(writeData, 50);

    // Peek should not advance read pointer
    size_t peeked = rb.peek(peekData, 50);
    EXPECT_EQ(peeked, 50u);
    EXPECT_EQ(rb.availableRead(), 50u); // Still available
    EXPECT_EQ(std::memcmp(writeData, peekData, 50), 0);

    // Read should give same data
    size_t bytesRead = rb.read(readData, 50);
    EXPECT_EQ(bytesRead, 50u);
    EXPECT_EQ(std::memcmp(writeData, readData, 50), 0);
}

TEST_F(RingBufferTest, Skip) {
    RingBuffer rb(256);
    uint8_t writeData[100];
    uint8_t readData[50];

    for (int i = 0; i < 100; ++i) writeData[i] = static_cast<uint8_t>(i);

    rb.write(writeData, 100);

    // Skip first 50 bytes
    size_t skipped = rb.skip(50);
    EXPECT_EQ(skipped, 50u);
    EXPECT_EQ(rb.availableRead(), 50u);

    // Read remaining should start at byte 50
    size_t bytesRead = rb.read(readData, 50);
    EXPECT_EQ(bytesRead, 50u);
    for (int i = 0; i < 50; ++i) {
        EXPECT_EQ(readData[i], static_cast<uint8_t>(i + 50));
    }
}

TEST_F(RingBufferTest, Reset) {
    RingBuffer rb(256);
    uint8_t data[100];
    std::memset(data, 0xBB, sizeof(data));

    rb.write(data, 100);
    EXPECT_FALSE(rb.isEmpty());

    rb.reset();
    EXPECT_TRUE(rb.isEmpty());
    EXPECT_EQ(rb.availableRead(), 0u);
    EXPECT_EQ(rb.availableWrite(), rb.capacity());
}

TEST_F(RingBufferTest, FillLevel) {
    RingBuffer rb(1024);
    EXPECT_FLOAT_EQ(rb.fillLevel(), 0.0f);

    uint8_t data[512];
    rb.write(data, 512);
    EXPECT_FLOAT_EQ(rb.fillLevel(), 0.5f);

    rb.write(data, 512);
    EXPECT_FLOAT_EQ(rb.fillLevel(), 1.0f);
}

TEST_F(RingBufferTest, NullInputHandling) {
    RingBuffer rb(256);
    EXPECT_EQ(rb.write(nullptr, 100), 0u);
    EXPECT_EQ(rb.read(nullptr, 100), 0u);
    EXPECT_EQ(rb.peek(nullptr, 100), 0u);

    uint8_t data[10];
    EXPECT_EQ(rb.write(data, 0), 0u);
    EXPECT_EQ(rb.read(data, 0), 0u);
}

TEST_F(RingBufferTest, ConcurrentProducerConsumer) {
    // This test verifies thread safety of the SPSC ring buffer
    constexpr size_t BUFFER_SIZE = 4096;
    constexpr size_t TOTAL_BYTES = 1000000;
    constexpr size_t CHUNK_SIZE = 128;

    RingBuffer rb(BUFFER_SIZE);
    std::atomic<bool> done{false};
    std::atomic<size_t> totalWritten{0};
    std::atomic<size_t> totalRead{0};
    std::vector<uint8_t> produced(TOTAL_BYTES);
    std::vector<uint8_t> consumed(TOTAL_BYTES);

    // Fill produced data with a pattern
    for (size_t i = 0; i < TOTAL_BYTES; ++i) {
        produced[i] = static_cast<uint8_t>(i & 0xFF);
    }

    // Producer thread
    std::thread producer([&]() {
        size_t offset = 0;
        while (offset < TOTAL_BYTES) {
            size_t toWrite = std::min(CHUNK_SIZE, TOTAL_BYTES - offset);
            size_t written = rb.write(produced.data() + offset, toWrite);
            offset += written;
            totalWritten.store(offset, std::memory_order_relaxed);
            if (written == 0) {
                // Buffer full, yield
                std::this_thread::yield();
            }
        }
        done.store(true);
    });

    // Consumer thread
    std::thread consumer([&]() {
        size_t offset = 0;
        while (offset < TOTAL_BYTES) {
            size_t toRead = std::min(CHUNK_SIZE, TOTAL_BYTES - offset);
            size_t bytesRead = rb.read(consumed.data() + offset, toRead);
            offset += bytesRead;
            totalRead.store(offset, std::memory_order_relaxed);
            if (bytesRead == 0) {
                if (done.load() && rb.isEmpty()) break;
                std::this_thread::yield();
            }
        }
    });

    producer.join();
    consumer.join();

    // Verify ALL data arrived correctly - bit-perfect!
    EXPECT_EQ(totalWritten.load(), TOTAL_BYTES);
    EXPECT_EQ(totalRead.load(), TOTAL_BYTES);
    EXPECT_EQ(std::memcmp(produced.data(), consumed.data(), TOTAL_BYTES), 0);
}

TEST_F(RingBufferTest, ConcurrentStressTest) {
    // Multiple iterations with varying chunk sizes
    constexpr size_t BUFFER_SIZE = 2048;
    constexpr size_t TOTAL_BYTES = 500000;

    for (int iteration = 0; iteration < 5; ++iteration) {
        RingBuffer rb(BUFFER_SIZE);
        std::atomic<bool> done{false};
        std::vector<uint8_t> produced(TOTAL_BYTES);
        std::vector<uint8_t> consumed(TOTAL_BYTES);

        for (size_t i = 0; i < TOTAL_BYTES; ++i) {
            produced[i] = static_cast<uint8_t>((i + iteration) & 0xFF);
        }

        size_t writeChunk = 64 + iteration * 32; // Varying chunk sizes
        size_t readChunk = 48 + iteration * 16;

        std::thread producer([&]() {
            size_t offset = 0;
            while (offset < TOTAL_BYTES) {
                size_t toWrite = std::min(writeChunk, TOTAL_BYTES - offset);
                size_t written = rb.write(produced.data() + offset, toWrite);
                offset += written;
                if (written == 0) std::this_thread::yield();
            }
            done.store(true);
        });

        std::thread consumer([&]() {
            size_t offset = 0;
            while (offset < TOTAL_BYTES) {
                size_t toRead = std::min(readChunk, TOTAL_BYTES - offset);
                size_t bytesRead = rb.read(consumed.data() + offset, toRead);
                offset += bytesRead;
                if (bytesRead == 0) {
                    if (done.load() && rb.isEmpty()) break;
                    std::this_thread::yield();
                }
            }
        });

        producer.join();
        consumer.join();

        EXPECT_EQ(std::memcmp(produced.data(), consumed.data(), TOTAL_BYTES), 0)
            << "Data corruption on iteration " << iteration;
    }
}
