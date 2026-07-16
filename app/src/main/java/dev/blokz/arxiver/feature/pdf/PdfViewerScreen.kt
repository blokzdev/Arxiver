package dev.blokz.arxiver.feature.pdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.data.resolveReaderDark
import dev.blokz.arxiver.ui.components.ErrorState
import dev.blokz.arxiver.ui.components.ReaderThemeToggle
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    onBack: () -> Unit,
    viewModel: PdfViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // The effective dark is resolved HERE (not in the VM) so SYSTEM mode live-tracks an OS dark-toggle (P-Reader2 RNM).
    val themeMode by viewModel.readerThemeMode.collectAsState()
    val nightMode = resolveReaderDark(themeMode, isSystemInDarkTheme())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pdf_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    // Shared reader theme control — one tap advances SYSTEM→LIGHT→DARK→SYSTEM (so SYSTEM is
                    // reachable here, unlike the old LIGHT⇄DARK flip); the pref is global, both readers stay in step.
                    ReaderThemeToggle(mode = themeMode, onSetMode = viewModel::setReaderTheme)
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when {
                state.downloading ->
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.pdf_downloading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.md),
                        )
                    }
                state.error != null -> {
                    val externalUrl = state.externalUrl
                    val context = LocalContext.current
                    ErrorState(
                        error = state.error,
                        onRetry = viewModel::retry,
                        secondaryLabel = externalUrl?.let { stringResource(R.string.pdf_open_in_browser) },
                        onSecondary =
                            externalUrl?.let { url ->
                                { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                            },
                    )
                }
                else ->
                    state.file?.let {
                        PdfPages(
                            file = it,
                            nightMode = nightMode,
                            ioDispatcher = viewModel.ioDispatcher,
                            initialPosition = state.initialPosition,
                            onPositionChanged = viewModel::onPositionChanged,
                        )
                    }
            }
        }
    }
}

private const val US_LETTER_ASPECT = 0.7727f

/**
 * Night-reading colour filter (P-Reader2 PR.UX.1): a hue-preserving smart invert (see [PdfNightRender]) — so
 * coloured figures stay legible in dark instead of a plain negation flipping every hue — with softened extremes.
 */
private val invertFilter = ColorFilter.colorMatrix(ComposeColorMatrix(PdfNightRender.matrix))

