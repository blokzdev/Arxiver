package dev.blokz.arxiver.feature.knowledgemap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.search.GraphScene
import dev.blokz.arxiver.core.search.GraphSceneBuilder
import dev.blokz.arxiver.core.search.RelationEdge
import dev.blokz.arxiver.core.search.RelationEdgeKind
import dev.blokz.arxiver.core.search.RelationGraph
import dev.blokz.arxiver.core.search.RelationNode
import dev.blokz.arxiver.core.search.RenderKind
import dev.blokz.arxiver.ui.theme.ArxiverTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeMapScreen(
    onBack: () -> Unit,
    onPaperClick: (String) -> Unit,
    viewModel: KnowledgeMapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var listMode by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.knowledge_map_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (state is KnowledgeMapUiState.Ready) {
                        val viewMapDesc = stringResource(R.string.knowledge_map_view_map)
                        val viewListDesc = stringResource(R.string.knowledge_map_view_list)
                        IconButton(
                            onClick = { listMode = !listMode },
                            // Announce the active view to TalkBack so the swap is perceivable without sight.
                            modifier =
                                Modifier.semantics {
                                    stateDescription = if (listMode) viewMapDesc else viewListDesc
                                },
                        ) {
                            if (listMode) {
                                Icon(Icons.Filled.Hub, viewMapDesc)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.List, viewListDesc)
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                KnowledgeMapUiState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is KnowledgeMapUiState.Empty ->
                    EmptyMap(
                        stringResource(s.message),
                        onRetry = viewModel::load,
                        modifier = Modifier.align(Alignment.Center),
                    )
                is KnowledgeMapUiState.Ready ->
                    if (listMode) {
                        KnowledgeMapList(s.scene, onPaperClick, Modifier.fillMaxSize())
                    } else {
                        KnowledgeMapCanvas(s.scene, onPaperClick, Modifier.fillMaxSize())
                    }
            }
        }
    }
}

@Composable
private fun EmptyMap(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.knowledge_map_retry))
        }
    }
}

/** Tap target padding in pixels — fingers are bigger than nodes. */
private const val TAP_SLOP_PX = 24f

/** Off-screen margin (px) for viewport culling. */
private const val CULL_MARGIN = 64f
private val DASH_INTERVALS = floatArrayOf(10f, 8f)

/** A pannable / zoomable Compose canvas that only *blits* the precomputed [scene] — it computes nothing. */
@Composable
fun KnowledgeMapCanvas(
    scene: GraphScene,
    onPaperTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paperColor = MaterialTheme.colorScheme.primary
    val clusterColor = MaterialTheme.colorScheme.tertiary
    val unconnectedColor = MaterialTheme.colorScheme.outline
    val edgeColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
    val textMeasurer = rememberTextMeasurer()
    val context = LocalContext.current
    val mapDescription = stringResource(R.string.cd_knowledge_map)
    val dashed = remember { PathEffect.dashPathEffect(DASH_INTERVALS) }

    // rememberSaveable so the user's pan/zoom survives rotation (the scene itself is held by the VM).
    var userScale by rememberSaveable { mutableStateOf(1f) }
    var offsetX by rememberSaveable { mutableStateOf(0f) }
    var offsetY by rememberSaveable { mutableStateOf(0f) }

    Canvas(
        modifier =
            modifier
                .semantics { contentDescription = mapDescription }
                .pointerInput(scene) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        // Focal zoom: keep the layout point under the fingers fixed while scaling.
                        val r =
                            applyTransform(
                                PanZoom(userScale, offsetX, offsetY),
                                centroid.x,
                                centroid.y,
                                pan.x,
                                pan.y,
                                zoom,
                                size.width / 2f,
                                size.height / 2f,
                            )
                        userScale = r.userScale
                        offsetX = r.offsetX
                        offsetY = r.offsetY
                    }
                }
                .pointerInput(scene) {
                    detectTapGestures { tap ->
                        val vp =
                            computeViewport(
                                scene.bounds,
                                size.width.toFloat(),
                                size.height.toFloat(),
                                userScale,
                                offsetX,
                                offsetY,
                            )
                        val hit =
                            scene.hitTest(
                                vp.toLayoutX(tap.x),
                                vp.toLayoutY(tap.y),
                                tierForZoom(userScale),
                                vp.toLayoutDistance(TAP_SLOP_PX),
                            )
                        when (hit?.kind) {
                            RenderKind.PAPER -> onPaperTap(scene.nodes[hit.refId].id)
                            // A super-node has no paper to open — drill into it so its members surface + become tappable.
                            RenderKind.CLUSTER, RenderKind.UNCONNECTED -> {
                                val r = focusOn(hit.x, hit.y, CLUSTER_DRILL_ZOOM, vp)
                                userScale = r.userScale
                                offsetX = r.offsetX
                                offsetY = r.offsetY
                            }
                            null -> Unit
                        }
                    }
                },
    ) {
        val vp = computeViewport(scene.bounds, size.width, size.height, userScale, offsetX, offsetY)
        val view = scene.visibleAt(tierForZoom(userScale))

        for (e in view.edges) {
            val ax = vp.toScreenX(e.fromX)
            val ay = vp.toScreenY(e.fromY)
            val bx = vp.toScreenX(e.toX)
            val by = vp.toScreenY(e.toY)
            // Cull edges whose whole span is off the same side of the canvas (mirrors the node cull).
            val edgeOffLeft = ax < -CULL_MARGIN && bx < -CULL_MARGIN
            val edgeOffRight = ax > size.width + CULL_MARGIN && bx > size.width + CULL_MARGIN
            val edgeOffTop = ay < -CULL_MARGIN && by < -CULL_MARGIN
            val edgeOffBottom = ay > size.height + CULL_MARGIN && by > size.height + CULL_MARGIN
            if (edgeOffLeft || edgeOffRight || edgeOffTop || edgeOffBottom) continue
            drawLine(
                color = edgeColor,
                start = Offset(ax, ay),
                end = Offset(bx, by),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = if (e.kind == RelationEdgeKind.SIMILAR) dashed else null,
            )
        }
        for (rn in view.nodes) {
            val px = vp.toScreenX(rn.x)
            val py = vp.toScreenY(rn.y)
            val nodeOffX = px < -CULL_MARGIN || px > size.width + CULL_MARGIN
            val nodeOffY = py < -CULL_MARGIN || py > size.height + CULL_MARGIN
            if (nodeOffX || nodeOffY) continue
            val radius = (rn.radius.toFloat() * vp.contentScale * userScale).coerceIn(4f, 48f)
            val color =
                when (rn.kind) {
                    RenderKind.PAPER -> paperColor
                    RenderKind.CLUSTER -> clusterColor
                    RenderKind.UNCONNECTED -> unconnectedColor
                }
            drawCircle(color, radius, Offset(px, py))
            val label =
                when (rn.kind) {
                    RenderKind.UNCONNECTED -> context.getString(R.string.knowledge_map_unconnected, rn.count)
                    else -> rn.label
                }
            if (label.isNotEmpty()) {
                val shown = if (label.length > 28) label.take(27) + "…" else label
                drawText(textMeasurer, shown, topLeft = Offset(px + radius + 4f, py - 7f), style = labelStyle)
            }
        }
    }
}

