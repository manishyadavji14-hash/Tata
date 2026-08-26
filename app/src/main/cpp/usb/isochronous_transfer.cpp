#include "isochronous_transfer.h"
#include <algorithm>
#include <cstring>

namespace bitperfect {
namespace usb {

IsochronousTransfer::IsochronousTransfer() = default;

IsochronousTransfer::~IsochronousTransfer() {
    stop();
}

bool IsochronousTransfer::configure(const IsoTransferConfig& config) {
    if (active_.load()) return false;
    if (config.maxPacketSize == 0 || config.packetsPerTransfer == 0 || config.queueDepth == 0) {
        return false;
    }

    config_ = config;

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
    active_.store(false);

    // Mark all buffers as not in flight
    for (auto& buf : buffers_) {
        buf.inFlight = false;
    }

    outstandingTransfers_.store(0);
}

bool IsochronousTransfer::submitTransfer(size_t index) {
    if (index >= buffers_.size()) return false;
    if (!active_.load()) return false;

    auto& buf = buffers_[index];
    size_t bufferSize = getTransferBufferSize();

    // Request data from the supply callback
    size_t supplied = supplyCallback_(buf.data.data(), bufferSize);
    if (supplied == 0 && active_.load()) {
        // Underrun - fill with silence
        std::memset(buf.data.data(), 0, bufferSize);
        supplied = bufferSize;
        stats_.underrunCount.fetch_add(1);
    }

    buf.inFlight = true;
    buf.submitTimeUs = 0; // Would use real timestamp in production
    outstandingTransfers_.fetch_add(1);
    stats_.totalTransfers.fetch_add(1);

    return true;
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
