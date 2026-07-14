package dev.blokz.arxiver.feature.html

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.blokz.arxiver.core.ai.TocModel
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.data.ReaderThemeMode
import dev.blokz.arxiver.data.resolveReaderDark
import dev.blokz.arxiver.feature.paper.ask.AskQuoteRequest
import dev.blokz.arxiver.feature.paper.ask.AskSheet
import dev.blokz.arxiver.feature.paper.ask.ConversationMarkdown
import dev.blokz.arxiver.feature.paper.ask.ConversationMarkdownLabels
import dev.blokz.arxiver.ui.components.ErrorState
import dev.blokz.arxiver.ui.feedback.FeedbackMessage
import dev.blokz.arxiver.ui.feedback.LocalFeedbackController
import dev.blokz.arxiver.ui.shareText
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.delay

/** The reader's mutually-exclusive modal sheets — opening one structurally closes the other. */
internal enum class ReaderSheet { NONE, TOC, ASK }

/**
 * The HTML-edition reader (Phase P-HTML PH.4/PH.6): renders the sanitized + transformed
 * [ReaderDocWriter] output in the offline [HtmlReaderWebView]. native→ar5iv→PDF fallback is driven by
 * the ViewModel's one-shot effect; the toolbar always offers "Read PDF instead" (never strand); ar5iv
 * is shown with an honest banner; external links require a confirm before opening. PH.6 adds the TOC
 * sheet (always reachable while a doc shows — a stable affordance even for anchor-poor papers) and the
 * scroll-idle position probe that feeds the ViewModel's reading-position slot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlReaderScreen(
    onBack: () -> Unit,
    onFallbackToPdf: (String) -> Unit,
    onPaperClick: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: HtmlReaderViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.readerThemeMode.collectAsState()
    val nightMode = resolveReaderDark(themeMode, isSystemInDarkTheme())
    // Force the reader (chrome + body) to the shared night-mode. rememberReaderTheme() + the `html` derivation
    // below must both live INSIDE this wrapper — else the article renders in the app theme while only the chrome
    // recolours, a half-themed reader (P-Reader2 RNM.3). Material You dynamic colour is kept (§C-8); SYSTEM mode
    // live-tracks the OS via isSystemInDarkTheme().
    ArxiverTheme(darkTheme = nightMode) {
        val state by viewModel.uiState.collectAsState()
        val theme = rememberReaderTheme()
        val context = LocalContext.current
        val feedback = LocalFeedbackController.current
        var externalUrl by remember { mutableStateOf<String?>(null) }
        var activeSheet by rememberSaveable { mutableStateOf(ReaderSheet.NONE) }
        val controller = remember { ReaderScrollController() }
        var scrollTick by remember { mutableLongStateOf(0L) }
        val anchorIds = remember(state.doc) { state.doc?.anchors?.map { it.id } ?: emptyList() }
        val tocEntries = remember(state.doc) { TocModel.buildToc(state.doc?.anchors ?: emptyList()) }

        // PH.7 find-in-page. finding/query survive rotation + process death; counts are
        // document-lifetime (auto-clear on every reload via the html key + on query change).
        var finding by rememberSaveable { mutableStateOf(false) }
        var findQuery by rememberSaveable { mutableStateOf("") }
        val html = state.doc?.let { doc -> remember(doc, theme) { ReaderDocWriter.write(doc, theme) } }
        val findCounts = remember(html) { mutableStateOf<FindCounts?>(null) }
        val closeFind = {
            finding = false
            findQuery = ""
            findCounts.value = null
            controller.clearFind()
        }

        // PH.7 selection→Ask: the consume-once quote offer for the reader-hosted AskSheet.
        var quoteSeq by rememberSaveable { mutableLongStateOf(0L) }
        var pendingQuoteText by rememberSaveable { mutableStateOf<String?>(null) }

        val pinnedToNotesMessage = stringResource(R.string.ask_pinned_to_notes)
        val matchAnnounceTemplate = stringResource(R.string.html_find_match_announce)
        val noMatchesAnnounce = stringResource(R.string.html_find_no_matches_announce)

        LaunchedEffect(Unit) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is HtmlReaderEffect.FallbackToPdf -> onFallbackToPdf(effect.id)
                    is HtmlReaderEffect.JumpToAnchor -> {
                        controller.jumpTo(effect.anchorId)
                        controller.announce(context.getString(R.string.html_toc_jumped, effect.label))
                    }
                }
            }
        }

        // Scroll-idle debounce: each tick restarts the wait; quiet → probe the reading position.
        LaunchedEffect(scrollTick) {
            if (scrollTick == 0L) return@LaunchedEffect
            delay(PROBE_DEBOUNCE_MS)
            controller.probe(anchorIds) { raw ->
                viewModel.onPositionProbed(ReaderScrollJs.parseProbeResult(raw))
            }
        }

        // Re-issue an active find once per reload, AFTER the PH.6 restore reveals (the reveal funnel
        // guarantees ordering — find's match-scroll then deterministically wins over the re-apply).
        controller.onRevealed = {
            if (finding && findQuery.isNotBlank()) controller.findAll(findQuery)
        }

        // Close the find bar (not the screen) on Back while finding.
        BackHandler(enabled = finding) { closeFind() }

        Scaffold(
            topBar = {
                if (finding) {
                    ReaderFindBar(
                        query = findQuery,
                        counts = findCounts.value,
                        onQueryChange = { q ->
                            findQuery = q
                            findCounts.value = null // a slow prior count never paints a new query
                            if (q.isNotBlank()) controller.findAll(q) else controller.clearFind()
                        },
                        onSubmit = {
                            if (findCounts.value?.let { it.total > 0 } == true) {
                                controller.findNext(true)
                            } else if (findQuery.isNotBlank()) {
                                controller.findAll(findQuery)
                            }
                        },
                        onNext = { controller.findNext(true) },
                        onPrevious = { controller.findNext(false) },
                        onClose = closeFind,
                    )
                } else {
                    TopAppBar(
                        title = { Text(stringResource(R.string.html_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                            }
                        },
                        actions = {
                            if (state.doc != null) {
                                IconButton(onClick = { finding = true }) {
                                    Icon(Icons.Filled.Search, stringResource(R.string.cd_find_in_page))
                                }
                                IconButton(onClick = { activeSheet = ReaderSheet.TOC }) {
                                    Icon(Icons.AutoMirrored.Filled.Toc, stringResource(R.string.cd_toc))
                                }
                                // The guaranteed Ask path (TalkBack + every ActionMode degradation).
                                IconButton(onClick = { activeSheet = ReaderSheet.ASK }) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.cd_reader_ask))
                                }
                            }
                            // Shared reader night-mode toggle (RNM.3) — writes an EXPLICIT light/dark, persisted globally.
                            IconButton(
                                onClick = {
                                    viewModel.setReaderTheme(
                                        if (nightMode) ReaderThemeMode.LIGHT else ReaderThemeMode.DARK,
                                    )
                                },
                            ) {
                                Icon(
                                    imageVector = if (nightMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                    contentDescription = stringResource(R.string.cd_toggle_night_mode),
                                )
                            }
                            IconButton(onClick = viewModel::openPdfInstead) {
                                Icon(Icons.Filled.PictureAsPdf, stringResource(R.string.action_read_pdf_instead))
                            }
                        },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    state.loading -> LoadingState()
                    state.error != null -> ErrorState(error = state.error, onRetry = viewModel::retry)
                    state.doc != null && html != null -> {
                        val doc = state.doc!!
                        Column(Modifier.fillMaxSize()) {
                            if (doc.source == HtmlSource.AR5IV) Ar5ivBanner()
                            HtmlReaderWebView(
                                html = html,
                                controller = controller,
                                askSelectionLabel = stringResource(R.string.html_ask_selection),
                                onPaperClick = onPaperClick,
                                onExternalUrl = { externalUrl = it },
                                onAnchorTap = viewModel::onJump,
                                onScrollTick = { scrollTick++ },
                                onPageReady = {
                                    // Read the VM's CURRENT target at load-completion time — never a
                                    // value captured at expose time (the PH.6 restore invariant).
                                    controller.onPageReady(
                                        target = viewModel.restoreTarget(),
                                        minFraction = HtmlReaderViewModel.MIN_RESTORE_FRACTION,
                                        onApplied = viewModel::onRestoreApplied,
                                    )
                                },
                                onFindResult = { active, total, done ->
                                    findCounts.value = FindCounts(active, total, done)
                                    if (done) {
                                        controller.announce(
                                            if (total > 0) {
                                                matchAnnounceTemplate.format(active + 1, total)
                                            } else {
                                                noMatchesAnnounce
                                            },
                                        )
                                    }
                                },
                                onAskSelection = { excerpt ->
                                    quoteSeq++
                                    pendingQuoteText = excerpt
                                    activeSheet = ReaderSheet.ASK
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        if (activeSheet == ReaderSheet.TOC && state.doc != null) {
            TocSheet(
                entries = tocEntries,
                onSelect = { entry, label ->
                    activeSheet = ReaderSheet.NONE // dismiss-then-act
                    viewModel.onTocSelect(entry.anchorId, label)
                },
                onDismiss = { activeSheet = ReaderSheet.NONE },
            )
        }

        if (activeSheet == ReaderSheet.ASK && state.doc != null) {
            val exportLabels =
                ConversationMarkdownLabels(
                    you = stringResource(R.string.ask_export_you),
                    assistant = stringResource(R.string.ask_export_assistant),
                    sources = stringResource(R.string.ask_export_sources),
                    footer = stringResource(R.string.ask_export_footer),
                )
            AskSheet(
                scope = RetrievalScope.Paper(viewModel.paperId.value),
                onDismiss = {
                    activeSheet = ReaderSheet.NONE
                    pendingQuoteText = null
                },
                onConfigureProvider = {
                    activeSheet = ReaderSheet.NONE
                    onOpenAiSettings()
                },
                onOpenCrossRef = { rawId ->
                    ArxivId.parse(rawId)?.let { (id, _) ->
                        activeSheet = ReaderSheet.NONE
                        onPaperClick(id.value)
                    }
                },
                onPinAnswer = { answer ->
                    viewModel.pinNote(answer)
                    feedback.show(FeedbackMessage(text = pinnedToNotesMessage))
                },
                onShareAnswer = { m ->
                    context.shareText(
                        ConversationMarkdown.answer(m, exportLabels),
                        subject = state.paperTitle ?: "",
                    )
                },
                conversationTitle = state.paperTitle,
                initialQuote = pendingQuoteText?.let { AskQuoteRequest(quoteSeq, it) },
            )
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
    } // ArxiverTheme(darkTheme = …) — closes the forced-theme wrapper opened at the top (RNM.3)
}

/** Scroll-quiet window before a position probe fires. PROVISIONAL — device-ratified (§M). */
private const val PROBE_DEBOUNCE_MS = 400L

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
