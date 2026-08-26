#pragma once

#include "../usb/usb_audio_device.h"
#include "../usb/usb_control.h"
#include "../usb/isochronous_transfer.h"
#include "../usb/usbdevfs_iso_backend.h"
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
#include <mutex>
#include <unordered_map>

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
     * Attach an opened USB audio device so playback can reach hardware.
     *
     * Android does not let native code open a USB device, so the Java layer must
     * open it, claim the streaming interface, select the alternate setting, and
     * pass the resulting descriptor down. The descriptor is duplicated here, so
     * the caller may close its own copy.
     *
     * Call after parseDevice() and before configure(): configure() reads the
     * endpoint out of the parsed descriptors and hands it to the transport.
     *
     * @param fd            UsbDeviceConnection.getFileDescriptor()
     * @param interfaceNum  Streaming interface that was claimed
     * @param altSetting    Alternate setting that was selected
     * @return true when the descriptor was duplicated successfully
     */
    bool attachUsbDevice(int fd, uint8_t interfaceNum, uint8_t altSetting);

    /**
     * Detach the USB device. Stops playback first, because the transport cannot
     * outlive the descriptor it is submitting against.
     */
    void detachUsbDevice();

    /**
     * Whether audio is actually being transmitted to a USB device.
     *
     * This is deliberately distinct from getState() == PLAYING. The engine can
     * be PLAYING and accepting data with no device attached, in which case the
     * data goes nowhere. Diagnostics and the UI use this so that state can never
     * again be presented as USB output when it is not.
     */
    bool isUsbOutputActive() const;

    /**
     * Whether a USB device is attached and could carry playback.
     *
     * Distinct from isUsbOutputActive(), which is only true once a stream is
     * running. Callers deciding *where to send* the next track need this one.
     */
    bool isUsbDeviceAttached() const;

    /** Name of the transport in use, for diagnostics. */
    std::string getTransportName() const;

    /** Bytes actually accepted by the USB transport. 0 when not hardware-backed. */
    uint64_t getUsbBytesTransferred() const;

    /** Isochronous transfer errors reported by the kernel. */
    uint64_t getUsbTransferErrors() const;

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
     * Information for an incrementally decoded PCM file.
     */
    struct DecoderInfo {
        uint32_t sampleRate = 0;
        uint8_t bitsPerSample = 0;
        uint8_t channels = 0;
        uint64_t totalFrames = 0;
    };

    /**
     * Open a WAV or FLAC decoder session. The returned opaque ID remains valid
     * until closeDecoder() or shutdown().
     */
    uint64_t openDecoder(const std::string& path);

    /** Get immutable format information for an open decoder session. */
    bool getDecoderInfo(uint64_t sessionId, DecoderInfo& info) const;

    /**
     * Decode up to maxFrames into a caller-owned PCM buffer.
     * @return decoded frame count, 0 at EOF, or -1 for an invalid session.
     */
    int64_t readDecoder(uint64_t sessionId, uint8_t* buffer, size_t maxFrames);

    /** Seek and return the resulting frame position, or -1 on failure. */
    int64_t seekDecoder(uint64_t sessionId, uint64_t frameIndex);

    /** Close a decoder session. Repeated close calls are harmless. */
    void closeDecoder(uint64_t sessionId);

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

    struct DecoderSession {
        std::mutex mutex;
        std::unique_ptr<decoder::AudioDecoder> decoder;
        decoder::AudioFormat format;
    };

    std::shared_ptr<DecoderSession> findDecoderSession(uint64_t sessionId) const;
    void closeAllDecoders();

    /**
     * Find the isochronous OUT endpoint for a rate and format on a streaming
     * interface. Returns nullptr when the device exposes no matching endpoint.
     */
    const usb::EndpointDescriptor* findOutputEndpoint(uint8_t interfaceNum,
                                                      uint32_t sampleRate,
                                                      usb::PcmFormat format) const;

    mutable std::mutex decoderSessionsMutex_;
    std::unordered_map<uint64_t, std::shared_ptr<DecoderSession>> decoderSessions_;
    std::atomic<uint64_t> nextDecoderSessionId_{1};

    // Configuration
    PlaybackConfig currentConfig_;
    usb::ControlTransferFunc controlTransferFunc_;

    // USB hardware attachment. Shared with IsochronousTransfer, which submits
    // through it from the audio thread and reaps on its own thread.
    std::shared_ptr<usb::UsbdevfsIsoBackend> usbBackend_;
    uint8_t claimedInterface_ = 0;
    uint8_t claimedAltSetting_ = 0;

    // Track transition callback for gapless playback JNI notification
    std::function<void(void*)> trackTransitionCallback_;
    void* javaVm_ = nullptr;
};

} // namespace jni
} // namespace bitperfect
