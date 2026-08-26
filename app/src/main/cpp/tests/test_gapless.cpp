#include <gtest/gtest.h>
#include "audio/gapless_engine.h"
#include "decoder/audio_decoder.h"
#include <vector>
#include <cstring>
#include <memory>

using namespace bitperfect::audio;
using namespace bitperfect::decoder;

namespace {

/**
 * Test decoder that produces known patterns.
 * Each frame contains the frame number in each sample position.
 */
class TestDecoder : public AudioDecoder {
public:
    TestDecoder(uint32_t sampleRate, uint8_t bitsPerSample, uint8_t channels,
                uint64_t totalFrames, uint8_t fillValue = 0)
        : format_{sampleRate, bitsPerSample, channels, totalFrames},
          fillValue_(fillValue) {}

    bool open(const std::string& /*path*/) override { return true; }

    size_t read(uint8_t* buffer, size_t frames) override {
        uint64_t remaining = format_.totalFrames - currentFrame_;
        size_t framesToRead = std::min(frames, static_cast<size_t>(remaining));
        if (framesToRead == 0) return 0;

        size_t bytesPerFrame = format_.bytesPerFrame();
        size_t totalBytes = framesToRead * bytesPerFrame;

        // Fill with known pattern based on fill value and frame number
        for (size_t i = 0; i < totalBytes; ++i) {
            buffer[i] = static_cast<uint8_t>(fillValue_ + ((currentFrame_ + i / bytesPerFrame) & 0xFF));
        }

        currentFrame_ += framesToRead;
        return framesToRead;
    }

    bool seek(const SeekPosition& position) override {
        currentFrame_ = std::min(position.frameIndex, format_.totalFrames);
        return true;
    }

    void close() override { currentFrame_ = 0; }
    AudioFormat getFormat() const override { return format_; }
    double getDuration() const override { return format_.durationSeconds(); }
    bool isOpen() const override { return true; }
    uint64_t getPosition() const override { return currentFrame_; }
    const char* getTypeName() const override { return "TEST"; }

private:
    AudioFormat format_;
    uint64_t currentFrame_ = 0;
    uint8_t fillValue_;
};

} // anonymous namespace

// --- Same-format gapless transition ---

TEST(GaplessEngine, SameFormatTransitionNoGap) {
    GaplessEngine engine;

    // Track 1: 100 frames at 44100/16/2, fill=0xAA
    auto track1 = std::make_unique<TestDecoder>(44100, 16, 2, 100, 0xAA);
    // Track 2: 100 frames at same format, fill=0xBB
    auto track2 = std::make_unique<TestDecoder>(44100, 16, 2, 100, 0xBB);

    engine.setCurrentTrack(std::move(track1), "track1.wav");
    engine.queueNextTrack(std::move(track2), "track2.wav");

    EXPECT_FALSE(engine.willFormatChange());

    // Read enough to cover both tracks (200 frames * 4 bytes/frame = 800 bytes)
    const size_t bytesPerFrame = 4;  // 16-bit stereo
    std::vector<uint8_t> buffer(200 * bytesPerFrame);
    size_t totalRead = 0;

    // Read all 200 frames in one go - gapless engine should handle transition
    totalRead = engine.read(buffer.data(), 200);

    // Should have read all 200 frames (100 from each track)
    EXPECT_EQ(totalRead, 200u);
}

TEST(GaplessEngine, TransitionCallbackFired) {
    GaplessEngine engine;

    auto track1 = std::make_unique<TestDecoder>(44100, 16, 2, 50, 0x11);
    auto track2 = std::make_unique<TestDecoder>(44100, 16, 2, 50, 0x22);

    bool callbackFired = false;
    TrackTransitionInfo capturedInfo;

    engine.setTransitionCallback([&](const TrackTransitionInfo& info) {
        callbackFired = true;
        capturedInfo = info;
    });

    engine.setCurrentTrack(std::move(track1), "first.wav");
    engine.queueNextTrack(std::move(track2), "second.wav");

    // Read past the first track
    std::vector<uint8_t> buffer(400);
    engine.read(buffer.data(), 100);  // Request more than track1 has

    EXPECT_TRUE(callbackFired);
    EXPECT_EQ(capturedInfo.completedTrackPath, "first.wav");
    EXPECT_EQ(capturedInfo.nextTrackPath, "second.wav");
    EXPECT_FALSE(capturedInfo.formatChanged);
    EXPECT_FALSE(capturedInfo.requiresReconfigure);
}

// --- Format change transition ---

TEST(GaplessEngine, FormatChangeDetected) {
    GaplessEngine engine;

    // Track 1: 44100/16/2
    auto track1 = std::make_unique<TestDecoder>(44100, 16, 2, 100, 0x01);
    // Track 2: 96000/24/2 (different format)
    auto track2 = std::make_unique<TestDecoder>(96000, 24, 2, 100, 0x02);

    engine.setCurrentTrack(std::move(track1), "track1.wav");
    engine.queueNextTrack(std::move(track2), "track2.flac");

    EXPECT_TRUE(engine.willFormatChange());
}

