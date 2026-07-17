package dev.blokz.arxiver.feature.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRZ.1 — the pure tile-planning geometry. The two load-bearing properties (full-item-rect mapping anchor and
 * the coverage-only pad clamp) are regression pins for the misregistration class the personal validation caught
 * in the planning workflow's synthesis (mapping into the padded rect drifts tiles by ~1dp·zoom on screen).
 */
class PdfTileMathTest {
    private val viewport = IntSize(1000, 2000)
    private val eps = 0.01f

    /** One page item exactly covering the viewport at 1×. */
    private fun fullPage(index: Int = 0) = PageRect(pageIndex = index, itemOffsetPx = 0, itemSizePx = 2000)

    private val bigBudget = Long.MAX_VALUE / 8

    @Test
    fun `not zoomed plans nothing`() {
        val specs =
            planTiles(1f, Offset.Zero, viewport, 1000, listOf(fullPage()), padPx = 4, tileBudgetBytes = bigBudget)
        assertTrue(specs.isEmpty())
    }

    @Test
    fun `a centered 2x zoom on a full-viewport page plans one screen-resolution tile`() {
        val specs =
            planTiles(2f, Offset.Zero, viewport, 1000, listOf(fullPage()), padPx = 0, tileBudgetBytes = bigBudget)
        assertEquals(1, specs.size)
        val spec = specs.single()
        // Visible preimage at 2× centered: [250..750] × [500..1500].
        assertEquals(250f, spec.regionLeft, eps)
        assertEquals(500f, spec.regionTop, eps)
        assertEquals(500f, spec.regionWidth, eps)
        assertEquals(1000f, spec.regionHeight, eps)
        // Screen-resolution: the 500-base-px-wide region fills the 1000px viewport → tile is 1000×2000 px.
        assertEquals(1000, spec.tileWpx)
        assertEquals(2000, spec.tileHpx)
        assertEquals(2000f, spec.zoomedPageWidthPx, eps)
        assertEquals(500f, spec.tileLeftPx, eps)
        assertEquals(1000f, spec.tileTopPx, eps)
    }

    @Test
    fun `content mapping anchors to the full item rect, not the padded rect`() {
        // Page item at offset 100; pad 4. The drawn page rect ≡ the item rect (FillWidth + centre re-expand
        // over the padding), so a region starting at base-y 600 maps to page-plane y (600−100)·k — NOT (600−104)·k.
        val page = PageRect(pageIndex = 0, itemOffsetPx = 100, itemSizePx = 2000)
        val specs =
            planTiles(2f, Offset.Zero, viewport, 1000, listOf(page), padPx = 4, tileBudgetBytes = bigBudget)
        val spec = specs.single()
        // k = zoomedPageWidth/W = 2. Visible top = 500 < coverTop? coverTop = 104 < 500 → region top = 500.
        assertEquals(500f, spec.regionTop, eps)
        assertEquals((500f - 100f) * 2f, spec.tileTopPx, eps)
    }

    @Test
    fun `tiles never cover the inter-page seam`() {
        // Page starts at base-y 600 with pad 4; the visible rect reaches above it. Coverage must start at
        // itemOffset+pad (the seam stays base-rendered), while the MAPPING still anchors at itemOffset.
        val page = PageRect(pageIndex = 1, itemOffsetPx = 600, itemSizePx = 1400)
        val specs =
            planTiles(2f, Offset.Zero, viewport, 1000, listOf(page), padPx = 4, tileBudgetBytes = bigBudget)
        val spec = specs.single()
        assertEquals(604f, spec.regionTop, eps) // coverTop = 600 + 4
        assertEquals((604f - 600f) * 2f, spec.tileTopPx, eps) // mapped from the FULL item rect top (600)
    }

    @Test
    fun `a page boundary splits the visible region into one tile per page`() {
        val pageA = PageRect(pageIndex = 3, itemOffsetPx = -500, itemSizePx = 1500)
        val pageB = PageRect(pageIndex = 4, itemOffsetPx = 1000, itemSizePx = 1500)
        val specs =
            planTiles(2f, Offset.Zero, viewport, 1000, listOf(pageA, pageB), padPx = 0, tileBudgetBytes = bigBudget)
        assertEquals(2, specs.size)
        val (a, b) = specs
        assertEquals(3, a.pageIndex)
        assertEquals(4, b.pageIndex)
        // Visible band is [500..1500]; page A covers [500..1000), page B [1000..1500] — a clean partition.
        assertEquals(500f, a.regionTop, eps)
        assertEquals(500f, a.regionHeight, eps)
        assertEquals(1000f, b.regionTop, eps)
        assertEquals(500f, b.regionHeight, eps)
    }

