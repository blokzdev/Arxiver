package dev.blokz.arxiver.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Gently rounder than M3 defaults (SPEC-UI §1) — modern without going bubbly.
 * extraSmall carries chips/badges, small carries skeleton blocks and inline
 * surfaces, medium carries cards, extraLarge carries sheets.
 */
val ArxiverShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
