package com.bitperfect.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bitperfect.android.player.SpectrumAnalysis
import com.bitperfect.android.player.SpectrumTap
import kotlin.math.max

/**
 * The spectrum of what is playing.
 *
 * Driven from the frame clock rather than a timer, so it draws exactly once per frame
 * and stops entirely when the composable leaves the tree — which is also when the tap
 * is switched off, so no audio is copied for a picture nobody is looking at.
 *
 * The whole thing is state-free above the bar heights: no recomposition per frame,
 * only a redraw. Heights live in a plain array read inside the draw lambda, and the
 * frame loop nudges a counter to invalidate it.
 */
@Composable
fun SpectrumVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val bars = remember { FloatArray(SpectrumAnalysis.BAND_COUNT) }
    val window = remember { FloatArray(SpectrumAnalysis.FFT_SIZE) }
    val windowed = remember { FloatArray(SpectrumAnalysis.FFT_SIZE) }
    val magnitudes = remember { FloatArray(SpectrumAnalysis.FFT_SIZE / 2) }
    val hann = remember { SpectrumAnalysis.hannWindow(SpectrumAnalysis.FFT_SIZE) }

    // Bumped every frame purely to invalidate the draw. Cheaper than hoisting the
    // whole array into state, which would recompose instead of just redrawing.
    var frame by remember { mutableIntStateOf(0) }

    // The tap only copies audio while this is on screen.
    DisposableEffect(Unit) {
        SpectrumTap.setActive(true)
        onDispose {
            SpectrumTap.setActive(false)
            bars.fill(0f)
        }
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            // Fall to the floor rather than freezing mid-song when playback stops.
            while (bars.any { it > 0.001f }) {
                withFrameNanos {
                    for (i in bars.indices) bars[i] = bars[i] * IDLE_DECAY
                    frame++
                }
            }
            return@LaunchedEffect
        }

        var edges = IntArray(0)
        var edgesForRate = 0

        while (true) {
            withFrameNanos {
                val rate = SpectrumTap.sampleRate
                if (SpectrumTap.read(window) && rate > 0) {
                    if (rate != edgesForRate) {
                        edges = SpectrumAnalysis.bandEdges(
                            sampleRate = rate,
                            fftSize = SpectrumAnalysis.FFT_SIZE,
                            bandCount = SpectrumAnalysis.BAND_COUNT
                        )
                        edgesForRate = rate
                    }

                    for (i in window.indices) windowed[i] = window[i] * hann[i]
                    SpectrumAnalysis.magnitudes(windowed, magnitudes)

                    if (edges.size == SpectrumAnalysis.BAND_COUNT + 1) {
                        for (band in bars.indices) {
                            val level = SpectrumAnalysis.bandLevel(
                                magnitudes = magnitudes,
                                fromBin = edges[band],
                                toBin = edges[band + 1]
                            )
                            bars[band] = SpectrumAnalysis.decayed(bars[band], level, BAR_DECAY)
                        }
                    }
                } else {
                    for (i in bars.indices) bars[i] = bars[i] * IDLE_DECAY
                }
                frame++
            }
        }
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read so the draw is invalidated each frame.
            @Suppress("UNUSED_EXPRESSION") frame

            val count = bars.size
            if (count == 0 || size.width <= 0f || size.height <= 0f) return@Canvas

            val slot = size.width / count
            val barWidth = slot * BAR_WIDTH_SHARE
            val gap = (slot - barWidth) / 2f
            val radius = barWidth / 2f

            val brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.35f)),
                startY = 0f,
                endY = size.height
            )

            for (band in 0 until count) {
                // A floor so the spectrum reads as a row of bars at rest rather than
                // vanishing, which would look like a failure instead of silence.
                val height = max(bars[band] * size.height, radius * 2f)
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(x = band * slot + gap, y = size.height - height),
                    size = Size(width = barWidth, height = height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                )
            }
        }
    }
}

/** How much of each slot the bar fills; the rest is the gap. */
private const val BAR_WIDTH_SHARE = 0.62f

/** Fall per frame while playing. Rises are instant — see SpectrumAnalysis.decayed. */
private const val BAR_DECAY = 0.86f

/** Faster fall when there is nothing to show, so it settles promptly. */
private const val IDLE_DECAY = 0.80f

/** Height the player gives the strip. */
val SPECTRUM_HEIGHT = 40.dp
