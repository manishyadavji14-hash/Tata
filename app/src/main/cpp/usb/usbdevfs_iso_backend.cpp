#include "usbdevfs_iso_backend.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <new>

#if defined(__linux__)
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>
#endif

namespace bitperfect {
namespace usb {

#if defined(__linux__)

/**
 * A usbdevfs URB plus its trailing iso_frame_desc array.
 *
 * usbdevfs_urb ends in a zero-length array, so the allocation has to be
 * sizeof(usbdevfs_urb) + packets * sizeof(usbdevfs_iso_packet_desc) and the
 * struct cannot simply be a member.
 */
struct UsbdevfsIsoBackend::Urb {
    usbdevfs_urb* raw = nullptr;
    size_t index = 0;
    bool inFlight = false;
};

namespace {

/** Allocate a URB sized for `packets` isochronous packet descriptors. */
usbdevfs_urb* allocateIsoUrb(uint8_t packets) {
    const size_t bytes =
        sizeof(usbdevfs_urb) + static_cast<size_t>(packets) * sizeof(usbdevfs_iso_packet_desc);
    void* memory = std::calloc(1, bytes);
    return static_cast<usbdevfs_urb*>(memory);
}

} // namespace

UsbdevfsIsoBackend::UsbdevfsIsoBackend() = default;

UsbdevfsIsoBackend::~UsbdevfsIsoBackend() {
    detach();
}

bool UsbdevfsIsoBackend::attach(int fd) {
    if (fd < 0) return false;
    detach();

    // Duplicate so that Java closing the UsbDeviceConnection does not pull the
    // descriptor out from under the reaper thread mid-ioctl.
    const int duplicated = ::fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (duplicated < 0) return false;

    fd_ = duplicated;
    shuttingDown_.store(false);
    return true;
}

void UsbdevfsIsoBackend::detach() {
    shuttingDown_.store(true);
    cancelAll();
    releaseUrbs();

    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
}

void UsbdevfsIsoBackend::releaseUrbs() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (Urb* urb : urbs_) {
        if (urb != nullptr) {
            std::free(urb->raw);
            delete urb;
        }
    }
    urbs_.clear();
    payloads_.clear();
    inFlight_.store(0);
}

bool UsbdevfsIsoBackend::configure(uint8_t endpointAddress,
                                   uint16_t maxPacketSize,
                                   uint8_t packetsPerTransfer,
                                   uint8_t queueDepth) {
    if (fd_ < 0) return false;
    if (maxPacketSize == 0 || packetsPerTransfer == 0 || queueDepth == 0) return false;

    // An isochronous OUT endpoint has bit 7 clear. Reject an IN address rather
    // than submitting transfers that can never carry playback data.
    if ((endpointAddress & 0x80) != 0) return false;

    releaseUrbs();

    std::lock_guard<std::mutex> lock(mutex_);
    endpointAddress_ = endpointAddress;
    maxPacketSize_ = maxPacketSize;
    packetsPerTransfer_ = packetsPerTransfer;

    const size_t transferBytes =
        static_cast<size_t>(packetsPerTransfer) * static_cast<size_t>(maxPacketSize);

    urbs_.reserve(queueDepth);
    payloads_.resize(queueDepth);

    for (uint8_t i = 0; i < queueDepth; ++i) {
        usbdevfs_urb* raw = allocateIsoUrb(packetsPerTransfer);
        if (raw == nullptr) {
            urbs_.clear();
            payloads_.clear();
            return false;
        }

        Urb* urb = new (std::nothrow) Urb();
        if (urb == nullptr) {
            std::free(raw);
            urbs_.clear();
            payloads_.clear();
            return false;
        }

        payloads_[i].assign(transferBytes, 0);

        raw->type = USBDEVFS_URB_TYPE_ISO;
        raw->endpoint = endpointAddress;
        // ISO_ASAP lets the kernel schedule at the next available frame, which
        // is what a continuous stream wants; pinning start_frame is only useful
        // for tightly synchronised multi-endpoint cases.
        raw->flags = USBDEVFS_URB_ISO_ASAP;
        raw->buffer = payloads_[i].data();
        raw->buffer_length = static_cast<int>(transferBytes);
        raw->number_of_packets = static_cast<int>(packetsPerTransfer);
        raw->signr = 0;

        urb->raw = raw;
        urb->index = i;
        // usercontext carries the queue index back through reap, so a completion
        // can be matched to its slot without searching.
        raw->usercontext = reinterpret_cast<void*>(static_cast<uintptr_t>(i));

        urbs_.push_back(urb);
    }

    return true;
}

bool UsbdevfsIsoBackend::submit(size_t index, const uint8_t* data, size_t length) {
    if (fd_ < 0 || shuttingDown_.load()) return false;

    std::lock_guard<std::mutex> lock(mutex_);
    if (index >= urbs_.size()) return false;

    Urb* urb = urbs_[index];
    if (urb == nullptr || urb->inFlight) return false;

    const size_t capacity = payloads_[index].size();
    const size_t toSend = length > capacity ? capacity : length;

    if (data != nullptr && toSend > 0) {
        std::memcpy(payloads_[index].data(), data, toSend);
    }
    if (toSend < capacity) {
        // Silence rather than stale audio in the tail, so an underrun is quiet
        // instead of a repeated fragment.
        std::memset(payloads_[index].data() + toSend, 0, capacity - toSend);
    }

    usbdevfs_urb* raw = urb->raw;
    // Every packet carries the nominal payload. An isochronous OUT stream is
    // clocked by the packet schedule, so the schedule must stay intact even when
    // the ring buffer ran short: the tail was zeroed above, so a shortfall is
    // silence rather than a rate change.
    //
    // Note config_.maxPacketSize is the *nominal* size for the current rate and
    // format (NativeBridge::configure passes calculateNominalPacketSize), not
    // the endpoint's wMaxPacketSize.
    for (int i = 0; i < raw->number_of_packets; ++i) {
        raw->iso_frame_desc[i].length = maxPacketSize_;
        raw->iso_frame_desc[i].actual_length = 0;
        raw->iso_frame_desc[i].status = 0;
    }
    raw->buffer_length = static_cast<int>(capacity);
    raw->actual_length = 0;
    raw->status = 0;

    if (::ioctl(fd_, USBDEVFS_SUBMITURB, raw) < 0) {
        return false;
    }

    urb->inFlight = true;
    inFlight_.fetch_add(1);
    return true;
}

void UsbdevfsIsoBackend::cancelAll() {
    if (fd_ < 0) return;

    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (Urb* urb : urbs_) {
            if (urb != nullptr && urb->inFlight) {
                // EINVAL here just means the URB already completed.
                ::ioctl(fd_, USBDEVFS_DISCARDURB, urb->raw);
            }
        }
    }

