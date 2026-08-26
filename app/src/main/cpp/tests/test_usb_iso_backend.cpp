#include <gtest/gtest.h>

#include "usb/isochronous_transfer.h"
#include "usb/usb_iso_backend.h"
#include "usb/usbdevfs_iso_backend.h"

#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

using namespace bitperfect::usb;

namespace {

/**
 * Backend that records what was submitted and lets a test release completions,
 * standing in for the kernel. This is what lets the queueing and resubmit logic
 * be exercised without a USB device attached.
 */
class RecordingBackend : public UsbIsoBackend {
public:
    struct Submission {
        size_t index;
        std::vector<uint8_t> payload;
    };

    bool configure(uint8_t endpoint, uint16_t packetSize, uint8_t packets,
                   uint8_t depth) override {
        endpoint_ = endpoint;
        packetSize_ = packetSize;
        packets_ = packets;
        depth_ = depth;
        configured_ = true;
        return acceptConfigure_;
    }

    bool submit(size_t index, const uint8_t* data, size_t length) override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!acceptSubmit_) return false;
        submissions_.push_back({index, std::vector<uint8_t>(data, data + length)});
        pending_.push_back({index, TransferStatus::COMPLETED, length});
        cv_.notify_all();
        return true;
    }

    void cancelAll() override {
        std::lock_guard<std::mutex> lock(mutex_);
        cancelCalls_++;
        pending_.clear();
    }

    bool needsCompletionThread() const override { return needsThread_; }

    bool waitForCompletion(IsoCompletion& out, int timeoutMs) override {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!cv_.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                          [this] { return !pending_.empty(); })) {
            return false;
        }
        out = pending_.front();
        pending_.pop_front();
        return true;
    }

    const char* name() const override { return "recording"; }
    bool isHardware() const override { return hardware_; }

    size_t submissionCount() {
        std::lock_guard<std::mutex> lock(mutex_);
        return submissions_.size();
    }

    std::vector<Submission> submissions() {
        std::lock_guard<std::mutex> lock(mutex_);
        return submissions_;
    }

    int cancelCalls() {
        std::lock_guard<std::mutex> lock(mutex_);
        return cancelCalls_;
    }

    void setAcceptSubmit(bool accept) {
        std::lock_guard<std::mutex> lock(mutex_);
        acceptSubmit_ = accept;
    }
    void setAcceptConfigure(bool accept) { acceptConfigure_ = accept; }
    void setNeedsThread(bool needs) { needsThread_ = needs; }
    void setHardware(bool hardware) { hardware_ = hardware; }

    bool configured() const { return configured_; }
    uint8_t endpoint() const { return endpoint_; }
    uint16_t packetSize() const { return packetSize_; }
    uint8_t packets() const { return packets_; }
    uint8_t depth() const { return depth_; }

private:
    std::mutex mutex_;
    std::condition_variable cv_;
    std::vector<Submission> submissions_;
    std::deque<IsoCompletion> pending_;
    int cancelCalls_ = 0;
    bool acceptSubmit_ = true;
    bool acceptConfigure_ = true;
    bool needsThread_ = false;
    bool hardware_ = true;
    bool configured_ = false;
    uint8_t endpoint_ = 0;
    uint16_t packetSize_ = 0;
    uint8_t packets_ = 0;
    uint8_t depth_ = 0;
};

IsoTransferConfig makeConfig() {
    IsoTransferConfig config;
    config.endpointAddress = 0x01;
    config.maxPacketSize = 192;
    config.packetsPerTransfer = 8;
    config.queueDepth = 4;
    config.interval = 1;
    return config;
}

} // namespace

// --- Backend selection and honesty about hardware ---------------------------

TEST(UsbIsoBackend, DefaultsToLoopbackAndReportsNoHardware) {
    IsochronousTransfer transfer;
    EXPECT_FALSE(transfer.isHardwareBacked());
    EXPECT_STREQ(transfer.backendName(), "loopback (no hardware)");
}

