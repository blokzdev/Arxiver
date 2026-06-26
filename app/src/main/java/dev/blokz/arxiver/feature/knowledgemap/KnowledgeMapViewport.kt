package dev.blokz.arxiver.feature.knowledgemap

import dev.blokz.arxiver.core.search.LodTier
import dev.blokz.arxiver.core.search.SceneBounds

/**
 * The pure projection math for the knowledge-map canvas (P-Atlas PA.5d), kept free of Compose so it
 * is unit-testable off-device. Layout space (the [dev.blokz.arxiver.core.search.GraphScene]
 * coordinate system) maps to screen pixels by: centre the scene's bounding-box centre on the canvas
 * centre, scale by `contentScale` (fit-to-canvas) × `userScale` (pinch), then translate by the user's
 * pan [offsetX]/[offsetY]. [toLayout] is the exact inverse, used to turn a tap back into a hit-test.
 */
data class MapViewport(
    val contentScale: Float,
    val userScale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val canvasCenterX: Float,
    val canvasCenterY: Float,
    val boundsCenterX: Float,
    val boundsCenterY: Float,
) {
    private val k: Float get() = contentScale * userScale

    fun toScreenX(x: Double): Float = (x.toFloat() - boundsCenterX) * k + canvasCenterX + offsetX

    fun toScreenY(y: Double): Float = (y.toFloat() - boundsCenterY) * k + canvasCenterY + offsetY

    fun toLayoutX(screenX: Float): Double = ((screenX - canvasCenterX - offsetX) / k + boundsCenterX).toDouble()

    fun toLayoutY(screenY: Float): Double = ((screenY - canvasCenterY - offsetY) / k + boundsCenterY).toDouble()

    /** A layout-space distance (e.g. a tap radius) scaled to the current zoom — for hit-test slop. */
    fun toLayoutDistance(screenDistance: Float): Double = (screenDistance / k).toDouble()
}

/** Zoom thresholds → level-of-detail tier. Open at overview (whole graph), drill in to detail. */
fun tierForZoom(userScale: Float): LodTier =
    when {
        userScale < OVERVIEW_MAX_ZOOM -> LodTier.OVERVIEW
        userScale < MID_MAX_ZOOM -> LodTier.MID
        else -> LodTier.DETAIL
    }

/** Below this pinch-zoom the map shows clustered super-nodes; the canvas opens here (userScale = 1). */
const val OVERVIEW_MAX_ZOOM = 1.6f
private const val MID_MAX_ZOOM = 3.2f

/** Pinch-zoom clamp so the user can't lose the graph off-screen. */
const val MIN_USER_ZOOM = 0.4f
const val MAX_USER_ZOOM = 8f

/** Zoom level a cluster/holding super-node tap drills into (past [OVERVIEW_MAX_ZOOM] so members appear + are tappable). */
const val CLUSTER_DRILL_ZOOM = 2.6f

/** The pannable canvas's gesture state — pinch zoom + pan offset. Plain floats so it's `rememberSaveable`-friendly. */
data class PanZoom(
    val userScale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/**
 * Apply one transform-gesture step **anchored at the pinch [centroid]** (plus a [panX]/[panY] drag),
 * so the layout point under the fingers stays under the fingers as the user zooms — the standard
 * focal-zoom formulation. The `boundsCenter`/`contentScale` terms cancel, so this needs only the
 * canvas centre. Pure → unit-testable.
 */
fun applyTransform(
    current: PanZoom,
    centroidX: Float,
    centroidY: Float,
    panX: Float,
    panY: Float,
    zoom: Float,
    canvasCenterX: Float,
    canvasCenterY: Float,
): PanZoom {
    val newScale = (current.userScale * zoom).coerceIn(MIN_USER_ZOOM, MAX_USER_ZOOM)
    val factor = newScale / current.userScale
    return PanZoom(
        userScale = newScale,
        offsetX = centroidX + panX - canvasCenterX - (centroidX - canvasCenterX - current.offsetX) * factor,
        offsetY = centroidY + panY - canvasCenterY - (centroidY - canvasCenterY - current.offsetY) * factor,
    )
}

/** Centre a layout point ([layoutX]/[layoutY]) on the canvas at [targetScale] — used to drill a cluster tap. */
fun focusOn(
    layoutX: Double,
    layoutY: Double,
    targetScale: Float,
    vp: MapViewport,
): PanZoom {
    val k = vp.contentScale * targetScale
    return PanZoom(
        userScale = targetScale,
        offsetX = -(layoutX.toFloat() - vp.boundsCenterX) * k,
        offsetY = -(layoutY.toFloat() - vp.boundsCenterY) * k,
    )
}

/** Fraction of the canvas the fitted scene occupies at userScale = 1 (leaves a margin). */
private const val FIT_MARGIN = 0.85f

/**
 * Fit a scene's [bounds] into a [canvasW]×[canvasH] canvas at the given pinch [userScale]/[offset].
 * Pure (no Compose) so the fit math is unit-testable. Degenerate (zero-extent) bounds fall back to a
 * 1-unit extent so a single-node / collinear scene still renders.
 */
fun computeViewport(
    bounds: SceneBounds,
    canvasW: Float,
    canvasH: Float,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
): MapViewport {
    val bw = bounds.width.toFloat().coerceAtLeast(1f)
    val bh = bounds.height.toFloat().coerceAtLeast(1f)
    val contentScale = minOf(canvasW * FIT_MARGIN / bw, canvasH * FIT_MARGIN / bh)
    return MapViewport(
        contentScale = contentScale,
        userScale = userScale,
        offsetX = offsetX,
        offsetY = offsetY,
        canvasCenterX = canvasW / 2f,
        canvasCenterY = canvasH / 2f,
        boundsCenterX = ((bounds.minX + bounds.maxX) / 2.0).toFloat(),
        boundsCenterY = ((bounds.minY + bounds.maxY) / 2.0).toFloat(),
    )
}
