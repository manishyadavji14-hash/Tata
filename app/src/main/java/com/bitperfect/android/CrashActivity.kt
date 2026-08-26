package com.bitperfect.android

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * CrashActivity - displays the last crash report on screen.
 *
 * This exists because logcat is not readable on some Android 16 devices and
 * app-private external directories are not always visible to other tools.
 * Showing the trace on screen is the one channel that always works.
 *
 * Built with plain views (no Compose, no AppCompat, no resources) so that it
 * cannot itself fail for the same reason the app just did.
 */
class CrashActivity : Activity() {

    companion object {
        const val EXTRA_REPORT = "com.bitperfect.android.extra.CRASH_REPORT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = intent?.getStringExtra(EXTRA_REPORT)
            ?: BitPerfectApp.lastCrashReport.takeIf { it.isNotEmpty() }
            ?: "No crash report available."

        val padding = dp(16)

        val header = TextView(this).apply {
            text = "BitPerfect crashed"
            setTextColor(Color.parseColor("#FFD4A853"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(Typeface.DEFAULT_BOLD)
        }

        val hint = TextView(this).apply {
            text = "Report saved to Downloads/bitperfect_crash.txt"
            setTextColor(Color.parseColor("#FFAAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(12))
        }

        val body = TextView(this).apply {
            text = report
            setTextColor(Color.parseColor("#FFE8E8E8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(header)
            addView(hint)
            addView(body)
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FF0A0A0A"))
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(scroll)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
