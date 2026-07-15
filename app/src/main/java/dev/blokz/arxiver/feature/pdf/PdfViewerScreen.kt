package dev.blokz.arxiver.feature.pdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.data.ReaderThemeMode
import dev.blokz.arxiver.data.resolveReaderDark
import dev.blokz.arxiver.ui.components.ErrorState
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
                    IconButton(
                        onClick = {
                            // Toggle writes an EXPLICIT light/dark (never leaves it on SYSTEM), and persists globally.
                            viewModel.setReaderTheme(if (nightMode) ReaderThemeMode.LIGHT else ReaderThemeMode.DARK)
                        },
                    ) {
                        Icon(
                            imageVector = if (nightMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = stringResource(R.string.cd_toggle_night_mode),
                        )
                    }
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
    var restored by remember(rendererState) { mutableStateOf(false) }
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
    // The page pill shows while scrolling and lingers briefly after.
    var pillVisible by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            pillVisible = true
        } else {
            delay(PILL_LINGER_MS)
            pillVisible = false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Rasterise each page at the reader's REAL pixel width (PR.UX.2) instead of a flat 1080 — crisp on
        // hi-DPI screens — capped on a heap-derived ceiling so a wide foldable can't OOM. Recomputed only when
        // the container width actually changes (rotation / unfold). aspectRatio is still driven by the page's
        // own w/h, so item heights — and therefore P-Read scroll offsets — are unchanged by the resolution.
        val targetWidth = remember(constraints.maxWidth) { pdfTargetWidth(constraints.maxWidth) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            ) {
                Text(
                    text = stringResource(R.string.pdf_page_indicator, currentPage, rendererState.pageCount),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
        }
    }
}

private const val PILL_LINGER_MS = 1_500L

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

    // Free each page bitmap the moment its item leaves composition (PR.UX.2). LazyColumn recycles off-screen
    // items, so without this the higher-resolution bitmaps would linger until GC and pile up on a fast scroll.
    // onDispose runs only after the Image is gone, so there's no draw-after-recycle; the keyed effect also
    // frees the previous bitmap when a width change re-rasterises.
    val current = bitmap
    DisposableEffect(current) {
        onDispose { if (current != null && !current.isRecycled) current.recycle() }
    }

    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: US_LETTER_ASPECT

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .padding(vertical = 1.dp),
    ) {
        bitmap?.let {
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
