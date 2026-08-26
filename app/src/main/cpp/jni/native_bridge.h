#pragma once

#include "../usb/usb_audio_device.h"
#include "../usb/usb_control.h"
#include "../usb/isochronous_transfer.h"
#include "../pcm/pcm_engine.h"
#include "../audio/sample_rate_manager.h"
#include "../buffer/audio_buffer_manager.h"
#include "../diagnostics/diagnostics.h"
#include "../audio/format_detector.h"
#include "../decoder/decoder_factory.h"

#include <memory>
#include <string>
#include <vector>
#include <atomic>
#include <functional>

namespace bitperfect {
namespace jni {

/**
 * Engine state.
 */
enum class EngineState : uint8_t {
    UNINITIALIZED = 0,
    INITIALIZED,
    CONFIGURED,
    PLAYING,
    PAUSED,
    STOPPED,
    ERROR
};

/**
 * Device information passed to Kotlin layer.
 */
struct DeviceInfo {
    uint16_t vendorId = 0;
    uint16_t productId = 0;
    std::string deviceName;
    std::vector<uint32_t> supportedRates;
    std::vector<uint8_t> supportedBitDepths;
    uint8_t maxChannels = 0;
    bool supportsAsync = false;
};

/**
 * Playback format configuration.
 */
struct PlaybackConfig {
    uint32_t sampleRate = 44100;
    usb::PcmFormat format = usb::PcmFormat::S16_LE;
    uint8_t channels = 2;
    uint32_t bufferSizeMs = 50;
};

/**
 * NativeBridge - JNI interface for the native audio engine.
 *
 * This class is the single entry point from Kotlin to the native engine.
 * It manages the lifecycle of all native components and exposes them
 * via JNI-compatible methods.
 */
class NativeBridge {
public:
    static NativeBridge& instance();

    /**
     * Initialize the engine.
     */
    bool initialize();

    /**
     * Shutdown and release all resources.
     */
    void shutdown();

    /**
     * Parse USB device descriptors.
     * @param descriptorData Raw USB configuration descriptor
     * @param length Length of descriptor data
     * @return true if audio device was found
     */
    bool parseDevice(const uint8_t* descriptorData, size_t length);

    /**
     * Get discovered device information.
     */
    DeviceInfo getDeviceInfo() const;

    /**
     * Configure for playback.
     */
    bool configure(const PlaybackConfig& config);

    /**
     * Start playback.
     */
    bool startPlayback();

    /**
     * Pause playback.
     */
    bool pausePlayback();

    /**
     * Resume playback.
     */
    bool resumePlayback();

    /**
     * Stop playback.
     */
    void stopPlayback();

    /**
     * Write audio data to the playback buffer.
     * @param data PCM audio data
     * @param length Data length in bytes
     * @return Bytes accepted
     */
    size_t writeAudioData(const uint8_t* data, size_t length);

    /**
     * Get current engine state.
     */
    EngineState getState() const { return state_.load(); }

    /**
     * Get buffer fill level (0.0 - 1.0).
     */
    float getBufferLevel() const;

    /**
     * Get buffer statistics.
     */
    buffer::BufferStatistics getBufferStatistics() const;

    /**
     * Get diagnostic statistics.
     */
    diagnostics::DiagnosticStats getDiagnosticStats() const;

    /**
     * Set the USB control transfer function (called from JNI with Android USB APIs).
     */
    void setControlTransferFunction(usb::ControlTransferFunc func);

    /**
     * Get current sample rate.
     */
    uint32_t getCurrentSampleRate() const { return currentConfig_.sampleRate; }

    /**
     * Get current format.
     */
    usb::PcmFormat getCurrentFormat() const { return currentConfig_.format; }

    /**
     * Get current channels.
     */
    uint8_t getCurrentChannels() const { return currentConfig_.channels; }

    /**
     * Detect the format of an audio file.
     */
    struct FormatInfo {
        uint32_t sampleRate = 0;
        uint8_t bitsPerSample = 0;
        uint8_t channels = 0;
    };
    FormatInfo detectFormat(const std::string& path) const;

    /**
     * Track transition callback type for gapless playback.
     */
    using TrackTransitionJniCallback = std::function<void(void*)>;

    /**
     * Set the track transition callback (called from JNI registration).
     */
    void setTrackTransitionCallback(std::function<void(void*)> callback) {
        trackTransitionCallback_ = std::move(callback);
    }

    /**
     * Notify of a track transition (called from gapless engine).
     */
    void notifyTrackTransition() {
        if (trackTransitionCallback_) {
            trackTransitionCallback_(javaVm_);
        }
    }

    /**
     * Store the JavaVM pointer for JNI callbacks.
     */
    void setJavaVm(void* vm) { javaVm_ = vm; }

private:
    NativeBridge() = default;
    ~NativeBridge() = default;

    std::atomic<EngineState> state_{EngineState::UNINITIALIZED};

    // Engine components
    std::unique_ptr<usb::UsbAudioDevice> audioDevice_;
    std::unique_ptr<usb::UsbControl> usbControl_;
    std::unique_ptr<usb::IsochronousTransfer> isoTransfer_;
    std::unique_ptr<pcm::PcmEngine> pcmEngine_;
    std::unique_ptr<audio::SampleRateManager> sampleRateManager_;
    std::unique_ptr<buffer::AudioBufferManager> bufferManager_;

    // Configuration
    PlaybackConfig currentConfig_;
    usb::ControlTransferFunc controlTransferFunc_;

    // Track transition callback for gapless playback JNI notification
    std::function<void(void*)> trackTransitionCallback_;
    void* javaVm_ = nullptr;
};

} // namespace jni
} // namespace bitperfect
