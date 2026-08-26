#include "native_bridge.h"
#include <array>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>

#ifndef STANDALONE_TEST
#include <jni.h>

// === JNI Optimization: Cached IDs ===
// Caching class/method/field IDs avoids repeated lookups in hot paths.
// These are populated in JNI_OnLoad and remain valid for the VM lifetime.

namespace {

struct JniCache {
    // Class references (global refs to prevent GC)
    jclass engineClass = nullptr;
    jclass byteBufferClass = nullptr;

    // Method IDs for callbacks from native to Java
    jmethodID onStateChangedMethod = nullptr;
    jmethodID onBufferLevelChangedMethod = nullptr;
    jmethodID onErrorMethod = nullptr;

    // Field IDs for direct access
    jfieldID nativeHandleField = nullptr;

    bool initialized = false;
};

static JniCache g_jniCache;

} // anonymous namespace

/**
 * JNI_OnLoad - Called when the native library is loaded.
 * Caches class/method/field IDs to avoid repeated JNI lookups.
 * Uses GetDirectBufferAddress for zero-copy audio data transfer.
 */
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Store JavaVM for callbacks
    bitperfect::jni::NativeBridge::instance().setJavaVm(static_cast<void*>(vm));

    // Cache NativeAudioEngine class
    jclass engineClass = env->FindClass("com/bitperfect/android/engine/NativeAudioEngine");
    if (engineClass != nullptr) {
        g_jniCache.engineClass = static_cast<jclass>(env->NewGlobalRef(engineClass));
        env->DeleteLocalRef(engineClass);
    }

    // Cache ByteBuffer class for direct buffer operations
    jclass byteBufferClass = env->FindClass("java/nio/ByteBuffer");
    if (byteBufferClass != nullptr) {
        g_jniCache.byteBufferClass = static_cast<jclass>(env->NewGlobalRef(byteBufferClass));
        env->DeleteLocalRef(byteBufferClass);
    }

    g_jniCache.initialized = true;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    if (g_jniCache.engineClass != nullptr) {
        env->DeleteGlobalRef(g_jniCache.engineClass);
        g_jniCache.engineClass = nullptr;
    }
    if (g_jniCache.byteBufferClass != nullptr) {
        env->DeleteGlobalRef(g_jniCache.byteBufferClass);
        g_jniCache.byteBufferClass = nullptr;
    }
    g_jniCache.initialized = false;
}

// === JNI Native Method Implementations ===
// Use GetDirectBufferAddress for zero-copy audio data transfer.
// Batch operations to minimize JNI transitions.

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_initialize(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jboolean>(bitperfect::jni::NativeBridge::instance().initialize());
}

