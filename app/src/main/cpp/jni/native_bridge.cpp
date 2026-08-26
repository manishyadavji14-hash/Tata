#include "native_bridge.h"
#include <cstring>

namespace bitperfect {
namespace jni {

NativeBridge& NativeBridge::instance() {
    static NativeBridge instance;
    return instance;
}

bool NativeBridge::initialize() {
    if (state_.load() != EngineState::UNINITIALIZED &&
        state_.load() != EngineState::STOPPED &&
        state_.load() != EngineState::ERROR) {
        return false;
    }

    audioDevice_ = std::make_unique<usb::UsbAudioDevice>();
    pcmEngine_ = std::make_unique<pcm::PcmEngine>();
    sampleRateManager_ = std::make_unique<audio::SampleRateManager>();

    diagnostics::Diagnostics::instance().logMessage(
        diagnostics::LogLevel::INFO,
        diagnostics::LogCategory::BOOT,
        "NativeBridge initialized"
    );

    state_.store(EngineState::INITIALIZED);
    return true;
}

void NativeBridge::shutdown() {
    stopPlayback();

    audioDevice_.reset();
    usbControl_.reset();
    isoTransfer_.reset();
    pcmEngine_.reset();
    sampleRateManager_.reset();
    bufferManager_.reset();

    state_.store(EngineState::UNINITIALIZED);

    diagnostics::Diagnostics::instance().logMessage(
        diagnostics::LogLevel::INFO,
        diagnostics::LogCategory::BOOT,
        "NativeBridge shutdown"
    );
}

bool NativeBridge::parseDevice(const uint8_t* descriptorData, size_t length) {
    if (!audioDevice_ || !descriptorData || length == 0) return false;

    bool success = audioDevice_->parseDescriptors(descriptorData, length);
    if (success) {
        sampleRateManager_->initFromDescriptors(audioDevice_->getDescriptors());

        diagnostics::Diagnostics::instance().logMessage(
            diagnostics::LogLevel::INFO,
            diagnostics::LogCategory::USB,
            "Device parsed successfully"
        );
    } else {
        diagnostics::Diagnostics::instance().recordError(
            diagnostics::LogCategory::USB,
            "Failed to parse device descriptors"
        );
    }

    return success;
}

DeviceInfo NativeBridge::getDeviceInfo() const {
    DeviceInfo info;
    if (!audioDevice_) return info;

    const auto& desc = audioDevice_->getDescriptors();
    info.vendorId = desc.vendorId;
    info.productId = desc.productId;
    info.deviceName = desc.deviceName;
    info.supportedRates = sampleRateManager_->getSupportedRates();

    // Collect supported bit depths from streaming interfaces
    for (const auto& si : desc.streamingInterfaces) {
        for (const auto& alt : si.altSettings) {
            uint8_t bits = 0;
            if (desc.version == usb::UacVersion::UAC1) {
                bits = alt.format_uac1.bBitResolution;
            } else {
                bits = alt.format_uac2.bBitResolution;
            }
            if (bits > 0) {
                bool found = false;
                for (uint8_t b : info.supportedBitDepths) {
                    if (b == bits) { found = true; break; }
                }
                if (!found) info.supportedBitDepths.push_back(bits);
            }
        }
    }

    // Check for async endpoints
    for (const auto& si : desc.streamingInterfaces) {
        for (const auto& alt : si.altSettings) {
            if (alt.endpoint.syncType() == usb::SyncType::ASYNC) {
                info.supportsAsync = true;
            }
        }
    }

    return info;
}

bool NativeBridge::configure(const PlaybackConfig& config) {
    if (state_.load() != EngineState::INITIALIZED &&
        state_.load() != EngineState::CONFIGURED &&
        state_.load() != EngineState::STOPPED) {
        return false;
    }

    currentConfig_ = config;

    // Configure PCM engine
    pcm::PcmFormatInfo formatInfo;
    formatInfo.format = config.format;
    formatInfo.channels = config.channels;
    formatInfo.sampleRate = config.sampleRate;
    formatInfo.bitsPerSample = pcm::PcmEngine::getBitsPerSample(config.format);
    formatInfo.bytesPerSample = pcm::PcmEngine::getBytesPerSample(config.format);
    pcmEngine_->configure(formatInfo);

    // Setup buffer manager
    uint32_t bytesPerSecond = formatInfo.bytesPerSecond();
    size_t bufferSize = (config.bufferSizeMs * bytesPerSecond) / 1000;
    if (bufferSize < 4096) bufferSize = 4096;
    bufferManager_ = std::make_unique<buffer::AudioBufferManager>(bufferSize, 0.5f);

    // Setup isochronous transfer
    if (audioDevice_) {
        auto outputIface = audioDevice_->getOutputStreamingInterface();
        if (outputIface) {
            isoTransfer_ = std::make_unique<usb::IsochronousTransfer>();
            usb::IsoTransferConfig isoConfig;
            isoConfig.maxPacketSize = usb::IsochronousTransfer::calculateNominalPacketSize(
                config.sampleRate, formatInfo.bytesPerFrame());
            isoConfig.packetsPerTransfer = usb::ISO_PACKETS_PER_URB;
            isoConfig.queueDepth = usb::DEFAULT_URB_COUNT;
            isoTransfer_->configure(isoConfig);
        }
    }

    // Negotiate sample rate
    if (sampleRateManager_ && !sampleRateManager_->getSupportedRates().empty()) {
        auto result = sampleRateManager_->negotiateRate(config.sampleRate, usbControl_.get());
        if (!result.success && usbControl_) {
            diagnostics::Diagnostics::instance().recordError(
                diagnostics::LogCategory::CLOCK,
                "Rate negotiation failed: " + result.errorMessage
            );
        }
    }

    state_.store(EngineState::CONFIGURED);
    return true;
}

bool NativeBridge::startPlayback() {
    if (state_.load() != EngineState::CONFIGURED &&
        state_.load() != EngineState::PAUSED) {
        return false;
    }

    state_.store(EngineState::PLAYING);

    diagnostics::Diagnostics::instance().logMessage(
        diagnostics::LogLevel::INFO,
        diagnostics::LogCategory::PCM,
        "Playback started"
    );

    return true;
}

bool NativeBridge::pausePlayback() {
    if (state_.load() != EngineState::PLAYING) return false;

    state_.store(EngineState::PAUSED);
    return true;
}

bool NativeBridge::resumePlayback() {
    if (state_.load() != EngineState::PAUSED) return false;

    state_.store(EngineState::PLAYING);
    return true;
}

void NativeBridge::stopPlayback() {
    if (state_.load() == EngineState::UNINITIALIZED) return;

    if (isoTransfer_ && isoTransfer_->isActive()) {
        isoTransfer_->stop();
    }

    if (bufferManager_) {
        bufferManager_->reset();
    }

    if (state_.load() != EngineState::UNINITIALIZED) {
        state_.store(EngineState::STOPPED);
    }

    diagnostics::Diagnostics::instance().logMessage(
        diagnostics::LogLevel::INFO,
        diagnostics::LogCategory::PCM,
        "Playback stopped"
    );
}

size_t NativeBridge::writeAudioData(const uint8_t* data, size_t length) {
    if (!bufferManager_ || !data || length == 0) return 0;
    if (state_.load() != EngineState::PLAYING &&
        state_.load() != EngineState::CONFIGURED) return 0;

    return bufferManager_->write(data, length);
}

float NativeBridge::getBufferLevel() const {
    if (!bufferManager_) return 0.0f;
    return bufferManager_->getFillLevel();
}

buffer::BufferStatistics NativeBridge::getBufferStatistics() const {
    if (!bufferManager_) return {};
    return bufferManager_->getStatistics();
}

diagnostics::DiagnosticStats NativeBridge::getDiagnosticStats() const {
    return diagnostics::Diagnostics::instance().getStats();
}

void NativeBridge::setControlTransferFunction(usb::ControlTransferFunc func) {
    controlTransferFunc_ = std::move(func);
    if (controlTransferFunc_) {
        usbControl_ = std::make_unique<usb::UsbControl>(controlTransferFunc_);
    }
}

} // namespace jni
} // namespace bitperfect
