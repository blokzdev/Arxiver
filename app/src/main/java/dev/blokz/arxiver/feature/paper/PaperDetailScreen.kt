package dev.blokz.arxiver.feature.paper

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.TagEntity
import dev.blokz.arxiver.core.model.Citation
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PdfAccess
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.model.pdfAccess
import dev.blokz.arxiver.data.DiscoverHit
import dev.blokz.arxiver.data.DiscoverResult
import dev.blokz.arxiver.data.NeighborsResult
import dev.blokz.arxiver.data.PdfStorage
import dev.blokz.arxiver.data.RelatedPaper
import dev.blokz.arxiver.data.s2.browserFallbackUrl
import dev.blokz.arxiver.data.s2.bylineText
import dev.blokz.arxiver.feature.claude.DispatchSheet
import dev.blokz.arxiver.feature.paper.ask.AskSheet
import dev.blokz.arxiver.feature.paper.ask.ConversationMarkdown
import dev.blokz.arxiver.feature.paper.ask.ConversationMarkdownLabels
import dev.blokz.arxiver.ui.components.ErrorState
import dev.blokz.arxiver.ui.components.ScoreBar
import dev.blokz.arxiver.ui.components.SkeletonLine
import dev.blokz.arxiver.ui.components.StatusChip
import dev.blokz.arxiver.ui.components.StatusTone
import dev.blokz.arxiver.ui.feedback.FeedbackAction
import dev.blokz.arxiver.ui.feedback.FeedbackMessage
import dev.blokz.arxiver.ui.feedback.LocalFeedbackController
import dev.blokz.arxiver.ui.fixtures.PreviewFixtures
import dev.blokz.arxiver.ui.sharePdf
import dev.blokz.arxiver.ui.shareText
import dev.blokz.arxiver.ui.theme.ArxiverMotion
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperDetailScreen(
    onBack: () -> Unit,
    onOpenPdf: (String) -> Unit,
    onOpenHtml: (String) -> Unit,
    onPaperClick: (String) -> Unit,
    onOpenConnections: (String) -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: PaperDetailViewModel = hiltViewModel(),
) {
    var showDispatch by remember { mutableStateOf(false) }
    var showAsk by remember { mutableStateOf(false) }
    var showOrganize by remember { mutableStateOf(false) }
    var showDiscoverSheet by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }
    val feedback = LocalFeedbackController.current
    val clipboard = LocalClipboardManager.current
    val savedMessage = stringResource(R.string.today_snackbar_saved)
    val addToLabel = stringResource(R.string.action_add_to_collection)
    val pinnedToNotesMessage = stringResource(R.string.ask_pinned_to_notes)
    val referenceCopiedMessage = stringResource(R.string.paper_reference_copied)
    val bibtexCopiedMessage = stringResource(R.string.paper_bibtex_copied)
    val pdfNotDownloadedMessage = stringResource(R.string.paper_pdf_not_downloaded)
    val exportLabels =
        ConversationMarkdownLabels(
            you = stringResource(R.string.ask_export_you),
            assistant = stringResource(R.string.ask_export_assistant),
            sources = stringResource(R.string.ask_export_sources),
            footer = stringResource(R.string.ask_export_footer),
        )
    val state by viewModel.uiState.collectAsState()
    val entry by viewModel.entry.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val collections by viewModel.collections.collectAsState()
    val memberCollectionIds by viewModel.memberCollectionIds.collectAsState()
    val related by viewModel.related.collectAsState()
    val followedAuthors by viewModel.followedAuthors.collectAsState()
    val oaState by viewModel.oa.collectAsState()
    val discoverState by viewModel.discover.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // The serif hero in the content is the identity moment; the bar title
    // fades in only once the hero scrolls away.
    val scrollState = rememberScrollState()
    val heroThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    val showBarTitle by remember {
        derivedStateOf { scrollState.value > heroThresholdPx }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(
                        visible = showBarTitle,
                        enter = fadeIn(tween(ArxiverMotion.DURATION_SHORT)),
                        exit = fadeOut(tween(ArxiverMotion.DURATION_SHORT)),
                    ) {
                        Text(
                            text = state.paper?.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    state.paper?.let { paper ->
                        IconButton(onClick = { showAsk = true }) {
                            Icon(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.cd_ask))
                        }
                        // Send-to-Claude routines are source-aware (P-Dispatch) — available for any paper; a
                        // non-arXiv paper rides source/native_id/url in the payload.
                        IconButton(onClick = { showDispatch = true }) {
                            Icon(Icons.Filled.AutoAwesome, stringResource(R.string.cd_send_to_claude))
                        }
                        IconButton(
                            onClick = {
                                val wasSaved = entry != null
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleSaved()
                                // On a fresh save, offer the second step: file it into a collection/tag.
                                if (!wasSaved) {
                                    feedback.show(
                                        FeedbackMessage(
                                            text = savedMessage,
                                            secondary = FeedbackAction(addToLabel) { showOrganize = true },
                                        ),
                                    )
                                }
                            },
                        ) {
                            Crossfade(targetState = entry != null, label = "bookmark") { saved ->
                                if (saved) {
                                    Icon(Icons.Filled.Bookmark, stringResource(R.string.cd_unsave_paper))
                                } else {
                                    Icon(Icons.Filled.BookmarkBorder, stringResource(R.string.cd_save_paper))
                                }
                            }
                        }
                        IconButton(
                            onClick = {
                                val send =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "${paper.title}\n${paper.canonicalUrl()}")
                                    }
                                context.startActivity(
                                    Intent.createChooser(send, context.getString(R.string.action_share)),
                                )
                            },
                        ) {
                            Icon(Icons.Filled.Share, stringResource(R.string.action_share))
                        }
                        Box {
                            IconButton(onClick = { showActionsMenu = true }) {
                                Icon(Icons.Filled.MoreVert, stringResource(R.string.cd_more_actions))
                            }
                            DropdownMenu(
                                expanded = showActionsMenu,
                                onDismissRequest = { showActionsMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_share_pdf)) },
                                    leadingIcon = { Icon(Icons.Filled.PictureAsPdf, null) },
                                    onClick = {
                                        showActionsMenu = false
                                        val pdf = PdfStorage.localPdf(context, paper.ref.storageId)
                                        if (pdf != null) {
                                            context.sharePdf(pdf, subject = paper.title)
                                        } else {
                                            // Offline-safe fallback: share the arXiv PDF link, no silent download.
                                            context.shareText(paper.pdfUrl, subject = paper.title)
                                            feedback.show(FeedbackMessage(text = pdfNotDownloadedMessage))
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_copy_reference)) },
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                    onClick = {
                                        showActionsMenu = false
                                        clipboard.setText(AnnotatedString(Citation.reference(paper)))
                                        feedback.show(FeedbackMessage(text = referenceCopiedMessage))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_copy_bibtex)) },
                                    leadingIcon = { Icon(Icons.Filled.Code, null) },
                                    onClick = {
                                        showActionsMenu = false
                                        clipboard.setText(AnnotatedString(Citation.bibtex(paper)))
                                        feedback.show(FeedbackMessage(text = bibtexCopiedMessage))
                                    },
                                )
                            }
                        }
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
                state.loading -> DetailSkeleton()
                state.notFound -> ErrorState(message = stringResource(R.string.paper_not_found))
                else ->
                    state.paper?.let { paper ->
                        PaperDetailContent(
                            paper = paper,
                            entry = entry,
                            notes = notes,
                            tags = tags,
                            collections = collections,
                            memberCollectionIds = memberCollectionIds,
                            related = related,
                            followedAuthors = followedAuthors,
                            onToggleAuthorFollow = viewModel::toggleAuthorFollow,
                            oaState = oaState,
                            onResolveOa = viewModel::resolveOa,
                            discoverState = discoverState,
                            discoverable = viewModel.isDiscoverable(paper),
                            onDiscover = viewModel::discoverSimilar,
                            onShowDiscoverResults = { showDiscoverSheet = true },
                            scrollState = scrollState,
                            onOpenPdf = onOpenPdf,
                            onOpenHtml = onOpenHtml,
                            onPaperClick = onPaperClick,
                            onOpenConnections = onOpenConnections,
                            onSetStatus = viewModel::setStatus,
                            onSetRating = viewModel::setRating,
                            onAddNote = viewModel::addNote,
                            onDeleteNote = viewModel::deleteNote,
                            onAddTag = viewModel::addTag,
                            onRemoveTag = viewModel::removeTag,
                            onAddToCollection = viewModel::addToCollection,
                            onRemoveFromCollection = viewModel::removeFromCollection,
                            onCreateCollection = viewModel::createCollectionWithPaper,
                        )
                    }
            }
        }
    }

    if (showDispatch) {
        state.paper?.let { paper ->
            DispatchSheet(
                paperIds = listOf(paper.ref.storageId),
                onDismiss = { showDispatch = false },
                onGoToRoutines = {
                    showDispatch = false
                    onOpenRoutines()
                },
            )
        }
    }

    if (showAsk) {
        state.paper?.let { paper ->
            AskSheet(
                scope = dev.blokz.arxiver.core.search.RetrievalScope.Paper(paper.ref.storageId),
                onDismiss = { showAsk = false },
                onConfigureProvider = {
                    showAsk = false
                    onOpenAiSettings()
                },
                // Tapping an `arXiv:<id>` in an answer opens that paper in-app (validated here,
                // fetch-on-demand if not in the library) — P-Rich R3a.
                onOpenCrossRef = { rawId ->
                    dev.blokz.arxiver.core.model.ArxivId.parse(rawId)?.let { (id, _) ->
                        showAsk = false
                        onPaperClick(id.value)
                    }
                },
                // Pin the answer into this paper's notes, with a confirming snackbar — P-Rich R3a.
                onPinAnswer = { answer ->
                    viewModel.addNote(answer)
                    feedback.show(FeedbackMessage(text = pinnedToNotesMessage))
                },
                // Share an answer / the whole conversation as Markdown via the OS sheet — P-Rich R4.
                onShareAnswer = { m ->
                    context.shareText(ConversationMarkdown.answer(m, exportLabels), subject = paper.title)
                },
                // Whole-conversation export (text / Markdown file / PDF) is owned by AskSheet — P-Share PS.6.
                conversationTitle = paper.title,
            )
        }
    }

    if (showOrganize) {
        state.paper?.let { paper ->
            dev.blokz.arxiver.feature.organize.OrganizeSheet(
                paperIds = listOf(paper.ref.storageId),
                onDismiss = { showOrganize = false },
            )
        }
    }

    // P-Discover-MLT PDM.4: the discovery results — everything is already in hand from the ONE memoized
    // response (zero network on open/scroll; re-opening never re-egresses). An arXiv row navigates in-app
    // (the destination detail screen fetch-persists the native record — the existing paper(ref) path);
    // a non-arXiv row opens the browser inside the sheet row itself.
    if (showDiscoverSheet) {
        ((discoverState as? DiscoverUiState.Done)?.result as? DiscoverResult.Ready)?.let { ready ->
            DiscoverResultsSheet(
                hits = ready.hits,
                onDismiss = { showDiscoverSheet = false },
                onOpenArxiv = { bareId ->
                    showDiscoverSheet = false
                    onPaperClick(bareId)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaperDetailContent(
    paper: Paper,
    entry: LibraryEntryEntity?,
    notes: List<NoteEntity>,
    tags: List<TagEntity>,
    collections: List<dev.blokz.arxiver.core.database.entity.CollectionEntity>,
    memberCollectionIds: Set<Long>,
    related: NeighborsResult,
    followedAuthors: Set<String>,
    onToggleAuthorFollow: (String) -> Unit,
    oaState: OaUiState,
    onResolveOa: () -> Unit,
    discoverState: DiscoverUiState,
    discoverable: Boolean,
    onDiscover: () -> Unit,
    onShowDiscoverResults: () -> Unit,
    scrollState: androidx.compose.foundation.ScrollState,
    onOpenPdf: (String) -> Unit,
    onOpenHtml: (String) -> Unit,
    onPaperClick: (String) -> Unit,
    onOpenConnections: (String) -> Unit,
    onSetStatus: (String) -> Unit,
    onSetRating: (Int?) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (Long) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (Long) -> Unit,
    onAddToCollection: (Long) -> Unit,
    onRemoveFromCollection: (Long) -> Unit,
    onCreateCollection: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(text = paper.title, style = MaterialTheme.typography.headlineSmall)
        AuthorLine(
            authors = paper.authors,
            followedAuthors = followedAuthors,
            onToggleAuthorFollow = onToggleAuthorFollow,
        )
        // Provenance badge for a non-arXiv paper (chemRxiv, PS.1). arXiv is the default identity and shows
        // no badge — the chip marks the exception, keeping the arXiv case visually byte-identical.
        if (paper.ref.origin != Source.ARXIV) {
            val badgeCd = stringResource(R.string.cd_source_badge, paper.ref.origin.displayName)
            StatusChip(
                text = paper.ref.origin.displayName,
                tone = StatusTone.Neutral,
                icon = Icons.Filled.Science,
                modifier = Modifier.semantics { contentDescription = badgeCd },
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            paper.categories.forEach { code -> AssistChip(onClick = {}, label = { Text(code) }) }
        }
        Text(
            text =
                stringResource(
                    R.string.paper_dates,
                    dateFormat.format(paper.publishedAt.atZone(ZoneId.systemDefault())),
                    paper.latestVersion,
                    dateFormat.format(paper.updatedAt.atZone(ZoneId.systemDefault())),
                ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            // The HTML edition (ar5iv/native) is an arXiv-only transform — hidden for a non-arXiv paper.
            // The FlowRow reflows to a clean two-button bar; the affordance is simply absent (no disabled
            // ceremony). HtmlReaderViewModel defensively falls back to PDF if ever handed a non-arXiv ref.
            if (paper.ref.origin == Source.ARXIV) {
                FilledTonalButton(onClick = { onOpenHtml(paper.ref.storageId) }) {
                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null)
                    Text(
                        text = stringResource(R.string.action_open_html),
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
            // Honest per-source affordance (PE.3): an in-app-fetchable PDF gets the reader; a gated source
            // (SSRN/PsyArXiv/Research Square/Preprints.org/chemRxiv) gets a DIRECT browser button instead of a
            // download-spinner detour that was always going to fail closed.
            if (paper.isPdfFetchable()) {
                FilledTonalButton(onClick = { onOpenPdf(paper.ref.storageId) }) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                    Text(
                        text = stringResource(R.string.action_open_pdf),
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            } else {
                val uriHandler = LocalUriHandler.current
                val url = paper.canonicalUrl()
                if (url.isNotBlank()) {
                    // Distinct CD with the app-switch cue so TalkBack tells this apart from the OA "Open PDF"
                    // action below (both leave the app for the browser).
                    val browserCd = stringResource(R.string.cd_open_in_browser)
                    FilledTonalButton(
                        onClick = { uriHandler.openUri(url) },
                        modifier = Modifier.semantics { contentDescription = browserCd },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Text(
                            text = stringResource(R.string.pdf_open_in_browser),
                            modifier = Modifier.padding(start = Spacing.sm),
                        )
                    }
                }
            }
            // P-OA: the open-access published-version affordance — only for a browser-gated preprint with a
            // title to search on. arXiv/bio/med already read their PDF in-app and are intentionally excluded.
            if (paper.ref.origin.pdfAccess() == PdfAccess.BROWSER && paper.title.isNotBlank()) {
                OaFulltextButton(oa = oaState, onResolve = onResolveOa)
            }
            // P-Discover-MLT: "Discover similar" — hidden when the paper carries neither an arXiv id nor a
            // DOI (never a dead control). Two-tap morph (the ratified P-OA pattern): tap 1 searches (the ONE
            // disclosed S2 call), the button morphs to "Show N similar", tap 2 opens the sheet from memory.
            if (discoverable) {
                DiscoverSimilarButton(
                    state = discoverState,
                    onDiscover = onDiscover,
                    onShowResults = onShowDiscoverResults,
                )
            }
            FilledTonalButton(onClick = { onOpenConnections(paper.ref.storageId) }) {
                Icon(Icons.Filled.Hub, contentDescription = null)
                Text(
                    text = stringResource(R.string.paper_view_connections),
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
        }
        // The OA provenance / "no free version" line sits BELOW the action row so a state morph never changes a
        // button's height (which would reflow the FlowRow under the user's finger).
        if (paper.ref.origin.pdfAccess() == PdfAccess.BROWSER && paper.title.isNotBlank()) {
            OaFulltextCaption(oa = oaState)
        }
        // The discovery disclosure / outcome line — same below-the-row placement discipline as the OA caption.
        if (discoverable) {
            DiscoverCaption(state = discoverState)
        }

        if (entry != null) {
            LibrarySection(entry, onSetStatus, onSetRating)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // SSRN strips 100% of abstracts and Research Square 86% (licensing, permanent) — a dead heading over
        // nothing reads as a rendering bug; say honestly that the source doesn't provide one (PE.3).
        if (paper.abstract.isNotBlank()) {
            DetailHeading(stringResource(R.string.paper_abstract_heading))
            ExpandableAbstract(paper.abstract)
        } else {
            Text(
                stringResource(R.string.paper_no_abstract, paper.ref.origin.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (entry != null) {
            CollectionsSection(
                collections = collections,
                memberIds = memberCollectionIds,
                onAdd = onAddToCollection,
                onRemove = onRemoveFromCollection,
                onCreate = onCreateCollection,
            )
            TagsSection(tags, onAddTag, onRemoveTag)
            NotesSection(notes, onAddNote, onDeleteNote)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        when (related) {
            is NeighborsResult.Ready -> RelatedSection(related.neighbors, onPaperClick)
            NeighborsResult.Loading ->
                RelatedNeighborsPlaceholder(stringResource(R.string.paper_related_loading))
            NeighborsResult.NotEmbedded ->
                RelatedNeighborsPlaceholder(stringResource(R.string.paper_related_not_indexed))
            NeighborsResult.NoRelations ->
                RelatedNeighborsPlaceholder(stringResource(R.string.paper_related_none))
        }

        MetadataSection(paper)
    }
}

/**
 * The author byline as individually tappable names (P-Discover2 PD.1): tap an author → a menu to follow/unfollow.
 * A follow is a free-text arXiv `au:"name"` match — namesakes can collide, so the copy stays honest.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuthorLine(
    authors: List<String>,
    followedAuthors: Set<String>,
    onToggleAuthorFollow: (String) -> Unit,
) {
    var menuAuthor by remember { mutableStateOf<String?>(null) }
    val followLabel = stringResource(R.string.action_follow_author)
    val followingLabel = stringResource(R.string.action_following_author)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        authors.forEachIndexed { index, author ->
            val following = author in followedAuthors
            val cd =
                stringResource(
                    if (following) R.string.cd_author_following else R.string.cd_author_follow,
                    author,
                )
            Box {
                Text(
                    text = if (index < authors.lastIndex) "$author," else author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .clickable { menuAuthor = author }
                            .semantics { contentDescription = cd },
                )
                DropdownMenu(expanded = menuAuthor == author, onDismissRequest = { menuAuthor = null }) {
                    DropdownMenuItem(
                        text = { Text(if (following) followingLabel else followLabel) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (following) Icons.Filled.Check else Icons.Filled.PersonAdd,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onToggleAuthorFollow(author)
                            menuAuthor = null
                        },
                    )
                }
            }
        }
    }
}

/** Detail headings match the app-wide section-header style sans padding. */
@Composable
private fun DetailHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ExpandableAbstract(abstract: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.animateContentSize(tween(ArxiverMotion.DURATION_MEDIUM))) {
        Text(
            text = abstract,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_ABSTRACT_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.action_less else R.string.action_more))
        }
    }
}

private const val COLLAPSED_ABSTRACT_LINES = 10

@Composable
private fun LibrarySection(
    entry: LibraryEntryEntity,
    onSetStatus: (String) -> Unit,
    onSetRating: (Int?) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(
                LibraryEntryEntity.STATUS_TO_READ to stringResource(R.string.library_filter_to_read),
                LibraryEntryEntity.STATUS_READING to stringResource(R.string.library_filter_reading),
                LibraryEntryEntity.STATUS_READ to stringResource(R.string.library_filter_read),
            ).forEach { (value, label) ->
                FilterChip(
                    selected = entry.status == value,
                    onClick = { onSetStatus(value) },
                    label = { Text(label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            (1..5).forEach { star ->
                val starDescription = pluralStringResource(R.plurals.cd_rate_star, star, star)
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSetRating(if (entry.rating == star) null else star)
                    },
                ) {
                    val filled = (entry.rating ?: 0) >= star
                    Crossfade(targetState = filled, label = "star-$star") { isFilled ->
                        Icon(
                            imageVector = if (isFilled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = starDescription,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    tags: List<TagEntity>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (Long) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        DetailHeading(stringResource(R.string.paper_tags_heading))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text("#${tag.name}") },
                    trailingIcon = {
                        IconButton(onClick = { onRemoveTag(tag.id) }) {
                            Icon(
                                Icons.Filled.Close,
                                stringResource(R.string.cd_remove_tag, tag.name),
                                modifier = Modifier.padding(0.dp),
                            )
                        }
                    },
                )
            }
            AssistChip(
                onClick = { adding = true },
                label = { Text(stringResource(R.string.paper_add_tag)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )
        }
        if (adding) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.paper_tag_hint)) },
                )
                TextButton(
                    onClick = {
                        onAddTag(newTag)
                        newTag = ""
                        adding = false
                    },
                    enabled = newTag.isNotBlank(),
                ) { Text(stringResource(R.string.action_add)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CollectionsSection(
    collections: List<dev.blokz.arxiver.core.database.entity.CollectionEntity>,
    memberIds: Set<Long>,
    onAdd: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        DetailHeading(stringResource(R.string.paper_collections_heading))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            collections.forEach { collection ->
                val selected = collection.id in memberIds
                FilterChip(
                    selected = selected,
                    onClick = { if (selected) onRemove(collection.id) else onAdd(collection.id) },
                    label = { Text(collection.name) },
                    leadingIcon =
                        if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                )
            }
            AssistChip(
                onClick = { creating = true },
                label = { Text(stringResource(R.string.paper_new_collection)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )
        }
        if (creating) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.library_collection_name_hint)) },
                )
                TextButton(
                    onClick = {
                        onCreate(newName)
                        newName = ""
                        creating = false
                    },
                    enabled = newName.isNotBlank(),
                ) { Text(stringResource(R.string.action_create)) }
            }
        }
    }
}

@Composable
private fun NotesSection(
    notes: List<NoteEntity>,
    onAddNote: (String) -> Unit,
    onDeleteNote: (Long) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        DetailHeading(stringResource(R.string.paper_notes_heading))
        notes.forEach { note ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onDeleteNote(note.id) }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.cd_delete_note))
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.paper_note_hint)) },
            )
            TextButton(
                onClick = {
                    onAddNote(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text(stringResource(R.string.action_add)) }
        }
    }
}

/**
 * The calm "more like this" loading/empty states (P-Discover2 PD.2). Unlike the old silent-hide, the section keeps
 * its heading and states the honest reason (indexing / none on device), so the absence is explained, not mysterious.
 */
@Composable
private fun RelatedNeighborsPlaceholder(message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        DetailHeading(stringResource(R.string.paper_related_heading))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RelatedSection(
    related: List<RelatedPaper>,
    onPaperClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        DetailHeading(stringResource(R.string.paper_related_heading))
        related.forEach { item ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPaperClick(item.paper.ref.storageId) }
                        .padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.paper.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                    Text(
                        text = item.paper.authors.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(start = Spacing.sm),
                ) {
                    Text(
                        text = "${(item.similarity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    ScoreBar(
                        score = item.similarity.toFloat(),
                        modifier =
                            Modifier
                                .padding(top = Spacing.xs)
                                .width(48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataSection(paper: Paper) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            // arXiv shows its versioned id; a non-arXiv paper shows its source (its DOI carries identity,
            // rendered by the DOI row below) — never a fake "arXiv ID" with a chemrxiv:… value or vN.
            if (paper.ref.origin == Source.ARXIV) {
                MetadataRow(
                    stringResource(R.string.paper_meta_arxiv_id),
                    "${paper.ref.storageId}v${paper.latestVersion}",
                )
            } else {
                MetadataRow(stringResource(R.string.paper_meta_source), paper.ref.origin.displayName)
            }
            paper.comment?.let { MetadataRow(stringResource(R.string.paper_meta_comment), it) }
            paper.journalRef?.let { MetadataRow(stringResource(R.string.paper_meta_journal), it) }
            paper.doi?.let { MetadataRow(stringResource(R.string.paper_meta_doi), it) }
            paper.citationCount?.let { MetadataRow(stringResource(R.string.paper_meta_citations), it.toString()) }
        }
    }
}

@Composable
private fun MetadataRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.65f),
        )
    }
}

/** Content-shaped placeholder for the detail load (SPEC-UI §4). */
@Composable
private fun DetailSkeleton() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SkeletonLine(widthFraction = 0.95f)
        SkeletonLine(widthFraction = 0.8f)
        SkeletonLine(widthFraction = 0.5f)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SkeletonLine(widthFraction = 0.2f)
            SkeletonLine(widthFraction = 0.2f)
        }
        repeat(6) { SkeletonLine(widthFraction = if (it == 5) 0.6f else 1f) }
    }
}

