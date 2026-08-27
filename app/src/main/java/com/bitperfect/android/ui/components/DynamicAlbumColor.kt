package com.bitperfect.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlin.math.max

/**
 * Derives a "bright and punching" accent colour from album art.
 *
 * The colour drives the mini player background and the player-screen accents, and
 * it animates when the track changes so the swap is a wash rather than a cut.
 *
 * Design decisions:
 *  - Palette's *vibrant* swatches are preferred over dominant, because the
 *    dominant colour of a cover is often a dark or muted background, which is the
 *    opposite of "punching".
 *  - The chosen colour is then saturated and floored to a minimum brightness, so
 *    even a muted cover yields something with life. A cover that genuinely has no
 *    vibrant colour falls back to the supplied default (the theme primary).
 *  - Extraction runs off the main thread inside produceState, keyed on the URI,
 *    so it re-runs only when the art changes and never blocks composition.
 */
@Composable
fun rememberDynamicAlbumColor(
    artworkUri: String?,
    fallback: Color,
    animationMillis: Int = 600
): State<Color> {
    val context = LocalContext.current

    // Extraction is off the main thread and re-runs only when the art or the
    // fallback changes. A plain state plus LaunchedEffect rather than
    // produceState, which a lint heuristic mis-flags here.
    var target by remember { mutableStateOf(fallback) }
    LaunchedEffect(artworkUri, fallback) {
        target = if (artworkUri.isNullOrBlank()) {
            fallback
        } else {
            extractAccent(context, artworkUri, fallback)
        }
    }

    // animateColorAsState gives the cross-track transition for free.
    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = animationMillis),
        label = "albumAccent"
    )
}

/**
 * A small palette derived from the art: an accent plus foreground colours that
 * are guaranteed legible on it.
 */
data class AlbumColorScheme(
    val accent: Color,
    val onAccent: Color
)

@Composable
fun rememberAlbumColorScheme(
    artworkUri: String?,
    fallbackAccent: Color,
    animationMillis: Int = 600
): AlbumColorScheme {
    val accent by rememberDynamicAlbumColor(artworkUri, fallbackAccent, animationMillis)
    // Black or white on the accent, whichever contrasts more. Cheaper and more
    // reliable than asking Palette for a body-text colour, which is often null.
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    return AlbumColorScheme(accent = accent, onAccent = onAccent)
}

private suspend fun extractAccent(
    context: android.content.Context,
    artworkUri: String,
    fallback: Color
): Color {
    return try {
        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(artworkUri)
            .allowHardware(false) // Palette needs to read pixels back.
            .size(128)            // Downscale: a thumbnail is plenty for a colour.
            .build()

        val result = loader.execute(request)
        if (result !is SuccessResult) return fallback

        val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: return fallback

        val palette = Palette.from(bitmap).maximumColorCount(16).generate()

        // Preference order from most to least punchy; the muted variants are a
        // last resort before the theme fallback.
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.dominantSwatch
            ?: return fallback

        punchUp(Color(swatch.rgb))
    } catch (error: Exception) {
        fallback
    }
}

/**
 * Push a colour toward vivid: raise saturation and floor its brightness, so a
 * washed-out swatch still reads as an accent rather than grey.
 */
private fun punchUp(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] = max(hsv[1], 0.55f)        // saturation floor
    hsv[2] = max(hsv[2], 0.60f).coerceAtMost(0.95f) // brightness band
    return Color(android.graphics.Color.HSVToColor(hsv))
}