extern "C" JNIEXPORT void JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_shutdown(JNIEnv* /*env*/, jobject /*thiz*/) {
    bitperfect::jni::NativeBridge::instance().shutdown();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_parseDevice(
        JNIEnv* env, jobject /*thiz*/, jbyteArray descriptorData) {
    if (descriptorData == nullptr) return JNI_FALSE;

    jsize length = env->GetArrayLength(descriptorData);
    if (length == 0) return JNI_FALSE;

    // Use GetPrimitiveArrayCritical for zero-copy access (no GC during access)
    auto* data = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(descriptorData, nullptr));
    if (data == nullptr) return JNI_FALSE;

    bool result = bitperfect::jni::NativeBridge::instance().parseDevice(data, static_cast<size_t>(length));

    env->ReleasePrimitiveArrayCritical(descriptorData, data, JNI_ABORT);
    return static_cast<jboolean>(result);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_configure(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jint sampleRate, jint format, jint channels, jint bufferSizeMs) {
    bitperfect::jni::PlaybackConfig config;
    config.sampleRate = static_cast<uint32_t>(sampleRate);
    config.format = static_cast<bitperfect::usb::PcmFormat>(format);
    config.channels = static_cast<uint8_t>(channels);
    config.bufferSizeMs = static_cast<uint32_t>(bufferSizeMs);
    return static_cast<jboolean>(bitperfect::jni::NativeBridge::instance().configure(config));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_startPlayback(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jboolean>(bitperfect::jni::NativeBridge::instance().startPlayback());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_pausePlayback(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jboolean>(bitperfect::jni::NativeBridge::instance().pausePlayback());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_resumePlayback(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jboolean>(bitperfect::jni::NativeBridge::instance().resumePlayback());
}

extern "C" JNIEXPORT void JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_stopPlayback(JNIEnv* /*env*/, jobject /*thiz*/) {
    bitperfect::jni::NativeBridge::instance().stopPlayback();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_writeAudioData(
        JNIEnv* env, jobject /*thiz*/, jbyteArray data, jint offset, jint length) {
    if (data == nullptr || length <= 0) return 0;

    // Use GetDirectBufferAddress if available, otherwise GetPrimitiveArrayCritical
    // GetPrimitiveArrayCritical provides zero-copy access with GC suspension
    auto* bytes = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(data, nullptr));
    if (bytes == nullptr) return 0;

    size_t written = bitperfect::jni::NativeBridge::instance().writeAudioData(
            bytes + offset, static_cast<size_t>(length));

    env->ReleasePrimitiveArrayCritical(data, bytes, JNI_ABORT);
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_getState(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(bitperfect::jni::NativeBridge::instance().getState());
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_getBufferLevel(JNIEnv* /*env*/, jobject /*thiz*/) {
    return bitperfect::jni::NativeBridge::instance().getBufferLevel();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_getCurrentSampleRate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(bitperfect::jni::NativeBridge::instance().getCurrentSampleRate());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_getSupportedSampleRates(JNIEnv* env, jobject /*thiz*/) {
    auto info = bitperfect::jni::NativeBridge::instance().getDeviceInfo();
    jintArray result = env->NewIntArray(static_cast<jsize>(info.supportedRates.size()));
    if (result != nullptr && !info.supportedRates.empty()) {
        // Batch copy: single JNI transition for all rates
        std::vector<jint> rates(info.supportedRates.begin(), info.supportedRates.end());
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(rates.size()), rates.data());
    }
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_getSupportedBitDepths(JNIEnv* env, jobject /*thiz*/) {
    auto info = bitperfect::jni::NativeBridge::instance().getDeviceInfo();
    jintArray result = env->NewIntArray(static_cast<jsize>(info.supportedBitDepths.size()));
    if (result != nullptr && !info.supportedBitDepths.empty()) {
        std::vector<jint> depths(info.supportedBitDepths.begin(), info.supportedBitDepths.end());
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(depths.size()), depths.data());
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_getUnderrunCount(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto stats = bitperfect::jni::NativeBridge::instance().getBufferStatistics();
    return static_cast<jint>(stats.underrunCount);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_getTotalBytesTransferred(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto stats = bitperfect::jni::NativeBridge::instance().getBufferStatistics();
    return static_cast<jlong>(stats.totalBytesRead);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_getDeviceName(JNIEnv* env, jobject /*thiz*/) {
    auto info = bitperfect::jni::NativeBridge::instance().getDeviceInfo();
    return env->NewStringUTF(info.deviceName.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_getCurrentBitDepth(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto format = bitperfect::jni::NativeBridge::instance().getCurrentFormat();
    return static_cast<jint>(bitperfect::pcm::PcmEngine::getBitsPerSample(format));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_getCurrentChannels(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(bitperfect::jni::NativeBridge::instance().getCurrentChannels());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_nativeDetectFormat(JNIEnv* env, jobject /*thiz*/, jstring path) {
    if (path == nullptr) return nullptr;

    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    if (pathStr == nullptr) return nullptr;

    auto formatInfo = bitperfect::jni::NativeBridge::instance().detectFormat(pathStr);
    env->ReleaseStringUTFChars(path, pathStr);

    if (formatInfo.sampleRate == 0) return nullptr;

    jintArray result = env->NewIntArray(3);
    if (result == nullptr) return nullptr;

    jint values[3] = {
        static_cast<jint>(formatInfo.sampleRate),
        static_cast<jint>(formatInfo.bitsPerSample),
        static_cast<jint>(formatInfo.channels)
    };
    env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_nativeOpenDecoder(
        JNIEnv* env, jobject /*thiz*/, jstring path) {
    if (path == nullptr) return 0;

    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    if (pathStr == nullptr) return 0;

    uint64_t sessionId = bitperfect::jni::NativeBridge::instance().openDecoder(pathStr);
    env->ReleaseStringUTFChars(path, pathStr);
    return static_cast<jlong>(sessionId);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_nativeGetDecoderFormat(
        JNIEnv* env, jobject /*thiz*/, jlong sessionId) {
    if (sessionId <= 0) return nullptr;

    bitperfect::jni::NativeBridge::DecoderInfo info;
    if (!bitperfect::jni::NativeBridge::instance().getDecoderInfo(
            static_cast<uint64_t>(sessionId), info)) {
        return nullptr;
    }

    jlongArray result = env->NewLongArray(4);
    if (result == nullptr) return nullptr;

    const jlong values[4] = {
        static_cast<jlong>(info.sampleRate),
        static_cast<jlong>(info.bitsPerSample),
        static_cast<jlong>(info.channels),
        static_cast<jlong>(info.totalFrames)
    };
    env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_nativeReadDecoder(
        JNIEnv* env, jobject /*thiz*/, jlong sessionId,
        jobject outputBuffer, jint maxFrames) {
    if (sessionId <= 0 || outputBuffer == nullptr || maxFrames <= 0) return -1;

    void* output = env->GetDirectBufferAddress(outputBuffer);
    jlong capacity = env->GetDirectBufferCapacity(outputBuffer);
    if (output == nullptr || capacity <= 0) return -1;

    bitperfect::jni::NativeBridge::DecoderInfo info;
    auto& bridge = bitperfect::jni::NativeBridge::instance();
    if (!bridge.getDecoderInfo(static_cast<uint64_t>(sessionId), info)) return -1;

    const size_t bytesPerFrame =
        (static_cast<size_t>(info.bitsPerSample) / 8U) * info.channels;
    if (bytesPerFrame == 0 ||
        static_cast<size_t>(maxFrames) >
            std::numeric_limits<size_t>::max() / bytesPerFrame) {
        return -1;
    }

    const size_t required = static_cast<size_t>(maxFrames) * bytesPerFrame;
    if (required > static_cast<size_t>(capacity)) return -1;

    const int64_t framesRead = bridge.readDecoder(
        static_cast<uint64_t>(sessionId), static_cast<uint8_t*>(output),
        static_cast<size_t>(maxFrames));
    if (framesRead < 0 || framesRead > std::numeric_limits<jint>::max()) return -1;
    return static_cast<jint>(framesRead);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_nativeSeekDecoder(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong sessionId, jlong frameIndex) {
    if (sessionId <= 0 || frameIndex < 0) return -1;
    return static_cast<jlong>(bitperfect::jni::NativeBridge::instance().seekDecoder(
        static_cast<uint64_t>(sessionId), static_cast<uint64_t>(frameIndex)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_nativeCloseDecoder(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong sessionId) {
    if (sessionId <= 0) return;
    bitperfect::jni::NativeBridge::instance().closeDecoder(
        static_cast<uint64_t>(sessionId));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bitperfect_android_engine_NativeAudioEngine_registerTrackTransitionCallback(
        JNIEnv* env, jobject /*thiz*/, jobject controller) {
    if (controller == nullptr) return JNI_FALSE;

    // Store a global reference to the controller for callbacks
    jobject globalRef = env->NewGlobalRef(controller);
    if (globalRef == nullptr) return JNI_FALSE;

    // Cache the onTrackTransition method ID
    jclass clazz = env->GetObjectClass(controller);
    jmethodID method = env->GetMethodID(clazz, "onTrackTransition", "()V");
    env->DeleteLocalRef(clazz);

    if (method == nullptr) {
        env->DeleteGlobalRef(globalRef);
        return JNI_FALSE;
    }

    bitperfect::jni::NativeBridge::instance().setTrackTransitionCallback(
        [globalRef, method](void* vmPtr) {
            JavaVM* vm = static_cast<JavaVM*>(vmPtr);
            JNIEnv* callbackEnv = nullptr;
            bool attached = false;
            jint result = vm->GetEnv(reinterpret_cast<void**>(&callbackEnv), JNI_VERSION_1_6);
            if (result == JNI_EDETACHED) {
                JNIEnv* attachEnv = nullptr;
                vm->AttachCurrentThread(&attachEnv, nullptr);
                callbackEnv = attachEnv;
                attached = true;
            }
            if (callbackEnv != nullptr) {
                callbackEnv->CallVoidMethod(globalRef, method);
            }
            if (attached) {
                vm->DetachCurrentThread();
            }
        }
    );

    return JNI_TRUE;
}

#endif // !STANDALONE_TEST

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
    closeAllDecoders();

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

    // Activate isochronous transfer with supply/complete callbacks
    if (isoTransfer_ && bufferManager_) {
        auto supplyCallback = [this](uint8_t* buffer, size_t maxLength) -> size_t {
            // Read audio data from the buffer manager to supply to USB
            return bufferManager_->read(buffer, maxLength);
        };

        auto completeCallback = [this](const uint8_t* /*data*/, size_t length,
                                        usb::TransferStatus status) {
            if (status != usb::TransferStatus::COMPLETED) {
                diagnostics::Diagnostics::instance().recordError(
                    diagnostics::LogCategory::TRANSFER,
                    "Transfer completion error"
                );
            }
            (void)length;
        };

        if (!isoTransfer_->isActive()) {
            isoTransfer_->start(supplyCallback, completeCallback);
        }
    }

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

NativeBridge::FormatInfo NativeBridge::detectFormat(const std::string& path) const {
    FormatInfo info;

    // Use decoder factory to create appropriate decoder
    auto type = decoder::DecoderFactory::detectFromExtension(path);
    if (type == decoder::DecoderType::UNKNOWN) {
        return info;
    }

    auto dec = decoder::DecoderFactory::create(type);
    if (!dec) {
        return info;
    }

    if (!dec->open(path)) {
        return info;
    }

    auto format = dec->getFormat();
    info.sampleRate = format.sampleRate;
    info.bitsPerSample = format.bitsPerSample;
    info.channels = format.channels;

    dec->close();
    return info;
}

uint64_t NativeBridge::openDecoder(const std::string& path) {
    if (path.empty()) return 0;

    std::unique_ptr<decoder::AudioDecoder> audioDecoder;
    std::array<uint8_t, 12> magic{};
    if (FILE* file = std::fopen(path.c_str(), "rb")) {
        const size_t bytesRead = std::fread(magic.data(), 1, magic.size(), file);
        std::fclose(file);
        audioDecoder = decoder::DecoderFactory::createFromMagic(magic.data(), bytesRead);
    }
    if (!audioDecoder) {
        audioDecoder = decoder::DecoderFactory::createFromPath(path);
    }
    if (!audioDecoder || !audioDecoder->open(path)) return 0;

    const decoder::AudioFormat format = audioDecoder->getFormat();
    if (format.sampleRate == 0 || format.channels == 0 ||
        format.bitsPerSample == 0 || format.bytesPerFrame() == 0) {
        audioDecoder->close();
        return 0;
    }

    auto session = std::make_shared<DecoderSession>();
    session->format = format;
    session->decoder = std::move(audioDecoder);

    uint64_t sessionId = nextDecoderSessionId_.fetch_add(1, std::memory_order_relaxed);
    if (sessionId == 0) {
        sessionId = nextDecoderSessionId_.fetch_add(1, std::memory_order_relaxed);
    }

    {
        std::lock_guard<std::mutex> lock(decoderSessionsMutex_);
        decoderSessions_[sessionId] = std::move(session);
    }
    return sessionId;
}

std::shared_ptr<NativeBridge::DecoderSession> NativeBridge::findDecoderSession(
        uint64_t sessionId) const {
    if (sessionId == 0) return nullptr;
    std::lock_guard<std::mutex> lock(decoderSessionsMutex_);
    const auto it = decoderSessions_.find(sessionId);
    return it == decoderSessions_.end() ? nullptr : it->second;
}

bool NativeBridge::getDecoderInfo(uint64_t sessionId, DecoderInfo& info) const {
    const auto session = findDecoderSession(sessionId);
    if (!session) return false;

    std::lock_guard<std::mutex> lock(session->mutex);
    if (!session->decoder || !session->decoder->isOpen()) return false;
    info.sampleRate = session->format.sampleRate;
    info.bitsPerSample = session->format.bitsPerSample;
    info.channels = session->format.channels;
    info.totalFrames = session->format.totalFrames;
    return true;
}

int64_t NativeBridge::readDecoder(
        uint64_t sessionId, uint8_t* buffer, size_t maxFrames) {
    if (!buffer || maxFrames == 0 ||
        maxFrames > static_cast<size_t>(std::numeric_limits<int64_t>::max())) {
        return -1;
    }

    const auto session = findDecoderSession(sessionId);
    if (!session) return -1;

    std::lock_guard<std::mutex> lock(session->mutex);
    if (!session->decoder || !session->decoder->isOpen()) return -1;
    return static_cast<int64_t>(session->decoder->read(buffer, maxFrames));
}

int64_t NativeBridge::seekDecoder(uint64_t sessionId, uint64_t frameIndex) {
    const auto session = findDecoderSession(sessionId);
    if (!session) return -1;

    std::lock_guard<std::mutex> lock(session->mutex);
    if (!session->decoder || !session->decoder->isOpen()) return -1;

    decoder::SeekPosition position;
    position.frameIndex = frameIndex;
    if (!session->decoder->seek(position)) return -1;

    const uint64_t resultingFrame = session->decoder->getPosition();
    if (resultingFrame > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
        return -1;
    }
    return static_cast<int64_t>(resultingFrame);
}

void NativeBridge::closeDecoder(uint64_t sessionId) {
    std::shared_ptr<DecoderSession> session;
    {
        std::lock_guard<std::mutex> lock(decoderSessionsMutex_);
        const auto it = decoderSessions_.find(sessionId);
        if (it == decoderSessions_.end()) return;
        session = std::move(it->second);
        decoderSessions_.erase(it);
    }

    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->decoder) {
        session->decoder->close();
        session->decoder.reset();
    }
}

void NativeBridge::closeAllDecoders() {
    std::unordered_map<uint64_t, std::shared_ptr<DecoderSession>> sessions;
    {
        std::lock_guard<std::mutex> lock(decoderSessionsMutex_);
        sessions.swap(decoderSessions_);
    }

    for (auto& entry : sessions) {
        const auto& session = entry.second;
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->decoder) {
            session->decoder->close();
            session->decoder.reset();
        }
    }
}

} // namespace jni
} // namespace bitperfect
