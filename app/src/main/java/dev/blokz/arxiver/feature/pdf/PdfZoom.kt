package dev.blokz.arxiver.feature.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize

/**
 * Pure focal-zoom geometry for the PDF reader's pinch / double-tap zoom (P-Reader2 PR.UX.3).
 *
 * Kept entirely Compose-state-free so the math can be unit-tested without a device — the gesture layer in
 * [PdfViewerScreen] feeds raw `(centroid, pan, zoom)` and writes the result to a `graphicsLayer` that uses the
 * DEFAULT centre transform origin, which is exactly the pivot this derivation assumes. The transform is
 * draw-only (post-layout), so the `LazyColumn`'s own scroll offsets keep their base-layout meaning and P-Read
 * reading positions are never disturbed by zoom.
 */
internal object PdfZoom {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f

    /** Double-tap toggles between fit (1×) and this comfortable read-in level. */
    const val DOUBLE_TAP_SCALE = 2.5f

    /** A hair above 1 so float dust doesn't read as "zoomed" (re-enables list scroll / hides pan). */
    const val ZOOM_EPSILON = 1.001f

    fun coerceScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    fun isZoomed(scale: Float): Boolean = scale > ZOOM_EPSILON

    /**
     * The pan translation (px) that keeps the content point under [centroid] fixed while the scale goes
     * [oldScale] → [newScale] and the finger(s) additionally pan by [pan]. For a centre-origin layer of
     * [size], a content point `x` renders at `W/2 + (x − W/2)·s + tx`; pinning the centroid across the scale
     * change solves to `tx' = k·tx + (centroid − centre)·(1 − k) + pan`, with `k = newScale/oldScale`. The
     * result is [clampOffset]ed so the scaled plane can never reveal a gap at the edges.
     */
    fun focalOffset(
        current: Offset,
        oldScale: Float,
        newScale: Float,
        centroid: Offset,
        pan: Offset,
        size: IntSize,
    ): Offset {
        val k = if (oldScale <= 0f) 1f else newScale / oldScale
        val centreX = size.width / 2f
        val centreY = size.height / 2f
        val x = k * current.x + (centroid.x - centreX) * (1f - k) + pan.x
        val y = k * current.y + (centroid.y - centreY) * (1f - k) + pan.y
        return clampOffset(Offset(x, y), newScale, size)
    }

    /** Clamp the translation to `|tx| ≤ (scale−1)·W/2`, `|ty| ≤ (scale−1)·H/2` — at 1× that pins offset to zero. */
    fun clampOffset(
        offset: Offset,
        scale: Float,
        size: IntSize,
    ): Offset {
        val maxX = ((scale - 1f) * size.width / 2f).coerceAtLeast(0f)
        val maxY = ((scale - 1f) * size.height / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    /**
     * Where a base-layout point renders on screen under the centre-origin draw transform (P-ReaderZoom): the
     * forward map `q = C + (p − C)·s + t` the `graphicsLayer` applies. The tile overlay projects each sharp
     * tile's base rect through this at the CURRENT scale/offset, so tiles stay registered mid-gesture.
     */
    fun project(
        point: Offset,
        scale: Float,
        offset: Offset,
        size: IntSize,
    ): Offset {
        val cx = size.width / 2f
        val cy = size.height / 2f
        return Offset(cx + (point.x - cx) * scale + offset.x, cy + (point.y - cy) * scale + offset.y)
    }

    /** Inverse of [project] — the base-layout point currently rendered at a given screen position. */
    fun unproject(
        point: Offset,
        scale: Float,
        offset: Offset,
        size: IntSize,
    ): Offset {
        val s = if (scale <= 0f) 1f else scale
        val cx = size.width / 2f
        val cy = size.height / 2f
        return Offset(cx + (point.x - offset.x - cx) / s, cy + (point.y - offset.y - cy) / s)
    }

    /**
     * The base-layout rect currently visible on screen — the viewport's preimage under the draw transform.
     * Under [clampOffset] this is always ⊆ the viewport itself, so the visible region only ever overlaps
     * COMPOSED LazyColumn items (the list lays out at 1×; a zoomed pan can never reveal an uncomposed page).
     */
    fun visibleContentRect(
        scale: Float,
        offset: Offset,
        size: IntSize,
    ): Rect {
        val topLeft = unproject(Offset.Zero, scale, offset, size)
        val bottomRight = unproject(Offset(size.width.toFloat(), size.height.toFloat()), scale, offset, size)
        return Rect(topLeft, bottomRight)
    }
}
