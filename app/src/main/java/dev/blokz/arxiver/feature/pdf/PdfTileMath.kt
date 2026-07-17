package dev.blokz.arxiver.feature.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One visible LazyColumn page item, in the list's 1×-base viewport coordinates. */
internal data class PageRect(
    val pageIndex: Int,
    val itemOffsetPx: Int,
    val itemSizePx: Int,
)

/**
 * One sharp tile: the render recipe (`zoomedPageWidthPx`/`tileLeftPx`/`tileTopPx`/`tileWpx`/`tileHpx`, all in
 * the zoomed-page pixel plane — fed to `renderRegion`'s Matrix) plus the draw anchor (`region*`, in 1×-base
 * viewport coordinates — projected through [PdfZoom.project] at draw time so the tile tracks the live gesture).
 */
internal data class TileSpec(
    val pageIndex: Int,
    val zoomedPageWidthPx: Float,
    val tileLeftPx: Float,
    val tileTopPx: Float,
    val tileWpx: Int,
    val tileHpx: Int,
    val regionLeft: Float,
    val regionTop: Float,
    val regionWidth: Float,
    val regionHeight: Float,
)

/** The hard byte cap for one tile generation (~1/12 heap; the realistic worst case is one viewport ≈ 4–15MB). */
internal fun pdfTileBudgetBytes(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Long = maxHeapBytes / 12

/**
 * Plans the sharp tiles for the current settle — the pure tile-planning geometry of the crisp-zoom overlay
 * (P-ReaderZoom PRZ.1), a Compose-state-free peer of [PdfZoom], fully unit-testable without a device.
 *
 * When the reader is zoomed, the base pages are just GPU-upscaled (soft). On gesture settle the overlay
 * re-renders the VISIBLE region at zoom resolution: this intersects the viewport's preimage
 * ([PdfZoom.visibleContentRect]) with each visible page item and emits one [TileSpec] per overlap — a render
 * recipe in the zoomed-page pixel plane plus a draw anchor in 1×-base viewport coordinates. Empty when not
 * zoomed. [padPx] is the page item's vertical content padding (the 1dp seam); [targetWidth] the base raster
 * width ([pdfTargetWidth]).
 *
 * Two properties are load-bearing (both unit-test-pinned):
 *  - **Mapping anchors to the FULL item rect.** The page Image sits in a 1dp-vertically-padded box, but
 *    `ContentScale.FillWidth` + centre alignment re-expand the drawn page over the whole item rect (the 1dp
 *    strips are merely clipped). Mapping into the padded rect instead would misregister tiles by ~1dp·zoom
 *    (≈14px at 4×). The pad is only a COVERAGE clamp: tiles never paint over the inter-page seam.
 *  - **Memory is bounded by construction.** Tiles partition (a subset of) the viewport, so a full generation
 *    costs at most `viewportW·viewportH·4` bytes at ANY zoom — plus [pdfTileBudgetBytes] as a hard cap that
 *    degrades render resolution (√-scaled per dimension, draw geometry unchanged) rather than allocating more.
 */
internal fun planTiles(
    scale: Float,
    offset: Offset,
    viewport: IntSize,
    targetWidth: Int,
    visiblePages: List<PageRect>,
    padPx: Int,
    tileBudgetBytes: Long,
): List<TileSpec> {
    if (!PdfZoom.isZoomed(scale)) return emptyList()
    if (viewport.width <= 0 || viewport.height <= 0) return emptyList()
    val visible = PdfZoom.visibleContentRect(scale, offset, viewport)
    val width = viewport.width.toFloat()
    // Tile density: never softer than the (possibly supersampled) base render, never denser than the screen
    // shows — max(W·Z, targetWidth), NOT max(W, targetWidth)·Z (which would waste memory on small screens).
    val zoomedPageWidthPx = maxOf(width * scale, targetWidth.toFloat())
    val k = zoomedPageWidthPx / width // base-px → zoomed-page-px
    val specs = mutableListOf<TileSpec>()
    for (page in visiblePages) {
        // Content mapping anchors to the FULL item rect (see class doc); the pad only clamps coverage.
        val mapTop = page.itemOffsetPx.toFloat()
        val coverTop = mapTop + padPx
        val coverBottom = mapTop + page.itemSizePx - padPx
        val left = maxOf(visible.left, 0f)
        val right = minOf(visible.right, width)
        val top = maxOf(visible.top, coverTop)
        val bottom = minOf(visible.bottom, coverBottom)
        if (right <= left || bottom <= top) continue
        val tileW = ((right - left) * k).roundToInt()
        val tileH = ((bottom - top) * k).roundToInt()
        // A sliver that rounds below one pixel would make createBitmap throw — skip it.
        if (tileW < 1 || tileH < 1) continue
        specs +=
            TileSpec(
                pageIndex = page.pageIndex,
                zoomedPageWidthPx = zoomedPageWidthPx,
                tileLeftPx = left * k,
                tileTopPx = (top - mapTop) * k,
                tileWpx = tileW,
                tileHpx = tileH,
                regionLeft = left,
                regionTop = top,
                regionWidth = right - left,
                regionHeight = bottom - top,
            )
    }
    // Over budget → degrade render resolution (√-scale each dimension so bytes scale linearly with the ratio);
    // the draw geometry (region*) is unchanged, so the tile just blits slightly softer — never allocate-and-OOM.
    val needed = specs.sumOf { it.tileWpx.toLong() * it.tileHpx * 4L }
    if (needed <= tileBudgetBytes || needed == 0L) return specs
    val f = sqrt(tileBudgetBytes.toDouble() / needed).toFloat()
    return specs.mapNotNull { spec ->
        val degradedW = (spec.tileWpx * f).roundToInt()
        val degradedH = (spec.tileHpx * f).roundToInt()
        if (degradedW < 1 || degradedH < 1) {
            null
        } else {
            spec.copy(
                zoomedPageWidthPx = spec.zoomedPageWidthPx * f,
                tileLeftPx = spec.tileLeftPx * f,
                tileTopPx = spec.tileTopPx * f,
                tileWpx = degradedW,
                tileHpx = degradedH,
            )
        }
    }
}
