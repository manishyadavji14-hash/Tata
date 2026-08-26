#include "isochronous_transfer.h"
#include <algorithm>
#include <chrono>
#include <cstring>

namespace bitperfect {
namespace usb {

IsochronousTransfer::IsochronousTransfer()
    : backend_(std::make_shared<LoopbackIsoBackend>()) {}

IsochronousTransfer::~IsochronousTransfer() {
    stop();
}

void IsochronousTransfer::setBackend(std::shared_ptr<UsbIsoBackend> backend) {
    if (active_.load()) return;
    backend_ = backend ? std::move(backend) : std::make_shared<LoopbackIsoBackend>();
}

bool IsochronousTransfer::isHardwareBacked() const {
    return backend_ && backend_->isHardware();
}

const char* IsochronousTransfer::backendName() const {
    return backend_ ? backend_->name() : "none";
}

bool IsochronousTransfer::configure(const IsoTransferConfig& config) {
    if (active_.load()) return false;
    if (config.maxPacketSize == 0 || config.packetsPerTransfer == 0 || config.queueDepth == 0) {
        return false;
    }

    config_ = config;

    if (!backend_) return false;
    if (!backend_->configure(config.endpointAddress, config.maxPacketSize,
                             config.packetsPerTransfer, config.queueDepth)) {
        return false;
    }

    // Allocate transfer buffers
    size_t bufferSize = getTransferBufferSize();
    buffers_.resize(config.queueDepth);
    for (auto& buf : buffers_) {
        buf.data.resize(bufferSize, 0);
        buf.inFlight = false;
        buf.submitTimeUs = 0;
    }

    return true;
}

bool IsochronousTransfer::start(TransferSupplyCallback supplyCallback,
                                 TransferCompleteCallback completeCallback) {
    if (active_.load()) return false;
    if (!supplyCallback || !completeCallback) return false;
    if (buffers_.empty()) return false;

    supplyCallback_ = std::move(supplyCallback);
    completeCallback_ = std::move(completeCallback);
    stats_.reset();
    active_.store(true);

    // The reaper has to be running before the first submission, otherwise a
    // transfer can complete with nothing to observe it and the queue stalls.
    if (backend_ && backend_->needsCompletionThread()) {
        reaperRunning_.store(true);
        reaperThread_ = std::thread([this] { runReaperLoop(); });
    }

    // Submit initial set of transfers
    for (size_t i = 0; i < buffers_.size(); ++i) {
        if (!submitTransfer(i)) {
            stop();
            return false;
        }
    }

    return true;
}

void IsochronousTransfer::stop() {
    const bool wasActive = active_.exchange(false);

    if (backend_ && wasActive) {
        backend_->cancelAll();
    }

    stopReaper();

    // Mark all buffers as not in flight
    for (auto& buf : buffers_) {
        buf.inFlight = false;
    }

    outstandingTransfers_.store(0);
}

void IsochronousTransfer::stopReaper() {
    reaperRunning_.store(false);
    if (reaperThread_.joinable()) {
        reaperThread_.join();
    }
}

void IsochronousTransfer::runReaperLoop() {
    // Short timeout so shutdown is prompt. Each URB carries
    // packetsPerTransfer * 125us of audio, so a few milliseconds of slack here
    // is well inside the queue depth.
    constexpr int kPollTimeoutMs = 5;

    IsoCompletion completion;
    while (reaperRunning_.load()) {
        if (!backend_->waitForCompletion(completion, kPollTimeoutMs)) {
            continue;
        }
        onTransferComplete(completion.transferIndex, completion.status, completion.actualLength);
    }
}

bool IsochronousTransfer::submitTransfer(size_t index) {
    if (index >= buffers_.size()) return false;
    if (!active_.load()) return false;

    auto& buf = buffers_[index];
    size_t bufferSize = getTransferBufferSize();

    // Request data from the supply callback
    size_t supplied = supplyCallback_(buf.data.data(), bufferSize);
    if (supplied < bufferSize && active_.load()) {
        // Underrun: pad the remainder with silence rather than transmitting the
        // previous transfer's tail, and keep sending so the stream clock holds.
        std::memset(buf.data.data() + supplied, 0, bufferSize - supplied);
        if (supplied == 0) {
            stats_.underrunCount.fetch_add(1);
        }
        supplied = bufferSize;
    }

    // Hand it to the transport. Until this existed the bytes stopped here, so
    // the engine reported a healthy stream while emitting nothing.
    if (!backend_->submit(index, buf.data.data(), bufferSize)) {
        stats_.errorCount.fetch_add(1);
        return false;
    }

    buf.inFlight = true;
    buf.submitTimeUs = nowMicros();
    outstandingTransfers_.fetch_add(1);
    stats_.totalTransfers.fetch_add(1);

    return true;
}

uint64_t IsochronousTransfer::nowMicros() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(now).count());
}

void IsochronousTransfer::resubmitTransfer(size_t index) {
    if (active_.load()) {
        submitTransfer(index);
    }
}

void IsochronousTransfer::onTransferComplete(size_t transferIndex, TransferStatus status,
                                              size_t actualLength) {
    if (transferIndex >= buffers_.size()) return;

    auto& buf = buffers_[transferIndex];
    buf.inFlight = false;
    outstandingTransfers_.fetch_sub(1);

    if (status == TransferStatus::COMPLETED) {
        stats_.completedTransfers.fetch_add(1);
        stats_.totalBytesTransferred.fetch_add(actualLength);
        if (buf.submitTimeUs != 0) {
            const uint64_t elapsed = nowMicros() - buf.submitTimeUs;
            stats_.totalLatencyUs.fetch_add(elapsed);
        }

        // Deliver data to callback
        if (completeCallback_) {
            completeCallback_(buf.data.data(), actualLength, status);
        }
    } else if (status == TransferStatus::CANCELLED) {
        // Normal cancellation during stop, do not resubmit
        return;
    } else {
        stats_.errorCount.fetch_add(1);

        if (completeCallback_) {
            completeCallback_(nullptr, 0, status);
        }
    }

    // Resubmit if still active
    resubmitTransfer(transferIndex);
}

uint32_t IsochronousTransfer::calculatePacketsForFrames(uint32_t frames, uint32_t sampleRate,
                                                         uint32_t bytesPerFrame) {
    if (sampleRate == 0 || bytesPerFrame == 0) return 0;

    // High-speed: 8000 microframes per second
    // Frames per microframe = sampleRate / 8000
    uint32_t framesPerPacket = sampleRate / 8000;
    if (framesPerPacket == 0) framesPerPacket = 1;

    return (frames + framesPerPacket - 1) / framesPerPacket;
}

uint32_t IsochronousTransfer::calculateNominalPacketSize(uint32_t sampleRate,
                                                          uint32_t bytesPerFrame) {
    if (bytesPerFrame == 0) return 0;

    // For high-speed isochronous: one packet per 125us microframe
    // Nominal size = (sampleRate * bytesPerFrame) / 8000
    // Round up to handle fractional samples
    return ((sampleRate * bytesPerFrame) + 7999) / 8000;
}

} // namespace usb
} // namespace bitperfect
