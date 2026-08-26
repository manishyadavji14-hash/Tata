package com.bitperfect.android.player

/**
 * Sealed class representing all possible playback states.
 *
 * Each state carries associated data relevant to that state.
 * This provides exhaustive pattern matching in when expressions.
 */
sealed class PlaybackState {

    /** Player is idle - no track loaded. */
    data object Idle : PlaybackState()

    /** Player is loading a track. */
    data class Loading(
        val trackPath: String
    ) : PlaybackState()

    /** Player is actively playing. */
    data class Playing(
        val trackPath: String,
        val positionMs: Long,
        val durationMs: Long,
        val format: AudioFormatInfo
    ) : PlaybackState()

    /** Player is paused. */
    data class Paused(
        val trackPath: String,
        val positionMs: Long
    ) : PlaybackState()

    /** Player encountered an error. */
    data class Error(
        val message: String,
        val trackPath: String? = null
    ) : PlaybackState()

    /** Playback has stopped (reached end of queue). */
    data object Stopped : PlaybackState()
}

/**
 * Audio format information for the currently playing track.
 */
data class AudioFormatInfo(
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val codec: String = "PCM"
) {
    val isHighRes: Boolean
        get() = sampleRate > 48000 || bitDepth > 16

    val displayString: String
        get() = "${codec} ${sampleRate / 1000.0}kHz / ${bitDepth}bit"
}