/**
 * The open-access resolver's primary action (P-OA): one morphing button that keeps a single slot across states,
 * so the row never gains or drops a button (no flash). [OaUiState.NotFound] renders NOTHING here — the caption
 * below the row states that outcome. Every state carries a content description; resolving + terminal states
 * announce politely so a TalkBack user learns the result without re-scrubbing.
 */
@Composable
private fun OaFulltextButton(
    oa: OaUiState,
    onResolve: () -> Unit,
) {
    when (oa) {
        OaUiState.Idle -> {
            val cd = stringResource(R.string.cd_find_oa_pdf)
            FilledTonalButton(onClick = onResolve, modifier = Modifier.semantics { contentDescription = cd }) {
                Icon(Icons.Filled.TravelExplore, contentDescription = null)
                Text(stringResource(R.string.action_find_oa_pdf), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
        OaUiState.Loading -> {
            val cd = stringResource(R.string.cd_resolving_oa)
            FilledTonalButton(
                onClick = {},
                enabled = false,
                modifier =
                    Modifier.semantics {
                        contentDescription = cd
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.oa_resolving), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
        is OaUiState.Ready -> {
            val uriHandler = LocalUriHandler.current
            val label = if (oa.versionOfRecord) R.string.action_open_oa_published else R.string.action_open_oa_free
            val cd =
                if (oa.versionOfRecord && oa.journalName != null) {
                    stringResource(R.string.cd_open_oa_pdf, oa.journalName)
                } else {
                    stringResource(R.string.cd_open_oa_pdf_no_journal)
                }
            FilledTonalButton(
                onClick = { uriHandler.openUri(oa.url) },
                modifier =
                    Modifier.semantics {
                        contentDescription = cd
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                Icon(Icons.Filled.LockOpen, contentDescription = null)
                Text(stringResource(label), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
        OaUiState.Error -> {
            FilledTonalButton(onClick = onResolve) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(stringResource(R.string.oa_error_retry), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
        OaUiState.NotFound -> Unit
    }
}

/** Provenance ("Published in …") or the calm "no free version" line beneath the OA action row (P-OA). */
@Composable
private fun OaFulltextCaption(oa: OaUiState) {
    val text =
        when (oa) {
            is OaUiState.Ready ->
                when {
                    !oa.versionOfRecord -> stringResource(R.string.oa_free_via_openalex)
                    oa.journalName != null -> stringResource(R.string.oa_published_in, oa.journalName)
                    else -> stringResource(R.string.oa_published_available)
                }
            OaUiState.NotFound -> stringResource(R.string.oa_not_found)
            else -> null
        }
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(top = Spacing.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * The two-tap "Discover similar" morph (P-Discover-MLT PDM.3, cloning the ratified P-OA pattern): Idle
 * searches (the ONE disclosed S2 call); Ready shows the honest post-dedup count and reopens the memoized
 * sheet (no re-egress); only a retryable Error re-fires. The honest empties + SeedNotFound render NO
 * button — the caption below the row carries the explanation (a control that can only no-op is noise).
 */
@Composable
private fun DiscoverSimilarButton(
    state: DiscoverUiState,
    onDiscover: () -> Unit,
    onShowResults: () -> Unit,
) {
    when (state) {
        DiscoverUiState.Idle -> {
            val cd = stringResource(R.string.cd_discover_similar)
            FilledTonalButton(onClick = onDiscover, modifier = Modifier.semantics { contentDescription = cd }) {
                Icon(Icons.Filled.ManageSearch, contentDescription = null)
                Text(
                    stringResource(R.string.action_discover_similar),
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
        }
        DiscoverUiState.Loading -> {
            val cd = stringResource(R.string.cd_discover_searching)
            FilledTonalButton(
                onClick = {},
                enabled = false,
                modifier =
                    Modifier.semantics {
                        contentDescription = cd
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.discover_searching),
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
        }
        is DiscoverUiState.Done ->
            when (val result = state.result) {
                is DiscoverResult.Ready -> {
                    val count = result.hits.size
                    val cd = stringResource(R.string.cd_show_similar)
                    FilledTonalButton(
                        onClick = onShowResults,
                        modifier =
                            Modifier.semantics {
                                contentDescription = cd
                                liveRegion = LiveRegionMode.Polite
                            },
                    ) {
                        Icon(Icons.Filled.ManageSearch, contentDescription = null)
                        Text(
                            pluralStringResource(R.plurals.action_show_similar, count, count),
                            modifier = Modifier.padding(start = Spacing.sm),
                        )
                    }
                }
                is DiscoverResult.Error -> {
                    FilledTonalButton(onClick = onDiscover) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text(
                            stringResource(R.string.discover_error_retry),
                            modifier = Modifier.padding(start = Spacing.sm),
                        )
                    }
                }
                // Honest terminals: no dead control — the caption explains the outcome.
                DiscoverResult.SeedNotFound,
                DiscoverResult.EmptyNoneReturned,
                DiscoverResult.EmptyAllLocal,
                -> Unit
            }
    }
}

/**
 * The discovery disclosure / outcome line under the action row (PDM.3). Pre-tap it IS the egress
 * disclosure ("sends only this paper's ID" — the tap is the opt-in, the P-OA resolve-on-tap precedent);
 * post-tap it names the honest outcome cause. Same never-reflow-the-row placement as [OaFulltextCaption].
 */
@Composable
private fun DiscoverCaption(state: DiscoverUiState) {
    val text =
        when (state) {
            DiscoverUiState.Idle, DiscoverUiState.Loading -> stringResource(R.string.discover_disclosure)
            is DiscoverUiState.Done ->
                when (state.result) {
                    is DiscoverResult.Ready -> stringResource(R.string.discover_ordered_by)
                    DiscoverResult.SeedNotFound -> stringResource(R.string.discover_seed_not_found)
                    DiscoverResult.EmptyNoneReturned -> stringResource(R.string.discover_empty_none)
                    DiscoverResult.EmptyAllLocal -> stringResource(R.string.discover_empty_all_local)
                    is DiscoverResult.Error -> stringResource(R.string.discover_error_caption)
                }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .padding(top = Spacing.sm)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The bounded discovery results (PDM.4). Everything is in hand from the one memoized response — this
 * sheet issues ZERO network on open or scroll. An arXiv row opens in-app (the destination detail screen
 * fetch-persists the native record); a non-arXiv row opens the browser (doi.org → the hit's OA PDF → its
 * S2 landing page) and persists nothing — the shipped read-only posture for non-arXiv S2 hits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverResultsSheet(
    hits: List<DiscoverHit>,
    onDismiss: () -> Unit,
    onOpenArxiv: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
        ) {
            Text(
                text = pluralStringResource(R.plurals.discover_sheet_count, hits.size, hits.size),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.discover_ordered_by),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm),
            )
            hits.forEach { hit ->
                val inApp = hit.arxivId != null
                val actionCd =
                    if (inApp) {
                        stringResource(R.string.cd_discover_open_in_app, hit.title)
                    } else {
                        stringResource(R.string.cd_discover_open_browser, hit.title)
                    }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                val arxivId = hit.arxivId
                                if (arxivId != null) {
                                    onOpenArxiv(arxivId.value)
                                } else {
                                    uriHandler.openUri(hit.browserFallbackUrl())
                                }
                            }
                            .padding(vertical = Spacing.sm)
                            .semantics(mergeDescendants = true) { contentDescription = actionCd },
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = hit.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val byline = hit.bylineText()
                        if (byline.isNotBlank()) {
                            Text(
                                text = byline,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!inApp) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.sm),
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PaperDetailContentPreview() {
    ArxiverTheme {
        PaperDetailContent(
            paper = PreviewFixtures.paper,
            entry =
                LibraryEntryEntity(
                    paperId = PreviewFixtures.paper.ref.storageId,
                    addedAt = 0L,
                    status = LibraryEntryEntity.STATUS_READING,
                    rating = 4,
                ),
            notes = emptyList(),
            tags = emptyList(),
            collections = emptyList(),
            memberCollectionIds = emptySet(),
            related = NeighborsResult.NoRelations,
            followedAuthors = emptySet(),
            onToggleAuthorFollow = {},
            oaState = OaUiState.Idle,
            onResolveOa = {},
            discoverState = DiscoverUiState.Idle,
            discoverable = true,
            onDiscover = {},
            onShowDiscoverResults = {},
            scrollState = rememberScrollState(),
            onOpenPdf = {},
            onOpenHtml = {},
            onPaperClick = {},
            onOpenConnections = {},
            onSetStatus = {},
            onSetRating = {},
            onAddNote = {},
            onDeleteNote = {},
            onAddTag = {},
            onRemoveTag = {},
            onAddToCollection = {},
            onRemoveFromCollection = {},
            onCreateCollection = {},
        )
    }
}

@Preview(showBackground = true, name = "OA published found")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "OA published found dark")
@Composable
private fun PaperDetailContentOaFoundPreview() {
    // P-OA found state: a browser-gated preprint resolved to its published, open-access version of record —
    // the "Open published PDF" button + provenance caption, in light and dark.
    ArxiverTheme {
        PaperDetailContent(
            paper = PreviewFixtures.chemrxivPaper,
            entry = null,
            notes = emptyList(),
            tags = emptyList(),
            collections = emptyList(),
            memberCollectionIds = emptySet(),
            related = NeighborsResult.NoRelations,
            followedAuthors = emptySet(),
            onToggleAuthorFollow = {},
            oaState =
                OaUiState.Ready(
                    url = "https://example.org/published.pdf",
                    journalName = "Environmental Microbiology",
                    versionOfRecord = true,
                ),
            onResolveOa = {},
            discoverState = DiscoverUiState.Done(DiscoverResult.EmptyAllLocal),
            discoverable = true,
            onDiscover = {},
            onShowDiscoverResults = {},
            scrollState = rememberScrollState(),
            onOpenPdf = {},
            onOpenHtml = {},
            onPaperClick = {},
            onOpenConnections = {},
            onSetStatus = {},
            onSetRating = {},
            onAddNote = {},
            onDeleteNote = {},
            onAddTag = {},
            onRemoveTag = {},
            onAddToCollection = {},
            onRemoveFromCollection = {},
            onCreateCollection = {},
        )
    }
}

@Preview(showBackground = true, name = "chemRxiv (non-arXiv)")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "chemRxiv (non-arXiv) dark")
@Composable
private fun PaperDetailContentChemRxivPreview() {
    // Non-arXiv surface (PS.1): source badge shown, no "Read HTML" button, Source + DOI metadata rows.
    ArxiverTheme {
        PaperDetailContent(
            paper = PreviewFixtures.chemrxivPaper,
            entry =
                LibraryEntryEntity(
                    paperId = PreviewFixtures.chemrxivPaper.ref.storageId,
                    addedAt = 0L,
                    status = LibraryEntryEntity.STATUS_TO_READ,
                    rating = null,
                ),
            notes = emptyList(),
            tags = emptyList(),
            collections = emptyList(),
            memberCollectionIds = emptySet(),
            related = NeighborsResult.NoRelations,
            followedAuthors = emptySet(),
            onToggleAuthorFollow = {},
            oaState = OaUiState.Idle,
            onResolveOa = {},
            discoverState = DiscoverUiState.Idle,
            discoverable = true,
            onDiscover = {},
            onShowDiscoverResults = {},
            scrollState = rememberScrollState(),
            onOpenPdf = {},
            onOpenHtml = {},
            onPaperClick = {},
            onOpenConnections = {},
            onSetStatus = {},
            onSetRating = {},
            onAddNote = {},
            onDeleteNote = {},
            onAddTag = {},
            onRemoveTag = {},
            onAddToCollection = {},
            onRemoveFromCollection = {},
            onCreateCollection = {},
        )
    }
}
