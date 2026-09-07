package com.bitperfect.android.player

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Which formats may be sent to a USB DAC.
 *
 * This is the rule that decides whether an attached DAC carries a track at all, so it
 * is worth pinning down. It exists because the previous behaviour started every track
 * on the USB sink and let unsupported formats fail there, which left the player stopped
 * with the reason gone from the screen a moment later — a file that plays perfectly
 * well became unplayable merely because a DAC was plugged in.
 *
 * A format only qualifies if a native decoder can produce the file's own samples. A
 * platform-decoded stream has been through a conversion that cannot be vouched for, so
 * sending it to the DAC while calling the path bit-perfect would be a lie.
 */
class UsbOutputFormatRuleTest {

    @Test
    @DisplayName("WAV and FLAC can be sent to a DAC untouched")
    fun exactFormatsQualify() {
        for (path in listOf(
            "/music/track.wav",
            "/music/track.wave",
            "/music/track.flac",
            "/music/UPPER.FLAC",
            "/music/Mixed.Wav"
        )) {
            assertTrue(NativePcmSource.canOpen(path), "expected $path to qualify")
        }
    }

    @Test
    @DisplayName("Platform-decoded formats do not, so they go to Android's output")
    fun lossyFormatsDoNotQualify() {
        // Every one of these plays fine on the Android path. The point of the rule is
        // that the DAC is skipped for them, not that the file is rejected.
        for (path in listOf(
            "/music/track.m4a",
            "/music/track.aac",
            "/music/track.mp3",
            "/music/track.ogg",
            "/music/track.opus",
            "/music/track.dsf"
        )) {
            assertFalse(NativePcmSource.canOpen(path), "expected $path not to qualify")
        }
    }

    @Test
    @DisplayName("A file with no extension does not qualify rather than being guessed at")
    fun extensionlessDoesNotQualify() {
        assertFalse(NativePcmSource.canOpen("/music/track"))
        assertFalse(NativePcmSource.canOpen(""))
    }
}
