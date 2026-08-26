#pragma once

#include "usb_types.h"
#include <cstdint>
#include <functional>
#include <vector>
#include <atomic>
#include <memory>

namespace bitperfect {
namespace usb {

// Transfer completion status
enum class TransferStatus : uint8_t {
    COMPLETED = 0,
    CANCELLED = 1,
    ERROR = 2,
    STALL = 3,
    OVERFLOW = 4,
    SHORT_PACKET = 5
};

// Individual isochronous packet result
struct IsoPacketResult {
    uint32_t actualLength = 0;
    TransferStatus status = TransferStatus::COMPLETED;
};

// Transfer statistics
struct TransferStatistics {
    std::atomic<uint64_t> totalTransfers{0};
    std::atomic<uint64_t> completedTransfers{0};
    std::atomic<uint64_t> errorCount{0};
    std::atomic<uint64_t> underrunCount{0};
    std::atomic<uint64_t> overrunCount{0};
    std::atomic<uint64_t> totalBytesTransferred{0};
    std::atomic<uint64_t> totalLatencyUs{0};

    void reset() {
        totalTransfers.store(0);
        completedTransfers.store(0);
        errorCount.store(0);
        underrunCount.store(0);
        overrunCount.store(0);
        totalBytesTransferred.store(0);
        totalLatencyUs.store(0);
    }

    double averageLatencyUs() const {
        uint64_t completed = completedTransfers.load();
        if (completed == 0) return 0.0;
        return static_cast<double>(totalLatencyUs.load()) / completed;
    }

    double errorRate() const {
        uint64_t total = totalTransfers.load();
        if (total == 0) return 0.0;
        return static_cast<double>(errorCount.load()) / total;
    }
};

// Configuration for isochronous transfer
struct IsoTransferConfig {
    uint8_t endpointAddress = 0;
    uint16_t maxPacketSize = 0;
    uint8_t packetsPerTransfer = ISO_PACKETS_PER_URB;
    uint8_t queueDepth = DEFAULT_URB_COUNT;
    uint8_t interval = 1;  // Polling interval (125us units for high-speed)
};

// Callback for completed transfers
using TransferCompleteCallback = std::function<void(const uint8_t* data, size_t length,
                                                     TransferStatus status)>;

// Callback for supplying data for OUT transfers
using TransferSupplyCallback = std::function<size_t(uint8_t* buffer, size_t maxLength)>;

/**
 * Manages isochronous USB transfers for audio streaming.
 * Handles queuing, completion callbacks, error recovery, and statistics.
 */
class IsochronousTransfer {
public:
    IsochronousTransfer();
    ~IsochronousTransfer();

    /**
     * Configure the transfer parameters.
     */
    bool configure(const IsoTransferConfig& config);

    /**
     * Start streaming. For playback (OUT), uses supplyCallback to get data.
     */
    bool start(TransferSupplyCallback supplyCallback, TransferCompleteCallback completeCallback);

    /**
     * Stop streaming and cancel outstanding transfers.
     */
    void stop();

    /**
     * Check if streaming is active.
     */
    bool isActive() const { return active_.load(); }

    /**
     * Get transfer statistics.
     */
    const TransferStatistics& getStatistics() const { return stats_; }

    /**
     * Reset statistics counters.
     */
    void resetStatistics() { stats_.reset(); }

    /**
     * Get the configured packet size.
     */
    uint16_t getPacketSize() const { return config_.maxPacketSize; }

    /**
     * Get the transfer buffer size (packets * maxPacketSize).
     */
    size_t getTransferBufferSize() const {
        return config_.packetsPerTransfer * config_.maxPacketSize;
    }

    /**
     * Calculate packets needed for a given number of frames at a sample rate.
     * @param frames Number of audio frames
     * @param sampleRate Sample rate in Hz
     * @param bytesPerFrame Bytes per audio frame
     * @return Number of USB packets needed
     */
    static uint32_t calculatePacketsForFrames(uint32_t frames, uint32_t sampleRate,
                                               uint32_t bytesPerFrame);

    /**
     * Calculate nominal packet size for a sample rate and format.
     * For high-speed (125us microframes): size = (sampleRate * bytesPerFrame) / 8000
     */
    static uint32_t calculateNominalPacketSize(uint32_t sampleRate, uint32_t bytesPerFrame);

    /**
     * Process a completed transfer (called from USB completion thread).
     */
    void onTransferComplete(size_t transferIndex, TransferStatus status, size_t actualLength);

private:
    struct TransferBuffer {
        std::vector<uint8_t> data;
        bool inFlight = false;
        uint64_t submitTimeUs = 0;
    };

    IsoTransferConfig config_;
    std::vector<TransferBuffer> buffers_;
    TransferSupplyCallback supplyCallback_;
    TransferCompleteCallback completeCallback_;
    TransferStatistics stats_;
    std::atomic<bool> active_{false};
    std::atomic<uint32_t> outstandingTransfers_{0};

    bool submitTransfer(size_t index);
    void resubmitTransfer(size_t index);
};

} // namespace usb
} // namespace bitperfect
