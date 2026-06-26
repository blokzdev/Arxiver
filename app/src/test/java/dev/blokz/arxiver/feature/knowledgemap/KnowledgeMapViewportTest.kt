package dev.blokz.arxiver.feature.knowledgemap

import dev.blokz.arxiver.core.search.LodTier
import dev.blokz.arxiver.core.search.SceneBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure projection math for the PA.5d canvas — verified off-device so the fiddly screen↔layout maths can't regress. */
class KnowledgeMapViewportTest {
    @Test
    fun `toLayout exactly inverts toScreen`() {
        val vp = computeViewport(SceneBounds(0.0, 0.0, 100.0, 80.0), 1000f, 800f, 2f, 30f, -20f)
        val sx = vp.toScreenX(42.0)
        val sy = vp.toScreenY(17.0)
        assertEquals(42.0, vp.toLayoutX(sx), 1e-2)
        assertEquals(17.0, vp.toLayoutY(sy), 1e-2)
    }

    @Test
    fun `the bounds centre lands on the canvas centre plus pan offset`() {
        val vp = computeViewport(SceneBounds(0.0, 0.0, 100.0, 100.0), 1000f, 1000f, 1f, 7f, -3f)
        assertEquals(507f, vp.toScreenX(50.0), 1e-2f)
        assertEquals(497f, vp.toScreenY(50.0), 1e-2f)
    }

    @Test
    fun `a degenerate zero-extent scene still yields a finite transform`() {
        val vp = computeViewport(SceneBounds(5.0, 5.0, 5.0, 5.0), 1000f, 1000f, 1f, 0f, 0f)
        assertTrue(vp.toScreenX(5.0).isFinite() && vp.toScreenY(5.0).isFinite())
    }

    @Test
    fun `tierForZoom crosses overview to mid to detail at the thresholds`() {
        assertEquals(LodTier.OVERVIEW, tierForZoom(1f))
        assertEquals(LodTier.OVERVIEW, tierForZoom(1.59f))
        assertEquals(LodTier.MID, tierForZoom(2f))
        assertEquals(LodTier.MID, tierForZoom(3.1f))
        assertEquals(LodTier.DETAIL, tierForZoom(5f))
    }

    @Test
    fun `applyTransform keeps the pinch focal point fixed (focal zoom, not bounds-centre zoom)`() {
        val bounds = SceneBounds(0.0, 0.0, 100.0, 80.0)
        val w = 1000f
        val h = 800f
        val fingerX = 700f
        val fingerY = 300f // deliberately off the canvas centre
        val before = computeViewport(bounds, w, h, 1f, 0f, 0f)
        val layoutX = before.toLayoutX(fingerX)
        val layoutY = before.toLayoutY(fingerY)
        val r = applyTransform(PanZoom(1f, 0f, 0f), fingerX, fingerY, 0f, 0f, 2f, w / 2, h / 2)
        val after = computeViewport(bounds, w, h, r.userScale, r.offsetX, r.offsetY)
        assertEquals(2f, r.userScale, 1e-4f)
        // The layout point under the fingers must still project to the same screen point after zooming.
        assertEquals(fingerX, after.toScreenX(layoutX), 0.5f)
        assertEquals(fingerY, after.toScreenY(layoutY), 0.5f)
    }

    @Test
    fun `applyTransform with unit zoom is a pure pan`() {
        val r = applyTransform(PanZoom(2f, 10f, -5f), 300f, 400f, 25f, -15f, 1f, 500f, 500f)
        assertEquals(2f, r.userScale, 1e-4f)
        assertEquals(35f, r.offsetX, 1e-3f)
        assertEquals(-20f, r.offsetY, 1e-3f)
    }

    @Test
    fun `focusOn centres a layout point on the canvas at the target scale`() {
        val bounds = SceneBounds(0.0, 0.0, 200.0, 200.0)
        val w = 1000f
        val h = 1000f
        val vp = computeViewport(bounds, w, h, 1f, 0f, 0f)
        val r = focusOn(30.0, 170.0, CLUSTER_DRILL_ZOOM, vp)
        val after = computeViewport(bounds, w, h, r.userScale, r.offsetX, r.offsetY)
        assertEquals(CLUSTER_DRILL_ZOOM, r.userScale, 1e-4f)
        assertEquals(500f, after.toScreenX(30.0), 0.5f)
        assertEquals(500f, after.toScreenY(170.0), 0.5f)
    }
}
