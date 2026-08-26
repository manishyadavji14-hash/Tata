#pragma once

#include "usb_iso_backend.h"
#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

namespace bitperfect {
namespace usb {

/**
 * Isochronous OUT transport over Linux usbdevfs.
 *
 * This is the real thing: it submits URBs to the kernel with
 * USBDEVFS_SUBMITURB and reaps them with USBDEVFS_REAPURBNDELAY, driven by
 * poll() on the device file descriptor.
 *
 * The file descriptor comes from Android's UsbDeviceConnection.getFileDescriptor()
 * after the Java side has claimed the streaming interface and selected the
 * alternate setting. Native code cannot open a USB device on Android, so the
 * descriptor must be handed down; see NativeBridge::attachUsbDevice.
 *
 * The descriptor is duplicated on attach, so this object's lifetime is
 * independent of the Java UsbDeviceConnection being closed underneath it.
 *
 * Threading: submit() is called from the audio thread and waitForCompletion()
 * from the reaper thread. Both touch the URB pool, so it is mutex-guarded.
 * The ioctls themselves are not called under the lock any longer than needed.
 */
class UsbdevfsIsoBackend : public UsbIsoBackend {
public:
    UsbdevfsIsoBackend();
    ~UsbdevfsIsoBackend() override;

    /**
     * Take ownership of a duplicate of `fd`.
     * @return false if the descriptor could not be duplicated.
     */
    bool attach(int fd);

    /** Close the duplicated descriptor and release all URBs. */
    void detach();

    bool isAttached() const { return fd_ >= 0; }

    bool configure(uint8_t endpointAddress,
                   uint16_t maxPacketSize,
                   uint8_t packetsPerTransfer,
                   uint8_t queueDepth) override;

    bool submit(size_t index, const uint8_t* data, size_t length) override;
    void cancelAll() override;
    bool needsCompletionThread() const override { return true; }
    bool waitForCompletion(IsoCompletion& out, int timeoutMs) override;
    const char* name() const override { return "usbdevfs isochronous"; }
    bool isHardware() const override { return true; }

    /** Number of URBs the kernel currently holds. */
    uint32_t inFlightCount() const { return inFlight_.load(); }

    /**
     * Map a kernel URB status (negative errno, or per-packet status) onto the
     * engine's TransferStatus. Exposed for unit testing; it is pure.
     */
    static TransferStatus statusFromErrno(int err);

private:
    // Opaque so the header does not need <linux/usbdevice_fs.h>, which keeps
    // this includable from the host test build.
    struct Urb;

    int fd_ = -1;
    uint8_t endpointAddress_ = 0;
    uint16_t maxPacketSize_ = 0;
    uint8_t packetsPerTransfer_ = 0;

    mutable std::mutex mutex_;
    std::vector<Urb*> urbs_;
    std::vector<std::vector<uint8_t>> payloads_;
    std::atomic<uint32_t> inFlight_{0};
    std::atomic<bool> shuttingDown_{false};

    void releaseUrbs();
};

} // namespace usb
} // namespace bitperfect
