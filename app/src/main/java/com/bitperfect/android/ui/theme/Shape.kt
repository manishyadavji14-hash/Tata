package com.bitperfect.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * BitPerfect Shape definitions.
 *
 * Rounded corners at various scales for cards, buttons, dialogs,
 * and other UI elements. Slightly larger corner radii for a modern,
 * premium feel that matches the audiophile aesthetic.
 */
val BitPerfectShapes = Shapes(
    // Small components: chips, small buttons
    extraSmall = RoundedCornerShape(4.dp),

    // Small: text fields, small cards
    small = RoundedCornerShape(8.dp),

    // Medium: cards, dialogs
    medium = RoundedCornerShape(12.dp),

    // Large: bottom sheets, large cards
    large = RoundedCornerShape(16.dp),

    // Extra large: full-screen dialogs, navigation drawers
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * Custom shape values for specific BitPerfect components.
 */
object BitPerfectShapeTokens {
    val AlbumArtCorner = RoundedCornerShape(16.dp)
    val PlayerCardCorner = RoundedCornerShape(24.dp)
    val FormatBadgeCorner = RoundedCornerShape(8.dp)
    val ButtonCorner = RoundedCornerShape(12.dp)
    val BottomSheetCorner = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val DialogCorner = RoundedCornerShape(20.dp)
    val SeekBarThumb = RoundedCornerShape(50)
    val NavigationBarItem = RoundedCornerShape(12.dp)
}
