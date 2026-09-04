package com.bitperfect.android.ui.components

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Short haptics for the transport controls.
 *
 * Deliberately not Compose's `LocalHapticFeedback`: on this Compose version that
 * offers only `LongPress` and `TextHandleMove`, neither of which is a button press.
 * The platform constants have the right vocabulary — a confirmation for a state
 * change, a tick for stepping through a list, a gesture end for releasing a
 * scrubber — so they are used directly through the host `View`.
 *
 * Each kind resolves to the best constant the running version has and falls back on
 * older releases, because the expressive ones arrived well after this app's minimum.
 * Nothing here overrides the user's system haptics setting: `performHapticFeedback`
 * respects it, and a music player is not the place to insist.
 */
// InlinedApi: these constants are `static final int`, so the compiler bakes the
// value in and there is no field lookup at runtime — nothing to fail on an older
// release. Which value is used is chosen by SDK_INT below, and
// TransportHapticsTest pins every branch, including the minSdk one.
@SuppressLint("InlinedApi")
object TransportHaptics {

    /** A state the user just changed: play/pause, favourite, shuffle on. */
    fun confirm(view: View) {
        view.performHapticFeedback(confirmConstant(Build.VERSION.SDK_INT))
    }

    /** Stepping to another item: next, previous, cycling repeat mode. */
    fun tick(view: View) {
        view.performHapticFeedback(tickConstant(Build.VERSION.SDK_INT))
    }

    /** Letting go of the seek bar. */
    fun gestureEnd(view: View) {
        view.performHapticFeedback(gestureEndConstant(Build.VERSION.SDK_INT))
    }

    /**
     * `CONFIRM` is a deliberate, slightly weightier tap, added in API 30.
     * `KEYBOARD_TAP` is the closest thing available before that.
     */
    internal fun confirmConstant(sdkInt: Int): Int =
        if (sdkInt >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }

    /**
     * `SEGMENT_TICK` (API 34) is tuned for moving through discrete items and is
     * lighter than `CLOCK_TICK`, which is the long-standing fallback.
     */
    internal fun tickConstant(sdkInt: Int): Int =
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }

    /**
     * `GESTURE_END` (API 30) marks the completion of a drag, which is exactly what
     * releasing the seek bar is.
     */
    internal fun gestureEndConstant(sdkInt: Int): Int =
        if (sdkInt >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.GESTURE_END
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
}