    // Discarded URBs still have to be reaped or the kernel keeps the buffers.
    IsoCompletion drained;
    while (inFlight_.load() > 0) {
        if (!waitForCompletion(drained, 10)) break;
    }
}

TransferStatus UsbdevfsIsoBackend::statusFromErrno(int err) {
    // Kernel reports URB status as a negative errno.
    const int code = err < 0 ? -err : err;
    switch (code) {
        case 0:
            return TransferStatus::COMPLETED;
        case ENOENT:
        case ECONNRESET:
            return TransferStatus::CANCELLED;
        case EPIPE:
            return TransferStatus::STALL;
        case EOVERFLOW:
            return TransferStatus::OVERFLOW;
        case EREMOTEIO:
            return TransferStatus::SHORT_PACKET;
        default:
            return TransferStatus::ERROR;
    }
}

bool UsbdevfsIsoBackend::waitForCompletion(IsoCompletion& out, int timeoutMs) {
    if (fd_ < 0) return false;

    // usbdevfs signals a reapable URB with POLLOUT. Polling rather than a
    // blocking REAPURB keeps shutdown responsive: a blocking ioctl cannot be
    // interrupted without a signal.
    struct pollfd pfd {};
    pfd.fd = fd_;
    pfd.events = POLLOUT;

    const int ready = ::poll(&pfd, 1, timeoutMs);
    if (ready <= 0) return false;

    if ((pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0 &&
        (pfd.revents & POLLOUT) == 0) {
        // Device has gone away.
        return false;
    }

    usbdevfs_urb* reaped = nullptr;
    if (::ioctl(fd_, USBDEVFS_REAPURBNDELAY, &reaped) < 0) return false;
    if (reaped == nullptr) return false;

    const size_t index = static_cast<size_t>(reinterpret_cast<uintptr_t>(reaped->usercontext));

    size_t actual = 0;
    TransferStatus worst = statusFromErrno(reaped->status);
    for (int i = 0; i < reaped->number_of_packets; ++i) {
        actual += reaped->iso_frame_desc[i].actual_length;
        if (worst == TransferStatus::COMPLETED && reaped->iso_frame_desc[i].status != 0) {
            worst = statusFromErrno(static_cast<int>(reaped->iso_frame_desc[i].status));
        }
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (index < urbs_.size() && urbs_[index] != nullptr) {
            urbs_[index]->inFlight = false;
        }
    }
    if (inFlight_.load() > 0) inFlight_.fetch_sub(1);

    out.transferIndex = index;
    out.status = worst;
    out.actualLength = actual;
    return true;
}

#else // !__linux__

// Non-Linux host builds (developer machines running the standalone test suite)
// have no usbdevfs. The type still has to exist so the engine compiles; it
// simply never attaches, and isHardware() being true is irrelevant because
// attach() always fails.

struct UsbdevfsIsoBackend::Urb {
    int unused = 0;
};

UsbdevfsIsoBackend::UsbdevfsIsoBackend() = default;
UsbdevfsIsoBackend::~UsbdevfsIsoBackend() = default;

bool UsbdevfsIsoBackend::attach(int) { return false; }
void UsbdevfsIsoBackend::detach() {}
bool UsbdevfsIsoBackend::configure(uint8_t, uint16_t, uint8_t, uint8_t) { return false; }
bool UsbdevfsIsoBackend::submit(size_t, const uint8_t*, size_t) { return false; }
void UsbdevfsIsoBackend::cancelAll() {}
bool UsbdevfsIsoBackend::waitForCompletion(IsoCompletion&, int) { return false; }
void UsbdevfsIsoBackend::releaseUrbs() {}

TransferStatus UsbdevfsIsoBackend::statusFromErrno(int err) {
    return err == 0 ? TransferStatus::COMPLETED : TransferStatus::ERROR;
}

#endif // __linux__

} // namespace usb
} // namespace bitperfect
