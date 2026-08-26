package com.bitperfect.android.player

import com.bitperfect.android.engine.NativeAudioEngine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Unit tests for PlaybackController state machine.
 *
 * Tests state transitions: Idle -> Playing, Playing -> Paused, Paused -> Playing,
 * Playing -> Stopped, seek behavior, and error handling.
 *
 * Note: These tests use a mock NativeAudioEngine since the real one requires JNI.
 * In a full test environment, use Mockito or a test double.
 */
@DisplayName("PlaybackController State Machine Tests")
class PlaybackControllerTest {

    // Simple test double for NativeAudioEngine
    // In production tests, would use Mockito or similar
    private lateinit var mockEngine: TestNativeAudioEngine
    private lateinit var controller: PlaybackController

    @BeforeEach
    fun setUp() {
        mockEngine = TestNativeAudioEngine()
        controller = PlaybackController(mockEngine)
    }

    // === State Transitions ===

    @Test
    @DisplayName("Initial state is Idle")
    fun initialStateIsIdle() {
        assertTrue(controller.state is PlaybackState.Idle)
    }

    @Test
    @DisplayName("Idle -> Playing when track available")
    fun idleToPlayingWithTrack() {
        controller.queue.add("/music/track1.flac")
        controller.play()

        assertTrue(controller.state is PlaybackState.Playing)
    }

    @Test
    @DisplayName("Idle -> Idle when no track in queue")
    fun idleStaysIdleWithNoTrack() {
        controller.play()
        assertTrue(controller.state is PlaybackState.Idle)
    }

    @Test
    @DisplayName("Playing -> Paused on pause()")
    fun playingToPaused() {
        controller.queue.add("/music/track1.flac")
        controller.play()
        assertTrue(controller.state is PlaybackState.Playing)

        controller.pause()
        assertTrue(controller.state is PlaybackState.Paused)
    }

    @Test
    @DisplayName("Paused -> Playing on play()")
    fun pausedToPlaying() {
        controller.queue.add("/music/track1.flac")
        controller.play()
        controller.pause()
        assertTrue(controller.state is PlaybackState.Paused)

        controller.play() // Should resume
        assertTrue(controller.state is PlaybackState.Playing)
    }

    @Test
    @DisplayName("Playing -> Stopped on stop()")
    fun playingToStopped() {
        controller.queue.add("/music/track1.flac")
        controller.play()
        controller.stop()

        assertTrue(controller.state is PlaybackState.Stopped)
    }

    @Test
    @DisplayName("Stopped -> Playing on play()")
    fun stoppedToPlaying() {
        controller.queue.add("/music/track1.flac")
        controller.play()
        controller.stop()
        controller.play()

        assertTrue(controller.state is PlaybackState.Playing)
    }

    @Test
    @DisplayName("Pause has no effect when not playing")
    fun pauseWhenNotPlaying() {
        controller.pause()
        assertTrue(controller.state is PlaybackState.Idle)
    }

    // === Seek Behavior ===

    @Test
    @DisplayName("Seek while playing updates position")
    fun seekWhilePlaying() {
        controller.queue.add("/music/track1.flac")
        controller.play()

        controller.seek(30000L) // Seek to 30 seconds
        val state = controller.state
        assertTrue(state is PlaybackState.Playing)
        assertEquals(30000L, (state as PlaybackState.Playing).positionMs)
    }

    @Test
    @DisplayName("Seek while paused updates position")
    fun seekWhilePaused() {
        controller.queue.add("/music/track1.flac")
        controller.play()
        controller.pause()

        controller.seek(15000L)
        val state = controller.state
        assertTrue(state is PlaybackState.Paused)
        assertEquals(15000L, (state as PlaybackState.Paused).positionMs)
    }

    @Test
    @DisplayName("Seek to zero restarts track")
    fun seekToZero() {
        controller.queue.add("/music/track1.flac")
        controller.play()
        controller.seek(50000L)
        controller.seek(0L)

        val state = controller.state as PlaybackState.Playing
        assertEquals(0L, state.positionMs)
    }

    // === Next/Previous ===

    @Test
    @DisplayName("Next advances to next track")
    fun nextAdvances() {
        controller.queue.addAll(listOf("/music/track1.flac", "/music/track2.flac"))
        controller.play()
        controller.next()

        val state = controller.state
        assertTrue(state is PlaybackState.Playing)
        assertEquals("/music/track2.flac", (state as PlaybackState.Playing).trackPath)
    }

