#include <gtest/gtest.h>
#include "../buffer/ring_buffer.h"
#include <vector>
#include <cstring>
#include <chrono>
#include <thread>
#include <atomic>
#include <new>

using namespace bitperfect::buffer;

// === Allocation Tracking ===

// Global allocation counter for detecting hot-path allocations
static std::atomic<int64_t> g_allocationCount{0};
static std::atomic<bool> g_trackingEnabled{false};

// Override global operator new to detect allocations in hot path
void* operator new(std::size_t size) {
    if (g_trackingEnabled.load(std::memory_order_relaxed)) {
        g_allocationCount.fetch_add(1, std::memory_order_relaxed);
    }
    void* ptr = std::malloc(size);
    if (!ptr) throw std::bad_alloc();
    return ptr;
}

void* operator new[](std::size_t size) {
    if (g_trackingEnabled.load(std::memory_order_relaxed)) {
        g_allocationCount.fetch_add(1, std::memory_order_relaxed);
    }
    void* ptr = std::malloc(size);
    if (!ptr) throw std::bad_alloc();
    return ptr;
}

void operator delete(void* ptr) noexcept {
    std::free(ptr);
}

void operator delete[](void* ptr) noexcept {
    std::free(ptr);
}

void operator delete(void* ptr, std::size_t) noexcept {
    std::free(ptr);
}

void operator delete[](void* ptr, std::size_t) noexcept {
    std::free(ptr);
}

class RingBufferBenchmark : public ::testing::Test {
protected:
    void SetUp() override {
        g_allocationCount.store(0);
        g_trackingEnabled.store(false);
    }

    void TearDown() override {
        g_trackingEnabled.store(false);
    }
};

// === Zero Allocation in Hot Path ===

TEST_F(RingBufferBenchmark, ZeroAllocationInWriteReadHotPath) {
    // Create buffer (allocations happen here, before tracking)
    RingBuffer ring(65536);
    uint8_t writeData[1024];
    uint8_t readData[1024];
    std::memset(writeData, 0xAA, sizeof(writeData));

    // Start tracking allocations
    g_allocationCount.store(0);
    g_trackingEnabled.store(true);

    // Hot path: write and read in a loop (simulating real-time audio)
    const int iterations = 10000;
    for (int i = 0; i < iterations; ++i) {
        ring.write(writeData, sizeof(writeData));
        ring.read(readData, sizeof(readData));
    }

    g_trackingEnabled.store(false);

    // Verify ZERO heap allocations in the hot path
    int64_t allocations = g_allocationCount.load();
    EXPECT_EQ(allocations, 0)
        << "Detected " << allocations << " heap allocations in ring buffer hot path. "
        << "Real-time audio paths must not allocate.";
}

TEST_F(RingBufferBenchmark, ZeroAllocationInPeekSkip) {
    RingBuffer ring(65536);
    uint8_t writeData[512];
    uint8_t peekData[512];
    std::memset(writeData, 0xBB, sizeof(writeData));
    ring.write(writeData, sizeof(writeData));

    g_allocationCount.store(0);
    g_trackingEnabled.store(true);

    for (int i = 0; i < 1000; ++i) {
        ring.peek(peekData, 256);
        ring.skip(256);
        ring.write(writeData, 256);
    }

    g_trackingEnabled.store(false);

    EXPECT_EQ(g_allocationCount.load(), 0)
        << "Peek/skip operations must not allocate on the heap.";
}

// === Throughput Measurement ===

TEST_F(RingBufferBenchmark, ThroughputSmallBuffer4KB) {
    RingBuffer ring(4096);
    uint8_t writeData[256];
    uint8_t readData[256];
    std::memset(writeData, 0xCC, sizeof(writeData));

    auto start = std::chrono::high_resolution_clock::now();
    const size_t totalBytes = 100 * 1024 * 1024; // 100 MB
    size_t bytesProcessed = 0;

    while (bytesProcessed < totalBytes) {
        ring.write(writeData, sizeof(writeData));
        ring.read(readData, sizeof(readData));
        bytesProcessed += sizeof(writeData);
    }

    auto end = std::chrono::high_resolution_clock::now();
    double seconds = std::chrono::duration<double>(end - start).count();
    double mbPerSec = (totalBytes / (1024.0 * 1024.0)) / seconds;

    // Ring buffer should achieve at least 500 MB/s on any modern hardware
    EXPECT_GT(mbPerSec, 500.0)
        << "Throughput " << mbPerSec << " MB/s is below minimum for real-time audio";
}

TEST_F(RingBufferBenchmark, ThroughputMediumBuffer64KB) {
    RingBuffer ring(65536);
    uint8_t writeData[4096];
    uint8_t readData[4096];
    std::memset(writeData, 0xDD, sizeof(writeData));

    auto start = std::chrono::high_resolution_clock::now();
    const size_t totalBytes = 200 * 1024 * 1024; // 200 MB
    size_t bytesProcessed = 0;

    while (bytesProcessed < totalBytes) {
        ring.write(writeData, sizeof(writeData));
        ring.read(readData, sizeof(readData));
        bytesProcessed += sizeof(writeData);
    }

    auto end = std::chrono::high_resolution_clock::now();
    double seconds = std::chrono::duration<double>(end - start).count();
    double mbPerSec = (totalBytes / (1024.0 * 1024.0)) / seconds;

    EXPECT_GT(mbPerSec, 1000.0)
        << "Throughput " << mbPerSec << " MB/s with 4KB chunks should exceed 1 GB/s";
}

