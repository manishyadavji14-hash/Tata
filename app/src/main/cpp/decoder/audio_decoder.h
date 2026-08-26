#pragma once

#include <cstdint>
#include <cstddef>
#include <string>

namespace bitperfect {
namespace decoder {

/**
 * Audio format information returned by decoders.
 */
struct AudioFormat {
    uint32_t sampleRate = 0;
    uint8_t bitsPerSample = 0;
    uint8_t channels = 0;
    uint64_t totalFrames = 0;    // Total frames (samples per channel)

    uint32_t bytesPerFrame() const {
        return (bitsPerSample / 8) * channels;
    }

    uint32_t bytesPerSecond() const {
        return bytesPerFrame() * sampleRate;
    }

    double durationSeconds() const {
        if (sampleRate == 0) return 0.0;
        return static_cast<double>(totalFrames) / sampleRate;
    }

    bool operator==(const AudioFormat& other) const {
        return sampleRate == other.sampleRate &&
               bitsPerSample == other.bitsPerSample &&
               channels == other.channels;
    }

    bool operator!=(const AudioFormat& other) const {
        return !(*this == other);
    }
};

/**
 * Seek position for decoder.
 */
struct SeekPosition {
    uint64_t frameIndex = 0;     // Target frame (sample) number

    static SeekPosition fromSeconds(double seconds, uint32_t sampleRate) {
        SeekPosition pos;
        pos.frameIndex = static_cast<uint64_t>(seconds * sampleRate);
        return pos;
    }
};

/**
 * Abstract base class for audio decoders.
 *
 * Provides a uniform interface for opening, reading, seeking, and closing
 * audio files of different formats. Implementations handle format-specific
 * parsing and decoding.
 */
class AudioDecoder {
public:
    virtual ~AudioDecoder() = default;

    /**
     * Open an audio file for decoding.
     * @param path File path to open
     * @return true if the file was opened successfully
     */
    virtual bool open(const std::string& path) = 0;

    /**
     * Read decoded PCM frames from the file.
     * @param buffer Destination buffer for PCM data
     * @param frames Maximum number of frames to read
     * @return Number of frames actually read (0 at end of file)
     */
    virtual size_t read(uint8_t* buffer, size_t frames) = 0;

    /**
     * Seek to a specific position in the file.
     * @param position Target seek position
     * @return true if seek was successful
     */
    virtual bool seek(const SeekPosition& position) = 0;

    /**
     * Close the file and release resources.
     */
    virtual void close() = 0;

    /**
     * Get the audio format of the opened file.
     * @return Format information (valid only after successful open())
     */
    virtual AudioFormat getFormat() const = 0;

    /**
     * Get the total duration in seconds.
     * @return Duration in seconds (0 if unknown)
     */
    virtual double getDuration() const = 0;

    /**
     * Check if a file is currently open.
     * @return true if a file is open and ready for reading
     */
    virtual bool isOpen() const = 0;

    /**
     * Get current position in frames.
     * @return Current frame position
     */
    virtual uint64_t getPosition() const = 0;

    /**
     * Get the file type name (e.g., "WAV", "FLAC", "DSF").
     */
    virtual const char* getTypeName() const = 0;
};

} // namespace decoder
} // namespace bitperfect
