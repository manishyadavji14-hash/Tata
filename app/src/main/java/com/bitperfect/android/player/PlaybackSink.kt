package com.bitperfect.android.player

/**
 * An audio output backend.
 *
 * There are two, and they are not interchangeable in quality:
 *
 *  - [AudioTrackPlaybackSink] renders through Android's mixer. It always works,
 *    but the platform may resample or mix, so it is not bit-perfect.
 *  - [UsbPlaybackSink] streams to a USB DAC through the native engine, bypassing
 *    the mixer. This is the point of the app, and needs a DAC attached.
 *
 * Both decode with the native WAV/FLAC decoders through
 * `NativeAudioEngine.openDecoder`, so the bytes handed to either output come off
 * the same decode path.
 *
 * Implementations own a worker thread. Every method here is called from the main
 * thread; callbacks are posted back to the main thread.
 */
interface PlaybackSink {

    interface Listener {
        fun onPrepared(trackPath: String, format: AudioFormatInfo, durationMs: Long)
        fun onCompleted(trackPath: String)
        fun onError(trackPath: String, message: String)
    }

    /** Position within the current track, as rendered. */
    val positionMs: Long

    /** Duration of the current track, 0 until [Listener.onPrepared]. */
    val durationMs: Long

    /** Format of the current track, null when nothing is loaded. */
    val currentFormat: AudioFormatInfo?

    /**
     * Platform equalizer and bass boost, or null when this output cannot carry
     * them.
     *
     * Null for USB: platform audio effects attach to an AudioTrack session, and
     * a stream that bypasses AudioTrack has no session for them to bind to.
     * Applying them would also stop the output being bit-perfect, which is the
     * whole reason for using a DAC.
     */
    val audioEffects: AudioEffectsController?

    /** Human-readable name of this output, for diagnostics and the UI. */
    val outputName: String

    /** Whether this output delivers unmodified samples to the hardware. */
    val isBitPerfect: Boolean

    /** Begin playing a file. Replaces anything currently playing. */
    fun play(trackPath: String)

    /** @return true when the state actually changed. */
    fun pause(): Boolean

    /** @return true when the state actually changed. */
    fun resume(): Boolean

    /** @return true when the seek was accepted. */
    fun seekTo(positionMs: Long): Boolean

    /** Stop and reset to an idle state. */
    fun stop()

    /** Release all resources. The sink is unusable afterwards. */
    fun release()

    /**
     * Force the reported position, for callers that track it externally such as
     * a media session.
     */
    fun overridePosition(positionMs: Long)
}