    @Test
    @DisplayName("Next at end stops playback (RepeatMode.OFF)")
    fun nextAtEndStops() {
        controller.queue.add("/music/track1.flac")
        controller.play()
        controller.next()

        assertTrue(controller.state is PlaybackState.Stopped)
    }

    @Test
    @DisplayName("Previous restarts when position > 3s")
    fun previousRestartsAfter3Seconds() {
        controller.queue.addAll(listOf("/music/track1.flac", "/music/track2.flac"))
        controller.play()
        controller.updatePosition(5000L) // 5 seconds in
        controller.previous()

        // Should restart current track (seek to 0)
        val state = controller.state as PlaybackState.Playing
        assertEquals(0L, state.positionMs)
    }

    // === Error Handling ===

    @Test
    @DisplayName("Error state when engine configure fails")
    fun errorOnConfigureFailure() {
        mockEngine.shouldFailConfigure = true
        controller.queue.add("/music/track1.flac")
        controller.play()

        assertTrue(controller.state is PlaybackState.Error)
    }

    @Test
    @DisplayName("Error state when engine start fails")
    fun errorOnStartFailure() {
        mockEngine.shouldFailStart = true
        controller.queue.add("/music/track1.flac")
        controller.play()

        assertTrue(controller.state is PlaybackState.Error)
    }

    // === State Listeners ===

    @Test
    @DisplayName("State listeners are notified")
    fun stateListenerNotified() {
        val states = mutableListOf<PlaybackState>()
        controller.addStateListener { states.add(it) }

        controller.queue.add("/music/track1.flac")
        controller.play()

        assertTrue(states.isNotEmpty())
        // Should have received Loading and Playing states
        assertTrue(states.any { it is PlaybackState.Loading })
        assertTrue(states.any { it is PlaybackState.Playing })
    }

    @Test
    @DisplayName("Removed listener not called")
    fun removedListenerNotCalled() {
        val states = mutableListOf<PlaybackState>()
        val listener: (PlaybackState) -> Unit = { states.add(it) }

        controller.addStateListener(listener)
        controller.removeStateListener(listener)

        controller.queue.add("/music/track1.flac")
        controller.play()

        assertTrue(states.isEmpty())
    }

    // === Repeat Mode ===

    @Test
    @DisplayName("Set and get repeat mode")
    fun repeatMode() {
        controller.setRepeatMode(RepeatMode.ALL)
        assertEquals(RepeatMode.ALL, controller.getRepeatMode())

        controller.setRepeatMode(RepeatMode.ONE)
        assertEquals(RepeatMode.ONE, controller.getRepeatMode())
    }

    // === Shuffle ===

    @Test
    @DisplayName("Toggle shuffle")
    fun toggleShuffle() {
        assertFalse(controller.isShuffleEnabled())
        controller.toggleShuffle()
        assertTrue(controller.isShuffleEnabled())
        controller.toggleShuffle()
        assertFalse(controller.isShuffleEnabled())
    }

    // === Release ===

    @Test
    @DisplayName("Release stops and clears listeners")
    fun releaseStopsAndClears() {
        controller.queue.add("/music/track1.flac")
        controller.play()
        controller.release()

        assertTrue(controller.state is PlaybackState.Stopped)
    }
}

/**
 * Test double for NativeAudioEngine.
 * Simulates engine behavior without JNI.
 */
private class TestNativeAudioEngine : NativeAudioEngine() {
    var shouldFailConfigure = false
    var shouldFailStart = false
    private var currentRate = 44100

    override fun initialize(): Boolean = true
    override fun shutdown() {}
    override fun parseDevice(descriptorData: ByteArray): Boolean = true

    override fun configure(sampleRate: Int, format: Int, channels: Int, bufferSizeMs: Int): Boolean {
        if (shouldFailConfigure) return false
        currentRate = sampleRate
        return true
    }

    override fun startPlayback(): Boolean = !shouldFailStart
    override fun pausePlayback(): Boolean = true
    override fun resumePlayback(): Boolean = true
    override fun stopPlayback() {}
    override fun writeAudioData(data: ByteArray, offset: Int, length: Int): Int = length
    override fun getState(): Int = 0
    override fun getBufferLevel(): Float = 0.5f
    override fun getCurrentSampleRate(): Int = currentRate
    override fun getSupportedSampleRates(): IntArray = intArrayOf(44100, 96000, 192000)
    override fun getSupportedBitDepths(): IntArray = intArrayOf(16, 24, 32)
    override fun getUnderrunCount(): Int = 0
    override fun getTotalBytesTransferred(): Long = 0L
    override fun getDeviceName(): String = "Test DAC"
}