@Composable
private fun PdfPages(
    file: File,
    nightMode: Boolean,
    ioDispatcher: CoroutineDispatcher,
    initialPosition: PdfResumeTarget?,
    onPositionChanged: (Int, Int, Float) -> Unit,
) {
    val rendererState = remember(file, ioDispatcher) { PdfRendererHolder(file, ioDispatcher) }
    DisposableEffect(rendererState) {
        onDispose { rendererState.close() }
    }

    val listState = rememberLazyListState()

    // Restore the durable position once, after the pages exist (P-Read). scrollToItem is an instant jump — no
    // drag — so it does NOT trip the genuine-scroll persist below (reopening never re-writes or inflates recency).
    // rememberSaveable (not remember) so this "already restored" latch SURVIVES the config-change Activity
    // recreation — .MainActivity declares no `configChanges`, so a rotate recreates the Activity. rememberLazyListState
    // already restores the LIVE scroll on rotate; without a saved latch this effect would re-scroll to the VM's
    // open-time page and discard the user's place (PP.3). Keyed on `file` so a genuinely new document still restores.
    var restored by rememberSaveable(file) { mutableStateOf(false) }
    LaunchedEffect(rendererState.pageCount, initialPosition) {
        val target = initialPosition
        if (!restored && rendererState.pageCount > 0 && target != null) {
            listState.scrollToItem(target.pageIndex.coerceIn(0, rendererState.pageCount - 1), target.offsetPx)
            restored = true
        }
    }

    // Persist ONLY after a genuine user drag settles — never on open, never on the restore jump (both leave
    // isScrollInProgress false), so a merely-opened PDF creates no shelf row.
    var userScrolled by remember(rendererState) { mutableStateOf(false) }
    LaunchedEffect(listState, rendererState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                userScrolled = true
            } else if (userScrolled) {
                onPositionChanged(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    scrollFraction(listState, rendererState.pageCount),
                )
            }
        }
    }
    val currentPage by remember {
        derivedStateOf { listState.firstVisibleItemIndex + 1 }
    }
    // The page pill is a persistent, tappable jump control whenever there's more than one page (PR.UX.4) — an
    // interactive affordance shouldn't hide itself the way the old scroll-only indicator did.
    val pillVisible = rendererState.pageCount > 1
    val scope = rememberCoroutineScope()
    var showJumpDialog by remember(rendererState) { mutableStateOf(false) }

    // Pinch / double-tap zoom (PR.UX.3). scale+offset drive a DRAW-ONLY graphicsLayer on the list, so the LazyColumn
    // still lays out at 1× and its scroll offsets keep their base-layout meaning — P-Read restore stays byte-identical,
    // and neither zoom nor pan writes a reading-position row. Zoom is not persisted. Hoisted to the PdfPages body (above
    // BoxWithConstraints) so the jump dialog — a sibling outside the Box — can reset it on a deliberate jump (PP.2).
    var scale by remember(rendererState) { mutableFloatStateOf(1f) }
    var offset by remember(rendererState) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Rasterise each page at the reader's REAL pixel width (PR.UX.2) instead of a flat 1080 — crisp on
        // hi-DPI screens — capped on a heap-derived ceiling so a wide foldable can't OOM. Recomputed only when
        // the container width actually changes (rotation / unfold). aspectRatio is still driven by the page's
        // own w/h, so item heights — and therefore P-Read scroll offsets — are unchanged by the resolution.
        val targetWidth = remember(constraints.maxWidth) { pdfTargetWidth(constraints.maxWidth) }

        val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
        // Read `scale` through derivedStateOf so THIS composition scope recomposes only when the zoomed/not-zoomed
        // BOOLEAN flips (once per zoom-in / zoom-out), NOT on every per-frame scale write during a pinch. A bare
        // `PdfZoom.isZoomed(scale)` here subscribed the enclosing (LazyColumn-emitting) scope to every gesture frame,
        // so a pinch re-measured the whole list each frame; combined with the old per-item bitmap recycle that then
        // raced a boundary page's bitmap out from under the still-compositing zoom graphicsLayer, the page went BLANK
        // (the reported real-device bug). The graphicsLayer below already defers its `scale`/`offset` reads to the
        // draw phase, so zoom stays genuinely draw-only.
        val zoomed by remember { derivedStateOf { PdfZoom.isZoomed(scale) } }

        LazyColumn(
            state = listState,
            // While zoomed, the list stops owning drags (they become pan); at 1× it scrolls normally and the
            // gesture handler below only claims the gesture once a SECOND finger lands (a pinch).
            userScrollEnabled = !zoomed,
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(viewport) {
                        detectTapGestures(
                            onDoubleTap = { tap ->
                                // Read `scale` fresh (not the captured `zoomed`) — pointerInput doesn't restart on
                                // a scale change, so the closure-captured value would be stale and never toggle back.
                                val newScale = if (PdfZoom.isZoomed(scale)) 1f else PdfZoom.DOUBLE_TAP_SCALE
                                offset = PdfZoom.focalOffset(offset, scale, newScale, tap, Offset.Zero, viewport)
                                scale = newScale
                            },
                        )
                    }
                    .pointerInput(viewport) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                // Initial pass: decide BEFORE the list's scrollable so a pinch can't first-frame
                                // scroll the page. We claim the gesture only for a genuine pinch (2+ fingers) or
                                // while already zoomed, and only consume actual MOVEMENT — so a single-finger drag
                                // at 1× (and a stationary double-tap while zoomed) still reach the list / tap
                                // detector untouched.
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointers = event.changes.count { it.pressed }
                                if (pointers >= 2 || PdfZoom.isZoomed(scale)) {
                                    val newScale = PdfZoom.coerceScale(scale * event.calculateZoom())
                                    offset =
                                        PdfZoom.focalOffset(
                                            offset,
                                            scale,
                                            newScale,
                                            event.calculateCentroid(useCurrent = true),
                                            event.calculatePan(),
                                            viewport,
                                        )
                                    scale = newScale
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
        ) {
            items(count = rendererState.pageCount, key = { it }) { pageIndex ->
                PdfPage(rendererState, pageIndex, nightMode, targetWidth)
            }
        }
        AnimatedVisibility(
            visible = pillVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Spacing.lg),
        ) {
            // Fold the live position into the CD so TalkBack announces "Page N of M" as well as the jump affordance
            // (the visible pill keeps the compact "N / M" for sighted users). Recomposes on page change — trivial (PP.3).
            val jumpLabel = stringResource(R.string.cd_pdf_jump, currentPage, rendererState.pageCount)
            Surface(
                onClick = { showJumpDialog = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                modifier = Modifier.semantics { contentDescription = jumpLabel },
            ) {
                Text(
                    text = stringResource(R.string.pdf_page_indicator, currentPage, rendererState.pageCount),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
        }
    }

    if (showJumpDialog) {
        val pageCount = rendererState.pageCount
        // Seed at the current page; edits are local to the dialog until "Go". `target` (Int) is canonical — the
        // slider, steppers and the "Page N of M" label all read it; `pageText` backs the type-a-page field so it can
        // be freely edited (empty / mid-type) without fighting the Int.
        var target by remember { mutableIntStateOf(currentPage.coerceIn(1, pageCount)) }
        var pageText by remember { mutableStateOf(target.toString()) }
        // Slider + steppers write through here so the field mirrors them.
        val setPage = { p: Int ->
            val clamped = p.coerceIn(1, pageCount)
            target = clamped
            pageText = clamped.toString()
        }
        // The one jump action, shared by the Go button and the keyboard's Go key. `target` is the single source of
        // truth — always in 1..pageCount and always mirrored by the label + slider, so the jump can never diverge
        // from what the dialog shows. Resets zoom to 1× (draw-only snapshot writes — no reading-position row) so a
        // deliberate jump never lands mid-pan/zoom.
        val performJump: () -> Unit = {
            showJumpDialog = false
            val index = (target - 1).coerceIn(0, pageCount - 1)
            scale = 1f
            offset = Offset.Zero
            scope.launch {
                listState.scrollToItem(index)
                // A DELIBERATE jump persists (unlike the restore jump, which leaves userScrolled false): record the
                // position so the "Continue reading" shelf + resume follow the reader here.
                onPositionChanged(index, 0, (index.toFloat() / pageCount).coerceIn(0f, 1f))
            }
        }
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text(stringResource(R.string.pdf_jump_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.pdf_jump_page_label, target, pageCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = pageText,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(6)
                            pageText = digits
                            // Any parseable value moves `target` (coerced to 1..pageCount) so the label + slider
                            // always predict where Go lands; an empty / non-numeric field leaves `target` at its
                            // last value (Go then jumps there — a no-op page change).
                            digits.toIntOrNull()?.let { target = it.coerceIn(1, pageCount) }
                        },
                        label = { Text(stringResource(R.string.pdf_jump_field_label)) },
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Go,
                            ),
                        keyboardActions = KeyboardActions(onGo = { performJump() }),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { setPage(target - 1) }, enabled = target > 1) {
                            Icon(Icons.Filled.Remove, stringResource(R.string.cd_pdf_jump_prev))
                        }
                        Slider(
                            value = target.toFloat(),
                            onValueChange = { setPage(it.roundToInt()) },
                            valueRange = 1f..pageCount.toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { setPage(target + 1) }, enabled = target < pageCount) {
                            Icon(Icons.Filled.Add, stringResource(R.string.cd_pdf_jump_next))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = performJump) { Text(stringResource(R.string.pdf_jump_go)) }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Continuous read progress in [0,1] — `(page + intra-page ratio)/pageCount` (P-Read), so a first-page read
 * still counts (unlike `page/pageCount`) and a just-opened first page reads ~0 (unlike `(page+1)/pageCount`).
 */
private fun scrollFraction(
    listState: LazyListState,
    pageCount: Int,
): Float {
    if (pageCount <= 0) return 0f
    val itemHeight = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
    val intra = if (itemHeight > 0) listState.firstVisibleItemScrollOffset.toFloat() / itemHeight else 0f
    return ((listState.firstVisibleItemIndex + intra) / pageCount).coerceIn(0f, 1f)
}

internal const val MIN_PDF_RENDER_WIDTH = 720
internal const val MAX_PDF_RENDER_WIDTH = 2560

/**
 * The pixel width to rasterise each PDF page at (PR.UX.2). The reader draws each page at the container width,
 * so rendering 1:1 with the container's REAL pixels is exactly crisp — the old flat 1080 upscaled to blur on a
 * 1440px screen. The only reason to render *narrower* than the container is memory: a wide foldable/tablet
 * (2000px+) at ARGB_8888 makes a page bitmap big enough to OOM, and a flat 2048 cap OOMs low-RAM devices while
 * starving hi-RAM ones. So cap on a heap-derived ceiling instead: budget one page bitmap (4 B/px, height
 * ≈ 1.43·width) to ~1/8 of the heap and invert `bytes ≈ 4·1.43·W²` for the max width. On a 256 MB heap that
 * ceiling is ~2.4k px (a 1080–1440 phone passes through untouched); on a 64 MB heap it drops to ~1.2k,
 * protecting the device. Pure + heap-injected so it is unit-testable.
 */
internal fun pdfTargetWidth(
    containerPx: Int,
    maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
): Int {
    val budgetBytes = maxHeapBytes / 8.0
    val memCapWidth = sqrt(budgetBytes / (4.0 * 1.43)).toInt()
    val ceiling = maxOf(MIN_PDF_RENDER_WIDTH, minOf(MAX_PDF_RENDER_WIDTH, memCapWidth))
    return containerPx.coerceIn(MIN_PDF_RENDER_WIDTH, ceiling)
}

@Composable
private fun PdfPage(
    holder: PdfRendererHolder,
    pageIndex: Int,
    nightMode: Boolean,
    targetWidth: Int,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, holder, pageIndex, targetWidth) {
        value = holder.renderPage(pageIndex, targetWidth)
    }

    // Bitmap lifetime is left to GC. It is bounded by the LazyColumn's composed window (only visible + prefetched
    // pages hold a bitmap — a disposed page's produceState state is dropped, making its bitmap unreferenced and
    // reclaimable) and each bitmap is heap-capped by pdfTargetWidth. The previous PR.UX.2 `onDispose { recycle() }`
    // was REMOVED: during a pinch it raced a boundary page's bitmap out from under the still-compositing zoom
    // graphicsLayer and painted the page BLANK. (A bounded LRU bitmap cache for very large PDFs is tracked in the
    // backlog if fast-scroll heap pressure ever shows on device.)
    //
    // Defensive guard: never hand a recycled bitmap to asImageBitmap() — drawing one throws. Post-fix nothing
    // recycles a composed page's bitmap, so this only future-proofs against a re-introduced recycle.
    val drawable = bitmap?.takeIf { !it.isRecycled }
    val aspect = drawable?.let { it.width.toFloat() / it.height } ?: US_LETTER_ASPECT

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .padding(vertical = 1.dp),
    ) {
        drawable?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_pdf_page, pageIndex + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillWidth,
                colorFilter = if (nightMode) invertFilter else null,
            )
        } ?: CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }
}

/**
 * PdfRenderer is single-threaded and page-exclusive; all access funnels
 * through one mutex.
 */
private class PdfRendererHolder(
    file: File,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val mutex = Mutex()

    val pageCount: Int = renderer.pageCount

    suspend fun renderPage(
        index: Int,
        targetWidth: Int = 1080,
    ): Bitmap? =
        withContext(ioDispatcher) {
            mutex.withLock {
                runCatching {
                    renderer.openPage(index).use { page ->
                        val scale = targetWidth.toFloat() / page.width
                        val bitmap = createBitmap(targetWidth, (page.height * scale).toInt())
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }.getOrNull()
            }
        }

    fun close() {
        runCatching {
            renderer.close()
            fd.close()
        }
    }
}