TEST(GaplessEngine, FormatChangeTransitionCallback) {
    GaplessEngine engine;

    auto track1 = std::make_unique<TestDecoder>(44100, 16, 2, 50, 0x01);
    auto track2 = std::make_unique<TestDecoder>(96000, 24, 2, 50, 0x02);

    bool callbackFired = false;
    TrackTransitionInfo capturedInfo;

    engine.setTransitionCallback([&](const TrackTransitionInfo& info) {
        callbackFired = true;
        capturedInfo = info;
    });

    engine.setCurrentTrack(std::move(track1), "track1.wav");
    engine.queueNextTrack(std::move(track2), "track2.flac");

    // Read past the first track - need buffer big enough for either format
    std::vector<uint8_t> buffer(4096);
    engine.read(buffer.data(), 100);

    EXPECT_TRUE(callbackFired);
    EXPECT_TRUE(capturedInfo.formatChanged);
    EXPECT_TRUE(capturedInfo.requiresReconfigure);
}

// --- Buffer continuity ---

TEST(GaplessEngine, BufferContinuity) {
    GaplessEngine engine;

    // Track 1: 10 frames, fill=0x10
    auto track1 = std::make_unique<TestDecoder>(44100, 16, 1, 10, 0x10);
    // Track 2: 10 frames, fill=0x20
    auto track2 = std::make_unique<TestDecoder>(44100, 16, 1, 10, 0x20);

    engine.setCurrentTrack(std::move(track1), "t1.wav");
    engine.queueNextTrack(std::move(track2), "t2.wav");

    // Read all 20 frames in one call (2 bytes per frame for 16-bit mono)
    std::vector<uint8_t> buffer(40);
    size_t framesRead = engine.read(buffer.data(), 20);

    EXPECT_EQ(framesRead, 20u);

    // Verify first track data (fill=0x10, frame offset pattern)
    EXPECT_EQ(buffer[0], static_cast<uint8_t>(0x10));  // Frame 0

    // Verify second track data starts at frame 10 (fill=0x20)
    EXPECT_EQ(buffer[20], static_cast<uint8_t>(0x20));  // Frame 0 of track 2
}

// --- No next track ---

TEST(GaplessEngine, FinishedWhenNoNextTrack) {
    GaplessEngine engine;

    auto track1 = std::make_unique<TestDecoder>(44100, 16, 2, 50, 0x01);
    engine.setCurrentTrack(std::move(track1));
    // No next track queued

    std::vector<uint8_t> buffer(400);
    size_t framesRead = engine.read(buffer.data(), 100);

    EXPECT_EQ(framesRead, 50u);  // Only track1's frames
    EXPECT_TRUE(engine.isFinished());
}

TEST(GaplessEngine, HasNextTrack) {
    GaplessEngine engine;

    auto track1 = std::make_unique<TestDecoder>(44100, 16, 2, 100, 0x01);
    auto track2 = std::make_unique<TestDecoder>(44100, 16, 2, 100, 0x02);

    engine.setCurrentTrack(std::move(track1));
    EXPECT_FALSE(engine.hasNextTrack());

    engine.queueNextTrack(std::move(track2));
    EXPECT_TRUE(engine.hasNextTrack());
}

// --- Reset ---

TEST(GaplessEngine, Reset) {
    GaplessEngine engine;

    auto track1 = std::make_unique<TestDecoder>(44100, 16, 2, 100, 0x01);
    auto track2 = std::make_unique<TestDecoder>(44100, 16, 2, 100, 0x02);

    engine.setCurrentTrack(std::move(track1));
    engine.queueNextTrack(std::move(track2));
    engine.reset();

    EXPECT_FALSE(engine.hasNextTrack());
    EXPECT_FALSE(engine.isFinished());

    std::vector<uint8_t> buffer(100);
    EXPECT_EQ(engine.read(buffer.data(), 10), 0u);
}

// --- ClearNextTrack ---

TEST(GaplessEngine, ClearNextTrack) {
    GaplessEngine engine;

    auto track1 = std::make_unique<TestDecoder>(44100, 16, 2, 100, 0x01);
    auto track2 = std::make_unique<TestDecoder>(44100, 16, 2, 100, 0x02);

    engine.setCurrentTrack(std::move(track1));
    engine.queueNextTrack(std::move(track2));
    EXPECT_TRUE(engine.hasNextTrack());

    engine.clearNextTrack();
    EXPECT_FALSE(engine.hasNextTrack());
}

// --- Format queries ---

TEST(GaplessEngine, GetFormats) {
    GaplessEngine engine;

    auto track1 = std::make_unique<TestDecoder>(44100, 16, 2, 100, 0x01);
    auto track2 = std::make_unique<TestDecoder>(96000, 24, 2, 200, 0x02);

    engine.setCurrentTrack(std::move(track1));
    engine.queueNextTrack(std::move(track2));

    AudioFormat currentFmt = engine.getCurrentFormat();
    EXPECT_EQ(currentFmt.sampleRate, 44100u);
    EXPECT_EQ(currentFmt.bitsPerSample, 16);

    AudioFormat nextFmt = engine.getNextFormat();
    EXPECT_EQ(nextFmt.sampleRate, 96000u);
    EXPECT_EQ(nextFmt.bitsPerSample, 24);
}
