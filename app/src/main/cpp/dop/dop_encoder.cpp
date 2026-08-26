#include "dop_encoder.h"

namespace bitperfect {
namespace dop {

void DopEncoder::configure(uint32_t channels, uint32_t dsdRate) {
    channels_ = channels;
    dsdRate_ = dsdRate;
    transportRate_ = calculateTransportRate(dsdRate);
    reset();
}

size_t DopEncoder::encode(const uint8_t* dsdData, size_t dsdLength,
                           uint8_t* dopOutput, size_t dopMaxLength) {
    if (!dsdData || dsdLength == 0 || !dopOutput || dopMaxLength == 0) {
        return 0;
    }

    size_t written = 0;
    size_t dsdPos = 0;

    // If we have a pending byte from previous call, pair it with the first byte
    if (hasPendingByte_ && dsdLength > 0) {
        if (dopMaxLength < DOP_FRAME_BYTES) {
            return 0; // Not enough output space
        }

        uint8_t marker = markerIsA_ ? DOP_MARKER_A : DOP_MARKER_B;

        // DoP frame (24-bit LE):
        // Byte 0 (LSB): second DSD byte (lower)
        // Byte 1: first DSD byte (upper)
        // Byte 2 (MSB): marker
        dopOutput[written + 0] = dsdData[0];       // second DSD byte
        dopOutput[written + 1] = pendingByte_;     // first DSD byte (pending)
        dopOutput[written + 2] = marker;

        markerIsA_ = !markerIsA_;
        totalFramesEncoded_++;
        written += DOP_FRAME_BYTES;
        dsdPos = 1;
        hasPendingByte_ = false;
    }

    // Process remaining DSD bytes in pairs
    while (dsdPos + 1 < dsdLength) {
        if (written + DOP_FRAME_BYTES > dopMaxLength) {
            // Save unpaired bytes if we can't write more
            break;
        }

        uint8_t marker = markerIsA_ ? DOP_MARKER_A : DOP_MARKER_B;

        // DoP frame (24-bit LE):
        // Byte 0 (LSB): second DSD byte (lower)
        // Byte 1: first DSD byte (upper)
        // Byte 2 (MSB): marker
        dopOutput[written + 0] = dsdData[dsdPos + 1]; // second DSD byte
        dopOutput[written + 1] = dsdData[dsdPos];     // first DSD byte
        dopOutput[written + 2] = marker;

        markerIsA_ = !markerIsA_;
        totalFramesEncoded_++;
        written += DOP_FRAME_BYTES;
        dsdPos += 2;
    }

    // Handle partial (odd) byte: save for next call
    if (dsdPos < dsdLength) {
        hasPendingByte_ = true;
        pendingByte_ = dsdData[dsdPos];
    }

    return written;
}

size_t DopEncoder::encodeStereo(const uint8_t* leftDsd, const uint8_t* rightDsd,
                                 size_t dsdLength,
                                 uint8_t* dopOutput, size_t dopMaxLength) {
    if (!leftDsd || !rightDsd || dsdLength == 0 || !dopOutput || dopMaxLength == 0) {
        return 0;
    }

    size_t written = 0;
    size_t dsdPos = 0;

    // Process DSD bytes in pairs (2 DSD bytes per DoP frame per channel)
    while (dsdPos + 1 < dsdLength) {
        // Need space for both L and R DoP frames (6 bytes total per stereo frame pair)
        if (written + DOP_FRAME_BYTES * 2 > dopMaxLength) {
            break;
        }

        uint8_t marker = markerIsA_ ? DOP_MARKER_A : DOP_MARKER_B;

        // Left channel DoP frame
        dopOutput[written + 0] = leftDsd[dsdPos + 1]; // second DSD byte
        dopOutput[written + 1] = leftDsd[dsdPos];     // first DSD byte
        dopOutput[written + 2] = marker;

        // Right channel DoP frame (same marker for same frame)
        dopOutput[written + 3] = rightDsd[dsdPos + 1]; // second DSD byte
        dopOutput[written + 4] = rightDsd[dsdPos];     // first DSD byte
        dopOutput[written + 5] = marker;

        markerIsA_ = !markerIsA_;
        totalFramesEncoded_++;
        written += DOP_FRAME_BYTES * 2;
        dsdPos += 2;
    }

    return written;
}

void DopEncoder::reset() {
    markerIsA_ = true;
    totalFramesEncoded_ = 0;
    hasPendingByte_ = false;
    pendingByte_ = 0;
}

uint32_t DopEncoder::calculateTransportRate(uint32_t dsdRate) {
    // Each DoP frame carries 16 DSD bits (2 DSD bytes)
    // So transport rate = dsd_rate / 16
    // DSD64 (2822400) -> 176400
    // DSD128 (5644800) -> 352800
    // DSD256 (11289600) -> 705600
    if (dsdRate == 2822400) return DOP_RATE_DSD64;
    if (dsdRate == 5644800) return DOP_RATE_DSD128;
    if (dsdRate == 11289600) return DOP_RATE_DSD256;

    // Generic calculation: DSD rate / 16
    return dsdRate / 16;
}

size_t DopEncoder::calculateOutputSize(size_t dsdBytes) {
    // Each pair of DSD bytes produces one 3-byte DoP frame
    size_t frames = dsdBytes / 2;
    return frames * DOP_FRAME_BYTES;
}

} // namespace dop
} // namespace bitperfect
