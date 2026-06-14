package dev.blokz.arxiver.feature.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.feature.browse.ErrorState
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    onBack: () -> Unit,
    viewModel: PdfViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

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
                    IconButton(onClick = viewModel::toggleNightMode) {
                        Icon(
                            imageVector = if (state.nightMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
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
                state.error != null -> ErrorState(error = state.error, onRetry = viewModel::retry)
                else ->
                    state.file?.let {
                        PdfPages(file = it, nightMode = state.nightMode, ioDispatcher = viewModel.ioDispatcher)
                    }
            }
        }
    }
}

private const val US_LETTER_ASPECT = 0.7727f

/** Inverts page colors for night reading. */
private val invertFilter =
    ColorFilter.colorMatrix(
        ComposeColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

@Composable
private fun PdfPages(
    file: File,
    nightMode: Boolean,
    ioDispatcher: CoroutineDispatcher,
) {
    val rendererState = remember(file, ioDispatcher) { PdfRendererHolder(file, ioDispatcher) }
    DisposableEffect(rendererState) {
        onDispose { rendererState.close() }
    }

    val listState = rememberLazyListState()
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

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(count = rendererState.pageCount, key = { it }) { pageIndex ->
                PdfPage(rendererState, pageIndex, nightMode)
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

@Composable
private fun PdfPage(
    holder: PdfRendererHolder,
    pageIndex: Int,
    nightMode: Boolean,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, holder, pageIndex) {
        value = holder.renderPage(pageIndex)
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