TEST(UsbIsoBackend, ReportsHardwareWhenHardwareBackendInstalled) {
    IsochronousTransfer transfer;
    transfer.setBackend(std::make_shared<RecordingBackend>());
    EXPECT_TRUE(transfer.isHardwareBacked());
    EXPECT_STREQ(transfer.backendName(), "recording");
}

TEST(UsbIsoBackend, NullBackendFallsBackToLoopbackRatherThanCrashing) {
    IsochronousTransfer transfer;
    transfer.setBackend(std::make_shared<RecordingBackend>());
    transfer.setBackend(nullptr);
    EXPECT_FALSE(transfer.isHardwareBacked());
}

TEST(UsbIsoBackend, BackendCannotBeSwappedWhileStreaming) {
    IsochronousTransfer transfer;
    auto backend = std::make_shared<RecordingBackend>();
    transfer.setBackend(backend);
    ASSERT_TRUE(transfer.configure(makeConfig()));
    ASSERT_TRUE(transfer.start([](uint8_t*, size_t max) { return max; },
                               [](const uint8_t*, size_t, TransferStatus) {}));

    transfer.setBackend(nullptr);
    EXPECT_TRUE(transfer.isHardwareBacked()) << "swap during streaming must be refused";
    transfer.stop();
}

// --- Configuration is forwarded to the transport ---------------------------

TEST(UsbIsoBackend, ConfigureForwardsEndpointAndGeometry) {
    IsochronousTransfer transfer;
    auto backend = std::make_shared<RecordingBackend>();
    transfer.setBackend(backend);

    ASSERT_TRUE(transfer.configure(makeConfig()));

    EXPECT_TRUE(backend->configured());
    EXPECT_EQ(backend->endpoint(), 0x01);
    EXPECT_EQ(backend->packetSize(), 192);
    EXPECT_EQ(backend->packets(), 8);
    EXPECT_EQ(backend->depth(), 4);
}

TEST(UsbIsoBackend, ConfigureFailsWhenBackendRejects) {
    IsochronousTransfer transfer;
    auto backend = std::make_shared<RecordingBackend>();
    backend->setAcceptConfigure(false);
    transfer.setBackend(backend);

    EXPECT_FALSE(transfer.configure(makeConfig()));
}

// --- Data actually reaches the transport ----------------------------------

TEST(UsbIsoBackend, StartSubmitsOneTransferPerQueueSlot) {
    IsochronousTransfer transfer;
    auto backend = std::make_shared<RecordingBackend>();
    transfer.setBackend(backend);
    ASSERT_TRUE(transfer.configure(makeConfig()));

    ASSERT_TRUE(transfer.start([](uint8_t* buffer, size_t max) {
                                   std::memset(buffer, 0x5A, max);
                                   return max;
                               },
                               [](const uint8_t*, size_t, TransferStatus) {}));

    EXPECT_EQ(backend->submissionCount(), 4u);
    transfer.stop();
}

TEST(UsbIsoBackend, SuppliedAudioIsHandedToTheTransportUnmodified) {
    IsochronousTransfer transfer;
    auto backend = std::make_shared<RecordingBackend>();
    transfer.setBackend(backend);

    IsoTransferConfig config = makeConfig();
    config.queueDepth = 1;
    ASSERT_TRUE(transfer.configure(config));

    ASSERT_TRUE(transfer.start([](uint8_t* buffer, size_t max) {
                                   for (size_t i = 0; i < max; ++i) {
                                       buffer[i] = static_cast<uint8_t>(i & 0xFF);
                                   }
                                   return max;
                               },
                               [](const uint8_t*, size_t, TransferStatus) {}));

    auto submissions = backend->submissions();
    ASSERT_FALSE(submissions.empty());
    const auto& payload = submissions.front().payload;
    ASSERT_EQ(payload.size(), transfer.getTransferBufferSize());
    for (size_t i = 0; i < payload.size(); ++i) {
        ASSERT_EQ(payload[i], static_cast<uint8_t>(i & 0xFF)) << "byte " << i << " altered";
    }
    transfer.stop();
}

