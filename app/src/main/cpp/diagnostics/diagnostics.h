#pragma once

#include <cstdint>
#include <string>
#include <atomic>
#include <functional>

namespace bitperfect {
namespace diagnostics {

/**
 * Log categories for structured logging.
 */
enum class LogCategory : uint8_t {
    BOOT = 0,
    USB,
    CLOCK,
    FORMAT,
    PCM,
    DSD,
    DOP,
    NATIVE_DSD,
    BUFFER,
    TRANSFER,
    ERROR,
    RECOVERY,
    JNI,
    COUNT
};

/**
 * Log severity levels.
 */
enum class LogLevel : uint8_t {
    VERBOSE = 0,
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    FATAL
};

/**
 * Transfer latency sample for tracking.
 */
struct LatencySample {
    uint64_t timestampUs = 0;
    uint32_t latencyUs = 0;
    uint32_t transferSize = 0;
};

/**
 * Aggregated statistics.
 */
struct DiagnosticStats {
    uint64_t totalTransfers = 0;
    uint64_t totalErrors = 0;
    uint64_t totalUnderruns = 0;
    uint64_t totalOverruns = 0;
    uint64_t totalBytesTransferred = 0;
    double avgLatencyUs = 0.0;
    double maxLatencyUs = 0.0;
    double minLatencyUs = 0.0;
    uint32_t currentBufferFillPercent = 0;
};

/**
 * Callback for log output.
 */
using LogCallback = std::function<void(LogLevel level, LogCategory category,
                                        const std::string& message)>;

/**
 * Diagnostics - Structured logging and statistics for the audio engine.
 * Thread-safe, minimal overhead in hot path.
 */
class Diagnostics {
public:
    static Diagnostics& instance();

    /**
     * Log a message.
     */
    void log(LogLevel level, LogCategory category, const char* fmt, ...);

    /**
     * Log a message (string version).
     */
    void logMessage(LogLevel level, LogCategory category, const std::string& message);

    /**
     * Record a transfer completion for statistics.
     */
    void recordTransfer(uint32_t latencyUs, uint32_t bytes);

    /**
     * Record an error.
     */
    void recordError(LogCategory category, const std::string& errorMessage);

    /**
     * Record a buffer underrun.
     */
    void recordUnderrun();

    /**
     * Record a buffer overrun.
     */
    void recordOverrun();

    /**
     * Get current aggregate statistics.
     */
    DiagnosticStats getStats() const;

    /**
     * Reset all statistics.
     */
    void resetStats();

    /**
     * Set minimum log level.
     */
    void setMinLevel(LogLevel level) { minLevel_ = level; }

    /**
     * Get minimum log level.
     */
    LogLevel getMinLevel() const { return minLevel_; }

    /**
     * Enable/disable a category.
     */
    void setCategoryEnabled(LogCategory category, bool enabled);

    /**
     * Check if a category is enabled.
     */
    bool isCategoryEnabled(LogCategory category) const;

    /**
     * Set log output callback.
     */
    void setLogCallback(LogCallback callback);

    /**
     * Get category name string.
     */
    static const char* categoryName(LogCategory category);

    /**
     * Get level name string.
     */
    static const char* levelName(LogLevel level);

private:
    Diagnostics();
    ~Diagnostics() = default;

    LogLevel minLevel_ = LogLevel::INFO;
    bool categoryEnabled_[static_cast<int>(LogCategory::COUNT)];
    LogCallback logCallback_;

    // Statistics (atomic for thread safety)
    std::atomic<uint64_t> totalTransfers_{0};
    std::atomic<uint64_t> totalErrors_{0};
    std::atomic<uint64_t> totalUnderruns_{0};
    std::atomic<uint64_t> totalOverruns_{0};
    std::atomic<uint64_t> totalBytesTransferred_{0};
    std::atomic<uint64_t> totalLatencyUs_{0};
    std::atomic<uint64_t> maxLatencyUs_{0};
    std::atomic<uint64_t> minLatencyUs_{UINT64_MAX};
};

// Convenience macros
#define BP_LOG(level, category, ...) \
    bitperfect::diagnostics::Diagnostics::instance().log(level, category, __VA_ARGS__)

#define BP_LOG_VERBOSE(category, ...) BP_LOG(bitperfect::diagnostics::LogLevel::VERBOSE, category, __VA_ARGS__)
#define BP_LOG_DEBUG(category, ...) BP_LOG(bitperfect::diagnostics::LogLevel::DEBUG, category, __VA_ARGS__)
#define BP_LOG_INFO(category, ...) BP_LOG(bitperfect::diagnostics::LogLevel::INFO, category, __VA_ARGS__)
#define BP_LOG_WARN(category, ...) BP_LOG(bitperfect::diagnostics::LogLevel::WARNING, category, __VA_ARGS__)
#define BP_LOG_ERROR(category, ...) BP_LOG(bitperfect::diagnostics::LogLevel::ERROR, category, __VA_ARGS__)

} // namespace diagnostics
} // namespace bitperfect