    @Test
    fun `a zero or sub-pixel overlap is dropped, not rendered`() {
        // Zero overlap: the page starts exactly where the visible band ends → nothing planned.
        val touching = PageRect(pageIndex = 2, itemOffsetPx = 1500, itemSizePx = 1500)
        val none =
            planTiles(2f, Offset.Zero, viewport, 1000, listOf(touching), padPx = 0, tileBudgetBytes = bigBudget)
        assertTrue(none.isEmpty())

        // Sub-pixel overlap: at scale 2.5 with a fractional pan, the visible bottom lands 0.1 base px into the
        // next page (0.25 tile px → rounds to 0). createBitmap(w, 0) would throw — the spec must be dropped.
        val tall = IntSize(1000, 2001)
        // visible.bottom = cy + (H − t − cy)/s = 1000.5 + (1000.5 − t)/2.5; t = −248.5 → bottom = 1500.1.
        val sliver = PageRect(pageIndex = 3, itemOffsetPx = 1500, itemSizePx = 1500)
        val specs =
            planTiles(2.5f, Offset(0f, -248.5f), tall, 1000, listOf(sliver), padPx = 0, tileBudgetBytes = bigBudget)
        assertTrue(specs.isEmpty())
    }

    @Test
    fun `over budget degrades resolution by the square root and leaves draw geometry unchanged`() {
        val full =
            planTiles(2f, Offset.Zero, viewport, 1000, listOf(fullPage()), padPx = 0, tileBudgetBytes = bigBudget)
                .single()
        val neededBytes = full.tileWpx.toLong() * full.tileHpx * 4L
        val budget = neededBytes / 4 // force f = 0.5
        val degraded =
            planTiles(2f, Offset.Zero, viewport, 1000, listOf(fullPage()), padPx = 0, tileBudgetBytes = budget)
                .single()
        assertEquals((full.tileWpx * 0.5f).roundToInt(), degraded.tileWpx)
        assertEquals((full.tileHpx * 0.5f).roundToInt(), degraded.tileHpx)
        assertEquals(full.zoomedPageWidthPx * 0.5f, degraded.zoomedPageWidthPx, eps)
        assertEquals(full.tileLeftPx * 0.5f, degraded.tileLeftPx, eps)
        // Draw geometry byte-identical — the tile just blits softer at the same place.
        assertEquals(full.regionLeft, degraded.regionLeft, eps)
        assertEquals(full.regionTop, degraded.regionTop, eps)
        assertEquals(full.regionWidth, degraded.regionWidth, eps)
        assertEquals(full.regionHeight, degraded.regionHeight, eps)
        val degradedBytes = degraded.tileWpx.toLong() * degraded.tileHpx * 4L
        assertTrue(degradedBytes <= budget + 8L) // rounding slack of a few pixels
    }

    @Test
    fun `tiles are never softer than a supersampled base`() {
        // 600px screen, base supersampled at 720 (the pdfTargetWidth floor). At a mild 1.1× zoom, W·Z = 660
        // would be SOFTER than the base — density must floor at targetWidth.
        val smallViewport = IntSize(600, 1200)
        val page = PageRect(0, 0, 1200)
        val specs =
            planTiles(1.1f, Offset.Zero, smallViewport, 720, listOf(page), padPx = 0, tileBudgetBytes = bigBudget)
        assertEquals(720f, specs.single().zoomedPageWidthPx, eps)
    }

    @Test
    fun `at deep zoom density follows the screen`() {
        val specs =
            planTiles(4f, Offset.Zero, viewport, 1000, listOf(fullPage()), padPx = 0, tileBudgetBytes = bigBudget)
        // W·Z = 4000 > targetWidth 1000 → screen-driven density (max(W·Z, targetWidth) = W·Z, NOT (max of
        // W,targetWidth)·Z which would over-render on supersampled-base devices).
        assertEquals(4000f, specs.single().zoomedPageWidthPx, eps)
    }

    @Test
    fun `a full generation never exceeds one viewport of pixels`() {
        // Tiles partition (a subset of) the viewport preimage — the inherent memory bound at any zoom.
        for (scale in listOf(1.5f, 2f, 3f, 4f)) {
            val offset = PdfZoom.clampOffset(Offset(1e9f, -1e9f), scale, viewport)
            val pages = listOf(PageRect(0, -1500, 1500), PageRect(1, 0, 1500), PageRect(2, 1500, 1500))
            val specs = planTiles(scale, offset, viewport, 1000, pages, padPx = 1, tileBudgetBytes = bigBudget)
            val totalPx = specs.sumOf { it.tileWpx.toLong() * it.tileHpx }
            val viewportPx = viewport.width.toLong() * viewport.height
            assertTrue(totalPx <= viewportPx + specs.size * 64L) // per-tile rounding slack
        }
    }
}
