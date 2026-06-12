package dev.blokz.arxiver.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/**
 * Motion tokens (SPEC-UI §1): standard M3 transitions, no gratuitous
 * animation. Short = component state (icon swaps, checkmarks), Medium =
 * in-screen layout (expand/collapse, list moves), Long = navigation and
 * hero elements.
 */
object ArxiverMotion {
    const val DURATION_SHORT = 150
    const val DURATION_MEDIUM = 250
    const val DURATION_LONG = 350

    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val DecelerateEasing = CubicBezierEasing(0f, 0f, 0f, 1f)
    val AccelerateEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
}