/**
 * The mandatory non-spatial fallback (P-Atlas PA.5d): the same [scene] grouped by cluster as a plain
 * list. This is the screen-reader-complete, low-vision, and low-end-device path — pure Compose, no
 * Canvas — guaranteeing every relation is reachable without gestures.
 */
@Composable
fun KnowledgeMapList(
    scene: GraphScene,
    onPaperClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        for (cluster in scene.clusters) {
            item(key = "cluster-${cluster.id}") {
                SectionHeader(stringResource(R.string.knowledge_map_cluster, cluster.label, cluster.memberIndices.size))
            }
            items(cluster.memberIndices, key = { "n$it" }) { idx ->
                PaperRow(scene.nodes[idx].title) { onPaperClick(scene.nodes[idx].id) }
            }
        }
        val loose = scene.nodes.filter { it.clusterId == GraphScene.UNCONNECTED_GROUP }
        if (loose.isNotEmpty()) {
            item(key = "unconnected") {
                SectionHeader(stringResource(R.string.knowledge_map_unconnected, loose.size))
            }
            items(loose, key = { "u${it.id}" }) { node ->
                PaperRow(node.title) { onPaperClick(node.id) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    HorizontalDivider()
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaperRow(
    title: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 2) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// --- previews ---

private fun sampleScene(): GraphScene {
    val nodes =
        (0 until 8).map { RelationNode("24%02d".format(it), "Paper number $it", inLibrary = true) } +
            RelationNode("2499", "An isolated paper")
    val edges =
        listOf(
            RelationEdge("2400", "2401", RelationEdgeKind.CITES),
            RelationEdge("2401", "2402", RelationEdgeKind.CITES),
            RelationEdge("2402", "2400", RelationEdgeKind.CITES),
            RelationEdge("2404", "2405", RelationEdgeKind.SIMILAR, 0.9),
            RelationEdge("2405", "2406", RelationEdgeKind.SIMILAR, 0.8),
            RelationEdge("2406", "2404", RelationEdgeKind.SIMILAR, 0.85),
        )
    return GraphSceneBuilder.build(RelationGraph(nodes, edges))
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun KnowledgeMapCanvasPreview() {
    ArxiverTheme {
        KnowledgeMapCanvas(sampleScene(), onPaperTap = {}, modifier = Modifier.fillMaxSize())
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun KnowledgeMapListPreview() {
    ArxiverTheme {
        KnowledgeMapList(sampleScene(), onPaperClick = {}, modifier = Modifier.fillMaxSize())
    }
}