TEST_F(RingBufferBenchmark, ThroughputLargeBuffer1MB) {
    RingBuffer ring(1024 * 1024);
    uint8_t writeData[16384];
    uint8_t readData[16384];
    std::memset(writeData, 0xEE, sizeof(writeData));

    auto start = std::chrono::high_resolution_clock::now();
    const size_t totalBytes = 500 * 1024 * 1024; // 500 MB
    size_t bytesProcessed = 0;

    while (bytesProcessed < totalBytes) {
        ring.write(writeData, sizeof(writeData));
        ring.read(readData, sizeof(readData));
        bytesProcessed += sizeof(writeData);
    }

    auto end = std::chrono::high_resolution_clock::now();
    double seconds = std::chrono::duration<double>(end - start).count();
    double mbPerSec = (totalBytes / (1024.0 * 1024.0)) / seconds;

    EXPECT_GT(mbPerSec, 1000.0)
        << "Throughput " << mbPerSec << " MB/s with 16KB chunks should exceed 1 GB/s";
}

// === Multi-threaded Stress Test ===

TEST_F(RingBufferBenchmark, MultiThreadedStress) {
    RingBuffer ring(65536);
    const size_t totalBytes = 10 * 1024 * 1024; // 10 MB total
    const size_t chunkSize = 512;
    std::atomic<bool> producerDone{false};
    std::atomic<size_t> bytesRead{0};
    std::atomic<size_t> bytesWritten{0};

    // Producer thread
    std::thread producer([&]() {
        uint8_t data[chunkSize];
        for (size_t i = 0; i < chunkSize; ++i) {
            data[i] = static_cast<uint8_t>(i & 0xFF);
        }

        size_t remaining = totalBytes;
        while (remaining > 0) {
            size_t toWrite = std::min(chunkSize, remaining);
            size_t written = ring.write(data, toWrite);
            if (written > 0) {
                remaining -= written;
                bytesWritten.fetch_add(written, std::memory_order_relaxed);
            } else {
                std::this_thread::yield();
            }
        }
        producerDone.store(true);
    });

    // Consumer thread
    std::thread consumer([&]() {
        uint8_t data[chunkSize];
        while (!producerDone.load() || ring.availableRead() > 0) {
            size_t readCount = ring.read(data, chunkSize);
            if (readCount > 0) {
                bytesRead.fetch_add(readCount, std::memory_order_relaxed);
            } else {
                std::this_thread::yield();
            }
        }
    });

    producer.join();
    consumer.join();

    EXPECT_EQ(bytesWritten.load(), totalBytes);
    EXPECT_EQ(bytesRead.load(), totalBytes);
}

TEST_F(RingBufferBenchmark, MultiThreadedDataIntegrity) {
    RingBuffer ring(32768);
    const size_t totalFrames = 100000;
    const size_t frameSize = 8; // 2ch * 32bit
    std::atomic<bool> producerDone{false};
    std::atomic<bool> integrityOk{true};

    // Producer: writes sequential frame numbers
    std::thread producer([&]() {
        for (size_t frame = 0; frame < totalFrames; ++frame) {
            uint8_t data[frameSize];
            // Write frame number into first 4 bytes
            uint32_t frameNum = static_cast<uint32_t>(frame);
            std::memcpy(data, &frameNum, 4);
            std::memcpy(data + 4, &frameNum, 4);

            while (ring.write(data, frameSize) == 0) {
                std::this_thread::yield();
            }
        }
        producerDone.store(true);
    });

    // Consumer: reads and verifies sequential frame numbers
    std::thread consumer([&]() {
        uint32_t expectedFrame = 0;
        uint8_t data[frameSize];

        while (!producerDone.load() || ring.availableRead() > 0) {
            size_t readCount = ring.read(data, frameSize);
            if (readCount == frameSize) {
                uint32_t receivedFrame;
                std::memcpy(&receivedFrame, data, 4);
                if (receivedFrame != expectedFrame) {
                    integrityOk.store(false);
                    break;
                }
                expectedFrame++;
            } else if (readCount > 0 && readCount < frameSize) {
                // Partial read should not happen with proper frame alignment
                integrityOk.store(false);
                break;
            } else {
                std::this_thread::yield();
            }
        }
    });

    producer.join();
    consumer.join();

    EXPECT_TRUE(integrityOk.load()) << "Data integrity violated in multi-threaded access";
}

TEST_F(RingBufferBenchmark, MultiThreadedTiming) {
    RingBuffer ring(65536);
    const size_t totalBytes = 50 * 1024 * 1024; // 50 MB
    const size_t chunkSize = 1024;
    std::atomic<bool> producerDone{false};

    auto start = std::chrono::high_resolution_clock::now();

    std::thread producer([&]() {
        uint8_t data[chunkSize];
        std::memset(data, 0xAA, chunkSize);
        size_t remaining = totalBytes;
        while (remaining > 0) {
            size_t written = ring.write(data, std::min(chunkSize, remaining));
            if (written > 0) remaining -= written;
            else std::this_thread::yield();
        }
        producerDone.store(true);
    });

    std::thread consumer([&]() {
        uint8_t data[chunkSize];
        size_t totalRead = 0;
        while (totalRead < totalBytes) {
            size_t readCount = ring.read(data, chunkSize);
            if (readCount > 0) totalRead += readCount;
            else std::this_thread::yield();
        }
    });

    producer.join();
    consumer.join();

    auto end = std::chrono::high_resolution_clock::now();
    double seconds = std::chrono::duration<double>(end - start).count();
    double mbPerSec = (totalBytes / (1024.0 * 1024.0)) / seconds;

    // Multi-threaded throughput should still be reasonable
    EXPECT_GT(mbPerSec, 100.0)
        << "Multi-threaded throughput " << mbPerSec << " MB/s is too low";
}
