#include "gapless_engine.h"
#include <cstring>
#include <algorithm>

namespace bitperfect {
namespace audio {

GaplessEngine::GaplessEngine() = default;
GaplessEngine::~GaplessEngine() = default;

void GaplessEngine::setCurrentTrack(std::unique_ptr<decoder::AudioDecoder> decoder,
                                     const std::string& path) {
    currentDecoder_ = std::move(decoder);
    currentPath_ = path;
    finished_ = false;
}

void GaplessEngine::queueNextTrack(std::unique_ptr<decoder::AudioDecoder> decoder,
                                    const std::string& path) {
    nextDecoder_ = std::move(decoder);
    nextPath_ = path;

    // Pre-buffer the next track if format is the same
    if (nextDecoder_ && currentDecoder_ && !willFormatChange()) {
        decoder::AudioFormat fmt = nextDecoder_->getFormat();
        size_t bytesPerFrame = fmt.bytesPerFrame();
        if (bytesPerFrame > 0) {
            size_t preBufferBytes = preBufferSize_ * bytesPerFrame;
            preBuffer_.resize(preBufferBytes);
            size_t framesRead = nextDecoder_->read(preBuffer_.data(), preBufferSize_);
            preBufferFilled_ = framesRead * bytesPerFrame;
            preBufferConsumed_ = 0;
        }
    }
}

size_t GaplessEngine::read(uint8_t* buffer, size_t frames) {
    if (!currentDecoder_ || finished_) {
        return 0;
    }

    size_t totalFramesRead = 0;
    size_t remainingFrames = frames;
    decoder::AudioFormat fmt = currentDecoder_->getFormat();
    size_t bytesPerFrame = fmt.bytesPerFrame();
    if (bytesPerFrame == 0) return 0;

    // Read from current decoder
    size_t framesRead = currentDecoder_->read(buffer, remainingFrames);
    totalFramesRead += framesRead;
    remainingFrames -= framesRead;

    // If current track ended and we have remaining space
    if (framesRead < frames && remainingFrames > 0) {
        if (nextDecoder_) {
            // Perform track transition
            performTransition();

            if (currentDecoder_) {
                // First, flush any pre-buffered data
                if (preBufferFilled_ > preBufferConsumed_) {
                    size_t availableBytes = preBufferFilled_ - preBufferConsumed_;
                    size_t availableFrames = availableBytes / bytesPerFrame;
                    size_t framesToCopy = std::min(remainingFrames, availableFrames);
                    size_t bytesToCopy = framesToCopy * bytesPerFrame;

                    memcpy(buffer + totalFramesRead * bytesPerFrame,
                           preBuffer_.data() + preBufferConsumed_,
                           bytesToCopy);
                    preBufferConsumed_ += bytesToCopy;
                    totalFramesRead += framesToCopy;
                    remainingFrames -= framesToCopy;
                }

                // Then read more from the (now current) decoder
                if (remainingFrames > 0) {
                    size_t moreFrames = currentDecoder_->read(
                        buffer + totalFramesRead * bytesPerFrame,
                        remainingFrames);
                    totalFramesRead += moreFrames;
                }
            }
        } else {
            // No next track - we are done
            finished_ = true;
        }
    }

    return totalFramesRead;
}

void GaplessEngine::performTransition() {
    TrackTransitionInfo info;
    info.completedTrackPath = currentPath_;
    info.nextTrackPath = nextPath_;
    info.formatChanged = willFormatChange();
    info.requiresReconfigure = info.formatChanged;

    // Swap decoders
    currentDecoder_ = std::move(nextDecoder_);
    currentPath_ = std::move(nextPath_);
    nextPath_.clear();

    // Notify via callback
    if (transitionCallback_) {
        transitionCallback_(info);
    }
}

bool GaplessEngine::willFormatChange() const {
    if (!currentDecoder_ || !nextDecoder_) return false;

    decoder::AudioFormat currentFmt = currentDecoder_->getFormat();
    decoder::AudioFormat nextFmt = nextDecoder_->getFormat();

    return currentFmt != nextFmt;
}

decoder::AudioFormat GaplessEngine::getCurrentFormat() const {
    if (currentDecoder_) {
        return currentDecoder_->getFormat();
    }
    return decoder::AudioFormat{};
}

decoder::AudioFormat GaplessEngine::getNextFormat() const {
    if (nextDecoder_) {
        return nextDecoder_->getFormat();
    }
    return decoder::AudioFormat{};
}

void GaplessEngine::setTransitionCallback(TrackTransitionCallback callback) {
    transitionCallback_ = std::move(callback);
}

void GaplessEngine::clearNextTrack() {
    nextDecoder_.reset();
    nextPath_.clear();
    preBuffer_.clear();
    preBufferFilled_ = 0;
    preBufferConsumed_ = 0;
}

void GaplessEngine::reset() {
    currentDecoder_.reset();
    nextDecoder_.reset();
    currentPath_.clear();
    nextPath_.clear();
    preBuffer_.clear();
    preBufferFilled_ = 0;
    preBufferConsumed_ = 0;
    finished_ = false;
}

} // namespace audio
} // namespace bitperfect
