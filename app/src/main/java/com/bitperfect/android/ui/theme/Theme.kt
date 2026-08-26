package com.bitperfect.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * BitPerfect Material 3 Theme.
 *
 * Features:
 * - Dynamic color support on Android 12+ (Material You)
 * - Custom audiophile color palette as fallback
 * - Dark theme optimized for deep blacks (AMOLED-friendly)
 * - Light theme with warm tones
 * - Proper system bar coloring
 * - Custom typography and shape scales
 */

private val DarkColorScheme = darkColorScheme(
    primary = GoldPrimary,
    onPrimary = DarkBackground,
    primaryContainer = GoldPrimaryDark,
    onPrimaryContainer = GoldPrimaryLight,
    secondary = BluePrimary,
    onSecondary = DarkBackground,
    secondaryContainer = BluePrimaryDark,
    onSecondaryContainer = BluePrimaryLight,
    tertiary = CopperTertiary,
    onTertiary = DarkBackground,
    tertiaryContainer = CopperTertiaryDark,
    onTertiaryContainer = CopperTertiaryLight,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = ErrorRed,
    onError = OnError,
    errorContainer = ErrorRedDark,
    onErrorContainer = OnError,
    outline = DarkOutline,
    inverseSurface = LightSurface,
    inverseOnSurface = LightOnSurface,
    inversePrimary = GoldPrimaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = GoldPrimaryDark,
    onPrimary = LightBackground,
    primaryContainer = GoldPrimaryLight,
    onPrimaryContainer = GoldPrimaryDark,
    secondary = BluePrimaryDark,
    onSecondary = LightBackground,
    secondaryContainer = BluePrimaryLight,
    onSecondaryContainer = BluePrimaryDark,
    tertiary = CopperTertiaryDark,
    onTertiary = LightBackground,
    tertiaryContainer = CopperTertiaryLight,
    onTertiaryContainer = CopperTertiaryDark,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = ErrorRed,
    onError = OnError,
    errorContainer = ErrorRedDark,
    onErrorContainer = OnError,
    outline = LightOutline,
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkOnSurface,
    inversePrimary = GoldPrimary
)

/**
 * Theme mode options for user settings.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun BitPerfectTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        // Use dynamic color on Android 12+ if enabled
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Update system bar colors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BitPerfectTypography,
        shapes = BitPerfectShapes,
        content = content
    )
}
