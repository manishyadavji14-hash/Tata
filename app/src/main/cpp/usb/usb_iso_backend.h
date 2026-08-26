#pragma once

#include "usb_types.h"
#include <cstdint>
#include <cstddef>

namespace bitperfect {
namespace usb {

/**
 * Result of reaping one completed isochronous transfer.
 */
struct IsoCompletion {
    size_t transferIndex = 0;
    TransferStatus status = TransferStatus::COMPLETED;
    size_t actualLength = 0;
};

/**
 * Platform boundary for isochronous USB I/O.
 *
 * IsochronousTransfer owns queue management, the resubmit loop and statistics.
 * Everything that actually touches a device lives behind this interface, which
 * exists for two reasons:
 *
 *  - The queueing and packet-splitting logic can be unit tested on a host with
 *    no USB device present.
 *  - There is exactly one place where "are we really talking to hardware?" is
 *    answered, so the engine cannot silently believe it is streaming when it is
 *    not. That was previously the case: the transport accepted data, counted it,
 *    and dropped it.
 */
class UsbIsoBackend {
public:
    virtual ~UsbIsoBackend() = default;

    /**
     * Prepare `queueDepth` transfers, each carrying `packetsPerTransfer`
     * packets of at most `maxPacketSize` bytes to `endpointAddress`.
     */
    virtual bool configure(uint8_t endpointAddress,
                           uint16_t maxPacketSize,
                           uint8_t packetsPerTransfer,
                           uint8_t queueDepth) = 0;

    /**
     * Hand transfer `index` to the device. `length` may be shorter than the
     * full transfer buffer; the backend splits it across packets.
     */
    virtual bool submit(size_t index, const uint8_t* data, size_t length) = 0;

    /**
     * Cancel everything in flight. Must be safe to call when nothing is queued.
     */
    virtual void cancelAll() = 0;

    /**
     * Whether IsochronousTransfer should run a thread that calls
     * waitForCompletion() and feeds the result back into onTransferComplete().
     *
     * False for in-memory backends, where tests drive completion by hand.
     */
    virtual bool needsCompletionThread() const = 0;

    /**
     * Block for up to `timeoutMs` for a transfer to finish.
     * @return true when `out` was populated, false on timeout or shutdown.
     */
    virtual bool waitForCompletion(IsoCompletion& out, int timeoutMs) = 0;

    /**
     * Human-readable name, used in diagnostics so it is visible which
     * transport is in use.
     */
    virtual const char* name() const = 0;

    /**
     * Whether this backend transmits to real hardware. Diagnostics uses this so
     * throughput counters are never presented as USB output when they are not.
     */
    virtual bool isHardware() const = 0;
};

/**
 * In-memory backend: accepts data and discards it.
 *
 * This is the behaviour the transport used to have unconditionally. It is kept
 * for unit tests, and is deliberately explicit about being a fake so it cannot
 * be mistaken for a working transport again. `isHardware()` returns false, and
 * NativeBridge refuses to report USB output as active while it is installed.
 */
class LoopbackIsoBackend : public UsbIsoBackend {
public:
    bool configure(uint8_t, uint16_t, uint8_t, uint8_t) override { return true; }
    bool submit(size_t, const uint8_t*, size_t) override { return true; }
    void cancelAll() override {}
    bool needsCompletionThread() const override { return false; }
    bool waitForCompletion(IsoCompletion&, int) override { return false; }
    const char* name() const override { return "loopback (no hardware)"; }
    bool isHardware() const override { return false; }
};

} // namespace usb
} // namespace bitperfect
