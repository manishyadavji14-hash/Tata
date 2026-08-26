#include "pcm_engine.h"
#include <cstring>
#include <algorithm>

namespace bitperfect {
namespace pcm {

void PcmEngine::configure(const PcmFormatInfo& format) {
    format_ = format;
}

size_t PcmEngine::passthrough(const uint8_t* src, size_t srcSize,
                               uint8_t* dst, size_t dstSize) {
    if (!src || !dst || srcSize == 0 || dstSize == 0) return 0;
    size_t copySize = std::min(srcSize, dstSize);
    std::memcpy(dst, src, copySize);
    return copySize;
}

size_t PcmEngine::convert(const uint8_t* src, size_t srcSize,
                           uint8_t* dst, size_t dstSize,
                           PcmFormat srcFormat, PcmFormat dstFormat,
                           uint8_t channels) {
    if (!src || !dst || srcSize == 0 || dstSize == 0 || channels == 0) return 0;

    // Same format = passthrough
    if (srcFormat == dstFormat) {
        return passthrough(src, srcSize, dst, dstSize);
    }

    uint8_t srcBps = getBytesPerSample(srcFormat);
    if (srcBps == 0) return 0;
    size_t srcFrames = srcSize / (srcBps * channels);
    if (srcFrames == 0) return 0;

    // Route to specific converter
    if (srcFormat == PcmFormat::S16_LE && dstFormat == PcmFormat::S24_3LE) {
        return convert16to24_3(src, srcFrames, dst, dstSize, channels);
    }
    if (srcFormat == PcmFormat::S16_LE && dstFormat == PcmFormat::S32_LE) {
        return convert16to32(src, srcFrames, dst, dstSize, channels);
    }
    if (srcFormat == PcmFormat::S24_3LE && dstFormat == PcmFormat::S32_LE) {
        return convert24_3to32(src, srcFrames, dst, dstSize, channels);
    }
    if (srcFormat == PcmFormat::S32_LE && dstFormat == PcmFormat::S24_3LE) {
        return convert32to24_3(src, srcFrames, dst, dstSize, channels);
    }
    if (srcFormat == PcmFormat::S24_3LE && dstFormat == PcmFormat::S24_LE) {
        return convert24_3to24in32(src, srcFrames, dst, dstSize, channels);
    }
    if (srcFormat == PcmFormat::S24_LE && dstFormat == PcmFormat::S24_3LE) {
        return convert24in32to24_3(src, srcFrames, dst, dstSize, channels);
    }
    // 16 to 24-in-32: chain 16->24_3->24in32 or handle directly
    if (srcFormat == PcmFormat::S16_LE && dstFormat == PcmFormat::S24_LE) {
        // 16-bit to 24-in-32: zero-pad 2 LSBs
        size_t totalSamples = srcFrames * channels;
        size_t needed = totalSamples * 4;
        if (dstSize < needed) return 0;

        const uint8_t* sp = src;
        uint8_t* dp = dst;
        for (size_t i = 0; i < totalSamples; ++i) {
            dp[0] = 0;       // LSB padding
            dp[1] = sp[0];   // Original low byte
            dp[2] = sp[1];   // Original high byte
            dp[3] = (sp[1] & 0x80) ? 0xFF : 0x00;  // Sign extension to fill 32-bit
            sp += 2;
            dp += 4;
        }
        return needed;
    }

    return 0; // Unsupported conversion
}

size_t PcmEngine::convert16to24_3(const uint8_t* src, size_t srcFrames,
                                    uint8_t* dst, size_t dstSize, uint8_t channels) {
    size_t totalSamples = srcFrames * channels;
    size_t needed = totalSamples * 3;
    if (dstSize < needed) return 0;

    const uint8_t* sp = src;
    uint8_t* dp = dst;

    for (size_t i = 0; i < totalSamples; ++i) {
        // 16-bit to 24-bit: place 16-bit data in upper bits, zero-pad LSB
        dp[0] = 0;       // LSB (zero padding)
        dp[1] = sp[0];   // Original low byte
        dp[2] = sp[1];   // Original high byte (MSB)
        sp += 2;
        dp += 3;
    }

    return needed;
}

size_t PcmEngine::convert16to32(const uint8_t* src, size_t srcFrames,
                                  uint8_t* dst, size_t dstSize, uint8_t channels) {
    size_t totalSamples = srcFrames * channels;
    size_t needed = totalSamples * 4;
    if (dstSize < needed) return 0;

    const uint8_t* sp = src;
    uint8_t* dp = dst;

    for (size_t i = 0; i < totalSamples; ++i) {
        // 16-bit to 32-bit: place in upper 16 bits, zero-pad lower 16 bits
        dp[0] = 0;       // LSB padding
        dp[1] = 0;       // Second padding byte
        dp[2] = sp[0];   // Original low byte
        dp[3] = sp[1];   // Original high byte (MSB)
        sp += 2;
        dp += 4;
    }

    return needed;
}

size_t PcmEngine::convert24_3to32(const uint8_t* src, size_t srcFrames,
                                    uint8_t* dst, size_t dstSize, uint8_t channels) {
    size_t totalSamples = srcFrames * channels;
    size_t needed = totalSamples * 4;
    if (dstSize < needed) return 0;

    const uint8_t* sp = src;
    uint8_t* dp = dst;

    for (size_t i = 0; i < totalSamples; ++i) {
        // 24-bit packed to 32-bit: place in upper 24 bits, zero-pad LSB
        dp[0] = 0;       // LSB padding
        dp[1] = sp[0];   // Original byte 0
        dp[2] = sp[1];   // Original byte 1
        dp[3] = sp[2];   // Original byte 2 (MSB)
        sp += 3;
        dp += 4;
    }

    return needed;
}

size_t PcmEngine::convert32to24_3(const uint8_t* src, size_t srcFrames,
                                    uint8_t* dst, size_t dstSize, uint8_t channels) {
    size_t totalSamples = srcFrames * channels;
    size_t needed = totalSamples * 3;
    if (dstSize < needed) return 0;

    const uint8_t* sp = src;
    uint8_t* dp = dst;

    for (size_t i = 0; i < totalSamples; ++i) {
        // 32-bit to 24-bit packed: take upper 24 bits (bytes 1, 2, 3)
        dp[0] = sp[1];   // Byte 1 of 32-bit value
        dp[1] = sp[2];   // Byte 2
        dp[2] = sp[3];   // Byte 3 (MSB)
        sp += 4;
        dp += 3;
    }

    return needed;
}

size_t PcmEngine::convert24_3to24in32(const uint8_t* src, size_t srcFrames,
                                        uint8_t* dst, size_t dstSize, uint8_t channels) {
    size_t totalSamples = srcFrames * channels;
    size_t needed = totalSamples * 4;
    if (dstSize < needed) return 0;

    const uint8_t* sp = src;
    uint8_t* dp = dst;

    for (size_t i = 0; i < totalSamples; ++i) {
        // 24-bit packed to 24-in-32 container: zero-pad MSB
        dp[0] = sp[0];   // Original byte 0 (LSB of 24-bit)
        dp[1] = sp[1];   // Original byte 1
        dp[2] = sp[2];   // Original byte 2 (MSB of 24-bit)
        dp[3] = (sp[2] & 0x80) ? 0xFF : 0x00;  // Sign extension
        sp += 3;
        dp += 4;
    }

    return needed;
}

size_t PcmEngine::convert24in32to24_3(const uint8_t* src, size_t srcFrames,
                                        uint8_t* dst, size_t dstSize, uint8_t channels) {
    size_t totalSamples = srcFrames * channels;
    size_t needed = totalSamples * 3;
    if (dstSize < needed) return 0;

    const uint8_t* sp = src;
    uint8_t* dp = dst;

    for (size_t i = 0; i < totalSamples; ++i) {
        // 24-in-32 to 24-bit packed: take lower 3 bytes
        dp[0] = sp[0];   // Byte 0 (LSB of 24-bit)
        dp[1] = sp[1];   // Byte 1
        dp[2] = sp[2];   // Byte 2 (MSB of 24-bit)
        sp += 4;
        dp += 3;
    }

    return needed;
}

uint8_t PcmEngine::getBytesPerSample(PcmFormat format) {
    switch (format) {
        case PcmFormat::S16_LE:   return 2;
        case PcmFormat::S24_3LE:  return 3;
        case PcmFormat::S24_LE:   return 4;
        case PcmFormat::S32_LE:   return 4;
        case PcmFormat::FLOAT_LE: return 4;
    }
    return 0;
}

uint8_t PcmEngine::getBitsPerSample(PcmFormat format) {
    switch (format) {
        case PcmFormat::S16_LE:   return 16;
        case PcmFormat::S24_3LE:  return 24;
        case PcmFormat::S24_LE:   return 24;
        case PcmFormat::S32_LE:   return 32;
        case PcmFormat::FLOAT_LE: return 32;
    }
    return 0;
}

bool PcmEngine::isPassthroughCompatible(PcmFormat src, PcmFormat dst) {
    return src == dst;
}

bool PcmEngine::verifyBitPerfect(const uint8_t* src, size_t srcSize,
                                   const uint8_t* dst, size_t dstSize,
                                   PcmFormat srcFormat, PcmFormat dstFormat,
                                   uint8_t channels) {
    if (!src || !dst || srcSize == 0 || dstSize == 0 || channels == 0) return false;

    // Same format: byte-for-byte comparison
    if (srcFormat == dstFormat) {
        if (srcSize != dstSize) return false;
        return std::memcmp(src, dst, srcSize) == 0;
    }

    uint8_t srcBps = getBytesPerSample(srcFormat);
    uint8_t dstBps = getBytesPerSample(dstFormat);
    if (srcBps == 0 || dstBps == 0) return false;

    size_t srcFrames = srcSize / (srcBps * channels);
    size_t dstFrames = dstSize / (dstBps * channels);
    if (srcFrames != dstFrames) return false;

    size_t totalSamples = srcFrames * channels;

    // Verify that original bits are preserved in the conversion
    for (size_t i = 0; i < totalSamples; ++i) {
        const uint8_t* sp = src + i * srcBps;
        const uint8_t* dp = dst + i * dstBps;

        // Extract the significant bits from source
        int32_t srcVal = 0;
        switch (srcFormat) {
            case PcmFormat::S16_LE:
                srcVal = static_cast<int16_t>(sp[0] | (sp[1] << 8));
                srcVal <<= 16; // Shift to 32-bit MSB-aligned
                break;
            case PcmFormat::S24_3LE:
                srcVal = sp[0] | (sp[1] << 8) | (sp[2] << 16);
                if (srcVal & 0x800000) srcVal |= 0xFF000000; // Sign extend
                srcVal <<= 8; // Shift to 32-bit MSB-aligned
                break;
            case PcmFormat::S24_LE:
                srcVal = sp[0] | (sp[1] << 8) | (sp[2] << 16);
                if (srcVal & 0x800000) srcVal |= 0xFF000000;
                srcVal <<= 8;
                break;
            case PcmFormat::S32_LE:
                srcVal = sp[0] | (sp[1] << 8) | (sp[2] << 16) | (sp[3] << 24);
                break;
            default:
                return false;
        }

        // Extract significant bits from destination
        int32_t dstVal = 0;
        switch (dstFormat) {
            case PcmFormat::S16_LE:
                dstVal = static_cast<int16_t>(dp[0] | (dp[1] << 8));
                dstVal <<= 16;
                break;
            case PcmFormat::S24_3LE:
                dstVal = dp[0] | (dp[1] << 8) | (dp[2] << 16);
                if (dstVal & 0x800000) dstVal |= 0xFF000000;
                dstVal <<= 8;
                break;
            case PcmFormat::S24_LE:
                dstVal = dp[0] | (dp[1] << 8) | (dp[2] << 16);
                if (dstVal & 0x800000) dstVal |= 0xFF000000;
                dstVal <<= 8;
                break;
            case PcmFormat::S32_LE:
                dstVal = dp[0] | (dp[1] << 8) | (dp[2] << 16) | (dp[3] << 24);
                break;
            default:
                return false;
        }

        // Compare - masking for precision loss in down-conversion
        uint8_t srcBits = getBitsPerSample(srcFormat);
        uint8_t dstBits = getBitsPerSample(dstFormat);
        uint8_t compareBits = std::min(srcBits, dstBits);
        int32_t mask = ~((1 << (32 - compareBits)) - 1);

        if ((srcVal & mask) != (dstVal & mask)) {
            return false;
        }
    }

    return true;
}

} // namespace pcm
} // namespace bitperfect
