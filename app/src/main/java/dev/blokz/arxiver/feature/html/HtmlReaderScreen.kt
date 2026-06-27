package dev.blokz.arxiver.feature.html

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.ai.HtmlSource
import dev.blokz.arxiver.core.ai.ReaderDocWriter
import dev.blokz.arxiver.ui.components.ErrorState
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * The HTML-edition reader (Phase P-HTML PH.4): renders the sanitized + transformed [ReaderDocWriter]
 * output in the offline [HtmlReaderWebView]. native→ar5iv→PDF fallback is driven by the ViewModel's
 * one-shot effect; the toolbar always offers "Read PDF instead" (never strand); ar5iv is shown with an
 * honest banner; external links require a confirm before opening.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlReaderScreen(
    onBack: () -> Unit,
    onFallbackToPdf: (String) -> Unit,
    onPaperClick: (String) -> Unit,
    viewModel: HtmlReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val theme = rememberReaderTheme()
    val context = LocalContext.current
    var externalUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HtmlReaderEffect.FallbackToPdf -> onFallbackToPdf(effect.id)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.html_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::openPdfInstead) {
                        Icon(Icons.Filled.PictureAsPdf, stringResource(R.string.action_read_pdf_instead))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(error = state.error, onRetry = viewModel::retry)
                state.doc != null -> {
                    val doc = state.doc!!
                    val html = remember(doc, theme) { ReaderDocWriter.write(doc, theme) }
                    Column(Modifier.fillMaxSize()) {
                        if (doc.source == HtmlSource.AR5IV) Ar5ivBanner()
                        HtmlReaderWebView(
                            html = html,
                            onPaperClick = onPaperClick,
                            onExternalUrl = { externalUrl = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    externalUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { externalUrl = null },
            title = { Text(stringResource(R.string.html_external_title)) },
            text = { Text(url) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                    externalUrl = null
                }) { Text(stringResource(R.string.html_external_open)) }
            },
            dismissButton = {
                TextButton(onClick = { externalUrl = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.html_rendering),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )
    }
}

/** Quiet, persistent notice that the rendering is the community ar5iv conversion (TalkBack-announced). */
@Composable
private fun Ar5ivBanner() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.html_banner_ar5iv),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LoadingPreview() {
    ArxiverTheme { LoadingState() }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Ar5ivBannerPreview() {
    ArxiverTheme { Ar5ivBanner() }
}