TEST(UsbIsoBackend, StartFailsWhenTransportRefusesSubmission) {
    IsochronousTransfer transfer;
    auto backend = std::make_shared<RecordingBackend>();
    backend->setAcceptSubmit(false);
    transfer.setBackend(backend);
    ASSERT_TRUE(transfer.configure(makeConfig()));

    EXPECT_FALSE(transfer.start([](uint8_t*, size_t max) { return max; },
                                [](const uint8_t*, size_t, TransferStatus) {}));
    EXPECT_FALSE(transfer.isActive());
}

// --- Underrun handling ----------------------------------------------------

TEST(UsbIsoBackend, ShortSupplyIsPaddedWithSilenceNotStaleAudio) {
    IsochronousTransfer transfer;
    auto backend = std::make_shared<RecordingBackend>();
    transfer.setBackend(backend);

    IsoTransferConfig config = makeConfig();
    config.queueDepth = 1;
    ASSERT_TRUE(transfer.configure(config));

    const size_t half = transfer.getTransferBufferSize() / 2;
    ASSERT_TRUE(transfer.start([half](uint8_t* buffer, size_t) {
                                   std::memset(buffer, 0x7F, half);
                                   return half;
                               },
                               [](const uint8_t*, size_t, TransferStatus) {}));

    auto submissions = backend->submissions();
    ASSERT_FALSE(submissions.empty());
    const auto& payload = submissions.front().payload;
    ASSERT_EQ(payload.size(), transfer.getTransferBufferSize());
    for (size_t i = 0; i < half; ++i) {
        ASSERT_EQ(payload[i], 0x7F) << "supplied audio altered at " << i;
    }
    for (size_t i = half; i < payload.size(); ++i) {
        ASSERT_EQ(payload[i], 0x00) << "tail not silent at " << i;
    }
    transfer.stop();
}

TEST(UsbIsoBackend, FullPacketScheduleIsKeptOnUnderrun) {
    IsochronousTransfer transfer;
    auto backend = std::make_shared<RecordingBackend>();
    transfer.setBackend(backend);

    IsoTransferConfig config = makeConfig();
    config.queueDepth = 1;
    ASSERT_TRUE(transfer.configure(config));

    // Supplying nothing at all is a total underrun.
    ASSERT_TRUE(transfer.start([](uint8_t*, size_t) { return static_cast<size_t>(0); },
                               [](const uint8_t*, size_t, TransferStatus) {}));

    auto submissions = backend->submissions();
    ASSERT_FALSE(submissions.empty());
    // A clocked stream must keep sending full transfers, so the DAC's sample
    // clock does not stall; the payload is simply silent.
    EXPECT_EQ(submissions.front().payload.size(), transfer.getTransferBufferSize());
    EXPECT_EQ(transfer.getStatistics().underrunCount.load(), 1u);
    transfer.stop();
}

// --- Reaper thread drives the resubmit loop -------------------------------

