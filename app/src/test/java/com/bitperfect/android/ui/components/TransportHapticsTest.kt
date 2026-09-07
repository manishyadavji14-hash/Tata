package com.bitperfect.android.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Choosing a haptic constant the running Android version actually has.
 *
 * The expressive constants all arrived after this app's minimum of API 29, so each
 * kind has to fall back. Referencing one that does not exist yet is not a compile
 * error — they are plain ints — it is a runtime surprise, which is exactly the sort
 * of thing worth a test rather than a careful read.
 */
@DisplayName("TransportHaptics Tests")
class TransportHapticsTest {

    @Test
    @DisplayName("confirm uses CONFIRM from API 30 and KEYBOARD_TAP before it")
    fun confirmFallsBack() {
        assertEquals(
            HapticFeedbackConstants.CONFIRM,
            TransportHaptics.confirmConstant(Build.VERSION_CODES.R)
        )
        assertEquals(
            HapticFeedbackConstants.CONFIRM,
            TransportHaptics.confirmConstant(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
        // minSdk is 29, so this branch is reachable on real devices.
        assertEquals(
            HapticFeedbackConstants.KEYBOARD_TAP,
            TransportHaptics.confirmConstant(Build.VERSION_CODES.Q)
        )
    }

    @Test
    @DisplayName("tick uses SEGMENT_TICK from API 34 and CLOCK_TICK before it")
    fun tickFallsBack() {
        assertEquals(
            HapticFeedbackConstants.SEGMENT_TICK,
            TransportHaptics.tickConstant(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            TransportHaptics.tickConstant(Build.VERSION_CODES.TIRAMISU)
        )
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            TransportHaptics.tickConstant(Build.VERSION_CODES.Q)
        )
    }

    @Test
    @DisplayName("gesture end uses GESTURE_END from API 30 and CLOCK_TICK before it")
    fun gestureEndFallsBack() {
        assertEquals(
            HapticFeedbackConstants.GESTURE_END,
            TransportHaptics.gestureEndConstant(Build.VERSION_CODES.R)
        )
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            TransportHaptics.gestureEndConstant(Build.VERSION_CODES.Q)
        )
    }

    @Test
    @DisplayName("every kind resolves to something on the oldest supported release")
    fun nothingIsUnresolvedAtMinSdk() {
        // minSdk 29. A constant that does not exist there would be a crash on the
        // oldest devices the app claims to support.
        val minSdk = 29
        for (constant in listOf(
            TransportHaptics.confirmConstant(minSdk),
            TransportHaptics.tickConstant(minSdk),
            TransportHaptics.gestureEndConstant(minSdk)
        )) {
            assertEquals(true, constant >= 0, "resolved to a nonsense constant: $constant")
        }
    }

    @Test
    @DisplayName("the three kinds are actually distinguishable on a modern release")
    fun kindsDifferWhereTheyCan() {
        val modern = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val confirm = TransportHaptics.confirmConstant(modern)
        val tick = TransportHaptics.tickConstant(modern)
        val gestureEnd = TransportHaptics.gestureEndConstant(modern)

        // If these collapsed to one value, pressing play would feel identical to
        // skipping a track, and the point of having three is lost.
        assertEquals(3, setOf(confirm, tick, gestureEnd).size)
    }
}
