#pragma once

#include "../decoder/audio_decoder.h"
#include <cstdint>
#include <cstddef>
#include <memory>
#include <functional>
#include <vector>
#include <string>

namespace bitperfect {
namespace audio {

/**
 * Track transition information passed to callback.
 */
struct TrackTransitionInfo {
    std::string completedTrackPath;
    std::string nextTrackPath;
    bool formatChanged = false;       // True if sample rate/bit depth/channels changed
    bool requiresReconfigure = false; // True if DAC needs reconfiguration
};

/**
 * Track transition callback type.
 */
using TrackTransitionCallback = std::function<void(const TrackTransitionInfo&)>;

/**
 * GaplessEngine - manages seamless track transitions.
 *
 * Design principles:
 * - Pre-decodes the next track while the current track is playing
 * - If the next track has the same format (sample rate, bit depth, channels),
 *   simply swap the buffer source with no USB restart needed
 * - If the format changes, perform a safe DAC reconfiguration:
 *   drain current buffer, reconfigure, then start new track
 * - Never introduces an audible gap, click, or pop
 * - Notifies UI of track transitions via callback
 *
 * Buffer management:
 * - Current track reads into the output buffer
 * - When current track runs out of data, immediately switches to next track
 * - The next track decoder is pre-opened and pre-buffered so data is ready
 *   at the moment of transition
 */
class GaplessEngine {
public:
    GaplessEngine();
    ~GaplessEngine();

    /**
     * Set the current track decoder.
     * @param decoder Decoder for the current track (takes ownership)
     * @param path Track file path (for transition info)
     */
    void setCurrentTrack(std::unique_ptr<decoder::AudioDecoder> decoder,
                         const std::string& path = "");

    /**
     * Queue the next track for gapless transition.
     * The decoder should already be opened and ready.
     * @param decoder Decoder for the next track (takes ownership)
     * @param path Track file path (for transition info)
     */
    void queueNextTrack(std::unique_ptr<decoder::AudioDecoder> decoder,
                        const std::string& path = "");

    /**
     * Read audio data from the engine.
     * Handles transitions transparently - when the current track ends,
     * seamlessly switches to the next track.
     * @param buffer Output buffer for PCM data
     * @param frames Number of frames to read
     * @return Number of frames actually read
     */
    size_t read(uint8_t* buffer, size_t frames);

    /**
     * Check if a format change will occur at the next transition.
     * @return true if the next track has a different format
     */
    bool willFormatChange() const;

    /**
     * Get the current audio format.
     */
    decoder::AudioFormat getCurrentFormat() const;

    /**
     * Get the next track's format (if queued).
     */
    decoder::AudioFormat getNextFormat() const;

    /**
     * Check if there is a next track queued.
     */
    bool hasNextTrack() const { return nextDecoder_ != nullptr; }

    /**
     * Check if the current track has ended and no next track is available.
     */
    bool isFinished() const { return finished_; }

    /**
     * Set the track transition callback.
     */
    void setTransitionCallback(TrackTransitionCallback callback);

    /**
     * Clear the next track.
     */
    void clearNextTrack();

    /**
     * Reset the engine (stop everything).
     */
    void reset();

    /**
     * Get the pre-buffer size in frames.
     */
    size_t getPreBufferSize() const { return preBufferSize_; }

    /**
     * Set the pre-buffer size in frames.
     */
    void setPreBufferSize(size_t frames) { preBufferSize_ = frames; }

private:
    void performTransition();

    std::unique_ptr<decoder::AudioDecoder> currentDecoder_;
    std::unique_ptr<decoder::AudioDecoder> nextDecoder_;
    std::string currentPath_;
    std::string nextPath_;

    TrackTransitionCallback transitionCallback_;
    bool finished_ = false;
    size_t preBufferSize_ = 4096;  // Frames to pre-buffer for next track

    // Pre-buffer for seamless transitions
    std::vector<uint8_t> preBuffer_;
    size_t preBufferFilled_ = 0;
    size_t preBufferConsumed_ = 0;
};

} // namespace audio
} // namespace bitperfect
