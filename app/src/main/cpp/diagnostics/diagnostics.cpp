#include "diagnostics.h"
#include <cstdarg>
#include <cstdio>
#include <algorithm>

namespace bitperfect {
namespace diagnostics {

Diagnostics& Diagnostics::instance() {
    static Diagnostics instance;
    return instance;
}

Diagnostics::Diagnostics() {
    for (int i = 0; i < static_cast<int>(LogCategory::COUNT); ++i) {
        categoryEnabled_[i] = true;
    }
}

void Diagnostics::log(LogLevel level, LogCategory category, const char* fmt, ...) {
    if (level < minLevel_) return;
    if (!isCategoryEnabled(category)) return;

    char buffer[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    std::string message(buffer);
    logMessage(level, category, message);
}

void Diagnostics::logMessage(LogLevel level, LogCategory category, const std::string& message) {
    if (level < minLevel_) return;
    if (!isCategoryEnabled(category)) return;

    if (logCallback_) {
        logCallback_(level, category, message);
    }

    // In Android, this would call __android_log_print
    // For standalone testing, output to stderr
#ifndef __ANDROID__
    if (level >= LogLevel::WARNING) {
        fprintf(stderr, "[%s][%s] %s\n", levelName(level), categoryName(category), message.c_str());
    }
#endif
}

void Diagnostics::recordTransfer(uint32_t latencyUs, uint32_t bytes) {
    totalTransfers_.fetch_add(1, std::memory_order_relaxed);
    totalBytesTransferred_.fetch_add(bytes, std::memory_order_relaxed);
    totalLatencyUs_.fetch_add(latencyUs, std::memory_order_relaxed);

    // Update max latency (relaxed CAS loop)
    uint64_t current = maxLatencyUs_.load(std::memory_order_relaxed);
    while (latencyUs > current) {
        if (maxLatencyUs_.compare_exchange_weak(current, latencyUs, std::memory_order_relaxed)) {
            break;
        }
    }

    // Update min latency
    current = minLatencyUs_.load(std::memory_order_relaxed);
    while (latencyUs < current) {
        if (minLatencyUs_.compare_exchange_weak(current, latencyUs, std::memory_order_relaxed)) {
            break;
        }
    }
}

void Diagnostics::recordError(LogCategory category, const std::string& errorMessage) {
    totalErrors_.fetch_add(1, std::memory_order_relaxed);
    logMessage(LogLevel::ERROR, category, errorMessage);
}

void Diagnostics::recordUnderrun() {
    totalUnderruns_.fetch_add(1, std::memory_order_relaxed);
}

void Diagnostics::recordOverrun() {
    totalOverruns_.fetch_add(1, std::memory_order_relaxed);
}

DiagnosticStats Diagnostics::getStats() const {
    DiagnosticStats stats;
    stats.totalTransfers = totalTransfers_.load(std::memory_order_relaxed);
    stats.totalErrors = totalErrors_.load(std::memory_order_relaxed);
    stats.totalUnderruns = totalUnderruns_.load(std::memory_order_relaxed);
    stats.totalOverruns = totalOverruns_.load(std::memory_order_relaxed);
    stats.totalBytesTransferred = totalBytesTransferred_.load(std::memory_order_relaxed);

    uint64_t totalLat = totalLatencyUs_.load(std::memory_order_relaxed);
    uint64_t transfers = stats.totalTransfers;
    stats.avgLatencyUs = (transfers > 0) ? static_cast<double>(totalLat) / transfers : 0.0;
    stats.maxLatencyUs = static_cast<double>(maxLatencyUs_.load(std::memory_order_relaxed));

    uint64_t minLat = minLatencyUs_.load(std::memory_order_relaxed);
    stats.minLatencyUs = (minLat == UINT64_MAX) ? 0.0 : static_cast<double>(minLat);

    return stats;
}

void Diagnostics::resetStats() {
    totalTransfers_.store(0, std::memory_order_relaxed);
    totalErrors_.store(0, std::memory_order_relaxed);
    totalUnderruns_.store(0, std::memory_order_relaxed);
    totalOverruns_.store(0, std::memory_order_relaxed);
    totalBytesTransferred_.store(0, std::memory_order_relaxed);
    totalLatencyUs_.store(0, std::memory_order_relaxed);
    maxLatencyUs_.store(0, std::memory_order_relaxed);
    minLatencyUs_.store(UINT64_MAX, std::memory_order_relaxed);
}

void Diagnostics::setCategoryEnabled(LogCategory category, bool enabled) {
    int idx = static_cast<int>(category);
    if (idx >= 0 && idx < static_cast<int>(LogCategory::COUNT)) {
        categoryEnabled_[idx] = enabled;
    }
}

bool Diagnostics::isCategoryEnabled(LogCategory category) const {
    int idx = static_cast<int>(category);
    if (idx >= 0 && idx < static_cast<int>(LogCategory::COUNT)) {
        return categoryEnabled_[idx];
    }
    return false;
}

void Diagnostics::setLogCallback(LogCallback callback) {
    logCallback_ = std::move(callback);
}

const char* Diagnostics::categoryName(LogCategory category) {
    switch (category) {
        case LogCategory::BOOT:     return "BOOT";
        case LogCategory::USB:      return "USB";
        case LogCategory::CLOCK:    return "CLOCK";
        case LogCategory::FORMAT:   return "FORMAT";
        case LogCategory::PCM:      return "PCM";
        case LogCategory::BUFFER:   return "BUFFER";
        case LogCategory::TRANSFER: return "TRANSFER";
        case LogCategory::ERROR:    return "ERROR";
        case LogCategory::RECOVERY: return "RECOVERY";
        case LogCategory::JNI:      return "JNI";
        default:                    return "UNKNOWN";
    }
}

const char* Diagnostics::levelName(LogLevel level) {
    switch (level) {
        case LogLevel::VERBOSE: return "V";
        case LogLevel::DEBUG:   return "D";
        case LogLevel::INFO:    return "I";
        case LogLevel::WARNING: return "W";
        case LogLevel::ERROR:   return "E";
        case LogLevel::FATAL:   return "F";
        default:                return "?";
    }
}

} // namespace diagnostics
} // namespace bitperfect