TEST(UsbIsoBackend, ReaperThreadKeepsTheQueueMoving) {
    IsochronousTransfer transfer;
    auto backend = std::make_shared<RecordingBackend>();
    backend->setNeedsThread(true);
    transfer.setBackend(backend);
    ASSERT_TRUE(transfer.configure(makeConfig()));

    ASSERT_TRUE(transfer.start([](uint8_t*, size_t max) { return max; },
                               [](const uint8_t*, size_t, TransferStatus) {}));

    // Without a completion thread the queue is submitted once and stalls. With
    // one, completions feed resubmissions, so the count climbs past queueDepth.
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (backend->submissionCount() <= 4u &&
           std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    EXPECT_GT(backend->submissionCount(), 4u)
        << "completions did not drive resubmission";
    transfer.stop();
    EXPECT_FALSE(transfer.isActive());
}

TEST(UsbIsoBackend, StopCancelsOutstandingTransfersAndJoinsReaper) {
    IsochronousTransfer transfer;
    auto backend = std::make_shared<RecordingBackend>();
    backend->setNeedsThread(true);
    transfer.setBackend(backend);
    ASSERT_TRUE(transfer.configure(makeConfig()));
    ASSERT_TRUE(transfer.start([](uint8_t*, size_t max) { return max; },
                               [](const uint8_t*, size_t, TransferStatus) {}));

    transfer.stop();

    EXPECT_FALSE(transfer.isActive());
    EXPECT_GE(backend->cancelCalls(), 1);
    // Repeated stop must be harmless; the reaper is already joined.
    transfer.stop();
}

TEST(UsbIsoBackend, DestructorStopsCleanlyWhileStreaming) {
    auto backend = std::make_shared<RecordingBackend>();
    backend->setNeedsThread(true);
    {
        IsochronousTransfer transfer;
        transfer.setBackend(backend);
        ASSERT_TRUE(transfer.configure(makeConfig()));
        ASSERT_TRUE(transfer.start([](uint8_t*, size_t max) { return max; },
                                   [](const uint8_t*, size_t, TransferStatus) {}));
    }
    // Reaching here without a hang or crash is the assertion.
    SUCCEED();
}

// --- Kernel status mapping ------------------------------------------------

TEST(UsbdevfsBackend, MapsKernelUrbStatusToTransferStatus) {
    EXPECT_EQ(UsbdevfsIsoBackend::statusFromErrno(0), TransferStatus::COMPLETED);
#if defined(__linux__)
    // The kernel reports URB status as a negative errno.
    EXPECT_EQ(UsbdevfsIsoBackend::statusFromErrno(-ENOENT), TransferStatus::CANCELLED);
    EXPECT_EQ(UsbdevfsIsoBackend::statusFromErrno(-ECONNRESET), TransferStatus::CANCELLED);
    EXPECT_EQ(UsbdevfsIsoBackend::statusFromErrno(-EPIPE), TransferStatus::STALL);
    EXPECT_EQ(UsbdevfsIsoBackend::statusFromErrno(-EOVERFLOW), TransferStatus::OVERFLOW);
    EXPECT_EQ(UsbdevfsIsoBackend::statusFromErrno(-EREMOTEIO), TransferStatus::SHORT_PACKET);
    EXPECT_EQ(UsbdevfsIsoBackend::statusFromErrno(-EIO), TransferStatus::ERROR);
    // Positive values are accepted too, since per-packet status is unsigned.
    EXPECT_EQ(UsbdevfsIsoBackend::statusFromErrno(EPIPE), TransferStatus::STALL);
#endif
}

TEST(UsbdevfsBackend, RefusesToAttachAnInvalidDescriptor) {
    UsbdevfsIsoBackend backend;
    EXPECT_FALSE(backend.attach(-1));
    EXPECT_FALSE(backend.isAttached());
}

TEST(UsbdevfsBackend, ConfigureRequiresAnAttachedDescriptor) {
    UsbdevfsIsoBackend backend;
    EXPECT_FALSE(backend.configure(0x01, 192, 8, 4));
}

TEST(UsbdevfsBackend, ReportsItselfAsHardware) {
    UsbdevfsIsoBackend backend;
    EXPECT_TRUE(backend.isHardware());
    EXPECT_TRUE(backend.needsCompletionThread());
}

TEST(UsbdevfsBackend, DetachIsSafeWithoutAttach) {
    UsbdevfsIsoBackend backend;
    backend.detach();
    backend.cancelAll();
    EXPECT_FALSE(backend.isAttached());
}

TEST(LoopbackBackend, AcceptsDataButReportsNoHardware) {
    LoopbackIsoBackend backend;
    EXPECT_TRUE(backend.configure(0x01, 192, 8, 4));
    const std::vector<uint8_t> data(64, 0xAB);
    EXPECT_TRUE(backend.submit(0, data.data(), data.size()));
    EXPECT_FALSE(backend.isHardware());
    EXPECT_FALSE(backend.needsCompletionThread());

    IsoCompletion completion;
    EXPECT_FALSE(backend.waitForCompletion(completion, 0));
}
